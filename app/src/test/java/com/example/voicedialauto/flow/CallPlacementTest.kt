package com.example.voicedialauto.flow

import org.junit.Assert.assertEquals
import org.junit.Test

class CallPlacementTest {

  private val cfg = CallPlacementConfig(maxAttempts = 3)

  @Test fun activeConfirmsWithoutRetry() {
    assertEquals(CallPlacementDecision.CONFIRMED, CallPlacementLogic.decide(0, true, false, cfg))
  }

  @Test fun everActivePreventsDuplicateRetry() {
    assertEquals(CallPlacementDecision.CONFIRMED, CallPlacementLogic.decide(2, false, true, cfg))
  }

  @Test fun inactiveRetriesUntilCap() {
    assertEquals(CallPlacementDecision.RETRY, CallPlacementLogic.decide(0, false, false, cfg))
    assertEquals(CallPlacementDecision.RETRY, CallPlacementLogic.decide(1, false, false, cfg))
    assertEquals(CallPlacementDecision.GIVE_UP, CallPlacementLogic.decide(2, false, false, cfg))
  }
}
