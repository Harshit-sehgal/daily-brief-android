package com.example.core

import com.example.data.model.BriefingEvent
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GanttLayoutTest {

  private val utc = TimeZone.getTimeZone("UTC")

  private fun instant(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    timeZone: TimeZone = utc,
  ): Long =
    Calendar.getInstance(timeZone)
      .apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
      }
      .timeInMillis

  private fun event(
    id: String,
    start: Long,
    end: Long,
    board: String = "Default",
    status: String = "To Do",
    title: String = id,
    deadline: Boolean = false,
  ) =
    BriefingEvent(
      id = id,
      title = title,
      startTime = start,
      endTime = end,
      source = "Manual",
      description = null,
      isDeadline = deadline,
      isUrgent = false,
      kanbanBoard = board,
      kanbanStatus = status,
    )

  @Test
  fun `visible range rejects empty and reversed intervals`() {
    assertFailsWithIllegalArgument { GanttLayout.VisibleRange(10, 10) }
    assertFailsWithIllegalArgument { GanttLayout.VisibleRange(11, 10) }
  }

  @Test
  fun `time conversion clamps overscroll and remains stable near long limits`() {
    val nearMaximum = GanttLayout.VisibleRange(Long.MAX_VALUE - 1_000, Long.MAX_VALUE)

    assertEquals(0.0, nearMaximum.positionOf(Long.MIN_VALUE), 0.0)
    assertEquals(0.5, nearMaximum.positionOf(Long.MAX_VALUE - 500), 0.000_000_001)
    assertEquals(1.0, nearMaximum.positionOf(Long.MAX_VALUE), 0.0)
    assertEquals(Long.MAX_VALUE - 500, nearMaximum.timeAt(0.5))
    assertEquals(Long.MAX_VALUE - 1_000, nearMaximum.timeAt(-1.0))
    assertEquals(Long.MAX_VALUE, nearMaximum.timeAt(2.0))
    assertFailsWithIllegalArgument { nearMaximum.timeAt(Double.NaN) }
  }

  @Test
  fun `empty input produces no groups or rows`() {
    val range = GanttLayout.VisibleRange(100, 200)

    val layout = GanttLayout.layout(emptyList(), range)

    assertTrue(layout.groups.isEmpty())
    assertTrue(layout.items.isEmpty())
  }

  @Test
  fun `bars are clipped at both viewport edges and outside rows are excluded`() {
    val range = GanttLayout.VisibleRange(100, 200)
    val layout =
      GanttLayout.layout(
        listOf(
          event("left", 50, 125),
          event("middle", 125, 175),
          event("right", 175, 250),
          event("before", 50, 100),
          event("after", 200, 250),
        ),
        range,
      )
    val rows = layout.items.associateBy { it.event.id }

    assertEquals(setOf("left", "middle", "right"), rows.keys)
    assertEquals(0.0, rows.getValue("left").startPosition, 0.0)
    assertEquals(0.25, rows.getValue("left").endPosition, 0.0)
    assertTrue(rows.getValue("left").clippedAtStart)
    assertFalse(rows.getValue("left").clippedAtEnd)
    assertEquals(0.75, rows.getValue("right").startPosition, 0.0)
    assertEquals(1.0, rows.getValue("right").endPosition, 0.0)
    assertFalse(rows.getValue("right").clippedAtStart)
    assertTrue(rows.getValue("right").clippedAtEnd)
  }

  @Test
  fun `groups and rows have deterministic board status time ordering`() {
    val range = GanttLayout.VisibleRange(0, 1_000)
    val shuffled =
      listOf(
        event("z", 300, 400, board = "Beta", status = "Doing"),
        event("later", 500, 600, board = "Alpha", status = "To Do"),
        event("tie-b", 100, 200, board = "Alpha", status = "To Do", title = "Same"),
        event("done", 50, 70, board = "Alpha", status = "Done"),
        event("tie-a", 100, 200, board = "Alpha", status = "To Do", title = "Same"),
      )

    val first = GanttLayout.layout(shuffled, range)
    val second = GanttLayout.layout(shuffled.reversed(), range)

    val expectedGroups =
      listOf(
        GanttLayout.GroupKey("Alpha", "Done"),
        GanttLayout.GroupKey("Alpha", "To Do"),
        GanttLayout.GroupKey("Beta", "Doing"),
      )
    assertEquals(expectedGroups, first.groups.map { it.key })
    assertEquals(
      listOf("done", "tie-a", "tie-b", "later", "z"),
      first.items.map { it.event.id },
    )
    assertEquals(first.items.map { it.event.id }, second.items.map { it.event.id })
    assertEquals((0..4).toList(), first.items.map { it.rowIndex })
  }

  @Test
  fun `only a point deadline is represented as a milestone`() {
    val range = GanttLayout.VisibleRange(0, 1_000)
    val pointDeadline = event("milestone", 200, 200, deadline = true)
    val durationDeadline = event("deadline-bar", 300, 400, deadline = true)
    val ambiguousPoint = event("ordinary-point", 500, 500)
    val invalid = event("invalid", 700, 600, deadline = true)

    val layout =
      GanttLayout.layout(
        listOf(pointDeadline, durationDeadline, ambiguousPoint, invalid),
        range,
      )
    val rows = layout.items.associateBy { it.event.id }

    assertEquals(setOf("milestone", "deadline-bar"), rows.keys)
    assertEquals(GanttLayout.ItemKind.MILESTONE, rows.getValue("milestone").kind)
    assertEquals(
      rows.getValue("milestone").startPosition,
      rows.getValue("milestone").endPosition,
      0.0,
    )
    assertEquals(GanttLayout.ItemKind.BAR, rows.getValue("deadline-bar").kind)
    assertNull(GanttLayout.kindOf(ambiguousPoint))
    assertNull(GanttLayout.kindOf(invalid))
  }

  @Test
  fun `milestones use half-open range boundaries`() {
    val range = GanttLayout.VisibleRange(100, 200)
    val layout =
      GanttLayout.layout(
        listOf(
          event("start", 100, 100, deadline = true),
          event("end", 200, 200, deadline = true),
        ),
        range,
      )

    assertEquals(listOf("start"), layout.items.map { it.event.id })
    assertEquals(0.0, layout.items.single().startPosition, 0.0)
  }

  @Test
  fun `a multi-year bar keeps useful geometry after clipping`() {
    val visibleStart = instant(2026, 1, 1)
    val visibleEnd = instant(2027, 1, 1)
    val range = GanttLayout.VisibleRange(visibleStart, visibleEnd)
    val century = event("century", instant(1970, 1, 1), instant(2070, 1, 1))

    val row = GanttLayout.layout(listOf(century), range).items.single()

    assertEquals(0.0, row.startPosition, 0.0)
    assertEquals(1.0, row.endPosition, 0.0)
    assertTrue(row.clippedAtStart)
    assertTrue(row.clippedAtEnd)
  }

  @Test
  fun `calendar ticks cross spring DST without assuming 24 hour days`() {
    val newYork = TimeZone.getTimeZone("America/New_York")
    val start = instant(2026, 3, 7, timeZone = newYork)
    val end = instant(2026, 3, 11, timeZone = newYork)

    val ticks =
      GanttLayout.dayTicks(
        range = GanttLayout.VisibleRange(start, end),
        timeZone = newYork,
        maximumTicks = 10,
      )
    val intervals = ticks.zipWithNext { first, second -> second.timeMs - first.timeMs }

    assertEquals(5, ticks.size)
    assertEquals(
      listOf(24L * HOUR_MS, 23L * HOUR_MS, 24L * HOUR_MS, 24L * HOUR_MS),
      intervals,
    )
    assertEquals(listOf(0.0, 1.0), listOf(ticks.first().position, ticks.last().position))
  }

  @Test
  fun `today band has the real DST day width and exposes now only when visible`() {
    val newYork = TimeZone.getTimeZone("America/New_York")
    val rangeStart = instant(2026, 3, 7, timeZone = newYork)
    val rangeEnd = instant(2026, 3, 10, timeZone = newYork)
    val now = instant(2026, 3, 8, 12, timeZone = newYork)
    val range = GanttLayout.VisibleRange(rangeStart, rangeEnd)

    val today = requireNotNull(GanttLayout.today(range, now, newYork))

    assertEquals(23L * HOUR_MS, today.nextDayStartMs - today.dayStartMs)
    assertEquals(23.0 / 71.0, today.endPosition - today.startPosition, 0.000_000_001)
    assertEquals(range.positionOf(now), today.nowPosition)
    assertFalse(today.clippedAtStart)
    assertFalse(today.clippedAtEnd)
    assertNull(
      GanttLayout.today(
        range = range,
        nowMs = instant(2026, 3, 15, 12, timeZone = newYork),
        timeZone = newYork,
      ),
    )
  }

  @Test
  fun `wide ranges coarsen calendar ticks to the requested capacity`() {
    val range =
      GanttLayout.VisibleRange(
        instant(2020, 1, 1),
        instant(2030, 1, 1),
      )

    val ticks = GanttLayout.dayTicks(range, utc, maximumTicks = 12)

    assertTrue(ticks.size <= 12)
    assertTrue(ticks.isNotEmpty())
    assertTrue(ticks.all { it.stepDays > 1 })
    assertTrue(ticks.zipWithNext().all { (first, second) -> second.timeMs > first.timeMs })
  }

  @Test
  fun `tick helpers validate dimensions and mark month boundaries`() {
    assertEquals(6, GanttLayout.tickCapacity(360.0, 60.0))
    assertEquals(1, GanttLayout.tickCapacity(30.0, 60.0))
    assertFailsWithIllegalArgument { GanttLayout.tickCapacity(0.0, 60.0) }
    assertFailsWithIllegalArgument { GanttLayout.tickCapacity(360.0, Double.NaN) }

    val range =
      GanttLayout.VisibleRange(
        instant(2025, 12, 31),
        instant(2026, 2, 2),
      )
    val ticks = GanttLayout.dayTicks(range, utc, maximumTicks = 40)
    val byTime = ticks.associateBy { it.timeMs }

    assertEquals(GanttLayout.TickKind.YEAR, byTime.getValue(instant(2026, 1, 1)).kind)
    assertEquals(GanttLayout.TickKind.MONTH, byTime.getValue(instant(2026, 2, 1)).kind)
    assertFailsWithIllegalArgument {
      GanttLayout.dayTicks(range, utc, minimumStepDays = 0)
    }
    assertFailsWithIllegalArgument { GanttLayout.dayTicks(range, utc, maximumTicks = 0) }
    assertFailsWithIllegalArgument { GanttLayout.dayTicks(range, utc, maximumTicks = 10_001) }
  }

  private fun assertFailsWithIllegalArgument(block: () -> Unit) {
    try {
      block()
      fail("Expected IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
      // Expected.
    }
  }

  private companion object {
    const val HOUR_MS = 60L * 60 * 1_000
  }
}
