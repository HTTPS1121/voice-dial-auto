package com.example.voicedialauto.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartGateTest {
    @Test fun carMicRightAfterPreviousCarSessionWaits300ms() {
        assertEquals(300L, StartGate.delayMs(now = 10_000, carMic = true, lastCarEndAt = 10_000, lastCancelAt = 0))
        assertEquals(270L, StartGate.delayMs(now = 10_030, carMic = true, lastCarEndAt = 10_000, lastCancelAt = 0))
        assertEquals(0L, StartGate.delayMs(now = 10_400, carMic = true, lastCarEndAt = 10_000, lastCancelAt = 0))
    }

    @Test fun phoneMicAfterCarSessionDoesNotWait() {
        assertEquals(0L, StartGate.delayMs(now = 10_010, carMic = false, lastCarEndAt = 10_000, lastCancelAt = 0))
    }

    @Test fun anyStartRightAfterCancelWaits() {
        // car onStop → onStart 0.7 s later is fine; a restart 50 ms after a cancel waits the rest
        assertEquals(250L, StartGate.delayMs(now = 5_050, carMic = false, lastCarEndAt = 0, lastCancelAt = 5_000))
        assertEquals(0L, StartGate.delayMs(now = 5_700, carMic = true, lastCarEndAt = 0, lastCancelAt = 5_000))
    }

    @Test fun firstStartEverIsImmediate() {
        assertEquals(0L, StartGate.delayMs(now = 1, carMic = true, lastCarEndAt = 0, lastCancelAt = 0))
    }

    @Test fun phoneFallbackKeepsOwnCaptureAndSilero() {
        // car mic failed → phone mic: own capture unless the recognizer truly rejected own audio
        assertNull(StartGate.recognizerMicReason(sdkSupportsOwn = true, vadEnabled = true, carMic = false, ownAllowed = true, policyReason = null))
    }

    @Test fun recognizerMicOnlyForRealReasons() {
        assertTrue(StartGate.recognizerMicReason(true, true, false, ownAllowed = false, policyReason = "error 3 twice before ready")!!.contains("rejected"))
        assertTrue(StartGate.recognizerMicReason(false, true, false, true, null)!!.contains("android<13"))
        assertTrue(StartGate.recognizerMicReason(true, false, false, true, null)!!.contains("VAD off"))
        // car mic always captures itself
        assertNull(StartGate.recognizerMicReason(true, false, true, false, "x"))
    }

    @Test fun error11NeverPausesOwnAudioEvenRepeated() {
        val p = AudioSourcePolicy()
        repeat(10) { p.onError(11, readySeen = false, freshRecognizer = true, speechSeen = false, now = it * 100L) }
        assertNull(StartGate.recognizerMicReason(true, true, false, p.allowOwnAudio(1_000), p.reason))
    }
}
