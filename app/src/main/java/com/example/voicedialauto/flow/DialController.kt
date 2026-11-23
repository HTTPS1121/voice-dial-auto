package com.example.voicedialauto.flow

import com.example.voicedialauto.contacts.CancelDetector
import com.example.voicedialauto.contacts.ChoiceResolver
import com.example.voicedialauto.contacts.ContactEntry
import com.example.voicedialauto.contacts.ContactMatcher
import com.example.voicedialauto.contacts.MatchAttemptLog
import com.example.voicedialauto.contacts.QueryExtractor
import com.example.voicedialauto.contacts.SpokenNumberParser
import kotlin.math.ceil

/**
 * Platform-neutral dialer state machine (SPEC.md §3). Pure Kotlin → unit-tested on the JVM.
 * The phone activity and the Android Auto screens only render [state] and forward user input.
 * Must be driven from a single (main) thread.
 *
 * Anti-loop rules (v4):
 *  - every recognizer session gets a token; callbacks from older sessions, and any callback after
 *    the first terminal one (results/error) of a session, are ignored;
 *  - automatic starts (resume, silence restart, BUSY/CLIENT retry) pass a circuit breaker
 *    ([DialConfig.maxAutoStarts] per [DialConfig.autoStartWindowMs]); user actions reset it;
 *    opening the app (icon / first onVisible) is a user start, not automatic;
 *  - silence restarts continue until the (configurable) window expires, but a session that failed
 *    fast (< minHealthySessionMs) is restarted at most maxFastRestarts times and passes the breaker;
 *    healthy restarts don't count against the breaker (they are ≥ 1.5 s apart by definition);
 *  - at most ONE transient-error (BUSY/CLIENT) retry, with backoff, per request;
 *  - when the window expires with no speech in progress, the mic is closed;
 *  - onVisible()/onHidden() are idempotent; a hide shorter than [DialConfig.hideDebounceMs] is
 *    ignored (AA first-paint flap). After a real hide, Listening/Processing resume; Idle stays
 *    Idle unless away ≥ minAwayForAutoListenMs or the launcher sends restart().
 */
class DialController(
    private val speech: SpeechInput,
    private val contacts: ContactsSource,
    private val caller: Caller,
    private val scheduler: Scheduler,
    private val permissions: () -> List<String>,
    private val languages: LanguageSource,
    private val config: DialConfig = DialConfig(),
    private val speaker: Speaker? = null,
    private val prompts: Prompts? = null,
    private val timings: TimingSource? = null,
    /** v11: matching runs here (Android: background thread; tests: inline). */
    private val worker: Worker = Worker.Inline,
    /** When false (default), leaving the UI stops the session. A live outgoing call is never cancelled. */
    private val continueWhenHidden: () -> Boolean = { false },
) {
    var state: DialState = DialState.Idle()
        private set

    /** Called on every state change. */
    var onStateChanged: ((DialState) -> Unit)? = null
    /** Microphone level while listening (for a VU ring). */
    var onLevel: ((Float) -> Unit)? = null

    private val matcher = ContactMatcher(threshold = config.matchThreshold)
    /** "phone" or "car" — set by the host for the debug log. */
    var matchSource: String = ""
    /**
     * Car: set before the first [onVisible] when phone audio may still be in call mode after hangup.
     * Skips starting the mic until the host clears it and calls [restart].
     */
    var deferInitialListen: Boolean = false
    /** Current listening request (name or list): window end, fast-failure budget, session start. */
    private var windowEnd = 0L
    private var fastRestarts = 0
    private var sessionStartedAt = 0L
    private var heardInSession = false
    private var deadline: Cancellable? = null

    private fun nameWindowMs() = (timings?.nameWindowMs ?: config.nameWindowMs).coerceIn(1_000, 600_000)
    private fun choiceWindowMs() = (timings?.choiceWindowMs ?: config.choiceWindowMs).coerceIn(1_000, 600_000)
    private fun countdownMs() = (timings?.countdownMs ?: config.countdownMs).coerceIn(0, 60_000)
    private fun listCountdownMs() = (timings?.listCountdownMs ?: config.listCountdownMs).coerceIn(0, 60_000)
    private val autoGuard = RateLimiter(config.maxAutoStarts, config.autoStartWindowMs)

    private var opened = false
    private var visible = false
    private var hiddenAt: Long? = null
    private var lastCallAt: Long? = null
    private var lastCalledName: String? = null
    private var transientRetries = 0
    private var token = 0
    private var pending: Cancellable? = null // countdown tick or delayed restart
    private var suppressNextVisible = false
    private var hideTask: Cancellable? = null
    /** Set when a real hide stopped the session; next onVisible stays Idle until a user action. */
    private var stoppedOnHide = false
    /** Set when a real hide kept the session running in the background (continueWhenHidden = true). */
    private var runningWhileHidden = false
    private var duckHold: Cancellable? = null
    /** True after [commitHide] actually released the mic. False if hide was only scheduled. */
    private var hideCommitted = false
    /** Last non-blank partial of the current listening session (car endpointing fallback). */
    private var lastPartial: String? = null
    /** Words we already armed [nameCommit] for. Grows only when the user is still talking. */
    private var heldWords: List<String> = emptyList()
    /** Fires [nameHoldMs] after the utterance stopped growing. Independent of Google's onResults. */
    private var nameCommit: Cancellable? = null
    /**
     * Car list/confirm: state is already Choosing/Confirm (first root template is correct) but
     * SpeechRecognizer / TTS wait for [onCarResultScreenShown] after that template is delivered.
     */
    private var carListAwaitingUi = false
    private var carConfirmAwaitingUi = false
    private var pendingConfirmPrompt: Pair<String, String>? = null
    /** Deferred list-mic / confirm-TTS after the car host has the first result template. */
    private var carPaintTask: Cancellable? = null

    /** Number of times the recognizer was started (diagnostics / tests). */
    var recognizerStarts = 0
        private set

    // ------------------------------------------------------------------ lifecycle

    /** App/screen became visible (phone onStart, car session onStart). Idempotent. */
    fun onVisible() {
        hideTask?.cancel(); hideTask = null
        if (visible) { emit(); return }
        visible = true
        // AA onStop/onStart flap: hide never committed, mic still open, stay on Listening.
        if (opened && !hideCommitted) {
            hiddenAt = null
            emit()
            return
        }
        hideCommitted = false
        val now = scheduler.now()
        val away = hiddenAt?.let { now - it } ?: Long.MAX_VALUE
        hiddenAt = null
        val longAway = away >= config.minAwayForAutoListenMs

        if (suppressNextVisible) {
            suppressNextVisible = false
            when (state) {
                // The mic was released in onHidden(); show the idle mic instead of a stale "listening".
                is DialState.Listening, is DialState.Processing, is DialState.Calling -> { set(DialState.Idle(lastCalledName)); return }
                is DialState.NeedsPermissions -> Unit
                else -> { emit(); return }
            }
        }
        val missing = permissions()
        if (missing.isNotEmpty()) { set(DialState.NeedsPermissions(missing)); return }

        if (stoppedOnHide) {
            // Car: finishCarApp → reopen often gets an AA onStop/onStart flap (< minAway). The old
            // longAway gate left us Idle with no mic and no duck. Treat car re-show as a real open
            // unless we're inside the post-call quiet window on the same controller.
            if (matchSource == "car" && !recentlyCalled()) {
                stoppedOnHide = false
                holdAutoListen = false
            } else if (matchSource == "car" && longAway) {
                stoppedOnHide = false
                holdAutoListen = false
            } else if (matchSource != "car" && longAway && !recentlyCalled()) {
                stoppedOnHide = false
                holdAutoListen = false
            } else {
                if (state is DialState.Calling) set(DialState.Idle(lastCalledName)) else emit()
                return
            }
        }

        if (runningWhileHidden) {
            runningWhileHidden = false
            when (val cur = state) {
                // Background session is still actively listening / matching / counting down:
                // resume UI seamlessly without killing the open mic or resetting the countdown.
                is DialState.Listening, is DialState.Processing, is DialState.Confirm -> {
                    emit()
                    return
                }
                is DialState.Choosing -> if (cur.mic == MicState.LISTENING) {
                    emit()
                    return
                }
                else -> Unit
            }
        }

        val s = state
        val lang = languages.primaryLanguage
        if (holdAutoListen && opened) {
            if (matchSource == "car" && !recentlyCalled() && s !is DialState.Calling) {
                holdAutoListen = false
            } else if (matchSource == "car" && longAway && s !is DialState.Calling) {
                holdAutoListen = false
            } else {
                // After a phone call ended or interrupted us: clear Calling / Listening to Idle.
                if (s is DialState.Listening || s is DialState.Processing || s is DialState.Confirm || s is DialState.Calling) {
                    set(DialState.Idle(lastCalledName))
                } else if (s is DialState.Choosing) {
                    set(s.copy(mic = MicState.OFF, partial = null))
                } else {
                    emit()
                }
                return
            }
        }
        when {
            !opened -> {
                opened = true
                userAction()
                if (deferInitialListen) {
                    FlowLog.d("flow", "[$matchSource] defer initial listen (call audio still settling)")
                    set(DialState.Idle(lastCalledName))
                } else {
                    listenName(lang)
                }
            }
            s is DialState.NeedsPermissions -> listenName(lang)
            // Host paused us; UI still says listening — open the mic again, never dump to Idle.
            s is DialState.Listening || s is DialState.Processing -> listenName(lang, auto = true)
            s is DialState.Calling ->
                if (recentlyCalled() || !longAway) set(DialState.Idle(lastCalledName))
                else listenName(lang, auto = true)
            // Car reopen after hangup/flap: real user open → listenName (not auto) so circuit breaker
            // and a failed IN_CALL start can't leave us Idle without duck.
            s is DialState.Idle ->
                when {
                    recentlyCalled() && matchSource != "car" -> set(DialState.Idle(lastCalledName))
                    recentlyCalled() && matchSource == "car" && !longAway -> set(DialState.Idle(lastCalledName))
                    matchSource == "car" -> { userAction(); listenName(lang) }
                    longAway -> listenName(lang, auto = true)
                    else -> emit()
                }
            s is DialState.Choosing ->
                if (longAway && autoGuard.tryAcquire(now)) listenChoice(s.items) else set(s.copy(mic = MicState.OFF))
            s is DialState.NoMatch || s is DialState.Failed -> emit()
            s is DialState.Confirm -> emit()
            else -> if (longAway) listenName(lang, auto = true) else set(DialState.Idle(lastCalledName))
        }
    }

    /** Milliseconds left in the current listen window (0 if none). */
    fun remainingWindowMs(): Long = (windowEnd - scheduler.now()).coerceAtLeast(0L)

    /** True while an active listen / match / countdown should keep running in the background. */
    fun hasActiveBackgroundWork(): Boolean = when (val s = state) {
        is DialState.Listening, is DialState.Processing, is DialState.Confirm -> true
        is DialState.Choosing -> s.mic == MicState.LISTENING || carListAwaitingUi
        else -> false
    }

    /** If [onHidden] debounce is pending, apply it now (e.g. session [onDestroy] before debounce elapses). */
    fun flushPendingHide() {
        hideTask?.cancel(); hideTask = null
        if (!visible && !hideCommitted) commitHide()
    }

    /**
     * Session/activity is gone. Always stop listen/TTS unless an outgoing call is in flight —
     * the host tears the car session down as soon as dialing starts.
     */
    fun onDestroyed() {
        hideTask?.cancel(); hideTask = null
        carListAwaitingUi = false
        carConfirmAwaitingUi = false
        pendingConfirmPrompt = null
        carPaintTask?.cancel(); carPaintTask = null
        visible = false
        hideCommitted = true
        runningWhileHidden = false
        if (state is DialState.Calling) {
            // Phone owns audio now — never leave NavDuck pinned on a dying session (breaks the
            // next open: no duck + interrupt race after hangup).
            stopSpeech()
            dropDuck()
            return
        }
        stopAll()
        dropDuck()
        stoppedOnHide = true
        set(DialState.Idle(lastCalledName))
    }

    /** App/screen hidden: release mic and TTS, cancel a pending countdown (unless continueWhenHidden). Idempotent. */
    fun onHidden() {
        if (!visible) return
        visible = false
        hiddenAt = scheduler.now()
        hideTask?.cancel()
        // Confirm + «המשך אחרי יציאה» off: cancel immediately so a tick inside the 400ms AA flap
        // window cannot place the call after the user already left.
        if (!continueWhenHidden() && state is DialState.Confirm) {
            commitHide()
            return
        }
        val delay = config.hideDebounceMs
        if (delay <= 0) commitHide()
        else hideTask = scheduler.post(delay) { hideTask = null; commitHide() }
    }

    private fun commitHide() {
        hideCommitted = true
        when (val s = state) {
            is DialState.Calling -> {
                // Host tears the car session down when the phone call starts. Keep the call + duck.
                runningWhileHidden = false
                stopSpeech()
            }
            else -> if (continueWhenHidden()) {
                when (s) {
                    // Keep confirmation TTS ("נמצא שם אחד...") playing, keep duck, keep countdown ticking!
                    is DialState.Confirm -> {
                        runningWhileHidden = true
                        emit()
                    }
                    // Keep the microphone actively listening and matching in the background!
                    is DialState.Listening, is DialState.Processing -> {
                        runningWhileHidden = true
                        emit()
                    }
                    is DialState.Choosing -> {
                        if (s.mic == MicState.LISTENING) {
                            runningWhileHidden = true
                            emit()
                        } else {
                            runningWhileHidden = false
                            stopSpeech()
                            dropDuck()
                            set(s.copy(partial = null))
                        }
                    }
                    else -> {
                        runningWhileHidden = false
                        stopSpeech()
                        dropDuck()
                        emit()
                    }
                }
            } else {
                runningWhileHidden = false
                stopAll()
                dropDuck()
                stoppedOnHide = true
                set(DialState.Idle(lastCalledName))
            }
        }
    }

    /**
     * v9: phone call ringing/off-hook (or audio mode RINGTONE/IN_CALL) -> stop TTS and recognition
     * IMMEDIATELY and go idle. No automatic listening afterwards until the user acts (tap / launcher).
     * Our own outgoing call (state [DialState.Calling]) only releases the mic and keeps its state.
     */
    fun onInterrupted() {
        FlowLog.d("flow", "[$matchSource] interrupted (phone call / audio) in ${describe(state)}")
        holdAutoListen = true
        stopAll()
        dropDuck()
        when (state) {
            is DialState.Calling, is DialState.NeedsPermissions, is DialState.Idle -> emit()
            else -> set(DialState.Idle(lastCalledName))
        }
    }

    /** Phone call disconnected: never leave the UI stuck on the previous Calling screen. */
    fun onCallEnded() {
        FlowLog.d("flow", "[$matchSource] call ended in ${describe(state)}")
        lastCallAt = scheduler.now()
        dropDuck()
        if (state is DialState.Calling) {
            set(DialState.Idle(lastCalledName))
        }
    }

    /** Set by [onInterrupted]; cleared by any user action. Blocks auto-listen in [onVisible]. */
    private var holdAutoListen = false

    /** E.g. before opening the settings screen: the next onVisible() keeps the current state. */
    fun suppressNextResume() { suppressNextVisible = true }

    fun onPermissionsResult() {
        if (state !is DialState.NeedsPermissions) return
        val missing = permissions()
        if (missing.isEmpty()) { opened = true; userAction(); listenName(languages.primaryLanguage) }
        else set(DialState.NeedsPermissions(missing))
    }

    // ------------------------------------------------------------------ user input

    /** Launcher icon tapped again / app re-opened via intent. */
    fun restart() {
        hideTask?.cancel(); hideTask = null
        hideCommitted = false
        visible = true
        val missing = permissions()
        if (missing.isNotEmpty()) { set(DialState.NeedsPermissions(missing)); return }
        opened = true
        userAction(); listenName(languages.primaryLanguage)
    }

    fun recognizeAgain() { userAction(); listenName(languages.primaryLanguage) }

    fun tryOtherLanguage() { userAction(); listenName(languages.secondaryLanguage) }

    fun toggleLanguage() {
        userAction()
        languages.togglePrimaryLanguage()
        if (state is DialState.Listening) listenName(languages.primaryLanguage) else emit()
    }

    fun cancel() {
        FlowLog.d("flow", "cancel (state=${describe(state)})")
        userAction()
        carListAwaitingUi = false
        carConfirmAwaitingUi = false
        pendingConfirmPrompt = null
        carPaintTask?.cancel(); carPaintTask = null
        stopAll(); dropDuck(); set(DialState.Idle())
    }

    /** Big mic button / header mic action. */
    fun micPressed() {
        userAction()
        when (val s = state) {
            is DialState.Listening -> cancel()
            is DialState.Choosing ->
                if (s.mic == MicState.LISTENING) { stopAll(); set(s.copy(mic = MicState.OFF, partial = null)) }
                else listenChoice(s.items)
            else -> listenName(languages.primaryLanguage)
        }
    }

    /** v12: tap on a row → exactly like a single match: countdown + "בטל התקשרות". */
    fun pick(entry: ContactEntry) {
        userAction()
        startCountdown(entry, null, prompts?.picked(entry.displayName), listCountdownMs())
    }

    /** "Call now" during the countdown. */
    fun callNow() {
        val s = state as? DialState.Confirm ?: return
        userAction(); stopAll(); call(s.entry)
    }

    /** Back from the car list screen → new name lookup. */
    fun backFromList() { userAction(); listenName(languages.primaryLanguage) }

    /**
     * Manual "סנכרן אנשי קשר". Reloads from the ContentResolver; [onDone] gets the new count.
     * Does not change the dial state (caller shows a toast / CarToast).
     */
    fun syncContacts(onDone: (count: Int) -> Unit) {
        contacts.refresh { list -> onDone(list.size) }
    }

    private fun userAction() { autoGuard.reset(); holdAutoListen = false; stoppedOnHide = false; runningWhileHidden = false }

    // ------------------------------------------------------------------ name lookup

    private fun listenName(lang: String, auto: Boolean = false) {
        hideTask?.cancel(); hideTask = null
        hideCommitted = false
        stopAll()
        if (auto && !autoGuard.tryAcquire(scheduler.now())) {
            // Circuit breaker: too many automatic starts → wait for the user.
            dropDuck(); set(DialState.Idle(lastCalledName)); return
        }
        speech.pinDuck()
        beginWindow(nameWindowMs()) {
            if (state is DialState.Listening || state is DialState.Processing) {
                stopAll(); enterNoMatch(DialState.NoMatch("", emptyList()))
            }
        }
        contacts.ensureFresh() // v11: reload only if empty / stale / changed (was: full reload every time)
        pendingFirst = null
        continuations = 0
        startName(lang)
    }

    /** v12: words captured before the engine ended a session; more may follow (continuation). */
    private var pendingFirst: List<String>? = null
    private fun wordCount(t: String) = t.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } }

    /** Real words only. A trailing 1-letter flicker ("חני ה", "חני.") is not a new word. */
    private fun nameWords(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

    /**
     * True when the driver is still talking: first words, an extra word, or the last word got longer
     * ("חנ" > "חני"). A rewrite of the same words ("חני" / "חני." / "חנה") does not count —
     * that flicker is what kept the phone session open until the listen timer.
     */
    private fun utteranceGrew(prev: List<String>, next: List<String>): Boolean {
        if (next.isEmpty()) return false
        if (prev.isEmpty() || next.size > prev.size) return true
        if (next.size == prev.size) {
            val a = prev.last()
            val b = next.last()
            if (b.length > a.length && b.startsWith(a)) return true
        }
        return false
    }

    private fun armNameCommit(my: Int) {
        nameCommit?.cancel()
        nameCommit = scheduler.post(config.nameHoldMs) {
            nameCommit = null
            if (my != token) return@post
            commitHeardName()
        }
    }

    /** Close the mic and match [lastPartial]. Does not wait for Google onResults. */
    private fun commitHeardName() {
        nameCommit?.cancel(); nameCommit = null
        if (state !is DialState.Listening) return
        val raw = lastPartial?.trim().orEmpty()
        if (nameWords(raw).isEmpty()) return
        token++
        speech.cancel()
        onNameResults(listOf(raw))
    }
    private var continuations = 0

    /**
     * "חני" + "כהן" → "חני כהן" (combinations of the alternatives, best first). The first part is
     * NOT kept alone: it would make "חני לוי" look as good as "חני כהן" and force a list.
     */
    private fun combine(first: List<String>?, more: List<String>): List<String> {
        if (first.isNullOrEmpty()) return more
        if (more.isEmpty()) return first
        val out = LinkedHashSet<String>()
        more.forEach { out += "${first[0]} $it".trim() }
        first.drop(1).forEach { out += "$it ${more[0]}".trim() }
        return out.take(6)
    }

    /** Starts a listening window; [onExpired] runs at its end unless speech is in progress then. */
    private fun beginWindow(lengthMs: Long, onExpired: () -> Unit) {
        val now = scheduler.now()
        windowEnd = now + lengthMs
        fastRestarts = 0
        transientRetries = 0
        deadline?.cancel()
        deadline = scheduler.post(lengthMs) { expireWindow(onExpired) }
    }

    private fun expireWindow(onExpired: () -> Unit) {
        deadline = null
        // Speech already heard: match it now. Waiting here is the "list appears only when the
        // timer ends" bug — the phone's recognizer never closes a short name by itself.
        if (state is DialState.Listening && nameWords(lastPartial.orEmpty()).isNotEmpty()) {
            commitHeardName()
            return
        }
        onExpired()
    }

    private fun markSessionStart() { sessionStartedAt = scheduler.now(); heardInSession = false }

    /** Should a session that just ended in silence/no-match be restarted (within the window)? */
    private fun mayRestartAfterSilence(minHealthyMs: Long = config.minHealthySessionMs): Boolean {
        val now = scheduler.now()
        if (windowEnd - now < config.minRemainingForRestartMs) return false
        val healthy = now - sessionStartedAt >= minHealthyMs
        if (healthy) return true
        if (fastRestarts >= config.maxFastRestarts) return false
        if (!autoGuard.tryAcquire(now)) return false
        fastRestarts++
        return true
    }

    /**
     * One recognizer session on the name screen. v12: continuous options (long engine hints, warm
     * instance reuse, immediate restart) + a segmented session on Android 13+ so a pause between
     * two words doesn't end it. [continuation] = a short follow-up session after the ENGINE ended
     * the previous one; its words are appended to [pendingFirst].
     */
    private fun startName(lang: String, continuation: Boolean = false) {
        lastPartial = null
        heldWords = emptyList()
        nameCommit?.cancel(); nameCommit = null
        if (!continuation) FlowTimeline.begin(scheduler.now())
        val my = ++token
        val prefix = pendingFirst?.firstOrNull()
        val shown = DialState.Listening(lang, prefix)
        if (state != shown) set(shown)
        markSessionStart()
        recognizerStarts++
        val opts = ListenOptions(
            continuous = true,
            maxSessionMs = if (continuation) config.continuationMs + 8_000 else (windowEnd - scheduler.now()).coerceAtLeast(0),
            segmented = true,
        )
        if (continuation) {
            // No new word within continuationMs → the driver is done: match what we have.
            pending?.cancel()
            pending = scheduler.post(config.continuationMs) {
                pending = null
                if (my == token && !heardInSession) finishContinuation(emptyList())
            }
        }
        speech.start(lang, sessionListener(my, object : SpeechListener {
            override fun onPartial(text: String) {
                if (text.any { it.isLetterOrDigit() }) {
                    heardInSession = true
                    lastPartial = text
                    val words = nameWords(text)
                    if (utteranceGrew(heldWords, words)) {
                        heldWords = words
                        armNameCommit(my)
                    }
                }
                if (state is DialState.Listening) {
                    val t = if (prefix != null && text.isNotBlank()) "$prefix $text" else (text.ifBlank { prefix ?: "" })
                    set(DialState.Listening(lang, t.ifBlank { null }))
                }
            }
            override fun onLevel(rmsDb: Float) { onLevel?.invoke(rmsDb) }
            override fun onResults(hypotheses: List<String>) = onFinalResults(hypotheses, byApp = true)
            override fun onFinalResults(hypotheses: List<String>, byApp: Boolean) {
                // Prefer final results; if the recognizer returns nothing after stopListening,
                // fall back to the last partial (common on car hosts with road noise).
                val hyp = hypotheses.filter { it.isNotBlank() }.ifEmpty {
                    listOfNotNull(lastPartial?.takeIf { it.isNotBlank() })
                }
                lastPartial = null
                when {
                    hyp.isEmpty() && continuation -> finishContinuation(emptyList())
                    hyp.isEmpty() -> onNameError(RecognitionError.NO_SPEECH, lang, my)
                    else -> { val all = combine(pendingFirst, hyp); pendingFirst = null; onNameResults(all) }
                }
            }
            override fun onError(error: RecognitionError) =
                if (continuation && (error == RecognitionError.NO_SPEECH || lastPartial == null))
                    finishContinuation(listOfNotNull(lastPartial))
                else onNameError(error, lang, my)
        }), opts)
    }

    /**
     * v12: the engine ended the session after [hyp] (often right after the first word). Instead of
     * matching "חני" we keep the mic open for [DialConfig.continuationMs]: if the driver goes on
     * ("כהן") the words are appended, otherwise we match what we have. The overall name window no
     * longer applies (speech was heard), and the UI keeps showing "listening".
     */
    private fun beginContinuation(lang: String, hyp: List<String>) {
        FlowLog.d("flow", "[$matchSource] engine ended session after $hyp → continuation #${continuations + 1} (${config.continuationMs}ms)")
        deadline?.cancel(); deadline = null
        pendingFirst = combine(pendingFirst, hyp)
        continuations++
        FlowTimeline.mark("continuation$continuations", scheduler.now())
        startName(lang, continuation = true)
    }

    private fun finishContinuation(more: List<String>) {
        FlowLog.d("flow", "[$matchSource] continuation done more=$more first=$pendingFirst")
        pending?.cancel(); pending = null
        val all = combine(pendingFirst, more)
        pendingFirst = null
        token++
        speech.cancel()
        if (all.isEmpty()) { enterNoMatch(DialState.NoMatch("", emptyList(), 0, emptyList())); return }
        onNameResults(all)
    }

    private fun logAttempt(
        hypotheses: List<String>,
        lastPartial: String?,
        extracted: List<String>,
        contactsCount: Int,
        verdict: String,
        top: List<com.example.voicedialauto.contacts.ScoredContact>,
        decision: String,
    ) {
        FlowLog.d("match", "[$matchSource] verdict=$verdict hyp=$hypotheses partial=$lastPartial query=$extracted " +
            "contacts=$contactsCount top=${top.joinToString { "${it.contact.displayName}:${"%.2f".format(it.score)}" }} " +
            "decision=$decision timing=${FlowTimeline.current.format()}")
        MatchAttemptLog.add(
            MatchAttemptLog.Attempt(
                atMs = scheduler.now(),
                source = matchSource,
                hypotheses = hypotheses,
                lastPartial = lastPartial,
                extracted = extracted,
                contactsCount = contactsCount,
                verdict = verdict,
                top = top.map { it.contact.displayName to it.score },
                decision = decision,
                timeline = FlowTimeline.current,
            )
        )
    }

    private fun onNameError(error: RecognitionError, lang: String, my: Int) {
        // Same session token check is already applied by sessionListener; still honour restarts.
        val partial = lastPartial
        if (error == RecognitionError.NO_SPEECH && !partial.isNullOrBlank()) {
            lastPartial = null
            val all = combine(pendingFirst, listOf(partial)); pendingFirst = null
            onNameResults(all)
            return
        }
        when (error) {
            RecognitionError.BUSY, RecognitionError.CLIENT ->
                if (transientRetries++ < config.transientRetries && autoGuard.tryAcquire(scheduler.now()))
                    later(config.transientBackoffMs, my) { startName(lang) }
                else fail(error)
            RecognitionError.NO_SPEECH ->
                if (mayRestartAfterSilence(config.choiceMinHealthySessionMs)) later(config.choiceRestartDelayMs, my) { startName(lang) }
                else {
                    logAttempt(emptyList(), partial, emptyList(), 0, "NONE", emptyList(), "silence_no_partial")
                    stopAll(); enterNoMatch(DialState.NoMatch("", emptyList(), 0, emptyList()))
                }
            else -> fail(error)
        }
    }

    private fun onNameResults(hypotheses: List<String>) {
        deadline?.cancel(); deadline = null
        nameCommit?.cancel(); nameCommit = null
        FlowTimeline.mark("accepted", scheduler.now())
        if (CancelDetector.isCancel(hypotheses)) { cancel(); return }
        val heard = hypotheses.first()
        val partialSnapshot = lastPartial
        val my = ++token
        set(DialState.Processing(heard))
        // Same path for phone and car: ContactsCache (Application-scoped singleton) — served
        // immediately when loaded; matching then runs on [worker] (v11: off the main thread).
        contacts.withContacts { list ->
            if (my != token || state !is DialState.Processing) return@withContacts
            FlowTimeline.mark("contacts", scheduler.now())
            if (list.isEmpty()) {
                logAttempt(hypotheses, partialSnapshot, hypotheses.flatMap { QueryExtractor.candidates(it) }.distinct(),
                    0, "NO_CONTACTS", emptyList(), "cache_empty source=$matchSource")
                dropDuck(); set(DialState.Failed(DialError.NO_CONTACTS)); return@withContacts
            }
            val timeline = FlowTimeline.current
            timeline.mark("matchStart", scheduler.now())
            worker.submit({
                matcher.analyze(hypotheses, list, config.listLimit, config.suggestionThreshold, 3)
            }) { a ->
                timeline.mark("matchEnd", scheduler.now())
                if (my != token || state !is DialState.Processing) return@submit
                onAnalysis(a, hypotheses, partialSnapshot, heard, list.size)
            }
        }
    }

    private fun onAnalysis(
        a: ContactMatcher.Analysis,
        hypotheses: List<String>,
        partialSnapshot: String?,
        heard: String,
        cacheCount: Int,
    ) {
        val top = a.top
        val extracted = a.extracted
        when (val r = a.result) {
            is ContactMatcher.Result.Single -> {
                logAttempt(hypotheses, partialSnapshot, extracted, cacheCount, "SINGLE", listOf(r.match),
                    "call ${r.match.contact.displayName}")
                val delay = countdownMs().coerceAtLeast(config.minSingleMatchCountdownMs)
                startCountdown(r.match.contact, heard, prompts?.singleMatch(r.match.contact.displayName), delay)
            }
            is ContactMatcher.Result.Multiple -> {
                logAttempt(hypotheses, partialSnapshot, extracted, cacheCount, "MULTIPLE", r.matches.take(3),
                    "choose ${r.matches.size}")
                enterChoosing(r.matches.map { it.contact })
            }
            ContactMatcher.Result.None -> {
                val suggestions = a.suggestions.take(config.maxSuggestions).map { it.contact }
                    .ifEmpty { top.filter { it.score > 0.0 }.map { it.contact } }
                logAttempt(hypotheses, partialSnapshot, extracted, cacheCount, "NONE", top,
                    "query=${extracted.take(3)} top=${top.joinToString { "${it.contact.displayName}:${"%.2f".format(it.score)}" }}")
                enterNoMatch(DialState.NoMatch(heard, suggestions, cacheCount, top, extracted.joinToString(" | ")))
            }
        }
    }

    /**
     * v12: no match (or nothing heard). Spoken "לא נמצא אף שם, נסה מחדש" and the screen offers ONE
     * primary action, "התחל האזנה שוב". The mic stays closed — no automatic restart after the
     * prompt. Suggestions/top candidates stay in the state for the debug log only.
     */
    private fun enterNoMatch(s: DialState.NoMatch) {
        dropDuck()
        set(s)
        prompts?.let { say(it.noMatch(), it.languageTag) }
    }

    /** Speaks [text] unless it is blank (the phone's prompts leave everything but no-match blank). */
    private fun say(text: String, lang: String, onDone: (() -> Unit)? = null) {
        if (text.isNotBlank()) FlowLog.d("tts", "[$matchSource] say \"$text\"")
        val sp = speaker
        if (sp == null || text.isBlank()) { onDone?.invoke(); return }
        sp.speak(text, lang, onDone)
    }

    // ------------------------------------------------------------------ list / choice

    /**
     * Mic opens with the list. When visible, stay silent so the driver can immediately read the
     * screen and say a number; when hidden (background continue), announce multiple matches aloud.
     *
     * Car: do **not** start SpeechRecognizer until [onCarResultScreenShown] (root Screen's first
     * ListTemplate for this Choosing, plus [DialConfig.carResultPaintMs]). Result UI is drawn on
     * VoiceDialScreen itself — AA driving ignores pushed Screens visually. First template is
     * already LISTENING so the header mic is red from frame 1.
     */
    private fun enterChoosing(items: List<ContactEntry>) {
        if (matchSource == "car" && visible) {
            stopAll()
            speech.pinDuck()
            carListAwaitingUi = true
            set(DialState.Choosing(items, MicState.LISTENING))
            return
        }
        listenChoice(items)
        announceMultipleIfHidden(items.size)
    }

    private fun announceMultipleIfHidden(n: Int) {
        if (visible) return
        val p = prompts ?: return
        val sp = speaker ?: return
        val text = p.multiple(n).takeIf { it.isNotBlank() } ?: return
        promptText = text
        promptSpeaking = true
        sp.speak(text, p.languageTag) { promptSpeaking = false }
    }

    /**
     * First Confirm/List template delivered on the root car Screen. Open list mic / play confirm
     * TTS after [DialConfig.carResultPaintMs] (posted off onGetTemplate).
     */
    fun onCarResultScreenShown() {
        FlowTimeline.mark("shown", scheduler.now())
        val paintMs = config.carResultPaintMs.coerceAtLeast(0)
        if (carListAwaitingUi) {
            carListAwaitingUi = false
            val s = state as? DialState.Choosing
            if (s != null) {
                FlowLog.d("car", "list template delivered -> mic in ${paintMs}ms (${s.items.size})")
                armCarPaint(paintMs) {
                    val cur = state as? DialState.Choosing ?: return@armCarPaint
                    FlowTimeline.mark("mic", scheduler.now())
                    FlowLog.d("car", "list mic open (${cur.items.size}) timing=${FlowTimeline.current.format()}")
                    listenChoice(cur.items)
                }
            }
        }
        if (carConfirmAwaitingUi) {
            carConfirmAwaitingUi = false
            val p = pendingConfirmPrompt
            pendingConfirmPrompt = null
            if (state is DialState.Confirm && p != null) {
                FlowLog.d("car", "confirm template delivered -> tts in ${paintMs}ms")
                armCarPaint(paintMs) {
                    if (state is DialState.Confirm) say(p.first, p.second)
                }
            }
        }
    }

    private fun armCarPaint(delayMs: Long, block: () -> Unit) {
        carPaintTask?.cancel(); carPaintTask = null
        if (delayMs <= 0) { block(); return }
        carPaintTask = scheduler.post(delayMs) {
            carPaintTask = null
            block()
        }
    }

    /** Text of the short list prompt currently/last spoken (echo filter). */
    private var promptText: String? = null
    private var promptSpeaking = false

    private fun bargeIn() {
        if (promptSpeaking) { FlowLog.d("tts", "barge-in: speech → stop prompt"); promptSpeaking = false; speaker?.stop() }
    }

    /** v15: echo handling lives in [EchoFilter] (strip the prompt, keep the driver's remainder). */
    private fun isPromptLike(text: String): Boolean =
        EchoFilter.isPromptPrefix(text, promptText) || EchoFilter.isPromptPrefix(text, hintText)

    /** v15: the "נא לומר מספר" hint is echo-filtered exactly like the main list prompt. */
    private var hintText: String? = null
    private fun stripEcho(h: List<String>) = EchoFilter.strip(EchoFilter.strip(h, promptText), hintText)
    private fun stripEcho(t: String): String {
        val a = EchoFilter.split(t, promptText)
        val rest = if (a.isEcho) a.remainder else t
        val b = EchoFilter.split(rest, hintText)
        return if (b.isEcho) b.remainder else rest
    }

    private fun speakHint() {
        val p = prompts ?: return
        val sp = speaker ?: return
        val text = p.sayNumber().takeIf { it.isNotBlank() } ?: return
        hintText = text
        promptSpeaking = true
        FlowLog.d("tts", "[$matchSource] name fits several rows → hint over open mic \"$text\"")
        sp.speak(text, p.languageTag) { promptSpeaking = false }
    }

    private fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{Nd} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Opens a fresh listening window (Settings, default 30 s) on the list. */
    private fun listenChoice(items: List<ContactEntry>) {
        carListAwaitingUi = false
        stopAll()
        speech.pinDuck()
        beginWindow(choiceWindowMs()) {
            val s = state as? DialState.Choosing
            if (s != null && s.mic == MicState.LISTENING) {
                stopAll()
                dropDuck()
                set(s.copy(mic = MicState.OFF, partial = null))
            }
        }
        startChoice(items, notice = null)
    }

    private fun startChoice(items: List<ContactEntry>, notice: Int?) {
        val my = ++token
        val cur = state as? DialState.Choosing
        if (cur == null || cur.mic != MicState.LISTENING || cur.outOfRange != notice || cur.items != items)
            set(DialState.Choosing(items, MicState.LISTENING, outOfRange = notice))
        markSessionStart()
        recognizerStarts++
        val opts = ListenOptions(continuous = true, maxSessionMs = (windowEnd - scheduler.now()).coerceAtLeast(0))
        speech.start(languages.primaryLanguage, sessionListener(my, object : SpeechListener {
            override fun onPartial(text: String) {
                // v15: a partial that is (a fuzzy prefix of) our prompt is the echo — no barge-in, not
                // "heard"; only the driver's remainder is shown.
                val mine = stripEcho(text)
                if (mine.any { it.isLetterOrDigit() }) { heardInSession = true; bargeIn() }
                val early = ChoiceResolver.resolveEarly(listOf(mine), items, matcher)
                if (early != null) {
                    startCountdown(early, mine, prompts?.picked(early.displayName), listCountdownMs())
                    return
                }
                val s = state as? DialState.Choosing ?: return
                val shown = mine.ifBlank { null }
                if (s.partial != shown) set(s.copy(partial = shown))
            }
            override fun onLevel(rmsDb: Float) { onLevel?.invoke(rmsDb) }
            override fun onResults(raw: List<String>) {
                // v15: strip our prompt, keep what the driver said after/inside it.
                val hypotheses = stripEcho(raw)
                if (hypotheses != raw.map { it.trim() }.filter { it.isNotBlank() }.distinct() && hypotheses.isNotEmpty())
                    FlowLog.d("flow", "echo stripped $raw → $hypotheses")
                if (hypotheses.isEmpty()) {
                    // Echo only: relisten IMMEDIATELY (mic stays LISTENING, no silence back-off).
                    FlowLog.d("flow", "echo only $raw → relisten now")
                    restartChoice(items, null, my, silence = false)
                    return
                }
                bargeIn()
                if (CancelDetector.isCancel(hypotheses)) { cancel(); return }
                val pick = ChoiceResolver.resolve(hypotheses, items, matcher)
                if (pick != null) {
                    startCountdown(pick, hypotheses.first(), prompts?.picked(pick.displayName), listCountdownMs())
                    return
                }
                if (ChoiceResolver.nameTie(hypotheses, items, matcher)) {
                    FlowLog.d("flow", "[$matchSource] name $hypotheses fits several rows equally → ask for the number")
                    speakHint()
                    restartChoice(items, null, my)
                    return
                }
                val n = hypotheses.firstNotNullOfOrNull { SpokenNumberParser.parse(it) }?.takeIf { it > items.size }
                restartChoice(items, n, my)
            }
            override fun onError(error: RecognitionError) {
                when (error) {
                    RecognitionError.BUSY, RecognitionError.CLIENT ->
                        if (transientRetries++ < config.transientRetries)
                            later(config.transientBackoffMs, my) { startChoice(items, notice) }
                        else set(DialState.Choosing(items, MicState.OFF, outOfRange = notice))
                    RecognitionError.NO_PERMISSION, RecognitionError.NOT_AVAILABLE ->
                        set(DialState.Choosing(items, MicState.OFF))
                    else -> restartChoice(items, null, my, silence = true)
                }
            }
        }), opts)
    }

    /**
     * Keeps listening on the list until the window expires. After real speech that didn't resolve
     * (e.g. "five" on a 3-row list) we always reopen while time is left; after silence/errors the
     * fast-failure rules apply.
     */
    private fun restartChoice(items: List<ContactEntry>, notice: Int?, my: Int, silence: Boolean = false) {
        val ok = if (silence) mayRestartAfterSilence(config.choiceMinHealthySessionMs)
        else windowEnd - scheduler.now() >= config.minRemainingForRestartMs
        if (ok) {
            // v10: the mic indicator never leaves LISTENING between back-to-back sessions, and the
            // next session starts right away (posted, so we're out of the recognizer callback).
            val cur = state as? DialState.Choosing
            if (cur == null || cur.mic != MicState.LISTENING || cur.outOfRange != notice || cur.items != items)
                set(DialState.Choosing(items, MicState.LISTENING, outOfRange = notice))
            later(config.choiceRestartDelayMs, my) { startChoice(items, notice) }
        } else {
            stopAll()
            dropDuck()
            set(DialState.Choosing(items, MicState.OFF, outOfRange = notice))
        }
    }

    // ------------------------------------------------------------------ countdown & call

    /**
     * v12: every call goes through here (single match, spoken number, tapped row). Our own timer
     * places the call when the countdown ends (the car UI doesn't rely on Action.FLAG_DEFAULT);
     * the actions are "חייג עכשיו" and "בטל התקשרות"; hiding the app cancels it (onHidden).
     */
    private fun startCountdown(
        entry: ContactEntry,
        heard: String?,
        prompt: String? = null,
        delayMs: Long = countdownMs(),
    ) {
        stopAll()
        speech.pinDuck()
        val total = delayMs
        if (total <= 0) { call(entry); return }
        val my = ++token
        val carUi = matchSource == "car" && visible
        if (carUi) {
            carConfirmAwaitingUi = true
            val text = prompt?.takeIf { it.isNotBlank() }
            pendingConfirmPrompt = if (text != null && prompts != null) text to prompts.languageTag else null
        }
        set(confirmState(entry, heard, total, total))
        if (!carUi) prompts?.let { p -> prompt?.let { say(it, p.languageTag) } }
        scheduleTick(my, entry, heard, total, total)
    }

    private fun confirmState(entry: ContactEntry, heard: String?, remaining: Long, total: Long) =
        DialState.Confirm(entry, ceil(remaining / 1000.0).toInt(), heard, remaining, total)

    /** Ticks on whole-second boundaries of the remaining time (3.5 s → 3 at 0.5 s, 2, 1, call). */
    private fun scheduleTick(my: Int, entry: ContactEntry, heard: String?, remaining: Long, total: Long) {
        val frac = remaining % 1000
        val step = if (frac == 0L) 1000L.coerceAtMost(remaining) else frac
        pending = scheduler.post(step) {
            if (my != token || state !is DialState.Confirm) return@post
            val left = remaining - step
            if (left <= 0) { pending = null; call(entry) }
            else { set(confirmState(entry, heard, left, total)); scheduleTick(my, entry, heard, left, total) }
        }
    }

    private fun call(entry: ContactEntry) {
        FlowLog.d("flow", "[$matchSource] placing call → ${entry.displayName} ${entry.number}")
        ++token
        lastCallAt = scheduler.now()
        lastCalledName = entry.displayName
        set(DialState.Calling(entry))
        if (!caller.call(entry)) {
            dropDuck()
            set(DialState.Failed(DialError.CALL_FAILED, entry.displayName))
            return
        }
        // Don't unduck here: ACTION_DIAL/CALL takes ~1s before telephony has audio, and media
        // would slam back to full volume. Release when the call interrupts us, or after a timeout.
        duckHold?.cancel()
        duckHold = scheduler.post(config.duckHoldAfterDialMs) {
            duckHold = null
            if (state is DialState.Calling) speech.unpinDuck()
        }
    }

    private fun dropDuck() {
        duckHold?.cancel(); duckHold = null
        speech.unpinDuck()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Wraps a listener so it only reacts while [my] is the current token AND only to the FIRST
     * terminal callback (onResults/onError) of that recognizer session.
     */
    private fun sessionListener(my: Int, inner: SpeechListener): SpeechListener = object : SpeechListener {
        private var terminated = false
        private fun live() = my == token && !terminated
        override fun onListening() { if (live()) inner.onListening() }
        override fun onPartial(text: String) { if (live()) inner.onPartial(text) }
        override fun onLevel(rmsDb: Float) { if (live()) inner.onLevel(rmsDb) }
        override fun onResults(hypotheses: List<String>) { if (live()) { terminated = true; inner.onResults(hypotheses) } }
        override fun onFinalResults(hypotheses: List<String>, byApp: Boolean) {
            if (live()) { terminated = true; inner.onFinalResults(hypotheses, byApp) }
        }
        override fun onError(error: RecognitionError) { if (live()) { terminated = true; inner.onError(error) } }
    }

    /** Runs [block] after [delayMs] unless the session [my] was superseded meanwhile. */
    private fun later(delayMs: Long, my: Int, block: () -> Unit) {
        pending?.cancel()
        pending = scheduler.post(delayMs) { pending = null; if (my == token) block() }
    }

    private fun fail(error: RecognitionError) {
        dropDuck()
        when (error) {
            RecognitionError.NO_PERMISSION -> {
                val missing = permissions()
                set(if (missing.isNotEmpty()) DialState.NeedsPermissions(missing) else DialState.Failed(DialError.OTHER))
            }
            RecognitionError.NOT_AVAILABLE -> set(DialState.Failed(DialError.NOT_AVAILABLE))
            RecognitionError.NETWORK -> set(DialState.Failed(DialError.NETWORK))
            RecognitionError.LANGUAGE -> set(DialState.Failed(DialError.LANGUAGE))
            else -> set(DialState.Failed(DialError.OTHER))
        }
    }

    private fun recentlyCalled(): Boolean {
        val t = lastCallAt ?: return false
        return scheduler.now() - t < config.afterCallQuietMs
    }

    /** Mic/TTS only — leave the listen-window timer so a background session can still expire. */
    private fun stopSpeech() {
        token++
        pending?.cancel(); pending = null
        nameCommit?.cancel(); nameCommit = null
        carPaintTask?.cancel(); carPaintTask = null
        heldWords = emptyList()
        heardInSession = false
        speaker?.stop()
        speech.cancel()
    }

    private fun stopAll() {
        stopSpeech()
        deadline?.cancel(); deadline = null
    }

    private var lastLogged: String? = null
    private fun set(s: DialState) {
        state = s
        val d = describe(s)
        if (d != lastLogged) { lastLogged = d; FlowLog.d("state", "[$matchSource] $d") }
        emit()
    }

    private fun describe(s: DialState): String = when (s) {
        is DialState.Listening -> "Listening ${s.lang} \"${s.partial.orEmpty()}\""
        is DialState.Processing -> "Processing \"${s.heard}\""
        is DialState.Confirm -> "Confirm ${s.entry.displayName} ${s.secondsLeft}s/${s.totalMs}ms"
        is DialState.Calling -> "Calling ${s.entry.displayName} ${s.entry.number}"
        is DialState.Choosing -> "Choosing ${s.items.size} [${s.items.joinToString { it.displayName }}] mic=${s.mic}"
        is DialState.NoMatch -> "NoMatch \"${s.heard}\" contacts=${s.contactsCount}"
        else -> s.toString()
    }
    private fun emit() { onStateChanged?.invoke(state) }
}
