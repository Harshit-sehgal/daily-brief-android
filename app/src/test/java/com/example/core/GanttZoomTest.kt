package com.example.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GanttZoomTest {
  @Test
  fun `a pinch lands only on ranges the buttons also offer`() {
    // Spreading fingers shows less time; pinching them shows more.
    assertEquals(7, GanttZoom.rangeForScale(30, 1.6f))
    assertEquals(30, GanttZoom.rangeForScale(90, 1.6f))
    assertEquals(90, GanttZoom.rangeForScale(30, 0.5f))
    assertEquals(30, GanttZoom.rangeForScale(7, 0.5f))

    // The ends hold rather than wrapping around.
    assertEquals(7, GanttZoom.rangeForScale(7, 2.5f))
    assertEquals(90, GanttZoom.rangeForScale(90, 0.2f))

    // A drift too small to be a deliberate pinch changes nothing.
    listOf(0.9f, 1.0f, 1.2f).forEach { scale ->
      assertEquals("scale=$scale", 30, GanttZoom.rangeForScale(30, scale))
    }
    // Nonsense input cannot invent a range.
    assertEquals(30, GanttZoom.rangeForScale(30, Float.NaN))
    assertEquals(30, GanttZoom.rangeForScale(30, -1f))
    assertTrue(GanttZoom.rangeForScale(45, 1.6f) in GanttZoom.Ranges)
  }

  @Test
  fun `zooming keeps the moment under the fingers on screen`() {
    val start = ScheduleAnalysis.startOfDay(1_760_000_000_000L)
    val day = 24 * 60 * 60 * 1000L

    // Pinching at the middle of a 30-day range down to 7 days keeps the middle day in view.
    val next = GanttZoom.focusPreservingStart(start, 30, 0.5f, 7)
    val focusedDay = ScheduleAnalysis.startOfDay(start + 15 * day)
    assertTrue("focus should stay inside the new range", focusedDay >= next)
    assertTrue(focusedDay < next + 7 * day)

    // Zooming at the very left edge keeps the left edge.
    assertEquals(start, GanttZoom.focusPreservingStart(start, 30, 0f, 7))

    // Zooming at the right edge keeps the right edge inside the new range.
    val atEnd = GanttZoom.focusPreservingStart(start, 30, 1f, 7)
    assertEquals(ScheduleAnalysis.startOfDay(start + 30 * day - 7 * day), atEnd)

    // Every result is a day boundary, because the map is drawn in days.
    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { fraction ->
      val result = GanttZoom.focusPreservingStart(start, 90, fraction, 30)
      assertEquals(result, ScheduleAnalysis.startOfDay(result))
    }
  }

  @Test
  fun `the overview always contains the visible range and refuses an empty plan`() {
    val day = 24 * 60 * 60 * 1000L
    val planStart = 1_000 * day
    val planEnd = planStart + 60 * day

    val inside =
      requireNotNull(
        GanttZoom.overview(planStart, planEnd, planStart + 10 * day, planStart + 17 * day)
      )
    assertEquals(planStart, inside.startMs)
    assertEquals(planEnd, inside.endMs)
    assertTrue(inside.viewportStart > 0.0 && inside.viewportEnd < 1.0)
    assertTrue(inside.viewportStart < inside.viewportEnd)

    // Looking outside the plan widens the overview rather than pushing the marker off it.
    val outside =
      requireNotNull(
        GanttZoom.overview(planStart, planEnd, planStart - 30 * day, planStart - 23 * day)
      )
    assertEquals(planStart - 30 * day, outside.startMs)
    assertEquals(0.0, outside.viewportStart, 0.0001)
    assertTrue(outside.planStart > 0.0)

    // Nothing scheduled means no overview at all.
    assertNull(GanttZoom.overview(null, null, planStart, planEnd))
    assertNull(GanttZoom.overview(planStart, planStart, planStart, planEnd))
  }

  @Test
  fun `tapping the overview centres the range on what was tapped`() {
    val day = 24 * 60 * 60 * 1000L
    val planStart = ScheduleAnalysis.startOfDay(2_000 * day)
    val overview =
      requireNotNull(
        GanttZoom.overview(planStart, planStart + 100 * day, planStart, planStart + 7 * day)
      )

    val middle = GanttZoom.dayForOverviewTap(overview, 0.5f, visibleDays = 7)
    // Half of 100 days, minus half a 7-day window.
    assertEquals(ScheduleAnalysis.startOfDay(planStart + 50 * day - 3 * day - day / 2), middle)
    assertEquals(middle, ScheduleAnalysis.startOfDay(middle))

    val clamped = GanttZoom.dayForOverviewTap(overview, 2f, visibleDays = 30)
    assertTrue("a tap past the end still resolves to a real day", clamped > planStart)
  }
}
