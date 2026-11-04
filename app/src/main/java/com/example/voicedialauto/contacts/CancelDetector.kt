package com.example.voicedialauto.contacts

/** Recognizes a spoken "cancel" (whole utterance only, so names are never mistaken for it). */
object CancelDetector {
    private val PHRASES = listOf(
        "ביטול", "בטל", "תבטל", "תבטלי", "לא", "לא תודה", "עזוב", "עזבי", "תפסיק", "תפסיקי", "די", "חלאס",
        "cancel", "stop", "no", "nope", "never mind", "nevermind", "forget it", "cancel call", "abort",
    ).map { TextNormalizer.normalize(it) }.toSet()

    private val POLITE = setOf("please", "בבקשה", "thanks", "תודה")

    fun isCancel(hypotheses: List<String>): Boolean {
        val first = hypotheses.firstOrNull() ?: return false
        val tokens = TextNormalizer.tokens(TextNormalizer.normalize(first)).filter { it !in POLITE }
        return tokens.isNotEmpty() && tokens.joinToString(" ") in PHRASES
    }
}
