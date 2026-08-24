package com.example.server

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioWeekTest {
  private fun utc(y: Int, month: Int, d: Int, hour: Int = 12, minute: Int = 0): Long =
    ZonedDateTime.of(y, month, d, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

  @Test
  fun `the week window opens on Monday midnight UTC for every day of the week`() {
    // 2026-08-17 is a Monday; walk the whole week and pin the same Monday start.
    for (day in 17..23) {
      val now = utc(2026, 8, day)
      assertEquals("day $day must map to Monday midnight", utc(2026, 8, 17, 0), PortfolioWeek.startOf(now))
    }
  }

  @Test
  fun `a mid-week instant lands in its own week, not the next`() {
    assertEquals(utc(2026, 8, 17, 0), PortfolioWeek.startOf(utc(2026, 8, 21, 23)))
    assertEquals(utc(2026, 8, 17, 0), PortfolioWeek.startOf(utc(2026, 8, 23, 14, 30)))
  }

  @Test
  fun `the boundary is exclusive at the far end`() {
    val start = PortfolioWeek.startOf(utc(2026, 8, 23))
    assertEquals(utc(2026, 8, 24, 0), start + 7 * 24 * 60 * 60 * 1000L)
  }
}
