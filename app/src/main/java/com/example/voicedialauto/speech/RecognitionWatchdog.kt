package com.example.voicedialauto.speech

/**
 * When the listen screen is open but the recognizer never becomes ready or stalls with no events,
 * restart the session (bounded). Pure Kotlin for unit tests.
 */
data class RecognitionWatchdogConfig(
    val readyTimeoutMs: Long = 2_800,
    val stallAfterReadyMs: Long = 4_500,
    val maxRestartsPerListen: Int = 2,
)

enum class RecognitionWatchdogAction { NONE, RESTART_READY, RESTART_STALL }

object RecognitionWatchdogLogic {
    fun readyTimeout(
        readySeen: Boolean,
        restarts: Int,
        config: RecognitionWatchdogConfig,
    ): RecognitionWatchdogAction =
        if (!readySeen && restarts < config.maxRestartsPerListen) RecognitionWatchdogAction.RESTART_READY
        else RecognitionWatchdogAction.NONE

    fun stallTimeout(
        readySeen: Boolean,
        transcriptHeard: Boolean,
        readyForMs: Long,
        restarts: Int,
        config: RecognitionWatchdogConfig,
    ): RecognitionWatchdogAction =
        if (readySeen && !transcriptHeard && readyForMs >= config.stallAfterReadyMs &&
            restarts < config.maxRestartsPerListen
        ) RecognitionWatchdogAction.RESTART_STALL
        else RecognitionWatchdogAction.NONE
}
