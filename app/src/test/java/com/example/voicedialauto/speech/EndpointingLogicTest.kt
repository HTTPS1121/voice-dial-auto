package com.example.voicedialauto.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointingLogicTest {

    @Test fun defaultSilenceInRange() {
        assertTrue(
            VoiceRecognizer.DEFAULT_END_SILENCE_MS in
                VoiceRecognizer.MIN_END_SILENCE_MS..VoiceRecognizer.MAX_END_SILENCE_MS,
        )
        // v13: the VAD decides; the RMS fallback uses hangover + 0.4 s (900 ms at the 0.5 s default),
        // and after stopListening we wait at most 600 ms for final results (v11: 1.2 s).
        assertEquals(900L, VoiceRecognizer.DEFAULT_END_SILENCE_MS)
        assertEquals(1_500L, VoiceRecognizer.completeSilenceFor(1_800L))
        assertEquals(600L, VoiceRecognizer.RESULTS_GRACE_MS)
    }

    @Test fun emptyFinalPrefersPartial() {
        fun resolve(final: List<String>, partial: String?): List<String> =
            final.filter { it.isNotBlank() }.ifEmpty {
                listOfNotNull(partial?.takeIf { it.isNotBlank() })
            }
        assertEquals(listOf("תתקשר לדנה"), resolve(emptyList(), "תתקשר לדנה"))
        assertEquals(listOf("final"), resolve(listOf("final"), "partial"))
        assertEquals(emptyList<String>(), resolve(emptyList(), null))
        assertEquals(emptyList<String>(), resolve(emptyList(), "   "))
    }

    @Test fun finalPreferredAndLongerPartialAdded() {
        assertEquals(listOf("חני כהן"), VoiceRecognizer.mergeFinalAndPartial(listOf("חני כהן"), "חני"))
        assertEquals(listOf("חני כהן", "חנה"), VoiceRecognizer.mergeFinalAndPartial(listOf("חני", "חנה"), "חני כהן"))
        assertEquals(listOf("חני כהן"), VoiceRecognizer.mergeFinalAndPartial(emptyList(), "חני כהן"))
        assertEquals(listOf("דנה"), VoiceRecognizer.mergeFinalAndPartial(listOf("דנה"), "יוסי לוי"))
        assertEquals(emptyList<String>(), VoiceRecognizer.mergeFinalAndPartial(emptyList(), "…"))
    }
}
