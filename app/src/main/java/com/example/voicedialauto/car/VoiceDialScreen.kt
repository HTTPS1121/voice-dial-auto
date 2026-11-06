package com.example.voicedialauto.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import com.example.voicedialauto.R
import com.example.voicedialauto.car.CarUi.bidi
import com.example.voicedialauto.car.CarUi.header
import com.example.voicedialauto.contacts.ContactsCache
import com.example.voicedialauto.contacts.ScoredContact
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialError
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.util.Prefs

/**
 * Root car screen. Renders every non-list [DialState] as a MessageTemplate.
 * Sync-contacts action on Idle / NoMatch / NO_CONTACTS; contact count on no-match.
 */
class VoiceDialScreen(carContext: CarContext, private val controller: DialController) : Screen(carContext) {

    private val prefs = Prefs(carContext)
    private var lastOutOfRange: Int? = null

    init {
        controller.onStateChanged = { onState(it) }
    }

    /**
     * v9 — driving restrictions (Plan task flows, developer.android.com): a task may use at most
     * 5 steps; a MessageTemplate update counts as a NEW STEP unless title AND message are unchanged,
     * a ListTemplate update is a refresh only with the same title and same rows. v8 rewrote the
     * message on every partial transcript ("שמעתי: ד…", "דנ…", "דנה") and again for "מחפש…", so by the
     * time the list was pushed the quota was exhausted → "can't use while driving".
     * Now: (1) Listening and Processing share one constant message; live text goes into an action
     * title (adding/changing a button = refresh); (2) we only invalidate when what is drawn changes
     * ([renderKey]; countdown ticks, mic restarts and partials on the list draw nothing new);
     * (3) invalidations are throttled to one per [MIN_INVALIDATE_MS].
     */
    private fun onState(s: DialState) {
        val top = runCatching { screenManager.top }.getOrNull()
        if (top == null) {
            refreshRoot()
            return
        }
        if (needsList(s)) {
            if (top is ContactListScreen) top.refresh()
            else { rootKey = null; screenManager.push(ContactListScreen(carContext, controller)) }
            val oor = (s as? DialState.Choosing)?.outOfRange
            if (oor != null && oor != lastOutOfRange) {
                CarToast.makeText(
                    carContext,
                    carContext.getString(R.string.car_out_of_range, oor),
                    CarToast.LENGTH_SHORT,
                ).show()
            }
            lastOutOfRange = oor
        } else {
            lastOutOfRange = null
            if (top is ContactListScreen) { screenManager.pop(); rootKey = null }
            refreshRoot()
        }
    }

    private var rootKey: String? = null
    /** False until the host has called [onGetTemplate] at least once. */
    private var hostHasTemplate = false
    private val throttle = Throttle { if (renderKey(controller.state) != rootKey) invalidate() }

    private fun refreshRoot() {
        // Session onStart + contacts-ready often fire listenName before the host asks for the
        // first template. invalidate() in that window is the AA "unexpected error" flash on open.
        if (!hostHasTemplate) return
        val key = renderKey(controller.state)
        if (key == rootKey) return
        // v11: a NEW kind of screen (e.g. Processing → countdown / no-match) is drawn at once;
        // only same-kind updates (live transcript) go through the 400 ms throttle.
        if (rootKey == null || key.substringBefore('|') != rootKey!!.substringBefore('|')) invalidate()
        else throttle.request()
    }

    /** What the root MessageTemplate actually draws for [s] (no countdown seconds, no mic restarts). */
    private fun renderKey(s: DialState): String = when (s) {
        is DialState.Listening -> "L|${s.lang}|${s.partial.orEmpty()}|${s.secondsLeft}"
        is DialState.Processing -> "P|${s.heard}"
        is DialState.Confirm -> "C|${s.entry.contactId}|${s.entry.number}|${s.secondsLeft}"
        is DialState.Choosing -> "CH"
        is DialState.Idle -> "I|${s.lastCalled}|${ContactsCache.count}"
        is DialState.NoMatch -> "NM"
        else -> s.toString()
    }

    override fun onGetTemplate(): Template {
        hostHasTemplate = true
        rootKey = renderKey(controller.state)
        when (controller.state) {
            is DialState.Confirm, is DialState.Calling, is DialState.NoMatch, is DialState.Failed ->
                com.example.voicedialauto.flow.FlowTimeline.mark("shown", android.os.SystemClock.elapsedRealtime())
            else -> Unit
        }
        val title = bidi(carContext, carContext.getString(R.string.car_title))
        val mic = CarUi.iconAction(carContext, R.drawable.ic_mic) { controller.recognizeAgain() }
        return when (val s = controller.state) {
            is DialState.NeedsPermissions -> MessageTemplate.Builder(
                bidi(carContext, carContext.getString(R.string.car_permissions_needed)),
            ).header(carContext, title, null)
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.car_grant))
                        .setBackgroundColor(CarColor.BLUE)
                        .setOnClickListener(
                            ParkedOnlyOnClickListener.create {
                                carContext.requestPermissions(Permissions.list(s.missing)) { _, _ ->
                                    controller.onPermissionsResult()
                                }
                            },
                        )
                        .build(),
                ).build()

            is DialState.Idle -> {
                val msg = buildString {
                    append(carContext.getString(R.string.car_idle))
                    s.lastCalled?.let {
                        append('\n').append(carContext.getString(R.string.car_last_call, it))
                    }
                    append('\n').append(carContext.getString(R.string.car_contacts_count, ContactsCache.count))
                }
                MessageTemplate.Builder(bidi(carContext, msg)).header(carContext, title, null)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_mic))
                    .addAction(primary(R.string.car_recognize) { controller.recognizeAgain() })
                    .addAction(secondary(R.string.car_sync) { syncContacts() })
                    .build()
            }

            // Listening + Processing: SAME title, message and icon → every update is a refresh.
            is DialState.Listening -> listeningTemplate(
                title, s.lang,
                live = s.partial?.takeIf { it.isNotBlank() }?.let { carContext.getString(R.string.car_heard, it) },
                secondsLeft = s.secondsLeft,
            )

            is DialState.Processing -> listeningTemplate(
                title, prefs.primaryLanguage,
                live = carContext.getString(R.string.car_searching, s.heard),
                secondsLeft = 0,
            )

            // Message text is stable (name only). Seconds live on the Cancel button = refresh, not a
            // new task step. Call now skips the timer. No Action.FLAG_DEFAULT.
            is DialState.Confirm -> {
                MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_confirm_wait, s.entry.displayName)))
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

            is DialState.Calling ->
                MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_calling, s.entry.displayName)))
                    .header(carContext, title, mic)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_call, CarColor.GREEN))
                    .build()

            // v12: one message, one primary action; diagnostics live only in the debug log.
            is DialState.NoMatch ->
                MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_no_match_simple)))
                    .header(carContext, title, null)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_mic_off))
                    .addAction(primary(R.string.car_listen_again) { controller.recognizeAgain() })
                    .addAction(secondary(R.string.car_sync) { syncContacts() })
                    .build()

            is DialState.Failed -> {
                val text = when (s.error) {
                    DialError.NOT_AVAILABLE -> carContext.getString(R.string.car_err_not_available)
                    DialError.NETWORK -> carContext.getString(R.string.car_err_network)
                    DialError.LANGUAGE -> carContext.getString(R.string.car_err_language)
                    DialError.NO_CONTACTS ->
                        carContext.getString(R.string.car_no_contacts) + "\n" +
                            carContext.getString(R.string.car_contacts_count, ContactsCache.count)
                    DialError.CALL_FAILED -> carContext.getString(R.string.car_call_failed, s.name ?: "")
                    DialError.OTHER -> carContext.getString(R.string.car_err_other)
                }
                if (s.error == DialError.NO_CONTACTS) {
                    MessageTemplate.Builder(bidi(carContext, text)).header(carContext, title, null)
                        .addAction(primary(R.string.car_sync) { syncContacts() })
                        .addAction(secondary(R.string.car_recognize_again) { controller.recognizeAgain() })
                        .build()
                } else {
                    retryMessage(
                        title,
                        text,
                        otherLanguage = s.error == DialError.NETWORK ||
                            s.error == DialError.LANGUAGE ||
                            s.error == DialError.OTHER,
                    )
                }
            }

            is DialState.Choosing -> MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_on_list)))
                .header(carContext, title, mic).build()
        }
    }

    private fun listeningTemplate(title: String, lang: String, live: String?, secondsLeft: Int): Template {
        val b = MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_listening_lang, langLabel(lang))))
            .header(carContext, title, null)
            .setIcon(CarUi.icon(carContext, R.drawable.ic_mic, CarColor.RED))
        val cancelTitle = if (secondsLeft > 0) {
            carContext.getString(R.string.car_cancel_secs, secondsLeft)
        } else {
            carContext.getString(R.string.car_cancel)
        }
        // Cancel first (primary, stays put). "Heard: …" is the second action so hosts that
        // stack buttons put it underneath instead of shoving Cancel aside.
        b.addAction(
            Action.Builder()
                .setTitle(bidi(carContext, cancelTitle))
                .setBackgroundColor(CarColor.RED)
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener { controller.cancel() }
                .build(),
        )
        if (live != null) b.addAction(secondary(live) { controller.recognizeAgain() })
        return b.build()
    }

    private fun secondary(text: String, onClick: () -> Unit) = Action.Builder()
        .setTitle(text)
        .setOnClickListener(onClick)
        .build()

    private fun syncContacts() {
        controller.syncContacts { n ->
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_sync_done, n),
                CarToast.LENGTH_LONG,
            ).show()
            invalidate()
        }
    }

    private fun formatTop(top: List<ScoredContact>): String {
        if (top.isEmpty()) return "—"
        return top.joinToString("\n") {
            carContext.getString(R.string.car_top_line, it.contact.displayName, it.score)
        }
    }

    private fun retryMessage(title: String, text: String, otherLanguage: Boolean): Template {
        val b = MessageTemplate.Builder(bidi(carContext, text)).header(carContext, title, null)
            .addAction(primary(R.string.car_recognize_again) { controller.recognizeAgain() })
        if (otherLanguage) {
            val lang = if (prefs.secondaryLanguage == Prefs.LANG_EN) R.string.car_try_en else R.string.car_try_he
            b.addAction(secondary(lang) { controller.tryOtherLanguage() })
        }
        return b.build()
    }

    private fun primary(text: Int, onClick: () -> Unit) = Action.Builder()
        .setTitle(carContext.getString(text))
        .setIcon(CarUi.icon(carContext, R.drawable.ic_mic))
        .setBackgroundColor(CarColor.BLUE)
        .setOnClickListener(onClick)
        .build()

    private fun secondary(text: Int, onClick: () -> Unit) = Action.Builder()
        .setTitle(carContext.getString(text))
        .setOnClickListener(onClick)
        .build()

    private fun langLabel(tag: String) = carContext.getString(
        if (tag == Prefs.LANG_HE) R.string.lang_he_short else R.string.lang_en_short,
    )

    private object Permissions {
        fun list(missing: List<String>) =
            missing.ifEmpty { com.example.voicedialauto.util.Permissions.REQUIRED }
    }

    companion object {
        const val MIN_INVALIDATE_MS = 400L

        /** v12: only real multiple matches use the list; no-match is a simple message. */
        fun needsList(s: DialState) = s is DialState.Choosing
    }
}
