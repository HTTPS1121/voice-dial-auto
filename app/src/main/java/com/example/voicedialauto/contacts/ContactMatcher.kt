package com.example.voicedialauto.contacts

/**
 * Fuzzy, script-aware contact name matcher. Pure Kotlin.
 *
 * Scores each (query, contact) against the display name AND [ContactEntry.aliases]
 * (nickname / phonetic / given / family). Threshold defaults to 0.62 so short first-name
 * and cross-script hits still surface; ambiguity margin keeps close rivals in a list.
 *
 * v11 performance (car "5–7 s until the list appears"): v10 re-normalized every contact
 * (regex + NFD + phonetic key) on EVERY call, and the controller called it three times per
 * utterance (diagnose + match + suggestions), each doing hypotheses × candidates × contacts
 * Levenshtein runs on the main thread. Now:
 *  - [ContactIndex] holds the normalized/phonetic form of every contact, built once per
 *    contacts load (ContactsCache prewarms it on its background thread) and reused;
 *  - every query is prepared once (tokens, phonetic key, script) instead of per contact;
 *  - duplicate query candidates across hypotheses are scored once;
 *  - a Levenshtein run is skipped when its length-based upper bound can't beat the current score
 *    (exact: scores are identical to v10, verified against the v10 code in tests);
 *  - [analyze] computes match + diagnose + suggestions in ONE pass.
 */
class ContactMatcher(
    private val threshold: Double = 0.62,
    /** Candidates within this distance from the best score are considered ambiguous. */
    private val ambiguityMargin: Double = 0.08,
) {

    sealed interface Result {
        data class Single(val match: ScoredContact) : Result
        data class Multiple(val matches: List<ScoredContact>) : Result
        data object None : Result
    }

    /** Everything the dialer needs from one utterance, computed in a single pass. */
    data class Analysis(
        val result: Result,
        /** Best [diagnoseLimit] raw scores, even below threshold. */
        val top: List<ScoredContact>,
        /** Above the suggestion threshold (penalized), best first. */
        val suggestions: List<ScoredContact>,
        val extracted: List<String>,
    )

    fun match(hypotheses: List<String>, contacts: List<ContactEntry>, maxResults: Int = 10): Result {
        val sc = scoreAll(hypotheses, ContactIndex.of(contacts))
        return decide(sc.ranked(threshold), sc.fullKeys, maxResults)
    }

    /**
     * v15: exactly ONE contact whose full display name equals what was said (score 1.00), all
     * others lower → single match (no list just because "משה כהן לוי" scored 0.94).
     */
    private fun decide(ranked: List<ScoredContact>, fullKeys: Set<String>, maxResults: Int): Result {
        if (ranked.isEmpty()) return Result.None
        val best = ranked.maxOf { it.score }
        val perfect = ranked.filter { it.score >= PERFECT && it.contact.key in fullKeys }
        if (perfect.size == 1 && ranked.count { it.score >= PERFECT } == 1) return Result.Single(perfect.first())
        val close = ranked.filter { it.score >= best - ambiguityMargin }.take(maxResults)
        return if (close.size == 1) Result.Single(close.first()) else Result.Multiple(close)
    }

    /** All contacts above threshold: exact full-token matches first (v15), then by score. */
    fun rank(hypotheses: List<String>, contacts: List<ContactEntry>): List<ScoredContact> =
        scoreAll(hypotheses, ContactIndex.of(contacts)).ranked(threshold)

    /**
     * Top candidates even below threshold (for "did you mean" diagnostics). Never empty when
     * [contacts] is non-empty — returns the best [limit] scores (may be 0.0).
     */
    fun diagnose(hypotheses: List<String>, contacts: List<ContactEntry>, limit: Int = 3): List<ScoredContact> =
        scoreAll(hypotheses, ContactIndex.of(contacts)).top(limit)

    fun analyze(
        hypotheses: List<String>,
        contacts: List<ContactEntry>,
        maxResults: Int = 10,
        suggestionThreshold: Double = 0.35,
        diagnoseLimit: Int = 3,
    ): Analysis {
        val idx = ContactIndex.of(contacts)
        val scores = scoreAll(hypotheses, idx)
        return Analysis(
            result = decide(scores.ranked(threshold), scores.fullKeys, maxResults),
            top = scores.top(diagnoseLimit),
            suggestions = scores.ranked(suggestionThreshold),
            extracted = hypotheses.flatMap { QueryExtractor.candidates(it) }.distinct(),
        )
    }

    companion object { private const val PERFECT = 0.999 }

    fun scoreAgainst(text: String, contact: ContactEntry): Double {
        val c = ContactIndex.indexOne(contact)
        return QueryExtractor.candidates(text).maxOfOrNull { score(Query(it), c, 0.0) } ?: 0.0
    }

    // ------------------------------------------------------------------ scoring core

    private class Scores(val idx: ContactIndex) {
        /** Best raw score per contact (no penalties) → diagnose. */
        val raw = DoubleArray(idx.items.size)
        /** Best penalized score per contact → rank / suggestions. */
        val pen = DoubleArray(idx.items.size) { Double.NEGATIVE_INFINITY }
        /** v15: a query equals one whole name token ("משה" → "משה כהן", not "משהלה"). */
        val exactToken = BooleanArray(idx.items.size)
        /** v15: keys whose full display name equals a query exactly. */
        val fullKeys = HashSet<String>()

        fun ranked(th: Double): List<ScoredContact> {
            // Several entries can share a key (same contact+number): keep the best one, like v10's HashMap.
            val best = HashMap<String, ScoredContact>()
            for (i in pen.indices) {
                val s = pen[i]
                if (s < th) continue
                val e = idx.items[i].entry
                val prev = best[e.key]
                if (prev == null || s > prev.score) best[e.key] = ScoredContact(e, s)
            }
            val exactKeys = HashSet<String>()
            for (i in exactToken.indices) if (exactToken[i]) exactKeys += idx.items[i].entry.key
            return best.values.sortedWith(
                compareByDescending<ScoredContact> { it.contact.key in exactKeys }
                    .thenByDescending { it.score }.thenBy { it.contact.displayName },
            )
        }

        fun top(limit: Int): List<ScoredContact> {
            val best = LinkedHashMap<String, ScoredContact>()
            for (i in raw.indices) {
                val e = idx.items[i].entry
                val prev = best[e.key]
                if (prev == null || raw[i] > prev.score) best[e.key] = ScoredContact(e, raw[i])
            }
            return best.values.sortedByDescending { it.score }.take(limit)
        }
    }

    /** One pass over (unique query × contact): raw best (diagnose) and penalized best (rank). */
    private fun scoreAll(hypotheses: List<String>, idx: ContactIndex): Scores {
        val out = Scores(idx)
        // Unique queries with their smallest penalty (v10: 0.02 × hypothesis rank + 0.01 × candidate index).
        val queries = LinkedHashMap<String, Double>()
        hypotheses.forEachIndexed { rank, hyp ->
            QueryExtractor.candidates(hyp).forEachIndexed { qi, q ->
                val p = 0.02 * rank + (if (qi > 0) 0.01 * qi else 0.0)
                val prev = queries[q]
                if (prev == null || p < prev) queries[q] = p
            }
        }
        for ((q, penalty) in queries) {
            val query = Query(q)
            for (i in idx.items.indices) {
                val item = idx.items[i]
                if (q in item.tokenSet || q in item.nameSet) out.exactToken[i] = true
                if (penalty == 0.0 && item.names.firstOrNull() == q) out.fullKeys += item.entry.key
                val s = score(query, item, 0.0)
                if (s > out.raw[i]) out.raw[i] = s
                val ps = s - penalty
                if (ps > out.pen[i]) out.pen[i] = ps
            }
        }
        return out
    }

    /** A query prepared once (v10 recomputed all of this for every contact). */
    internal class Query(val q: String) {
        val tokens = TextNormalizer.tokens(q)
        val qp = TextNormalizer.phoneticKey(q)
        val qpBare = qp.replace(" ", "")
        val qpTokens = TextNormalizer.tokens(qp)
        val hebrew = q.any(TextNormalizer::isHebrew)
    }

    /** max(cur, factor × similarity(a, b)) — skips Levenshtein when it can't win (exact). */
    private fun simMax(cur: Double, factor: Double, a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return maxOf(cur, if (a == b) factor else 0.0)
        val la = a.length; val lb = b.length
        val bound = 1.0 - kotlin.math.abs(la - lb).toDouble() / maxOf(la, lb)
        if (factor * bound <= cur) return cur
        return maxOf(cur, factor * TextNormalizer.similarity(a, b))
    }

    private fun score(query: Query, c: ContactIndex.Item, start: Double): Double {
        val q = query.q
        if (q.isEmpty() || c.names.isEmpty()) return 0.0
        var s = start
        val qTokens = query.tokens

        if (q in c.nameSet) return 1.0
        // These token checks don't depend on the name → evaluate once (v10 repeated them per name).
        val anyTokenPrefix = q.length >= 2 && c.tokens.any { it.startsWith(q) }
        var tokenScore = 0.0
        for (t in c.tokens) {
            if (t.length >= 2 && q.length >= 2 && (t.startsWith(q) || q.startsWith(t))) tokenScore = maxOf(tokenScore, 0.88)
            if (t.length >= 3 && q.length >= 3 && (t.contains(q) || q.contains(t))) tokenScore = maxOf(tokenScore, 0.80)
        }
        s = maxOf(s, tokenScore)
        if (anyTokenPrefix) s = maxOf(s, 0.90)
        for (name in c.names) {
            if (q.length >= 2 && name.startsWith(q)) s = maxOf(s, 0.90)
            s = simMax(s, 0.88, q, name)
        }

        if (qTokens.size == 1 && q in c.tokenSet) return maxOf(s, 0.96)
        if (qTokens.size > 1 && qTokens.all { it in c.tokenSet }) return maxOf(s, 0.96)
        if (qTokens.isNotEmpty() && qTokens.all { qt ->
                qt.length >= 2 && c.tokens.any { it.startsWith(qt) || qt.startsWith(it) && it.length >= 2 }
            }) s = maxOf(s, 0.86)

        val n = qTokens.size
        for (nameTokens in c.nameTokens) {
            if (n in 1..nameTokens.size) {
                for (i in 0..nameTokens.size - n) {
                    val window = if (n == 1) nameTokens[i] else nameTokens.subList(i, i + n).joinToString(" ")
                    s = simMax(s, 0.88, q, window)
                }
            }
        }

        val qp = query.qp
        val qpBare = query.qpBare
        if (qpBare.isNotEmpty()) {
            val crossScript = query.hebrew != c.hebrew
            val bonus = if (crossScript) 0.08 else 0.0
            for (pn in c.phonNames) {
                if (qp == pn) s = maxOf(s, (if (qpBare.length >= 2) 0.85 else 0.80) + bonus)
                s = simMax(s, 0.78, qp, pn)
            }
            val qpTokens = query.qpTokens
            if (qpTokens.size == 1 && qp in c.phonTokenSet) s = maxOf(s, (if (qpBare.length >= 2) 0.82 else 0.78) + bonus)
            if (qpTokens.size > 1 && qpTokens.all { it in c.phonTokenSet }) s = maxOf(s, 0.84 + bonus)
            for (pt in c.phonTokens) {
                if (pt.isNotEmpty()) s = simMax(s, 0.76, qpBare, pt)
            }
        }
        return s
    }
}

/**
 * Precomputed, immutable match index for one contacts list (v11). Building it is the expensive
 * part (regex normalization, NFD, phonetic keys); it is cached by list identity, so the
 * ContactsCache snapshot is indexed once per load — on the loader's background thread.
 */
class ContactIndex private constructor(val items: List<Item>) {

    class Item(
        val entry: ContactEntry,
        /** Normalized display name + aliases. */
        val names: List<String>,
        val tokens: List<String>,
        val phonNames: List<String>,
        val phonTokens: List<String>,
    ) {
        val tokenSet: Set<String> = tokens.toHashSet()
        val nameSet: Set<String> = names.toHashSet()
        val phonTokenSet: Set<String> = phonTokens.toHashSet()
        val nameTokens: List<List<String>> = names.map { TextNormalizer.tokens(it) }
        val hebrew: Boolean = names.any { n -> n.any(TextNormalizer::isHebrew) }
    }

    companion object {
        @Volatile private var lastList: List<ContactEntry>? = null
        @Volatile private var lastIndex: ContactIndex? = null
        /** Number of full index builds (tests / diagnostics). */
        @Volatile var builds = 0
            private set
        /** Duration of the last full build in ms (debug log). */
        @Volatile var lastBuildMs = 0L
            private set

        /** Returns the cached index when [list] is the same object as last time. Thread-safe. */
        fun of(list: List<ContactEntry>): ContactIndex {
            val l = lastList; val i = lastIndex
            if (l === list && i != null) return i
            synchronized(this) {
                val l2 = lastList; val i2 = lastIndex
                if (l2 === list && i2 != null) return i2
                val t0 = System.nanoTime()
                val built = ContactIndex(list.map { indexOne(it) })
                lastBuildMs = (System.nanoTime() - t0) / 1_000_000
                builds++
                lastIndex = built
                lastList = list
                return built
            }
        }

        fun indexOne(e: ContactEntry): Item {
            val raw = (listOf(e.displayName) + e.aliases).map { TextNormalizer.normalize(it) }.filter { it.isNotBlank() }.distinct()
            val tokens = raw.flatMap { TextNormalizer.tokens(it) }.distinct()
            val phonNames = raw.map { TextNormalizer.phoneticKey(it) }.filter { it.isNotBlank() }.distinct()
            val phonTokens = tokens.map { TextNormalizer.phoneticKey(it) }.filter { it.isNotBlank() }.distinct()
            return Item(e, raw, tokens, phonNames, phonTokens)
        }

        internal fun resetForTests() { synchronized(this) { lastList = null; lastIndex = null; builds = 0 } }
    }
}
