package com.example.voicedialauto.car

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
import com.example.voicedialauto.car.CarUi.header
import com.example.voicedialauto.contacts.ContactEntry
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.flow.MicState
import com.example.voicedialauto.util.Prefs

/**
 * Numbered ListTemplate for (a) several matches and (b) "did you mean" suggestions.
 * Header mic shows the mic state (red = listening, off = tap to listen). v9: the mic opens the
 * moment the list appears and stays red for the whole list window (restarts included); the
 * optional short TTS prompt plays over the open mic (barge-in).
 * Row tap = call immediately. Spoken number/name → countdown from Settings (default 3.5 s, 0 = none) (handled by DialController).
 */
class ContactListScreen(carContext: CarContext, private val controller: DialController) : Screen(carContext) {

    private val prefs = Prefs(carContext)

    init {
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = controller.backFromList()
        })
    }

    private var drawnKey: String? = null
    private val throttle = Throttle { if (key(controller.state) != drawnKey) invalidate() }

    /**
     * v9: only redraw when what's drawn changes. Title and rows stay identical while the mic
     * restarts / partials arrive, so mic icon changes are refreshes (no task step), and repeated
     * restarts don't redraw at all.
     */
    fun refresh() { if (key(controller.state) != drawnKey) throttle.request() }

    private fun key(s: DialState): String = when (s) {
        is DialState.Choosing -> "CH|${s.items.joinToString(",") { "${it.contactId}:${it.number}" }}|${s.mic}"
        is DialState.NoMatch -> "NM|${s.heard}|${s.contactsCount}|${s.topCandidates.map { it.contact.contactId }}|${s.suggestions.map { it.contactId }}"
        else -> "X"
    }

    override fun onGetTemplate(): Template {
        drawnKey = key(controller.state)
        com.example.voicedialauto.flow.FlowTimeline.mark("shown", android.os.SystemClock.elapsedRealtime())
        return build()
    }

    private fun build(): Template = when (val s = controller.state) {
        is DialState.Choosing -> choosing(s)
        is DialState.NoMatch -> suggestions(s)
        // Transitional (we are about to be popped).
        else -> ListTemplate.Builder().header(carContext, carContext.getString(R.string.car_title), null)
            .setLoading(true).build()
    }

    private fun choosing(s: DialState.Choosing): Template {
        val list = ItemList.Builder()
        s.items.forEachIndexed { i, c -> list.addItem(row("${i + 1}. ${c.displayName}", c) { controller.pick(c) }) }
        val mic = when (s.mic) {
            MicState.LISTENING -> CarUi.iconAction(carContext, R.drawable.ic_mic, CarColor.RED) { controller.micPressed() }
            MicState.SPEAKING -> CarUi.iconAction(carContext, R.drawable.ic_volume) { controller.micPressed() }
            MicState.OFF -> CarUi.iconAction(carContext, R.drawable.ic_mic_off) { controller.micPressed() }
        }
        return ListTemplate.Builder()
            .header(carContext, carContext.getString(R.string.car_choose_title), mic)
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
            list.addItem(row(title, sc.contact) { controller.pick(sc.contact) })
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
            .header(carContext, header, mic)
            .setSingleList(list.build())
            .build()
    }

    private fun row(title: String, c: ContactEntry, onClick: () -> Unit) = Row.Builder()
        .setTitle(title)
        .addText(if (c.numberLabel.isNotBlank()) "${c.numberLabel} · ${c.number}" else c.number)
        .setOnClickListener(onClick)
        .build()
}
