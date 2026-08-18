package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanGanttLayoutTest {
  private val range = GanttLayout.VisibleRange(1_000L, 5_000L)

  @Test
  fun `split blocks share one task row and keep explicit position order`() {
    val item = item("task")
    val later = block("later", item.id, 3_000L, 4_000L, position = 1)
    val earlier = block("earlier", item.id, 1_500L, 2_000L, position = 0)

    val row = PlanGanttLayout.layout(listOf(item), listOf(later, earlier), range).rows.single()

    assertEquals(PlanGanttLayout.RowState.SCHEDULED, row.state)
    assertEquals(listOf("earlier", "later"), row.segments.map { it.block.id })
    assertEquals(0.125, row.segments.first().startPosition, 0.0)
    assertEquals(0.25, row.segments.first().endPosition, 0.0)
  }

  @Test
  fun `blocks clip honestly at both visible edges`() {
    val item = item("task")
    val spanning = block("spanning", item.id, 0L, 6_000L)

    val segment =
      PlanGanttLayout.layout(listOf(item), listOf(spanning), range)
        .rows
        .single()
        .segments
        .single()

    assertEquals(0.0, segment.startPosition, 0.0)
    assertEquals(1.0, segment.endPosition, 0.0)
    assertTrue(segment.clippedAtStart)
    assertTrue(segment.clippedAtEnd)
  }

  @Test
  fun `overlapping blocks use separate lanes and later work reuses a free lane`() {
    val item = item("task")
    val first = block("first", item.id, 1_500L, 2_500L, position = 2)
    val overlap = block("overlap", item.id, 2_000L, 3_000L, position = 0)
    val later = block("later", item.id, 3_000L, 4_000L, position = 1)

    val row = PlanGanttLayout.layout(listOf(item), listOf(first, overlap, later), range).rows.single()
    val lanes = row.segments.associate { it.block.id to it.lane }

    assertEquals(2, row.laneCount)
    assertTrue(lanes.getValue("first") != lanes.getValue("overlap"))
    assertEquals(lanes.getValue("first"), lanes.getValue("later"))
    assertEquals(listOf("overlap", "later", "first"), row.segments.map { it.block.id })
  }

  @Test
  fun `unscheduled and off-range work remain disclosed`() {
    val unscheduled = item("unscheduled", rank = 1L)
    val outside = item("outside", rank = 2L)
    val layout =
      PlanGanttLayout.layout(
        listOf(outside, unscheduled),
        listOf(block("future", outside.id, 8_000L, 9_000L)),
        range,
      )

    assertEquals(listOf("unscheduled", "outside"), layout.rows.map { it.item.id })
    assertEquals(PlanGanttLayout.RowState.UNSCHEDULED, layout.rows[0].state)
    assertEquals(PlanGanttLayout.RowState.OUTSIDE_RANGE, layout.rows[1].state)
    assertNull(layout.rows[0].revealAt)
    assertEquals(8_000L, layout.rows[1].revealAt)
    assertTrue(layout.rows.all { it.segments.isEmpty() })
  }

  @Test
  fun `milestones use due date as their point on the time spine`() {
    val milestone =
      item(
        id = "milestone",
        isMilestone = true,
        startConstraint = 1_500L,
        dueAt = 3_000L,
      )

    val row = PlanGanttLayout.layout(listOf(milestone), emptyList(), range).rows.single()

    assertEquals(PlanGanttLayout.RowState.MILESTONE, row.state)
    assertEquals(0.5, requireNotNull(row.milestonePosition), 0.0)
    assertTrue(row.segments.isEmpty())
  }

  @Test
  fun `undated and off-range milestones are not drawn as fake bars`() {
    val undated = item("undated", rank = 1L, isMilestone = true)
    val outside = item("outside", rank = 2L, isMilestone = true, dueAt = 9_000L)

    val rows = PlanGanttLayout.layout(listOf(outside, undated), emptyList(), range).rows

    assertEquals(PlanGanttLayout.RowState.UNSCHEDULED, rows[0].state)
    assertEquals(PlanGanttLayout.RowState.OUTSIDE_RANGE, rows[1].state)
    assertNull(rows[0].revealAt)
    assertEquals(9_000L, rows[1].revealAt)
    assertTrue(rows.all { it.milestonePosition == null })
  }

  @Test
  fun `start constraint alone never invents a milestone date`() {
    val milestone = item("milestone", isMilestone = true, startConstraint = 2_000L)

    val row = PlanGanttLayout.layout(listOf(milestone), emptyList(), range).rows.single()

    assertEquals(PlanGanttLayout.RowState.UNSCHEDULED, row.state)
    assertNull(row.milestonePosition)
    assertNull(row.revealAt)
  }

  @Test
  fun `ordinary due dates remain metadata until an explicit block exists`() {
    val task = item("task", dueAt = 3_000L)

    val row = PlanGanttLayout.layout(listOf(task), emptyList(), range).rows.single()

    assertEquals(PlanGanttLayout.RowState.UNSCHEDULED, row.state)
    assertNull(row.milestonePosition)
  }

  @Test
  fun `archived tasks orphan blocks and malformed blocks never leak into the layout`() {
    val active = item("active")
    val archived = item("archived", archivedAt = 7L)
    val layout =
      PlanGanttLayout.layout(
        listOf(archived, active),
        listOf(
          block("archived-block", archived.id, 2_000L, 3_000L),
          block("orphan", "missing", 2_000L, 3_000L),
          block("malformed", active.id, 3_000L, 3_000L),
        ),
        range,
      )

    assertEquals(listOf("active"), layout.rows.map { it.item.id })
    assertEquals(PlanGanttLayout.RowState.UNSCHEDULED, layout.rows.single().state)
    assertFalse(layout.rows.single().segments.isNotEmpty())
    assertNull(layout.rows.single().milestonePosition)
  }

  private fun item(
    id: String,
    rank: Long = 0L,
    isMilestone: Boolean = false,
    startConstraint: Long? = null,
    dueAt: Long? = null,
    archivedAt: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      rank = rank,
      isMilestone = isMilestone,
      startConstraint = startConstraint,
      dueAt = dueAt,
      archivedAt = archivedAt,
      createdAt = rank,
      updatedAt = rank,
    )

  private fun block(
    id: String,
    itemId: String,
    startAt: Long,
    endAt: Long,
    position: Int = 0,
  ) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = startAt,
      endAt = endAt,
      position = position,
      createdAt = startAt,
      updatedAt = startAt,
    )
}
