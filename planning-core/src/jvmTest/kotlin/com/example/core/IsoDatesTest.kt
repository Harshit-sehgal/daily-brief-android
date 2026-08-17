package com.example.core

import java.util.Calendar
import java.util.TimeZone
import kotlinx.datetime.TimeZone as CommonTimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The expectations are still built with `java.util.Calendar` on purpose.
 *
 * `IsoDates` no longer uses it — the parser is `kotlinx-datetime` now — so `Calendar` is an
 * independent oracle rather than the implementation restating itself. If the port had changed
 * any accepted shape or any resulting instant, these would fail.
 */
class IsoDatesTest {

  private val utc = TimeZone.getTimeZone("UTC")
  private val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
  private val utcZone = CommonTimeZone.UTC
  private val kolkataZone = CommonTimeZone.of("Asia/Kolkata")

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

    assertEquals(expected, IsoDates.parse("2026-03-05", kolkataZone))
  }

  @Test
  fun `a trailing Z is read as UTC`() {
    assertEquals(utcInstant(2026, 3, 5, 10, 30), IsoDates.parse("2026-03-05T10:30:00Z", kolkataZone))
    assertEquals(
      utcInstant(2026, 3, 5, 10, 30),
      IsoDates.parse("2026-03-05T10:30:00.000Z", kolkataZone),
    )
  }

  @Test
  fun `an explicit offset wins over the device zone`() {
    // 10:30+05:30 is 05:00 UTC, whatever zone the phone is in.
    assertEquals(
      utcInstant(2026, 3, 5, 5, 0),
      IsoDates.parse("2026-03-05T10:30:00.000+05:30", utcZone),
    )
    assertEquals(utcInstant(2026, 3, 5, 5, 0), IsoDates.parse("2026-03-05T10:30:00+0530", utcZone))
  }

  @Test
  fun `a timestamp without an offset is local wall-clock time`() {
    assertEquals(utcInstant(2026, 3, 5, 10, 30), IsoDates.parse("2026-03-05T10:30:00", utcZone))
  }

  @Test
  fun `unparseable input returns zero rather than a wrong date`() {
    assertEquals(0L, IsoDates.parse("not a date", utcZone))
    assertEquals(0L, IsoDates.parse("", utcZone))
    assertEquals(0L, IsoDates.parse("2026-13-45", utcZone))
  }

  @Test
  fun `surrounding whitespace is ignored`() {
    assertEquals(utcInstant(2026, 3, 5, 10, 30), IsoDates.parse("  2026-03-05T10:30:00Z  ", utcZone))
  }
}
