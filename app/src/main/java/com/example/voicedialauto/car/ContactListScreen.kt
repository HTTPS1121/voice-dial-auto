package com.example.voicedialauto.car

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.example.voicedialauto.R
import com.example.voicedialauto.car.CarUi.bidi
import com.example.voicedialauto.car.CarUi.header
import com.example.voicedialauto.car.CarUi.numberedTitle
import com.example.voicedialauto.contacts.ContactEntry
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.flow.FlowTimeline
import com.example.voicedialauto.flow.MicState
import com.example.voicedialauto.util.FileLog
import com.example.voicedialauto.util.Prefs

/**
 * Numbered ListTemplate for (a) several matches and (b) "did you mean" suggestions.
 *
 * Same pattern every working AA search/list app uses while driving:
 * first paint = setLoading(true) (breaks out of Listening MessageTemplate), then invalidate
 * with real rows (loading→content is a free refresh and hosts always paint it).
 *
 * Mic opens only after the **content** template is returned (posted), never from ON_START /
 * loading frame — starting SpeechRecognizer there freezes the driving host on Listening.
 */
class ContactListScreen(carContext: CarContext, private val controller: DialController) : Screen(carContext) {

    private val prefs = Prefs(carContext)
    private val main = Handler(Looper.getMainLooper())
    private var passedLoading = false
    private var armedMic = false
    private var drawnKey: String? = null
    private val throttle = Throttle { if (key(controller.state) != drawnKey) invalidate() }

    init {
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = controller.backFromList()
        })
    }

    /**
     * v9: only redraw when what's drawn changes. Title and rows stay identical while the mic
     * restarts / partials arrive, so mic icon changes are refreshes (no task step), and repeated
     * restarts don't redraw at all.
     */
    fun refresh() {
        if (!passedLoading) return
        if (key(controller.state) != drawnKey) throttle.request()
    }

    private fun key(s: DialState): String = when (s) {
        is DialState.Choosing -> "CH|${s.items.joinToString(",") { "${it.contactId}:${it.number}" }}|${s.mic}"
        is DialState.NoMatch -> "NM|${s.heard}|${s.contactsCount}|${s.topCandidates.map { it.contact.contactId }}|${s.suggestions.map { it.contactId }}"
        else -> "X"
    }

    override fun onGetTemplate(): Template {
        if (!passedLoading) {
            passedLoading = true
            FileLog.log("car", "list LOADING (break Listening)", "car")
            main.post {
                FileLog.log("car", "list LOADING → content invalidate", "car")
                invalidate()
            }
            return ListTemplate.Builder()
                .header(carContext, bidi(carContext, carContext.getString(R.string.car_choose_title)), null)
                .setLoading(true)
                .build()
        }

        drawnKey = key(controller.state)
        val now = SystemClock.elapsedRealtime()
        FlowTimeline.mark("shown", now)
        if (!armedMic) {
            armedMic = true
            val push = FlowTimeline.current.get("push")
            val sincePush = push?.let { now - it }
            FileLog.log(
                "car",
                "list template content" + (sincePush?.let { " push→shown ${it}ms" } ?: ""),
                "car",
            )
            main.postDelayed({ controller.onCarResultScreenShown() }, 350L)
        }
        return build()
    }

    private fun build(): Template = when (val s = controller.state) {
        is DialState.Choosing -> choosing(s)
        is DialState.NoMatch -> suggestions(s)
        // Transitional (we are about to be popped).
        else -> ListTemplate.Builder().header(carContext, bidi(carContext, carContext.getString(R.string.car_title)), null)
            .setLoading(true).build()
    }

    private fun listCap(): Int = runCatching {
        carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
            .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6).coerceIn(1, 10)

    private fun choosing(s: DialState.Choosing): Template {
        val cap = listCap()
        val items = s.items.take(cap)
        FileLog.log(
            "car", "list template rows=${items.size} cap=$cap of ${s.items.size}", "car",
        )
        val list = ItemList.Builder()
        items.forEachIndexed { i, c ->
            list.addItem(
                Row.Builder()
                    .setTitle(numberedTitle(carContext, i + 1, c.displayName))
                    .setOnClickListener { controller.pick(c) }
                    .build(),
            )
        }
        val mic = when (s.mic) {
            MicState.LISTENING -> CarUi.iconAction(carContext, R.drawable.ic_mic, CarColor.RED) { controller.micPressed() }
            MicState.SPEAKING -> CarUi.iconAction(carContext, R.drawable.ic_volume) { controller.micPressed() }
            MicState.OFF -> CarUi.iconAction(carContext, R.drawable.ic_mic_off) { controller.micPressed() }
        }
        val retry = CarUi.iconAction(carContext, R.drawable.ic_refresh) { controller.recognizeAgain() }
        return ListTemplate.Builder()
            .header(carContext, bidi(carContext, carContext.getString(R.string.car_choose_title)), mic, retry)
            .setSingleList(list.build())
            .build()
    }

    private fun suggestions(s: DialState.NoMatch): Template {
        val limit = runCatching {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val list = ItemList.Builder()
        // Prefer scored topCandidates so the driver sees why it failed (name + score).
        val scored = s.topCandidates.ifEmpty {
            s.suggestions.map { com.example.voicedialauto.contacts.ScoredContact(it, 0.0) }
        }
        scored.take((limit - 3).coerceAtLeast(1)).forEach { sc ->
            val title = if (sc.score > 0) {
                carContext.getString(R.string.car_top_line, sc.contact.displayName, sc.score)
            } else sc.contact.displayName
            list.addItem(row(bidi(carContext, title), sc.contact) { controller.pick(sc.contact) })
        }
        list.addItem(
            Row.Builder().setTitle(carContext.getString(R.string.car_recognize_again))
                .setImage(CarUi.icon(carContext, R.drawable.ic_mic))
                .setOnClickListener { controller.recognizeAgain() }.build()
        )
        list.addItem(
            Row.Builder().setTitle(carContext.getString(R.string.car_sync))
                .addText(carContext.getString(R.string.car_contacts_count, s.contactsCount))
                .setOnClickListener {
                    controller.syncContacts { n ->
                        CarToast.makeText(carContext, carContext.getString(R.string.car_sync_done, n), CarToast.LENGTH_LONG).show()
                        invalidate()
                    }
                }.build()
        )
        if (limit >= 5) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(if (prefs.secondaryLanguage == Prefs.LANG_EN) R.string.car_try_en else R.string.car_try_he))
                    .setOnClickListener { controller.tryOtherLanguage() }.build()
            )
        }
        val mic = CarUi.iconAction(carContext, R.drawable.ic_mic) { controller.recognizeAgain() }
        val header = carContext.getString(R.string.car_did_you_mean) + " · " +
            carContext.getString(R.string.car_contacts_count, s.contactsCount)
        return ListTemplate.Builder()
            .header(carContext, bidi(carContext, header), mic)
            .setSingleList(list.build())
            .build()
    }

    private fun row(title: String, c: ContactEntry, onClick: () -> Unit) = Row.Builder()
        .setTitle(title)
        .addText(if (c.numberLabel.isNotBlank()) "${c.numberLabel} · ${c.number}" else c.number)
        .setOnClickListener(onClick)
        .build()
}
