package com.example.voicedialauto.flow

/**
 * v12: platform-neutral logging hook for the pure-Kotlin flow (state machine, matching). The app
 * installs [sink] (→ util.FileLog, persistent rolling file); in JVM tests it stays null.
 */
object FlowLog {
    @Volatile var sink: ((tag: String, msg: String) -> Unit)? = null
    /** "phone" / "car" — set by each host so log lines can be told apart. */
    fun d(tag: String, msg: String) { sink?.invoke(tag, msg) }
}
