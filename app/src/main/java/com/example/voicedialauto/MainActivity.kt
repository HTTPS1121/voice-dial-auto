package com.example.voicedialauto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.voicedialauto.contacts.ContactEntry
import com.example.voicedialauto.flow.BackgroundWorker
import com.example.voicedialauto.flow.CachedContactsSource
import com.example.voicedialauto.flow.Caller
import com.example.voicedialauto.flow.DialConfig
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialError
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.flow.FlowTimeline
import com.example.voicedialauto.flow.MainThreadScheduler
import com.example.voicedialauto.flow.MicState
import com.example.voicedialauto.flow.ResPrompts
import com.example.voicedialauto.speech.InterruptionMonitor
import com.example.voicedialauto.speech.TtsSpeaker
import com.example.voicedialauto.speech.VoiceRecognizer
import com.example.voicedialauto.ui.VoiceDialTheme
import com.example.voicedialauto.util.FileLog
import com.example.voicedialauto.util.Permissions
import com.example.voicedialauto.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone voice dialer — Google Material 3 Expressive (Jetpack Compose).
 * Driven by [DialController] (shared with Android Auto).
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var recognizer: VoiceRecognizer
    private lateinit var controller: DialController
    private lateinit var interruptions: InterruptionMonitor
    private lateinit var tts: TtsSpeaker

    private var uiState by mutableStateOf<DialState>(DialState.Idle())
    private var micLevelDb by mutableFloatStateOf(0f)
    private var themeTick by mutableIntStateOf(0)
    private var askedPermissions = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            controller.onPermissionsResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        FileLog.log("lifecycle", "phone MainActivity onCreate", "phone")
        recognizer = VoiceRecognizer(
            this, null,
            endOfSpeechSilenceMs = { prefs.endOfSpeechSilenceMs },
            segmentedEnabled = { prefs.segmentedRecognition },
            vadSettings = { prefs.vadSettings },
            source = "phone",
        )
        tts = TtsSpeaker(this)
        controller = DialController(
            speech = recognizer,
            contacts = CachedContactsSource(this),
            caller = Caller { placeCall(it) },
            scheduler = MainThreadScheduler(),
            permissions = { Permissions.missing(this) },
            languages = prefs,
            config = DialConfig(afterCallQuietMs = 60_000, listLimit = 8),
            timings = prefs,
            worker = BackgroundWorker,
            speaker = tts,
            prompts = ResPrompts(this),
        )
        controller.matchSource = "phone"
        interruptions = InterruptionMonitor(this) { controller.onInterrupted() }
        tts.onFocusLoss = { if (interruptions.isInCallNow()) controller.onInterrupted() }

        controller.onStateChanged = { s ->
            if (s is DialState.Choosing || s is DialState.Confirm || s is DialState.NoMatch || s is DialState.Calling) {
                FlowTimeline.mark("shown", SystemClock.elapsedRealtime())
            }
            if (s !is DialState.Listening) micLevelDb = 0f
            uiState = s
            if (s is DialState.NeedsPermissions && !askedPermissions) {
                askedPermissions = true
                permissionLauncher.launch(Permissions.TO_REQUEST.toTypedArray())
            }
        }
        controller.onLevel = { db ->
            micLevelDb = db
        }

        setContent {
            val tick = themeTick
            val themeMode = remember(tick) { prefs.themeMode }
            val dynamicColors = remember(tick) { prefs.dynamicColors }
            VoiceDialTheme(themeMode = themeMode, useDynamicColor = dynamicColors) {
                DialerMainScreen(
                    state = uiState,
                    micLevelDb = micLevelDb,
                    primaryLanguage = prefs.primaryLanguage,
                    secondaryLanguage = prefs.secondaryLanguage,
                    onMicClick = { controller.micPressed() },
                    onCancel = { controller.cancel() },
                    onCallNow = { controller.callNow() },
                    onRecognizeAgain = { controller.recognizeAgain() },
                    onTryOtherLanguage = { controller.tryOtherLanguage() },
                    onToggleLanguage = {
                        controller.toggleLanguage()
                        themeTick++
                    },
                    onPickContact = { controller.pick(it) },
                    onOpenSettings = {
                        controller.suppressNextResume()
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onRequestPermissions = {
                        permissionLauncher.launch(Permissions.TO_REQUEST.toTypedArray())
                    },
                    onOpenSystemSettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            )
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        themeTick++
        FileLog.log("lifecycle", "phone onStart", "phone")
        interruptions.start()
        VoiceRecognizer.resetAudioPolicy()
        controller.onVisible()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        controller.restart()
    }

    override fun onStop() {
        FileLog.log("lifecycle", "phone onStop -> cancel countdown / mic / TTS", "phone")
        controller.onHidden()
        interruptions.stop()
        super.onStop()
    }

    override fun onDestroy() {
        recognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    private fun placeCall(entry: ContactEntry): Boolean {
        val uri = Uri.fromParts("tel", entry.number, null)
        val canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        return try {
            startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
            true
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_CALL failed, falling back to ACTION_DIAL", e)
            runCatching { startActivity(Intent(Intent.ACTION_DIAL, uri)) }.isSuccess
        }
    }

    private companion object { const val TAG = "MainActivity" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialerMainScreen(
    state: DialState,
    micLevelDb: Float,
    primaryLanguage: String,
    secondaryLanguage: String,
    onMicClick: () -> Unit,
    onCancel: () -> Unit,
    onCallNow: () -> Unit,
    onRecognizeAgain: () -> Unit,
    onTryOtherLanguage: () -> Unit,
    onToggleLanguage: () -> Unit,
    onPickContact: (ContactEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val langLabel = stringResource(
        if (primaryLanguage == Prefs.LANG_HE) R.string.ph_lang_badge_he else R.string.ph_lang_badge_en
    )
    val langSwitchedTemplate = stringResource(R.string.ph_language_switched, langLabel)

    val outOfRangeNumber = (state as? DialState.Choosing)?.outOfRange
    val outOfRangeMsg = outOfRangeNumber?.let { stringResource(R.string.ph_out_of_range, it) }
    LaunchedEffect(outOfRangeNumber) {
        if (outOfRangeMsg != null) {
            snackbarHostState.showSnackbar(outOfRangeMsg)
        }
    }

    val callFailedName = (state as? DialState.Failed)?.takeIf { it.error == DialError.CALL_FAILED }?.name
    val callFailedMsg = callFailedName?.let { stringResource(R.string.ph_call_failed, it) }
    LaunchedEffect(callFailedName) {
        if (callFailedMsg != null) {
            snackbarHostState.showSnackbar(callFailedMsg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                navigationIcon = {
                    Surface(
                        onClick = {
                            onToggleLanguage()
                            scope.launch {
                                snackbarHostState.showSnackbar(langSwitchedTemplate)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = langLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.padding(end = 8.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.ph_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Mic Status Pill Banner
            val bannerState: Pair<MicState, Int>? = when (state) {
                is DialState.Listening -> MicState.LISTENING to R.string.ph_mic_listening_name_plain
                is DialState.Choosing -> when (state.mic) {
                    MicState.LISTENING -> MicState.LISTENING to R.string.ph_mic_listening_plain
                    MicState.SPEAKING -> MicState.SPEAKING to R.string.ph_mic_speaking_plain
                    MicState.OFF -> MicState.OFF to R.string.ph_mic_off_plain
                }
                else -> null
            }

            AnimatedVisibility(visible = bannerState != null) {
                bannerState?.let { (micState, textRes) ->
                    MicStatusPill(
                        micState = micState,
                        text = stringResource(textRes),
                        onClick = onMicClick,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Main Content by State
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                modifier = Modifier.weight(1f),
                label = "dial_state",
            ) { s ->
                when (s) {
                    is DialState.NeedsPermissions -> PermissionsCard(
                        missing = s.missing,
                        onGrant = onRequestPermissions,
                        onOpenSettings = onOpenSystemSettings,
                    )
                    is DialState.Confirm -> ConfirmCountdownView(
                        state = s,
                        onCancel = onCancel,
                        onCallNow = onCallNow,
                    )
                    is DialState.Choosing -> ChoosingListView(
                        state = s,
                        onPick = onPickContact,
                        onMicToggle = onMicClick,
                        onSearchOther = onRecognizeAgain,
                    )
                    else -> CenterOrbView(
                        state = s,
                        micLevelDb = micLevelDb,
                        secondaryLanguage = secondaryLanguage,
                        onMicClick = onMicClick,
                        onCancel = onCancel,
                        onRecognizeAgain = onRecognizeAgain,
                        onTryOtherLanguage = onTryOtherLanguage,
                    )
                }
            }
        }
    }
}

@Composable
private fun MicStatusPill(
    micState: MicState,
    text: String,
    onClick: () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "pill_pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (micState == MicState.LISTENING) 0.65f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    val (bg, fg, icon) = when (micState) {
        MicState.LISTENING -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Rounded.Mic,
        )
        MicState.SPEAKING -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.AutoMirrored.Rounded.VolumeUp,
        )
        MicState.OFF -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.MicOff,
        )
    }

    Surface(
        onClick = onClick,
        color = bg.copy(alpha = pulseAlpha),
        contentColor = fg,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CenterOrbView(
    state: DialState,
    micLevelDb: Float,
    secondaryLanguage: String,
    onMicClick: () -> Unit,
    onCancel: () -> Unit,
    onRecognizeAgain: () -> Unit,
    onTryOtherLanguage: () -> Unit,
) {
    val statusText = when (state) {
        is DialState.Idle -> stringResource(R.string.ph_idle)
        is DialState.Listening -> stringResource(R.string.ph_listening)
        is DialState.Processing -> stringResource(R.string.ph_searching)
        is DialState.Calling -> stringResource(R.string.ph_calling, state.entry.displayName)
        is DialState.NoMatch -> stringResource(R.string.ph_no_match_simple)
        is DialState.Failed -> when (state.error) {
            DialError.NOT_AVAILABLE -> stringResource(R.string.ph_err_not_available)
            DialError.NETWORK -> stringResource(R.string.ph_err_network)
            DialError.LANGUAGE -> stringResource(R.string.ph_err_language)
            DialError.NO_CONTACTS -> stringResource(R.string.ph_no_contacts)
            DialError.CALL_FAILED -> stringResource(R.string.ph_call_failed, state.name ?: "")
            DialError.OTHER -> stringResource(R.string.ph_err_other)
        }
        else -> ""
    }

    val detailText = when (state) {
        is DialState.Idle -> state.lastCalled?.let { stringResource(R.string.ph_last_call, it) } ?: ""
        is DialState.Listening -> state.partial ?: stringResource(R.string.ph_listening_hint)
        is DialState.Processing -> "\"${state.heard}\""
        is DialState.Calling -> state.entry.number
        else -> ""
    }

    val isListening = state is DialState.Listening
    val isCalling = state is DialState.Calling
    val isError = state is DialState.NoMatch || state is DialState.Failed

    val scale by animateFloatAsState(
        targetValue = if (isListening) 1f + (micLevelDb.coerceIn(0f, 10f) / 10f) * 0.22f else 1f,
        animationSpec = tween(90),
        label = "vu_scale",
    )

    val infinite = rememberInfiniteTransition(label = "orb_halo")
    val haloScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.24f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo_scale",
    )

    val (orbBg, orbFg, orbIcon) = when {
        isCalling -> Triple(
            Color(0xFF1B873B),
            Color.White,
            Icons.Rounded.PhoneInTalk,
        )
        isListening -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Rounded.Mic,
        )
        isError -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.Mic,
        )
        else -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Rounded.Mic,
        )
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (detailText.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Google Assistant / Pixel Expressive Orb Button
        Box(contentAlignment = Alignment.Center) {
            if (isListening) {
                Box(
                    modifier = Modifier
                        .size(188.dp)
                        .scale(haloScale)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)),
                )
            }
            Surface(
                onClick = onMicClick,
                shape = RoundedCornerShape(44.dp),
                color = orbBg,
                contentColor = orbFg,
                shadowElevation = if (isListening) 12.dp else 4.dp,
                modifier = Modifier
                    .size(152.dp)
                    .scale(scale),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = orbIcon,
                        contentDescription = stringResource(
                            if (isListening) R.string.ph_stop_listening else R.string.ph_mic_desc
                        ),
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Bottom Action Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                is DialState.Listening -> {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ph_cancel),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                is DialState.Processing, is DialState.Calling -> {
                    Button(
                        onClick = onRecognizeAgain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ph_recognize_again),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                is DialState.NoMatch -> {
                    Button(
                        onClick = onRecognizeAgain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ph_listen_again_nomatch),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                is DialState.Failed -> {
                    Button(
                        onClick = onRecognizeAgain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ph_recognize_again),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (state.error == DialError.NETWORK ||
                        state.error == DialError.LANGUAGE ||
                        state.error == DialError.OTHER
                    ) {
                        OutlinedButton(
                            onClick = onTryOtherLanguage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Icon(Icons.Rounded.Language, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    if (secondaryLanguage == Prefs.LANG_EN) R.string.ph_try_en else R.string.ph_try_he
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ConfirmCountdownView(
    state: DialState.Confirm,
    onCancel: () -> Unit,
    onCallNow: () -> Unit,
) {
    var progress by remember(state.entry.key) {
        mutableFloatStateOf((state.remainingMs.toFloat() / state.totalMs.coerceAtLeast(1L)).coerceIn(0f, 1f))
    }

    LaunchedEffect(state.entry.key) {
        val startMs = SystemClock.elapsedRealtime()
        val initialRemaining = state.remainingMs
        val total = state.totalMs.coerceAtLeast(1L)
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val rem = (initialRemaining - elapsed).coerceAtLeast(0L)
            progress = (rem.toFloat() / total).coerceIn(0f, 1f)
            if (rem <= 0L) break
            delay(16L)
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(50, easing = LinearEasing),
        label = "countdown_progress",
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.ph_countdown, state.secondsLeft),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )

        ElevatedCard(
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = state.entry.displayName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))

                val sub = listOf(state.entry.number, state.entry.numberLabel)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                Text(
                    text = sub,
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(24.dp))

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CircleShape),
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onCallNow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Icon(Icons.Rounded.Call, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.ph_call_now),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.ph_cancel_call),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ChoosingListView(
    state: DialState.Choosing,
    onPick: (ContactEntry) -> Unit,
    onMicToggle: () -> Unit,
    onSearchOther: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.ph_choose),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.partial ?: stringResource(R.string.ph_choose_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            itemsIndexed(state.items, key = { i, c -> "${i}_${c.key}" }) { index, contact ->
                ContactChoiceRow(
                    index = index + 1,
                    contact = contact,
                    onClick = { onPick(contact) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.mic == MicState.LISTENING) {
                FilledTonalButton(
                    onClick = onMicToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Rounded.MicOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ph_stop_listening),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            } else {
                Button(
                    onClick = onMicToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Rounded.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ph_listen_again),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            OutlinedButton(
                onClick = onSearchOther,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ph_search_other),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun ContactChoiceRow(
    index: Int,
    contact: ContactEntry,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val phoneLine = if (contact.numberLabel.isNotBlank()) {
                    "${contact.number} · ${contact.numberLabel}"
                } else {
                    contact.number
                }
                Text(
                    text = phoneLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(12.dp))

            FilledIconButton(
                onClick = onClick,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Call,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    missing: List<String>,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val missingLabels = missing.map { p ->
        when (p) {
            Manifest.permission.RECORD_AUDIO -> stringResource(R.string.ph_perm_mic)
            Manifest.permission.READ_CONTACTS -> stringResource(R.string.ph_perm_contacts)
            else -> stringResource(R.string.ph_perm_phone)
        }
    }.joinToString(", ")

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.ph_perm_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.ph_perm_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (missingLabels.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ph_perm_missing, missingLabels),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onGrant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ph_perm_grant),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.ph_perm_settings))
                }
            }
        }
    }
}
