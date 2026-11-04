package com.example.voicedialauto

import android.app.Application
import android.os.Build
import com.google.android.material.color.DynamicColors
import org.lsposed.hiddenapibypass.HiddenApiBypass

class VoiceDialApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        com.example.voicedialauto.util.FileLog.init(this) // v12: persistent rolling log (phone + car)
        com.example.voicedialauto.speech.vad.VadEngine.prewarm(this) // v13: load Silero off the main thread
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    companion object {
        @Volatile lateinit var instance: VoiceDialApp
            private set
    }
}
