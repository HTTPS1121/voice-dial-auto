package com.example.voicedialauto.flow

import com.example.voicedialauto.contacts.ContactEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DialControllerTest {

    // ---------------------------------------------------------------- fakes
    class FakeSpeech : SpeechInput {
        var listener: SpeechListener? = null
        val all = mutableListOf<SpeechListener>()
        var starts = 0
        var active = 0
        var maxConcurrent = 0
        var lastLang: String? = null
        /** If set, every start immediately fails with this error (error storm). */
        var stormError: RecognitionError? = null
        val options = mutableListOf<ListenOptions>()
        override fun start(languageTag: String, listener: SpeechListener, options: ListenOptions) {
            this.options += options
            starts++; lastLang = languageTag; this.listener = listener; all += listener
            active++; maxConcurrent = maxOf(maxConcurrent, active)
            stormError?.let { e -> active--; this.listener = null; listener.onError(e) }
        }
        override fun cancel() { if (listener != null) active--; listener = null }
        fun say(vararg h: String) { val l = listener!!; listener = null; active--; l.onResults(h.toList()) }
        fun silence() = error(RecognitionError.NO_SPEECH)
        fun error(e: RecognitionError) { val l = listener!!; listener = null; active--; l.onError(e) }
        val listening get() = listener != null
        fun partial(t: String) { listener!!.onPartial(t) }
        /** The recognition ENGINE ended the session on its own (e.g. at the pause after "חני"). */
        fun engineSay(vararg h: String) { val l = listener!!; listener = null; active--; l.onFinalResults(h.toList(), byApp = false) }
    }

    class FakeScheduler : Scheduler {
        var t = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun now() = t
        override fun post(delayMs: Long, block: () -> Unit): Cancellable {
            val e = (t + delayMs) to block
            tasks += e
            return object : Cancellable { override fun cancel() { tasks.remove(e) } }
        }
        fun advance(ms: Long) {
            val end = t + ms
            while (true) {
                val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
                tasks.remove(next); t = next.first; next.second()
            }
            t = end
        }
    }

    class FakeLang : LanguageSource {
        override var primaryLanguage = "he-IL"
        override val secondaryLanguage get() = if (primaryLanguage == "he-IL") "en-US" else "he-IL"
        override fun togglePrimaryLanguage() { primaryLanguage = secondaryLanguage }
    }

    class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        private var pending: (() -> Unit)? = null
        var speaking = false
        override fun speak(text: String, languageTag: String, onDone: (() -> Unit)?) { stop(); spoken += text; pending = onDone; speaking = true }
        override fun stop() { speaking = false; val p = pending; pending = null; p?.invoke() }
        fun finish() { val p = pending; pending = null; p?.invoke() }
    }

    private val contacts = listOf(
        ContactEntry(1, "דנה כהן", "0501"), ContactEntry(2, "Dana Levi", "0502"),
        ContactEntry(3, "יוסי מזרחי", "0503"), ContactEntry(4, "Moshe Peretz", "0504"),
    )

    private lateinit var speech: FakeSpeech
    private lateinit var clock: FakeScheduler
    private lateinit var called: MutableList<ContactEntry>
    private var missing = listOf<String>()
    private var callResult = true

    class FakeTimings(
        override var nameWindowMs: Long = DEFAULT_NAME_WINDOW_MS,
        override var choiceWindowMs: Long = DEFAULT_CHOICE_WINDOW_MS,
        override var countdownMs: Long = DEFAULT_COUNTDOWN_MS,
    ) : TimingSource

    private var timings = FakeTimings()

    var refreshes = 0
    var freshChecks = 0

    private fun controller(
        speaker: Speaker? = null,
        list: List<ContactEntry> = contacts,
        worker: Worker = Worker.Inline,
    ) = DialController(
        timings = timings,
        speech = speech,
        worker = worker,
        contacts = object : ContactsSource {
            override fun refresh(onLoaded: ((List<ContactEntry>) -> Unit)?) { refreshes++; onLoaded?.invoke(list) }
            override fun ensureFresh() { freshChecks++ }
            override fun withContacts(block: (List<ContactEntry>) -> Unit) = block(list)
        },
        caller = Caller { called += it; callResult },
        scheduler = clock,
        permissions = { missing },
        languages = FakeLang(),
        config = DialConfig(afterCallQuietMs = 60_000, listLimit = 6),
        speaker = speaker,
        prompts = speaker?.let {
            object : Prompts {
                override val languageTag = "he-IL"
                override fun singleMatch(name: String) = "single $name"
                override fun picked(name: String) = "picked $name"
                override fun multiple(count: Int) = "נמצאו כמה שמות נא להגיד את מספר התוצאה הרצוי"
                override fun sayNumber() = "נא לומר מספר"
                override fun noMatch() = "nomatch"
            }
        },
    )

    @Before fun setUp() { refreshes = 0; freshChecks = 0; timings = FakeTimings(); speech = FakeSpeech(); clock = FakeScheduler(); called = mutableListOf(); missing = emptyList(); callResult = true }

    @Test fun openStartsListeningImmediately() {
        val c = controller(); c.onVisible()
        assertTrue(c.state is DialState.Listening); assertTrue(speech.listening)
    }

    @Test fun missingPermissionsShowsPermissionState() {
        missing = listOf("android.permission.RECORD_AUDIO")
        val c = controller(); c.onVisible()
        assertEquals(DialState.NeedsPermissions(missing), c.state)
        missing = emptyList(); c.onPermissionsResult()
        assertTrue(c.state is DialState.Listening)
    }

    @Test fun singleMatchCountsDownThenCalls() {
        val c = controller(); c.onVisible()
        speech.say("תתקשר ליוסי")
        val s = c.state as DialState.Confirm
        // v12: default countdown 5 s (was 3.5 s).
        assertEquals(5, s.secondsLeft); assertEquals(5000, s.totalMs); assertTrue(called.isEmpty())
        clock.advance(1000); assertEquals(4, (c.state as DialState.Confirm).secondsLeft)
        clock.advance(1000); assertEquals(3, (c.state as DialState.Confirm).secondsLeft)
        clock.advance(2000); assertEquals(1, (c.state as DialState.Confirm).secondsLeft)
        assertTrue(called.isEmpty())
        clock.advance(1000)
        assertEquals("יוסי מזרחי", called.single().displayName)
        assertTrue(c.state is DialState.Calling)
    }

    @Test fun cancelDuringCountdownDoesNotCall() {
        val c = controller(); c.onVisible(); speech.say("call Moshe")
        c.cancel(); clock.advance(5000)
        assertTrue(called.isEmpty()); assertTrue(c.state is DialState.Idle)
    }

    @Test fun recognizeAgainDuringCountdownListens() {
        val c = controller(); c.onVisible(); speech.say("call Moshe")
        c.recognizeAgain(); clock.advance(5000)
        assertTrue(called.isEmpty()); assertTrue(c.state is DialState.Listening)
    }

    @Test fun callNowSkipsCountdown() {
        val c = controller(); c.onVisible(); speech.say("call Moshe"); c.callNow()
        assertEquals(1, called.size)
    }

    @Test fun mainScreenRestartsOnceOnSilence() {
        val c = controller(); c.onVisible()
        speech.silence(); assertTrue(c.state is DialState.Listening); clock.advance(300); assertEquals(2, speech.starts)
        speech.silence(); assertEquals(DialState.NoMatch("", emptyList()), c.state)
        clock.advance(60_000); assertEquals(2, speech.starts)
    }

    @Test fun spokenCancelGoesIdle() {
        val c = controller(); c.onVisible(); speech.say("ביטול")
        assertTrue(c.state is DialState.Idle); assertTrue(called.isEmpty())
    }

    @Test fun multipleMatchesShowListAndListenWithRestartsFor30s() {
        val c = controller(); c.onVisible(); speech.say("תתקשר לדנה")
        val s = c.state as DialState.Choosing
        assertEquals(2, s.items.size); assertEquals(MicState.LISTENING, s.mic)
        // Healthy (5 s) silent sessions keep restarting until the 30 s window ends, then mic off.
        var t = 0L
        while (t + 5300 <= 28_000) { clock.advance(5000); speech.silence(); assertEquals(MicState.LISTENING, (c.state as DialState.Choosing).mic); clock.advance(300); t += 5300 }
        clock.advance(30_000 - t)
        assertEquals(MicState.OFF, (c.state as DialState.Choosing).mic)
        // Tap → fresh window.
        c.micPressed(); assertEquals(MicState.LISTENING, (c.state as DialState.Choosing).mic)
    }

    @Test fun listWindowEndsAfter30sEvenWithRestartsLeft() {
        val c = controller(); c.onVisible(); speech.say("תתקשר לדנה")
        clock.advance(30_000)                    // window deadline closes the mic by itself
        assertEquals(MicState.OFF, (c.state as DialState.Choosing).mic)
        assertTrue(!speech.listening)
    }

    @Test fun spokenNumberPicksWithCountdown() {
        val c = controller(); c.onVisible(); speech.say("תתקשר לדנה")
        val items = (c.state as DialState.Choosing).items
        speech.say("מספר שתיים")
        assertEquals(items[1], (c.state as DialState.Confirm).entry)
        clock.advance(5000); assertEquals(items[1], called.single())
    }

    @Test fun outOfRangeNumberKeepsListening() {
        val c = controller(); c.onVisible(); speech.say("תתקשר לדנה")
        speech.say("חמש")
        val s = c.state as DialState.Choosing
        assertEquals(5, s.outOfRange); assertEquals(MicState.LISTENING, s.mic)
        clock.advance(300); assertTrue(speech.listening)
    }

    /** v12 (replaces tapOnRowCallsImmediately): a tapped row gets the same countdown as a single match. */
    @Test fun tapOnRowStartsCountdownLikeSingleMatch() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        val items = (c.state as DialState.Choosing).items
        c.pick(items[0])
        assertEquals(items[0], (c.state as DialState.Confirm).entry); assertTrue(called.isEmpty())
        assertEquals("picked ${items[0].displayName}", sp.spoken.last())
        assertTrue(!speech.listening)
        clock.advance(5_000); assertEquals(items[0], called.single())
    }

    @Test fun noMatchOffersSuggestions() {
        val c = controller(); c.onVisible(); speech.say("call Mosko")
        val s = c.state as DialState.NoMatch
        assertTrue(s.suggestions.size in 1..3)
        assertEquals("Moshe Peretz", s.suggestions.first().displayName)
    }

    @Test fun tryOtherLanguageUsesSecondary() {
        val c = controller(); c.onVisible(); speech.silence(); clock.advance(300); speech.silence()
        c.tryOtherLanguage(); assertEquals("en-US", speech.lastLang)
    }

    @Test fun busyIsRetriedOnceWithBackoff() {
        val c = controller(); c.onVisible()
        speech.error(RecognitionError.BUSY)
        assertEquals(1, speech.starts)          // not immediately
        clock.advance(749); assertEquals(1, speech.starts)
        clock.advance(1); assertEquals(2, speech.starts)
        speech.error(RecognitionError.BUSY)
        assertTrue(c.state is DialState.Failed)
        clock.advance(60_000); assertEquals(2, speech.starts)
    }

    @Test fun resumeAfterCallStaysIdleThenListensLater() {
        val c = controller(); c.onVisible(); speech.say("call Moshe"); c.callNow()
        c.onHidden(); clock.advance(10_000); c.onVisible()
        assertEquals(DialState.Idle("Moshe Peretz"), c.state); assertTrue(!speech.listening)
        c.onHidden(); clock.advance(60_000); c.onVisible()
        assertTrue(c.state is DialState.Listening)
        assertEquals(1, speech.maxConcurrent)
    }

    @Test fun resumeWhileListeningListensAgain() {
        val c = controller(); c.onVisible(); c.onHidden(); assertTrue(!speech.listening)
        clock.advance(3_000); c.onVisible(); assertTrue(speech.listening)
    }

    @Test fun hidingDuringCountdownCancelsCall() {
        val c = controller(); c.onVisible(); speech.say("call Moshe")
        c.onHidden(); clock.advance(5000)
        assertTrue(called.isEmpty()); assertTrue(c.state is DialState.Idle)
    }

    @Test fun returnFromSettingsDoesNotAutoListen() {
        val c = controller(); c.onVisible(); c.suppressNextResume(); c.onHidden(); c.onVisible()
        assertTrue(c.state is DialState.Idle); assertTrue(!speech.listening)
    }

    // ---------------------------------------------------------------- v9: list without TTS gap

    /** v12 (replaces listListensImmediatelyAndSilentlyByDefault + readAloudOnSpeaksShortPromptOverOpenMic). */
    @Test fun listAlwaysSpeaksPromptWithMicAlreadyOpen() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        assertEquals(MicState.LISTENING, (c.state as DialState.Choosing).mic)
        assertTrue(speech.listening); assertTrue(sp.speaking)
        assertEquals(listOf("נמצאו כמה שמות נא להגיד את מספר התוצאה הרצוי"), sp.spoken)
    }

    @Test fun bargeInSpeechStopsTts() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        speech.partial("שתיים")
        assertTrue(!sp.speaking); assertTrue(speech.listening)
        speech.say("שתיים")
        assertTrue(c.state is DialState.Confirm)
    }

    /** Our own prompt heard by the mic must neither stop the prompt nor pick a row. */
    @Test fun ttsEchoIsIgnored() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        speech.partial("נמצאו כמה שמות")
        assertTrue("echo partial must not barge in", sp.speaking)
        speech.say("נמצאו כמה שמות נא להגיד את מספר")
        val s = c.state as DialState.Choosing
        assertEquals(MicState.LISTENING, s.mic); assertTrue(called.isEmpty())
        clock.advance(1_000); assertTrue(speech.listening)
    }

    @Test fun micStaysOnAcrossListSilenceRestarts() {
        val seen = mutableListOf<MicState>()
        val c = controller(FakeSpeaker()); c.onVisible(); speech.say("תתקשר לדנה")
        c.onStateChanged = { (it as? DialState.Choosing)?.let { ch -> seen += ch.mic } }
        repeat(3) { clock.advance(2_000); speech.silence(); clock.advance(1_000) }
        // v10: back-to-back sessions emit nothing at all; anything emitted must still be LISTENING.
        assertTrue(seen.all { it == MicState.LISTENING })
    }

    // ---------------------------------------------------------------- v9: phone-call interruption

    @Test fun incomingCallOnListStopsTtsAndMicAndGoesIdle() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        val starts = speech.starts
        c.onInterrupted()
        assertTrue(c.state is DialState.Idle); assertTrue(!speech.listening); assertTrue(!sp.speaking)
        clock.advance(60_000); assertEquals(starts, speech.starts)
    }

    @Test fun noAutoResumeAfterCallEnds() {
        val c = controller(); c.onVisible(); c.onInterrupted()
        c.onHidden(); clock.advance(120_000); c.onVisible()
        assertTrue(c.state is DialState.Idle); assertTrue(!speech.listening)
        c.recognizeAgain(); assertTrue(speech.listening)
    }

    @Test fun interruptDuringCountdownDoesNotCall() {
        val c = controller(); c.onVisible(); speech.say("call Moshe")
        c.onInterrupted(); clock.advance(10_000)
        assertTrue(called.isEmpty()); assertTrue(c.state is DialState.Idle)
    }

    @Test fun ownOutgoingCallKeepsCallingState() {
        timings.countdownMs = 0
        val c = controller(); c.onVisible(); speech.say("call Moshe")
        assertTrue(c.state is DialState.Calling)
        c.onInterrupted()
        assertTrue(c.state is DialState.Calling); assertEquals(1, called.size)
    }

    @Test fun failedCallShowsError() {
        callResult = false
        val c = controller(); c.onVisible(); speech.say("call Moshe"); c.callNow()
        assertEquals(DialState.Failed(DialError.CALL_FAILED, "Moshe Peretz"), c.state)
    }

    // ---------------------------------------------------------------- v4: loop regression tests

    /** v3 bug: something pauses/resumes the activity right after listening starts → endless restart. */
    @Test fun resumeFlappingDuringListeningDoesNotLoop() {
        val c = controller(); c.onVisible()
        repeat(20) { clock.advance(100); c.onHidden(); clock.advance(100); c.onVisible() }
        assertEquals(1, speech.starts)
        assertTrue(c.state is DialState.Idle)
        assertEquals(1, speech.maxConcurrent)
    }

    @Test fun longAwayFlappingIsCircuitBroken() {
        val c = controller(); c.onVisible()
        repeat(7) { c.onHidden(); clock.advance(2_500); c.onVisible() }   // 17.5 s < 20 s window
        assertEquals(3, speech.starts)                                     // max 3 automatic starts / 20 s
        assertTrue(c.state is DialState.Idle)
        // A user tap always works again.
        c.micPressed(); assertTrue(c.state is DialState.Listening)
    }

    @Test fun duplicateOnVisibleStartsOnce() {
        val c = controller(); c.onVisible(); c.onVisible(); c.onVisible()
        assertEquals(1, speech.starts)
    }

    @Test fun silenceStormStopsAfterOneRestart() {
        speech.stormError = RecognitionError.NO_SPEECH
        val c = controller(); c.onVisible(); clock.advance(10_000)
        assertEquals(2, speech.starts)
        assertEquals(DialState.NoMatch("", emptyList()), c.state)
    }

    @Test fun clientErrorStormRetriesOnceThenFails() {
        speech.stormError = RecognitionError.CLIENT
        val c = controller(); c.onVisible(); clock.advance(10_000)
        assertEquals(2, speech.starts)
        assertEquals(DialState.Failed(DialError.OTHER), c.state)
    }

    @Test fun busyStormRetriesOnceThenFails() {
        speech.stormError = RecognitionError.BUSY
        val c = controller(); c.onVisible(); clock.advance(10_000)
        assertEquals(2, speech.starts)
        assertTrue(c.state is DialState.Failed)
    }

    @Test fun duplicateTerminalCallbacksAreIgnored() {
        val c = controller(); c.onVisible()
        val l = speech.listener!!
        l.onError(RecognitionError.NO_SPEECH)   // → one delayed restart
        l.onError(RecognitionError.NO_SPEECH)   // duplicate: ignored
        l.onError(RecognitionError.CLIENT)      // late error after terminal: ignored
        clock.advance(1_000)
        assertEquals(2, speech.starts)
        assertTrue(c.state is DialState.Listening)
    }

    @Test fun errorAfterResultsIsIgnored() {
        val c = controller(); c.onVisible()
        val l = speech.listener!!
        l.onResults(listOf("call Moshe"))
        l.onError(RecognitionError.CLIENT)       // Google often sends this after results/destroy
        assertTrue(c.state is DialState.Confirm)
        clock.advance(5_000); assertEquals(1, called.size)  // v12: default countdown 5 s
    }

    @Test fun staleSessionCallbacksAreIgnored() {
        val c = controller(); c.onVisible()
        val old = speech.listener!!
        c.recognizeAgain()                       // new session
        old.onResults(listOf("call Moshe"))      // stale
        old.onError(RecognitionError.NO_SPEECH)  // stale
        assertTrue(c.state is DialState.Listening); assertEquals(2, speech.starts)
    }

    @Test fun silenceRestartIsCancelledByHide() {
        val c = controller(); c.onVisible(); speech.silence()  // restart pending (300 ms)
        c.onHidden(); clock.advance(1_000)
        assertEquals(1, speech.starts)
    }

    @Test fun listRestartsNeverOverlap() {
        val c = controller(); c.onVisible(); speech.say("תתקשר לדנה")
        repeat(10) { if (speech.listening) speech.silence(); clock.advance(400) }
        assertEquals(1, speech.maxConcurrent)
        assertTrue(speech.starts <= 2 + 4)     // initial name + list + 4 restarts
        assertEquals(MicState.OFF, (c.state as DialState.Choosing).mic)
    }

    // ---------------------------------------------------------------- v5: configurable timings

    @Test fun nameWindowKeepsRestartingHealthySilenceUntilExpiry() {
        timings.nameWindowMs = 40_000
        val c = controller(); c.onVisible()
        // Google-like: each silent session lasts ~5 s. More restarts than the 3-per-20 s breaker.
        var n = 0
        while (c.state is DialState.Listening && n < 50) {
            clock.advance(5_000); if (speech.listening) speech.silence(); clock.advance(300); n++
        }
        assertTrue("starts=${speech.starts}", speech.starts >= 7)   // ~40 s / 5.3 s
        assertEquals(DialState.NoMatch("", emptyList()), c.state)
        assertTrue(clock.now() <= 40_000 + 5_300)
        assertEquals(1, speech.maxConcurrent)
    }

    @Test fun shortNameWindowStopsListeningAtDeadline() {
        timings.nameWindowMs = 5_000
        val c = controller(); c.onVisible()
        clock.advance(5_000)
        assertEquals(DialState.NoMatch("", emptyList()), c.state)
        assertTrue(!speech.listening); assertEquals(1, speech.starts)
    }

    @Test fun deadlineDoesNotCutOffSpeechInProgress() {
        timings.nameWindowMs = 5_000
        val c = controller(); c.onVisible()
        clock.advance(4_000); speech.listener!!.onPartial("תתקשר ל")
        clock.advance(2_000)                                   // past the deadline
        assertTrue(c.state is DialState.Listening); assertTrue(speech.listening)
        speech.say("תתקשר ליוסי")
        assertTrue(c.state is DialState.Confirm)
    }

    @Test fun longWindowStillStopsErrorStorm() {
        timings.nameWindowMs = 60_000
        speech.stormError = RecognitionError.NO_SPEECH
        val c = controller(); c.onVisible(); clock.advance(70_000)
        assertEquals(2, speech.starts)                       // one fast restart only
        assertEquals(DialState.NoMatch("", emptyList()), c.state)
    }

    @Test fun longListWindowIsHonoured() {
        timings.choiceWindowMs = 120_000
        val c = controller(); c.onVisible(); speech.say("תתקשר לדנה")
        clock.advance(100_000)
        // still listening (each session silent for 5 s would restart; here one long session)
        assertEquals(MicState.LISTENING, (c.state as DialState.Choosing).mic)
        clock.advance(20_000)
        assertEquals(MicState.OFF, (c.state as DialState.Choosing).mic)
        assertTrue(!speech.listening)
    }

    @Test fun shortListWindowClosesMic() {
        timings.choiceWindowMs = 10_000
        val c = controller(); c.onVisible(); speech.say("תתקשר לדנה")
        clock.advance(9_999); assertEquals(MicState.LISTENING, (c.state as DialState.Choosing).mic)
        clock.advance(1); assertEquals(MicState.OFF, (c.state as DialState.Choosing).mic)
    }

    @Test fun listOutOfRangeKeepsListeningWithinWindow() {
        timings.choiceWindowMs = 10_000
        val c = controller(); c.onVisible(); speech.say("תתקשר לדנה")
        repeat(3) { clock.advance(1_000); speech.say("חמש"); clock.advance(300) }
        assertEquals(MicState.LISTENING, (c.state as DialState.Choosing).mic)
        assertEquals(5, (c.state as DialState.Choosing).outOfRange)
    }

    @Test fun zeroCountdownCallsImmediately() {
        timings.countdownMs = 0
        val c = controller(); c.onVisible(); speech.say("תתקשר ליוסי")
        assertTrue(c.state is DialState.Calling)
        assertEquals("יוסי מזרחי", called.single().displayName)
    }

    @Test fun halfSecondCountdown() {
        timings.countdownMs = 1_500
        val c = controller(); c.onVisible(); speech.say("תתקשר ליוסי")
        assertEquals(2, (c.state as DialState.Confirm).secondsLeft)
        clock.advance(499); assertTrue(called.isEmpty())
        clock.advance(1); assertEquals(1, (c.state as DialState.Confirm).secondsLeft)
        clock.advance(999); assertTrue(called.isEmpty())
        clock.advance(1); assertEquals(1, called.size)
    }

    @Test fun tenSecondCountdownCanBeCancelled() {
        timings.countdownMs = 10_000
        val c = controller(); c.onVisible(); speech.say("תתקשר ליוסי")
        assertEquals(10, (c.state as DialState.Confirm).secondsLeft)
        clock.advance(9_000); c.cancel(); clock.advance(5_000)
        assertTrue(called.isEmpty()); assertTrue(c.state is DialState.Idle)
    }

    @Test fun settingsChangeAppliesToNextRequest() {
        val c = controller(); c.onVisible(); speech.say("תתקשר ליוסי")
        assertEquals(5_000, (c.state as DialState.Confirm).totalMs)  // v12 default
        c.cancel(); timings.countdownMs = 6_000
        c.recognizeAgain(); speech.say("תתקשר ליוסי")
        assertEquals(6_000, (c.state as DialState.Confirm).totalMs)
    }

// ---------------------------------------------------------------- v8: partial fallback / endpointing

    @Test fun noSpeechWithPartialMatchesInsteadOfNoMatch() {
        val c = controller(); c.onVisible()
        speech.listener!!.onPartial("תתקשר ליוסי")
        speech.error(RecognitionError.NO_SPEECH)
        // Controller should fall back to partial and match יוסי
        assertTrue(
            "state=${c.state}",
            c.state is DialState.Confirm || c.state is DialState.Calling || c.state is DialState.Processing,
        )
        if (c.state is DialState.Confirm) {
            assertEquals("יוסי מזרחי", (c.state as DialState.Confirm).entry.displayName)
        }
    }

    @Test fun noSpeechWithoutPartialStillNoMatchAfterRestarts() {
        timings.nameWindowMs = 5_000
        val c = controller(); c.onVisible()
        speech.silence()
        clock.advance(300)
        if (speech.listening) speech.silence()
        clock.advance(10_000)
        assertTrue(
            "state=${c.state}",
            c.state is DialState.NoMatch || c.state is DialState.Idle || c.state is DialState.Listening,
        )
    }

    // ---------------------------------------------------------------- v10: continuous list listening

    private fun toList(c: DialController) { c.onVisible(); speech.say("תתקשר לדנה"); assertTrue(c.state is DialState.Choosing) }

    @Test fun listSessionsAreContinuousMode() {
        val c = controller(); toList(c)
        assertTrue(speech.options.last().continuous)
        assertTrue(speech.options.last().maxSessionMs in 1..DEFAULT_CHOICE_WINDOW_MS)
        // v12: the name session is continuous + segmented too (was: classic).
        assertTrue(speech.options.first().continuous); assertTrue(speech.options.first().segmented)
    }

    /** Road noise only: every session ends after ~2 s with NO_SPEECH. The mic must stay on, with no flicker. */
    @Test fun noiseOnlySessionsContinueWithoutFlickerUntilWindowEnds() {
        val c = controller(); toList(c)
        val emitted = mutableListOf<DialState>()
        c.onStateChanged = { emitted += it }
        val startsBefore = speech.starts
        var t = 0L
        while (t < 27_000) {
            clock.advance(2_000); t += 2_000
            if (speech.listening) speech.silence()
            clock.advance(0)
            assertTrue("mic dropped at $t", speech.listening || (c.state as DialState.Choosing).mic == MicState.LISTENING)
        }
        assertTrue(speech.starts - startsBefore >= 12)
        // No state emission at all for back-to-back noise sessions (car: no template refresh).
        assertTrue("emitted ${emitted.size}", emitted.isEmpty())
        assertEquals(1, speech.maxConcurrent)
        clock.advance(5_000); if (speech.listening) speech.silence()
        clock.advance(1_000)
        val s = c.state as DialState.Choosing
        assertEquals(MicState.OFF, s.mic); assertTrue(!speech.listening)
    }

    @Test fun listRestartIsImmediate() {
        val c = controller(); toList(c)
        clock.advance(3_000); speech.silence()
        assertTrue(!speech.listening)
        clock.advance(0)
        assertTrue(speech.listening)
    }

    /** Real error storm (recognizer fails instantly) still closes the mic. */
    @Test fun listErrorStormStillStops() {
        val c = controller(); toList(c)
        repeat(10) { clock.advance(50); if (speech.listening) speech.silence(); clock.advance(0) }
        assertEquals(MicState.OFF, (c.state as DialState.Choosing).mic)
        assertTrue(!speech.listening)
        assertTrue(speech.starts <= 4)
    }

    @Test fun noisePartialWithoutLettersDoesNotCountAsSpeech() {
        val sp = FakeSpeaker()
        val c = controller(sp); toList(c)
        speech.partial("…")
        assertTrue(sp.speaking) // no barge-in on punctuation noise
        speech.partial("שתיים"); assertTrue(!sp.speaking)
    }

    // ---------------------------------------------------------------- v11: first name only, async match, no reload

    @Test fun firstNameOnlyShowsListOfThatFirstName() {
        val list = listOf(
            ContactEntry(1, "חני כהן", "0501"), ContactEntry(2, "חני לוי", "0502"),
            ContactEntry(3, "חנה ביטון", "0503"), ContactEntry(4, "משה פרץ", "0504"),
        )
        val c = controller(list = list); c.onVisible()
        speech.say("תתקשר לחני")
        val s = c.state as DialState.Choosing
        assertEquals(setOf("חני כהן", "חני לוי"), s.items.map { it.displayName }.take(2).toSet())
        assertTrue(s.items.none { it.displayName == "משה פרץ" })
    }

    /** With both words captured (final or merged partial) the full name is called, not listed. */
    @Test fun twoWordUtteranceCallsTheFullName() {
        val list = listOf(ContactEntry(1, "חני כהן", "0501"), ContactEntry(2, "חני לוי", "0502"))
        timings.countdownMs = 0
        val c = controller(list = list); c.onVisible()
        speech.say("חני כהן")
        assertEquals("חני כהן", called.single().displayName)
    }

    @Test fun listeningDoesNotReloadContactsEveryTime() {
        val c = controller(); c.onVisible(); speech.say("תתקשר לדנה")
        c.recognizeAgain(); c.recognizeAgain()
        assertEquals(0, refreshes)          // v10 did a full reload on every listening start
        assertEquals(3, freshChecks)        // cheap staleness check instead
    }

    class DeferredWorker : Worker {
        val queue = mutableListOf<() -> Unit>()
        override fun <T> submit(work: () -> T, onDone: (T) -> Unit) { queue += { onDone(work()) } }
        fun runAll() { val q = queue.toList(); queue.clear(); q.forEach { it() } }
    }

    @Test fun matchingRunsOnWorkerAndStaleResultsAreDropped() {
        val w = DeferredWorker()
        val c = controller(worker = w); c.onVisible()
        speech.say("תתקשר לדנה")
        assertTrue(c.state is DialState.Processing); assertEquals(1, w.queue.size)
        w.runAll()
        assertTrue(c.state is DialState.Choosing)
        // A result arriving after the user cancelled is ignored.
        c.recognizeAgain(); speech.say("call Moshe"); c.cancel()
        w.runAll()
        assertTrue(c.state is DialState.Idle); assertTrue(called.isEmpty())
    }

    // ---------------------------------------------------------------- v12: simple no-match

    @Test fun noMatchSpeaksPromptAndDoesNotRestart() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible()
        speech.say("תתקשר לזבולון")
        assertTrue(c.state is DialState.NoMatch)
        assertEquals(listOf("nomatch"), sp.spoken)
        val starts = speech.starts
        sp.finish(); clock.advance(60_000)
        assertEquals(starts, speech.starts); assertTrue(!speech.listening)
        assertTrue(c.state is DialState.NoMatch)
    }

    @Test fun nothingHeardAlsoSpeaksNoMatchPrompt() {
        timings.nameWindowMs = 5_000
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible()
        repeat(4) { clock.advance(2_000); if (speech.listening) speech.silence() }
        clock.advance(5_000)
        assertTrue(c.state is DialState.NoMatch)
        assertEquals(listOf("nomatch"), sp.spoken)
        assertTrue(!speech.listening)
    }

    @Test fun listenAgainFromNoMatchStopsTtsAndListens() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לזבולון")
        assertTrue(sp.speaking)
        c.recognizeAgain()
        assertTrue(!sp.speaking); assertTrue(c.state is DialState.Listening); assertTrue(speech.listening)
    }

    @Test fun blankPromptsAreNotSpoken() {
        val sp = FakeSpeaker()
        val c = DialController(
            speech = speech,
            contacts = object : ContactsSource {
                override fun refresh(onLoaded: ((List<ContactEntry>) -> Unit)?) { onLoaded?.invoke(contacts) }
                override fun withContacts(block: (List<ContactEntry>) -> Unit) = block(contacts)
            },
            caller = Caller { called += it; true },
            scheduler = clock, permissions = { emptyList() }, languages = FakeLang(),
            speaker = sp, timings = timings,
            prompts = object : Prompts {
                override val languageTag = "he-IL"
                override fun singleMatch(name: String) = ""
                override fun picked(name: String) = ""
                override fun multiple(count: Int) = ""
                override fun noMatch() = "לא נמצא אף שם, נסה מחדש"
            },
        )
        c.onVisible(); speech.say("call Moshe")
        c.cancel(); c.recognizeAgain(); speech.say("תתקשר לדנה")
        assertTrue(sp.spoken.isEmpty())
        c.recognizeAgain(); speech.say("תתקשר לזבולון")
        assertEquals(listOf("לא נמצא אף שם, נסה מחדש"), sp.spoken)
    }

    // ---------------------------------------------------------------- v12: spoken flow

    @Test fun singleMatchSaysFoundOneNameThenCountsDown() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר ליוסי")
        assertEquals(listOf("single יוסי מזרחי"), sp.spoken)
        assertEquals(5_000, (c.state as DialState.Confirm).totalMs)
        assertTrue(!speech.listening)
        clock.advance(5_000); assertEquals("יוסי מזרחי", called.single().displayName)
    }

    @Test fun cancelDuringCountdownStopsTtsAndNeverCalls() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר ליוסי")
        c.cancel()
        assertTrue(!sp.speaking); clock.advance(10_000); assertTrue(called.isEmpty())
    }

    @Test fun spokenNumberSaysPickedAndCountsDown() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        val items = (c.state as DialState.Choosing).items
        speech.say("שתיים")
        assertEquals("picked ${items[1].displayName}", sp.spoken.last())
        assertEquals(items[1], (c.state as DialState.Confirm).entry)
    }

    // ---------------------------------------------------------------- v12: continuation after an engine cut

    private val hanni = listOf(ContactEntry(1, "חני כהן", "0501"), ContactEntry(2, "חני לוי", "0502"), ContactEntry(3, "משה פרץ", "0503"))

    /** The bug: the engine ends the session after "חני"; "כהן" follows → must call חני כהן. */
    @Test fun engineCutAfterFirstWordIsContinued() {
        timings.countdownMs = 0
        val c = controller(list = hanni); c.onVisible()
        speech.engineSay("חני")
        assertTrue("still listening: ${c.state}", c.state is DialState.Listening)
        assertEquals("חני", (c.state as DialState.Listening).partial)
        assertTrue(speech.listening); assertTrue(called.isEmpty())
        speech.partial("כהן")
        assertEquals("חני כהן", (c.state as DialState.Listening).partial)
        speech.say("כהן")
        assertEquals("חני כהן", called.single().displayName)
    }

    @Test fun engineCutWithNoMoreSpeechMatchesAfterContinuationWindow() {
        val c = controller(list = hanni); c.onVisible()
        speech.engineSay("חני")
        clock.advance(1_200); assertTrue(c.state is DialState.Listening)
        clock.advance(200)
        val s = c.state as DialState.Choosing
        assertEquals(setOf("חני כהן", "חני לוי"), s.items.map { it.displayName }.toSet())
    }

    @Test fun ourOwnEndOfSpeechMatchesImmediately() {
        val c = controller(list = hanni); c.onVisible()
        speech.say("חני")                     // byApp = true: the driver really stopped
        assertTrue(c.state is DialState.Choosing)
    }

    @Test fun continuationsAreCapped() {
        timings.countdownMs = 0
        val c = controller(list = hanni); c.onVisible()
        speech.engineSay("תתקשר")
        speech.engineSay("ל")
        speech.engineSay("משה")                // 3rd engine cut → match now (max 2 continuations)
        assertEquals("משה פרץ", called.single().displayName)
    }

    @Test fun continuationIsNotCutByNameWindow() {
        timings.nameWindowMs = 5_000; timings.countdownMs = 0
        val c = controller(list = hanni); c.onVisible()
        clock.advance(4_900); speech.engineSay("חני")
        clock.advance(300)                     // past the name window
        assertTrue(c.state is DialState.Listening)
        speech.partial("כהן"); speech.say("כהן")
        assertEquals("חני כהן", called.single().displayName)
    }

    @Test fun noSpeechInContinuationMatchesFirstPart() {
        val c = controller(list = hanni); c.onVisible()
        speech.engineSay("חני")
        speech.silence()
        assertTrue(c.state is DialState.Choosing)
    }

    // ---------------------------------------------------------------- v15: echo stripping

    @Test fun numberAppendedToEchoedPromptIsPicked() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        val items = (c.state as DialState.Choosing).items
        speech.say("נמצאו כמה שמות נא להגיד את מספר התוצאה הרצוי שתיים")
        assertEquals(items[1], (c.state as DialState.Confirm).entry)
    }

    @Test fun echoOnlyRelistensImmediatelyWithMicOn() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        val starts = speech.starts
        speech.say("נמצאו כמה שמות נא להגיד את מספר התוצאה הרצוי")
        assertEquals(MicState.LISTENING, (c.state as DialState.Choosing).mic)
        clock.advance(DialConfig().choiceRestartDelayMs + 1)
        assertEquals("restarted at once", starts + 1, speech.starts)
        assertTrue(speech.listening)
    }

    @Test fun repeatedEchoOnlySessionsNeverTurnMicOff() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        repeat(6) {
            speech.say("נמצאו כמה שמות נא להגיד")
            clock.advance(DialConfig().choiceRestartDelayMs + 1)
            assertEquals(MicState.LISTENING, (c.state as DialState.Choosing).mic)
        }
    }

    @Test fun echoPartialDoesNotBargeInButDriverRemainderDoes() {
        val sp = FakeSpeaker()
        val c = controller(sp); c.onVisible(); speech.say("תתקשר לדנה")
        speech.partial("נמצאו כמה שמ")
        assertTrue(sp.speaking)
        speech.partial("נמצאו כמה שמות שתיים")
        assertTrue(!sp.speaking)
        assertEquals("שתיים", (c.state as DialState.Choosing).partial)
    }

    // ---------------------------------------------------------------- v15b: names on the list, continuation

    private val twoMoshe = listOf(
        ContactEntry(1, "משה כהן", "0501111111", "נייד"), ContactEntry(1, "משה כהן", "0372222222", "עבודה"),
        ContactEntry(3, "משה לוי", "0503333333"),
    )

    @Test fun nameOnListPicksTheStrictlyBestRow() {
        val c = controller(list = hanni); c.onVisible(); speech.say("חני")
        val items = (c.state as DialState.Choosing).items
        speech.say("חני לוי")
        assertEquals("חני לוי", (c.state as DialState.Confirm).entry.displayName)
        assertTrue(items.any { it.displayName == "חני לוי" })
    }

    @Test fun nameFittingSeveralRowsAsksForNumberAndKeepsListening() {
        val sp = FakeSpeaker()
        val c = controller(sp, list = twoMoshe); c.onVisible(); speech.say("משה")
        val rows = (c.state as DialState.Choosing).items
        sp.finish()
        speech.say("משה כהן")
        assertEquals("נא לומר מספר", sp.spoken.last())
        assertEquals(MicState.LISTENING, (c.state as DialState.Choosing).mic)
        clock.advance(DialConfig().choiceRestartDelayMs + 1); assertTrue(speech.listening)
        // the hint's echo + the number → picked
        speech.say("נא לומר מספר שתיים")
        assertEquals(rows[1], (c.state as DialState.Confirm).entry)
    }

    @Test fun twoWordEngineResultSkipsContinuation() {
        timings.countdownMs = 0
        val c = controller(list = hanni); c.onVisible()
        speech.engineSay("חני כהן")
        assertEquals("חני כהן", called.single().displayName)
    }
}
