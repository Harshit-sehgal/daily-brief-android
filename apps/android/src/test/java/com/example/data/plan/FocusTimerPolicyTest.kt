package com.example.data.plan

import com.example.data.model.PlanBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusTimerPolicyTest {
  private fun block(id: String, startAt: Long, endAt: Long) =
    PlanBlock(
      id = id,
      planItemId = "item-$id",
      startAt = startAt,
      endAt = endAt,
      position = 0,
      locked = false,
      createdAt = startAt,
      updatedAt = startAt,
    )

  @Test
  fun `an ended block cannot start a session`() {
    val now = 10_000L
    assertNull(FocusTimerPolicy.specFor(block("b", now - 60_000, now - 1), now))
    assertNull(FocusTimerPolicy.specFor(block("b", now - 60_000, now), now))
  }

  @Test
  fun `a live block runs for its own remaining length`() {
    val now = 10_000L
    val spec = FocusTimerPolicy.specFor(block("b", now - 15_000, now + 45_000), now)
    assertEquals(now + 45_000, spec?.endAtMs)
    assertEquals("b", spec?.blockId)
    assertEquals("item-b", spec?.itemId)
  }

  @Test
  fun `a long block is capped so the timer stays a session`() {
    val now = 10_000L
    val spec = FocusTimerPolicy.specFor(block("b", now, now + 8 * 60 * 60_000L), now)
    assertEquals(now + FocusTimerPolicy.MAX_SESSION_MINUTES * 60_000L, spec?.endAtMs)
  }

  @Test
  fun `a block starting later still runs at most the cap from now`() {
    val now = 10_000L
    val spec = FocusTimerPolicy.specFor(block("b", now + 3 * 60 * 60_000L, now + 4 * 60 * 60_000L), now)
    assertEquals(now + FocusTimerPolicy.MAX_SESSION_MINUTES * 60_000L, spec?.endAtMs)
  }
}