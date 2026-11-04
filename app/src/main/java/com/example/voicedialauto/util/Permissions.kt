package com.example.voicedialauto.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Permissions {
    val REQUIRED = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
    )

    /**
     * v9: optional — lets [com.example.voicedialauto.speech.InterruptionMonitor] use
     * TelephonyCallback on Android 12+. Without it the permission-free audio-mode listener is used.
     */
    val OPTIONAL = listOf(Manifest.permission.READ_PHONE_STATE)

    /** What the phone screen asks for in one dialog. */
    val TO_REQUEST get() = REQUIRED + OPTIONAL

    fun missing(context: Context): List<String> = REQUIRED.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}
