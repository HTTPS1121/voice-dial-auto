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
 *
 * v19: optional fast path after speech when RMS stays near the pre-speech baseline for an adaptive
 * hangover (sustained, not a single dip), partial is stable for that hangover, and VAD (if present)
 * agrees the driver is not still speaking.
 */
class Endpointer(
    val silenceMs: Long,
    private val rmsEnterDelta: Float = 1.8f,
    private val rmsExitDelta: Float = 1.1f,
    private val minLoudMs: Long = 250,
    val quietMs: Long = (silenceMs * 2 / 3).coerceAtLeast(600L),
    val maxStableMs: Long = silenceMs * 2,
    /** Fast baseline return (typical in the 10 s name listen window on device). */
    private val baselineFastPathEnabled: Boolean = true,
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
    private var inLoudBand = false
    private var lastRms = 0f
    private var lastRmsAt = 0L
    private var peakAboveBaseline = 0f
    private var loudSpeechMs = 0L
    /** Continuous near-baseline since (reset on any rise above hysteresis band). */
    private var nearBaselineSince: Long? = null
    /** Last sample above the floor (any length — a short rise still means "not quiet"). */
    var lastLoudAt = Long.MIN_VALUE / 2
        private set
    /** Pre-speech ambient level (EMA while not loud); used for fast return-to-baseline endpointing. */
    val ambientBaseline: Float get() = noiseFloor

    data class EndSignals(
        val vadInSpeech: Boolean = false,
        val vadProb: Float = 0f,
        val vadEndThreshold: Float = 0.35f,
        val blockAmbiguousFirstName: Boolean = false,
    )

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
        lastRms = db
        val dt = when {
            lastRmsAt == 0L -> 50L
            else -> (now - lastRmsAt).coerceIn(1L, 200L)
        }
        lastRmsAt = now
        val floorReady = samples > 0
        val enterLoud = floorReady && db >= noiseFloor + rmsEnterDelta
        val exitLoud = floorReady && db > noiseFloor + rmsExitDelta
        if (!inLoudBand) {
            if (enterLoud) {
                inLoudBand = true
                loudSince = now
                lastLoudAt = now
            } else {
                samples++
                noiseFloor = if (samples == 1) db else noiseFloor * 0.97f + db * 0.03f
            }
        } else {
            if (!exitLoud) inLoudBand = false
            else {
                val since = loudSince ?: now.also { loudSince = it }
                if (now - since >= minLoudMs) loudSeen = true
                lastLoudAt = now
                peakAboveBaseline = maxOf(peakAboveBaseline, db - noiseFloor)
                loudSpeechMs += dt
                noiseFloor = noiseFloor * 0.997f + db * 0.003f
            }
        }
        updateNearBaseline(now, db, floorReady)
    }

    private fun updateNearBaseline(now: Long, db: Float, floorReady: Boolean) {
        if (!floorReady) {
            nearBaselineSince = null
            return
        }
        if (db <= noiseFloor + BASELINE_RETURN_MARGIN) {
            if (nearBaselineSince == null) nearBaselineSince = now
        } else {
            nearBaselineSince = null
        }
    }

    /** Elapsed time of the last transcript change (0 = none). */
    val changedAt: Long get() = if (speechConfirmed) partialChangedAt else 0L

    /** ms since the transcript last changed (0 before any speech). */
    fun stableFor(now: Long): Long = if (!speechConfirmed) 0L else now - partialChangedAt

    fun adaptiveBaselineHangoverMs(now: Long): Long {
        var h = BASELINE_HANGOVER_MIN_MS
        if (partialWordCount() == 1) h += 120L
        if (partialLooksGrowing(now)) h += 180L
        if (peakAboveBaseline >= 5f) h += 80L
        if (loudSpeechMs < 450) h += 100L
        return h.coerceIn(BASELINE_HANGOVER_MIN_MS, BASELINE_HANGOVER_MAX_MS)
    }

    private fun partialLooksGrowing(now: Long): Boolean =
        now - partialChangedAt < PARTIAL_GROWING_MS

    /**
     * Fast end when level has stayed near baseline for the full adaptive hangover, the partial is
     * unchanged for that long, and optional VAD says speech has ended.
     */
    fun shouldEndOnBaselineReturn(now: Long, signals: EndSignals = EndSignals()): Boolean {
        if (!baselineFastPathEnabled) return false
        if (!speechConfirmed || !loudSeen) return false
        if (now < partialChangedAt) return false
        if (signals.blockAmbiguousFirstName) return false
        if (signals.vadInSpeech || signals.vadProb >= signals.vadEndThreshold) return false
        if (partialWordCount() != 1) return false
        val hang = adaptiveBaselineHangoverMs(now)
        val nearSince = nearBaselineSince ?: return false
        if (now - nearSince < hang) return false
        if (now - partialChangedAt < hang) return false
        if (now - lastLoudAt < hang) return false
        return true
    }

    private fun partialWordCount(): Int =
        lastPartial?.trim()?.split(Regex("\\s+"))?.count { w -> w.any { it.isLetterOrDigit() } } ?: 0

    fun shouldEnd(now: Long, signals: EndSignals = EndSignals()): Boolean {
        if (!speechConfirmed) return false
        if (now < partialChangedAt) return false
        if (shouldEndOnBaselineReturn(now, signals)) return true
        val stableFor = now - partialChangedAt
        if (stableFor >= maxStableMs) return true
        val quiet = !loudSeen || now - lastLoudAt >= quietMs
        return stableFor >= silenceMs && quiet
    }

    /** Re-check of an end decided at [decidedAt]: cancelled by any loud sample or new partial since. */
    fun confirmEnd(decidedAt: Long, now: Long, signals: EndSignals = EndSignals()): Boolean =
        shouldEnd(now, signals) && lastLoudAt < decidedAt && partialChangedAt < decidedAt

    companion object {
        fun isMeaningful(text: String?): Boolean = text != null && text.any { it.isLetterOrDigit() }
        /** Delay between deciding to end and calling stopListening (cancellable). */
        const val CONFIRM_MS = 250L
        const val BASELINE_HANGOVER_MIN_MS = 600L
        const val BASELINE_HANGOVER_MAX_MS = 1_200L
        const val BASELINE_RETURN_MARGIN = 1.0f
        const val PARTIAL_GROWING_MS = 450L
    }
}
