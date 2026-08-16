package com.example.ui.viewmodel

import com.example.core.ScheduleAnalysis
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDraftEditsTest {

  private val newYork: TimeZone = TimeZone.getTimeZone("America/New_York")

  private fun instant(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    zone: TimeZone = newYork,
  ): Long =
    Calendar.getInstance(zone)
      .apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
      }
      .timeInMillis

  private fun draft(startMs: Long, endMs: Long, isAllDay: Boolean = false) =
    EventDraft(title = "Review", startMs = startMs, endMs = endMs, isAllDay = isAllDay)

  @Test
  fun `turning all-day off returns the times it replaced`() {
    val timed = draft(instant(2026, 3, 10, 14, 30), instant(2026, 3, 10, 15, 15))

    val allDay = EventDraftEdits.asAllDay(timed, newYork)
    assertTrue(allDay.isAllDay)
    assertEquals(instant(2026, 3, 10), allDay.startMs)
    assertEquals(instant(2026, 3, 11), allDay.endMs)

    val back = EventDraftEdits.asTimed(allDay, timed.startMs, timed.endMs, newYork)
    assertFalse(back.isAllDay)
    assertEquals(timed.startMs, back.startMs)
    assertEquals(timed.endMs, back.endMs)
  }

  @Test
  fun `restoring times follows the day the draft moved to`() {
    val timed = draft(instant(2026, 3, 10, 14, 30), instant(2026, 3, 10, 15, 15))
    val movedAllDay = EventDraftEdits.asAllDay(timed, newYork).let {
      it.copy(startMs = instant(2026, 3, 12), endMs = instant(2026, 3, 13))
    }

    val back = EventDraftEdits.asTimed(movedAllDay, timed.startMs, timed.endMs, newYork)

    // The clock time and length come back; the new date stays.
    assertEquals(instant(2026, 3, 12, 14, 30), back.startMs)
    assertEquals(45 * 60_000L, back.endMs - back.startMs)
  }

  @Test
  fun `with nothing to restore all-day falls back to a working morning`() {
    val allDay = draft(instant(2026, 3, 10), instant(2026, 3, 11), isAllDay = true)

    val back = EventDraftEdits.asTimed(allDay, restoreStartMs = null, restoreEndMs = null, timeZone = newYork)

    assertEquals(instant(2026, 3, 10, 9, 0), back.startMs)
    assertEquals(instant(2026, 3, 10, 10, 0), back.endMs)
    assertFalse(back.isAllDay)
  }

  @Test
  fun `moving to another day keeps the clock time and the length`() {
    val timed = draft(instant(2026, 3, 10, 14, 30), instant(2026, 3, 10, 15, 15))
    // The date picker reports UTC midnight for the chosen calendar date.
    val pickedUtc = instant(2026, 3, 12, zone = TimeZone.getTimeZone("UTC"))

    val moved = EventDraftEdits.movedToDay(timed, pickedUtc, newYork)

    assertEquals(instant(2026, 3, 12, 14, 30), moved.startMs)
    assertEquals(45 * 60_000L, moved.endMs - moved.startMs)
  }

  @Test
  fun `moving an all-day event lands on one whole local day`() {
    val allDay = draft(instant(2026, 3, 10), instant(2026, 3, 11), isAllDay = true)
    val pickedUtc = instant(2026, 3, 12, zone = TimeZone.getTimeZone("UTC"))

    val moved = EventDraftEdits.movedToDay(allDay, pickedUtc, newYork)

    assertEquals(instant(2026, 3, 12), moved.startMs)
    assertEquals(instant(2026, 3, 13), moved.endMs)
  }

  @Test
  fun `moving the start drags the end along`() {
    val timed = draft(instant(2026, 3, 10, 14, 30), instant(2026, 3, 10, 15, 15))

    val moved = EventDraftEdits.withStart(timed, 9, 0, newYork)

    assertEquals(instant(2026, 3, 10, 9, 0), moved.startMs)
    assertEquals(45 * 60_000L, moved.endMs - moved.startMs)
  }

  @Test
  fun `an end before the start runs past midnight instead of going backwards`() {
    val timed = draft(instant(2026, 3, 10, 22, 0), instant(2026, 3, 10, 23, 0))

    val overnight = EventDraftEdits.withEnd(timed, 1, 30, newYork)

    assertEquals(instant(2026, 3, 11, 1, 30), overnight.endMs)
    assertTrue(overnight.endMs > overnight.startMs)
  }

  @Test
  fun `an all-day event spans one day even when that day loses an hour`() {
    // 8 March 2026 is a spring-forward day in New York: 23 hours long.
    val timed = draft(instant(2026, 3, 8, 14, 30), instant(2026, 3, 8, 15, 15))

    val allDay = EventDraftEdits.asAllDay(timed, newYork)

    assertEquals(ScheduleAnalysis.startOfDay(timed.startMs, newYork), allDay.startMs)
    assertEquals(instant(2026, 3, 9), allDay.endMs)
    assertEquals(23 * 60 * 60_000L, allDay.endMs - allDay.startMs)
  }
}
