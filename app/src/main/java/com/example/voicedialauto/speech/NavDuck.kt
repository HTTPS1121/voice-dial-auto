package com.example.voicedialauto.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.max

/**
 * Same duck as Waze turn-by-turn: media lowers, does not pause.
 * The player (Spotify / YouTube / AA host) picks the amount — we don't.
 *
 * AA often ignores a focus request until something actually plays on the nav stream.
 * A looping near-silent [AudioTrack] on [USAGE_ASSISTANCE_NAVIGATION_GUIDANCE] is that something.
 */
internal object NavDuck {
    private var pad: AudioTrack? = null
    private var users = 0

    fun attrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun hold(am: AudioManager): AudioFocusRequest {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs())
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        if (users++ == 0) startPad()
        return req
    }

    fun hold(context: Context): AudioFocusRequest =
        hold(context.getSystemService(AudioManager::class.java))

    fun release(am: AudioManager, req: AudioFocusRequest?) {
        if (req != null) runCatching { am.abandonAudioFocusRequest(req) }
        if (users > 0 && --users == 0) stopPad()
    }

    fun release(context: Context, req: AudioFocusRequest?) {
        release(context.getSystemService(AudioManager::class.java), req)
    }

    private fun startPad() {
        runCatching {
            val sr = 16_000
            val min = AudioTrack.getMinBufferSize(
                sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val samples = max(min / 2, sr / 20) // ≥50 ms
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(ShortArray(samples), 0, samples)
            track.setLoopPoints(0, samples, -1)
            // Inaudible, but the stream is active so the AA host ducks now — not after the mic opens.
            track.setVolume(0.001f)
            track.play()
            pad = track
        }
    }

    private fun stopPad() {
        pad?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        pad = null
    }
}
