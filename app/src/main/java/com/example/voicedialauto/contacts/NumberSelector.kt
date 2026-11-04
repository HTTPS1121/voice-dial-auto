package com.example.voicedialauto.contacts

/** One phone number row of a contact, as read from ContactsContract. */
data class PhoneRow(val number: String, val isMobile: Boolean, val isSuperPrimary: Boolean, val label: String = "")

/**
 * Which numbers of a contact become dial targets (SPEC §6, decision 7):
 *  - the user's default number if set; else
 *  - the single mobile number if there is exactly one; else
 *  - every distinct number (one row each, so the list shows "Mobile / Work / Home").
 */
object NumberSelector {
    fun select(rows: List<PhoneRow>): List<PhoneRow> {
        val distinct = rows.filter { it.number.isNotBlank() }.distinctBy { digits(it.number) }
        if (distinct.size <= 1) return distinct
        distinct.firstOrNull { it.isSuperPrimary }?.let { return listOf(it) }
        val mobiles = distinct.filter { it.isMobile }
        if (mobiles.size == 1) return mobiles
        return distinct
    }

    /** Digits only, with a leading 972 country code mapped to 0 so "+972-50…" == "050…". */
    fun digits(n: String): String {
        val d = n.filter { it.isDigit() }
        return if (d.startsWith("972")) "0" + d.substring(3) else d
    }
}
