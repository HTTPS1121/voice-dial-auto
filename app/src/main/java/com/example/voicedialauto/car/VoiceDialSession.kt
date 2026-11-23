package com.example.voicedialauto.car

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.voicedialauto.VoiceDialApp
import com.example.voicedialauto.contacts.ContactsCache
import com.example.voicedialauto.flow.BackgroundWorker
import com.example.voicedialauto.flow.CachedContactsSource
import com.example.voicedialauto.flow.Caller
import com.example.voicedialauto.flow.DialConfig
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.flow.MainThreadScheduler
import com.example.voicedialauto.flow.ResPrompts
import com.example.voicedialauto.speech.InterruptionMonitor
import com.example.voicedialauto.speech.TtsSpeaker
import com.example.voicedialauto.speech.VoiceRecognizer
import com.example.voicedialauto.util.FileLog
import com.example.voicedialauto.util.Permissions
import com.example.voicedialauto.util.Prefs

/**
 * Contacts preload in the background. Matching waits on [ContactsCache.withContacts];
 * listening must not wait — first template would be Idle ("tap the mic").
 */
class VoiceDialSession : Session() {

    private lateinit var controller: DialController
    private lateinit var interruptions: InterruptionMonitor
    private var sessionLifecycleStarted = false
    private val main = Handler(Looper.getMainLooper())
    private var firstListenTries = 0

    override fun onCreateScreen(intent: Intent): Screen {
        val app = runCatching { VoiceDialApp.instance }.getOrNull()
        app?.cancelBackgroundSession()
        val appCtx = app ?: carContext.applicationContext
        val prefs = Prefs(appCtx)
        var sessionAlive = true
        FileLog.log("lifecycle", "car session onCreateScreen carApi=${carContext.carAppApiLevel}", "car")
        val recognizer = VoiceRecognizer(
            appCtx, carContext,
            useCarMic = { prefs.useCarMic },
            endOfSpeechSilenceMs = { prefs.endOfSpeechSilenceMs },
            segmentedEnabled = { prefs.segmentedRecognition },
            vadSettings = { prefs.vadSettings },
            source = "car",
        )
        val tts = TtsSpeaker(appCtx, carAudio = true)
        val promptCtx = appCtx.createConfigurationContext(carContext.resources.configuration)
        controller = DialController(
            speech = recognizer,
            contacts = CachedContactsSource(appCtx),
            caller = Caller { entry ->
                val ok = if (sessionAlive) {
                    CallLauncher.call(carContext, entry.number, prefs.useActionCall)
                } else {
                    CallLauncher.placeSystemCall(appCtx, entry.number)
                }
                // Leave the car UI as soon as the call is placed. Otherwise, after hangup while
                // still "in" our session, recognition restarts and speaks "no match".
                if (ok && sessionAlive) {
                    try {
                        carContext.finishCarApp()
                        FileLog.log("lifecycle", "call placed → finishCarApp (lose focus)", "car")
                    } catch (t: Throwable) {
                        FileLog.log("lifecycle", "finishCarApp failed: ${t.message}", "car")
                    }
                }
                ok
            },
            scheduler = MainThreadScheduler(),
            permissions = { Permissions.missing(appCtx) },
            languages = prefs,
            config = DialConfig(afterCallQuietMs = 5_000, listLimit = listLimit(), transientRetries = 2, transientBackoffMs = 500),
            speaker = tts,
            prompts = ResPrompts(promptCtx),
            timings = prefs,
            worker = BackgroundWorker,
            continueWhenHidden = { prefs.continueWhenHidden },
        )
        controller.matchSource = "car"
        // Hangup→reopen in <1s: audio mode is often still IN_CALL. Starting SpeechRecognizer /
        // NavDuck then fails silently (no duck, no listen). Defer until mode clears.
        controller.deferInitialListen = isCallAudio(appCtx)
        if (controller.deferInitialListen) {
            FileLog.log("lifecycle", "defer initial listen — call audio still active", "car")
        }
        interruptions = InterruptionMonitor(
            context = appCtx,
            onCallEnded = { controller.onCallEnded() },
            onInterrupt = { controller.onInterrupted() },
        )
        // TTS already stopped itself on focus loss; if a call is ringing/active, abort everything.
        tts.onFocusLoss = { if (interruptions.isInCallNow()) controller.onInterrupted() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                FileLog.log("lifecycle", "car session onStart", "car")
                interruptions.start()
                VoiceRecognizer.resetAudioPolicy()
                // If we were in Calling and the phone call already disconnected while hidden, clear it now.
                if (controller.state is DialState.Calling && !interruptions.isInCallNow()) {
                    controller.onCallEnded()
                }
                val firstStart = !sessionLifecycleStarted
                sessionLifecycleStarted = true
                controller.onVisible()
                if (firstStart || controller.deferInitialListen) ensureFirstListen()
            }
            override fun onStop(owner: LifecycleOwner) {
                FileLog.log("lifecycle", "car session onStop -> hide", "car")
                controller.onHidden()
                if (!prefs.continueWhenHidden || !controller.hasActiveBackgroundWork()) {
                    interruptions.stop()
                }
            }
            override fun onDestroy(owner: LifecycleOwner) {
                FileLog.log("lifecycle", "car session onDestroy", "car")
                sessionAlive = false
                controller.flushPendingHide()
                recognizer.detachCarContext()
                val adopted = app?.adoptBackgroundSession(
                    controller = controller,
                    continueWhenHidden = prefs.continueWhenHidden,
                    recognizer = recognizer,
                    tts = tts,
                    interruptions = interruptions,
                ) == true
                if (!adopted) {
                    interruptions.stop()
                    controller.onDestroyed()
                    // Always release NavDuck — phone call audio owns the stream after dial.
                    recognizer.destroy(keepDuck = false)
                    tts.shutdown()
                }
            }
        })
        ContactsCache.refresh(appCtx) { list ->
            Log.i(TAG, "car preload: ${list.size} contacts")
        }
        // Listen BEFORE wiring the screen: emit() with no listener is a no-op, and the first
        // template is already Listening. Calling onVisible after VoiceDialScreen() NPE's
        // ScreenManager.getTop() — the host has not pushed the screen yet (crashes the process,
        // phone included).
        controller.onVisible()
        return VoiceDialScreen(carContext, controller)
    }

    /**
     * After hangup+immediate reopen, wait until telephony releases audio, then [restart] so
     * NavDuck + SpeechRecognizer start on a clean MODE_NORMAL (otherwise both fail quietly).
     */
    private fun ensureFirstListen() {
        if (interruptions.isInCallNow() || isCallAudio(carContext)) {
            if (firstListenTries++ < 40) { // ~8s @ 200ms
                FileLog.log(
                    "lifecycle",
                    "wait call-audio clear try=$firstListenTries state=${controller.state::class.simpleName}",
                    "car",
                )
                main.postDelayed({ ensureFirstListen() }, 200L)
                return
            }
            FileLog.log("lifecycle", "call-audio still set after waits — restart anyway", "car")
        }
        controller.deferInitialListen = false
        when (controller.state) {
            is DialState.Listening, is DialState.Processing ->
                FileLog.log("lifecycle", "already listening after call-audio clear", "car")
            else -> {
                FileLog.log("lifecycle", "call-audio clear → restart listen+duck", "car")
                controller.restart()
            }
        }
    }

    private fun isCallAudio(ctx: Context): Boolean {
        val mode = ctx.getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
    }

    override fun onNewIntent(intent: Intent) {
        if (controller.state is DialState.Calling && !interruptions.isInCallNow()) {
            controller.onCallEnded()
        }
        // Leave/re-enter must never stick on a stale Confirm/List — always fresh listen.
        // (Old SHOW_RESULTS force-show path removed: it raced push/pop and left HU on results.)
        ResultForceShow.clear()
        carContext.getCarService(androidx.car.app.ScreenManager::class.java).popToRoot()
        if (controller.state is DialState.Calling) {
            controller.onVisible()
        } else {
            FileLog.log("lifecycle", "onNewIntent → restart (fresh listen)", "car")
            controller.restart()
        }
    }

    private fun listLimit(): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(8).coerceIn(2, 10)

    private companion object { const val TAG = "VoiceDialSession" }
}
