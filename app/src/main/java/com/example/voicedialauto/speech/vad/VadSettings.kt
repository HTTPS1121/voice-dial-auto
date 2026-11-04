package com.example.voicedialauto.speech.vad

/** v13: end-of-speech settings handed to the recognizer per session. */
data class VadSettings(
    val enabled: Boolean = true,
    val sensitivity: VadEndpointer.Sensitivity = VadEndpointer.Sensitivity.NORMAL,
    val hangoverMs: Long = VadEndpointer.DEFAULT_HANGOVER_MS,
)
