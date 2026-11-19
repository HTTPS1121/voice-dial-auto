package com.example.voicedialauto.car

import android.content.ComponentName
import android.content.Intent
import androidx.car.app.CarContext
import com.example.voicedialauto.util.FileLog

/**
 * Forces the Android Auto host to display our app's current top Screen.
 *
 * CarContext.startCarApp → Session.onNewIntent is documented to make the host call
 * onGetTemplate on the top screen **and display it** — bypassing the driving paint
 * throttle that silently drops invalidate()/push updates.
 */
object ResultForceShow {
    const val ACTION = "com.example.voicedialauto.car.SHOW_RESULTS"
    const val EXTRA_KIND = "kind"

    @Volatile
    private var lastKind: String? = null

    fun clear() {
        lastKind = null
    }

    /** Once per result episode (`list` / `confirm`). Safe to call on every state tick. */
    fun request(carContext: CarContext, kind: String) {
        if (lastKind == kind) return
        lastKind = kind
        val intent = Intent(ACTION)
            .setComponent(ComponentName(carContext, VoiceDialCarAppService::class.java))
            .putExtra(EXTRA_KIND, kind)
        try {
            carContext.startCarApp(intent)
            FileLog.log("car", "force-show startCarApp kind=$kind", "car")
        } catch (t: Throwable) {
            lastKind = null
            FileLog.log("car", "force-show FAILED kind=$kind: ${t.message}", "car")
        }
    }
}
