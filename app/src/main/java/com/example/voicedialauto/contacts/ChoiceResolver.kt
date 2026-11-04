package com.example.voicedialauto.contacts

/**
 * Picks one entry from a short numbered list given recognizer hypotheses.
 * Shared by the car ChooseContactScreen and the phone MainActivity.
 * Priority: strong name match > spoken number (EN/HE) > weaker name match.
 */
object ChoiceResolver {
    fun resolve(hypotheses: List<String>, items: List<ContactEntry>, matcher: ContactMatcher = ContactMatcher()): ContactEntry? {
        fun bestByName(minScore: Double): ContactEntry? {
            for (h in hypotheses) {
                val scored = items.map { it to matcher.scoreAgainst(h, it) }.sortedByDescending { it.second }
                val top = scored.firstOrNull() ?: continue
                val second = scored.getOrNull(1)?.second ?: 0.0
                // v15: the strictly best row wins (dedupe removed the account copies that tied before).
                if (top.second >= minScore && top.second - second > TIE) return top.first
            }
            return null
        }
        bestByName(0.9)?.let { return it }
        for (h in hypotheses) {
            val n = SpokenNumberParser.parse(h) ?: continue
            if (n in 1..items.size) return items[n - 1]
        }
        return bestByName(0.75)
    }

    private const val TIE = 0.001

    /**
     * v15: the driver repeated a NAME that fits several rows equally well (e.g. one person with a
     * mobile and a work number) → the caller asks for the number instead of silently relistening.
     */
    fun nameTie(hypotheses: List<String>, items: List<ContactEntry>, matcher: ContactMatcher = ContactMatcher()): Boolean =
        hypotheses.any { h ->
            if (SpokenNumberParser.parse(h) != null) return@any false
            val scores = items.map { matcher.scoreAgainst(h, it) }.sortedDescending()
            scores.size >= 2 && scores[0] >= 0.75 && scores[0] - scores[1] <= TIE
        }
}
