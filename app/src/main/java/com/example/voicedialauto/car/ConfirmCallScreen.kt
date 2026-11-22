package com.example.voicedialauto.car

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ListTemplate
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
 * Confirm / calling screen.
 *
 * Driving hosts often refuse to leave a MessageTemplate (Listening) for another MessageTemplate
 * in the same throttle window. Every working AA list/search app does this instead:
 *   1) push a new Screen
 *   2) first paint = ListTemplate setLoading(true)  ← forces a visible break from Listening
 *   3) invalidate → real MessageTemplate (loading→content is a free refresh when same List;
 *      here step 3 is MessageTemplate after the spinner already painted)
 */
class ConfirmCallScreen(
    carContext: CarContext,
    private val controller: DialController,
) : Screen(carContext) {

    private var drawnKey: String? = null
    private var passedLoading = false
    private var armedTts = false
    private val main = Handler(Looper.getMainLooper())

    init {
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = controller.cancel()
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // invalidate() before STARTED is a no-op — countdown would stick on 5.
                if (passedLoading) invalidate()
            }
        })
    }

    fun refresh() {
        if (!passedLoading) return
        val k = key(controller.state)
        if (k != drawnKey) invalidate()
    }

    private fun key(s: DialState): String = when (s) {
        is DialState.Confirm -> "C|${s.entry.contactId}|${s.entry.number}|${s.secondsLeft}"
        is DialState.Calling -> "CALL|${s.entry.contactId}|${s.entry.number}"
        else -> "OTHER"
    }

    override fun onGetTemplate(): Template {
        val title = bidi(carContext, carContext.getString(R.string.car_title))

        // Step 1: spinner. Different template type than Listening MessageTemplate → host must paint.
        if (!passedLoading) {
            passedLoading = true
            FileLog.log("car", "confirm LOADING (break Listening)", "car")
            main.post {
                FileLog.log("car", "confirm LOADING → content invalidate", "car")
                invalidate()
            }
            return ListTemplate.Builder()
                .header(carContext, title, null)
                .setLoading(true)
                .build()
        }

        drawnKey = key(controller.state)
        val now = SystemClock.elapsedRealtime()
        FlowTimeline.mark("shown", now)
        if (!armedTts) {
            armedTts = true
            val push = FlowTimeline.current.get("push")
            val sincePush = push?.let { now - it }
            FileLog.log(
                "car",
                "confirm template content" + (sincePush?.let { " push→shown ${it}ms" } ?: ""),
                "car",
            )
            main.postDelayed({ controller.onCarResultScreenShown() }, 350L)
        }

        // Driving MessageTemplate: 2 body actions max. No header end-actions (OEM rejects).
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
