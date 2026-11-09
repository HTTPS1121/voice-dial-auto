package com.example.voicedialauto.telecom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.voicedialauto.util.FileLog

/**
 * Places an outbound call without tying it to a visible [Activity].
 * [TelecomManager.placeCall] is tried first when [preferDirect] and CALL_PHONE are granted — it
 * survives the activity going to background during the handoff to the system dialer.
 * Fallback: ACTION_CALL / ACTION_DIAL via applicationContext + [Intent.FLAG_ACTIVITY_NEW_TASK].
 */
object OutboundDialer {
    private const val TAG = "OutboundDialer"

    fun place(context: Context, number: String, preferDirect: Boolean): Boolean {
        val app = context.applicationContext
        val uri = Uri.fromParts("tel", number, null)
        val canCall = ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (preferDirect && canCall) {
            app.getSystemService(TelecomManager::class.java)?.let { tm ->
                runCatching {
                    tm.placeCall(uri, Bundle())
                    FileLog.log("call", "TelecomManager.placeCall $number")
                    Log.i(TAG, "TelecomManager.placeCall")
                    return true
                }.onFailure { Log.w(TAG, "TelecomManager.placeCall failed", it) }
            }
        }
        val action = if (canCall && preferDirect) Intent.ACTION_CALL else Intent.ACTION_DIAL
        if (startActivityCall(app, uri, action)) return true
        return if (action == Intent.ACTION_CALL) startActivityCall(app, uri, Intent.ACTION_DIAL) else false
    }

    private fun startActivityCall(context: Context, uri: Uri, action: String): Boolean =
        runCatching {
            val intent = Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            FileLog.log("call", "startActivity $action $uri")
            Log.i(TAG, "startActivity $action")
            true
        }.getOrElse { e ->
            Log.w(TAG, "startActivity $action failed", e)
            false
        }
}
