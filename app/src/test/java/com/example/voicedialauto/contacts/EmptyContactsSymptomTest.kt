package com.example.voicedialauto.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents the car bug symptom: matching against an empty list yields None (no-match UI),
 * not a dedicated error — so an empty cache looks like "name not found" with a good transcript.
 */
class EmptyContactsSymptomTest {
    @Test fun emptyListLooksLikeNoMatch() {
        val r = ContactMatcher().match(listOf("תתקשר לדנה"), emptyList())
        assertEquals(ContactMatcher.Result.None, r)
    }
}
