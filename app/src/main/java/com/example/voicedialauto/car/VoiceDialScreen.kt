package com.example.voicedialauto.car

import android.os.Handler
import android.os.Looper
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
 * Single car Screen for the whole recognize → result flow.
 *
 * Why results finally paint while driving:
 * Listening/Processing use [ListTemplate] + setLoading(true). When match lands, we invalidate
 * to the real List/Message content. Hosts treat loading→content as a **refresh they must paint**
 * (same path every AA search/POI app uses). Pushing extra Screens / Message→Message is what
 * left the HU stuck on Listening.
 */
class VoiceDialScreen(carContext: CarContext, private val controller: DialController) : Screen(carContext) {

    private val prefs = Prefs(carContext)
    private val main = Handler(Looper.getMainLooper())
    private var lastOutOfRange: Int? = null
    private var rootKey: String? = null
    private var hostHasTemplate = false
    /** "confirm" / "list" once we've armed mic/TTS for the current result. */
    private var resultArmedKind: String? = null
    private val throttle = Throttle { if (renderKey(controller.state) != rootKey) invalidate() }

    init {
        controller.onStateChanged = { onState(it) }
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (controller.state) {
                    is DialState.Choosing -> controller.backFromList()
                    is DialState.Confirm -> controller.cancel()
                    is DialState.Listening, is DialState.Processing -> controller.cancel()
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
        // Drop any leftover pushed screens from older builds — results draw on THIS screen only.
        val top = runCatching { screenManager.top }.getOrNull()
        if (top != null && top !is VoiceDialScreen) {
            screenManager.popToRoot()
            rootKey = null
            resultArmedKind = null
        }

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
            // Stay on the loading ListTemplate — do not invalidate (would fight the result paint).
            is DialState.Processing -> return

            is DialState.Listening -> {
                resultArmedKind = null
                ResultForceShow.clear()
                val key = renderKey(s)
                if (hostHasTemplate && key == rootKey) return
                refreshRoot()
            }

            is DialState.Choosing, is DialState.Confirm, is DialState.Calling -> {
                throttle.cancel()
                // Immediate invalidate — loading→content refresh. No push, no startCarApp race.
                if (!hostHasTemplate) return
                val key = renderKey(s)
                if (key == rootKey) return
                FileLog.log("car", "result invalidate kind=${s::class.simpleName}", "car")
                invalidate()
            }

            else -> {
                resultArmedKind = null
                ResultForceShow.clear()
                refreshRoot()
            }
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
        // Listening + Processing share one key so partials/processing never re-invalidate.
        is DialState.Listening, is DialState.Processing -> "LOAD|${prefs.primaryLanguage}"
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
        armResultUiIfNeeded(s)
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

            // THE FIX: spinner ListTemplate while recognizing — result invalidate is loading→content.
            is DialState.Listening -> loadingList(carContext.getString(R.string.car_listening_lang, langLabel(s.lang)))
            is DialState.Processing -> loadingList(carContext.getString(R.string.car_listening_lang, langLabel(prefs.primaryLanguage)))

            is DialState.Confirm -> confirmTemplate(s)

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

    private fun loadingList(headerTitle: String): Template {
        FileLog.log("car", "root LOADING list (await result)", "car")
        return ListTemplate.Builder()
            .header(carContext, bidi(carContext, headerTitle), null)
            .setLoading(true)
            .build()
    }

    private fun armResultUiIfNeeded(s: DialState) {
        val kind = when (s) {
            is DialState.Confirm, is DialState.Calling -> "confirm"
            is DialState.Choosing -> "list"
            else -> null
        }
        if (kind == null) {
            if (s is DialState.Listening || s is DialState.Processing) resultArmedKind = null
            return
        }
        if (kind == resultArmedKind) return
        resultArmedKind = kind
        val now = SystemClock.elapsedRealtime()
        FlowTimeline.mark("shown", now)
        val matchEnd = FlowTimeline.current.get("matchEnd")
        val sinceMatch = matchEnd?.let { now - it }
        FileLog.log(
            "car",
            "root $kind CONTENT (loading→result)" + (sinceMatch?.let { " match→shown ${it}ms" } ?: ""),
            "car",
        )
        main.post { controller.onCarResultScreenShown() }
    }

    /**
     * Confirm as ListTemplate (not MessageTemplate): same type as the listening spinner, so
     * loading→rows is a free refresh the driving host is required to paint immediately.
     * Row titles stay fixed; only secondary text ticks the countdown (refresh-safe).
     */
    private fun confirmTemplate(s: DialState.Confirm): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(bidi(carContext, carContext.getString(R.string.car_call_now)))
                    .addText(bidi(carContext, s.entry.displayName))
                    .setOnClickListener { controller.callNow() }
                    .build(),
            )
            .addItem(
                Row.Builder()
                    .setTitle(bidi(carContext, carContext.getString(R.string.car_cancel)))
                    .addText(bidi(carContext, carContext.getString(R.string.car_cancel_call_secs, s.secondsLeft)))
                    .setOnClickListener { controller.cancel() }
                    .build(),
            )
            .build()
        return ListTemplate.Builder()
            .header(
                carContext,
                bidi(carContext, carContext.getString(R.string.car_confirm_wait, s.entry.displayName)),
                null,
            )
            .setSingleList(list)
            .build()
    }

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
