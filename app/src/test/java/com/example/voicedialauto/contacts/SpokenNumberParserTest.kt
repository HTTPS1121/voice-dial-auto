package com.example.voicedialauto.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenNumberParserTest {
    private fun p(s: String) = SpokenNumberParser.parse(s)

    @Test fun english() {
        assertEquals(1, p("one")); assertEquals(2, p("number two")); assertEquals(3, p("the third one"))
        assertEquals(2, p("2")); assertEquals(4, p("number 4")); assertEquals(2, p("to")); assertEquals(5, p("five please"))
    }

    @Test fun hebrew() {
        assertEquals(1, p("אחת")); assertEquals(1, p("אחד")); assertEquals(2, p("שתיים")); assertEquals(2, p("מספר שתיים"))
        assertEquals(3, p("שלוש")); assertEquals(4, p("ארבע")); assertEquals(5, p("חמש"))
        assertEquals(1, p("הראשון")); assertEquals(2, p("השני")); assertEquals(3, p("השלישי")); assertEquals(2, p("שניים"))
        assertEquals(3, p("מספר 3"))
    }

    @Test fun notANumber() { assertNull(p("דנה כהן")); assertNull(p("call Dana")) }
}
