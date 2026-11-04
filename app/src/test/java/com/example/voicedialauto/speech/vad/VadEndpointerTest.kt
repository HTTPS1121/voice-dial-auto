package com.example.voicedialauto.speech.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v13: synthetic Silero-like probability sequences, 32 ms frames. [run] feeds frames and returns
 * the time (ms from start) at which the endpointer first says END, or null.
 */
class VadEndpointerTest {
    private val F = 32L

    private class Seq { val p = ArrayList<Float>()
        fun speech(ms: Long, prob: Float = 0.9f) = apply { repeat((ms / 32).toInt()) { p += prob } }
        fun silence(ms: Long, prob: Float = 0.05f) = apply { repeat((ms / 32).toInt()) { p += prob } }
        fun frames(vararg probs: Float) = apply { probs.forEach { p += it } }
    }

    /** [transcriptFrom]: ms at which the recognizer's first meaningful partial arrives. */
    private fun run(e: VadEndpointer, s: Seq, transcriptFrom: Long = 300): Long? {
        var t = 0L
        for (prob in s.p) {
            t += F
            e.onFrame(prob, F, t)
            if (e.shouldEnd(t, hasTranscript = t >= transcriptFrom)) return t
        }
        return null
    }

    @Test fun twoWordsWith300msGapAreNotCut() {
        // "חני" 400 ms · 300 ms pause · "כהן" 450 ms · then silence
        val e = VadEndpointer()
        val end = run(e, Seq().silence(320).speech(416).silence(288).speech(448).silence(2_000))
        val secondWordEnd = 320 + 416 + 288 + 448
        assertTrue("ended at $end — before the 2nd word finished", end != null && end > secondWordEnd)
    }

    @Test fun silenceAfterSpeechEndsAfterHangover() {
        val e = VadEndpointer(hangoverMs = 500)
        val speechEnd = 320L + 640L
        val end = run(e, Seq().silence(320).speech(640).silence(1_500))!!
        // ~ hangover after the last speech frame (one frame of resolution)
        assertTrue("end=$end", end - speechEnd in 500L..(500L + 2 * F))
    }

    @Test fun seven100msSilenceEndsWithDefaultHangover() {
        val e = VadEndpointer() // 0.5 s default
        val end = run(e, Seq().speech(640).silence(704))
        assertTrue(end != null)
    }

    @Test fun roadNoiseWithoutSpeechNeverEnds() {
        // Road noise: Silero gives ~0.1–0.3, never ≥ 0.5 (measured on synthetic brown noise: max 0.28).
        val probs = FloatArray(400) { i -> 0.1f + 0.18f * ((i * 37 % 11) / 10f) }
        val e = VadEndpointer()
        assertEquals(null, run(e, Seq().frames(*probs), transcriptFrom = 0))
        assertFalse(e.speechConfirmed)
    }

    @Test fun shortNoiseBurstIsNotSpeech() {
        // one 32 ms bump at 0.6 → 32 ms of "speech", below the 200 ms minimum
        val e = VadEndpointer()
        assertEquals(null, run(e, Seq().silence(320).frames(0.6f).silence(2_000), transcriptFrom = 0))
    }

    @Test fun noEndWithoutTranscript() {
        // Speech-like audio but the recognizer produced no text (yet) → keep listening.
        val e = VadEndpointer()
        assertEquals(null, run(e, Seq().speech(640).silence(2_000), transcriptFrom = Long.MAX_VALUE))
        assertTrue(e.speechConfirmed)
    }

    @Test fun lateTranscriptEndsAsSoonAsItArrives() {
        val e = VadEndpointer()
        val end = run(e, Seq().speech(640).silence(2_000), transcriptFrom = 1_600)!!
        assertTrue(end in 1_600L..1_632L)
    }

    @Test fun singleNoiseSpikeInTrailingSilenceDoesNotResetHangover() {
        val e = VadEndpointer(hangoverMs = 500)
        // speech, 256 ms silence, ONE spike frame at 0.7, then silence
        val s = Seq().speech(640).silence(256).frames(0.7f).silence(1_000)
        val end = run(e, s)!!
        assertTrue("end=$end", end - 640 in 500L..(500L + 2 * F))
    }

    @Test fun midLevelNoiseInTrailingSilenceIsHandledByHysteresis() {
        // After speech, noise at 0.3–0.45 (between end 0.35 and start 0.5 for part of it) must not
        // keep the utterance open: below start → no re-entry; the 0.4 frames right after speech
        // just extend the tail (≥ end), then 0.3 ends speech.
        val e = VadEndpointer()
        val s = Seq().speech(640).frames(0.4f, 0.4f).silence(1_500, prob = 0.3f)
        val end = run(e, s)
        assertTrue(end != null && end <= 640 + 64 + 500 + 2 * F)
    }

    @Test fun resumedSpeechInTrailingSilenceCancelsTheEnd() {
        val e = VadEndpointer(hangoverMs = 500)
        // speech, 400 ms pause (< hangover), more speech (≥ 2 frames), silence
        val s = Seq().speech(640).silence(416).speech(320).silence(1_000)
        val end = run(e, s)!!
        assertTrue("end=$end", end >= 640 + 416 + 320 + 500)
    }

    @Test fun sensitivityMapsToThresholds() {
        assertEquals(0.5f, VadEndpointer.Sensitivity.of("normal").start)
        assertEquals(0.35f, VadEndpointer.Sensitivity.of("normal").end)
        assertEquals(VadEndpointer.Sensitivity.NORMAL, VadEndpointer.Sensitivity.of(null))
        assertTrue(VadEndpointer.Sensitivity.HIGH.start < VadEndpointer.Sensitivity.LOW.start)
    }

    @Test fun lowSensitivityIgnoresSoftNoiseThatHighCounts() {
        val soft = Seq().silence(320).speech(640, prob = 0.55f).silence(1_000)
        assertEquals(null, run(VadEndpointer(0.65f, 0.5f), soft))
        assertTrue(run(VadEndpointer(0.35f, 0.2f), soft) != null)
    }

    // ---- v15: wait for the transcript to catch up with late VAD speech

    @Test fun waitsForTranscriptWhenSpeechCameAfterIt() {
        val e = VadEndpointer(hangoverMs = 500, catchUpMs = 600)
        // word 1 at 0..640 (transcript "חיים" at 400), word 2 at 960..1400, transcript lags
        var t = 0L; var end: Long? = null
        val probs = Seq().speech(640).silence(320).speech(448).silence(2_000).p
        for (prob in probs) {
            t += F; e.onFrame(prob, F, t)
            if (end == null && e.shouldEnd(t, hasTranscript = true, transcriptAt = 400)) end = t
        }
        val lastSpeech = 640 + 320 + 448L
        assertTrue("end=$end", end != null && end!! - lastSpeech >= 500 + 600 - F)
    }

    @Test fun endsAtHangoverWhenTranscriptCaughtUp() {
        val e = VadEndpointer(hangoverMs = 500, catchUpMs = 600)
        var t = 0L; var end: Long? = null
        for (prob in Seq().speech(640).silence(320).speech(448).silence(2_000).p) {
            t += F; e.onFrame(prob, F, t)
            if (end == null && e.shouldEnd(t, hasTranscript = true, transcriptAt = 1_300)) end = t
        }
        val lastSpeech = 640 + 320 + 448L
        assertTrue("end=$end", end!! - lastSpeech in 500L..(500L + 2 * F))
    }
}
