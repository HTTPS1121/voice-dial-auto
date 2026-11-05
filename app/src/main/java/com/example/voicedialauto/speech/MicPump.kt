package com.example.voicedialauto.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import com.example.voicedialauto.speech.vad.VadEngine
import java.io.IOException
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * v13: WE capture the microphone (16 kHz mono PCM16) and feed the SAME bytes to
 *  1. SpeechRecognizer, through a pipe (RecognizerIntent.EXTRA_AUDIO_SOURCE, API 33+), and
 *  2. a real VAD (Silero / WebRTC) frame by frame, for end-of-utterance.
 * Sources: the phone mic (AudioRecord VOICE_COMMUNICATION + AEC/NS, v15; default) or the
 * car mic (CarAudioRecord, Car App API 5+). [onFrame] is called on the MAIN thread per 32 ms frame.
 */
class MicPump private constructor(
    private val src: Source,
    private val vad: VadEngine?,
    private val onFrame: (prob: Float, rmsDb: Float, at: Long) -> Unit,
) {
    interface Source {
        val label: String
        /** v15: capture details for the log (source, AEC/NS active). */
        val info: String get() = ""

        fun start()
        /** Bytes read (≥0) or <0 at end. */
        fun read(buf: ByteArray): Int
        /** Stops capture (any thread); a blocked read returns. */
        fun stop()
        /** Frees resources — called on the pump thread after the read loop has exited. */
        fun release() {}
    }

    private val pipe: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    val label: String get() = src.label
    val vadName: String get() = vad?.name ?: "none"
    val captureInfo: String get() = src.info.ifEmpty { "source=${src.label}" }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun attachTo(intent: Intent) {
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VadEngine.SAMPLE_RATE)
    }

    fun start() {
        src.start()
        vad?.reset()
        running = true
        thread = Thread({ loop() }, "mic-pump-${src.label}").also { it.start() }
    }

    private fun loop() {
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val buf = ByteArray(2048)
        val frame = ShortArray(VadEngine.FRAME_SAMPLES)
        var fill = 0
        var lowByte = -1
        try {
            while (running) {
                val n = src.read(buf)
                if (n < 0) break
                if (n == 0) continue
                out.write(buf, 0, n)
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xff
                    if (lowByte < 0) { lowByte = b; continue }
                    frame[fill++] = ((b shl 8) or lowByte).toShort(); lowByte = -1
                    if (fill == frame.size) {
                        fill = 0
                        val prob = vad?.let { runCatching { it.probability(frame) }.getOrDefault(0f) } ?: 0f
                        val db = rmsDb(frame)
                        val at = SystemClock.elapsedRealtime()
                        main.post { if (running) onFrame(prob, db, at) }
                    }
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "pipe closed: ${e.message}") // the recognizer finished reading
        } catch (e: IllegalStateException) {
            Log.d(TAG, "recording stopped: ${e.message}")
        } finally {
            runCatching { out.close() }
            runCatching { src.stop() }
            runCatching { src.release() }
        }
    }

    /** Closes the audio: the recognizer sees end-of-stream and finalizes. */
    fun stop() {
        if (!running && thread == null) return
        running = false
        runCatching { src.stop() }
        thread = null
        runCatching { pipe[0].close() }
    }

    /** Same scale the recognizer's onRmsChanged roughly uses (≈ −2 … 10 dB). */
    private fun rmsDb(f: ShortArray): Float {
        var sum = 0.0
        for (s in f) sum += s.toDouble() * s
        val rms = sqrt(sum / f.size) / 32768.0
        return (20 * log10(rms.coerceAtLeast(1e-6)) + 50).toFloat() / 5f
    }

    // ---------------------------------------------------------------- sources

    private class PhoneMic(
        private val context: Context,
        /** Car session: don't enter communication mode (that pauses media); duck like Waze instead. */
        private val duckMedia: Boolean,
    ) : Source {
        override val label = "phone"
        override var info = ""
        private var rec: AudioRecord? = null
        private var aec: AcousticEchoCanceler? = null
        private var ns: NoiseSuppressor? = null
        private var focus: AudioFocusRequest? = null
        @SuppressLint("MissingPermission") // checked in create()
        override fun start() {
            if (duckMedia) {
                focus = NavDuck.hold(context)
            }
            val min = AudioRecord.getMinBufferSize(VadEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // v15: VOICE_COMMUNICATION gets AEC/NS in the capture path (TTS is the far end).
            // In the car it also flips MODE_IN_COMMUNICATION and pauses media — skip it when ducking.
            var source = if (duckMedia) "VOICE_RECOGNITION" else "VOICE_COMMUNICATION"
            var r: AudioRecord? = null
            if (!duckMedia) {
                r = runCatching {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, VadEngine.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                }.getOrNull()?.let { if (it.state == AudioRecord.STATE_INITIALIZED) it else { it.release(); null } }
            }
            if (r == null) {
                source = "VOICE_RECOGNITION"
                r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VadEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
            }
            check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
            info = "source=$source aec=${aec?.enabled ?: "n/a"} ns=${ns?.enabled ?: "n/a"} duck=$duckMedia"
            r.startRecording()
            rec = r
        }
        override fun read(buf: ByteArray): Int = rec?.read(buf, 0, buf.size) ?: -1
        override fun stop() {
            rec?.let { runCatching { it.stop() } }
            NavDuck.release(context, focus)
            focus = null
        }
        override fun release() {
            rec?.let { runCatching { it.release() } }
            rec = null
            aec?.release(); aec = null; ns?.release(); ns = null
            NavDuck.release(context, focus)
            focus = null
        }
    }

    private class CarMic(private val carContext: CarContext) : Source {
        override val label = "car"
        private var record: CarAudioRecord? = null
        private var focus: AudioFocusRequest? = null
        @SuppressLint("MissingPermission") // checked in car()
        override fun start() {
            val rec = CarAudioRecord.create(carContext).also { record = it }
            // Waze-style duck, not EXCLUSIVE — exclusive paused media completely.
            focus = NavDuck.hold(carContext)
            rec.startRecording()
        }
        override fun read(buf: ByteArray): Int = record?.read(buf, 0, buf.size) ?: -1
        override fun stop() {
            runCatching { record?.stopRecording() }
            NavDuck.release(carContext, focus)
            focus = null
        }
    }

    companion object {
        private const val TAG = "MicPump"

        /** Own capture + EXTRA_AUDIO_SOURCE needs Android 13. */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun carMicSupported(carContext: CarContext): Boolean =
            isSupported() && carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5 &&
                CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE == VadEngine.SAMPLE_RATE

        private fun granted(c: Context) =
            ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun phone(
            context: Context,
            vad: VadEngine?,
            duckMedia: Boolean = false,
            onFrame: (Float, Float, Long) -> Unit,
        ): MicPump {
            check(granted(context)) { "RECORD_AUDIO not granted" }
            return MicPump(PhoneMic(context, duckMedia), vad, onFrame)
        }

        fun car(carContext: CarContext, vad: VadEngine?, onFrame: (Float, Float, Long) -> Unit): MicPump {
            check(granted(carContext)) { "RECORD_AUDIO not granted" }
            return MicPump(CarMic(carContext), vad, onFrame)
        }
    }
}
