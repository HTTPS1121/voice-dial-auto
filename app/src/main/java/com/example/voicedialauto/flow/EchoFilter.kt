package com.example.voicedialauto.flow

/**
 * v15: the car mic hears our own list prompt ("נמצאו כמה שמות נא להגיד את מספר התוצאה הרצוי")
 * and the driver's answer is APPENDED ("…התוצאה אחד", "…הרצוי 17"). v12–v14 threw the whole
 * hypothesis away. Now the prompt is STRIPPED and the remainder is kept. Pure, unit-tested.
 *
 * Alignment is forward-only over the prompt's tokens: each hypothesis token, from the start, is
 * consumed if it fuzzily matches one of the next few prompt tokens (equal, ≤1 edit for words of
 * 4+ letters, or a cut-off prefix of 2+ letters — partials stop mid-word). Recognizer drops of up to
 * [MAX_SKIP] prompt words are tolerated. The first token that doesn't align ends the echo; from
 * there on everything is the driver ("…את מספר התוצאה הרצוי מספר שתיים" → "מספר שתיים", because the
 * prompt's "מספר" was already consumed).
 */
object EchoFilter {
    private const val MAX_SKIP = 2

    data class Split(val echoTokens: Int, val remainder: String) {
        val isEcho: Boolean get() = echoTokens > 0
        val echoOnly: Boolean get() = echoTokens > 0 && remainder.isBlank()
    }

    fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{Nd} ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun split(hypothesis: String, prompt: String?): Split {
        val h = norm(hypothesis).split(' ').filter { it.isNotBlank() }
        val p = prompt?.let { norm(it).split(' ').filter { t -> t.isNotBlank() } }.orEmpty()
        if (h.isEmpty() || p.isEmpty()) return Split(0, h.joinToString(" "))
        // The mic may open mid-prompt ("להגיד את מספר התוצאה אחד"): the FIRST token may anchor
        // anywhere in the prompt, but an anchor past the prompt's start needs ≥ 2 aligned tokens
        // (so the driver's own "מספר שתיים" is not mistaken for the prompt's "מספר").
        var best = Split(0, h.joinToString(" "))
        for (anchor in p.indices) {
            if (!same(h[0], p[anchor], allowPrefix = h.size == 1)) continue
            var pi = anchor + 1
            var consumed = 1
            while (consumed < h.size && pi < p.size) {
                val tok = h[consumed]
                val last = consumed == h.size - 1
                val hit = (pi until minOf(p.size, pi + 1 + MAX_SKIP)).firstOrNull { j -> same(tok, p[j], allowPrefix = last) }
                    ?: break
                pi = hit + 1
                consumed++
            }
            if (anchor > 0 && consumed < 2) continue
            if (consumed > best.echoTokens) best = Split(consumed, h.drop(consumed).joinToString(" "))
            if (anchor == 0) break
        }
        return best
    }

    /** True if [partial] is (a fuzzy prefix of) the prompt and nothing else → not the driver. */
    fun isPromptPrefix(partial: String, prompt: String?): Boolean = split(partial, prompt).echoOnly

    /** Hypotheses with the echo removed; empty list = echo only. */
    fun strip(hypotheses: List<String>, prompt: String?): List<String> =
        hypotheses.map { val sp = split(it, prompt); if (sp.isEcho) sp.remainder else it.trim() }
            .filter { it.isNotBlank() }.distinct()

    private fun same(a: String, b: String, allowPrefix: Boolean): Boolean {
        if (a == b) return true
        if (allowPrefix && a.length >= 2 && b.startsWith(a)) return true
        if (a.length >= 4 && b.length >= 4 && editDistanceAtMost1(a, b)) return true
        return false
    }

    private fun editDistanceAtMost1(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
