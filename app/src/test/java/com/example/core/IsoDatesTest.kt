package com.example.core

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class IsoDatesTest {

  private val utc = TimeZone.getTimeZone("UTC")
  private val kolkata = TimeZone.getTimeZone("Asia/Kolkata")

  private fun utcInstant(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    second: Int = 0,
  ): Long =
    Calendar.getInstance(utc)
      .apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
      }
      .timeInMillis

  @Test
  fun `a bare date is the start of that day in the given zone`() {
    val expected =
      Calendar.getInstance(kolkata)
        .apply {
          clear()
          set(2026, 2, 5, 0, 0, 0)
        }
        .timeInMillis

    assertEquals(expected, IsoDates.parse("2026-03-05", kolkata))
  }

  @Test
  fun `a trailing Z is read as UTC`() {
    assertEquals(utcInstant(2026, 3, 5, 10, 30), IsoDates.parse("2026-03-05T10:30:00Z", kolkata))
    assertEquals(
      utcInstant(2026, 3, 5, 10, 30),
      IsoDates.parse("2026-03-05T10:30:00.000Z", kolkata),
    )
  }

  @Test
  fun `an explicit offset wins over the device zone`() {
    // 10:30+05:30 is 05:00 UTC, whatever zone the phone is in.
    assertEquals(
      utcInstant(2026, 3, 5, 5, 0),
      IsoDates.parse("2026-03-05T10:30:00.000+05:30", utc),
    )
    assertEquals(utcInstant(2026, 3, 5, 5, 0), IsoDates.parse("2026-03-05T10:30:00+0530", utc))
  }

  @Test
  fun `a timestamp without an offset is local wall-clock time`() {
    assertEquals(utcInstant(2026, 3, 5, 10, 30), IsoDates.parse("2026-03-05T10:30:00", utc))
  }

  @Test
  fun `unparseable input returns zero rather than a wrong date`() {
    assertEquals(0L, IsoDates.parse("not a date", utc))
    assertEquals(0L, IsoDates.parse("", utc))
    assertEquals(0L, IsoDates.parse("2026-13-45", utc))
  }

  @Test
  fun `surrounding whitespace is ignored`() {
    assertEquals(utcInstant(2026, 3, 5, 10, 30), IsoDates.parse("  2026-03-05T10:30:00Z  ", utc))
  }
}
