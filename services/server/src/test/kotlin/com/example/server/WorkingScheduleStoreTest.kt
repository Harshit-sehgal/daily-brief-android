package com.example.server

import com.example.contract.WorkScheduleWire
import com.example.contract.WorkScheduleWindowWire
import com.example.core.WorkingCalendar
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingScheduleStoreTest {
  @Test
  fun `converts workspace wall time in the saved timezone`() {
    val schedule = schedule(
      zone = "Asia/Kolkata",
      windows = listOf(window(day = 2, start = 9 * 60, end = 17 * 60)),
    )
    val spec = WorkingScheduleStore.specFor(schedule)
    val localStart = ZonedDateTime.of(LocalDate.of(2026, 1, 5), LocalTime.MIDNIGHT, ZoneId.of("Asia/Kolkata"))
      .toInstant().toEpochMilli()
    val intervals = WorkingCalendar.workingIntervals(spec, localStart, localStart + 24 * HOUR)
    val expectedStart = ZonedDateTime.of(LocalDate.of(2026, 1, 5), LocalTime.of(9, 0), ZoneId.of("Asia/Kolkata"))
      .toInstant().toEpochMilli()

    assertEquals(expectedStart, intervals.single().startAt)
    assertEquals(8 * 60, intervals.single().durationMinutes)
  }

  @Test
  fun `preserves the DST spring-forward wall clock rule`() {
    val schedule = schedule(
      zone = "America/New_York",
      windows = listOf(window(day = 1, start = 60, end = 180)),
    )
    val spec = WorkingScheduleStore.specFor(schedule)
    val rangeStart = Instant.parse("2026-03-08T00:00:00Z").toEpochMilli()
    val intervals = WorkingCalendar.workingIntervals(spec, rangeStart, rangeStart + 24 * HOUR)

    assertEquals(60, intervals.single().durationMinutes)
  }

  @Test
  fun `rejects overlapping weekly windows at the API boundary`() {
    try {
      WorkingScheduleStore.specFor(
        schedule(
          zone = "UTC",
          windows = listOf(
            window(day = 2, start = 9 * 60, end = 12 * 60),
            window(day = 2, start = 11 * 60, end = 13 * 60),
          ),
        ),
      )
      throw AssertionError("overlapping windows must be rejected")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message.orEmpty().contains("overlap"))
    }
  }

  private fun schedule(zone: String, windows: List<WorkScheduleWindowWire>) =
    WorkScheduleWire(
      id = "schedule-1",
      name = "Default working week",
      timeZoneId = zone,
      isDefault = true,
      rank = 0,
      windows = windows,
    )

  private fun window(day: Int, start: Int, end: Int) =
    WorkScheduleWindowWire(
      id = "window-$day-$start",
      dayOfWeek = day,
      startMinute = start,
      endMinute = end,
      rank = 0,
    )

  private companion object {
    const val HOUR = 3_600_000L
  }
}
