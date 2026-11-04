package com.example.voicedialauto.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoFilterTest {
    private val P = "נמצאו כמה שמות, נא להגיד את מספר התוצאה הרצוי"

    @Test fun numberAppendedToPromptIsKept() {
        assertEquals(listOf("אחד"), EchoFilter.strip(listOf("נמצאו כמה שמות נא להגיד את מספר התוצאה אחד"), P))
        assertEquals(listOf("17"), EchoFilter.strip(listOf("נמצאו כמה שמות נא להגיד את מספר התוצאה הרצוי 17"), P))
    }

    @Test fun recognizerDroppedPromptWordsStillStripped() {
        // "נא" and "את" missing, one typo
        assertEquals(listOf("שתיים"), EchoFilter.strip(listOf("נמצאו כמה שמות להגיד מספר התוצאה הרצוי שתיים"), P))
        assertEquals(listOf("שלוש"), EchoFilter.strip(listOf("נמצאו כמה שמותת נא להגיד את מספר התוצאה הרצוי שלוש"), P))
    }

    @Test fun echoOnlyGivesNothing() {
        assertTrue(EchoFilter.strip(listOf("נמצאו כמה שמות נא להגיד את מספר התוצאה הרצוי"), P).isEmpty())
        assertTrue(EchoFilter.split("נמצאו כמה שמות נא להגיד", P).echoOnly)
    }

    @Test fun cutOffPartialPrefixIsEcho() {
        assertTrue(EchoFilter.isPromptPrefix("נמצאו כמה שמ", P))
        assertTrue(EchoFilter.isPromptPrefix("נמצא", P))
    }

    @Test fun micOpenedMidPrompt() {
        assertEquals(listOf("אחד"), EchoFilter.strip(listOf("להגיד את מספר התוצאה הרצוי אחד"), P))
    }

    @Test fun driverSpeechIsNotEcho() {
        assertFalse(EchoFilter.isPromptPrefix("שתיים", P))
        assertEquals(listOf("שתיים"), EchoFilter.strip(listOf("שתיים"), P))
        // the driver's own "מספר שתיים" must survive (the prompt's "מספר" is mid-prompt)
        assertEquals(listOf("מספר שתיים"), EchoFilter.strip(listOf("מספר שתיים"), P))
        assertEquals(listOf("משה כהן"), EchoFilter.strip(listOf("משה כהן"), P))
    }

    @Test fun driverAnswerAfterFullPromptIncludingWordMispar() {
        assertEquals(listOf("מספר שתיים"), EchoFilter.strip(listOf("נמצאו כמה שמות נא להגיד את מספר התוצאה הרצוי מספר שתיים"), P))
    }

    @Test fun noPromptMeansNoStripping() {
        assertEquals(listOf("נמצאו אחד"), EchoFilter.strip(listOf("נמצאו אחד"), null))
    }
}
