package com.example.voicedialauto.car

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.voicedialauto.R
import com.example.voicedialauto.car.CarUi.bidi
import com.example.voicedialauto.car.CarUi.header
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.flow.FlowTimeline
import com.example.voicedialauto.util.FileLog

/**
 * Dedicated pre-call countdown screen on Android Auto.
 * Pushing a dedicated Screen guarantees immediate visual transition off the Listening template,
 * updates every second with zero driving-step cost (title + message stay constant, only the
 * Cancel button label ticks), and restores the task step quota on pop().
 *
 * Confirm TTS waits for the first [onGetTemplate] (posted), not Screen ON_START — same driving
 * race as the list mic.
 */
class ConfirmCallScreen(
    carContext: CarContext,
    private val controller: DialController,
) : Screen(carContext) {

    private var drawnKey: String? = null
    private var armedTts = false
    private val main = Handler(Looper.getMainLooper())

    init {
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = controller.cancel()
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // invalidate() before STARTED is a no-op — that's why the countdown stayed on 5.
                invalidate()
            }
        })
    }

    fun refresh() {
        val k = key(controller.state)
        if (k != drawnKey) invalidate()
    }

    private fun key(s: DialState): String = when (s) {
        is DialState.Confirm -> "C|${s.entry.contactId}|${s.entry.number}|${s.secondsLeft}"
        is DialState.Calling -> "CALL|${s.entry.contactId}|${s.entry.number}"
        else -> "OTHER"
    }

    override fun onGetTemplate(): Template {
        drawnKey = key(controller.state)
        val now = SystemClock.elapsedRealtime()
        FlowTimeline.mark("shown", now)
        if (!armedTts) {
            armedTts = true
            val push = FlowTimeline.current.get("push")
            val sincePush = push?.let { now - it }
            FileLog.log(
                "car",
                "confirm template first" + (sincePush?.let { " push→shown ${it}ms" } ?: ""),
                "car",
            )
            // Tiny delay so the forced paint settles before TTS grabs audio focus.
            main.postDelayed({ controller.onCarResultScreenShown() }, 350L)
        }
        val title = bidi(carContext, carContext.getString(R.string.car_title))
        // Driving MessageTemplate allows 2 body actions. A header end-action on top of those
        // is rejected on many head units ("can't use while driving") — the call still goes out
        // because the state machine is already in Confirm, but the countdown never appears.
        return when (val s = controller.state) {
            is DialState.Confirm -> {
                val msg = carContext.getString(R.string.car_confirm_wait, s.entry.displayName)
                MessageTemplate.Builder(bidi(carContext, msg))
                    .header(carContext, title, null)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_call, CarColor.GREEN))
                    .addAction(
                        Action.Builder()
                            .setTitle(bidi(carContext, carContext.getString(R.string.car_call_now)))
                            .setBackgroundColor(CarColor.GREEN)
                            .setOnClickListener { controller.callNow() }
                            .build(),
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle(bidi(carContext, carContext.getString(R.string.car_cancel_call_secs, s.secondsLeft)))
                            .setBackgroundColor(CarColor.RED)
                            .setOnClickListener { controller.cancel() }
                            .build(),
                    )
                    .build()
            }
            is DialState.Calling -> {
                MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_calling, s.entry.displayName)))
                    .header(carContext, title, null)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_call, CarColor.GREEN))
                    .build()
            }
            else -> {
                MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_idle)))
                    .header(carContext, title, null)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_mic))
                    .build()
            }
        }
    }
}
