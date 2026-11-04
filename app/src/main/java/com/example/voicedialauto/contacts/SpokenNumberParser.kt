package com.example.voicedialauto.contacts

/**
 * Parses a spoken list index in English or Hebrew: "2", "two", "number two", "the second one",
 * "אחת", "מספר שתיים", "השני", "שלישי" ... Returns the 1-based index or null.
 */
object SpokenNumberParser {

    private val WORDS: Map<String, Int> = buildMap {
        // English cardinals / ordinals
        listOf("one", "won", "first").forEach { put(it, 1) }
        listOf("two", "to", "too", "second").forEach { put(it, 2) }
        listOf("three", "tree", "third").forEach { put(it, 3) }
        listOf("four", "for", "fore", "fourth", "forth").forEach { put(it, 4) }
        listOf("five", "fifth").forEach { put(it, 5) }
        listOf("six", "sixth").forEach { put(it, 6) }
        listOf("seven", "seventh").forEach { put(it, 7) }
        listOf("eight", "eighth", "ate").forEach { put(it, 8) }
        listOf("nine", "ninth").forEach { put(it, 9) }
        listOf("ten", "tenth").forEach { put(it, 10) }
        // Hebrew (normalized: final letters unified → "ראשונ", "שלושימ" etc.)
        listOf("אחת", "אחד", "ראשונ", "ראשונה").forEach { put(it, 1) }
        listOf("שתיימ", "שתימ", "שניימ", "שנימ", "שתי", "שני", "שנייה", "שניה").forEach { put(it, 2) }
        listOf("שלוש", "שלושה", "שלש", "שלישי", "שלישית").forEach { put(it, 3) }
        listOf("ארבע", "ארבעה", "רביעי", "רביעית").forEach { put(it, 4) }
        listOf("חמש", "חמישה", "חמשה", "חמישי", "חמישית").forEach { put(it, 5) }
        listOf("שש", "שישה", "ששה", "שישי", "שישית").forEach { put(it, 6) }
        listOf("שבע", "שבעה", "שביעי", "שביעית").forEach { put(it, 7) }
        listOf("שמונה", "שמיני", "שמינית").forEach { put(it, 8) }
        listOf("תשע", "תשעה", "תשיעי", "תשיעית").forEach { put(it, 9) }
        listOf("עשר", "עשרה", "עשירי", "עשירית").forEach { put(it, 10) }
    }

    /** Words that are pure noise around a number. */
    private val NOISE = setOf(
        "number", "no", "option", "the", "please", "call", "choose", "pick", "select", "line", "item",
        "מספר", "אפשרות", "שורה", "את", "בבקשה", "תתקשר", "התקשר", "חייג", "תבחר", "בחר", "ל", "ה"
    )

    /** Weak homophones ("to", "for", "won"...) only count when they are the whole answer. */
    private val WEAK = setOf("to", "too", "for", "fore", "forth", "won", "tree", "ate", "שני", "שתי")

    fun parse(text: String): Int? {
        val norm = TextNormalizer.normalize(text)
        if (norm.isEmpty()) return null
        // Digits anywhere ("number 2", "#2", "2nd", or a recognizer that already wrote "2").
        Regex("\\d+").find(norm)?.let { return it.value.toIntOrNull()?.takeIf { n -> n in 1..99 } }

        val tokens = norm.split(' ')
            .map { stripHebrewPrefix(it) }
            .filter { it.isNotEmpty() && it !in NOISE }
        for (t in tokens) {
            val n = WORDS[t] ?: continue
            if (t in WEAK && tokens.size > 1) continue
            return n
        }
        return null
    }

    /** "השני" → "שני", "בשלוש" → "שלוש", "למספר" → "מספר". Only strips when the rest is a known word. */
    private fun stripHebrewPrefix(t: String): String {
        if (t.length > 2 && (t[0] == 'ה' || t[0] == 'ל' || t[0] == 'ב')) {
            val rest = t.substring(1)
            if (rest in WORDS || rest in NOISE) return rest
        }
        return t
    }
}
