package com.example.voicedialauto.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EndpointerTest {

    /** Road noise: RMS bouncing around, occasional spikes, no partials for 30 s → never end. */
    @Test fun noiseOnlyNeverEnds() {
        val ep = Endpointer(1_200)
        val rnd = Random(7)
        var t = 0L
        while (t < 30_000) {
            val db = 3f + rnd.nextFloat() * 2f + if (rnd.nextInt(20) == 0) 4f else 0f
            ep.onRms(db, t)
            assertFalse("ended at $t", ep.shouldEnd(t))
            t += 50
        }
        assertFalse(ep.speechConfirmed)
    }

    @Test fun blankOrPunctuationPartialsAreNotSpeech() {
        val ep = Endpointer(1_000)
        assertFalse(ep.onPartial("  ", 0)); assertFalse(ep.onPartial("…", 10)); assertFalse(ep.onPartial("?!", 20))
        assertFalse(ep.shouldEnd(10_000))
    }

    @Test fun stablePartialEndsAfterSilence() {
        val ep = Endpointer(1_200)
        assertTrue(ep.onPartial("שת", 1_000))
        assertTrue(ep.onPartial("שתיים", 1_400))
        assertFalse(ep.shouldEnd(2_500))
        assertTrue(ep.shouldEnd(2_600))
    }

    @Test fun changingPartialKeepsSessionOpen() {
        val ep = Endpointer(1_200)
        var t = 0L
        listOf("ד", "דנ", "דנה", "דנה כ", "דנה כה", "דנה כהן").forEach { p ->
            ep.onPartial(p, t); assertFalse(ep.shouldEnd(t + 1_000)); t += 1_000
        }
        assertTrue(ep.shouldEnd(t - 1_000 + 1_200))
    }

    /** v11: RMS quiet alone no longer ends — the partial must ALSO be stable for silenceMs. */
    @Test fun rmsQuietAfterSpeechEndsOnlyWhenPartialAlsoStable() {
        val ep = Endpointer(1_000)
        var t = 0L
        repeat(20) { ep.onRms(2f, t); t += 50 }           // floor ≈ 2 dB
        repeat(10) { ep.onRms(8f, t); t += 50 }           // speech 500 ms
        val partialAt = t
        ep.onPartial("two", t)
        repeat(30) { ep.onRms(2f, t); t += 50 }
        assertFalse(ep.shouldEnd(partialAt + 900))         // quiet ≥ quietMs, but partial only 900 ms old
        assertTrue(ep.shouldEnd(partialAt + 1_000))
    }

    /**
     * v11 bug: "חני כהן" said quickly → "חני". The partial for "כהן" lags > 1.2 s behind the audio.
     * While the driver is still saying "כהן" (RMS loud) we must not end.
     */
    @Test fun twoWordsWithLaggingPartialAreNotCutOff() {
        val ep = Endpointer(1_800)
        var t = 0L
        repeat(20) { ep.onRms(2f, t); t += 50 }           // noise floor, t = 1000
        repeat(8) { ep.onRms(8f, t); t += 50 }            // "חני" 1000..1400
        ep.onPartial("חני", 1_300)
        repeat(4) { ep.onRms(2.5f, t); t += 50 }          // 200 ms dip between words
        repeat(12) { ep.onRms(8f, t); t += 50 }           // "כהן" 1600..2200
        repeat(10) { ep.onRms(2f, t); assertFalse("ended at $t", ep.shouldEnd(t)); t += 50 } // 2200..2700
        // v10 rules (1.2 s stable OR quiet-after-loud) would already have ended at 2500 here.
        ep.onPartial("חני כהן", 2_900)                     // partial lags 700 ms behind the audio
        while (t < 4_600) { ep.onRms(2f, t); assertFalse("ended at $t", ep.shouldEnd(t)); t += 50 }
        assertTrue(ep.shouldEnd(2_900 + 1_800))
        assertTrue(ep.lastPartial == "חני כהן")
    }

    @Test fun rmsDipBetweenWordsIsNotAnEnd() {
        val ep = Endpointer(1_800)
        var t = 0L
        repeat(20) { ep.onRms(2f, t); t += 50 }
        ep.onPartial("דנה", t)
        repeat(36) { i -> ep.onRms(if ((i / 6) % 2 == 0) 8f else 2f, t); assertFalse(ep.shouldEnd(t)); t += 50 }
    }

    @Test fun riseAfterDecisionCancelsEnd() {
        val ep = Endpointer(1_000)
        var t = 0L
        repeat(20) { ep.onRms(2f, t); t += 50 }
        repeat(10) { ep.onRms(8f, t); t += 50 }
        ep.onPartial("חני", t)
        while (!ep.shouldEnd(t)) { ep.onRms(2f, t); t += 50 }
        val decided = t
        ep.onRms(8f, t + 100)                              // driver continues before stopListening
        assertFalse(ep.confirmEnd(decided, decided + Endpointer.CONFIRM_MS))
        // Without the rise the decision is confirmed.
        val ep2 = Endpointer(1_000); ep2.onPartial("חני", 0)
        assertTrue(ep2.confirmEnd(1_000, 1_250))
    }

    @Test fun constantLoudNoiseStillEndsAfterSafetyNet() {
        val ep = Endpointer(1_000)
        var t = 0L
        repeat(5) { ep.onRms(1f, t); t += 50 }
        ep.onPartial("דנה", t)
        val p = t
        while (t < p + 1_900) { ep.onRms(9f, t); assertFalse(ep.shouldEnd(t)); t += 50 }
        assertTrue(ep.shouldEnd(p + 2_000))                 // maxStableMs = 2 × silence
    }

    @Test fun oneWordEndsSoonAfterBaselineReturn() {
        val ep = Endpointer(10_000, maxStableMs = 20_000)
        var t = 0L
        repeat(30) { ep.onRms(2f, t); t += 50 }          // baseline ~2 dB
        repeat(10) { ep.onRms(9f, t); t += 50 }           // speech spike
        val wordAt = t
        ep.onPartial("שלום", wordAt)
        repeat(10) { ep.onRms(2.2f, t); t += 50 }           // back near baseline
        assertFalse(ep.shouldEnd(wordAt + 100))
        assertTrue(ep.shouldEndOnBaselineReturn(wordAt + Endpointer.BASELINE_RETURN_HANGOVER_MS + 50))
        assertTrue(ep.shouldEnd(wordAt + Endpointer.BASELINE_RETURN_HANGOVER_MS + 50))
    }

    @Test fun loudNoiseWithoutPartialNeverEnds() {
        val ep = Endpointer(800)
        var t = 0L
        repeat(10) { ep.onRms(1f, t); t += 50 }
        repeat(20) { ep.onRms(9f, t); t += 50 }           // loud burst (horn/bump) …
        repeat(100) { ep.onRms(1f, t); t += 50 }          // … then quiet
        assertTrue(ep.loudSeen); assertFalse(ep.shouldEnd(t))
    }
}
