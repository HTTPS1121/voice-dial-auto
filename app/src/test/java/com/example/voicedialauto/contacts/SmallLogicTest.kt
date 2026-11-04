package com.example.voicedialauto.contacts

import com.example.voicedialauto.flow.RestartPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallLogicTest {
    @Test fun cancelWords() {
        assertTrue(CancelDetector.isCancel(listOf("ביטול"))); assertTrue(CancelDetector.isCancel(listOf("Cancel please")))
        assertTrue(CancelDetector.isCancel(listOf("לא"))); assertTrue(CancelDetector.isCancel(listOf("never mind")))
        assertFalse(CancelDetector.isCancel(listOf("לא משנה תתקשר לדנה"))); assertFalse(CancelDetector.isCancel(listOf("call Dana")))
    }

    @Test fun numberSelection() {
        val mob = PhoneRow("050-1", true, false); val work = PhoneRow("03-1", false, false); val home = PhoneRow("03-2", false, false)
        assertEquals(listOf(mob), NumberSelector.select(listOf(mob, work)))                        // single mobile
        val def = work.copy(isSuperPrimary = true)
        assertEquals(listOf(def), NumberSelector.select(listOf(mob, def)))                         // default wins
        assertEquals(2, NumberSelector.select(listOf(work, home)).size)                            // no default/mobile → all
        assertEquals(3, NumberSelector.select(listOf(mob, PhoneRow("052-2", true, false), work)).size) // 2 mobiles → all
        assertEquals(1, NumberSelector.select(listOf(mob, PhoneRow("+972-50-1", true, false))).size)   // duplicates merged
    }

    @Test fun restartPolicy() {
        val p = RestartPolicy(30_000, 4); p.begin(0)
        repeat(4) { assertTrue(p.tryRestart(1000L * it)) }
        assertFalse(p.tryRestart(5000))
        p.begin(100_000); assertFalse(p.tryRestart(131_000))
    }
}
