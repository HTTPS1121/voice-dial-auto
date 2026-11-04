package com.example.voicedialauto.contacts

/**
 * Ring buffer of the last [CAPACITY] recognition → match attempts (phone + car share the process).
 * Shown in Settings → אבחון.
 */
object MatchAttemptLog {
    const val CAPACITY = 20

    data class Attempt(
        val atMs: Long,
        val source: String,
        val hypotheses: List<String>,
        val lastPartial: String?,
        val extracted: List<String>,
        val contactsCount: Int,
        val verdict: String,
        val top: List<Pair<String, Double>>,
        val decision: String = "",
        /** v11: live timing marks of this lookup (the "shown" mark lands after logging). */
        val timeline: com.example.voicedialauto.flow.Timeline? = null,
    )

    private val items = ArrayDeque<Attempt>()

    @Synchronized fun add(a: Attempt) {
        items.addFirst(a)
        while (items.size > CAPACITY) items.removeLast()
    }

    @Synchronized fun snapshot(): List<Attempt> = items.toList()

    @Synchronized fun clear() = items.clear()
}
