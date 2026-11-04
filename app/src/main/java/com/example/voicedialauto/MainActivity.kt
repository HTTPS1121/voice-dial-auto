package com.example.voicedialauto

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.voicedialauto.contacts.ContactEntry
import com.example.voicedialauto.flow.CachedContactsSource
import com.example.voicedialauto.flow.Caller
import com.example.voicedialauto.flow.DialConfig
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialError
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.flow.MainThreadScheduler
import com.example.voicedialauto.flow.MicState
import com.example.voicedialauto.speech.VoiceRecognizer
import com.example.voicedialauto.util.Permissions
import com.example.voicedialauto.speech.InterruptionMonitor
import com.example.voicedialauto.util.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import androidx.core.widget.NestedScrollView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Phone voice dialer (Material 3 / Material You UI).
 *
 * Lifecycle: the controller is driven by onStart/onStop, NOT onResume/onPause. v3 used
 * onResume/onPause, so anything that briefly paused the activity right after listening started
 * (mic-access / speech-services consent dialogs, a recognizer overlay, the notification shade,
 * the permission dialog) cancelled the recognizer and onResume immediately started it again →
 * endless start/stop loop. See DialController for the remaining guards.
 * All behaviour lives in the shared [DialController] (same as Android Auto);
 * this activity only renders [DialState] and forwards taps. No TTS on the phone (decision 4).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var recognizer: VoiceRecognizer
    private lateinit var controller: DialController

    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var micBar: MaterialCardView
    private lateinit var micBarText: TextView
    private lateinit var micBarIcon: ImageView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var mic: FloatingActionButton
    private lateinit var confirmPanel: View
    private lateinit var confirmName: TextView
    private lateinit var confirmNumber: TextView
    private lateinit var confirmProgress: LinearProgressIndicator
    private lateinit var listScroll: NestedScrollView
    private lateinit var listContainer: LinearLayout
    private lateinit var permissionPanel: View
    private lateinit var permMissing: TextView
    private lateinit var actions: LinearLayout
    private var lastSnack: Any? = null

    private var barPulse: ValueAnimator? = null
    private var countdownAnim: ObjectAnimator? = null
    private var countdownFor: String? = null
    private var askedPermissions = false
    private lateinit var interruptions: InterruptionMonitor
    private lateinit var tts: com.example.voicedialauto.speech.TtsSpeaker

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            controller.onPermissionsResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL // Hebrew-first UI (decision 10)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        com.example.voicedialauto.util.FileLog.log("lifecycle", "phone MainActivity onCreate", "phone")
        recognizer = VoiceRecognizer(
            this, null,
            endOfSpeechSilenceMs = { prefs.endOfSpeechSilenceMs },
            segmentedEnabled = { prefs.segmentedRecognition },
            vadSettings = { prefs.vadSettings },
            source = "phone",
        )
        tts = com.example.voicedialauto.speech.TtsSpeaker(this)
        controller = DialController(
            speech = recognizer,
            contacts = CachedContactsSource(this),
            caller = Caller { placeCall(it) },
            scheduler = MainThreadScheduler(),
            permissions = { Permissions.missing(this) },
            languages = prefs,
            config = DialConfig(afterCallQuietMs = 60_000, listLimit = 8),
            timings = prefs,
            worker = com.example.voicedialauto.flow.BackgroundWorker,
            speaker = tts,
            prompts = com.example.voicedialauto.flow.ResPrompts(this),
        )
        controller.matchSource = "phone"
        interruptions = InterruptionMonitor(this) { controller.onInterrupted() }
        tts.onFocusLoss = { if (interruptions.isInCallNow()) controller.onInterrupted() }
        bindViews()
        controller.onStateChanged = { render(it) }
        controller.onLevel = { db ->
            // VU feedback on the big mic: scale 1.0 … 1.18 with input level.
            val k = 1f + (db.coerceIn(0f, 10f) / 10f) * 0.18f
            mic.animate().scaleX(k).scaleY(k).setDuration(80).start()
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        micBar = findViewById(R.id.micBar)
        micBarText = findViewById(R.id.micBarText)
        micBarIcon = findViewById(R.id.micBarIcon)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        mic = findViewById(R.id.micButton)
        confirmPanel = findViewById(R.id.confirmPanel)
        confirmName = findViewById(R.id.confirmName)
        confirmNumber = findViewById(R.id.confirmNumber)
        confirmProgress = findViewById(R.id.confirmProgress)
        listScroll = findViewById(R.id.listScroll)
        listContainer = findViewById(R.id.listContainer)
        permissionPanel = findViewById(R.id.permissionPanel)
        permMissing = findViewById(R.id.permMissing)
        actions = findViewById(R.id.actions)

        mic.setOnClickListener { controller.micPressed() }
        micBar.setOnClickListener { controller.micPressed() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_language -> {
                    controller.toggleLanguage()
                    snack(getString(R.string.ph_language_switched, langName()))
                    true
                }
                R.id.action_settings -> {
                    controller.suppressNextResume()
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                else -> false
            }
        }
        findViewById<MaterialButton>(R.id.grantButton).setOnClickListener {
            permissionLauncher.launch(Permissions.TO_REQUEST.toTypedArray())
        }
        findViewById<MaterialButton>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onStart() {
        super.onStart()
        com.example.voicedialauto.util.FileLog.log("lifecycle", "phone onStart", "phone")
        interruptions.start()
        com.example.voicedialauto.speech.VoiceRecognizer.resetAudioPolicy() // v14: app opened → Silero gets a fresh chance
        controller.onVisible()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        controller.restart() // launcher icon tapped while open → new lookup
    }

    override fun onStop() {
        com.example.voicedialauto.util.FileLog.log("lifecycle", "phone onStop → cancel countdown / mic / TTS", "phone")
        controller.onHidden()
        interruptions.stop()
        super.onStop()
    }

    override fun onDestroy() {
        recognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    /** Phone call: ACTION_CALL when CALL_PHONE is granted, otherwise / on failure ACTION_DIAL. */
    private fun placeCall(entry: ContactEntry): Boolean {
        val uri = Uri.fromParts("tel", entry.number, null)
        val canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        return try {
            startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)); true
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_CALL failed, falling back to ACTION_DIAL", e)
            runCatching { startActivity(Intent(Intent.ACTION_DIAL, uri)) }.isSuccess
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun render(s: DialState) {
        if (s is DialState.Choosing || s is DialState.Confirm || s is DialState.NoMatch || s is DialState.Calling) {
            com.example.voicedialauto.flow.FlowTimeline.mark("shown", android.os.SystemClock.elapsedRealtime())
        }
        toolbar.menu.findItem(R.id.action_language)?.title = langName()
        permissionPanel.visibility = vis(s is DialState.NeedsPermissions)
        confirmPanel.visibility = vis(s is DialState.Confirm)
        val showList = s is DialState.Choosing
        listScroll.visibility = vis(showList)
        mic.visibility = vis(!showList && s !is DialState.NeedsPermissions && s !is DialState.Confirm)
        actions.removeAllViews()
        if (s is DialState.Listening) lastSnack = null
        if (s !is DialState.Confirm) stopCountdownAnim()
        if (s !is DialState.Listening) { mic.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }

        when (s) {
            is DialState.NeedsPermissions -> {
                micBar(null)
                status.setText(R.string.ph_perm_title); detail.text = ""
                permMissing.text = getString(R.string.ph_perm_missing, s.missing.joinToString(", ") { permLabel(it) })
                if (!askedPermissions) { askedPermissions = true; permissionLauncher.launch(Permissions.TO_REQUEST.toTypedArray()) }
            }
            is DialState.Idle -> {
                micBar(null); micColor(Mic.IDLE)
                status.setText(R.string.ph_idle)
                detail.text = s.lastCalled?.let { getString(R.string.ph_last_call, it) } ?: ""
            }
            is DialState.Listening -> {
                micBar(MicState.LISTENING, R.string.ph_mic_listening_name_plain); micColor(Mic.LISTENING)
                status.setText(R.string.ph_listening)
                detail.text = s.partial ?: getString(R.string.ph_listening_hint)
                button(R.string.ph_cancel, Style.OUTLINED) { controller.cancel() }
            }
            is DialState.Processing -> {
                micBar(null); micColor(Mic.IDLE)
                status.setText(R.string.ph_searching); detail.text = "\"${s.heard}\""
                recognizeAgainButton()
            }
            // v12: name + countdown + ONE button "בטל התקשרות". Our timer places the call.
            is DialState.Confirm -> {
                micBar(null)
                status.text = getString(R.string.ph_countdown, s.secondsLeft)
                detail.text = ""
                confirmName.text = s.entry.displayName
                confirmNumber.text = listOf(s.entry.number, s.entry.numberLabel).filter { it.isNotBlank() }.joinToString(" · ")
                startCountdownAnim(s)
                button(R.string.ph_cancel_call, Style.OUTLINED) { controller.cancel() }
            }
            is DialState.Calling -> {
                micBar(null); micColor(Mic.CALL)
                status.text = getString(R.string.ph_calling, s.entry.displayName)
                detail.text = s.entry.number
                recognizeAgainButton()
            }
            is DialState.Choosing -> {
                when (s.mic) {
                    MicState.LISTENING -> micBar(MicState.LISTENING, R.string.ph_mic_listening_plain)
                    MicState.SPEAKING -> micBar(MicState.SPEAKING, R.string.ph_mic_speaking_plain)
                    MicState.OFF -> micBar(MicState.OFF, R.string.ph_mic_off_plain)
                }
                status.setText(R.string.ph_choose)
                detail.text = s.partial ?: getString(R.string.ph_choose_hint)
                s.outOfRange?.let { n -> snackOnce("oor$n${s.items.size}", getString(R.string.ph_out_of_range, n)) }
                fillList(s.items, numbered = true)
                if (s.mic == MicState.LISTENING) button(R.string.ph_stop_listening, Style.TONAL) { controller.micPressed() }
                else button(R.string.ph_listen_again, Style.FILLED, icon = R.drawable.ic_mic) { controller.micPressed() }
                button(R.string.ph_search_other, Style.OUTLINED) { controller.recognizeAgain() }
            }
            // v12: simple no-match screen; scores/query are in Settings → אבחון only.
            is DialState.NoMatch -> {
                micBar(null); micColor(Mic.IDLE)
                status.text = getString(R.string.ph_no_match_simple)
                detail.text = ""
                button(R.string.ph_listen_again_nomatch, Style.FILLED, icon = R.drawable.ic_mic) { controller.recognizeAgain() }
            }
            is DialState.Failed -> {
                micBar(null); micColor(Mic.IDLE)
                status.text = when (s.error) {
                    DialError.NOT_AVAILABLE -> getString(R.string.ph_err_not_available)
                    DialError.NETWORK -> getString(R.string.ph_err_network)
                    DialError.LANGUAGE -> getString(R.string.ph_err_language)
                    DialError.NO_CONTACTS -> getString(R.string.ph_no_contacts)
                    DialError.CALL_FAILED -> getString(R.string.ph_call_failed, s.name ?: "")
                    DialError.OTHER -> getString(R.string.ph_err_other)
                }
                detail.text = ""
                if (s.error == DialError.CALL_FAILED) snackOnce("cf${s.name}", status.text)
                recognizeAgainButton()
                if (s.error == DialError.NETWORK || s.error == DialError.LANGUAGE || s.error == DialError.OTHER) otherLanguageButton()
            }
        }
    }

    private fun recognizeAgainButton() =
        button(R.string.ph_recognize_again, Style.FILLED, icon = R.drawable.ic_mic) { controller.recognizeAgain() }

    private fun otherLanguageButton() =
        button(if (prefs.secondaryLanguage == Prefs.LANG_EN) R.string.ph_try_en else R.string.ph_try_he, Style.OUTLINED) {
            controller.tryOtherLanguage()
        }

    private enum class Style { FILLED, TONAL, OUTLINED, CALL }
    private enum class Mic { IDLE, LISTENING, CALL }

    /** Material 3 buttons inflated from templates (filled / tonal / outlined) to get the real M3 styles. */
    private fun button(text: Int, style: Style, icon: Int? = null, onClick: () -> Unit) {
        val layout = when (style) {
            Style.FILLED, Style.CALL -> R.layout.btn_filled
            Style.TONAL -> R.layout.btn_tonal
            Style.OUTLINED -> R.layout.btn_outlined
        }
        val b = LayoutInflater.from(this).inflate(layout, actions, false) as MaterialButton
        b.setText(text)
        icon?.let { b.setIconResource(it) }
        if (style == Style.CALL) {
            b.backgroundTintList = ColorStateList.valueOf(callColor())
            val on = ContextCompat.getColor(this, R.color.vd_on_call)
            b.setTextColor(on); b.iconTint = ColorStateList.valueOf(on)
        }
        b.setOnClickListener { onClick() }
        actions.addView(b)
    }

    private fun attr(a: Int) = MaterialColors.getColor(root, a)
    private fun callColor() = MaterialColors.harmonizeWithPrimary(this, ContextCompat.getColor(this, R.color.vd_call))

    /** FAB colours from M3 colour roles: idle = primary container, listening = error container. */
    private fun micColor(m: Mic) {
        val (bg, fg, icon) = when (m) {
            Mic.IDLE -> Triple(attr(com.google.android.material.R.attr.colorPrimaryContainer),
                attr(com.google.android.material.R.attr.colorOnPrimaryContainer), R.drawable.ic_mic)
            Mic.LISTENING -> Triple(attr(com.google.android.material.R.attr.colorErrorContainer),
                attr(com.google.android.material.R.attr.colorOnErrorContainer), R.drawable.ic_mic)
            Mic.CALL -> Triple(callColor(), ContextCompat.getColor(this, R.color.vd_on_call), R.drawable.ic_call)
        }
        mic.backgroundTintList = ColorStateList.valueOf(bg)
        mic.imageTintList = ColorStateList.valueOf(fg)
        mic.setImageResource(icon)
        mic.contentDescription = getString(if (m == Mic.LISTENING) R.string.ph_stop_listening else R.string.ph_mic_desc)
    }

    /** Always-visible mic state banner (pulses while listening). null = hidden. */
    private fun micBar(state: MicState?, text: Int = 0) {
        barPulse?.cancel(); barPulse = null; micBar.alpha = 1f
        if (state == null) { micBar.visibility = View.GONE; return }
        micBar.visibility = View.VISIBLE
        micBarText.setText(text)
        val (bg, fg, icon) = when (state) {
            MicState.LISTENING -> Triple(com.google.android.material.R.attr.colorErrorContainer,
                com.google.android.material.R.attr.colorOnErrorContainer, R.drawable.ic_mic)
            MicState.SPEAKING -> Triple(com.google.android.material.R.attr.colorTertiaryContainer,
                com.google.android.material.R.attr.colorOnTertiaryContainer, R.drawable.ic_volume)
            MicState.OFF -> Triple(com.google.android.material.R.attr.colorSecondaryContainer,
                com.google.android.material.R.attr.colorOnSecondaryContainer, R.drawable.ic_mic_off)
        }
        micBar.setCardBackgroundColor(attr(bg))
        micBarText.setTextColor(attr(fg))
        micBarIcon.setImageResource(icon)
        micBarIcon.imageTintList = ColorStateList.valueOf(attr(fg))
        if (state == MicState.LISTENING) {
            barPulse = ObjectAnimator.ofFloat(micBar, View.ALPHA, 1f, 0.6f).apply {
                duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        }
    }

    private fun snack(text: CharSequence) = Snackbar.make(root, text, Snackbar.LENGTH_SHORT).show()

    /** Shows a Snackbar once per distinct [key] (render() runs on every state tick). */
    private fun snackOnce(key: Any, text: CharSequence) {
        if (lastSnack == key) return
        lastSnack = key
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    }

    private fun langName() = getString(
        if (prefs.primaryLanguage == Prefs.LANG_HE) R.string.ph_lang_badge_he else R.string.ph_lang_badge_en)

    private fun startCountdownAnim(s: DialState.Confirm) {
        if (countdownFor == s.entry.key) return // keep the running bar across 3→2→1 ticks
        stopCountdownAnim()
        countdownFor = s.entry.key
        val from = (1000 * s.remainingMs / s.totalMs.coerceAtLeast(1)).toInt()
        confirmProgress.progress = from
        countdownAnim = ObjectAnimator.ofInt(confirmProgress, "progress", from, 0).apply {
            duration = s.remainingMs; interpolator = LinearInterpolator(); start()
        }
    }

    private fun stopCountdownAnim() {
        countdownAnim?.cancel(); countdownAnim = null; countdownFor = null
    }

    private fun fillList(items: List<ContactEntry>, numbered: Boolean) {
        val tag = items to numbered
        if (listContainer.tag == tag) return
        listContainer.tag = tag
        listContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        items.forEachIndexed { i, c ->
            val row = inflater.inflate(R.layout.item_choice, listContainer, false)
            row.findViewById<TextView>(R.id.number).apply {
                text = (i + 1).toString(); visibility = vis(numbered)
            }
            row.findViewById<TextView>(R.id.name).text = c.displayName
            row.findViewById<TextView>(R.id.phone).text =
                if (c.numberLabel.isNotBlank()) "${c.number} · ${c.numberLabel}" else c.number
            row.contentDescription = "${i + 1}. ${c.displayName}"
            row.setOnClickListener { controller.pick(c) }
            listContainer.addView(row)
        }
    }

    private fun permLabel(p: String) = getString(when (p) {
        Manifest.permission.RECORD_AUDIO -> R.string.ph_perm_mic
        Manifest.permission.READ_CONTACTS -> R.string.ph_perm_contacts
        else -> R.string.ph_perm_phone
    })

    private fun vis(b: Boolean) = if (b) View.VISIBLE else View.GONE
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object { const val TAG = "MainActivity" }
}
