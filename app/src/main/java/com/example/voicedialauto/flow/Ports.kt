package com.example.voicedialauto.flow

import com.example.voicedialauto.contacts.ContactEntry

/** Recognition errors, platform-neutral. */
/** CLIENT = ERROR_CLIENT / server-disconnected (usually a session torn down under us); transient like BUSY. */
enum class RecognitionError { NO_SPEECH, NOT_AVAILABLE, NO_PERMISSION, NETWORK, LANGUAGE, BUSY, CLIENT, OTHER }

interface SpeechListener {
    fun onListening() {}
    fun onPartial(text: String) {}
    /** Input level in dB as reported by SpeechRecognizer.onRmsChanged (roughly -2..10). */
    fun onLevel(rmsDb: Float) {}
    /** Best-first list of hypotheses (never empty). */
    fun onResults(hypotheses: List<String>)
    /**
     * v12: final results with how the session ended. [byApp] = OUR end-of-speech detection
     * (stable transcript + RMS quiet) stopped it → the driver really finished. false = the
     * recognition ENGINE ended the session on its own — on many Google versions that happens at
     * the short pause between two words ("חני" | "כהן"), so the dialer listens on (continuation).
     */
    fun onFinalResults(hypotheses: List<String>, byApp: Boolean) = onResults(hypotheses)
    fun onError(error: RecognitionError)
}

/**
 * v10: how a recognizer session should behave.
 * [continuous] = list screen: back-to-back sessions that must feel like one open mic. The
 * recognizer asks for long silence timeouts while nobody spoke, ends a session only after real
 * speech (non-blank partial), and reuses the SpeechRecognizer instance for the next session.
 * [maxSessionMs] = time left in the listening window (upper bound for one session).
 */
data class ListenOptions(
    val continuous: Boolean = false,
    val maxSessionMs: Long = 0,
    /**
     * v12: ask for a SEGMENTED session (Android 13+, RecognizerIntent.EXTRA_SEGMENTED_SESSION):
     * the engine keeps one session open across pauses and reports each segment; only our own
     * end-of-speech detection ends it. Ignored where unsupported (automatic fallback).
     */
    val segmented: Boolean = false,
)

/** One-shot speech recognition. Starting again cancels the previous session. */
interface SpeechInput {
    fun start(languageTag: String, listener: SpeechListener) = start(languageTag, listener, ListenOptions())
    fun start(languageTag: String, listener: SpeechListener, options: ListenOptions)
    /** Stops everything and releases the recognizer (including a kept-warm instance). */
    fun cancel()
    /** Keep media ducked across recognizer/TTS hops (car). No-op on phone fakes. */
    fun pinDuck() {}
    fun unpinDuck() {}
}

interface ContactsSource {
    /** Start (re)loading in the background. Optional [onLoaded] runs on the main thread. */
    fun refresh(onLoaded: ((List<ContactEntry>) -> Unit)? = null)
    /** v11: reload only if empty / stale / changed. Called on every listening start. */
    fun ensureFresh() = refresh()
    /** Invoke [block] on the main thread with a non-empty list when possible (waits for load). */
    fun withContacts(block: (List<ContactEntry>) -> Unit)
}

/** Places the call. Returns false if it could not be started at all. */
fun interface Caller {
    fun call(entry: ContactEntry): Boolean
}

interface Cancellable { fun cancel() }

/**
 * v11: runs CPU work (contact matching) off the main thread and delivers the result back on the
 * main thread. Tests use [Inline].
 */
interface Worker {
    fun <T> submit(work: () -> T, onDone: (T) -> Unit)

    object Inline : Worker {
        override fun <T> submit(work: () -> T, onDone: (T) -> Unit) = onDone(work())
    }
}

interface Scheduler {
    fun now(): Long
    fun post(delayMs: Long, block: () -> Unit): Cancellable
}

/**
 * User-adjustable timings (phone Settings), read at the start of every listening request /
 * countdown so changes apply immediately on phone and car.
 */
interface TimingSource {
    /** Main screen: total time to wait for a name, including silence restarts. */
    val nameWindowMs: Long
    /** List: total time to wait for "1, 2, 3…" or a name, including silence restarts. */
    val choiceWindowMs: Long
    /** Delay before an automatic call after a single name match; 0 = call immediately. Default 5 s. */
    val countdownMs: Long
    /** Delay after a list pick (number/name/row). 0 = call immediately. */
    val listCountdownMs: Long get() = 0
    /**
     * RMS-fallback end-of-speech (only when we cannot capture audio for the VAD): a stable partial
     * this long + RMS quiet. v13: derived from the VAD hangover (default 0.5 s → 900 ms).
     */
    val endOfSpeechSilenceMs: Long get() = 900L
}

interface LanguageSource {
    val primaryLanguage: String
    val secondaryLanguage: String
    fun togglePrimaryLanguage()
}

/** Text-to-speech. [onDone] must be invoked exactly once (also on failure). */
interface Speaker {
    fun speak(text: String, languageTag: String, onDone: (() -> Unit)? = null)
    fun stop()
}

/**
 * Localized spoken prompts (v12: spoken on phone AND car). Blank text is never spoken.
 * None of them may contain digits or contact-like words that a TTS echo could turn into a pick.
 */
interface Prompts {
    /** BCP-47 tag of the language the prompts are written in (used for the TTS voice). */
    val languageTag: String
    /** Exactly one match: "נמצא שם אחד, דנה כהן". */
    fun singleMatch(name: String): String
    /** Row tapped / number spoken on the list: "מתקשר לדנה כהן". */
    fun picked(name: String): String
    /** Several matches (mic already open): "נמצאו כמה שמות, נא להגיד את מספר התוצאה הרצוי". */
    fun multiple(count: Int): String
    /** "לא נמצא אף שם, נסה מחדש". */
    fun noMatch(): String
    /** v15: list screen, a name that fits several rows equally: "נא לומר מספר". */
    fun sayNumber(): String = ""
}
