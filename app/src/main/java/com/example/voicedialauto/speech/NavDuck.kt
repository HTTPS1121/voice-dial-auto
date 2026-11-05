package com.example.voicedialauto.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Same duck as Waze turn-by-turn: media lowers, does not pause.
 * The player (Spotify / YouTube / AA host) picks the amount — we don't.
 */
internal object NavDuck {
    fun attrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun hold(am: AudioManager): AudioFocusRequest {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs())
            .setWillPauseWhenDucked(false)
            .build()
        am.requestAudioFocus(req)
        return req
    }

    fun hold(context: Context): AudioFocusRequest =
        hold(context.getSystemService(AudioManager::class.java))

    fun release(am: AudioManager, req: AudioFocusRequest?) {
        if (req != null) runCatching { am.abandonAudioFocusRequest(req) }
    }

    fun release(context: Context, req: AudioFocusRequest?) {
        release(context.getSystemService(AudioManager::class.java), req)
    }
}
