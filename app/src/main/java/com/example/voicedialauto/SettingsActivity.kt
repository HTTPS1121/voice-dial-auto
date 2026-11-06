package com.example.voicedialauto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.PhoneForwarded
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.voicedialauto.contacts.ContactIndex
import com.example.voicedialauto.contacts.ContactMatcher
import com.example.voicedialauto.contacts.ContactsCache
import com.example.voicedialauto.contacts.MatchAttemptLog
import com.example.voicedialauto.playreg.InstallSourceStatus
import com.example.voicedialauto.playreg.PlayRegistration
import com.example.voicedialauto.speech.RecognizerSessionLog
import com.example.voicedialauto.speech.VadStatus
import com.example.voicedialauto.speech.vad.VadSelfTest
import com.example.voicedialauto.ui.SettingsGroup
import com.example.voicedialauto.ui.SettingsSliderTile
import com.example.voicedialauto.ui.SettingsSwitchTile
import com.example.voicedialauto.ui.SettingsTile
import com.example.voicedialauto.ui.VoiceDialTheme
import com.example.voicedialauto.util.FileLog
import com.example.voicedialauto.util.Permissions
import com.example.voicedialauto.util.Prefs
import rikka.shizuku.Shizuku
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Google Pixel / Android 16 Material 3 Expressive Settings with sub-menus, colored circular icons,
 * segmented containers, theme picker, and live diagnostics.
 */
class SettingsActivity : ComponentActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private var refreshTick by mutableIntStateOf(0)

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { refreshTick++ }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        setContent {
            val tick = refreshTick
            var themeMode by remember(tick) { mutableStateOf(prefs.themeMode) }
            var dynamicColors by remember(tick) { mutableStateOf(prefs.dynamicColors) }

            VoiceDialTheme(themeMode = themeMode, useDynamicColor = dynamicColors) {
                SettingsNavigationHost(
                    prefs = prefs,
                    io = io,
                    refreshTick = tick,
                    onForceRefresh = { refreshTick++ },
                    onThemeChanged = { mode, dyn ->
                        prefs.themeMode = mode
                        prefs.dynamicColors = dyn
                        themeMode = mode
                        dynamicColors = dyn
                        refreshTick++
                    },
                    onFinishActivity = { finish() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }
        refreshTick++
    }

    override fun onStop() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        super.onStop()
    }
}

private enum class SettingsScreenId {
    ROOT,
    APPEARANCE,
    SPEECH,
    TIMING,
    DIAL,
    CONTACTS,
    ANDROID_AUTO,
    DIAGNOSTICS,
    PERMISSIONS,
    ABOUT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsNavigationHost(
    prefs: Prefs,
    io: java.util.concurrent.ExecutorService,
    refreshTick: Int,
    onForceRefresh: () -> Unit,
    onThemeChanged: (String, Boolean) -> Unit,
    onFinishActivity: () -> Unit,
) {
    var currentScreen by rememberSaveable { mutableStateOf(SettingsScreenId.ROOT) }

    BackHandler(enabled = currentScreen != SettingsScreenId.ROOT) {
        currentScreen = SettingsScreenId.ROOT
    }

    val title = when (currentScreen) {
        SettingsScreenId.ROOT -> stringResource(R.string.ph_settings)
        SettingsScreenId.APPEARANCE -> "עיצוב וערכת נושא"
        SettingsScreenId.SPEECH -> stringResource(R.string.st_hdr_speech)
        SettingsScreenId.TIMING -> stringResource(R.string.st_hdr_timing)
        SettingsScreenId.DIAL -> stringResource(R.string.st_hdr_dial)
        SettingsScreenId.CONTACTS -> stringResource(R.string.st_hdr_contacts)
        SettingsScreenId.ANDROID_AUTO -> stringResource(R.string.st_hdr_aa)
        SettingsScreenId.DIAGNOSTICS -> stringResource(R.string.st_hdr_diag)
        SettingsScreenId.PERMISSIONS -> stringResource(R.string.st_hdr_perm)
        SettingsScreenId.ABOUT -> stringResource(R.string.st_hdr_about)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = {
                            if (currentScreen == SettingsScreenId.ROOT) onFinishActivity()
                            else currentScreen = SettingsScreenId.ROOT
                        },
                        modifier = Modifier.padding(start = 8.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.st_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState != SettingsScreenId.ROOT) {
                    (slideInHorizontally(tween(240)) { -it / 4 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(150)))
                } else {
                    (slideInHorizontally(tween(240)) { it / 4 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 4 } + fadeOut(tween(150)))
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "settings_subscreen",
        ) { screen ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    when (screen) {
                        SettingsScreenId.ROOT -> RootSettingsContent(
                            prefs = prefs,
                            refreshTick = refreshTick,
                            onNavigate = { currentScreen = it },
                        )
                        SettingsScreenId.APPEARANCE -> AppearanceSubScreen(
                            prefs = prefs,
                            onThemeChanged = onThemeChanged,
                        )
                        SettingsScreenId.SPEECH -> SpeechSubScreen(
                            prefs = prefs,
                            onChanged = onForceRefresh,
                        )
                        SettingsScreenId.TIMING -> TimingSubScreen(
                            prefs = prefs,
                            onChanged = onForceRefresh,
                        )
                        SettingsScreenId.DIAL -> DialSubScreen(
                            prefs = prefs,
                            onChanged = onForceRefresh,
                        )
                        SettingsScreenId.CONTACTS -> ContactsSubScreen(
                            refreshTick = refreshTick,
                            onChanged = onForceRefresh,
                        )
                        SettingsScreenId.ANDROID_AUTO -> AndroidAutoSubScreen(
                            io = io,
                            refreshTick = refreshTick,
                            onChanged = onForceRefresh,
                        )
                        SettingsScreenId.DIAGNOSTICS -> DiagnosticsSubScreen()
                        SettingsScreenId.PERMISSIONS -> PermissionsSubScreen(
                            refreshTick = refreshTick,
                        )
                        SettingsScreenId.ABOUT -> AboutSubScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun RootSettingsContent(
    prefs: Prefs,
    refreshTick: Int,
    onNavigate: (SettingsScreenId) -> Unit,
) {
    val context = LocalContext.current
    val langBadge = stringResource(
        if (prefs.primaryLanguage == Prefs.LANG_HE) R.string.ph_lang_badge_he else R.string.ph_lang_badge_en
    )
    val themeSummary = remember(refreshTick) {
        val m = when (prefs.themeMode) {
            Prefs.THEME_DARK -> "כהה"
            Prefs.THEME_LIGHT -> "בהירה"
            else -> "לפי המערכת"
        }
        val dyn = if (prefs.dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " · Material You דינמי" else ""
        "$m$dyn"
    }
    val aaStatus = remember(refreshTick) { InstallSourceStatus.read(context) }
    val missingPerms = remember(refreshTick) { Permissions.missing(context) }
    val contactsCount = remember(refreshTick) { ContactsCache.count }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = "מראה והתאמה אישית") {
            SettingsTile(
                title = "עיצוב וערכת נושא",
                subtitle = themeSummary,
                icon = Icons.Rounded.Palette,
                iconBg = Color(0xFFE8DEF8),
                iconTint = Color(0xFF1D192B),
                showChevron = true,
                onClick = { onNavigate(SettingsScreenId.APPEARANCE) },
            )
        }

        SettingsGroup(title = "קול, זמנים וחיוג") {
            SettingsTile(
                title = stringResource(R.string.st_hdr_speech),
                subtitle = "${stringResource(R.string.st_hdr_speech_sum)} · $langBadge",
                icon = Icons.Rounded.RecordVoiceOver,
                iconBg = Color(0xFFD3E3FD),
                iconTint = Color(0xFF041E49),
                showChevron = true,
                onClick = { onNavigate(SettingsScreenId.SPEECH) },
            )
            SettingsTile(
                title = stringResource(R.string.st_hdr_timing),
                subtitle = "${prefs.nameWindowSeconds} שנ׳ שם · ${prefs.listWindowSeconds} שנ׳ רשימה",
                icon = Icons.Rounded.Timer,
                iconBg = Color(0xFFC2E7FF),
                iconTint = Color(0xFF001D35),
                showChevron = true,
                onClick = { onNavigate(SettingsScreenId.TIMING) },
            )
            SettingsTile(
                title = stringResource(R.string.st_hdr_dial),
                subtitle = stringResource(R.string.st_hdr_dial_sum),
                icon = Icons.Rounded.Call,
                iconBg = Color(0xFFC4EED0),
                iconTint = Color(0xFF072711),
                showChevron = true,
                onClick = { onNavigate(SettingsScreenId.DIAL) },
            )
        }

        SettingsGroup(title = "אנשי קשר ורכב") {
            SettingsTile(
                title = stringResource(R.string.st_hdr_contacts),
                subtitle = stringResource(R.string.st_contacts_count_sum, contactsCount),
                icon = Icons.Rounded.Contacts,
                iconBg = Color(0xFFFFD8E4),
                iconTint = Color(0xFF31111D),
                showChevron = true,
                onClick = { onNavigate(SettingsScreenId.CONTACTS) },
            )
            SettingsTile(
                title = stringResource(R.string.st_hdr_aa),
                subtitle = if (aaStatus.accepted) {
                    "מוכן ל-Android Auto ✓ (Play)"
                } else {
                    stringResource(R.string.st_hdr_aa_sum)
                },
                icon = Icons.Rounded.DirectionsCar,
                iconBg = if (aaStatus.accepted) Color(0xFFC4EED0) else Color(0xFFF9DEDC),
                iconTint = if (aaStatus.accepted) Color(0xFF072711) else Color(0xFF410E0B),
                showChevron = true,
                onClick = { onNavigate(SettingsScreenId.ANDROID_AUTO) },
            )
        }

        SettingsGroup(title = "מערכת ואבחון") {
            SettingsTile(
                title = stringResource(R.string.st_hdr_diag),
                subtitle = stringResource(R.string.st_hdr_diag_sum),
                icon = Icons.Rounded.BugReport,
                iconBg = Color(0xFFFFE0B2),
                iconTint = Color(0xFF4E2600),
                showChevron = true,
                onClick = { onNavigate(SettingsScreenId.DIAGNOSTICS) },
            )
            SettingsTile(
                title = stringResource(R.string.st_hdr_perm),
                subtitle = if (missingPerms.isEmpty()) {
                    stringResource(R.string.st_perm_ok)
                } else {
                    stringResource(R.string.st_hdr_perm_sum)
                },
                icon = Icons.Rounded.Security,
                iconBg = Color(0xFFD3E3FD),
                iconTint = Color(0xFF041E49),
                showChevron = true,
                onClick = { onNavigate(SettingsScreenId.PERMISSIONS) },
            )
            SettingsTile(
                title = stringResource(R.string.st_hdr_about),
                subtitle = BuildConfig.VERSION_NAME,
                icon = Icons.Rounded.Info,
                iconBg = Color(0xFFE0E2E8),
                iconTint = Color(0xFF191C20),
                showChevron = true,
                onClick = { onNavigate(SettingsScreenId.ABOUT) },
            )
        }
    }
}

// ------------------------------------------------------------------ Sub-screens

@Composable
private fun AppearanceSubScreen(
    prefs: Prefs,
    onThemeChanged: (String, Boolean) -> Unit,
) {
    var mode by remember { mutableStateOf(prefs.themeMode) }
    var dynamic by remember { mutableStateOf(prefs.dynamicColors) }

    val modes = listOf(
        Triple(Prefs.THEME_SYSTEM, "לפי המערכת (ברירת מחדל)", "מעבר אוטומטי בין מצב בהיר לכהה"),
        Triple(Prefs.THEME_LIGHT, "ערכת נושא בהירה", "מראה Google Material 3 בהיר ונקי"),
        Triple(Prefs.THEME_DARK, "ערכת נושא כהה", "מראה כהה מותאם לנהיגת לילה ולחיסכון בסוללה"),
    )

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = "מצב תצוגה") {
            modes.forEach { (key, label, sub) ->
                SettingsTile(
                    title = label,
                    subtitle = sub,
                    icon = Icons.Rounded.DarkMode,
                    onClick = {
                        mode = key
                        onThemeChanged(mode, dynamic)
                    },
                    trailing = {
                        RadioButton(
                            selected = mode == key,
                            onClick = {
                                mode = key
                                onThemeChanged(mode, dynamic)
                            },
                        )
                    },
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SettingsGroup(title = "צבעי Material You") {
                SettingsSwitchTile(
                    title = "צבעים דינמיים מהטפט",
                    subtitle = "התאמת פלטת הצבעים של האפליקציה לצבעי המערכת שלך ב-Android 12+",
                    icon = Icons.Rounded.ColorLens,
                    checked = dynamic,
                    onCheckedChange = {
                        dynamic = it
                        onThemeChanged(mode, dynamic)
                    },
                )
            }
        }
    }
}

@Composable
private fun SpeechSubScreen(
    prefs: Prefs,
    onChanged: () -> Unit,
) {
    var lang by remember { mutableStateOf(prefs.primaryLanguage) }
    var carMic by remember { mutableStateOf(prefs.useCarMic) }
    var segmented by remember { mutableStateOf(prefs.segmentedRecognition) }
    var vadEnabled by remember { mutableStateOf(prefs.vadEnabled) }
    var sensitivity by remember { mutableStateOf(prefs.vadSensitivityKey) }
    var hangoverTenths by remember { mutableIntStateOf(prefs.vadHangoverTenths) }

    var showLangDialog by remember { mutableStateOf(false) }
    var showSensDialog by remember { mutableStateOf(false) }

    val st = VadStatus
    val vadLastText = if (st.atMs == 0L) {
        stringResource(R.string.st_vad_last_none)
    } else {
        val name = when (st.engine) {
            "Silero" -> "Silero VAD"
            "WebRTC" -> "WebRTC VAD"
            else -> stringResource(R.string.st_vad_rms)
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(st.atMs))
        stringResource(R.string.st_vad_last, name, st.reason, time)
    }
    val sensObj = prefs.vadSensitivity
    val vadThresholds = stringResource(R.string.st_vad_info_sum, sensObj.start.toString(), sensObj.end.toString())

    val sensEntries = listOf(
        "low" to "נמוכה (רכב רועש: רק דיבור ברור נחשב)",
        "normal" to "רגילה (מומלץ)",
        "high" to "גבוהה (קול שקט / רחוק)",
    )

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = stringResource(R.string.st_cat_speech)) {
            SettingsTile(
                title = stringResource(R.string.st_lang),
                subtitle = if (lang == Prefs.LANG_HE) "עברית (he-IL)" else "English (en-US)",
                icon = Icons.Rounded.Language,
                showChevron = true,
                onClick = { showLangDialog = true },
            )
            SettingsSwitchTile(
                title = stringResource(R.string.st_car_mic),
                subtitle = stringResource(R.string.st_car_mic_sum),
                icon = Icons.Rounded.Mic,
                checked = carMic,
                onCheckedChange = {
                    carMic = it
                    prefs.useCarMic = it
                    onChanged()
                },
            )
            SettingsSwitchTile(
                title = stringResource(R.string.st_segmented),
                subtitle = stringResource(R.string.st_segmented_sum),
                icon = Icons.Rounded.GraphicEq,
                checked = segmented,
                onCheckedChange = {
                    segmented = it
                    prefs.segmentedRecognition = it
                    onChanged()
                },
            )
        }

        SettingsGroup(title = stringResource(R.string.st_vad_cat)) {
            SettingsSwitchTile(
                title = stringResource(R.string.st_vad_enabled),
                subtitle = stringResource(R.string.st_vad_enabled_sum),
                icon = Icons.Rounded.AutoAwesome,
                checked = vadEnabled,
                onCheckedChange = {
                    vadEnabled = it
                    prefs.vadEnabled = it
                    onChanged()
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_vad_sensitivity),
                subtitle = sensEntries.firstOrNull { it.first == sensitivity }?.second ?: sensitivity,
                icon = Icons.Rounded.Tune,
                enabled = vadEnabled,
                showChevron = true,
                onClick = { showSensDialog = true },
            )
            val tenthsFormatted = if (hangoverTenths % 10 == 0) {
                (hangoverTenths / 10).toString()
            } else {
                String.format(Locale.US, "%.1f", hangoverTenths / 10.0)
            }
            SettingsSliderTile(
                title = stringResource(R.string.st_vad_hangover),
                valueLabel = "${tenthsFormatted} שנ׳",
                subtitle = stringResource(R.string.st_vad_hangover_sum, tenthsFormatted),
                icon = Icons.Rounded.HourglassBottom,
                value = hangoverTenths.toFloat(),
                valueRange = Prefs.VAD_HANGOVER_MIN_TENTHS.toFloat()..Prefs.VAD_HANGOVER_MAX_TENTHS.toFloat(),
                steps = Prefs.VAD_HANGOVER_MAX_TENTHS - Prefs.VAD_HANGOVER_MIN_TENTHS - 1,
                onValueChange = {
                    val v = it.roundToInt()
                    hangoverTenths = v
                    prefs.vadHangoverTenths = v
                    onChanged()
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_vad_info),
                subtitle = "$vadLastText\n$vadThresholds",
                icon = Icons.Rounded.Speed,
            )
        }
    }

    if (showLangDialog) {
        AlertDialog(
            onDismissRequest = { showLangDialog = false },
            title = { Text(stringResource(R.string.st_lang)) },
            text = {
                Column {
                    listOf(Prefs.LANG_HE to "עברית", Prefs.LANG_EN to "English").forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = lang == code,
                                onClick = {
                                    lang = code
                                    prefs.primaryLanguage = code
                                    showLangDialog = false
                                    onChanged()
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLangDialog = false }) {
                    Text(stringResource(R.string.st_close))
                }
            },
        )
    }

    if (showSensDialog) {
        AlertDialog(
            onDismissRequest = { showSensDialog = false },
            title = { Text(stringResource(R.string.st_vad_sensitivity)) },
            text = {
                Column {
                    sensEntries.forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sensitivity == code,
                                onClick = {
                                    sensitivity = code
                                    prefs.vadSensitivityKey = code
                                    showSensDialog = false
                                    onChanged()
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSensDialog = false }) {
                    Text(stringResource(R.string.st_close))
                }
            },
        )
    }
}

@Composable
private fun TimingSubScreen(
    prefs: Prefs,
    onChanged: () -> Unit,
) {
    var nameWin by remember { mutableIntStateOf(prefs.nameWindowSeconds) }
    var listWin by remember { mutableIntStateOf(prefs.listWindowSeconds) }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = stringResource(R.string.st_cat_timing)) {
            SettingsSliderTile(
                title = stringResource(R.string.st_name_window),
                valueLabel = "$nameWin שנ׳",
                subtitle = stringResource(R.string.st_name_window_sum, nameWin.toString()),
                icon = Icons.Rounded.Timer,
                value = nameWin.toFloat(),
                valueRange = Prefs.NAME_WINDOW_MIN_S.toFloat()..Prefs.NAME_WINDOW_MAX_S.toFloat(),
                onValueChange = {
                    val v = it.roundToInt()
                    nameWin = v
                    prefs.nameWindowSeconds = v
                    onChanged()
                },
            )
            SettingsSliderTile(
                title = stringResource(R.string.st_list_window),
                valueLabel = "$listWin שנ׳",
                subtitle = stringResource(R.string.st_list_window_sum, listWin.toString()),
                icon = Icons.Rounded.HourglassBottom,
                value = listWin.toFloat(),
                valueRange = Prefs.LIST_WINDOW_MIN_S.toFloat()..Prefs.LIST_WINDOW_MAX_S.toFloat(),
                onValueChange = {
                    val v = (it / 5f).roundToInt() * 5
                    listWin = v
                    prefs.listWindowSeconds = v
                    onChanged()
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_timing_reset),
                subtitle = stringResource(R.string.st_timing_reset_sum),
                icon = Icons.Rounded.RestartAlt,
                onClick = {
                    prefs.resetTimings()
                    nameWin = prefs.nameWindowSeconds
                    listWin = prefs.listWindowSeconds
                    onChanged()
                },
            )
        }
    }
}

@Composable
private fun DialSubScreen(
    prefs: Prefs,
    onChanged: () -> Unit,
) {
    var countdownHalf by remember { mutableIntStateOf(prefs.countdownHalfSeconds) }
    var listCountdownHalf by remember { mutableIntStateOf(prefs.listCountdownHalfSeconds) }
    var actionCall by remember { mutableStateOf(prefs.useActionCall) }

    fun halfLabel(half: Int) = if (half % 2 == 0) {
        (half / 2).toString()
    } else {
        String.format(Locale.US, "%.1f", half / 2.0)
    }
    val halfFormatted = halfLabel(countdownHalf)
    val listHalfFormatted = halfLabel(listCountdownHalf)

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = stringResource(R.string.st_hdr_dial)) {
            SettingsSliderTile(
                title = stringResource(R.string.st_countdown),
                valueLabel = if (countdownHalf == 0) "מיידי" else "$halfFormatted שנ׳",
                subtitle = if (countdownHalf == 0) {
                    stringResource(R.string.st_countdown_zero)
                } else {
                    stringResource(R.string.st_countdown_sum, halfFormatted)
                },
                icon = Icons.Rounded.Timer,
                value = countdownHalf.toFloat(),
                valueRange = 0f..Prefs.COUNTDOWN_MAX_HALF_S.toFloat(),
                steps = Prefs.COUNTDOWN_MAX_HALF_S - 1,
                onValueChange = {
                    val v = it.roundToInt()
                    countdownHalf = v
                    prefs.countdownHalfSeconds = v
                    onChanged()
                },
            )
            SettingsSliderTile(
                title = stringResource(R.string.st_list_countdown),
                valueLabel = if (listCountdownHalf == 0) "מיידי" else "$listHalfFormatted שנ׳",
                subtitle = if (listCountdownHalf == 0) {
                    stringResource(R.string.st_list_countdown_zero)
                } else {
                    stringResource(R.string.st_list_countdown_sum, listHalfFormatted)
                },
                icon = Icons.Rounded.HourglassBottom,
                value = listCountdownHalf.toFloat(),
                valueRange = 0f..Prefs.COUNTDOWN_MAX_HALF_S.toFloat(),
                steps = Prefs.COUNTDOWN_MAX_HALF_S - 1,
                onValueChange = {
                    val v = it.roundToInt()
                    listCountdownHalf = v
                    prefs.listCountdownHalfSeconds = v
                    onChanged()
                },
            )
            SettingsSwitchTile(
                title = stringResource(R.string.st_action_call),
                subtitle = stringResource(R.string.st_action_call_sum),
                icon = Icons.Rounded.PhoneForwarded,
                checked = actionCall,
                onCheckedChange = {
                    actionCall = it
                    prefs.useActionCall = it
                    onChanged()
                },
            )
        }
    }
}

@Composable
private fun ContactsSubScreen(
    refreshTick: Int,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var count by remember(refreshTick) { mutableIntStateOf(ContactsCache.count) }
    var lastSyncMs by remember(refreshTick) { mutableStateOf(ContactsCache.lastSyncElapsedMs) }
    var showMatcherDialog by remember { mutableStateOf(false) }
    var matcherQuery by remember { mutableStateOf("") }
    var matcherResult by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun formatSyncAge(elapsedAt: Long): String {
        if (elapsedAt == 0L) return context.getString(R.string.st_sync_never)
        val ago = SystemClock.elapsedRealtime() - elapsedAt
        if (ago < 60_000L) return context.getString(R.string.st_sync_just_now)
        return context.getString(R.string.st_sync_minutes_ago, (ago / 60_000L).toInt().coerceAtLeast(1))
    }

    val syncSummary = if (lastSyncMs == 0L) {
        stringResource(R.string.st_sync_never)
    } else {
        stringResource(R.string.st_sync_done, count, formatSyncAge(lastSyncMs))
    }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = stringResource(R.string.st_cat_contacts)) {
            SettingsTile(
                title = stringResource(R.string.st_sync_contacts),
                subtitle = syncSummary,
                icon = Icons.Rounded.Sync,
                onClick = {
                    ContactsCache.refresh(context) { list ->
                        count = list.size
                        lastSyncMs = ContactsCache.lastSyncElapsedMs
                        val msg = context.getString(R.string.st_sync_done, list.size, formatSyncAge(lastSyncMs))
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        onChanged()
                    }
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_contacts_count),
                subtitle = stringResource(R.string.st_contacts_count_sum, count),
                icon = Icons.Rounded.Contacts,
            )
            SettingsTile(
                title = stringResource(R.string.st_matcher),
                subtitle = stringResource(R.string.st_matcher_sum),
                icon = Icons.Rounded.PersonSearch,
                showChevron = true,
                onClick = { showMatcherDialog = true },
            )
        }
    }

    fun runMatch(text: String) {
        if (text.isBlank() || Permissions.missing(context).isNotEmpty()) return
        ContactsCache.withContacts(context) { contacts ->
            val msg = when (val r = ContactMatcher().match(listOf(text), contacts)) {
                is ContactMatcher.Result.Single -> context.getString(
                    R.string.st_result_single,
                    "${r.match.contact.displayName} · ${r.match.contact.number}",
                )
                is ContactMatcher.Result.Multiple -> context.getString(
                    R.string.st_result_multi,
                    r.matches.mapIndexed { i, m ->
                        "${i + 1}. ${m.contact.displayName} · ${m.contact.number}"
                    }.joinToString("\n"),
                )
                ContactMatcher.Result.None -> context.getString(R.string.st_result_none)
            }
            matcherResult = "\"$text\"" to msg
        }
    }

    if (showMatcherDialog) {
        AlertDialog(
            onDismissRequest = { showMatcherDialog = false },
            title = { Text(stringResource(R.string.st_matcher)) },
            text = {
                OutlinedTextField(
                    value = matcherQuery,
                    onValueChange = { matcherQuery = it },
                    label = { Text(stringResource(R.string.st_matcher_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            showMatcherDialog = false
                            runMatch(matcherQuery)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMatcherDialog = false
                        runMatch(matcherQuery)
                    }
                ) {
                    Text(stringResource(R.string.st_search))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMatcherDialog = false }) {
                    Text(stringResource(R.string.st_close))
                }
            },
        )
    }

    matcherResult?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { matcherResult = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { matcherResult = null }) {
                    Text(stringResource(R.string.st_close))
                }
            },
        )
    }
}

@Composable
private fun AndroidAutoSubScreen(
    io: java.util.concurrent.ExecutorService,
    refreshTick: Int,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var localTick by remember(refreshTick) { mutableIntStateOf(refreshTick) }
    val status = remember(localTick) { InstallSourceStatus.read(context) }
    val none = stringResource(R.string.st_none)

    var showShizukuMissing by remember { mutableStateOf(false) }
    var showShizukuPerm by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    val statusSummary = if (status.accepted) {
        stringResource(R.string.st_aa_status_ok, status.initiating ?: none)
    } else {
        stringResource(
            R.string.st_aa_status_bad,
            status.installing ?: none,
            status.initiating ?: none,
            status.originating ?: none,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = stringResource(R.string.st_cat_aa)) {
            SettingsTile(
                title = stringResource(R.string.st_aa_status) + if (status.accepted) "  ✓" else "  ✗",
                subtitle = statusSummary,
                icon = if (status.accepted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                iconBg = if (status.accepted) Color(0xFFC4EED0) else Color(0xFFF9DEDC),
                iconTint = if (status.accepted) Color(0xFF072711) else Color(0xFF410E0B),
                onClick = {
                    localTick++
                    onChanged()
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_aa_reinstall),
                subtitle = stringResource(R.string.st_aa_reinstall_sum),
                icon = Icons.Rounded.Android,
                onClick = {
                    when {
                        !PlayRegistration.isShizukuRunning() -> showShizukuMissing = true
                        !PlayRegistration.hasShizukuPermission() -> showShizukuPerm = true
                        else -> {
                            isInstalling = true
                            val appCtx = context.applicationContext
                            io.execute {
                                val err = PlayRegistration.reinstallAsPlay(appCtx)
                                activity?.runOnUiThread {
                                    isInstalling = false
                                    when (err) {
                                        null -> infoDialog = context.getString(R.string.st_aa_reinstall) to
                                            context.getString(R.string.st_aa_install_launched)
                                        "APK_COPY_FAILED" ->
                                            Toast.makeText(context, R.string.st_aa_copy_failed, Toast.LENGTH_LONG).show()
                                        else ->
                                            Toast.makeText(context, R.string.st_aa_install_failed, Toast.LENGTH_LONG).show()
                                    }
                                    localTick++
                                    onChanged()
                                }
                            }
                        }
                    }
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_aa_adb),
                subtitle = stringResource(R.string.st_aa_adb_sum),
                icon = Icons.Rounded.ContentCopy,
                onClick = {
                    val cmd = PlayRegistration.adbCommand(context)
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("adb", cmd))
                    Toast.makeText(context, R.string.st_aa_adb_copied, Toast.LENGTH_SHORT).show()
                    infoDialog = context.getString(R.string.st_aa_adb) to
                        (cmd + "\n\n" + context.getString(R.string.st_aa_adb_sum))
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_aa_set_installer),
                subtitle = stringResource(R.string.st_aa_set_installer_sum),
                icon = Icons.Rounded.Terminal,
                onClick = {
                    when {
                        !PlayRegistration.isShizukuRunning() -> showShizukuMissing = true
                        !PlayRegistration.hasShizukuPermission() -> PlayRegistration.requestShizukuPermission()
                        else -> {
                            isInstalling = true
                            val appCtx = context.applicationContext
                            io.execute {
                                val result = PlayRegistration.setInstallerViaCmd(appCtx)
                                activity?.runOnUiThread {
                                    isInstalling = false
                                    infoDialog = context.getString(R.string.st_aa_cmd_result) to result
                                    localTick++
                                    onChanged()
                                }
                            }
                        }
                    }
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_aa_warning),
                subtitle = stringResource(R.string.st_aa_warning_sum),
                icon = Icons.Rounded.Info,
            )
        }
    }

    if (isInstalling) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.st_cat_aa)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.st_aa_installing))
                }
            },
            confirmButton = {},
        )
    }

    if (showShizukuPerm) {
        AlertDialog(
            onDismissRequest = { showShizukuPerm = false },
            title = { Text(stringResource(R.string.st_aa_shizuku_need_perm)) },
            text = { Text(stringResource(R.string.st_aa_shizuku_need_perm_body)) },
            confirmButton = {
                Button(onClick = {
                    showShizukuPerm = false
                    PlayRegistration.requestShizukuPermission()
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showShizukuPerm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showShizukuMissing) {
        AlertDialog(
            onDismissRequest = { showShizukuMissing = false },
            title = { Text(stringResource(R.string.st_aa_shizuku_missing_title)) },
            text = { Text(stringResource(R.string.st_aa_shizuku_missing_body)) },
            confirmButton = {
                Button(onClick = {
                    showShizukuMissing = false
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rushiranpise/Shizuku-Next/releases"))
                    )
                }) {
                    Text(stringResource(R.string.st_aa_shizuku_download))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showShizukuMissing = false
                        val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (launch != null) context.startActivity(launch)
                        else context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rushiranpise/Shizuku-Next/releases"))
                        )
                    }) {
                        Text(stringResource(R.string.st_aa_shizuku_open))
                    }
                    TextButton(onClick = { showShizukuMissing = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            },
        )
    }

    infoDialog?.let { (title, msg) ->
        AlertDialog(
            onDismissRequest = { infoDialog = null; localTick++ },
            title = { Text(title) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { infoDialog = null; localTick++ }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun DiagnosticsSubScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var dialogContent by remember { mutableStateOf<Triple<String, String, (() -> Unit)?>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = stringResource(R.string.st_hdr_diag)) {
            SettingsTile(
                title = stringResource(R.string.st_match_log),
                subtitle = stringResource(R.string.st_match_log_sum),
                icon = Icons.Rounded.History,
                showChevron = true,
                onClick = {
                    val items = MatchAttemptLog.snapshot()
                    val sessions = RecognizerSessionLog.snapshot()
                    if (items.isEmpty() && sessions.isEmpty()) {
                        dialogContent = Triple(
                            context.getString(R.string.st_match_log),
                            context.getString(R.string.st_match_log_empty),
                            null,
                        )
                    } else {
                        val sessionText = if (sessions.isEmpty()) "" else
                            "MIC SESSIONS (newest first)\n" + sessions.take(15).joinToString("\n") { e ->
                                "${if (e.continuous) "cont" else "name"}${if (e.reused) "·reuse" else ""}${if (e.carMic) "·carmic" else ""} " +
                                    "${e.durationMs}ms ${e.end}${if (e.speech) " speech=\"${e.partial}\"" else " no-speech"}" +
                                    (if (e.events.isNotBlank()) "\n   ${e.events}" else "")
                            } + "\n\n"
                        val perf = "contacts load ${ContactsCache.lastLoadMs}ms · index build " +
                            "${ContactIndex.lastBuildMs}ms · ${ContactsCache.count} contacts\n\n"
                        val text = perf + sessionText + items.joinToString("\n\n") { a ->
                            val top = a.top.joinToString { pair ->
                                "${pair.first} (${"%.2f".format(Locale.US, pair.second)})"
                            }
                            "[${a.source.ifBlank { "?" }}] ${a.verdict} · contacts=${a.contactsCount}\n" +
                                "final: ${a.hypotheses.joinToString(" | ").ifBlank { "—" }}\n" +
                                "partial: ${a.lastPartial ?: "—"}\n" +
                                "query: ${a.extracted.joinToString(" | ").ifBlank { "—" }}\n" +
                                "top: $top\n" +
                                "decision: ${a.decision.ifBlank { "—" }}" +
                                (a.timeline?.let { "\ntiming(ms): ${it.format()}" } ?: "")
                        }
                        dialogContent = Triple(
                            context.getString(R.string.st_match_log),
                            text,
                            {
                                MatchAttemptLog.clear()
                                RecognizerSessionLog.clear()
                            },
                        )
                    }
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_silero_test),
                subtitle = stringResource(R.string.st_silero_test_sum),
                icon = Icons.Rounded.Science,
                onClick = {
                    val title = context.getString(R.string.st_silero_test)
                    dialogContent = Triple(title, context.getString(R.string.st_silero_running), null)
                    val appCtx = context.applicationContext
                    Thread({
                        val text = runCatching {
                            val r = VadSelfTest.run(appCtx)
                            FileLog.log("vad", "self-test: $r")
                            r.toString()
                        }.getOrElse { "FAILED: ${it.javaClass.simpleName}: ${it.message}" }
                        activity?.runOnUiThread {
                            dialogContent = Triple(title, text, null)
                        }
                    }, "silero-selftest").start()
                },
            )
        }

        SettingsGroup(title = stringResource(R.string.st_filelog)) {
            SettingsTile(
                title = stringResource(R.string.st_log_view),
                subtitle = stringResource(R.string.st_log_view_sum),
                icon = Icons.Rounded.Description,
                showChevron = true,
                onClick = {
                    val text = FileLog.tail(60_000).ifBlank { context.getString(R.string.st_log_empty) }
                    dialogContent = Triple(
                        context.getString(R.string.st_log_view),
                        text.takeLast(60_000),
                        null,
                    )
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_log_copy),
                icon = Icons.Rounded.ContentCopy,
                onClick = {
                    val text = FileLog.tail(200_000)
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("voicedial.log", text))
                    Toast.makeText(
                        context,
                        context.getString(R.string.st_log_copied, text.length / 1024),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_log_share),
                subtitle = stringResource(R.string.st_log_share_sum),
                icon = Icons.Rounded.Share,
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val out = File(
                        File(context.cacheDir, "logshare").apply { mkdirs(); listFiles()?.forEach { it.delete() } },
                        "voicedial-$stamp.txt",
                    )
                    FileLog.exportTo(out)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.apkfiles", out)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, out.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.st_log_share)))
                },
            )
            SettingsTile(
                title = stringResource(R.string.st_log_clear),
                icon = Icons.Rounded.DeleteOutline,
                onClick = {
                    FileLog.clear()
                    MatchAttemptLog.clear()
                    RecognizerSessionLog.clear()
                    Toast.makeText(context, R.string.st_log_cleared, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    dialogContent?.let { (title, body, onClear) ->
        AlertDialog(
            onDismissRequest = { dialogContent = null },
            title = { Text(title) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogContent = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = if (onClear != null) {
                {
                    TextButton(onClick = {
                        onClear()
                        dialogContent = null
                    }) {
                        Text(stringResource(R.string.st_match_log_clear))
                    }
                }
            } else null,
        )
    }
}

@Composable
private fun PermissionsSubScreen(refreshTick: Int) {
    val context = LocalContext.current
    val missing = remember(refreshTick) { Permissions.missing(context) }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = stringResource(R.string.st_cat_perm)) {
            SettingsTile(
                title = stringResource(R.string.st_perm),
                subtitle = if (missing.isEmpty()) {
                    stringResource(R.string.st_perm_ok)
                } else {
                    stringResource(
                        R.string.ph_perm_missing,
                        missing.joinToString(", ") { it.substringAfterLast('.') },
                    )
                },
                icon = if (missing.isEmpty()) Icons.Rounded.Verified else Icons.Rounded.Security,
                showChevron = true,
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun AboutSubScreen() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: BuildConfig.VERSION_NAME
    }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsGroup(title = stringResource(R.string.st_hdr_about)) {
            SettingsTile(
                title = stringResource(R.string.st_version),
                subtitle = "$versionName (${BuildConfig.APPLICATION_ID})",
                icon = Icons.Rounded.Info,
            )
            SettingsTile(
                title = "Silero VAD v4 & ONNX Runtime",
                subtitle = "זיהוי סוף דיבור מבוסס למידת מכונה על המכשיר (MIT License)",
                icon = Icons.Rounded.AutoAwesome,
            )
            SettingsTile(
                title = "עיצוב Material 3 Expressive",
                subtitle = "בנוי ב-Jetpack Compose לפי הנחיות העיצוב העדכניות של Google (Material You)",
                icon = Icons.Rounded.Palette,
            )
        }
    }
}
