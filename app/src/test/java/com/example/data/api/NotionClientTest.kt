package com.example.data.api

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotionClientTest {

  @Test
  fun `date selection is independent of JSON property order`() {
    val due = NotionDateCandidate("Due", "2026-03-09")
    val date = NotionDateCandidate("Date", "2026-03-08")

    assertEquals(date, selectNotionDateCandidate(listOf(due, date)))
    assertEquals(date, selectNotionDateCandidate(listOf(date, due)))
  }

  @Test
  fun `unrecognized date properties use a stable lexical fallback`() {
    val zeta = NotionDateCandidate("Zeta", "2026-03-09")
    val alpha = NotionDateCandidate("Alpha", "2026-03-08")

    assertEquals(alpha, selectNotionDateCandidate(listOf(zeta, alpha)))
    assertNull(selectNotionDateCandidate(emptyList()))
  }

  @Test
  fun `date-only item becomes a DST-safe all-day window`() {
    val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")

    val window =
      parseNotionDateWindow(NotionDateCandidate("Date", "2026-03-08"), losAngeles)!!

    assertTrue(window.isAllDay)
    assertEquals(localMidnight(2026, 3, 8, losAngeles), window.startMs)
    assertEquals(localMidnight(2026, 3, 9, losAngeles), window.endExclusiveMs)
    assertEquals(23L * 60 * 60 * 1000, window.endExclusiveMs - window.startMs)
  }

  @Test
  fun `timed item without an end gets a one-hour window`() {
    val utc = TimeZone.getTimeZone("UTC")

    val window =
      parseNotionDateWindow(
        NotionDateCandidate("Date", "2026-03-08T09:30:00Z"),
        utc,
      )!!

    assertFalse(window.isAllDay)
    assertEquals(60L * 60 * 1000, window.endExclusiveMs - window.startMs)
  }

  @Test
  fun `invalid explicit date range is rejected instead of invented`() {
    val utc = TimeZone.getTimeZone("UTC")

    assertNull(
      parseNotionDateWindow(
        NotionDateCandidate(
          propertyName = "Date",
          start = "2026-03-09T10:00:00Z",
          end = "2026-03-09T09:00:00Z",
        ),
        utc,
      )
    )
  }

  private fun localMidnight(
    year: Int,
    month: Int,
    day: Int,
    zone: TimeZone,
  ): Long =
    Calendar.getInstance(zone)
      .apply {
        clear()
        set(year, month - 1, day, 0, 0, 0)
      }
      .timeInMillis
}
