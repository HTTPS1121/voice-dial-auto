package com.example.voicedialauto.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoiceResolverTieTest {
    private val rows = listOf(
        ContactEntry(1, "משה כהן", "0501111111", "נייד"),
        ContactEntry(1, "משה כהן", "0372222222", "עבודה"),
        ContactEntry(3, "משה לוי", "0503333333"),
    )

    @Test fun repeatedNameFittingTwoRowsIsATieNotAPick() {
        assertNull(ChoiceResolver.resolve(listOf("משה כהן"), rows))
        assertTrue(ChoiceResolver.nameTie(listOf("משה כהן"), rows))
    }

    @Test fun nameFittingOneRowBestIsPicked() {
        assertEquals("משה לוי", ChoiceResolver.resolve(listOf("משה לוי"), rows)!!.displayName)
        assertFalse(ChoiceResolver.nameTie(listOf("משה לוי"), rows))
    }

    @Test fun numbersAreNeverATie() {
        assertFalse(ChoiceResolver.nameTie(listOf("שתיים"), rows))
        assertEquals(rows[1], ChoiceResolver.resolve(listOf("שתיים"), rows))
    }
}
