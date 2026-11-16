package com.example.voicedialauto

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.flow.MicState
import com.example.voicedialauto.speech.InterruptionMonitor
import com.example.voicedialauto.speech.TtsSpeaker
import com.example.voicedialauto.speech.VoiceRecognizer
import com.example.voicedialauto.util.FileLog
import com.google.android.material.color.DynamicColors
import org.lsposed.hiddenapibypass.HiddenApiBypass

class VoiceDialApp : Application() {
    private val main = Handler(Looper.getMainLooper())

    private class BgSession(
        val controller: DialController,
        val recognizer: VoiceRecognizer,
        val tts: TtsSpeaker,
        val interruptions: InterruptionMonitor,
        var cleanupTask: Runnable? = null,
        var safetyTask: Runnable? = null,
    )

    private var bgSession: BgSession? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        FileLog.init(this) // v12: persistent rolling log (phone + car)
        com.example.voicedialauto.speech.vad.VadEngine.prewarm(this) // v13: load Silero off the main thread
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    fun cancelLeftoverPrompt() = cancelBackgroundSession()

    fun cancelBackgroundSession() {
        val s = bgSession ?: return
        bgSession = null
        s.cleanupTask?.let { main.removeCallbacks(it) }
        s.safetyTask?.let { main.removeCallbacks(it) }
        s.interruptions.stop()
        val calling = s.controller.state is DialState.Calling
        s.controller.onStateChanged = null
        s.controller.onLevel = null
        s.controller.onDestroyed()
        s.recognizer.destroy(keepDuck = calling)
        s.tts.shutdown()
    }

    /**
     * Car session / phone activity destroyed while "continue after leave" is on and an active
     * listen / match / confirm countdown is running: keep the full pipeline alive on the
     * Application until the call is placed or the spoken prompt completes.
     */
    fun adoptBackgroundSession(
        controller: DialController,
        continueWhenHidden: Boolean,
        recognizer: VoiceRecognizer,
        tts: TtsSpeaker,
        interruptions: InterruptionMonitor,
    ): Boolean {
        if (!continueWhenHidden || !controller.hasActiveBackgroundWork()) return false
        cancelBackgroundSession()
        FileLog.log("lifecycle", "adopted background session in state=${controller.state::class.simpleName}")
        val s = BgSession(controller, recognizer, tts, interruptions)
        bgSession = s
        interruptions.start()
        controller.onLevel = null

        fun scheduleCleanupWhenTtsIdle(extraDelayMs: Long = 0L) {
            s.cleanupTask?.let { main.removeCallbacks(it) }
            val r = Runnable {
                tts.whenIdle {
                    main.post {
                        if (bgSession === s) cancelBackgroundSession()
                    }
                }
            }
            s.cleanupTask = r
            if (extraDelayMs <= 0L) main.post(r) else main.postDelayed(r, extraDelayMs)
        }

        controller.onStateChanged = { st ->
            if (bgSession === s) {
                when (st) {
                    is DialState.Listening, is DialState.Processing, is DialState.Confirm -> Unit
                    is DialState.Choosing -> if (st.mic != MicState.LISTENING) scheduleCleanupWhenTtsIdle(200L)
                    is DialState.Calling -> scheduleCleanupWhenTtsIdle(3_500L)
                    is DialState.NoMatch, is DialState.Failed, is DialState.Idle, is DialState.NeedsPermissions ->
                        scheduleCleanupWhenTtsIdle(250L)
                }
            }
        }

        val safety = Runnable { if (bgSession === s) cancelBackgroundSession() }
        s.safetyTask = safety
        main.postDelayed(safety, 90_000L)
        return true
    }

    companion object {
        @Volatile lateinit var instance: VoiceDialApp
            private set
    }
}
