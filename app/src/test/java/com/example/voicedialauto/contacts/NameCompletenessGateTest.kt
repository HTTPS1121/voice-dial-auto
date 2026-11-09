package com.example.voicedialauto.contacts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameCompletenessGateTest {

    private val twoHanni = listOf(
        ContactEntry(1, "חני כהן", "0501"),
        ContactEntry(2, "חני לוי", "0502"),
        ContactEntry(3, "משה פרץ", "0503"),
    )

    @Test fun ambiguousFirstNamesFromContacts() {
        val amb = NameCompletenessGate.ambiguousFirstNames(twoHanni)
        assertTrue(amb.isNotEmpty())
        assertTrue(NameCompletenessGate.shouldBlockBaselineEnd("חני", amb))
    }

    @Test fun fullNameNotBlocked() {
        val amb = NameCompletenessGate.ambiguousFirstNames(twoHanni)
        assertFalse(NameCompletenessGate.shouldBlockBaselineEnd("חני כהן", amb))
    }

    @Test fun uniqueFirstNameNotBlocked() {
        val amb = NameCompletenessGate.ambiguousFirstNames(twoHanni)
        assertFalse(NameCompletenessGate.shouldBlockBaselineEnd("משה", amb))
    }
}
