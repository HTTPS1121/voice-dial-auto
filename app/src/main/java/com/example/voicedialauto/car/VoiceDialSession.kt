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
import com.example.voicedialauto.flow.MainThreadScheduler
import com.example.voicedialauto.flow.ResPrompts
import com.example.voicedialauto.util.FileLog
import com.example.voicedialauto.speech.InterruptionMonitor
import com.example.voicedialauto.speech.TtsSpeaker
import com.example.voicedialauto.speech.VoiceRecognizer
import com.example.voicedialauto.util.Permissions
import com.example.voicedialauto.util.Prefs

/**
 * Contacts preload in the background. Matching waits on [ContactsCache.withContacts];
 * listening must not wait — first template would be Idle ("tap the mic").
 */
class VoiceDialSession : Session() {

    private lateinit var controller: DialController

    override fun onCreateScreen(intent: Intent): Screen {
        val appCtx = runCatching { VoiceDialApp.instance }.getOrElse { carContext.applicationContext }
        val prefs = Prefs(appCtx)
        FileLog.log("lifecycle", "car session onCreateScreen carApi=${carContext.carAppApiLevel}", "car")
        val recognizer = VoiceRecognizer(
            carContext, carContext,
            useCarMic = { prefs.useCarMic },
            endOfSpeechSilenceMs = { prefs.endOfSpeechSilenceMs },
            segmentedEnabled = { prefs.segmentedRecognition },
            vadSettings = { prefs.vadSettings },
            source = "car",
        )
        val tts = TtsSpeaker(carContext)
        controller = DialController(
            speech = recognizer,
            contacts = CachedContactsSource(appCtx),
            caller = Caller { CallLauncher.call(carContext, it.number, prefs.useActionCall) },
            scheduler = MainThreadScheduler(),
            permissions = { Permissions.missing(carContext) },
            languages = prefs,
            config = DialConfig(afterCallQuietMs = 5_000, listLimit = listLimit()),
            speaker = tts,
            prompts = ResPrompts(carContext),
            timings = prefs,
            worker = BackgroundWorker,
        )
        controller.matchSource = "car"
        val interruptions = InterruptionMonitor(carContext) { controller.onInterrupted() }
        // TTS already stopped itself on focus loss; if a call is ringing/active, abort everything.
        tts.onFocusLoss = { if (interruptions.isInCallNow()) controller.onInterrupted() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                FileLog.log("lifecycle", "car session onStart", "car")
                interruptions.start()
                com.example.voicedialauto.speech.VoiceRecognizer.resetAudioPolicy()
                controller.onVisible()
            }
            override fun onStop(owner: LifecycleOwner) {
                FileLog.log("lifecycle", "car session onStop → cancel countdown / mic / TTS", "car")
                controller.onHidden()
                interruptions.stop()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                FileLog.log("lifecycle", "car session onDestroy", "car")
                interruptions.stop(); recognizer.destroy(); tts.shutdown()
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
        carContext.getCarService(androidx.car.app.ScreenManager::class.java).popToRoot()
        controller.restart()
    }

    private fun listLimit(): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6).coerceIn(2, 10)

    private companion object { const val TAG = "VoiceDialSession" }
}
