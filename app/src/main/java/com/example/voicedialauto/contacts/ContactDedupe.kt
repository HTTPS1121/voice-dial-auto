package com.example.voicedialauto.contacts

/**
 * v15: account copies of the same person ("משה כהן", "משה כהן Me", "meapi - משה כהן") showed up
 * as separate list rows. Pure logic, unit-tested:
 *  1. [cleanName] strips account tags: a lowercase latin tag + separator in front ("meapi - …",
 *     "wa: …") and a trailing "Me" / "(me)" — only when a real name remains.
 *  2. [dedupe] keeps ONE entry per normalized phone number (last 9 digits, so 050…, +97250…,
 *     050-… are equal). The kept entry gets the cleanest name; all names become aliases.
 */
object ContactDedupe {
    private val PREFIX_TAG = Regex("^\\s*[a-z][a-z0-9_.]{1,15}\\s*[-–—:|]\\s*(?=\\S)")
    private val SUFFIX_ME = Regex("\\s+[(\\[]?(?:Me|me|ME)[)\\]]?\\s*$")

    fun cleanName(name: String): String {
        var n = name.trim()
        PREFIX_TAG.find(n)?.let { m -> n.substring(m.range.last + 1).trim().takeIf { it.any(Char::isLetter) }?.let { n = it } }
        SUFFIX_ME.find(n)?.let { m -> n.substring(0, m.range.first).trim().takeIf { it.any(Char::isLetter) }?.let { n = it } }
        return n
    }

    fun numberKey(number: String): String {
        val d = NumberSelector.digits(number)
        return if (d.length > 9) d.takeLast(9) else d
    }

    fun dedupe(entries: List<ContactEntry>): List<ContactEntry> {
        val groups = LinkedHashMap<String, MutableList<ContactEntry>>()
        for (e in entries) {
            val k = numberKey(e.number).ifEmpty { "id:${e.contactId}|${e.number}" }
            groups.getOrPut(k) { ArrayList() } += e
        }
        return groups.values.map { g ->
            // Cleanest = a name that needed no cleaning, then the shortest clean name.
            val best = g.minWith(
                compareBy<ContactEntry> { if (cleanName(it.displayName) == it.displayName.trim()) 0 else 1 }
                    .thenBy { cleanName(it.displayName).length },
            )
            val clean = cleanName(best.displayName)
            val aliases = (g.flatMap { listOf(it.displayName, cleanName(it.displayName)) + it.aliases })
                .map { it.trim() }.filter { it.isNotBlank() && it != clean }.distinct()
            best.copy(displayName = clean, aliases = aliases, numberLabel = best.numberLabel.ifBlank { g.firstNotNullOfOrNull { it.numberLabel.ifBlank { null } } ?: "" })
        }
    }
}
