package com.example.voicedialauto.flow

/** True while the platform reports an outgoing or connected call (telephony / in-call audio). */
fun interface CallStateProbe {
    fun isCallActive(): Boolean
}

/**
 * After [Caller.call] returns true, wait until the call is visible in telephony state before
 * retrying. Pure scheduling decisions — unit-tested on the JVM.
 */
data class CallPlacementConfig(
    val maxAttempts: Int = 3,
    val firstCheckMs: Long = 650,
    val betweenChecksMs: Long = 400,
)

enum class CallPlacementDecision { CONFIRMED, RETRY, GIVE_UP }

object CallPlacementLogic {
    /**
     * @param attempt 0-based index of the last launch attempt that already ran.
     * @param active true if [CallStateProbe] sees ringing / off-hook / in-call audio now.
     * @param everActive true if any earlier check in this placement sequence saw active.
     */
    fun decide(
        attempt: Int,
        active: Boolean,
        everActive: Boolean,
        config: CallPlacementConfig,
    ): CallPlacementDecision = when {
        active || everActive -> CallPlacementDecision.CONFIRMED
        attempt + 1 >= config.maxAttempts -> CallPlacementDecision.GIVE_UP
        else -> CallPlacementDecision.RETRY
    }
}
