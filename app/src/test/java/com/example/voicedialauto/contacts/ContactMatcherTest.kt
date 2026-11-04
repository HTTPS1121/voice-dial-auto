package com.example.voicedialauto.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {

    private val contacts = listOf(
        ContactEntry(1, "דנה כהן", "0501111111"),
        ContactEntry(2, "Dana Levi", "0502222222"),
        ContactEntry(3, "יוסי מזרחי", "0503333333"),
        ContactEntry(4, "Moshe Peretz", "0504444444"),
        ContactEntry(5, "ליאור", "0505555555"),
        ContactEntry(6, "אמא", "0506666666"),
        ContactEntry(7, "Avi Ben-David", "0507777777"),
    )
    private val m = ContactMatcher()

    private fun single(vararg h: String): String {
        val r = m.match(h.toList(), contacts)
        assertTrue("expected single for ${h.toList()} but was $r", r is ContactMatcher.Result.Single)
        return (r as ContactMatcher.Result.Single).match.contact.displayName
    }

    @Test fun hebrewPrefixLamed() = assertEquals("יוסי מזרחי", single("תתקשר ליוסי"))
    @Test fun hebrewLongFiller() = assertEquals("יוסי מזרחי", single("אני רוצה להתקשר ליוסי מזרחי"))
    @Test fun hebrewMaqafPrefix() = assertEquals("אמא", single("תתקשר ל-אמא"))
    @Test fun nameStartingWithLamed() = assertEquals("ליאור", single("תתקשר לליאור"))
    @Test fun nameStartingWithLamedNoVerb() = assertEquals("ליאור", single("ליאור"))
    @Test fun englishFiller() = assertEquals("Moshe Peretz", single("I want to call Moshe"))
    @Test fun crossScriptHebrewSpokenLatinSaved() = assertEquals("Moshe Peretz", single("תתקשר למשה"))
    @Test fun hyphenatedName() = assertEquals("Avi Ben-David", single("call avi ben david"))
    @Test fun niqqud() = assertEquals("אמא", single("תִּתְקַשֵּׁר לְאִמָּא"))

    @Test fun ambiguousDana() {
        val r = m.match(listOf("תתקשר לדנה"), contacts)
        assertTrue("was $r", r is ContactMatcher.Result.Multiple)
        val names = (r as ContactMatcher.Result.Multiple).matches.map { it.contact.displayName }.toSet()
        assertEquals(setOf("דנה כהן", "Dana Levi"), names)
    }

    @Test fun fullNameDisambiguates() = assertEquals("Dana Levi", single("call Dana Levi"))
    @Test fun noMatch() = assertEquals(ContactMatcher.Result.None, m.match(listOf("call Zebulon"), contacts))
}
