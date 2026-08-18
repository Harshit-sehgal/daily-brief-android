package com.example.ui.screens

import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineAccessibilityMovePolicyTest {
  @Test
  fun `moves exactly fifteen minutes while preserving an off-grid minute offset`() {
    assertEquals(
      9 * 60 - 8,
      timelineAccessibilityMoveTarget(
        startMinute = 9 * 60 + 7,
        durationMinutes = 60,
        deltaMinutes = -15,
      ),
    )
    assertEquals(
      9 * 60 + 22,
      timelineAccessibilityMoveTarget(
        startMinute = 9 * 60 + 7,
        durationMinutes = 60,
        deltaMinutes = 15,
      ),
    )
  }

  @Test
  fun `omits a direction that would move outside the day`() {
    assertNull(
      timelineAccessibilityMoveTarget(
        startMinute = 0,
        durationMinutes = 60,
        deltaMinutes = -15,
      )
    )
    assertNull(
      timelineAccessibilityMoveTarget(
        startMinute = 23 * 60,
        durationMinutes = 60,
        deltaMinutes = 15,
      )
    )
  }

  @Test
  fun `full-day block and unsupported increments fail closed`() {
    assertNull(
      timelineAccessibilityMoveTarget(
        startMinute = 0,
        durationMinutes = 24 * 60,
        deltaMinutes = 15,
      )
    )
    assertNull(
      timelineAccessibilityMoveTarget(
        startMinute = 9 * 60,
        durationMinutes = 60,
        deltaMinutes = 30,
      )
    )
  }

  @Test
  fun `preview revision changes for metadata as well as time edits`() {
    val event =
      BriefingEvent(
        id = "revision",
        title = "Review, startTime=tricky",
        startTime = 1_800_000_000_000L,
        endTime = 1_800_003_600_000L,
        source = EventSource.MANUAL,
        description = null,
        isDeadline = false,
        isUrgent = false,
      )

    assertNotEquals(
      timelineEventRevision(event),
      timelineEventRevision(event.copy(description = "newer edit", userEdited = true)),
    )
    assertNotEquals(
      timelineEventRevision(event),
      timelineEventRevision(event.copy(startTime = event.startTime + 60_000L)),
    )
  }
}
