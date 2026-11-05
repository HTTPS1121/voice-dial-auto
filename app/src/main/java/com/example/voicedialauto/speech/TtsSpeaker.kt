package com.example.voicedialauto.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.car.app.CarContext
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.voicedialauto.flow.Speaker
import java.util.Locale

/**
 * TextToSpeech-backed [Speaker]. Guarantees [speak]'s onDone is called exactly once: when the
 * utterance finishes, fails, is stopped, TTS isn't available, or after a safety timeout.
 * Car: same duck as Waze (NAVIGATION_GUIDANCE + MAY_DUCK). Phone: USAGE_ASSISTANT.
 */
class TtsSpeaker(context: Context) : Speaker {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private var ready = false
    private var failed = false
    private var pending: (() -> Unit)? = null
    private var pendingDone: (() -> Unit)? = null
    private var focus: AudioFocusRequest? = null
    private var seq = 0

    /**
     * v9: invoked on the main thread when another app takes audio focus while we speak
     * (canDuck = AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK). The utterance is already stopped by then.
     */
    var onFocusLoss: ((canDuck: Boolean) -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> main.post {
                com.example.voicedialauto.util.FileLog.log("audio", "TTS focus loss $change")
                // Never keep talking over a ringtone / call / other assistant: stop right away.
                stop()
                onFocusLoss?.invoke(change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            }
        }
    }

    private val attrs = if (context is CarContext) NavDuck.attrs()
        else AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            failed = !ready
            if (failed) { pendingDone?.let { finish(it) }; pending = null } else pending?.invoke()
            pending = null
        }
    }.also {
        it.setAudioAttributes(attrs)
        it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { complete(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { complete(utteranceId) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { complete(utteranceId) } }
        })
    }

    private var currentId: String? = null

    override fun speak(text: String, languageTag: String, onDone: (() -> Unit)?) {
        stop()
        val done = onDone ?: {}
        if (failed) { done(); return }
        pendingDone = done
        val action: () -> Unit = {
            val res = tts.setLanguage(Locale.forLanguageTag(languageTag))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS language $languageTag unavailable")
                finish(done)
            } else {
                val id = "u${++seq}"
                currentId = id
                requestFocus()
                com.example.voicedialauto.util.FileLog.log("tts", "speak $id \"$text\" ($languageTag)")
                TtsActivity.speaking = true
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                // Safety timeout: ~80 ms per char, at least 4 s, at most 20 s.
                val timeout = (text.length * 80L).coerceIn(4_000L, 20_000L)
                main.postDelayed({ if (currentId == id) complete(id) }, timeout)
            }
        }
        if (ready) action() else pending = action
    }

    private fun complete(id: String?) {
        if (id == null || id != currentId) return
        currentId = null
        pendingDone?.let { finish(it) }
    }

    private fun finish(done: () -> Unit) {
        if (TtsActivity.speaking) { TtsActivity.speaking = false; TtsActivity.endedAt = android.os.SystemClock.elapsedRealtime() }
        pendingDone = null
        abandonFocus()
        done()
    }

    override fun stop() {
        pending = null
        if (currentId != null) {
            com.example.voicedialauto.util.FileLog.log("tts", "stop $currentId")
            currentId = null
            runCatching { tts.stop() }
        }
        pendingDone?.let { finish(it) }
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focus = req
        val granted = audio.requestAudioFocus(req)
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            // E.g. a call is ringing: don't speak at all.
            main.post { stop(); onFocusLoss?.invoke(false) }
        }
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object { const val TAG = "TtsSpeaker" }
}

/**
 * v15: "our prompt is playing" for the VAD gate. While true (and [TAIL_MS] after — Bluetooth/car
 * speakers lag), frames are not counted as the driver's speech.
 */
object TtsActivity {
    @Volatile var speaking = false
    @Volatile var endedAt = 0L
    const val TAIL_MS = 400L
    fun gated(now: Long) = speaking || (endedAt > 0 && now - endedAt < TAIL_MS)
}
