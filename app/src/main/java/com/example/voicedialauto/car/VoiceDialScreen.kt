package com.example.voicedialauto.car

import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.example.voicedialauto.R
import com.example.voicedialauto.car.CarUi.bidi
import com.example.voicedialauto.car.CarUi.header
import com.example.voicedialauto.car.CarUi.numberedTitle
import com.example.voicedialauto.contacts.ContactsCache
import com.example.voicedialauto.flow.DialController
import com.example.voicedialauto.flow.DialError
import com.example.voicedialauto.flow.DialState
import com.example.voicedialauto.flow.FlowTimeline
import com.example.voicedialauto.flow.MicState
import com.example.voicedialauto.util.FileLog
import com.example.voicedialauto.util.Prefs

/**
 * Root car screen (Idle / Listening / errors).
 *
 * Confirm + multi-match live on dedicated Screens ([ConfirmCallScreen] / [ContactListScreen]).
 * Driving hosts often accept push/invalidate but do not paint them — we also call
 * [ResultForceShow] (startCarApp → onNewIntent) so the host is forced to display the top screen.
 * Listening stays frozen (no partial invalidates) so the result transition is not buried in throttle.
 */
class VoiceDialScreen(carContext: CarContext, private val controller: DialController) : Screen(carContext) {

    private val prefs = Prefs(carContext)
    private var lastOutOfRange: Int? = null
    private var rootKey: String? = null
    /** False until the host has called [onGetTemplate] at least once. */
    private var hostHasTemplate = false
    private val throttle = Throttle { if (renderKey(controller.state) != rootKey) invalidate() }

    init {
        controller.onStateChanged = { onState(it) }
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (controller.state) {
                    is DialState.Choosing -> controller.backFromList()
                    is DialState.Confirm -> controller.cancel()
                    else -> {
                        isEnabled = false
                        carContext.onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun onState(s: DialState) {
        if (s is DialState.Processing) return

        val oor = (s as? DialState.Choosing)?.outOfRange
        if (oor != null && oor != lastOutOfRange) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_out_of_range, oor),
                CarToast.LENGTH_SHORT,
            ).show()
        }
        if (s !is DialState.Choosing) lastOutOfRange = null else lastOutOfRange = oor

        when (s) {
            is DialState.Choosing -> {
                throttle.cancel()
                ensureResultScreen("list")
            }
            is DialState.Confirm, is DialState.Calling -> {
                throttle.cancel()
                ensureResultScreen("confirm")
            }
            is DialState.Listening -> {
                ResultForceShow.clear()
                popResultsIfAny()
                val key = renderKey(s)
                if (hostHasTemplate && key == rootKey) return
                refreshRoot()
            }
            else -> {
                ResultForceShow.clear()
                popResultsIfAny()
                refreshRoot()
            }
        }
    }

    /** Push dedicated result Screen + force host display via startCarApp. */
    private fun ensureResultScreen(kind: String) {
        ResultForceShow.request(carContext, kind)
        val top = runCatching { screenManager.top }.getOrNull()
        when (kind) {
            "list" -> {
                if (top is ContactListScreen) {
                    top.refresh()
                    return
                }
                if (top != null && top !is VoiceDialScreen) screenManager.popToRoot()
                FlowTimeline.mark("push", SystemClock.elapsedRealtime())
                screenManager.push(ContactListScreen(carContext, controller))
                FileLog.log("car", "push ContactListScreen (+force-show)", "car")
            }
            "confirm" -> {
                if (top is ConfirmCallScreen) {
                    top.refresh()
                    return
                }
                if (top != null && top !is VoiceDialScreen) screenManager.popToRoot()
                FlowTimeline.mark("push", SystemClock.elapsedRealtime())
                screenManager.push(ConfirmCallScreen(carContext, controller))
                FileLog.log("car", "push ConfirmCallScreen (+force-show)", "car")
            }
        }
    }

    private fun popResultsIfAny() {
        val top = runCatching { screenManager.top }.getOrNull() ?: return
        if (top !is VoiceDialScreen) {
            screenManager.popToRoot()
            rootKey = null
        }
    }

    private fun refreshRoot() {
        if (!hostHasTemplate) return
        val key = renderKey(controller.state)
        if (key == rootKey) return
        if (rootKey == null || key.substringBefore('|') != rootKey!!.substringBefore('|')) invalidate()
        else throttle.request()
    }

    private fun renderKey(s: DialState): String = when (s) {
        is DialState.Listening -> "L|${s.lang}"
        is DialState.Processing -> "L|${prefs.primaryLanguage}"
        is DialState.Confirm -> "C|${s.entry.contactId}|${s.entry.number}|${s.secondsLeft}"
        is DialState.Calling -> "CALL|${s.entry.contactId}|${s.entry.number}"
        is DialState.Choosing ->
            "CH|${s.items.joinToString(",") { "${it.contactId}:${it.number}" }}"
        is DialState.Idle -> "I|${s.lastCalled}|${ContactsCache.count}"
        is DialState.NoMatch -> "NM"
        else -> s.toString()
    }

    override fun onGetTemplate(): Template {
        hostHasTemplate = true
        val s = controller.state
        rootKey = renderKey(s)
        val title = bidi(carContext, carContext.getString(R.string.car_title))
        return when (s) {
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

            is DialState.Listening -> listeningTemplate(title, s.lang)

            is DialState.Processing -> listeningTemplate(title, prefs.primaryLanguage)

            // Fallback if result Screens were not pushed yet (host still showing root briefly).
            is DialState.Confirm -> confirmTemplate(title, s)

            is DialState.Calling ->
                MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_calling, s.entry.displayName)))
                    .header(carContext, title, null)
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_call, CarColor.GREEN))
                    .build()

            is DialState.Choosing -> choosingTemplate(s)

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
        }
    }

    private fun confirmTemplate(title: String, s: DialState.Confirm): Template =
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

    private fun choosingTemplate(s: DialState.Choosing): Template {
        val cap = listCap()
        val items = s.items.take(cap)
        FileLog.log("car", "list template rows=${items.size} cap=$cap of ${s.items.size}", "car")
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

    private fun listCap(): Int = runCatching {
        carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
            .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6).coerceIn(1, 10)

    private fun listeningTemplate(title: String, lang: String): Template {
        val b = MessageTemplate.Builder(bidi(carContext, carContext.getString(R.string.car_listening_lang, langLabel(lang))))
            .header(carContext, title, null)
            .setIcon(CarUi.icon(carContext, R.drawable.ic_mic, CarColor.RED))
        b.addAction(
            Action.Builder()
                .setTitle(bidi(carContext, carContext.getString(R.string.car_cancel)))
                .setBackgroundColor(CarColor.RED)
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener { controller.cancel() }
                .build(),
        )
        b.addAction(
            Action.Builder()
                .setTitle(bidi(carContext, carContext.getString(R.string.car_heard_empty)))
                .setBackgroundColor(CarColor.BLUE)
                .setOnClickListener { controller.recognizeAgain() }
                .build(),
        )
        return b.build()
    }

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
    }
}
