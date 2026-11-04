package com.example.voicedialauto.speech.vad

/**
 * v13: end-of-utterance from a REAL voice-activity detector (Silero / WebRTC), pure Kotlin.
 *
 * Input: one speech probability per audio frame (Silero: 32 ms frames at 16 kHz).
 * Hysteresis: a frame starts/continues speech at p ≥ [startThreshold]; while in speech it stays
 * speech until p < [endThreshold] (0.5 / 0.35 at "normal"), so a word's soft tail does not flicker.
 * Re-entering speech after silence needs [resumeFrames] consecutive frames ≥ start, so a single
 * noise spike (bump, indicator click) in the trailing silence does not reset the hangover.
 *
 * END = real speech happened (≥ [minSpeechMs] of speech frames) AND the recognizer produced a
 * meaningful transcript, THEN the VAD has been in silence for [hangoverMs] (default 500 ms).
 * The short pause between two words ("חני … כהן", ~100–300 ms) is shorter than the hangover, so
 * it is not an end; the next word resumes speech and the hangover restarts.
 * Road noise: Silero gives low probabilities for engine/road noise, so noise alone never reaches
 * [minSpeechMs] and never ends anything (the session simply keeps listening).
 */
class VadEndpointer(
    val startThreshold: Float = 0.5f,
    val endThreshold: Float = 0.35f,
    val hangoverMs: Long = 500,
    val minSpeechMs: Long = 200,
    val resumeFrames: Int = 2,
    /**
     * v15: car audio often gave only the first word — Google's partial lagged the audio. If the VAD
     * heard a speech segment that STARTED after the transcript last changed, wait up to this much
     * longer (after the hangover) for the transcript to catch up.
     */
    val catchUpMs: Long = 600,
) {
    var inSpeech = false
        private set
    /** Total ms classified as speech in this session. */
    var speechMs = 0L
        private set
    /** End time of the last speech frame (elapsed ms); 0 = none yet. */
    var lastSpeechAt = 0L
        private set
    var frames = 0
        private set
    /** Start of the latest speech segment (elapsed ms). */
    var segmentStartAt = 0L
        private set
    private var aboveRun = 0
    private var maxProb = 0f

    val speechConfirmed: Boolean get() = speechMs >= minSpeechMs

    /** One frame of [frameMs] ending at [now]. Returns true when speech state flipped. */
    fun onFrame(prob: Float, frameMs: Long, now: Long): Boolean {
        frames++
        maxProb = maxOf(maxProb, prob)
        val was = inSpeech
        if (inSpeech) {
            if (prob >= endThreshold) { speechMs += frameMs; lastSpeechAt = now } else inSpeech = false
            aboveRun = if (prob >= startThreshold) aboveRun + 1 else 0
        } else {
            aboveRun = if (prob >= startThreshold) aboveRun + 1 else 0
            // First speech of the session starts on one frame; after that a re-start needs a run.
            val need = if (speechMs == 0L) 1 else resumeFrames
            if (aboveRun >= need) {
                inSpeech = true
                segmentStartAt = now - frameMs * aboveRun
                speechMs += frameMs * aboveRun
                lastSpeechAt = now
            }
        }
        return was != inSpeech
    }

    fun silenceFor(now: Long): Long = if (inSpeech || lastSpeechAt == 0L) 0L else now - lastSpeechAt

    /** [hasTranscript]: the recognizer already produced a meaningful partial/segment. */
    fun shouldEnd(now: Long, hasTranscript: Boolean, transcriptAt: Long = Long.MAX_VALUE): Boolean {
        if (!(hasTranscript && speechConfirmed && !inSpeech && silenceFor(now) >= hangoverMs)) return false
        if (transcriptAt >= segmentStartAt) return true
        return silenceFor(now) >= hangoverMs + catchUpMs
    }

    /** True while an end is held back only because the transcript hasn't caught up (logging). */
    fun waitingForTranscript(now: Long, hasTranscript: Boolean, transcriptAt: Long): Boolean =
        hasTranscript && speechConfirmed && !inSpeech && silenceFor(now) >= hangoverMs &&
            transcriptAt < segmentStartAt && !shouldEnd(now, hasTranscript, transcriptAt)

    fun summary(): String = "frames=$frames speech=${speechMs}ms max=${"%.2f".format(maxProb)} inSpeech=$inSpeech"

    /** Settings → VAD sensitivity. Higher sensitivity = lower thresholds (soft voices count as speech). */
    enum class Sensitivity(val start: Float, val end: Float) {
        LOW(0.65f, 0.5f), NORMAL(0.5f, 0.35f), HIGH(0.35f, 0.2f);
        companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: NORMAL }
    }

    companion object {
        const val DEFAULT_HANGOVER_MS = 500L
        const val MIN_HANGOVER_MS = 200L
        const val MAX_HANGOVER_MS = 1_500L
        /** Safety net: a transcript unchanged this long ends the utterance whatever the VAD says. */
        const val STABLE_FALLBACK_MS = 2_500L
    }
}
