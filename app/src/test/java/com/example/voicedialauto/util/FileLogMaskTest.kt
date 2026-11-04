package com.example.voicedialauto.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileLogMaskTest {
    @Test fun phoneNumbersMaskedExceptLastTwo() {
        assertEquals("Calling דנה כהן ••••••••67", FileLog.mask("Calling דנה כהן 0501234567"))
        assertEquals("+•••-••-•••-••67", FileLog.mask("+972-50-123-4567"))
    }

    @Test fun timingsAndShortNumbersStay() {
        assertEquals("results=+12345 match 38ms #42", FileLog.mask("results=+12345 match 38ms #42"))
    }
}
