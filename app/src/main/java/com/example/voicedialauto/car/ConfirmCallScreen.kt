package com.example.voicedialauto.car

import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import com.example.voicedialauto.R
import com.example.voicedialauto.car.CarUi.bidi
import com.example.voicedialauto.car.CarUi.header
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialState

/**
 * Dedicated pre-call countdown screen on Android Auto.
 * Pushing a dedicated Screen guarantees immediate visual transition off the Listening template,
 * updates every second with zero driving-step cost (title + message stay constant, only the
 * Cancel button label ticks), and restores the task step quota on pop().
 */
class ConfirmCallScreen(
    carContext: CarContext,
    private val controller: DialController,
) : Screen(carContext) {

    private var drawnKey: String? = null

    init {
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = controller.cancel()
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
        com.example.voicedialauto.flow.FlowTimeline.mark("shown", android.os.SystemClock.elapsedRealtime())
        val title = bidi(carContext, carContext.getString(R.string.car_title))
        val retry = CarUi.iconAction(carContext, R.drawable.ic_refresh) { controller.recognizeAgain() }
        return when (val s = controller.state) {
            is DialState.Confirm -> {
                val msg = buildString {
                    append(carContext.getString(R.string.car_confirm_wait, s.entry.displayName))
                    if (s.entry.number.isNotBlank()) append('\n').append(s.entry.number)
                }
                MessageTemplate.Builder(bidi(carContext, msg))
                    .header(carContext, title, retry)
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
                    .header(carContext, title, retry)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_call, CarColor.GREEN))
                    .build()
            }
            else -> {
                MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_idle)))
                    .header(carContext, title, retry)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_mic))
                    .build()
            }
        }
    }
}
