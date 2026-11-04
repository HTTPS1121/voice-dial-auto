package com.example.voicedialauto.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChoiceResolverTest {
    private val items = listOf(
        ContactEntry(1, "דנה כהן", "0501"),
        ContactEntry(2, "Dana Levi", "0502"),
        ContactEntry(3, "דנה שני", "0503"),
    )
    private fun r(vararg h: String) = ChoiceResolver.resolve(h.toList(), items)?.contactId

    @Test fun numbers() {
        assertEquals(1L, r("אחת")); assertEquals(2L, r("מספר שתיים")); assertEquals(3L, r("three")); assertEquals(2L, r("2"))
    }
    @Test fun names() { assertEquals(2L, r("Dana Levi")); assertEquals(1L, r("דנה כהן")) }
    @Test fun outOfRange() = assertNull(r("חמש"))
}
