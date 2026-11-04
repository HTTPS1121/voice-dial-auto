package com.example.voicedialauto.speech

/**
 * Pure end-of-utterance logic, unit-tested on the JVM.
 *
 * v10: a session may be ended by US only after real speech — a partial transcript containing a
 * letter or digit. Noise alone (RMS bumps, onBeginningOfSpeech, blank/punctuation partials) never
 * ends a session.
 *
 * v11 ("חני כהן" captured as "חני"): v10 ended on EITHER a 1.2 s stable partial OR RMS quiet after
 * loud. Google's partials lag the audio — the second word's partial can arrive > 1.2 s after the
 * first — and the short pause between two words is an RMS dip, so v10 cut the driver off after one
 * word. Now an end needs BOTH:
 *  1. stability: the partial hasn't changed for [silenceMs] (default 1.8 s), AND
 *  2. RMS quiet: no loud input for [quietMs] (when we ever saw loud input; mics that never rise
 *     above the floor count as quiet).
 * Safety net for constant road noise above the floor: a partial stable for [maxStableMs] ends
 * the utterance even if RMS never goes quiet (otherwise v8's "keeps waiting" bug would return).
 * [confirmEnd]: the recognizer re-checks shortly after deciding; loud input or a new partial in
 * between cancels the end.
 */
class Endpointer(
    val silenceMs: Long,
    private val rmsDelta: Float = 1.8f,
    private val minLoudMs: Long = 250,
    val quietMs: Long = (silenceMs * 2 / 3).coerceAtLeast(600L),
    val maxStableMs: Long = silenceMs * 2,
) {
    var lastPartial: String? = null
        private set
    /** True once a partial with a letter/digit arrived (the only "real speech" proof). */
    var speechConfirmed = false
        private set
    /** True once RMS stayed above the floor for [minLoudMs]. */
    var loudSeen = false
        private set

    private var partialChangedAt = 0L
    private var noiseFloor = 0f
    private var samples = 0
    private var loudSince: Long? = null
    /** Last sample above the floor (any length — a short rise still means "not quiet"). */
    var lastLoudAt = Long.MIN_VALUE / 2
        private set

    /** Returns true if the (meaningful) partial changed. Blank/punctuation-only partials are ignored. */
    fun onPartial(text: String, now: Long): Boolean {
        if (!isMeaningful(text)) return false
        if (text == lastPartial) return false
        lastPartial = text
        partialChangedAt = now
        speechConfirmed = true
        return true
    }

    fun onRms(db: Float, now: Long) {
        val loud = samples > 0 && db >= noiseFloor + rmsDelta
        if (!loud) {
            samples++
            noiseFloor = if (samples == 1) db else noiseFloor * 0.95f + db * 0.05f
            loudSince = null
        } else {
            val since = loudSince ?: now.also { loudSince = it }
            if (now - since >= minLoudMs) loudSeen = true
            lastLoudAt = now
            // Very slow drift so a permanent rise in road noise is eventually treated as floor.
            noiseFloor = noiseFloor * 0.995f + db * 0.005f
        }
    }

    /** Elapsed time of the last transcript change (0 = none). */
    val changedAt: Long get() = if (speechConfirmed) partialChangedAt else 0L

    /** ms since the transcript last changed (0 before any speech). */
    fun stableFor(now: Long): Long = if (!speechConfirmed) 0L else now - partialChangedAt

    fun shouldEnd(now: Long): Boolean {
        if (!speechConfirmed) return false
        val stableFor = now - partialChangedAt
        if (stableFor >= maxStableMs) return true
        val quiet = !loudSeen || now - lastLoudAt >= quietMs
        return stableFor >= silenceMs && quiet
    }

    /** Re-check of an end decided at [decidedAt]: cancelled by any loud sample or new partial since. */
    fun confirmEnd(decidedAt: Long, now: Long): Boolean =
        shouldEnd(now) && lastLoudAt < decidedAt && partialChangedAt < decidedAt

    companion object {
        fun isMeaningful(text: String?): Boolean = text != null && text.any { it.isLetterOrDigit() }
        /** Delay between deciding to end and calling stopListening (cancellable). */
        const val CONFIRM_MS = 250L
    }
}
