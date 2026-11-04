package com.example.voicedialauto.car

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Coalesces template invalidations: runs [action] at most once per [minIntervalMs]; a request
 * inside the interval is deferred (trailing call) so the last state is always drawn.
 */
internal class Throttle(
    private val minIntervalMs: Long = VoiceDialScreen.MIN_INVALIDATE_MS,
    private val action: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var last = 0L
    private var scheduled = false

    fun request() {
        if (scheduled) return
        val wait = last + minIntervalMs - SystemClock.elapsedRealtime()
        if (wait <= 0) run() else { scheduled = true; main.postDelayed({ scheduled = false; run() }, wait) }
    }

    private fun run() { last = SystemClock.elapsedRealtime(); action() }
}
