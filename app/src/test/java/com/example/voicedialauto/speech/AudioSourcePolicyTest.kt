package com.example.voicedialauto.speech

import com.example.voicedialauto.speech.AudioSourcePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourcePolicyTest {
    private val AUDIO = 3; private val CLIENT = 5
    private val BUSY = 8; private val TOO_MANY = 10; private val NETWORK = 2; private val NET_TIMEOUT = 1
    private val SERVER_DISCONNECTED = 11; private val SERVER = 4; private val NO_MATCH = 7; private val PERM = 9

    private fun AudioSourcePolicy.fail(code: Int, ready: Boolean = false, fresh: Boolean = true, speech: Boolean = false, now: Long = 0) =
        onError(code, ready, fresh, speech, now)

    @Test fun benignErrorsNeverDisable() {
        val p = AudioSourcePolicy()
        listOf(BUSY, TOO_MANY, NETWORK, NET_TIMEOUT, SERVER, SERVER_DISCONNECTED, NO_MATCH, PERM).forEach { code ->
            repeat(3) { assertEquals("code $code", Decision.IGNORE, p.fail(code)) }
        }
        assertTrue(p.allowOwnAudio(0)); assertEquals(0, p.consecutive)
    }

    @Test fun singleRejectionOnlyRetries() {
        val p = AudioSourcePolicy()
        assertEquals(Decision.RETRY_OWN_AUDIO, p.fail(CLIENT))
        assertTrue(p.allowOwnAudio(1))
    }

    @Test fun twoRejectionsInARowDisableTemporarily() {
        val p = AudioSourcePolicy()
        assertEquals(Decision.RETRY_OWN_AUDIO, p.fail(AUDIO, now = 0))
        assertEquals(Decision.DISABLE_AND_FALL_BACK, p.fail(AUDIO, now = 100))
        assertFalse(p.allowOwnAudio(100 + 60_000))
        assertTrue("re-enabled after 5 min", p.allowOwnAudio(100 + AudioSourcePolicy.DISABLE_MS))
    }

    @Test fun errorAfterReadyIsNotARejection() {
        val p = AudioSourcePolicy()
        repeat(3) { assertEquals(Decision.IGNORE, p.fail(CLIENT, ready = true)) }
        assertTrue(p.allowOwnAudio(0))
    }

    @Test fun reusedRecognizerErrorIsNotARejection() {
        val p = AudioSourcePolicy()
        repeat(3) { assertEquals(Decision.IGNORE, p.fail(CLIENT, fresh = false)) }
        assertTrue(p.allowOwnAudio(0))
    }

    @Test fun errorAfterSpeechIsNotARejection() {
        val p = AudioSourcePolicy()
        assertEquals(Decision.IGNORE, p.fail(AUDIO, speech = true))
    }

    @Test fun successfulReadyResetsTheCount() {
        val p = AudioSourcePolicy()
        assertEquals(Decision.RETRY_OWN_AUDIO, p.fail(CLIENT))
        p.onReady()
        assertEquals("not 'twice in a row'", Decision.RETRY_OWN_AUDIO, p.fail(CLIENT))
        assertTrue(p.allowOwnAudio(0))
    }

    @Test fun benignErrorBetweenRejectionsDoesNotCount() {
        val p = AudioSourcePolicy()
        assertEquals(Decision.RETRY_OWN_AUDIO, p.fail(CLIENT))
        assertEquals(Decision.IGNORE, p.fail(BUSY))
        assertEquals(Decision.DISABLE_AND_FALL_BACK, p.fail(CLIENT))
    }

    @Test fun resetOnAppOpenReEnablesImmediately() {
        val p = AudioSourcePolicy()
        p.fail(AUDIO); p.fail(AUDIO)
        assertFalse(p.allowOwnAudio(1_000))
        p.reset()
        assertTrue(p.allowOwnAudio(1_000)); assertEquals(0, p.consecutive)
    }

    @Test fun benignListUsedForSegmentedMode() {
        assertTrue(AudioSourcePolicy.isBenign(BUSY)); assertTrue(AudioSourcePolicy.isBenign(SERVER_DISCONNECTED))
        assertFalse(AudioSourcePolicy.isBenign(CLIENT)); assertFalse(AudioSourcePolicy.isBenign(AUDIO))
    }

    // ---- v15: transient start errors (car log: error 11 right after start; 7 at < 1 s)

    @Test fun error11IsTransientNeverARejection() {
        assertTrue(AudioSourcePolicy.isTransientStartError(SERVER_DISCONNECTED, 50, speechSeen = false))
        assertTrue(AudioSourcePolicy.isTransientStartError(SERVER_DISCONNECTED, 5_000, speechSeen = false))
        val p = AudioSourcePolicy()
        repeat(5) { assertEquals(Decision.IGNORE, p.fail(SERVER_DISCONNECTED)) }
        assertTrue(p.allowOwnAudio(0))
    }

    @Test fun earlyNoMatchIsTransientLateIsNot() {
        assertTrue(AudioSourcePolicy.isTransientStartError(NO_MATCH, 400, speechSeen = false))
        assertFalse(AudioSourcePolicy.isTransientStartError(NO_MATCH, 1_500, speechSeen = false))
    }

    @Test fun afterSpeechNothingIsTransient() {
        assertFalse(AudioSourcePolicy.isTransientStartError(SERVER_DISCONNECTED, 100, speechSeen = true))
    }

    @Test fun otherCodesAreNotTransient() {
        listOf(AUDIO, CLIENT, BUSY, NETWORK).forEach { assertFalse(AudioSourcePolicy.isTransientStartError(it, 100, false)) }
    }
}
