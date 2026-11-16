package com.example.voicedialauto.car

import android.content.Intent
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
                if (sessionAlive) CallLauncher.call(carContext, entry.number, prefs.useActionCall)
                else CallLauncher.placeSystemCall(appCtx, entry.number)
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
                // Ensure the very first car open always listens even if an early AA onStop fired first.
                if (firstStart && controller.state is DialState.Idle && !interruptions.isInCallNow()) {
                    FileLog.log("lifecycle", "car first onStart still Idle -> restart listen", "car")
                    controller.restart()
                }
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
                    val calling = controller.state is DialState.Calling
                    controller.onDestroyed()
                    recognizer.destroy(keepDuck = calling)
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

    override fun onNewIntent(intent: Intent) {
        if (controller.state is DialState.Calling && !interruptions.isInCallNow()) {
            controller.onCallEnded()
        }
        if (controller.state is DialState.Confirm) {
            controller.onVisible()
        } else {
            carContext.getCarService(androidx.car.app.ScreenManager::class.java).popToRoot()
            controller.restart()
        }
    }

    private fun listLimit(): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6).coerceIn(2, 10)

    private companion object { const val TAG = "VoiceDialSession" }
}
