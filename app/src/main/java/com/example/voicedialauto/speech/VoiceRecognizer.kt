package com.example.voicedialauto.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.car.app.CarContext
import com.example.voicedialauto.flow.FlowTimeline
import com.example.voicedialauto.flow.ListenOptions
import com.example.voicedialauto.flow.RecognitionError
import com.example.voicedialauto.flow.SpeechInput
import com.example.voicedialauto.flow.SpeechListener
import com.example.voicedialauto.util.FileLog
import com.example.voicedialauto.speech.vad.VadEndpointer
import com.example.voicedialauto.speech.vad.VadEngine
import com.example.voicedialauto.speech.vad.VadSettings

/**
 * SpeechRecognizer wrapper with our own end-of-utterance detection ([Endpointer]).
 *
 * v8: road noise often prevents Google's endpointing from firing, so after REAL speech we end the
 * session ourselves (stable partial / RMS quiet) and fall back to the last partial if onResults
 * doesn't arrive within [RESULTS_GRACE_MS].
 *
 * v10 (list screen "listen/stop loop"): v8 sent the SHORT silence hints (1.2 s / 0.9 s / min 0.4 s)
 * to every session, and every session — even the automatic restart after a noise-only session —
 * created a brand-new SpeechRecognizer and destroyed it at the end, so each restart replayed the
 * start/stop earcons and the host's mic indicator. In [ListenOptions.continuous] mode we now:
 *  - ask for LONG silence timeouts and a long minimum utterance length (only a hint; services
 *    that honour it keep one session open through noise), our [Endpointer] still ends the
 *    session ~silenceMs after real speech via stopListening();
 *  - never end a session on noise alone (only after a partial containing letters/digits);
 *  - keep the instance warm after a terminal callback and reuse it for the next session
 *    (no destroy/create between back-to-back sessions); a BUSY/CLIENT error right after a reuse
 *    is retried once on a fresh instance before it is reported;
 *  - stretch the watchdog to the session length.
 *
 * Must be used from the main thread.
 */
class VoiceRecognizer(
    private val context: Context,
    private val carContext: CarContext?,
    private val useCarMic: () -> Boolean = { false },
    /** Stable-partial / RMS quiet duration before we force end-of-speech (ms). */
    private val endOfSpeechSilenceMs: () -> Long = { DEFAULT_END_SILENCE_MS },
    /** v12: allow segmented (continuous) sessions on Android 13+ (Settings, default on). */
    private val segmentedEnabled: () -> Boolean = { true },
    /** v13: VAD end-of-speech settings (Silero / WebRTC on our own audio capture). */
    private val vadSettings: () -> VadSettings = { VadSettings() },
    /** v12: "phone" / "car" for the persistent log. */
    private val source: String = "phone",
) : SpeechInput {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    /** Kept-warm instance from the previous continuous session (idle, no listener work pending). */
    private var warm: SpeechRecognizer? = null
    private val dropWarm = Runnable { releaseWarm() }
    private var pump: MicPump? = null
    private var duck: AudioFocusRequest? = null
    /** DialController holds this across name → list / countdown so media doesn't unduck between hops. */
    private var duckPinned = false
    private var active = false
    private var sessionId = 0

    val isListening: Boolean get() = active

    override fun start(languageTag: String, listener: SpeechListener, options: ListenOptions) =
        start(languageTag, useCarMic(), listener, options)

    fun start(languageTag: String, useCarMic: Boolean, listener: SpeechListener, options: ListenOptions = ListenOptions()) {
        stopActive()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError(RecognitionError.NOT_AVAILABLE)
            return
        }
        // Duck immediately — not after VAD load / AudioRecord / StartGate. AA ducks late if we wait.
        ensureDuck()
        val carMicPossible = useCarMic && carContext != null && MicPump.carMicSupported(carContext)
        // v15: give the recognizer service / CarAudioRecord a moment after a car-mic session or a
        // cancel (error 11 within ~30 ms otherwise). Everything of the old session is released now.
        destroyPending()
        val wait = StartGate.delayMs(SystemClock.elapsedRealtime(), carMicPossible, lastCarEndAt, lastCancelAt)
        if (wait > 0) {
            FileLog.log("rec", "start gated ${wait}ms (carMic=$carMicPossible, after ${if (lastCancelAt > 0) "cancel" else "car session"})", source)
            active = true
            val r = Runnable {
                pendingStart = null
                active = false
                startInternal(languageTag, carMicPossible, listener, allowFallback = carMicPossible, options, allowReuse = false)
            }
            pendingStart = r
            main.postDelayed(r, wait)
            return
        }
        startInternal(languageTag, carMicPossible, listener, allowFallback = carMicPossible, options, allowReuse = true)
    }

    private var pendingStart: Runnable? = null
    private var lastCarEndAt = 0L
    private var lastCancelAt = 0L

    private fun startInternal(
        lang: String,
        carMic: Boolean,
        listener: SpeechListener,
        allowFallback: Boolean,
        options: ListenOptions,
        allowReuse: Boolean,
        transientAttempt: Int = 0,
    ) {
        destroyPending()
        ensureDuck()
        val mySession = ++sessionId
        active = true
        main.removeCallbacks(dropWarm)
        // Reuse only for continuous phone-mic sessions (the car-mic pump needs a fresh intent/pipe).
        val reuse = allowReuse && options.continuous && !carMic && warm != null
        val sr = if (reuse) warm!!.also { warm = null } else {
            releaseWarm()
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = sr
        val startedAt = SystemClock.elapsedRealtime()
        // v11 timing marks — name lookups only (list sessions would pollute the lookup's timeline).
        fun tl(key: String) { if (!options.continuous) FlowTimeline.mark(key, SystemClock.elapsedRealtime()) }
        tl("listen")

        val silenceMs = endOfSpeechSilenceMs().coerceIn(MIN_END_SILENCE_MS, MAX_END_SILENCE_MS)
        val sessionMs = if (options.continuous) {
            options.maxSessionMs.takeIf { it > 0 }?.coerceIn(3_000L, MAX_CONTINUOUS_SESSION_MS) ?: MAX_CONTINUOUS_SESSION_MS
        } else 0L
        // v12: segmented session (API 33+): one session across pauses between words; it ends only
        // when OUR end-of-speech detection calls stopListening (or the minimum length elapses).
        val segmented = options.segmented && options.continuous && !carMic &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && segmentedEnabled() && !segmentedBroken
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            // FREE_FORM (not WEB_SEARCH, which endpoints more aggressively).
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Hints — not all recognition services honour these, so we also endpoint ourselves.
            if (options.continuous) {
                // Nobody has spoken yet: keep the session open through noise as long as allowed.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, CONTINUOUS_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, CONTINUOUS_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, sessionMs)
                if (segmented && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(
                        RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    )
                }
            } else {
                // v11: 1.5 s / 1.2 s at the 1.8 s default (v10: 1.2 / 0.9 cut "חני כהן" to "חני").
                val complete = completeSilenceFor(silenceMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, complete)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, complete - 300L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            }
        }

        // v13: capture the audio OURSELVES (phone AudioRecord or car CarAudioRecord) and hand it to
        // the recognizer via EXTRA_AUDIO_SOURCE, so a real VAD (Silero; WebRTC fallback) can run on
        // the same frames. Not possible (Android < 13, VAD off, recognizer rejected it) → RMS fallback.
        val vs = vadSettings()
        var onFrame: (Float, Float, Long) -> Unit = { _, _, _ -> }
        var whyNoVad = ""
        val ownAllowed = audioPolicy.allowOwnAudio(SystemClock.elapsedRealtime())
        StartGate.recognizerMicReason(MicPump.isSupported(), vs.enabled, carMic, ownAllowed, audioPolicy.reason)?.let { r ->
            whyNoVad = if (!ownAllowed) "$r for ${(audioPolicy.disabledUntil - SystemClock.elapsedRealtime()) / 1000}s" else r
        }
        if (whyNoVad.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val engine = if (vs.enabled) runCatching { VadEngine.get(context) }.getOrNull() else null
                if (engine == null) whyNoVad = if (vs.enabled) "VAD engines failed to load" else "VAD off in settings"
                val p = if (carMic) MicPump.car(carContext!!, engine) { pr, db, at -> onFrame(pr, db, at) }
                else MicPump.phone(context, engine, duckMedia = source == "car") { pr, db, at -> onFrame(pr, db, at) }
                pump = p
                p.attachTo(intent)
                p.start()
            } catch (t: Throwable) {
                Log.w(TAG, "Own audio capture unavailable (carMic=$carMic)", t)
                FileLog.log("vad", "own capture failed (carMic=$carMic): ${t.javaClass.simpleName} ${t.message}", source)
                whyNoVad = "own capture failed: ${t.javaClass.simpleName}"
                pump?.stop(); pump = null
                // The intent may already carry the (now dead) pipe → rebuild it without the source.
                intent.removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE)
                intent.removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT)
                intent.removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING)
                intent.removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE)
            }
        }
        val usingCarMic = pump?.label == "car"
        val usingPump = pump != null
        val vadName = pump?.vadName ?: "none"
        val vadOn = usingPump && vadName != "none"
        run {
            val engineLabel = when {
                vadOn && vadName.startsWith("silero") -> "Silero"
                vadOn -> "WebRTC"
                else -> "RMS"
            }
            val why = when {
                engineLabel == "Silero" -> "ok (${pump?.label} mic)"
                engineLabel == "WebRTC" -> "Silero failed to load (${pump?.label} mic)"
                else -> whyNoVad.ifEmpty { if (usingPump) "VAD unavailable (RMS on own audio)" else "no own audio" }
            }
            VadStatus.set(engineLabel, why)
            FileLog.log("vad.session", "engine=$engineLabel reason=$why" + (pump?.let { " capture[${it.captureInfo}]" } ?: ""), source)
        }
        val vad = VadEndpointer(vs.sensitivity.start, vs.sensitivity.end, vs.hangoverMs.coerceIn(VadEndpointer.MIN_HANGOVER_MS, VadEndpointer.MAX_HANGOVER_MS))
        // RMS/stability endpointer: the whole job without VAD; with VAD only the 2.5 s stable-transcript net.
        val ep = Endpointer(silenceMs, maxStableMs = if (vadOn) VadEndpointer.STABLE_FALLBACK_MS else maxOf(silenceMs * 2, VadEndpointer.STABLE_FALLBACK_MS))
        var ending = false
        var readySeen = false
        var stopAt = 0L
        // v12: raw callback trace of this session (debug log + persistent file log).
        val events = StringBuilder()
        val sid = mySession
        fun rel(t: Long) = if (t == 0L) "-" else "+${t - startedAt}"
        fun ev(e: String) {
            val line = "+${SystemClock.elapsedRealtime() - startedAt} $e"
            if (events.length < 1_500) events.append(line).append(' ')
            FileLog.log("rec#$sid", line, source)
        }
        ev("start ${if (options.continuous) "continuous" else "name"} seg=$segmented reuse=$reuse carMic=$usingCarMic lang=$lang " +
            "audio=${if (usingPump) "own(${pump?.label})" else "recognizer"} vad=$vadName" +
            (if (vadOn) " thr=${vad.startThreshold}/${vad.endThreshold} hangover=${vad.hangoverMs}ms" else " rms-silence=${silenceMs}ms") +
            " session=${sessionMs}ms")
        // Segmented mode: finished segments + the current segment's partial.
        val segments = ArrayList<String>()
        var segPartial: String? = null
        var segmentsSeen = false
        fun transcript(): String = (segments + listOfNotNull(segPartial)).joinToString(" ").trim()
        var rmsMin = Float.MAX_VALUE; var rmsMax = -Float.MAX_VALUE; var rmsLoggedAt = startedAt

        fun log(end: String) = RecognizerSessionLog.add(
            RecognizerSessionLog.Entry(
                atMs = System.currentTimeMillis(), continuous = options.continuous, reused = reuse,
                carMic = usingCarMic, durationMs = SystemClock.elapsedRealtime() - startedAt,
                end = end, speech = ep.speechConfirmed, partial = ep.lastPartial,
                events = events.toString().trim(),
            )
        ).also { ev("end: $end") }

        /** Terminal: keep the instance warm if this was a clean continuous session end. */
        fun end(keepWarm: Boolean) = finish(keepWarm = keepWarm && options.continuous && !usingCarMic)

        fun deliverResults(list: List<String>, how: String) {
            if (mySession != sessionId) return
            val hyp = mergeFinalAndPartial(list, ep.lastPartial)
            val byApp = ending // our silence detection stopped it (vs the engine ending on its own)
            tl("results")
            val now = SystemClock.elapsedRealtime()
            log((if (hyp.isEmpty()) "$how:empty" else how) + " byApp=$byApp hyp=$hyp" +
                " t_lastSpeech=${rel(vad.lastSpeechAt)} t_stop=${rel(stopAt)} t_results=${rel(now)}" +
                (if (stopAt > 0) " wait=${now - stopAt}ms" else "") + (if (vadOn) " vad[${vad.summary()}]" else ""))
            end(keepWarm = true)
            if (hyp.isEmpty()) listener.onError(RecognitionError.NO_SPEECH) else listener.onFinalResults(hyp, byApp)
        }

        fun deliverError(mapped: RecognitionError, code: Int) {
            if (mySession != sessionId) return
            val p = ep.lastPartial
            // Prefer a real partial over a bare NO_SPEECH / NO_MATCH after we forced stop.
            if ((mapped == RecognitionError.NO_SPEECH || mapped == RecognitionError.OTHER) && p != null) {
                val byApp = ending
                log("error$code→partial byApp=$byApp \"$p\"")
                tl("results")
                end(keepWarm = mapped == RecognitionError.NO_SPEECH)
                listener.onFinalResults(listOf(p), byApp)
                return
            }
            log("error$code")
            end(keepWarm = mapped == RecognitionError.NO_SPEECH)
            listener.onError(mapped)
        }

        fun forceEnd(reason: String) {
            if (ending || mySession != sessionId) return
            ending = true
            tl("stop")
            stopAt = SystemClock.elapsedRealtime()
            ev("stopListening: $reason \"${ep.lastPartial}\" t_lastSpeech=${rel(vad.lastSpeechAt)} t_stop=${rel(stopAt)}")
            Log.i(TAG, "$reason → stopListening (\"${ep.lastPartial}\")")
            runCatching { sr.stopListening() }
            pump?.stop() // end of the audio stream → the recognizer finalizes right away
            // v11: wait up to RESULTS_GRACE_MS for the FINAL results (they usually contain every
            // word; partials lag), and only then fall back to the last partial.
            main.postDelayed({
                if (mySession == sessionId && active) deliverResults(emptyList(), "$reason+grace")
            }, RESULTS_GRACE_MS)
        }

        // v11: an end decision is confirmed CONFIRM_MS later; louder input / a new partial cancels it.
        var endDecidedAt = 0L
        var endReason = ""
        val confirmCheck = Runnable {
            if (mySession != sessionId || !active || ending || endDecidedAt == 0L) return@Runnable
            val now = SystemClock.elapsedRealtime()
            if (ep.confirmEnd(endDecidedAt, now)) forceEnd(endReason)
            else { ev("end cancelled: speech resumed"); endDecidedAt = 0L }
        }
        fun considerEnd(reason: String) {
            if (ending || endDecidedAt != 0L) return
            endDecidedAt = SystemClock.elapsedRealtime()
            endReason = reason
            ev("end decided: $reason")
            main.postDelayed(confirmCheck, Endpointer.CONFIRM_MS)
        }

        val stabilityCheck = object : Runnable {
            override fun run() {
                if (mySession != sessionId || !active || ending) return
                val now = SystemClock.elapsedRealtime()
                if (vadOn) {
                    if (ep.stableFor(now) >= VadEndpointer.STABLE_FALLBACK_MS) forceEnd("stable ${VadEndpointer.STABLE_FALLBACK_MS}ms (vad net)")
                    else if (vad.shouldEnd(now, ep.speechConfirmed, ep.changedAt)) forceEnd("vad silence ${vad.silenceFor(now)}ms")
                } else if (ep.shouldEnd(now)) considerEnd("stable ${silenceMs}ms+quiet")
                if (!ending && ep.speechConfirmed) main.postDelayed(this, STABILITY_TICK_MS)
            }
        }

        // v13: per 32 ms frame from our own capture (main thread): VAD endpointing + mic level.
        var vadLogAt = startedAt
        var ttsGated = false
        var catchUpLogged = false
        var pMax = 0f
        onFrame = frame@{ prob, db, at ->
            if (mySession != sessionId || !active) return@frame
            listener.onLevel(db)
            if (!vadOn) { ep.onRms(db, at); if (!ending && ep.shouldEnd(at)) considerEnd("stable+rms-quiet"); return@frame }
            // v15: our own TTS prompt (and its speaker tail) is not the driver's speech.
            val gated = TtsActivity.gated(at)
            if (gated != ttsGated) { ttsGated = gated; ev(if (gated) "vad gated: TTS playing" else "vad ungated: TTS done") }
            val p = if (gated) 0f else prob
            pMax = maxOf(pMax, prob)
            if (vad.onFrame(p, VadEngine.FRAME_MS, at)) {
                ev(if (vad.inSpeech) "vad speech (p=${"%.2f".format(prob)} total=${vad.speechMs}ms)"
                else "vad silence after ${rel(vad.lastSpeechAt)} (p=${"%.2f".format(prob)})")
            }
            if (at - vadLogAt >= 1_000) { ev("vad pmax=${"%.2f".format(pMax)} ${vad.summary()}"); vadLogAt = at; pMax = 0f }
            if (!ending && !catchUpLogged && vad.waitingForTranscript(at, ep.speechConfirmed, ep.changedAt)) {
                catchUpLogged = true
                ev("end held: VAD speech at ${rel(vad.segmentStartAt)} after last transcript ${rel(ep.changedAt)} → wait ≤${vad.catchUpMs}ms")
            }
            if (!ending && vad.shouldEnd(at, ep.speechConfirmed, ep.changedAt)) {
                ev("end decided: vad silence ${vad.silenceFor(at)}ms ≥ ${vad.hangoverMs}ms t_lastSpeech=${rel(vad.lastSpeechAt)} t_endDecided=${rel(at)}")
                forceEnd("vad")
            }
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (mySession != sessionId) return
                readySeen = true
                if (usingPump) audioPolicy.onReady()
                ev("onReadyForSpeech"); listener.onListening()
            }
            // Noise triggers this too — not proof of speech.
            override fun onBeginningOfSpeech() { if (mySession == sessionId) ev("onBeginningOfSpeech") }
            override fun onBufferReceived(buffer: ByteArray?) {}
            // v12: do NOT stop the car-mic pump here — the engine's end-of-speech can fire at the
            // pause after the first word, and closing the audio then truncated the utterance.
            override fun onEndOfSpeech() { if (mySession == sessionId) { tl("eos"); ev("onEndOfSpeech") } }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                if (mySession != sessionId || vadOn) return // VAD mode: level + endpointing from our frames
                listener.onLevel(rmsdB)
                val now = SystemClock.elapsedRealtime()
                ep.onRms(rmsdB, now)
                rmsMin = minOf(rmsMin, rmsdB); rmsMax = maxOf(rmsMax, rmsdB)
                if (now - rmsLoggedAt >= 1_000) {
                    ev("rms ${"%.1f".format(rmsMin)}..${"%.1f".format(rmsMax)} loud=${ep.loudSeen}")
                    rmsLoggedAt = now; rmsMin = Float.MAX_VALUE; rmsMax = -Float.MAX_VALUE
                }
                if (!ending && ep.shouldEnd(now)) considerEnd("stable+rms-quiet")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (mySession != sessionId || ending) return
                val raw = extractHypotheses(partialResults).firstOrNull() ?: return
                ev("onPartialResults \"$raw\"")
                val text = if (segmented) { segPartial = raw; transcript() } else raw
                if (ep.onPartial(text, SystemClock.elapsedRealtime())) {
                    listener.onPartial(text)
                    main.removeCallbacks(stabilityCheck)
                    main.postDelayed(stabilityCheck, silenceMs)
                }
            }

            override fun onResults(results: Bundle?) {
                if (mySession != sessionId) return
                main.removeCallbacks(stabilityCheck)
                val list = extractHypotheses(results)
                ev("onResults $list")
                // Segmented mode with segments already received: the whole transcript wins.
                if (segmentsSeen) deliverResults(listOf(transcript()), "results(seg)")
                else deliverResults(list, "results")
            }

            // v12 (API 33): segmented session callbacks.
            override fun onSegmentResults(segmentResults: Bundle) {
                if (mySession != sessionId) return
                segmentsSeen = true
                val seg = extractHypotheses(segmentResults).firstOrNull()
                ev("onSegmentResults \"${seg ?: ""}\"")
                if (seg != null && Endpointer.isMeaningful(seg)) segments += seg
                segPartial = null
                val t = transcript()
                if (!ending && ep.onPartial(t, SystemClock.elapsedRealtime())) {
                    listener.onPartial(t)
                    main.removeCallbacks(stabilityCheck)
                    main.postDelayed(stabilityCheck, silenceMs)
                }
            }

            override fun onEndOfSegmentedSession() {
                if (mySession != sessionId) return
                ev("onEndOfSegmentedSession")
                main.removeCallbacks(stabilityCheck)
                deliverResults(listOfNotNull(transcript().takeIf { it.isNotBlank() }), "endOfSegmentedSession")
            }

            override fun onError(error: Int) {
                if (mySession != sessionId) return
                main.removeCallbacks(stabilityCheck)
                ev("onError $error (carMic=$usingCarMic reused=$reuse seg=$segmented partial=${ep.lastPartial})")
                val mapped = map(error)
                val quick = SystemClock.elapsedRealtime() - startedAt < REUSE_FAILURE_MS
                // v15: error 11 (server disconnected) and 7 within 1 s of the start are TRANSIENT
                // (seen in the car right after a previous session ended): short pause, fresh
                // recognizer, SAME mic and own audio. Never disables anything.
                if (AudioSourcePolicy.isTransientStartError(error, SystemClock.elapsedRealtime() - startedAt,
                        speechSeen = ep.speechConfirmed || segmentsSeen) &&
                    transientAttempt < AudioSourcePolicy.MAX_TRANSIENT_RETRIES
                ) {
                    log("error$error transient → retry ${transientAttempt + 1}/${AudioSourcePolicy.MAX_TRANSIENT_RETRIES} in ${AudioSourcePolicy.TRANSIENT_DELAY_MS}ms (fresh recognizer, same mic)")
                    finish(keepWarm = false)
                    val expect = sessionId
                    main.postDelayed({
                        if (sessionId == expect && !active) {
                            startInternal(lang, carMic, listener, allowFallback, options, allowReuse = false, transientAttempt = transientAttempt + 1)
                        }
                    }, AudioSourcePolicy.TRANSIENT_DELAY_MS)
                    return
                }
                // v14: own-audio rejection needs a precise signature twice in a row (AudioSourcePolicy);
                // benign errors (busy, network, reuse…) never turn Silero off.
                if (usingPump && !usingCarMic) {
                    when (audioPolicy.onError(error, readySeen, freshRecognizer = !reuse,
                        speechSeen = ep.speechConfirmed || segmentsSeen, now = SystemClock.elapsedRealtime())) {
                        AudioSourcePolicy.Decision.RETRY_OWN_AUDIO -> {
                            log("error$error before ready → retry own audio on a fresh recognizer (1/2)")
                            finish(keepWarm = false)
                            main.post { startInternal(lang, carMic, listener, allowFallback, options, allowReuse = false) }
                            return
                        }
                        AudioSourcePolicy.Decision.DISABLE_AND_FALL_BACK -> {
                            log("error$error before ready twice → own audio paused 5 min (RMS fallback)")
                            finish(keepWarm = false)
                            main.post { startInternal(lang, carMic, listener, allowFallback, options, allowReuse = false) }
                            return
                        }
                        AudioSourcePolicy.Decision.IGNORE -> Unit
                    }
                }
                // v14: same precision for segmented mode — fresh recognizer, never ready, non-benign code,
                // and not already explained by our own audio source.
                if (segmented && !usingPump && !reuse && !readySeen && !ep.speechConfirmed && !segmentsSeen &&
                    SystemClock.elapsedRealtime() - startedAt < SEGMENTED_FAILURE_MS &&
                    !AudioSourcePolicy.isBenign(error)
                ) {
                    // The recognizer rejected segmented mode: disable it for this process, retry classic.
                    segmentedBroken = true
                    log("error$error→segmented off")
                    finish(keepWarm = false)
                    main.post {
                        startInternal(lang, carMic, listener, allowFallback, options.copy(segmented = false), allowReuse = false)
                    }
                    return
                }
                if (reuse && quick && (mapped == RecognitionError.BUSY || mapped == RecognitionError.CLIENT)) {
                    // The warm instance wasn't ready: silently retry once on a fresh one.
                    log("error$error→fresh")
                    finish(keepWarm = false)
                    main.post { startInternal(lang, carMic, listener, allowFallback, options, allowReuse = false) }
                    return
                }
                if (usingCarMic && allowFallback && mapped != RecognitionError.NO_SPEECH && ep.lastPartial == null) {
                    log("error$error→phoneMic (own capture + VAD unless rejected: " +
                        "${StartGate.recognizerMicReason(MicPump.isSupported(), vadSettings().enabled, false, audioPolicy.allowOwnAudio(SystemClock.elapsedRealtime()), audioPolicy.reason) ?: "own"})")
                    finish(keepWarm = false)
                    main.post { startInternal(lang, carMic = false, listener = listener, allowFallback = false, options, allowReuse = false) }
                    return
                }
                deliverError(mapped, error)
            }
        })
        sr.startListening(intent)

        val watchdog = if (options.continuous) sessionMs + WATCHDOG_SLACK_MS else WATCHDOG_MS
        main.postDelayed({
            if (mySession == sessionId && active) {
                Log.w(TAG, "Recognition watchdog fired (partial=${ep.lastPartial})")
                main.removeCallbacks(stabilityCheck)
                if (ep.lastPartial != null) deliverResults(emptyList(), "watchdog")
                else {
                    log("watchdog")
                    // A hung session: don't reuse it.
                    runCatching { sr.cancel() }
                    finish(keepWarm = false)
                    listener.onError(RecognitionError.NO_SPEECH)
                }
            }
        }, watchdog)
    }

    private fun extractHypotheses(bundle: Bundle?): List<String> {
        if (bundle == null) return emptyList()
        return bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.filter { it.isNotBlank() }.orEmpty()
    }

    private fun map(error: Int): RecognitionError = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.NO_SPEECH
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError.NO_PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> RecognitionError.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> RecognitionError.BUSY
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> RecognitionError.CLIENT
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            RecognitionError.LANGUAGE
        else -> RecognitionError.OTHER
    }

    private fun ensureDuck() {
        if (source != "car" || duck != null) return
        duck = NavDuck.hold(context)
        FileLog.log("audio", "nav-duck", source)
    }

    override fun pinDuck() {
        duckPinned = true
        ensureDuck()
    }

    override fun unpinDuck() {
        duckPinned = false
        if (active) return
        NavDuck.release(context, duck)
        duck = null
        FileLog.log("audio", "nav-unduck", source)
    }

    /** Ends the current session. [keepWarm]: park the (idle) instance for an immediate restart. */
    private fun finish(keepWarm: Boolean = false) {
        active = false
        sessionId++
        if (pump?.label == "car") lastCarEndAt = SystemClock.elapsedRealtime()
        pump?.stop(); pump = null
        if (!duckPinned) {
            NavDuck.release(context, duck)
            duck = null
        }
        recognizer?.let { r ->
            if (keepWarm) {
                releaseWarm()
                warm = r
                main.removeCallbacks(dropWarm)
                main.postDelayed(dropWarm, WARM_KEEP_MS)
            } else {
                toDestroy += r
                main.post { destroyPending() }
            }
        }
        recognizer = null
    }

    private fun releaseWarm() {
        main.removeCallbacks(dropWarm)
        warm?.let { toDestroy += it; main.post { destroyPending() } }
        warm = null
    }

    private val toDestroy = mutableListOf<SpeechRecognizer>()

    private fun destroyPending() {
        val list = toDestroy.toList(); toDestroy.clear()
        list.forEach { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
    }

    /** Cancels an active session only (a warm instance stays for reuse). */
    private fun stopActive() {
        pendingStart?.let { main.removeCallbacks(it); pendingStart = null; active = false }
        if (recognizer != null) {
            runCatching { recognizer?.cancel() }
            lastCancelAt = SystemClock.elapsedRealtime()
        }
        finish(keepWarm = false)
    }

    override fun cancel() {
        stopActive()
        releaseWarm()
    }

    fun destroy(keepDuck: Boolean = false) {
        if (!keepDuck) duckPinned = false
        cancel()
        destroyPending()
    }

    companion object {
        private const val TAG = "VoiceRecognizer"
        private const val WATCHDOG_MS = 15_000L
        private const val WATCHDOG_SLACK_MS = 5_000L
        /** v11: was 800 ms — final results often contain the second word the partial missed. */
        const val RESULTS_GRACE_MS = 600L // v13: was 1.2 s; the closed audio stream makes finals quick
        private const val STABILITY_TICK_MS = 200L
        /** Continuous mode: silence hints while nobody has spoken yet. */
        const val CONTINUOUS_SILENCE_MS = 10_000L
        /** Continuous mode: one session never asks for more than this (watchdog = this + slack). */
        const val MAX_CONTINUOUS_SESSION_MS = 20_000L
        /** How long an idle instance is kept for reuse. */
        private const val WARM_KEEP_MS = 3_000L
        /** v12: an early non-silence error in segmented mode → segmented is off for this process. */
        private const val SEGMENTED_FAILURE_MS = 1_500L
        /** v12: set once the recognizer rejected a segmented session (process-wide). */
        @Volatile var segmentedBroken = false
        /** v14: replaces v13's permanent externalAudioBroken flag (temporary, needs 2 precise failures). */
        val audioPolicy = AudioSourcePolicy()
        /** App opened / car session started: give our own audio (Silero) a fresh chance. */
        fun resetAudioPolicy() { audioPolicy.reset(); FileLog.log("vad", "own-audio policy reset") }
        /** BUSY/CLIENT this soon after starting a reused instance → retry on a fresh one. */
        private const val REUSE_FAILURE_MS = 700L
        /** v13: RMS-fallback only (VAD mode uses the hangover); derived from the 0.5 s hangover. */
        const val DEFAULT_END_SILENCE_MS = 900L

        /** Engine hint for "complete silence": 1.5 s at the 1.8 s default, never below 1.5 s. */
        fun completeSilenceFor(silenceMs: Long): Long = maxOf(1_500L, silenceMs - 300L)

        /**
         * Final results first (they usually hold every word). If the last partial has MORE words
         * than the best final and extends it ("חני" vs "חני כהן"), the partial replaces that final;
         * empty finals fall back to the partial.
         */
        fun mergeFinalAndPartial(finals: List<String>, partial: String?): List<String> {
            val f = finals.filter { it.isNotBlank() }
            val p = partial?.takeIf { Endpointer.isMeaningful(it) }
            if (f.isEmpty()) return listOfNotNull(p)
            if (p == null || f.any { it.trim() == p.trim() }) return f
            val best = f.first().trim()
            val longer = p.trim().split(Regex("\\s+")).size > best.split(Regex("\\s+")).size
            // The final is a strict prefix of a longer partial (engine stopped before the 2nd word
            // was finalized): the partial is the more complete transcript → it replaces that final.
            return if (longer && p.trim().startsWith(best)) listOf(p) + f.drop(1) else f
        }
        const val MIN_END_SILENCE_MS = 600L
        const val MAX_END_SILENCE_MS = 3_000L
    }
}
