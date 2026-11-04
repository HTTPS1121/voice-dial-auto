package com.example.voicedialauto.speech

/**
 * v14: when do we stop feeding our OWN audio (EXTRA_AUDIO_SOURCE → Silero VAD) to the recognizer?
 * Pure logic, unit-tested.
 *
 * v13 flipped a process-wide flag on ANY early error, so a benign BUSY / CLIENT / network /
 * reuse hiccup silently turned Silero off until the process died. Now:
 *  - Only a "rejection signature" counts: error AUDIO (3) or CLIENT (5) on a FRESH recognizer
 *    that never reached onReadyForSpeech. (A service that cannot read the supplied audio source
 *    fails before it is ready; AOSP RecognitionService has no dedicated error code for this, so
 *    the codes are inferred — every other code is ignored.)
 *  - Two such failures IN A ROW are needed. The first only triggers a retry (own audio, fresh
 *    recognizer); any session that reaches onReadyForSpeech with our audio resets the count.
 *  - Disabling is temporary: [DISABLE_MS] (5 min), and [reset] (app opened / car session started)
 *    clears it immediately.
 */
class AudioSourcePolicy(private val disableMs: Long = DISABLE_MS) {
    enum class Decision { IGNORE, RETRY_OWN_AUDIO, DISABLE_AND_FALL_BACK }

    var consecutive = 0
        private set
    var disabledUntil = 0L
        private set
    var reason: String? = null
        private set

    fun allowOwnAudio(now: Long): Boolean = now >= disabledUntil

    /** A session with our own audio reached onReadyForSpeech → the source works. */
    fun onReady() { consecutive = 0 }

    fun onError(code: Int, readySeen: Boolean, freshRecognizer: Boolean, speechSeen: Boolean, now: Long): Decision {
        if (!isRejectionSignature(code, readySeen, freshRecognizer, speechSeen)) return Decision.IGNORE
        consecutive++
        if (consecutive < 2) return Decision.RETRY_OWN_AUDIO
        consecutive = 0
        disabledUntil = now + disableMs
        reason = "error $code twice before ready"
        return Decision.DISABLE_AND_FALL_BACK
    }

    fun reset() { consecutive = 0; disabledUntil = 0L; reason = null }

    companion object {
        const val DISABLE_MS = 5 * 60_000L
        /** android.speech.SpeechRecognizer codes (copied to keep this class JVM-pure). */
        const val ERROR_AUDIO = 3
        const val ERROR_CLIENT = 5

        fun isRejectionSignature(code: Int, readySeen: Boolean, freshRecognizer: Boolean, speechSeen: Boolean): Boolean =
            (code == ERROR_AUDIO || code == ERROR_CLIENT) && !readySeen && freshRecognizer && !speechSeen

        const val ERROR_NO_MATCH = 7
        const val ERROR_SERVER_DISCONNECTED = 11
        const val MAX_TRANSIENT_RETRIES = 2
        const val TRANSIENT_DELAY_MS = 300L

        /**
         * v15: 11 at any time before speech, or 7 within the first second, is a recognizer-service
         * hiccup (car log: right after the previous session ended) → retry, never disable.
         */
        fun isTransientStartError(code: Int, elapsedMs: Long, speechSeen: Boolean): Boolean = !speechSeen &&
            (code == ERROR_SERVER_DISCONNECTED || (code == ERROR_NO_MATCH && elapsedMs < 1_000L))

        /** Errors that never mean "this mode is unsupported" (busy, rate limit, network, permission, silence). */
        fun isBenign(code: Int): Boolean = code in setOf(1, 2, 4, 6, 7, 8, 9, 10, 11)
    }
}

/**
 * v15 (car log: rec#82/#87/#96): a car-mic session started right after the previous one ended, or
 * any session started right after a cancelled one (onStop → onStart 0.7 s later), got error 11
 * within ~30 ms. The recognizer service and CarAudioRecord need a moment to let go.
 */
object StartGate {
    const val CAR_GAP_MS = 300L
    const val CANCEL_GAP_MS = 300L

    /** How long to wait before starting; 0 = start now. Times are elapsed ms (0 = never). */
    fun delayMs(now: Long, carMic: Boolean, lastCarEndAt: Long, lastCancelAt: Long): Long {
        var d = 0L
        if (carMic && lastCarEndAt > 0) d = maxOf(d, CAR_GAP_MS - (now - lastCarEndAt))
        if (lastCancelAt > 0) d = maxOf(d, CANCEL_GAP_MS - (now - lastCancelAt))
        return d.coerceAtLeast(0L)
    }

    /**
     * Our own capture (→ Silero) unless it's truly impossible. Returns null = own capture, or the
     * reason we use the recognizer's mic. A car→phone fallback goes through here too, so it keeps
     * own capture + Silero (v13 dropped to audio=recognizer).
     */
    fun recognizerMicReason(sdkSupportsOwn: Boolean, vadEnabled: Boolean, carMic: Boolean, ownAllowed: Boolean, policyReason: String?): String? = when {
        !sdkSupportsOwn -> "android<13 (no EXTRA_AUDIO_SOURCE)"
        !vadEnabled && !carMic -> "VAD off in settings"
        !ownAllowed && !carMic -> "recognizer rejected own audio ($policyReason)"
        else -> null
    }
}

/** v14: what the last recognizer session actually used (Settings + log). */
object VadStatus {
    @Volatile var engine: String = "—"
        private set
    @Volatile var reason: String = ""
        private set
    @Volatile var atMs: Long = 0L
        private set
    fun set(engine: String, reason: String) { this.engine = engine; this.reason = reason; atMs = System.currentTimeMillis() }
}
