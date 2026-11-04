package com.example.voicedialauto.util

import android.content.Context
import android.util.Log
import com.example.voicedialauto.flow.FlowLog
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v12: persistent rolling log for phone AND Android Auto sessions (same process, same file).
 * files/logs/voicedial.log, rotated at [MAX_BYTES] to voicedial.1.log (2 files kept).
 * Every line is flushed (car sessions can end at any moment). Phone numbers are masked
 * (all digits of a 7+ digit run except the last 2; timings stay readable); names are kept.
 */
object FileLog {
    const val MAX_BYTES = 2L * 1024 * 1024
    private const val TAG = "FileLog"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vd-log").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var dir: File? = null
    /** Default source tag; the car session/phone activity set their own per call. */
    @Volatile var source: String = "app"

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        FlowLog.sink = { tag, msg -> log(tag, msg) }
        log("app", "---- process start ----")
    }

    val current: File? get() = dir?.let { File(it, "voicedial.log") }
    private val previous: File? get() = dir?.let { File(it, "voicedial.1.log") }

    fun log(tag: String, msg: String, src: String = source) {
        val line = "${fmt.format(Date())} [$src] $tag: ${mask(msg)}\n"
        Log.d("VD/$tag", line.trimEnd())
        val f = current ?: return
        io.execute {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    previous?.let { p -> p.delete(); f.renameTo(p) }
                }
                FileWriter(f, true).use { w -> w.write(line); w.flush() }
            }.onFailure { Log.w(TAG, "write failed", it) }
        }
    }

    /** Last [maxBytes] of the log (previous file + current), for copy/view. Blocking (small). */
    fun tail(maxBytes: Int): String {
        val parts = listOfNotNull(previous, current).filter { it.exists() }
        val all = parts.joinToString("") { runCatching { it.readText() }.getOrDefault("") }
        return if (all.length > maxBytes) all.takeLast(maxBytes) else all
    }

    /** Writes previous+current into [out] (for sharing as one .txt). */
    fun exportTo(out: File): File {
        out.parentFile?.mkdirs()
        out.writeText(tail(Int.MAX_VALUE))
        return out
    }

    fun clear() {
        io.execute { runCatching { current?.delete(); previous?.delete() } }
    }

    private val DIGIT_RUN = Regex("\\+?\\d[\\d\\- ]{5,}\\d")

    /** Masks phone-like digit runs (7+ digits) except the last two digits. */
    fun mask(s: String): String = DIGIT_RUN.replace(s) { m ->
        val digits = m.value.count { it.isDigit() }
        if (digits < 7) return@replace m.value
        var seen = 0
        buildString {
            for (c in m.value) {
                if (c.isDigit()) { seen++; append(if (seen > digits - 2) c else '•') } else append(c)
            }
        }
    }
}
