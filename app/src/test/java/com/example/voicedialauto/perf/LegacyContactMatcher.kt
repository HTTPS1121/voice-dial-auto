package com.example.voicedialauto.perf
// v10 matcher, kept verbatim as the "before" baseline + equivalence oracle for the v11 indexed matcher.

import com.example.voicedialauto.contacts.*

/**
 * Fuzzy, script-aware contact name matcher. Pure Kotlin.
 *
 * Scores each (query, contact) against the display name AND [ContactEntry.aliases]
 * (nickname / phonetic / given / family). Threshold defaults to 0.62 so short first-name
 * and cross-script hits still surface; ambiguity margin keeps close rivals in a list.
 */
class LegacyContactMatcher(
    private val threshold: Double = 0.62,
    /** Candidates within this distance from the best score are considered ambiguous. */
    private val ambiguityMargin: Double = 0.08,
) {

    private class Indexed(
        val entry: ContactEntry,
        /** Normalized display name + aliases. */
        val names: List<String>,
        val tokens: List<String>,
        val phonNames: List<String>,
        val phonTokens: List<String>,
    )

    sealed interface Result {
        data class Single(val match: ScoredContact) : Result
        data class Multiple(val matches: List<ScoredContact>) : Result
        data object None : Result
    }

    fun match(hypotheses: List<String>, contacts: List<ContactEntry>, maxResults: Int = 10): Result {
        val ranked = rank(hypotheses, contacts)
        if (ranked.isEmpty()) return Result.None
        val best = ranked.first().score
        val close = ranked.filter { it.score >= best - ambiguityMargin }.take(maxResults)
        return if (close.size == 1) Result.Single(close.first()) else Result.Multiple(close)
    }

    /** All contacts above threshold, best first. */
    fun rank(hypotheses: List<String>, contacts: List<ContactEntry>): List<ScoredContact> {
        if (contacts.isEmpty()) return emptyList()
        val indexed = contacts.map { index(it) }
        val best = HashMap<String, ScoredContact>()
        hypotheses.forEachIndexed { rank, hyp ->
            val rankPenalty = 0.02 * rank
            QueryExtractor.candidates(hyp).forEachIndexed { qi, q ->
                val qPenalty = if (qi > 0) 0.01 * qi else 0.0
                for (c in indexed) {
                    val s = score(q, c) - rankPenalty - qPenalty
                    val prev = best[c.entry.key]
                    if (s >= threshold && (prev == null || s > prev.score)) {
                        best[c.entry.key] = ScoredContact(c.entry, s)
                    }
                }
            }
        }
        return best.values.sortedWith(compareByDescending<ScoredContact> { it.score }
            .thenBy { it.contact.displayName })
    }

    /**
     * Top candidates even below threshold (for "did you mean" diagnostics). Never empty when
     * [contacts] is non-empty — returns the best [limit] scores (may be 0.0).
     */
    fun diagnose(hypotheses: List<String>, contacts: List<ContactEntry>, limit: Int = 3): List<ScoredContact> {
        if (contacts.isEmpty()) return emptyList()
        val indexed = contacts.map { index(it) }
        val best = HashMap<String, ScoredContact>()
        for (hyp in hypotheses) {
            for (q in QueryExtractor.candidates(hyp)) {
                for (c in indexed) {
                    val s = score(q, c)
                    val prev = best[c.entry.key]
                    if (prev == null || s > prev.score) best[c.entry.key] = ScoredContact(c.entry, s)
                }
            }
        }
        return best.values.sortedByDescending { it.score }.take(limit)
    }

    fun scoreAgainst(text: String, contact: ContactEntry): Double {
        val c = index(contact)
        return QueryExtractor.candidates(text).maxOfOrNull { score(it, c) } ?: 0.0
    }

    private fun index(e: ContactEntry): Indexed {
        val raw = (listOf(e.displayName) + e.aliases).map { TextNormalizer.normalize(it) }.filter { it.isNotBlank() }.distinct()
        val tokens = raw.flatMap { TextNormalizer.tokens(it) }.distinct()
        val phonNames = raw.map { TextNormalizer.phoneticKey(it) }.filter { it.isNotBlank() }.distinct()
        val phonTokens = tokens.map { TextNormalizer.phoneticKey(it) }.filter { it.isNotBlank() }.distinct()
        return Indexed(e, raw, tokens, phonNames, phonTokens)
    }

    private fun score(q: String, c: Indexed): Double {
        if (q.isEmpty() || c.names.isEmpty()) return 0.0
        var s = 0.0
        val qTokens = TextNormalizer.tokens(q)

        for (name in c.names) {
            if (q == name) return 1.0
            if (q.length >= 2 && (name.startsWith(q) || c.tokens.any { it.startsWith(q) })) s = maxOf(s, 0.90)
            // Query is a prefix of a longer token (or vice versa) for 2+ char tokens.
            for (t in c.tokens) {
                if (t.length >= 2 && q.length >= 2 && (t.startsWith(q) || q.startsWith(t))) s = maxOf(s, 0.88)
                if (t.length >= 3 && q.length >= 3 && (t.contains(q) || q.contains(t))) s = maxOf(s, 0.80)
            }
            s = maxOf(s, 0.88 * TextNormalizer.similarity(q, name))
        }

        if (qTokens.size == 1 && q in c.tokens) return maxOf(s, 0.96)
        if (qTokens.size > 1 && qTokens.all { it in c.tokens }) return maxOf(s, 0.96)
        // Partial: every query token matches some contact token as prefix (≥2 chars).
        if (qTokens.isNotEmpty() && qTokens.all { qt ->
                qt.length >= 2 && c.tokens.any { it.startsWith(qt) || qt.startsWith(it) && it.length >= 2 }
            }) s = maxOf(s, 0.86)

        val n = qTokens.size
        for (nameTokens in c.names.map { TextNormalizer.tokens(it) }) {
            if (n in 1..nameTokens.size) {
                for (i in 0..nameTokens.size - n) {
                    val window = nameTokens.subList(i, i + n).joinToString(" ")
                    s = maxOf(s, 0.88 * TextNormalizer.similarity(q, window))
                }
            }
        }

        // Cross-script phonetic skeleton (Hebrew ↔ Latin). Allow length-1 for short names (אמא→M).
        val qp = TextNormalizer.phoneticKey(q)
        val qpBare = qp.replace(" ", "")
        if (qpBare.isNotEmpty()) {
            val crossScript = q.any(TextNormalizer::isHebrew) != c.names.any { it.any(TextNormalizer::isHebrew) }
            val bonus = if (crossScript) 0.08 else 0.0
            for (pn in c.phonNames) {
                if (qp == pn) s = maxOf(s, (if (qpBare.length >= 2) 0.85 else 0.80) + bonus)
                s = maxOf(s, 0.78 * TextNormalizer.similarity(qp, pn))
            }
            val qpTokens = TextNormalizer.tokens(qp)
            if (qpTokens.size == 1 && qp in c.phonTokens) s = maxOf(s, (if (qpBare.length >= 2) 0.82 else 0.78) + bonus)
            if (qpTokens.size > 1 && qpTokens.all { it in c.phonTokens }) s = maxOf(s, 0.84 + bonus)
            for (pt in c.phonTokens) {
                if (pt.isNotEmpty()) s = maxOf(s, 0.76 * TextNormalizer.similarity(qpBare, pt))
            }
        }
        return s
    }
}
