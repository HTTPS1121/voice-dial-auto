package com.example.voicedialauto.car

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.HostException
import com.example.voicedialauto.telecom.OutboundDialer

/**
 * Places a call through Android Auto's own phone UI.
 *
 * CarContext.startCarApp() officially supports "an Intent to make a phone call ... created as
 * defined in Common Intents → Initiate a phone call", i.e. Intent.ACTION_DIAL + tel: URI.
 * On Android Auto the host handles this and starts the call on the car's phone UI.
 * ACTION_CALL (needs CALL_PHONE) is optional/experimental; if the host rejects it we fall back.
 */
object CallLauncher {
    private const val TAG = "CallLauncher"

    fun call(carContext: CarContext, number: String, preferActionCall: Boolean): Boolean {
        if (preferActionCall && OutboundDialer.place(carContext.applicationContext, number, preferDirect = true)) {
            return true
        }
        val uri = Uri.fromParts("tel", number, null)
        if (preferActionCall) {
            try {
                carContext.startCarApp(Intent(Intent.ACTION_CALL, uri))
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "ACTION_CALL rejected, falling back to ACTION_DIAL", e)
            } catch (e: HostException) {
                Log.w(TAG, "ACTION_CALL not handled by host, falling back to ACTION_DIAL", e)
            }
        }
        return try {
            carContext.startCarApp(Intent(Intent.ACTION_DIAL, uri))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start call", e)
            false
        }
    }
}
