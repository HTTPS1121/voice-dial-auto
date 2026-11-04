package com.example.voicedialauto.scenario

import com.example.voicedialauto.contacts.ContactDedupe
import com.example.voicedialauto.contacts.ContactEntry
import com.example.voicedialauto.contacts.ContactMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v16: the exact contact set used in the head-unit (DHU) test run, and the three scenarios
 * from that run, checked on the pure matching layer.
 */
class HeadUnitScenarioTest {
    private val raw = listOf(
        ContactEntry(1, "משה כהן", "050-1111111"),
        ContactEntry(2, "משה כהן Me", "050-1111111"),
        ContactEntry(3, "meapi - משה כהן", "+972501111111"),
        ContactEntry(4, "משה כהן", "052-2222222"),
        ContactEntry(5, "משה אריה כהן", "053-3333333"),
        ContactEntry(6, "חני כהן", "054-4444444"),
    )
    private val list = ContactDedupe.dedupe(raw)
    private val m = ContactMatcher()

    @Test fun duplicatesCollapseToOneRowPerNumber() {
        assertEquals(4, list.size)
        assertEquals(listOf("משה כהן", "משה כהן", "משה אריה כהן", "חני כהן"), list.map { it.displayName })
        assertTrue(list.none { it.displayName.contains("Me") || it.displayName.contains("meapi") })
    }

    @Test fun exactFullNameIsSingle() {
        for (h in listOf("חני כהן", "תתקשר לחני כהן", "חני כהן.")) {
            val r = m.analyze(listOf(h), list, 6, 0.5, 3).result
            assertTrue("$h -> $r", r is ContactMatcher.Result.Single)
            assertEquals("חני כהן", (r as ContactMatcher.Result.Single).match.contact.displayName)
        }
    }

    @Test fun ambiguousFullNameIsList() {
        val r = m.analyze(listOf("משה כהן"), list, 6, 0.5, 3).result
        assertTrue("got $r", r is ContactMatcher.Result.Multiple)
        val rows = (r as ContactMatcher.Result.Multiple).matches.map { it.contact }
        // Both distinct people named "משה כהן" are offered, the account copies are not.
        assertEquals(2, rows.count { it.displayName == "משה כהן" })
        assertEquals(rows.size, rows.map { ContactDedupe.numberKey(it.number) }.distinct().size)
    }
}
