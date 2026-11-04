package com.example.voicedialauto.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDedupeTest {
    @Test fun accountTagsAreStripped() {
        assertEquals("משה כהן", ContactDedupe.cleanName("משה כהן Me"))
        assertEquals("משה כהן", ContactDedupe.cleanName("meapi - משה כהן"))
        assertEquals("Dana Levi", ContactDedupe.cleanName("Dana Levi (me)"))
        assertEquals("Me", ContactDedupe.cleanName("Me"))              // nothing would remain
        assertEquals("Ami Cohen", ContactDedupe.cleanName("Ami Cohen"))  // a real name stays
        assertEquals("Dana-Lee", ContactDedupe.cleanName("Dana-Lee"))   // capitalised: not a tag
    }

    @Test fun accountCopiesCollapseByNumber() {
        val list = listOf(
            ContactEntry(1, "משה כהן", "050-123-4567", "נייד"),
            ContactEntry(2, "משה כהן Me", "+972501234567"),
            ContactEntry(3, "meapi - משה כהן", "0501234567"),
            ContactEntry(4, "משה לוי", "0529999999"),
        )
        val out = ContactDedupe.dedupe(list)
        assertEquals(listOf("משה כהן", "משה לוי"), out.map { it.displayName })
        assertEquals("נייד", out[0].numberLabel)
        assertTrue("old names kept as aliases", "משה כהן Me" in out[0].aliases)
    }

    @Test fun cleanNameWinsEvenIfOnlyCopiesExist() {
        val out = ContactDedupe.dedupe(listOf(
            ContactEntry(2, "משה כהן Me", "+972501234567"),
            ContactEntry(3, "meapi - משה כהן", "0501234567"),
        ))
        assertEquals("משה כהן", out.single().displayName)
    }

    @Test fun sameNameDifferentNumbersStaySeparate() {
        val out = ContactDedupe.dedupe(listOf(
            ContactEntry(1, "דנה", "0501111111", "נייד"),
            ContactEntry(1, "דנה", "0372222222", "עבודה"),
        ))
        assertEquals(2, out.size)
    }

    @Test fun afterDedupeExactFullNameIsSingleMatch() {
        val list = ContactDedupe.dedupe(listOf(
            ContactEntry(1, "משה כהן", "0501234567"),
            ContactEntry(2, "משה כהן Me", "0501234567"),
            ContactEntry(3, "משה כהנא", "0507777777"),
            ContactEntry(4, "משה לוי", "0508888888"),
        ))
        val r = ContactMatcher().match(listOf("משה כהן"), list)
        assertTrue("got $r", r is ContactMatcher.Result.Single)
        assertEquals("משה כהן", (r as ContactMatcher.Result.Single).match.contact.displayName)
    }

    @Test fun firstNameOnlyRanksExactTokenFirst() {
        val list = listOf(
            ContactEntry(1, "משהלה ברק", "0501000001"),
            ContactEntry(2, "משה כהן", "0501000002"),
            ContactEntry(3, "מושה דוד", "0501000003"),
            ContactEntry(4, "משה לוי", "0501000004"),
        )
        val ranked = ContactMatcher().rank(listOf("משה"), list).map { it.contact.displayName }
        assertEquals(setOf("משה כהן", "משה לוי"), ranked.take(2).toSet())
    }
}
