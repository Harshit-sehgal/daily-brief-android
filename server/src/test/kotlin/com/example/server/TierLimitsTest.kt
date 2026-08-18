package com.example.server

import com.example.server.billing.TierLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierLimitsTest {
  @Test
  fun `free allows exactly one project`() {
    assertEquals(1, TierLimits.maxProjects(TierLimits.TIER_FREE))
    assertEquals(Int.MAX_VALUE, TierLimits.maxProjects(TierLimits.TIER_PAID))
  }

  @Test
  fun `capacity and baselines are paid surfaces`() {
    assertFalse(TierLimits.allowsCapacity(TierLimits.TIER_FREE))
    assertTrue(TierLimits.allowsCapacity(TierLimits.TIER_PAID))
    assertFalse(TierLimits.allowsBaselines(TierLimits.TIER_FREE))
    assertTrue(TierLimits.allowsBaselines(TierLimits.TIER_PAID))
  }

  @Test
  fun `a lapsed trial is still free`() {
    // The boundary is about the tier, never the date: the trial end only tells the
    // client when to start talking about upgrading.
    assertFalse(TierLimits.isPaid(TierLimits.TIER_FREE, TierLimits.STATUS_TRIAL))
    assertTrue(TierLimits.isPaid(TierLimits.TIER_PAID, TierLimits.STATUS_ACTIVE))
  }

  @Test
  fun `a paid row in any other status is not treated as paid`() {
    assertFalse(TierLimits.isPaid(TierLimits.TIER_PAID, "past_due"))
  }
}