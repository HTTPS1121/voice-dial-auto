package com.example.voicedialauto.speech

/**
 * v10: ring buffer of the last recognizer sessions (Settings → אבחון → יומן ניסיונות), so a
 * real-car "listen/stop loop" can be diagnosed: how long each session lived, how it ended,
 * whether the instance was reused, and whether real speech was heard.
 */
object RecognizerSessionLog {
    const val CAPACITY = 30

    data class Entry(
        val atMs: Long,
        val continuous: Boolean,
        val reused: Boolean,
        val carMic: Boolean,
        val durationMs: Long,
        val end: String,
        val speech: Boolean,
        val partial: String?,
        /** v12: raw callback trace "+ms event …". */
        val events: String = "",
    )

    private val items = ArrayDeque<Entry>()

    @Synchronized fun add(e: Entry) {
        items.addFirst(e)
        while (items.size > CAPACITY) items.removeLast()
    }

    @Synchronized fun snapshot(): List<Entry> = items.toList()
    @Synchronized fun clear() = items.clear()
}
