package com.example.core

import com.example.data.model.BriefingEvent
import java.util.Calendar
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPulseTest {

  private val utc = TimeZone.of("UTC")

  private val dayStart =
    Calendar.getInstance(java.util.TimeZone.getTimeZone(utc.id))
      .apply {
        clear()
        set(2026, 2, 5, 0, 0, 0)
      }
      .timeInMillis

  private fun at(hour: Int, minute: Int = 0) = dayStart + (hour * 60L + minute) * 60_000L

  private fun event(id: String, from: Long, to: Long, allDay: Boolean = false) =
    BriefingEvent(
      id = id,
      title = id,
      startTime = from,
      endTime = to,
      source = "Manual",
      description = null,
      isDeadline = false,
      isUrgent = false,
      isAllDay = allDay,
    )

  private val day =
    listOf(
      event("standup", at(9), at(9, 30)),
      event("review", at(11), at(12)),
      event("ship", at(16), at(17)),
    )

  @Test
  fun `mid-event reports what you are in and how long is left`() {
    val pulse = DayPulse.of(day, at(11, 20), dayStart, utc)

    assertEquals("review", pulse.current?.id)
    assertEquals(40, pulse.minutesLeft)
    assertEquals("ship", pulse.next?.id)
    assertTrue(pulse.isLive)
  }

  @Test
  fun `between events reports the gap as free time`() {
    val pulse = DayPulse.of(day, at(13), dayStart, utc)

    assertNull(pulse.current)
    assertEquals("ship", pulse.next?.id)
    assertEquals(180, pulse.minutesToNext)
    assertEquals(180, pulse.freeMinutes)
  }

  @Test
  fun `time inside an event is not free time`() {
    val pulse = DayPulse.of(day, at(11, 20), dayStart, utc)

    assertEquals(0, pulse.freeMinutes)
  }

  @Test
  fun `finished counts only what has actually ended`() {
    val pulse = DayPulse.of(day, at(11, 20), dayStart, utc)

    assertEquals(1, pulse.finished)
    assertEquals(3, pulse.total)
    assertEquals(listOf("review", "ship"), pulse.upcoming.map { it.id })
  }

  @Test
  fun `progress measures booked minutes behind you, not events`() {
    // 150 minutes booked; by 11:30 the 30-minute standup and 30 of the review are done.
    val pulse = DayPulse.of(day, at(11, 30), dayStart, utc)

    assertEquals(150, pulse.bookedMinutes)
    assertEquals(60f / 150f, pulse.progress, 0.001f)
  }

  @Test
  fun `after the last event the day reads as done`() {
    val pulse = DayPulse.of(day, at(18), dayStart, utc)

    assertTrue(pulse.isDone)
    assertNull(pulse.next)
    assertEquals(3, pulse.finished)
    assertEquals(1f, pulse.progress, 0.001f)
  }

  @Test
  fun `an empty day is clear rather than done`() {
    val pulse = DayPulse.of(emptyList(), at(12), dayStart, utc)

    assertTrue(pulse.isClear)
    assertFalse(pulse.isDone)
    assertEquals(0f, pulse.progress, 0.001f)
  }

  @Test
  fun `a day you are not in is described from its start, with nothing running`() {
    val tomorrow = dayStart + ScheduleAnalysis.DAY_MS
    val pulse = DayPulse.of(day, tomorrow + 3_600_000L, dayStart, utc)

    assertFalse(pulse.isLive)
    assertNull(pulse.current)
    // Read from midnight, the whole day is still ahead.
    assertEquals("standup", pulse.next?.id)
    assertEquals(0, pulse.finished)
    assertEquals(3, pulse.upcoming.size)
  }

  @Test
  fun `all-day entries stay out of the countdown`() {
    val events = day + event("holiday", dayStart, dayStart + ScheduleAnalysis.DAY_MS, allDay = true)
    val pulse = DayPulse.of(events, at(13), dayStart, utc)

    assertEquals(listOf("holiday"), pulse.allDay.map { it.id })
    assertEquals(3, pulse.total)
    assertEquals(150, pulse.bookedMinutes)
  }

  @Test
  fun `an event running past midnight only counts the part inside the day`() {
    val late = listOf(event("late", at(23), at(23) + 3 * 3_600_000L))
    val pulse = DayPulse.of(late, at(22), dayStart, utc)

    assertEquals(60, pulse.bookedMinutes)
  }

  @Test
  fun `remaining time rounds up so the last minute is never reported as zero`() {
    val pulse = DayPulse.of(day, at(11, 59) + 30_000L, dayStart, utc)

    assertEquals(1, pulse.minutesLeft)
  }

  @Test
  fun `durations read as hours and minutes`() {
    assertEquals("45m", DayPulse.humanDuration(45))
    assertEquals("1h", DayPulse.humanDuration(60))
    assertEquals("2h 10m", DayPulse.humanDuration(130))
  }

  @Test
  fun `spring-forward pulse stops at the next local midnight`() {
    val zone = TimeZone.of("America/New_York")
    val start = localAt(zone, 2026, Calendar.MARCH, 8, 0, 0)
    val late =
      event(
        "late",
        localAt(zone, 2026, Calendar.MARCH, 8, 23, 30),
        localAt(zone, 2026, Calendar.MARCH, 9, 0, 30),
      )
    val tomorrow =
      event(
        "tomorrow",
        localAt(zone, 2026, Calendar.MARCH, 9, 0, 15),
        localAt(zone, 2026, Calendar.MARCH, 9, 1, 0),
      )

    val pulse =
      DayPulse.of(
        listOf(late, tomorrow),
        localAt(zone, 2026, Calendar.MARCH, 8, 22, 0),
        start,
        zone,
      )

    assertTrue(pulse.isLive)
    assertEquals(listOf("late"), pulse.upcoming.map { it.id })
    assertEquals(1, pulse.total)
    assertEquals(30, pulse.bookedMinutes)
  }

  @Test
  fun `fall-back pulse includes the final local hour of the 25 hour day`() {
    val zone = TimeZone.of("America/New_York")
    val start = localAt(zone, 2026, Calendar.NOVEMBER, 1, 0, 0)
    val late =
      event(
        "late",
        localAt(zone, 2026, Calendar.NOVEMBER, 1, 23, 15),
        localAt(zone, 2026, Calendar.NOVEMBER, 1, 23, 45),
      )

    val pulse =
      DayPulse.of(
        listOf(late),
        localAt(zone, 2026, Calendar.NOVEMBER, 1, 23, 30),
        start,
        zone,
      )

    assertTrue(pulse.isLive)
    assertEquals("late", pulse.current?.id)
    assertEquals(30, pulse.bookedMinutes)
  }

  private fun localAt(
    zone: TimeZone,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
  ): Long =
    Calendar.getInstance(java.util.TimeZone.getTimeZone(zone.id)).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis
}
