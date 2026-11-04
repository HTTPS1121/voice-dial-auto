package com.example.voicedialauto.contacts

/** One dialable contact (one chosen phone number per contact). */
data class ContactEntry(
    val contactId: Long,
    val displayName: String,
    val number: String,
    val numberLabel: String = "",
    /**
     * Extra matchable names: given/family from StructuredName, phonetic name, nickname.
     * Not shown in the UI; used only by [ContactMatcher].
     */
    val aliases: List<String> = emptyList(),
) {
    /** Unique per dial target (a contact may contribute several numbers). */
    val key: String get() = "$contactId|${NumberSelector.digits(number)}"
}

data class ScoredContact(val contact: ContactEntry, val score: Double)
