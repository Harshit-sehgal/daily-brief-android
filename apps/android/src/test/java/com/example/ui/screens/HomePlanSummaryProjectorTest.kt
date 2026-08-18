package com.example.ui.screens

import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePlanSummaryProjectorTest {
  @Test
  fun `summary uses Board lane order and always exposes Inbox and recovery work`() {
    val board = board("active", "Launch")
    val columns =
      listOf(
        column("one", "Ready", rank = 1),
        column("two", "Doing", rank = 2),
        column("three", "Review", rank = 3),
        column("four", "Done", rank = 4),
      )
    val summary =
      requireNotNull(
        HomePlanSummaryProjector.project(
          board = board,
          columns = columns,
          items =
            listOf(
              item("inbox", columnId = null, rank = 0),
              item("milestone", columnId = "one", rank = 1, isMilestone = true),
              item("recovery", columnId = "missing", rank = 2),
              item("archived", columnId = "two", archivedAt = 5),
              item("foreign", boardId = "other", columnId = null),
            ),
        )
      )

    assertEquals("active", summary.boardId)
    assertEquals("Launch", summary.boardName)
    assertEquals(3, summary.totalItems)
    assertEquals(listOf("Inbox", "Ready", "Doing", "Needs routing"), summary.visibleLanes.map { it.title })
    assertEquals(listOf(1, 1, 0, 1), summary.visibleLanes.map { it.count })
    assertEquals(2, summary.hiddenLaneCount)
    assertEquals(listOf("inbox", "milestone", "recovery"), summary.preview.map { it.item.id })
    assertEquals(listOf("Inbox", "Ready", "Needs routing"), summary.preview.map { it.laneTitle })
    assertTrue(summary.preview[1].item.isMilestone)
  }

  @Test
  fun `missing active board has no misleading summary`() {
    assertEquals(null, HomePlanSummaryProjector.project(null, emptyList(), emptyList()))
  }

  private fun board(id: String, name: String) =
    PlanBoard(
      id = id,
      name = name,
      nameKey = name.lowercase(),
      rank = 0,
      isDefault = true,
      createdAt = 0,
      updatedAt = 0,
    )

  private fun column(id: String, name: String, rank: Long) =
    PlanColumn(
      id = id,
      boardId = "active",
      name = name,
      nameKey = name.lowercase(),
      rank = rank,
      createdAt = rank,
      updatedAt = rank,
    )

  private fun item(
    id: String,
    boardId: String = "active",
    columnId: String?,
    rank: Long = 0,
    archivedAt: Long? = null,
    isMilestone: Boolean = false,
  ) =
    PlanItem(
      id = id,
      boardId = boardId,
      columnId = columnId,
      title = id,
      rank = rank,
      isMilestone = isMilestone,
      archivedAt = archivedAt,
      createdAt = rank,
      updatedAt = rank,
    )
}
