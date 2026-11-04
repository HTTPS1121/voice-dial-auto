package com.example.voicedialauto.perf

import com.example.voicedialauto.contacts.ContactEntry
import com.example.voicedialauto.contacts.ContactIndex
import com.example.voicedialauto.contacts.ContactMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v11: (1) the indexed matcher returns exactly the v10 scores; (2) the index is built once per
 * contacts list and reused; (3) prints before/after timing for 1000 synthetic contacts.
 */
class MatcherBenchmarkTest {
    companion object {
        private val HE_FIRST = listOf("חני", "דנה", "יוסי", "משה", "אבי", "רונית", "שרה", "דוד", "מיכל", "נועה", "איתי", "עומר", "תמר", "גיל", "ליאור", "אורי", "שירה", "יעל", "רון", "עידו")
        private val HE_LAST = listOf("כהן", "לוי", "מזרחי", "פרץ", "ביטון", "אברהם", "פרידמן", "שפירא", "גולן", "אזולאי", "דהן", "חדד", "עמר", "בן דוד", "קפלן")
        private val EN_FIRST = listOf("Dana", "John", "Mike", "Sarah", "David", "Anna", "Tom", "Chris", "Emma", "Noah")
        private val EN_LAST = listOf("Smith", "Levi", "Cohen", "Brown", "Miller", "Davis", "Wilson", "Taylor")

        fun synthetic(n: Int, seed: Int = 1): List<ContactEntry> {
            val r = Random(seed)
            return (1..n).map { i ->
                val he = r.nextInt(10) < 7
                val f = if (he) HE_FIRST.random(r) else EN_FIRST.random(r)
                val l = if (he) HE_LAST.random(r) else EN_LAST.random(r)
                val name = if (r.nextInt(5) == 0) "$f $l ${i % 97}" else "$f $l"
                ContactEntry(i.toLong(), name, "05${1000000 + i}", "נייד", listOf(f, l, "${f}ush"))
            }
        }

        val HYPS = listOf("תתקשר לחני כהן", "תתקשר לחני כהנא", "תתקשר לחני קון", "תתקשר להני כהן", "תתקשר לחני")
        val QUERIES = listOf(
            HYPS, listOf("call Dana"), listOf("תתקשר לדנה"), listOf("zebulon"), listOf("יוסי מזרחי"),
            listOf("אני רוצה להתקשר לליאור"), listOf("Sara Smit", "Sarah Smith"), listOf("דויד"), listOf("Moshe"),
        )
    }

    private fun legacyOnce(list: List<ContactEntry>): Long {
        val m = LegacyContactMatcher(threshold = 0.62); val sug = LegacyContactMatcher(threshold = 0.35)
        val t0 = System.nanoTime()
        m.diagnose(HYPS, list, 3); m.match(HYPS, list, 6); sug.rank(HYPS, list)
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun newOnce(list: List<ContactEntry>): Long {
        val m = ContactMatcher(threshold = 0.62)
        val t0 = System.nanoTime()
        m.analyze(HYPS, list, 6, 0.35, 3)
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test fun sameScoresAsV10() {
        val list = synthetic(400, seed = 3)
        val legacy = LegacyContactMatcher(threshold = 0.62)
        val now = ContactMatcher(threshold = 0.62)
        for (h in QUERIES) {
            val a = legacy.rank(h, list).map { it.contact.key to "%.6f".format(it.score) }
            val b = now.rank(h, list).map { it.contact.key to "%.6f".format(it.score) }
            assertEquals("rank $h", a, b)
            val la = legacy.diagnose(h, list, 3).map { "%.6f".format(it.score) }
            val lb = now.diagnose(h, list, 3).map { "%.6f".format(it.score) }
            assertEquals("diagnose $h", la, lb)
            val ra = legacy.match(h, list, 6); val rb = now.match(h, list, 6)
            assertEquals("match kind $h", ra.javaClass.simpleName, rb.javaClass.simpleName)
            val an = now.analyze(h, list, 6, 0.35, 3)
            assertEquals(LegacyContactMatcher(threshold = 0.35).rank(h, list).map { it.contact.key }, an.suggestions.map { it.contact.key })
        }
    }

    @Test fun indexIsBuiltOncePerListAndReused() {
        val list = synthetic(200, seed = 9)
        val before = ContactIndex.builds
        val a = ContactIndex.of(list)
        val m = ContactMatcher()
        repeat(5) { m.analyze(HYPS, list); m.match(listOf("דנה"), list); m.diagnose(listOf("x"), list) }
        assertSame(a, ContactIndex.of(list))
        assertEquals(before + 1, ContactIndex.builds)
        // A new load (new list object) → one new build.
        val reloaded = list.toList()
        ContactIndex.of(reloaded); m.match(listOf("דנה"), reloaded)
        assertEquals(before + 2, ContactIndex.builds)
    }

    @Test fun benchmark() {
        val list = synthetic(1000)
        val legacyCold = legacyOnce(list)
        repeat(3) { legacyOnce(list) }
        val legacyWarm = (1..5).map { legacyOnce(list) }.average()

        val list2 = synthetic(1000, seed = 2) // fresh list → fresh index build included in "cold"
        val buildT0 = System.nanoTime(); ContactIndex.of(list2); val buildMs = (System.nanoTime() - buildT0) / 1_000_000
        val newCold = newOnce(list2)
        repeat(3) { newOnce(list2) }
        val newWarm = (1..5).map { newOnce(list2) }.average()
        println(
            "BENCH 1000 contacts × ${HYPS.size} hyps (JVM): v10 cold=${legacyCold}ms warm=${"%.1f".format(legacyWarm)}ms | " +
                "v11 index build=${buildMs}ms (once per load, background) match cold=${newCold}ms warm=${"%.1f".format(newWarm)}ms"
        )
        assertTrue(newWarm < legacyWarm)
    }
}
