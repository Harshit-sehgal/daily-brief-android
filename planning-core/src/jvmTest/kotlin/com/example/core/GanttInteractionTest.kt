package com.example.core

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GanttInteractionTest {
  @Test
  fun `move and resize buttons preserve exact state and fail safely at boundaries`() {
    val draft =
      GanttBlockDraft(
        itemId = "task",
        blockId = "block",
        startAt = 1_000_000_123L,
        endAt = 1_003_600_123L,
        locked = true,
      )

    val moved = requireNotNull(GanttBlockEditPolicy.moveByMinutes(draft, 15))
    assertEquals(draft.startAt + 900_000L, moved.startAt)
    assertEquals(draft.endAt + 900_000L, moved.endAt)
    assertEquals(draft.locked, moved.locked)

    val extended = requireNotNull(GanttBlockEditPolicy.resizeEndByMinutes(moved, 15))
    assertEquals(moved.endAt + 900_000L, extended.endAt)
    assertEquals(moved.startAt, extended.startAt)

    val startExtended = requireNotNull(GanttBlockEditPolicy.resizeStartByMinutes(moved, -15))
    assertEquals(moved.startAt - 900_000L, startExtended.startAt)
    assertEquals(moved.endAt, startExtended.endAt)

    val newStart = 2_000_000_789L
    val replaced = requireNotNull(GanttBlockEditPolicy.replaceStartPreservingDuration(draft, newStart))
    assertEquals(newStart, replaced.startAt)
    assertEquals(draft.endAt - draft.startAt, replaced.endAt - replaced.startAt)

    assertNull(
      GanttBlockEditPolicy.resizeEndByMinutes(
        draft.copy(endAt = draft.startAt + 15 * 60_000L),
        -15,
      )
    )
    assertNull(
      GanttBlockEditPolicy.moveByMinutes(
        draft.copy(startAt = Long.MAX_VALUE - 1, endAt = Long.MAX_VALUE),
        15,
      )
    )
  }

  @Test
  fun `partial working days and split shifts produce exact nonworking bands`() {
    val zone = TimeZone.getTimeZone("UTC")
    val start = at(zone, 2036, Calendar.FEBRUARY, 25, 0, 0)
    val end = at(zone, 2036, Calendar.FEBRUARY, 26, 0, 0)
    val spec =
      WorkingCalendarSpec(
        zoneId = zone.id,
        weeklyWindows =
          listOf(
            WorkingWeekWindow(Calendar.MONDAY, 9 * 60, 12 * 60),
            WorkingWeekWindow(Calendar.MONDAY, 13 * 60, 17 * 60),
          ),
      )

    assertEquals(
      listOf(
        WorkingInterval(start, start + 9 * HOUR_MS),
        WorkingInterval(start + 12 * HOUR_MS, start + 13 * HOUR_MS),
        WorkingInterval(start + 17 * HOUR_MS, end),
      ),
      GanttWorkingBands.nonWorkingIntervals(spec, start, end),
    )
  }

  @Test
  fun `closed date exception shades the complete local day`() {
    val zone = TimeZone.getTimeZone("UTC")
    val start = at(zone, 2036, Calendar.FEBRUARY, 25, 0, 0)
    val end = at(zone, 2036, Calendar.FEBRUARY, 26, 0, 0)
    val spec =
      WorkingCalendarSpec(
        zoneId = zone.id,
        weeklyWindows = listOf(WorkingWeekWindow(Calendar.MONDAY, 9 * 60, 17 * 60)),
        overrides = listOf(WorkingDateOverride("2036-02-25", emptyList())),
      )

    assertEquals(
      listOf(WorkingInterval(start, end)),
      GanttWorkingBands.nonWorkingIntervals(spec, start, end),
    )
  }

  private fun at(
    zone: TimeZone,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
  ): Long =
    Calendar.getInstance(zone).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis

  private companion object {
    const val MINUTE_MS = 60 * 1000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
  }
}