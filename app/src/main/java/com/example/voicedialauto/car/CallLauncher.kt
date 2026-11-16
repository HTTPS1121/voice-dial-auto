package com.example.voicedialauto.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.core.content.ContextCompat
import com.example.voicedialauto.util.FileLog

/**
 * Places an outgoing phone call reliably in Android Auto and on the phone.
 *
 * Why `startCarApp(ACTION_DIAL)` alone sometimes doesn't place a call:
 *  1. `ACTION_DIAL` on several head units / dialers only opens the keypad with the number pre-filled
 *     instead of actually dialing.
 *  2. Numbers with spaces, dashes, RTL marks or formatting can be silently ignored by the car dialer.
 *  3. Calling `startCarApp` while AA is in a lifecycle transition can drop the intent without throwing.
 *
 * Strategy:
 *  1. Sanitize the phone number to digits and leading `+` / `*` / `#`.
 *  2. If `CALL_PHONE` is granted (required by the app anyway), place the call directly through
 *     `TelecomManager.placeCall(uri, Bundle)` — Android's native system telecom path that initiates
 *     a real call immediately and automatically routes audio & UI to Android Auto's call screen.
 *  3. Fall back to `carContext.startCarApp(ACTION_CALL)` -> `carContext.startCarApp(ACTION_DIAL)`
 *     -> `applicationContext.startActivity(ACTION_CALL / ACTION_DIAL)`.
 */
object CallLauncher {
    private const val TAG = "CallLauncher"

    fun sanitizeNumber(raw: String): String {
        val trimmed = raw.trim()
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            when {
                ch in '0'..'9' || ch == '*' || ch == '#' -> sb.append(ch)
                ch == '+' && sb.isEmpty() -> sb.append(ch)
            }
        }
        return sb.toString().ifEmpty { trimmed }
    }

    @SuppressLint("MissingPermission")
    fun placeSystemCall(context: Context, rawNumber: String): Boolean {
        val clean = sanitizeNumber(rawNumber)
        if (clean.isBlank()) return false
        val uri = Uri.fromParts("tel", clean, null)
        val appCtx = context.applicationContext
        val hasCallPerm = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

        if (hasCallPerm) {
            val tm = appCtx.getSystemService(TelecomManager::class.java)
            if (tm != null) {
                try {
                    tm.placeCall(uri, Bundle())
                    FileLog.log("call", "TelecomManager.placeCall succeeded")
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "TelecomManager.placeCall failed, trying startActivity", t)
                    FileLog.log("call", "TelecomManager.placeCall failed: ${t.javaClass.simpleName} ${t.message}")
                }
            }
            try {
                appCtx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                FileLog.log("call", "startActivity(ACTION_CALL) succeeded")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "startActivity(ACTION_CALL) failed", t)
                FileLog.log("call", "startActivity(ACTION_CALL) failed: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        return try {
            appCtx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            FileLog.log("call", "startActivity(ACTION_DIAL) succeeded")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "All phone call methods failed", t)
            FileLog.log("call", "all phone call methods failed: ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun call(carContext: CarContext, number: String, preferActionCall: Boolean): Boolean {
        val clean = sanitizeNumber(number)
        if (clean.isBlank()) return false
        val uri = Uri.fromParts("tel", clean, null)
        val hasCallPerm = ContextCompat.checkSelfPermission(carContext, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

        // 1. Primary: TelecomManager.placeCall initiates an actual cellular/telecom call immediately
        //    and Android Auto automatically switches to its in-call screen.
        if (hasCallPerm) {
            val tm = carContext.getSystemService(TelecomManager::class.java)
            if (tm != null) {
                try {
                    tm.placeCall(uri, Bundle())
                    FileLog.log("call", "car TelecomManager.placeCall succeeded", "car")
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "car TelecomManager.placeCall failed, trying startCarApp", t)
                    FileLog.log("call", "car TelecomManager.placeCall failed: ${t.javaClass.simpleName} ${t.message}", "car")
                }
            }
        }

        // 2. CarContext.startCarApp with ACTION_CALL (when permitted or requested), then ACTION_DIAL.
        if (hasCallPerm || preferActionCall) {
            try {
                carContext.startCarApp(Intent(Intent.ACTION_CALL, uri))
                FileLog.log("call", "startCarApp(ACTION_CALL) succeeded", "car")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "startCarApp(ACTION_CALL) failed, falling back", t)
                FileLog.log("call", "startCarApp(ACTION_CALL) failed: ${t.javaClass.simpleName}", "car")
            }
        }

        try {
            carContext.startCarApp(Intent(Intent.ACTION_DIAL, uri))
            FileLog.log("call", "startCarApp(ACTION_DIAL) succeeded", "car")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "startCarApp(ACTION_DIAL) failed, falling back to system call", t)
            FileLog.log("call", "startCarApp(ACTION_DIAL) failed: ${t.javaClass.simpleName}", "car")
        }

        // 3. Final fallback: direct phone-side intent with NEW_TASK.
        return placeSystemCall(carContext, clean)
    }
}
