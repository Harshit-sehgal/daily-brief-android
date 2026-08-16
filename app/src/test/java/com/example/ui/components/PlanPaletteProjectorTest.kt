package com.example.ui.components

import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanPaletteProjectorTest {
  @Test
  fun `active tasks and milestones keep stable identity and honest destination context`() {
    val targets =
      PlanPaletteProjector.project(
        activeBoardId = "active-board",
        boards = listOf(board("active-board", "Launch"), board("other-board", "Other")),
        columns =
          listOf(
            column("ready", "Ready"),
            column("archived-column", "Old", archivedAt = 10),
          ),
        items =
          listOf(
            item("task-inbox", columnId = null, rank = 0),
            item("milestone-id", columnId = "ready", rank = 1, isMilestone = true),
            item("task-recovery", columnId = "archived-column", rank = 2),
            item("archived-task", columnId = null, rank = 3, archivedAt = 20),
            item("foreign-task", boardId = "other-board", columnId = null, rank = 4),
          ),
      )

    assertEquals(listOf("task-inbox", "milestone-id", "task-recovery"), targets.map { it.item.id })
    assertEquals(listOf("Task", "Milestone", "Task"), targets.map { it.group })
    assertEquals(
      listOf("Launch · Inbox", "Launch · Ready", "Launch · Needs routing"),
      targets.map { it.subtitle },
    )
    assertEquals(
      listOf("plan_item_task-inbox", "plan_item_milestone-id", "plan_item_task-recovery"),
      targets.map { it.paletteId },
    )
    assertTrue(targets[1].searchTerms.contains("milestone-id"))
    assertTrue(targets[1].searchTerms.contains("Launch"))
  }

  @Test
  fun `missing active context yields no stale task targets`() {
    assertTrue(
      PlanPaletteProjector.project(
          activeBoardId = "missing",
          boards = listOf(board("active-board", "Launch")),
          columns = emptyList(),
          items = listOf(item("task", columnId = null)),
        )
        .isEmpty()
    )
  }

  private fun board(id: String, name: String) =
    PlanBoard(
      id = id,
      name = name,
      nameKey = name.lowercase(),
      rank = 0,
      createdAt = 0,
      updatedAt = 0,
    )

  private fun column(id: String, name: String, archivedAt: Long? = null) =
    PlanColumn(
      id = id,
      boardId = "active-board",
      name = name,
      nameKey = name.lowercase(),
      rank = 0,
      archivedAt = archivedAt,
      createdAt = 0,
      updatedAt = 0,
    )

  private fun item(
    id: String,
    boardId: String = "active-board",
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
