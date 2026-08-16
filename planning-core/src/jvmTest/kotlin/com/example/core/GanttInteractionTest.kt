package com.example.core

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GanttInteractionTest {
  @Test
  fun `move and resize buttons preserve exact state and fail safely at boundaries`() {
    val draft =
      GanttBlockDraft(
        itemId = "task",
        blockId = "block",
        startAt = 1_000_000_123L,
        endAt = 1_003_600_123L,
        locked = true,
      )

    val moved = requireNotNull(GanttBlockEditPolicy.moveByMinutes(draft, 15))
    assertEquals(draft.startAt + 900_000L, moved.startAt)
    assertEquals(draft.endAt + 900_000L, moved.endAt)
    assertEquals(draft.locked, moved.locked)

    val extended = requireNotNull(GanttBlockEditPolicy.resizeEndByMinutes(moved, 15))
    assertEquals(moved.endAt + 900_000L, extended.endAt)
    assertEquals(moved.startAt, extended.startAt)

    val startExtended = requireNotNull(GanttBlockEditPolicy.resizeStartByMinutes(moved, -15))
    assertEquals(moved.startAt - 900_000L, startExtended.startAt)
    assertEquals(moved.endAt, startExtended.endAt)

    val newStart = 2_000_000_789L
    val replaced = requireNotNull(GanttBlockEditPolicy.replaceStartPreservingDuration(draft, newStart))
    assertEquals(newStart, replaced.startAt)
    assertEquals(draft.endAt - draft.startAt, replaced.endAt - replaced.startAt)

    assertNull(
      GanttBlockEditPolicy.resizeEndByMinutes(
        draft.copy(endAt = draft.startAt + 15 * 60_000L),
        -15,
      )
    )
    assertNull(
      GanttBlockEditPolicy.moveByMinutes(
        draft.copy(startAt = Long.MAX_VALUE - 1, endAt = Long.MAX_VALUE),
        15,
      )
    )
  }

  @Test
  fun `direct manipulation snaps every target to fifteen minutes without persisting`() {
    val dayStart = 2_000_000_000L
    val dayEnd = dayStart + DAY_MS
    val origin =
      GanttBlockDraft(
        itemId = "task",
        blockId = "block",
        startAt = dayStart + 9 * HOUR_MS,
        endAt = dayStart + 10 * HOUR_MS,
      )

    // A 960 px day makes ten pixels exactly fifteen minutes.
    val moved =
      requireNotNull(
        GanttDirectManipulationPolicy.preview(
          origin,
          GanttDragTarget.BLOCK,
          deltaPixels = 10f,
          canvasWidthPixels = 960f,
          rangeStartInclusive = dayStart,
          rangeEndExclusive = dayEnd,
        )
      )
    assertEquals(origin.startAt + 15 * MINUTE_MS, moved.startAt)
    assertEquals(origin.endAt + 15 * MINUTE_MS, moved.endAt)

    val resizedStart =
      requireNotNull(
        GanttDirectManipulationPolicy.preview(
          origin,
          GanttDragTarget.START,
          deltaPixels = -10f,
          canvasWidthPixels = 960f,
          rangeStartInclusive = dayStart,
          rangeEndExclusive = dayEnd,
        )
      )
    assertEquals(origin.startAt - 15 * MINUTE_MS, resizedStart.startAt)
    assertEquals(origin.endAt, resizedStart.endAt)

    val resizedEnd =
      requireNotNull(
        GanttDirectManipulationPolicy.preview(
          origin,
          GanttDragTarget.END,
          deltaPixels = 10f,
          canvasWidthPixels = 960f,
          rangeStartInclusive = dayStart,
          rangeEndExclusive = dayEnd,
        )
      )
    assertEquals(origin.startAt, resizedEnd.startAt)
    assertEquals(origin.endAt + 15 * MINUTE_MS, resizedEnd.endAt)

    assertEquals(
      0,
      GanttDirectManipulationPolicy.snappedDeltaMinutes(
        deltaPixels = 4.9f,
        canvasWidthPixels = 960f,
        rangeStartInclusive = dayStart,
        rangeEndExclusive = dayEnd,
      ),
    )
  }

  @Test
  fun `direct manipulation rejects overflow invalid geometry and visible range escape`() {
    val dayStart = 2_000_000_000L
    val dayEnd = dayStart + DAY_MS
    val origin =
      GanttBlockDraft(
        itemId = "task",
        startAt = dayStart,
        endAt = dayStart + HOUR_MS,
      )

    assertNull(
      GanttDirectManipulationPolicy.preview(
        origin,
        GanttDragTarget.BLOCK,
        deltaPixels = -10f,
        canvasWidthPixels = 960f,
        rangeStartInclusive = dayStart,
        rangeEndExclusive = dayEnd,
      )
    )
    assertNull(
      GanttDirectManipulationPolicy.adjustByMinutes(
        origin,
        GanttDragTarget.BLOCK,
        deltaMinutes = 1,
        rangeStartInclusive = dayStart,
        rangeEndExclusive = dayEnd,
      )
    )
    assertNull(
      GanttDirectManipulationPolicy.preview(
        origin,
        GanttDragTarget.START,
        deltaPixels = 40f,
        canvasWidthPixels = 960f,
        rangeStartInclusive = dayStart,
        rangeEndExclusive = dayEnd,
      )
    )
    assertNull(
      GanttDirectManipulationPolicy.snappedDeltaMinutes(
        deltaPixels = Float.NaN,
        canvasWidthPixels = 960f,
        rangeStartInclusive = dayStart,
        rangeEndExclusive = dayEnd,
      )
    )
    assertNull(
      GanttDirectManipulationPolicy.snappedDeltaMinutes(
        deltaPixels = 10f,
        canvasWidthPixels = 0f,
        rangeStartInclusive = dayStart,
        rangeEndExclusive = dayEnd,
      )
    )
    assertNull(
      GanttDirectManipulationPolicy.preview(
        origin.copy(startAt = Long.MAX_VALUE - HOUR_MS, endAt = Long.MAX_VALUE),
        GanttDragTarget.BLOCK,
        deltaPixels = 10f,
        canvasWidthPixels = 960f,
        rangeStartInclusive = Long.MAX_VALUE - DAY_MS,
        rangeEndExclusive = Long.MAX_VALUE,
      )
    )
  }

  @Test
  fun `autoscroll requests are measured bounded and inert for unsafe geometry`() {
    fun delta(
      pointerX: Float,
      current: Float = 100f,
      maximum: Float = 300f,
      viewport: Float = 240f,
    ) =
      GanttDirectManipulationPolicy.boundedAutoScrollDelta(
        pointerViewportX = pointerX,
        viewportWidthPixels = viewport,
        currentScrollPixels = current,
        maximumScrollPixels = maximum,
        edgeWidthPixels = 48f,
        maximumStepPixels = 24f,
      )

    assertEquals(0f, delta(pointerX = 120f))
    assertEquals(-24f, delta(pointerX = -20f))
    assertEquals(24f, delta(pointerX = 260f))
    assertEquals(-4f, delta(pointerX = -20f, current = 4f))
    assertEquals(3f, delta(pointerX = 260f, current = 297f))
    assertEquals(0f, delta(pointerX = 10f, viewport = 80f))
    assertEquals(0f, delta(pointerX = Float.NaN))
    assertEquals(0f, delta(pointerX = 10f, current = 301f, maximum = 300f))
  }

  @Test
  fun `partial working days and split shifts produce exact nonworking bands`() {
    val zone = TimeZone.getTimeZone("UTC")
    val start = at(zone, 2036, Calendar.FEBRUARY, 25, 0, 0)
    val end = at(zone, 2036, Calendar.FEBRUARY, 26, 0, 0)
    val spec =
      WorkingCalendarSpec(
        zoneId = zone.id,
        weeklyWindows =
          listOf(
            WorkingWeekWindow(Calendar.MONDAY, 9 * 60, 12 * 60),
            WorkingWeekWindow(Calendar.MONDAY, 13 * 60, 17 * 60),
          ),
      )

    assertEquals(
      listOf(
        WorkingInterval(start, start + 9 * HOUR_MS),
        WorkingInterval(start + 12 * HOUR_MS, start + 13 * HOUR_MS),
        WorkingInterval(start + 17 * HOUR_MS, end),
      ),
      GanttWorkingBands.nonWorkingIntervals(spec, start, end),
    )
  }

  @Test
  fun `closed date exception shades the complete local day`() {
    val zone = TimeZone.getTimeZone("UTC")
    val start = at(zone, 2036, Calendar.FEBRUARY, 25, 0, 0)
    val end = at(zone, 2036, Calendar.FEBRUARY, 26, 0, 0)
    val spec =
      WorkingCalendarSpec(
        zoneId = zone.id,
        weeklyWindows = listOf(WorkingWeekWindow(Calendar.MONDAY, 9 * 60, 17 * 60)),
        overrides = listOf(WorkingDateOverride("2036-02-25", emptyList())),
      )

    assertEquals(
      listOf(WorkingInterval(start, end)),
      GanttWorkingBands.nonWorkingIntervals(spec, start, end),
    )
  }

  @Test
  fun `manipulation targets never overlap and always keep a full-size move region`() {
    // A one-hour block at the 7-day zoom: 68 dp per day makes the bar under 3 dp wide.
    val narrow =
      requireNotNull(
        GanttDirectManipulationTargets.compute(
          barStartDp = 96f,
          barEndDp = 98.8f,
          canvasWidthDp = 476f,
          handleDp = 48f,
          minimumMoveDp = 48f,
        )
      )
    assertEquals(48f, narrow.moveWidthDp, 0.01f)
    // The move region stays centred on the bar the user is actually looking at.
    assertEquals(97.4f, narrow.moveStartDp + narrow.moveWidthDp / 2f, 0.01f)
    assertNoOverlap(narrow, canvasWidthDp = 476f)

    // A wide bar keeps its own span as the move region and its handles at the real boundaries.
    val wide =
      requireNotNull(
        GanttDirectManipulationTargets.compute(
          barStartDp = 100f,
          barEndDp = 340f,
          canvasWidthDp = 476f,
          handleDp = 48f,
          minimumMoveDp = 48f,
        )
      )
    assertEquals(100f, wide.moveStartDp, 0.01f)
    assertEquals(240f, wide.moveWidthDp, 0.01f)
    assertEquals(52f, requireNotNull(wide.startHandleStartDp), 0.01f)
    assertEquals(340f, requireNotNull(wide.endHandleStartDp), 0.01f)
    assertNoOverlap(wide, canvasWidthDp = 476f)
  }

  @Test
  fun `manipulation targets stay inside the canvas at both edges and fail closed when unusable`() {
    val atStart =
      requireNotNull(
        GanttDirectManipulationTargets.compute(0f, 2.8f, 476f, 48f, 48f)
      )
    assertEquals(48f, atStart.moveStartDp, 0.01f)
    assertEquals(0f, requireNotNull(atStart.startHandleStartDp), 0.01f)
    assertNoOverlap(atStart, canvasWidthDp = 476f)

    val atEnd =
      requireNotNull(
        GanttDirectManipulationTargets.compute(473.2f, 476f, 476f, 48f, 48f)
      )
    assertEquals(380f, atEnd.moveStartDp, 0.01f)
    assertEquals(476f, requireNotNull(atEnd.endHandleStartDp) + 48f, 0.01f)
    assertNoOverlap(atEnd, canvasWidthDp = 476f)

    // Too little room for handles: the move target survives, the handles honestly disappear.
    val cramped = requireNotNull(GanttDirectManipulationTargets.compute(10f, 12f, 60f, 48f, 48f))
    assertEquals(48f, cramped.moveWidthDp, 0.01f)
    assertNull(cramped.startHandleStartDp)
    assertNull(cramped.endHandleStartDp)

    assertNull(GanttDirectManipulationTargets.compute(10f, 5f, 476f, 48f, 48f))
    assertNull(GanttDirectManipulationTargets.compute(10f, 20f, 0f, 48f, 48f))
    assertNull(GanttDirectManipulationTargets.compute(Float.NaN, 20f, 476f, 48f, 48f))
    assertNull(GanttDirectManipulationTargets.compute(10f, 20f, 476f, 0f, 48f))
  }

  private fun assertNoOverlap(targets: GanttManipulationTargets, canvasWidthDp: Float) {
    val move = targets.moveStartDp..(targets.moveStartDp + targets.moveWidthDp)
    assertTrue("move region must fit the canvas", move.start >= 0f && move.endInclusive <= canvasWidthDp)
    assertTrue("move region must stay tappable", targets.moveWidthDp >= 48f)
    targets.startHandleStartDp?.let { start ->
      assertTrue("start handle must fit the canvas", start >= 0f && start + targets.handleDp <= canvasWidthDp)
      assertTrue("start handle must not cover the move region", start + targets.handleDp <= move.start)
    }
    targets.endHandleStartDp?.let { start ->
      assertTrue("end handle must fit the canvas", start >= 0f && start + targets.handleDp <= canvasWidthDp)
      assertTrue("end handle must not cover the move region", start >= move.endInclusive)
    }
  }

  private fun at(
    zone: TimeZone,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
  ): Long =
    Calendar.getInstance(zone).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis

  private companion object {
    const val MINUTE_MS = 60 * 1000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
  }
}
