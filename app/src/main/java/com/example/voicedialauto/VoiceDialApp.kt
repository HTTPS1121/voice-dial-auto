package com.example.voicedialauto

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.speech.TtsSpeaker
import com.google.android.material.color.DynamicColors
import org.lsposed.hiddenapibypass.HiddenApiBypass

class VoiceDialApp : Application() {
    private val main = Handler(Looper.getMainLooper())
    private var leftoverTts: TtsSpeaker? = null
    private var leftoverTask: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.example.voicedialauto.util.FileLog.init(this) // v12: persistent rolling log (phone + car)
        com.example.voicedialauto.speech.vad.VadEngine.prewarm(this) // v13: load Silero off the main thread
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    fun cancelLeftoverPrompt() {
        leftoverTask?.let { main.removeCallbacks(it) }
        leftoverTask = null
        leftoverTts?.shutdown()
        leftoverTts = null
    }

    /**
     * If the car/phone UI is dying while "continue after leave" is on, keep the no-match prompt
     * on the application so it can still play after the session is gone.
     * @return true if [tts] is now owned here (caller must not shut it down).
     */
    fun takeLeftoverPrompt(
        controller: DialController,
        continueWhenHidden: Boolean,
        tts: TtsSpeaker,
        noMatchText: String,
        languageTag: String,
    ): Boolean {
        val listening = controller.state is DialState.Listening || controller.state is DialState.Processing
        if (!continueWhenHidden || controller.state is DialState.Calling || !listening) return false
        val delayMs = controller.remainingWindowMs()
        if (delayMs <= 0L) return false
        holdLeftoverPrompt(tts, delayMs, noMatchText, languageTag)
        return true
    }

    private fun holdLeftoverPrompt(tts: TtsSpeaker, delayMs: Long, text: String, languageTag: String) {
        cancelLeftoverPrompt()
        leftoverTts = tts
        val task = Runnable {
            leftoverTask = null
            tts.speak(text, languageTag) {
                tts.shutdown()
                if (leftoverTts === tts) leftoverTts = null
            }
        }
        leftoverTask = task
        main.postDelayed(task, delayMs)
    }

    companion object {
        @Volatile lateinit var instance: VoiceDialApp
            private set
    }
}
