package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioGanttTest {

  private val utc = TimeZone.getTimeZone("UTC")

  private fun day(offsetDays: Int): Long =
    Calendar.getInstance(utc)
      .apply {
        clear()
        set(2026, 2, 2 + offsetDays, 0, 0, 0)
      }
      .timeInMillis

  private fun item(id: String, boardId: String) =
    PlanItem(
      id = id,
      boardId = boardId,
      title = id,
      rank = 1,
      effortMinutes = 60,
      progress = 0,
      locked = false,
      createdAt = 0,
      updatedAt = 0,
    )

  private fun block(id: String, itemId: String, start: Long, end: Long) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = start,
      endAt = end,
      position = 0,
      locked = false,
      linkedEventId = null,
      createdAt = 0,
      updatedAt = 0,
    )

  private val monday = day(0)

  @Test
  fun `all boards merge onto one spine in board order`() {
    val timeline =
      PortfolioGantt.layout(
        boards = listOf("b1" to "Build", "b2" to "Hire"),
        itemsByBoard =
          mapOf(
            "b1" to listOf(item("i1", "b1")),
            "b2" to listOf(item("i2", "b2")),
          ),
        blocksByItem =
          mapOf(
            "i1" to listOf(block("blk1", "i1", monday + 9 * 3_600_000, monday + 10 * 3_600_000)),
            "i2" to listOf(block("blk2", "i2", monday + 11 * 3_600_000, monday + 12 * 3_600_000)),
          ),
        range = GanttLayout.VisibleRange(monday, day(7)),
      )

    assertEquals(listOf("Build", "Hire"), timeline.bars.map(PortfolioBar::boardName))
    assertEquals(listOf("i1", "i2"), timeline.bars.map(PortfolioBar::itemId))
    assertTrue("bar one starts inside the week", timeline.bars[0].startPosition in 0.0..1.0)
  }

  @Test
  fun `blocks outside the range are clipped and flagged`() {
    val timeline =
      PortfolioGantt.layout(
        boards = listOf("b1" to "Build"),
        itemsByBoard = mapOf("b1" to listOf(item("i1", "b1"))),
        blocksByItem =
          mapOf(
            "i1" to
              listOf(
                block("before", "i1", monday - 3_600_000, monday + 3_600_000),
                block("after", "i1", day(7) - 3_600_000, day(8)),
              )
          ),
        range = GanttLayout.VisibleRange(monday, day(7)),
      )

    val before = timeline.bars.single { it.startMs == monday - 3_600_000 }
    assertTrue(before.clippedAtStart)
    assertEquals(0.0, before.startPosition, 0.0001)
    val after = timeline.bars.single { it.startMs == day(7) - 3_600_000 }
    assertTrue(after.clippedAtEnd)
    assertEquals(1.0, after.endPosition, 0.0001)
  }

  @Test
  fun `an empty portfolio is empty rather than misleading`() {
    val timeline =
      PortfolioGantt.layout(
        boards = listOf("b1" to "Build"),
        itemsByBoard = mapOf("b1" to listOf(item("i1", "b1"))),
        blocksByItem = emptyMap(),
        range = GanttLayout.VisibleRange(monday, day(7)),
      )

    assertTrue(timeline.isEmpty)
  }
}
