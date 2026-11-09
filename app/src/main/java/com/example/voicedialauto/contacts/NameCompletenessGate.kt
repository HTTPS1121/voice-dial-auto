package com.example.voicedialauto.contacts

/**
 * Blocks fast end-of-speech while the transcript is only a first name that matches several contacts
 * (e.g. "חני" when both "חני כהן" and "חני לוי" exist).
 */
object NameCompletenessGate {
    fun ambiguousFirstNames(contacts: List<ContactEntry>): Set<String> {
        val byFirst = LinkedHashMap<String, MutableSet<String>>()
        for (c in contacts) {
            val parts = c.displayName.trim().split(Regex("\\s+"))
                .filter { w -> w.any { it.isLetterOrDigit() } }
            if (parts.isEmpty()) continue
            val first = tokenKey(parts.first())
            val suffix = if (parts.size > 1) parts.drop(1).joinToString(" ") else c.displayName
            byFirst.getOrPut(first) { mutableSetOf() }.add(tokenKey(suffix))
        }
        return byFirst.filter { it.value.size > 1 }.keys.toSet()
    }

    fun shouldBlockBaselineEnd(partial: String?, ambiguousFirstNames: Set<String>): Boolean {
        if (partial.isNullOrBlank() || ambiguousFirstNames.isEmpty()) return false
        val words = partial.trim().split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }
        if (words.size != 1) return false
        return ambiguousFirstNames.contains(tokenKey(words.first()))
    }

    private fun tokenKey(s: String): String = TextNormalizer.normalize(s).trim().lowercase()
}
