package com.example.voicedialauto.flow

import com.example.voicedialauto.contacts.ContactEntry
import com.example.voicedialauto.contacts.ScoredContact

enum class MicState { SPEAKING, LISTENING, OFF }

enum class DialError { NOT_AVAILABLE, NETWORK, LANGUAGE, NO_CONTACTS, CALL_FAILED, OTHER }

/** Every UI state of the dialer (see SPEC.md §3). Rendered by the phone activity and the car screens. */
sealed interface DialState {
    data class NeedsPermissions(val missing: List<String>) : DialState
    data class Idle(val lastCalled: String? = null) : DialState
    data class Listening(val lang: String, val partial: String? = null) : DialState
    data class Processing(val heard: String) : DialState
    /** [secondsLeft] = whole seconds shown (rounded up); [remainingMs]/[totalMs] drive the progress bar. */
    data class Confirm(
        val entry: ContactEntry,
        val secondsLeft: Int,
        val heard: String?,
        val remainingMs: Long = secondsLeft * 1000L,
        val totalMs: Long = remainingMs,
    ) : DialState
    data class Calling(val entry: ContactEntry) : DialState
    data class Choosing(
        val items: List<ContactEntry>,
        val mic: MicState,
        val partial: String? = null,
        /** The driver said a number that isn't on the list (e.g. "five" with 3 rows). */
        val outOfRange: Int? = null,
    ) : DialState
    /**
     * heard == "" → nothing was heard.
     * [contactsCount] / [topCandidates] are diagnostics shown on no-match (car + phone).
     */
    data class NoMatch(
        val heard: String,
        val suggestions: List<ContactEntry>,
        val contactsCount: Int = 0,
        val topCandidates: List<ScoredContact> = emptyList(),
        /** Query strings actually scored by ContactMatcher (after filler strip). */
        val matchedQuery: String = "",
    ) : DialState
    data class Failed(val error: DialError, val name: String? = null) : DialState
}

data class DialConfig(
    /** Defaults; overridden by a [TimingSource] (Settings) when one is given to the controller. */
    val nameWindowMs: Long = DEFAULT_NAME_WINDOW_MS,
    val choiceWindowMs: Long = DEFAULT_CHOICE_WINDOW_MS,
    val countdownMs: Long = DEFAULT_COUNTDOWN_MS,
    /** After a list pick (row / spoken number). 0 = call immediately. */
    val listCountdownMs: Long = DEFAULT_LIST_COUNTDOWN_MS,
    /**
     * A recognizer session that ended (silence/error) faster than this is a "fast failure"
     * (error storm, recognizer refusing). Only [maxFastRestarts] of those per request are restarted,
     * and they also pass the circuit breaker. Healthy sessions (≥ this) are restarted until the
     * window expires — they are naturally rate-limited (≤ 1 per minHealthySessionMs).
     */
    val minHealthySessionMs: Long = 1_500,
    val maxFastRestarts: Int = 1,
    /** Don't open a new session if less than this is left in the window. */
    val minRemainingForRestartMs: Long = 1_500,
    /** Phone 60 s, car 5 s. */
    val afterCallQuietMs: Long = 60_000,
    val listLimit: Int = 8,
    /** BUSY/CLIENT errors: retries per listening request, each after [transientBackoffMs]. */
    val transientRetries: Int = 1,
    val transientBackoffMs: Long = 750,
    /** Small gap before a silence restart so we never hammer the recognizer. */
    val silenceRestartDelayMs: Long = 300,
    /**
     * v10, list screen: back-to-back sessions restart immediately (posted with 0 delay), and a
     * session counts as a fast failure (error storm) only below this length — a noise-only
     * session of a second or two is normal and must not close the mic.
     */
    val choiceRestartDelayMs: Long = 0,
    val choiceMinHealthySessionMs: Long = 700,
    /**
     * v12, name screen: when the ENGINE ends a session (not our silence detection), keep listening
     * this long for more words before matching ("חני" … "כהן"); at most [maxContinuations] times.
     */
    val continuationMs: Long = 1_300,
    val maxContinuations: Int = 2,
    /** Circuit breaker: max automatic (non-user) listening starts per window. */
    val maxAutoStarts: Int = 3,
    val autoStartWindowMs: Long = 20_000,
    /** Away shorter than this (dialog, shade, overlay) never auto-restarts listening from Idle. */
    val minAwayForAutoListenMs: Long = 2_000,
    /**
     * AA host often onStop/onStart within this window on first paint. Don't kill the mic yet —
     * otherwise the next onVisible dumps to Idle ("tap the mic").
     */
    val hideDebounceMs: Long = 400,
    /** Keep media ducked after dialing until the phone call grabs audio (or this timeout). */
    val duckHoldAfterDialMs: Long = 3_000,
    /** Outbound call: probe telephony after launch; retry if the call never appears (bounded). */
    val callPlacement: CallPlacementConfig = CallPlacementConfig(),
    val suggestionThreshold: Double = 0.35,
    val maxSuggestions: Int = 3,
    /** Match threshold (ContactMatcher). Lowered in v7 for first-name / cross-script. */
    val matchThreshold: Double = 0.62,
)

const val DEFAULT_NAME_WINDOW_MS = 10_000L
const val DEFAULT_CHOICE_WINDOW_MS = 30_000L
const val DEFAULT_COUNTDOWN_MS = 5_000L
const val DEFAULT_LIST_COUNTDOWN_MS = 0L

/** "Restart on silence" budget: at most [maxRestarts] restarts and only within [windowMs] since [begin]. */
class RestartPolicy(private val windowMs: Long, private val maxRestarts: Int) {
    private var startedAt = 0L
    var restartsUsed = 0
        private set

    fun begin(now: Long) { startedAt = now; restartsUsed = 0 }

    fun tryRestart(now: Long): Boolean {
        if (restartsUsed >= maxRestarts || now - startedAt >= windowMs) return false
        restartsUsed++
        return true
    }
}

/** Sliding-window limiter used as a circuit breaker against restart loops. */
class RateLimiter(private val maxEvents: Int, private val windowMs: Long) {
    private val events = ArrayDeque<Long>()
    fun tryAcquire(now: Long): Boolean {
        while (events.isNotEmpty() && now - events.first() >= windowMs) events.removeFirst()
        if (events.size >= maxEvents) return false
        events.addLast(now); return true
    }
    fun reset() = events.clear()
}
