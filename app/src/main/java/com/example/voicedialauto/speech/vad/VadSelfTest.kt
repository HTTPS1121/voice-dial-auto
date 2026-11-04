package com.example.voicedialauto.speech.vad

import android.content.Context
import android.os.SystemClock
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * v14: Settings → אבחון → "בדיקה עצמית של Silero". A FRESH SileroVad (not the cached one) is
 * loaded and run on generated 16 kHz audio: 1 s digital silence, 1 s road-like brown noise and
 * 1 s of a voiced, syllable-modulated harmonic signal. Proves the ONNX model loads and executes;
 * the numbers are a sanity check (desktop reference: silence ≈0, brown noise max ≈0.3, synthetic voice max ≈0.6 / mean ≈0.4), not a speech-quality benchmark (the "voice" is synthetic).
 */
object VadSelfTest {
    data class Result(
        val loadMs: Long, val perFrameMs: Double, val frames: Int,
        val silenceMax: Float, val noiseMax: Float, val voiceMax: Float, val voiceMean: Float,
    ) {
        override fun toString() = buildString {
            append("Silero VAD v4 (ONNX Runtime): OK\n")
            append("load: ${loadMs} ms · inference: ${"%.2f".format(perFrameMs)} ms/frame (${frames} frames)\n")
            append("p(silence) max ${"%.2f".format(silenceMax)}\n")
            append("p(road noise) max ${"%.2f".format(noiseMax)}\n")
            append("p(synthetic voice) max ${"%.2f".format(voiceMax)}, mean ${"%.2f".format(voiceMean)}")
        }
    }

    fun run(context: Context): Result {
        val t0 = SystemClock.elapsedRealtime()
        val vad = SileroVad(context.applicationContext)
        val loadMs = SystemClock.elapsedRealtime() - t0
        try {
            val sr = VadEngine.SAMPLE_RATE
            val silence = ShortArray(sr)
            val rnd = Random(1)
            var acc = 0.0
            val noise = ShortArray(sr) { acc = acc * 0.98 + rnd.nextGaussian() * 300; acc.coerceIn(-8000.0, 8000.0).toInt().toShort() }
            var phase = 0.0
            val voice = ShortArray(sr) { i ->
                val t = i.toDouble() / sr
                phase += 2 * PI * (140 + 20 * sin(2 * PI * 3 * t)) / sr       // pitch contour
                val env = 0.5 * (1 - kotlin.math.cos(2 * PI * 4 * t))          // ~4 syllables/s
                var v = 0.0
                for (h in 1..8) v += sin(h * phase) / h
                (v * env * 4000).toInt().coerceIn(-32000, 32000).toShort()
            }
            var frames = 0
            var inferNs = 0L
            fun probs(a: ShortArray): FloatArray {
                vad.reset()
                val n = a.size / VadEngine.FRAME_SAMPLES
                return FloatArray(n) { k ->
                    val f = a.copyOfRange(k * VadEngine.FRAME_SAMPLES, (k + 1) * VadEngine.FRAME_SAMPLES)
                    val s = System.nanoTime(); val p = vad.probability(f); inferNs += System.nanoTime() - s; frames++; p
                }
            }
            val ps = probs(silence); val pn = probs(noise); val pv = probs(voice)
            return Result(loadMs, inferNs / 1e6 / frames, frames, ps.max(), pn.max(), pv.max(), pv.average().toFloat())
        } finally { vad.close() }
    }
}
