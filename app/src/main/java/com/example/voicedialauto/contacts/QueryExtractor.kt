package com.example.voicedialauto.contacts

/**
 * Turns a raw utterance ("I want to call Dana", "תתקשר לדנה", "אני רוצה להתקשר ליוסי כהן")
 * into one or more candidate name queries (normalized).
 *
 * Several candidates are returned because the Hebrew prefix ל is ambiguous:
 * "לדנה" → "דנה", but "ליאור" is a real name. The matcher scores all candidates.
 */
object QueryExtractor {

    // Order matters: longer phrases first. All entries are already normalized (lowercase, no punctuation,
    // Hebrew final letters unified: ם→מ, ן→נ, ך→כ, ף→פ, ץ→צ).
    private val EN_FILLERS = listOf(
        "i would like to make a call to", "i would like to call", "i d like to call", "id like to call",
        "i want to make a call to", "i want to call", "i wanna call", "i need to call",
        "can you please call", "could you please call", "can you call", "could you call",
        "please call", "make a call to", "place a call to", "give a call to", "give a ring to",
        "call up", "call to", "phone to", "dial to",
        "ok google", "hey google", "please", "call", "phone", "dial", "ring", "contact", "now", "thanks", "thank you"
    )

    private val HE_FILLERS = listOf(
        "אני רוצה להתקשר", "אני רוצה לדבר עמ", "אני רוצה לדבר עם", "אני צריכ להתקשר", "אני צריכה להתקשר",
        "אני רוצה לחייג", "אני רוצה לצלצל", "אפשר להתקשר", "תוכל להתקשר", "תוכלי להתקשר",
        "בבקשה תתקשר", "תתקשר בבקשה", "תתקשרי", "תתקשר", "התקשר", "התקשרי", "להתקשר",
        "תחייג", "תחייגי", "חייג", "חייגי", "לחייג", "תצלצל", "תצלצלי", "צלצל", "לצלצל",
        "תחייג ל", "חייג ל", "תתקשר ל", "התקשר ל", "שיחה עם", "שיחה אל",
        "שיחה", "בבקשה", "עכשיו", "תודה", "אל", "את", "עמ", "עם", "של"
    )

    private val ALL_FILLERS = (EN_FILLERS + HE_FILLERS).map { TextNormalizer.normalize(it) }.distinct()
        .sortedByDescending { it.length }

    /** Returns distinct candidate queries, best guess first. Never returns blanks. */
    fun candidates(utterance: String): List<String> {
        val norm = TextNormalizer.normalize(utterance)
        if (norm.isEmpty()) return emptyList()

        val stripped = stripFillers(norm)
        val out = LinkedHashSet<String>()
        if (stripped.isNotEmpty()) {
            val noTo = stripped.removePrefix("to ").trim()
                .removePrefix("ל ").trim() // leftover lone ל after filler strip
            val noLamed = stripLamed(noTo)
            if (noLamed != null && noLamed.isNotEmpty()) out += noLamed
            if (noTo.isNotEmpty() && noTo != "ל") out += noTo
        }
        out += norm // last resort: the whole utterance (e.g. the user only said "Dana").
        return out.filter { it.isNotBlank() }
    }

    private fun stripFillers(norm: String): String {
        var tokens = " $norm "
        for (f in ALL_FILLERS) {
            tokens = tokens.replace(" $f ", " ")
        }
        // Also handle a filler glued to ל, e.g. "להתקשר לדנה" is fine, but "תתקשר ל דנה" leaves "ל".
        return tokens.replace(Regex("\\s+"), " ").trim()
    }

    /** "לדנה" → "דנה", "ל דנה" → "דנה". Returns null when there is no leading ל. */
    private fun stripLamed(q: String): String? {
        if (q.startsWith("ל ")) return q.substring(2).trim()
        if (q.length > 2 && q[0] == 'ל' && TextNormalizer.isHebrew(q[1])) return q.substring(1)
        return null
    }
}
