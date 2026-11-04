package com.example.voicedialauto.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * v9: detects phone-call interruptions and calls [onInterrupt] on the main thread so the dialer
 * can stop TTS + recognition immediately. Signals (any of them, de-duplicated):
 *  - API 31+: AudioManager.addOnModeChangedListener → MODE_RINGTONE / MODE_IN_CALL /
 *    MODE_CALL_SCREENING (no permission needed). MODE_IN_COMMUNICATION (VoIP) is deliberately NOT
 *    used: some voice-input/Bluetooth paths switch to it and it would abort our own listening;
 *    VoIP calls still take audio focus, which stops TTS;
 *  - API 31+: TelephonyCallback.CallStateListener, only if READ_PHONE_STATE was granted (optional);
 *  - API 26–30: PhoneStateListener.LISTEN_CALL_STATE (no permission needed below API 31).
 * [isInCallNow] is also available for one-off checks (e.g. when TTS loses audio focus).
 */
class InterruptionMonitor(context: Context, private val onInterrupt: (reason: String) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val audio = app.getSystemService(AudioManager::class.java)
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private var started = false
    private var modeListener: Any? = null
    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null

    fun start() {
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startApi31() else startLegacy()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopApi31() else stopLegacy()
        }.onFailure { Log.w(TAG, "stop", it) }
    }

    /** True while the phone is ringing or in a call (audio mode based; permission-free). */
    fun isInCallNow(): Boolean = isCallMode(audio.mode)

    private fun fire(reason: String) {
        if (!started) return
        Log.i(TAG, "interrupt: $reason")
        com.example.voicedialauto.util.FileLog.log("call", "interrupt: $reason")
        main.post { if (started) onInterrupt(reason) }
    }

    private fun isCallMode(mode: Int) = mode == AudioManager.MODE_RINGTONE ||
        mode == AudioManager.MODE_IN_CALL ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startApi31() {
        val ml = AudioManager.OnModeChangedListener { mode -> if (isCallMode(mode)) fire("audio_mode=$mode") }
        runCatching { audio.addOnModeChangedListener(mainExecutor, ml); modeListener = ml }
            .onFailure { Log.w(TAG, "mode listener", it) }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state != TelephonyManager.CALL_STATE_IDLE) fire("call_state=$state")
                }
            }
            runCatching { telephony?.registerTelephonyCallback(mainExecutor, cb); telephonyCallback = cb }
                .onFailure { Log.w(TAG, "telephony callback", it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun stopApi31() {
        (modeListener as? AudioManager.OnModeChangedListener)?.let { audio.removeOnModeChangedListener(it) }
        (telephonyCallback as? TelephonyCallback)?.let { telephony?.unregisterTelephonyCallback(it) }
        modeListener = null; telephonyCallback = null
    }

    @Suppress("DEPRECATION")
    private fun startLegacy() {
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state != TelephonyManager.CALL_STATE_IDLE) fire("call_state=$state")
            }
        }
        runCatching { telephony?.listen(l, PhoneStateListener.LISTEN_CALL_STATE); legacyListener = l }
            .onFailure { Log.w(TAG, "legacy listener", it) }
    }

    @Suppress("DEPRECATION")
    private fun stopLegacy() {
        legacyListener?.let { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) }
        legacyListener = null
    }

    private companion object { const val TAG = "InterruptionMonitor" }
}
