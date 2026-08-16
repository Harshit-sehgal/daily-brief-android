package com.example.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingCalendarTest {
  @Test
  fun `weekly windows and one-day overrides use local wall clock time`() {
    val zone = "Asia/Kolkata"
    val monday = at(zone, 2026, Calendar.AUGUST, 10, 0, 0)
    val wednesday = at(zone, 2026, Calendar.AUGUST, 12, 23, 59)
    val spec =
      standardSpec(zone).copy(
        overrides =
          listOf(
            WorkingDateOverride("2026-08-11", emptyList()),
            WorkingDateOverride("2026-08-12", listOf(WorkingDayWindow(12 * 60, 15 * 60))),
          )
      )

    val intervals = WorkingCalendar.workingIntervals(spec, monday, wednesday)

    assertEquals(2, intervals.size)
    assertEquals("2026-08-10 09:00", display(zone, intervals[0].startAt))
    assertEquals("2026-08-10 17:00", display(zone, intervals[0].endAt))
    assertEquals("2026-08-12 12:00", display(zone, intervals[1].startAt))
    assertEquals("2026-08-12 15:00", display(zone, intervals[1].endAt))
  }

  @Test
  fun `busy commitments and buffers are subtracted without changing the source interval`() {
    val zone = "Asia/Kolkata"
    val day = at(zone, 2026, Calendar.AUGUST, 10, 0, 0)
    val nextDay = at(zone, 2026, Calendar.AUGUST, 11, 0, 0)
    val busy =
      WorkingInterval(
        at(zone, 2026, Calendar.AUGUST, 10, 11, 0),
        at(zone, 2026, Calendar.AUGUST, 10, 12, 0),
      )

    val free =
      WorkingCalendar.freeIntervals(
        standardSpec(zone).copy(bufferMinutes = 15),
        day,
        nextDay,
        listOf(busy),
      )

    assertEquals(2, free.size)
    assertEquals("2026-08-10 09:00", display(zone, free[0].startAt))
    assertEquals("2026-08-10 10:45", display(zone, free[0].endAt))
    assertEquals("2026-08-10 12:15", display(zone, free[1].startAt))
    assertEquals("2026-08-10 17:00", display(zone, free[1].endAt))
    assertEquals(390, WorkingCalendar.capacityMinutes(standardSpec(zone).copy(bufferMinutes = 15), day, nextDay, listOf(busy)))
  }

  @Test
  fun `proposal chunks work deterministically around fixed commitments`() {
    val zone = "Asia/Kolkata"
    val start = at(zone, 2026, Calendar.AUGUST, 10, 0, 0)
    val end = at(zone, 2026, Calendar.AUGUST, 11, 0, 0)
    val busy =
      listOf(
        WorkingInterval(
          at(zone, 2026, Calendar.AUGUST, 10, 10, 30),
          at(zone, 2026, Calendar.AUGUST, 10, 13, 0),
        )
      )

    val plan =
      WorkingCalendar.propose(
        standardSpec(zone).copy(minimumChunkMinutes = 30, maximumChunkMinutes = 90),
        start,
        end,
        effortMinutes = 180,
        busy = busy,
      )

    assertTrue(plan.fits)
    assertEquals(2, plan.blocks.size)
    assertEquals("2026-08-10 09:00", display(zone, plan.blocks[0].startAt))
    assertEquals("2026-08-10 10:30", display(zone, plan.blocks[0].endAt))
    assertEquals("2026-08-10 13:00", display(zone, plan.blocks[1].startAt))
    assertEquals("2026-08-10 14:30", display(zone, plan.blocks[1].endAt))
    assertTrue(plan.explanation.contains("2 working-time blocks"))
  }

  @Test
  fun `proposal explains partial fit before a deadline`() {
    val zone = "Asia/Kolkata"
    val start = at(zone, 2026, Calendar.AUGUST, 10, 0, 0)
    val deadline = at(zone, 2026, Calendar.AUGUST, 10, 10, 0)

    val plan =
      WorkingCalendar.propose(
        standardSpec(zone),
        start,
        at(zone, 2026, Calendar.AUGUST, 11, 0, 0),
        effortMinutes = 180,
        busy = emptyList(),
        dueAt = deadline,
      )

    assertFalse(plan.fits)
    assertEquals(60, plan.scheduledMinutes)
    assertEquals(120, plan.unscheduledMinutes)
    assertTrue(plan.explanation.contains("120 minutes remain"))
  }

  @Test
  fun `spring-forward windows retain local day semantics`() {
    val zone = "America/New_York"
    val start = at(zone, 2026, Calendar.MARCH, 8, 0, 0)
    val end = at(zone, 2026, Calendar.MARCH, 9, 0, 0)
    val spec =
      WorkingCalendarSpec(
        zoneId = zone,
        weeklyWindows =
          listOf(WorkingWeekWindow(Calendar.SUNDAY, 1 * 60, 4 * 60)),
      )

    val interval = WorkingCalendar.workingIntervals(spec, start, end).single()

    assertEquals("2026-03-08 01:00", display(zone, interval.startAt))
    assertEquals("2026-03-08 04:00", display(zone, interval.endAt))
    assertEquals(120, interval.durationMinutes)
  }

  @Test
  fun `fall-back ambiguous wall times use the later standard-time occurrence`() {
    val zone = "America/New_York"
    val start = at(zone, 2026, Calendar.NOVEMBER, 1, 0, 0)
    val end = at(zone, 2026, Calendar.NOVEMBER, 2, 0, 0)
    val spec =
      WorkingCalendarSpec(
        zoneId = zone,
        weeklyWindows =
          listOf(WorkingWeekWindow(Calendar.SUNDAY, 1 * 60 + 30, 2 * 60 + 30)),
      )

    val interval = WorkingCalendar.workingIntervals(spec, start, end).single()

    // 01:30 occurs at both 05:30Z (daylight time) and 06:30Z (standard time).
    // java.util.Calendar deterministically selects the later standard-time occurrence.
    assertEquals(at("UTC", 2026, Calendar.NOVEMBER, 1, 6, 30), interval.startAt)
    assertEquals(at("UTC", 2026, Calendar.NOVEMBER, 1, 7, 30), interval.endAt)
    assertEquals(60, interval.durationMinutes)
  }

  @Test
  fun `override dates require exact shape valid date and full parse consumption`() {
    val invalidDates =
      listOf(
        "2026-8-11",
        "2026-08-1",
        "2026-08-11suffix",
        "2026-08-11 ",
        " 2026-08-11",
        "2026-02-29",
      )

    invalidDates.forEach { invalidDate ->
      val result =
        runCatching {
          WorkingCalendar.validate(
            standardSpec("UTC").copy(
              overrides = listOf(WorkingDateOverride(invalidDate, emptyList()))
            )
          )
        }
      assertTrue("Expected exact-date validation to reject '$invalidDate'", result.isFailure)
    }

    WorkingCalendar.validate(
      standardSpec("UTC").copy(
        overrides = listOf(WorkingDateOverride("2026-08-11", emptyList()))
      )
    )
  }

  @Test
  fun `invalid time zones overlaps and chunk bounds fail closed`() {
    assertTrue(
      runCatching { WorkingCalendar.validate(standardSpec("Not/AZone")) }.isFailure
    )
    assertTrue(
      runCatching {
          WorkingCalendar.validate(
            standardSpec("UTC").copy(
              weeklyWindows =
                listOf(
                  WorkingWeekWindow(Calendar.MONDAY, 9 * 60, 12 * 60),
                  WorkingWeekWindow(Calendar.MONDAY, 11 * 60, 13 * 60),
                )
            )
          )
        }
        .isFailure
    )
    assertTrue(
      runCatching {
          WorkingCalendar.validate(
            standardSpec("UTC").copy(minimumChunkMinutes = 90, maximumChunkMinutes = 30)
          )
        }
        .isFailure
    )
  }

  private fun standardSpec(zone: String) =
    WorkingCalendarSpec(
      zoneId = zone,
      weeklyWindows =
        (Calendar.MONDAY..Calendar.FRIDAY).map { day ->
          WorkingWeekWindow(day, 9 * 60, 17 * 60)
        },
    )

  private fun at(
    zone: String,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
  ): Long =
    Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis

  private fun display(zone: String, value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
      timeZone = TimeZone.getTimeZone(zone)
    }.format(value)
}
