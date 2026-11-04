package com.example.voicedialauto.flow

/**
 * v11: timestamps (elapsedRealtime ms) of one name lookup, from the start of the recognizer
 * session to the moment the result screen is drawn, so the car "slow list" can be measured.
 * Marks (first write wins):
 *  listen   – recognizer session started            eos     – end of speech (ours or the engine's)
 *  stop     – stopListening() called                results – final results (or partial fallback) delivered
 *  accepted – controller accepted the hypotheses    contacts – contacts list available
 *  matchStart / matchEnd – matcher on the worker thread
 *  shown    – list / countdown / no-match screen drawn (car onGetTemplate, phone render)
 */
class Timeline(val startedAt: Long) {
    private val marks = LinkedHashMap<String, Long>()

    @Synchronized fun mark(key: String, at: Long) { if (key !in marks) marks[key] = at }
    @Synchronized fun get(key: String): Long? = marks[key]
    @Synchronized fun snapshot(): Map<String, Long> = LinkedHashMap(marks)

    /** "eos=+1820 stop=+1830 results=+2410 … | results→shown 180ms" */
    fun format(): String {
        val m = snapshot()
        val rel = m.entries.joinToString(" ") { (k, v) -> "$k=+${v - startedAt}" }
        val r = m["results"] ?: m["accepted"]
        val shown = m["shown"]
        val tail = if (r != null && shown != null) " | results→shown ${shown - r}ms" else ""
        val match = if (m["matchStart"] != null && m["matchEnd"] != null) " match ${m["matchEnd"]!! - m["matchStart"]!!}ms" else ""
        return rel + tail + match
    }
}

object FlowTimeline {
    @Volatile var current: Timeline = Timeline(0)
        private set

    fun begin(at: Long): Timeline = Timeline(at).also { current = it }
    fun mark(key: String, at: Long) = current.mark(key, at)
}
