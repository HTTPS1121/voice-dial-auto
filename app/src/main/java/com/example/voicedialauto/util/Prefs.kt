package com.example.voicedialauto.util

import android.content.Context
import com.example.voicedialauto.flow.DEFAULT_CHOICE_WINDOW_MS
import com.example.voicedialauto.flow.DEFAULT_COUNTDOWN_MS
import com.example.voicedialauto.flow.DEFAULT_NAME_WINDOW_MS
import com.example.voicedialauto.flow.LanguageSource
import com.example.voicedialauto.flow.TimingSource
import com.example.voicedialauto.speech.vad.VadEndpointer
import com.example.voicedialauto.speech.vad.VadSettings

/** Small settings store shared by the phone UI and the car session. */
class Prefs(context: Context) : LanguageSource, TimingSource {
    private val sp = context.applicationContext.getSharedPreferences("voice_dial_prefs", Context.MODE_PRIVATE)

    init { migrate() }

    /**
     * v11: end-of-speech default 1.2 s → 1.8 s; v12: countdown default 3.5 s → 5 s. Users still on
     * an old default move to the new one once; anyone who picked another value keeps it.
     */
    private fun migrate() {
        val e = sp.edit()
        if (!sp.getBoolean(KEY_MIGRATED_V11, false)) {
            if (sp.getInt(KEY_END_SILENCE_TENTHS, -1) == OLD_END_SILENCE_DEFAULT_TENTHS) {
                e.putInt(KEY_END_SILENCE_TENTHS, END_SILENCE_DEFAULT_TENTHS)
            }
            e.putBoolean(KEY_MIGRATED_V11, true)
        }
        // v12: countdown default 3.5 s → 5 s; only users still on the old default are moved.
        if (!sp.getBoolean(KEY_MIGRATED_V12, false)) {
            if (sp.getInt(KEY_COUNTDOWN_HALF_S, -1) == OLD_COUNTDOWN_DEFAULT_HALF_S) {
                e.putInt(KEY_COUNTDOWN_HALF_S, COUNTDOWN_DEFAULT_HALF_S)
            }
            e.remove("read_list_aloud") // v9–v11 setting, removed in v12 (list prompt is always spoken)
            e.putBoolean(KEY_MIGRATED_V12, true)
        }
        // v13: the seconds-based "end-of-speech silence" setting is replaced by the VAD settings
        // (sensitivity + hangover). Everyone (incl. users on the old 1.8 s default) starts on the
        // new VAD defaults; the old value has different semantics and is dropped.
        // v15: the car mic (CarAudioRecord) was unstable in the real car (error 7 at start, error 11,
        // empty results) → PHONE mic with our own capture is the default; car mic is opt-in again.
        if (!sp.getBoolean(KEY_MIGRATED_V15, false)) {
            e.putBoolean(KEY_CAR_MIC, false)
            e.putBoolean(KEY_MIGRATED_V15, true)
        }
        if (!sp.getBoolean(KEY_MIGRATED_V13, false)) {
            e.remove(KEY_END_SILENCE_TENTHS)
            e.putBoolean(KEY_MIGRATED_V13, true)
        }
        e.apply()
    }

    /** BCP-47 tag of the primary recognition language: "he-IL" or "en-US". */
    override var primaryLanguage: String
        get() = sp.getString(KEY_LANG, LANG_HE) ?: LANG_HE
        set(v) = sp.edit().putString(KEY_LANG, v).apply()

    /** Mic source: phone mic via SpeechRecognizer, or car mic via CarAudioRecord (experimental). */
    var useCarMic: Boolean
        get() = sp.getBoolean(KEY_CAR_MIC, false)
        set(v) = sp.edit().putBoolean(KEY_CAR_MIC, v).apply()

    /** Intent used with CarContext.startCarApp: ACTION_DIAL (documented) or ACTION_CALL. */
    var useActionCall: Boolean
        get() = sp.getBoolean(KEY_ACTION_CALL, false)
        set(v) = sp.edit().putBoolean(KEY_ACTION_CALL, v).apply()

    // Timings (Settings → SeekBarPreference). Stored as whole seconds / half-seconds (ints).
    override val nameWindowMs: Long
        get() = sp.getInt(KEY_NAME_WINDOW_S, NAME_WINDOW_DEFAULT_S).coerceIn(NAME_WINDOW_MIN_S, NAME_WINDOW_MAX_S) * 1000L
    override val choiceWindowMs: Long
        get() = sp.getInt(KEY_LIST_WINDOW_S, LIST_WINDOW_DEFAULT_S).coerceIn(LIST_WINDOW_MIN_S, LIST_WINDOW_MAX_S) * 1000L
    override val countdownMs: Long
        get() = sp.getInt(KEY_COUNTDOWN_HALF_S, COUNTDOWN_DEFAULT_HALF_S).coerceIn(0, COUNTDOWN_MAX_HALF_S) * 500L
    override val listCountdownMs: Long
        get() = sp.getInt(KEY_LIST_COUNTDOWN_HALF_S, LIST_COUNTDOWN_DEFAULT_HALF_S).coerceIn(0, COUNTDOWN_MAX_HALF_S) * 500L

    /** Keep the listen window after leaving the car/phone UI. Default off: hide stops everything. */
    var continueWhenHidden: Boolean
        get() = sp.getBoolean(KEY_CONTINUE_WHEN_HIDDEN, false)
        set(v) = sp.edit().putBoolean(KEY_CONTINUE_WHEN_HIDDEN, v).apply()

    /**
     * v13: end-of-speech by a real VAD (Silero; WebRTC fallback). Sensitivity = probability
     * thresholds; hangover = silence after the last speech frame (tenths of a second, 5 = 0.5 s).
     */
    var vadEnabled: Boolean
        get() = sp.getBoolean(KEY_VAD_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_VAD_ENABLED, v).apply()
    val vadSensitivity: VadEndpointer.Sensitivity
        get() = VadEndpointer.Sensitivity.of(sp.getString(KEY_VAD_SENSITIVITY, "normal"))
    val vadHangoverMs: Long
        get() = sp.getInt(KEY_VAD_HANGOVER_TENTHS, VAD_HANGOVER_DEFAULT_TENTHS)
            .coerceIn(VAD_HANGOVER_MIN_TENTHS, VAD_HANGOVER_MAX_TENTHS) * 100L
    val vadSettings: VadSettings get() = VadSettings(vadEnabled, vadSensitivity, vadHangoverMs)

    /** Only for the RMS fallback (no own audio capture): derived from the hangover. */
    override val endOfSpeechSilenceMs: Long
        get() = (vadHangoverMs + 400L).coerceIn(800L, 2_000L)

    /**
     * v12: continuous (segmented) recognition on Android 13+. Default ON; turns itself off for the
     * process if the recognizer rejects it. Off = classic sessions + continuation listening.
     */
    var segmentedRecognition: Boolean
        get() = sp.getBoolean(KEY_SEGMENTED, true)
        set(v) = sp.edit().putBoolean(KEY_SEGMENTED, v).apply()

    /**
     * v16: Theme mode ("system", "light", "dark") and Material You dynamic colors (Android 12+).
     */
    var themeMode: String
        get() = sp.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = sp.edit().putString(KEY_THEME_MODE, v).apply()

    var dynamicColors: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_COLORS, true)
        set(v) = sp.edit().putBoolean(KEY_DYNAMIC_COLORS, v).apply()

    var nameWindowSeconds: Int
        get() = sp.getInt(KEY_NAME_WINDOW_S, NAME_WINDOW_DEFAULT_S).coerceIn(NAME_WINDOW_MIN_S, NAME_WINDOW_MAX_S)
        set(v) = sp.edit().putInt(KEY_NAME_WINDOW_S, v.coerceIn(NAME_WINDOW_MIN_S, NAME_WINDOW_MAX_S)).apply()

    var listWindowSeconds: Int
        get() = sp.getInt(KEY_LIST_WINDOW_S, LIST_WINDOW_DEFAULT_S).coerceIn(LIST_WINDOW_MIN_S, LIST_WINDOW_MAX_S)
        set(v) = sp.edit().putInt(KEY_LIST_WINDOW_S, v.coerceIn(LIST_WINDOW_MIN_S, LIST_WINDOW_MAX_S)).apply()

    var countdownHalfSeconds: Int
        get() = sp.getInt(KEY_COUNTDOWN_HALF_S, COUNTDOWN_DEFAULT_HALF_S).coerceIn(0, COUNTDOWN_MAX_HALF_S)
        set(v) = sp.edit().putInt(KEY_COUNTDOWN_HALF_S, v.coerceIn(0, COUNTDOWN_MAX_HALF_S)).apply()

    var listCountdownHalfSeconds: Int
        get() = sp.getInt(KEY_LIST_COUNTDOWN_HALF_S, LIST_COUNTDOWN_DEFAULT_HALF_S).coerceIn(0, COUNTDOWN_MAX_HALF_S)
        set(v) = sp.edit().putInt(KEY_LIST_COUNTDOWN_HALF_S, v.coerceIn(0, COUNTDOWN_MAX_HALF_S)).apply()

    var vadSensitivityKey: String
        get() = sp.getString(KEY_VAD_SENSITIVITY, "normal") ?: "normal"
        set(v) = sp.edit().putString(KEY_VAD_SENSITIVITY, v).apply()

    var vadHangoverTenths: Int
        get() = sp.getInt(KEY_VAD_HANGOVER_TENTHS, VAD_HANGOVER_DEFAULT_TENTHS)
            .coerceIn(VAD_HANGOVER_MIN_TENTHS, VAD_HANGOVER_MAX_TENTHS)
        set(v) = sp.edit().putInt(KEY_VAD_HANGOVER_TENTHS, v.coerceIn(VAD_HANGOVER_MIN_TENTHS, VAD_HANGOVER_MAX_TENTHS)).apply()

    fun resetTimings() {
        sp.edit()
            .putInt(KEY_NAME_WINDOW_S, NAME_WINDOW_DEFAULT_S)
            .putInt(KEY_LIST_WINDOW_S, LIST_WINDOW_DEFAULT_S)
            .putInt(KEY_COUNTDOWN_HALF_S, COUNTDOWN_DEFAULT_HALF_S)
            .putInt(KEY_LIST_COUNTDOWN_HALF_S, LIST_COUNTDOWN_DEFAULT_HALF_S)
            .apply()
    }

    override val secondaryLanguage: String get() = if (primaryLanguage == LANG_HE) LANG_EN else LANG_HE

    override fun togglePrimaryLanguage() { primaryLanguage = secondaryLanguage }

    companion object {
        const val LANG_HE = "he-IL"
        const val LANG_EN = "en-US"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLORS = "dynamic_colors"
        private const val KEY_LANG = "lang"
        private const val KEY_CAR_MIC = "car_mic"
        private const val KEY_ACTION_CALL = "action_call"
        const val KEY_NAME_WINDOW_S = "name_window_s"
        const val KEY_LIST_WINDOW_S = "list_window_s"
        const val KEY_COUNTDOWN_HALF_S = "countdown_half_s"
        const val KEY_LIST_COUNTDOWN_HALF_S = "list_countdown_half_s"
        private const val KEY_CONTINUE_WHEN_HIDDEN = "continue_when_hidden"
        const val LIST_COUNTDOWN_DEFAULT_HALF_S = 0
        val NAME_WINDOW_DEFAULT_S = (DEFAULT_NAME_WINDOW_MS / 1000).toInt()   // 10
        const val NAME_WINDOW_MIN_S = 5
        const val NAME_WINDOW_MAX_S = 60
        val LIST_WINDOW_DEFAULT_S = (DEFAULT_CHOICE_WINDOW_MS / 1000).toInt()  // 30
        const val LIST_WINDOW_MIN_S = 10
        const val LIST_WINDOW_MAX_S = 120
        val COUNTDOWN_DEFAULT_HALF_S = (DEFAULT_COUNTDOWN_MS / 500).toInt()    // 10 = 5.0 s (v12)
        const val OLD_COUNTDOWN_DEFAULT_HALF_S = 7                              // 3.5 s (v5–v11)
        private const val KEY_MIGRATED_V12 = "migrated_v12_countdown"
        const val COUNTDOWN_MAX_HALF_S = 20                                     // 10 s
        const val KEY_SEGMENTED = "segmented_recognition"
        const val KEY_VAD_ENABLED = "vad_enabled"
        const val KEY_VAD_SENSITIVITY = "vad_sensitivity"
        const val KEY_VAD_HANGOVER_TENTHS = "vad_hangover_tenths"
        const val VAD_HANGOVER_DEFAULT_TENTHS = 5                                 // 0.5 s
        const val VAD_HANGOVER_MIN_TENTHS = 2                                     // 0.2 s
        const val VAD_HANGOVER_MAX_TENTHS = 15                                    // 1.5 s
        private const val KEY_MIGRATED_V13 = "migrated_v13_vad"
        private const val KEY_MIGRATED_V15 = "migrated_v15_phone_mic"
        const val KEY_END_SILENCE_TENTHS = "end_silence_tenths"
        const val END_SILENCE_DEFAULT_TENTHS = 18                               // 1.8 s (v11; was 1.2 s)
        const val OLD_END_SILENCE_DEFAULT_TENTHS = 12
        private const val KEY_MIGRATED_V11 = "migrated_v11_end_silence"
        const val END_SILENCE_MIN_TENTHS = 6                                    // 0.6 s
        const val END_SILENCE_MAX_TENTHS = 30                                   // 3.0 s
    }
}
