package com.example.voicedialauto.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionWatchdogTest {

  private val cfg = RecognitionWatchdogConfig()

  @Test fun readyTimeoutRestartsWhenNeverReady() {
    assertEquals(
      RecognitionWatchdogAction.RESTART_READY,
      RecognitionWatchdogLogic.readyTimeout(false, 0, cfg),
    )
  }

  @Test fun readyTimeoutStopsAfterCap() {
    assertEquals(
      RecognitionWatchdogAction.NONE,
      RecognitionWatchdogLogic.readyTimeout(false, cfg.maxRestartsPerListen, cfg),
    )
  }

  @Test fun stallWhenReadyButNoTranscript() {
    assertEquals(
      RecognitionWatchdogAction.RESTART_STALL,
      RecognitionWatchdogLogic.stallTimeout(true, false, cfg.stallAfterReadyMs, 0, cfg),
    )
  }

  @Test fun noStallWhenTranscriptPresent() {
    assertEquals(
      RecognitionWatchdogAction.NONE,
      RecognitionWatchdogLogic.stallTimeout(true, true, 10_000, 0, cfg),
    )
  }
}
