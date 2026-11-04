package com.example.voicedialauto.speech.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.voicedialauto.util.FileLog
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Speech probability per 16 kHz mono PCM16 frame of [FRAME_SAMPLES] samples (32 ms). */
interface VadEngine : AutoCloseable {
    val name: String
    /** Call at the start of every recognizer session (clears recurrent state). */
    fun reset()
    fun probability(frame: ShortArray): Float

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
        const val FRAME_MS = 32L

        @Volatile private var cached: VadEngine? = null
        @Volatile var sileroFailed = false
            private set

        /** Silero (primary); WebRTC if Silero cannot load. Cached per process. Blocking (first call ~100 ms). */
        @Synchronized fun get(context: Context): VadEngine {
            cached?.let { return it }
            val e: VadEngine = if (!sileroFailed) {
                runCatching { SileroVad(context.applicationContext) }
                    .onFailure {
                        sileroFailed = true
                        Log.w("VadEngine", "Silero failed", it)
                        FileLog.log("vad", "Silero failed to load (${it.javaClass.simpleName}: ${it.message}) → WebRTC")
                    }.getOrNull() ?: WebRtcVad()
            } else WebRtcVad()
            FileLog.log("vad", "engine=${e.name}")
            cached = e
            return e
        }

        /** Loads the model in the background at app start, so the first session has no delay. */
        fun prewarm(context: Context) {
            val app = context.applicationContext
            Thread({ runCatching { get(app) } }, "vad-prewarm").start()
        }
    }
}

/**
 * Silero VAD v4 (MIT, snakers4/silero-vad), assets/silero_vad.onnx (1.8 MB), run with ONNX
 * Runtime. Inputs: input[1,512] float, sr int64, h/c [2,1,64]; output: speech probability.
 */
class SileroVad(context: Context) : VadEngine {
    override val name = "silero-v4"
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open(MODEL).use { it.readBytes() }.let { bytes ->
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1); setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        env.createSession(bytes, opts)
    }
    private var h = FloatArray(2 * 64)
    private var c = FloatArray(2 * 64)
    private val input = FloatArray(VadEngine.FRAME_SAMPLES)

    @Synchronized override fun reset() { h = FloatArray(2 * 64); c = FloatArray(2 * 64) }

    @Synchronized override fun probability(frame: ShortArray): Float {
        for (i in input.indices) input[i] = if (i < frame.size) frame[i] / 32768f else 0f
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, VadEngine.FRAME_SAMPLES.toLong())).use { inT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(VadEngine.SAMPLE_RATE.toLong())), longArrayOf()).use { srT ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, 64)).use { hT ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, 64)).use { cT ->
                        session.run(mapOf("input" to inT, "sr" to srT, "h" to hT, "c" to cT)).use { r ->
                            @Suppress("UNCHECKED_CAST")
                            val out = (r.get("output").get() as OnnxTensor).floatBuffer.get(0)
                            h = (r.get("hn").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                            c = (r.get("cn").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                            return out
                        }
                    }
                }
            }
        }
    }

    override fun close() { runCatching { session.close() } }

    companion object { const val MODEL = "silero_vad.onnx" }
}

/**
 * Fallback: WebRTC GMM VAD (gkonovalov/android-vad, MIT, ~160 KB native). Classifies the 512-sample
 * frame as 160 + 160 + 160 (+32 carried over) sub-frames; probability = fraction of speech sub-frames.
 */
class WebRtcVad : VadEngine {
    override val name = "webrtc"
    private val vad = VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_160, Mode.VERY_AGGRESSIVE, 0, 0)
    private val carry = ShortArray(160)
    private var carried = 0
    private val sub = ShortArray(160)

    @Synchronized override fun reset() { carried = 0 }

    @Synchronized override fun probability(frame: ShortArray): Float {
        var speech = 0; var total = 0; var i = 0
        while (true) {
            val need = 160 - carried
            if (frame.size - i < need) break
            System.arraycopy(carry, 0, sub, 0, carried)
            System.arraycopy(frame, i, sub, carried, need)
            i += need; carried = 0
            total++; if (vad.isSpeech(sub)) speech++
        }
        val rest = frame.size - i
        System.arraycopy(frame, i, carry, carried, rest); carried += rest
        return if (total == 0) 0f else speech.toFloat() / total
    }

    override fun close() { runCatching { vad.close() } }
}
