package com.example.voicedialauto.contacts

import java.text.Normalizer
import java.util.Locale

/**
 * Pure-Kotlin text helpers (no Android deps → unit-testable on the JVM).
 */
object TextNormalizer {

    /** Hebrew niqqud / cantillation marks (U+0591..U+05C7). */
    private val HEBREW_MARKS = Regex("[\\u0591-\\u05C7]")
    private val LATIN_DIACRITICS = Regex("\\p{Mn}+")
    /** BOM, bidi marks, zero-width chars that speech engines sometimes leave in. */
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]")
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
    private val SPACES = Regex("\\s+")
    /** Hebrew geresh/gershayim or ASCII apostrophe between letters (ג'וני, ג״ק). */
    private val GERESH_BETWEEN = Regex("(?<=\\p{L})[''`׳″\"](?=\\p{L})")

    private val FINAL_LETTERS = mapOf(
        'ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ'
    )

    /**
     * Lowercase, strip niqqud / bidi marks / Latin diacritics, drop geresh between letters
     * without inserting a space (so ג'וני → גיוני), unify Hebrew final letters,
     * turn remaining punctuation / maqaf into spaces, collapse spaces.
     */
    fun normalize(input: String): String {
        var s = input.lowercase(Locale.ROOT)
        s = s.replace(INVISIBLE, "")
        s = s.replace(HEBREW_MARKS, "")
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(LATIN_DIACRITICS, "")
        s = s.replace(GERESH_BETWEEN, "")
        s = buildString(s.length) { for (c in s) append(FINAL_LETTERS[c] ?: c) }
        s = s.replace(NON_WORD, " ")
        return s.replace(SPACES, " ").trim()
    }

    fun tokens(normalized: String): List<String> =
        if (normalized.isBlank()) emptyList() else normalized.split(' ')

    fun isHebrew(c: Char) = c in '\u05D0'..'\u05EA'

    /**
     * Rough cross-script phonetic skeleton so that "דנה" ≈ "Dana", "משה" ≈ "Moshe",
     * "יוסי" ≈ "Yossi". Vowels and Hebrew matres lectionis (א ה ו י ע) are dropped,
     * consonants are mapped to a small common alphabet. Input must be normalized.
     */
    fun phoneticKey(normalized: String): String {
        val out = StringBuilder()
        val s = normalized
            .replace("tz", "C").replace("ts", "C")
            .replace("sh", "S").replace("ch", "H").replace("kh", "H")
            .replace("ph", "P").replace("th", "T")
        for (c in s) {
            val m: Char? = when (c) {
                'ב' -> 'B'; 'ג' -> 'G'; 'ד' -> 'D'; 'ז' -> 'Z'; 'ח' -> 'H'
                'ט', 'ת' -> 'T'; 'כ' -> 'K'; 'ק' -> 'K'; 'ל' -> 'L'; 'מ' -> 'M'
                'נ' -> 'N'; 'ס', 'ש' -> 'S'; 'פ' -> 'P'; 'צ' -> 'C'; 'ר' -> 'R'
                'א', 'ה', 'ו', 'י', 'ע' -> null
                'b', 'v', 'w' -> 'B'; 'g', 'j' -> 'G'; 'd' -> 'D'; 'z' -> 'Z'
                'h' -> null; 't' -> 'T'; 'k', 'q', 'c' -> 'K'; 'l' -> 'L'; 'm' -> 'M'
                'n' -> 'N'; 's', 'x' -> 'S'; 'p', 'f' -> 'P'; 'r' -> 'R'
                'S' -> 'S'; 'H' -> 'H'; 'C' -> 'C'; 'P' -> 'P'; 'T' -> 'T'
                'a', 'e', 'i', 'o', 'u', 'y' -> null
                ' ' -> ' '
                else -> if (c.isDigit()) c else null
            }
            if (m != null && !(out.isNotEmpty() && out.last() == m && m != ' ')) out.append(m)
        }
        return out.toString().replace(SPACES, " ").trim()
    }

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / maxOf(a.length, b.length)
    }

    private val rows = ThreadLocal.withInitial { arrayOf(IntArray(64), IntArray(64)) }

    /** Allocation-free for strings up to 63 chars (reused per-thread rows). */
    fun levenshtein(a: String, b: String): Int {
        val n = b.length
        var buf = rows.get()
        if (buf[0].size <= n) { buf = arrayOf(IntArray(n + 1), IntArray(n + 1)); rows.set(buf) }
        var prev = buf[0]
        var cur = buf[1]
        for (j in 0..n) prev[j] = j
        for (i in 1..a.length) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..n) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[n]
    }
}
