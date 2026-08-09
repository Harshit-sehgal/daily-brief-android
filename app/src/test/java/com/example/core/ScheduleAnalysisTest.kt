package com.example.core

import com.example.data.model.BriefingEvent
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleAnalysisTest {

  private val utc = TimeZone.getTimeZone("UTC")

  private fun instant(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    zone: TimeZone = utc,
  ): Long =
    Calendar.getInstance(zone)
      .apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
      }
      .timeInMillis

  private fun event(
    id: String,
    startMs: Long,
    endMs: Long,
    urgent: Boolean = false,
    deadline: Boolean = false,
    allDay: Boolean = false,
    source: String = "Manual",
    location: String? = null,
  ) =
    BriefingEvent(
      id = id,
      title = id,
      startTime = startMs,
      endTime = endMs,
      source = source,
      description = null,
      isDeadline = deadline,
      isUrgent = urgent,
      isAllDay = allDay,
      location = location,
    )

  @Test
  fun `overlapping events are reported once, in start order`() {
    val a = event("a", instant(2026, 3, 5, 9), instant(2026, 3, 5, 10))
    val b = event("b", instant(2026, 3, 5, 9, 30), instant(2026, 3, 5, 11))

    val conflicts = ScheduleAnalysis.findConflicts(listOf(b, a))

    assertEquals(1, conflicts.size)
    assertEquals("a", conflicts.first().first.id)
    assertEquals("b", conflicts.first().second.id)
    assertEquals(30L * 60 * 1000, conflicts.first().overlapMs)
  }

  @Test
  fun `back to back events do not clash`() {
    val a = event("a", instant(2026, 3, 5, 9), instant(2026, 3, 5, 10))
    val b = event("b", instant(2026, 3, 5, 10), instant(2026, 3, 5, 11))

    assertTrue(ScheduleAnalysis.findConflicts(listOf(a, b)).isEmpty())
  }

  @Test
  fun `three overlapping events produce every pair`() {
    val start = instant(2026, 3, 5, 9)
    val events =
      listOf(
        event("a", start, start + 3 * HOUR),
        event("b", start + 30 * MINUTE, start + 3 * HOUR),
        event("c", start + HOUR, start + 2 * HOUR),
      )

    assertEquals(3, ScheduleAnalysis.findConflicts(events).size)
  }

  @Test
  fun `all-day entries never count as clashes`() {
    val dayStart = instant(2026, 3, 5)
    val allDay = event("all-day", dayStart, dayStart + 24 * HOUR, allDay = true)
    val meeting = event("meeting", dayStart + 9 * HOUR, dayStart + 10 * HOUR)

    assertTrue(ScheduleAnalysis.isAllDay(allDay))
    assertTrue(ScheduleAnalysis.findConflicts(listOf(allDay, meeting)).isEmpty())
  }

  @Test
  fun `zero length markers cannot clash`() {
    val at = instant(2026, 3, 5, 9)
    val marker = event("marker", at, at)
    val meeting = event("meeting", at, at + HOUR)

    assertTrue(ScheduleAnalysis.findConflicts(listOf(marker, meeting)).isEmpty())
  }

  @Test
  fun `booked time excludes all-day entries`() {
    val dayStart = instant(2026, 3, 5)
    val stats =
      ScheduleAnalysis.statsFor(
        listOf(
          event("all-day", dayStart, dayStart + 24 * HOUR, allDay = true),
          event("a", dayStart + 9 * HOUR, dayStart + 10 * HOUR),
          event("b", dayStart + 11 * HOUR, dayStart + 11 * HOUR + 30 * MINUTE, urgent = true),
        )
      )

    assertEquals(3, stats.total)
    assertEquals(90, stats.bookedMinutes)
    assertEquals(1, stats.urgent)
    assertEquals(0, stats.conflicts)
  }

  @Test
  fun `booked time can be clipped to the visible day`() {
    val dayStart = instant(2026, 3, 5)
    val stats =
      ScheduleAnalysis.statsFor(
        events =
          listOf(
            event("overnight", dayStart - HOUR, dayStart + HOUR),
            event("evening", dayStart + 23 * HOUR, dayStart + 26 * HOUR),
          ),
        startInclusive = dayStart,
        endExclusive = dayStart + 24 * HOUR,
      )

    assertEquals(120, stats.bookedMinutes)
  }

  @Test
  fun `day bounds cover exactly one day`() {
    val noon = instant(2026, 3, 5, 12)
    val bounds = ScheduleAnalysis.dayBounds(noon, utc)

    assertEquals(instant(2026, 3, 5), bounds.first)
    assertEquals(instant(2026, 3, 6) - 1, bounds.last)
  }

  @Test
  fun `day offsets survive a daylight saving transition`() {
    val london = TimeZone.getTimeZone("Europe/London")
    // 29 March 2026 is the UK clock change; naive +24h arithmetic skips a day.
    val saturday = instant(2026, 3, 28, 12, zone = london)

    val sunday = ScheduleAnalysis.startOfDayOffset(saturday, 1, london)
    val monday = ScheduleAnalysis.startOfDayOffset(saturday, 2, london)

    assertEquals(29, dayOfMonth(sunday, london))
    assertEquals(30, dayOfMonth(monday, london))
  }

  @Test
  fun `picker dates round trip through UTC`() {
    val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
    val localDay = ScheduleAnalysis.startOfDay(instant(2026, 3, 5, 23, 30, kolkata), kolkata)

    val asUtc = ScheduleAnalysis.utcMillisFromLocalDay(localDay, kolkata)
    val backAgain = ScheduleAnalysis.localDayFromUtcMillis(asUtc, kolkata)

    assertEquals(localDay, backAgain)
    assertEquals(5, dayOfMonth(asUtc, utc))
  }

  @Test
  fun `setting a time of day keeps the date`() {
    val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
    val day = ScheduleAnalysis.startOfDay(instant(2026, 3, 5, 2, 0, kolkata), kolkata)

    val at1745 = ScheduleAnalysis.withTimeOfDay(day, 17, 45, kolkata)

    assertEquals(5, dayOfMonth(at1745, kolkata))
    assertEquals(17, ScheduleAnalysis.hourOf(at1745, kolkata))
    assertEquals(45, ScheduleAnalysis.minuteOf(at1745, kolkata))
  }

  @Test
  fun `signature ignores ordering but reacts to a moved event`() {
    val a = event("a", instant(2026, 3, 5, 9), instant(2026, 3, 5, 10))
    val b = event("b", instant(2026, 3, 5, 11), instant(2026, 3, 5, 12))

    assertEquals(
      ScheduleAnalysis.signature(listOf(a, b)),
      ScheduleAnalysis.signature(listOf(b, a)),
    )
    assertNotEquals(
      ScheduleAnalysis.signature(listOf(a, b)),
      ScheduleAnalysis.signature(listOf(a.copy(startTime = a.startTime + HOUR), b)),
    )
    assertEquals(64, ScheduleAnalysis.signature(listOf(a, b)).length)
  }

  @Test
  fun `signature covers every field sent to the brief model`() {
    val base = event("a", instant(2026, 3, 5, 9), instant(2026, 3, 5, 10))
    val signature = ScheduleAnalysis.signature(listOf(base))

    assertNotEquals(signature, ScheduleAnalysis.signature(listOf(base.copy(source = "Notion"))))
    assertNotEquals(signature, ScheduleAnalysis.signature(listOf(base.copy(location = "Studio"))))
    assertNotEquals(signature, ScheduleAnalysis.signature(listOf(base.copy(isAllDay = true))))
    // Blank locations are omitted from the prompt and intentionally canonicalized.
    assertEquals(signature, ScheduleAnalysis.signature(listOf(base.copy(location = ""))))
  }

  @Test
  fun `long timed events are not inferred to be all day`() {
    val start = instant(2026, 3, 5)
    val longTimed = event("travel", start, start + 21 * HOUR)

    assertFalse(ScheduleAnalysis.isAllDay(longTimed))
  }

  @Test
  fun `UTC all-day dates are rebuilt at local DST-aware midnights`() {
    val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")
    val utcStart = instant(2026, 3, 8)
    val utcEnd = instant(2026, 3, 9)

    val (localStart, localEnd) =
      ScheduleAnalysis.normalizeAllDayUtcRange(utcStart, utcEnd, losAngeles)

    assertEquals(8, dayOfMonth(localStart, losAngeles))
    assertEquals(9, dayOfMonth(localEnd, losAngeles))
    assertEquals(0, ScheduleAnalysis.hourOf(localStart, losAngeles))
    assertEquals(23 * HOUR, localEnd - localStart)
  }

  @Test
  fun `grouping buckets by local day in ascending order`() {
    val first = event("first", instant(2026, 3, 6, 9), instant(2026, 3, 6, 10))
    val second = event("second", instant(2026, 3, 5, 15), instant(2026, 3, 5, 16))
    val third = event("third", instant(2026, 3, 5, 9), instant(2026, 3, 5, 10))

    val grouped = ScheduleAnalysis.groupByDay(listOf(first, second, third), utc)

    assertEquals(2, grouped.size)
    assertEquals(instant(2026, 3, 5), grouped[0].first)
    assertEquals(listOf("third", "second"), grouped[0].second.map { it.id })
    assertEquals(listOf("first"), grouped[1].second.map { it.id })
  }

  @Test
  fun `same day comparison respects the zone`() {
    val utcTime = instant(2026, 3, 5, 23)
    assertTrue(ScheduleAnalysis.isSameDay(utcTime, utcTime + 30 * MINUTE, utc))
    assertFalse(ScheduleAnalysis.isSameDay(utcTime, utcTime + 2 * HOUR, utc))
  }

  private fun dayOfMonth(timeMs: Long, zone: TimeZone): Int =
    Calendar.getInstance(zone).apply { timeInMillis = timeMs }.get(Calendar.DAY_OF_MONTH)

  private companion object {
    const val MINUTE = 60L * 1000
    const val HOUR = 60 * MINUTE
  }
}
