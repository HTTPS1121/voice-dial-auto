package com.example.voicedialauto.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hebrew / cross-script cases relevant to the car no-match reports. */
class HebrewMatchTest {

    private val contacts = listOf(
        ContactEntry(1, "דנה כהן", "0501111111"),
        ContactEntry(2, "Dana Levi", "0502222222", aliases = listOf("דנה")),
        ContactEntry(3, "יוסי מזרחי", "0503333333"),
        ContactEntry(4, "Johnny Cash", "0504444444", aliases = listOf("ג'וני", "גיוני")),
        ContactEntry(5, "אמא", "0505555555", aliases = listOf("Mom", "אימא")),
        ContactEntry(6, "ליאור אברהם", "0506666666"),
    )
    private val m = ContactMatcher()

    private fun name(vararg h: String) =
        (m.match(h.toList(), contacts) as ContactMatcher.Result.Single).match.contact.displayName

    @Test fun fillerAndLamed() = assertEquals("יוסי מזרחי", name("תתקשר ליוסי"))
    @Test fun firstNameOnly() = assertEquals("ליאור אברהם", name("תתקשר לליאור"))

    @Test fun danaIsAmbiguousWithAlias() {
        val r = m.match(listOf("דנה"), contacts)
        assertTrue("was $r", r is ContactMatcher.Result.Multiple)
        val names = (r as ContactMatcher.Result.Multiple).matches.map { it.contact.displayName }.toSet()
        assertTrue(names.contains("דנה כהן"))
        assertTrue(names.contains("Dana Levi"))
    }

    @Test fun gereshJohnny() {
        // Geresh between letters is dropped without inserting a space.
        assertEquals("גוני", TextNormalizer.normalize("ג'וני"))
        val r = m.match(listOf("תתקשר לג'וני"), contacts)
        assertTrue("was $r", r is ContactMatcher.Result.Single)
        assertEquals("Johnny Cash", (r as ContactMatcher.Result.Single).match.contact.displayName)
    }

    @Test fun shortNameIma() = assertEquals("אמא", name("תתקשר לאמא"))

    @Test fun bidiMarksStripped() {
        val marked = "\u200Fתתקשר לדנה\u200E"
        assertTrue(QueryExtractor.candidates(marked).any { it.contains("דנה") })
        val r = m.match(listOf(marked), contacts)
        assertTrue("was $r", r is ContactMatcher.Result.Multiple || r is ContactMatcher.Result.Single)
    }

    @Test fun diagnoseAlwaysReturnsTop() {
        val top = m.diagnose(listOf("zebulon"), contacts, 3)
        assertEquals(3, top.size)
    }
}

class QueryExtractorHebrewTest {
    @Test fun leftoverLamedSpace() {
        val c = QueryExtractor.candidates("תתקשר ל דנה")
        assertTrue(c.toString(), c.any { it == "דנה" })
    }
    @Test fun gluedLamed() {
        val c = QueryExtractor.candidates("תתקשר לדנה")
        assertTrue(c.toString(), c.any { it == "דנה" })
    }
}
