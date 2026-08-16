package com.example.ui.screens

import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanBoardProjectorTest {
  @Test
  fun `groups inbox and workflow columns in deterministic lane and row order`() {
    val lanes =
      PlanBoardProjector.project(
        boardId = "board",
        columns =
          listOf(
            column("later", name = "Later", rank = 2),
            column("doing", name = "Doing", rank = 1, createdAt = 2),
            column("ready", name = "Ready", rank = 1, createdAt = 1),
          ),
        items =
          listOf(
            item("inbox-b", columnId = null, rank = 2),
            item("doing-b", columnId = "doing", rank = 2),
            item("ready", columnId = "ready"),
            item("inbox-a", columnId = null, rank = 1),
            item("doing-a", columnId = "doing", rank = 1),
          ),
      )

    assertEquals(
      listOf(
        PlanBoardLaneKind.INBOX,
        PlanBoardLaneKind.COLUMN,
        PlanBoardLaneKind.COLUMN,
        PlanBoardLaneKind.COLUMN,
      ),
      lanes.map { it.kind },
    )
    assertEquals(listOf("Inbox", "Ready", "Doing", "Later"), lanes.map { it.title })
    assertEquals(listOf("inbox-a", "inbox-b"), lanes[0].rows.map { it.item.id })
    assertEquals(listOf("ready"), lanes[1].rows.map { it.item.id })
    assertEquals(listOf("doing-a", "doing-b"), lanes[2].rows.map { it.item.id })
    assertTrue(lanes[3].rows.isEmpty())
  }

  @Test
  fun `excludes archived items and data owned by another board`() {
    val lanes =
      PlanBoardProjector.project(
        boardId = "board",
        columns =
          listOf(
            column("active"),
            column("archived-column", archivedAt = 20),
            column("foreign-column", boardId = "other"),
          ),
        items =
          listOf(
            item("active", columnId = "active"),
            item("archived", columnId = null, archivedAt = 30),
            item("foreign-item", boardId = "other", columnId = "foreign-column"),
          ),
      )

    assertEquals(listOf("Inbox", "Column"), lanes.map { it.title })
    assertTrue(lanes.first().rows.isEmpty())
    assertEquals(listOf("active"), lanes.last().rows.map { it.item.id })
  }

  @Test
  fun `routes stale and non-owned column destinations into the recovery lane`() {
    val lanes =
      PlanBoardProjector.project(
        boardId = "board",
        columns =
          listOf(
            column("active"),
            column("archived", archivedAt = 20),
            column("other-column", boardId = "other"),
          ),
        items =
          listOf(
            item("known", columnId = "active"),
            item("deleted-destination", columnId = "missing", rank = 2),
            item("archived-destination", columnId = "archived", rank = 1),
            item("foreign-destination", columnId = "other-column", rank = 3),
          ),
      )

    val recovery = lanes.last()
    assertEquals(PlanBoardLaneKind.NEEDS_ROUTING, recovery.kind)
    assertEquals("needs-routing", recovery.key)
    assertEquals(
      listOf("archived-destination", "deleted-destination", "foreign-destination"),
      recovery.rows.map { it.item.id },
    )
  }

  @Test
  fun `collapse hides descendants while preserving the collapsed row child affordance`() {
    val lane =
      PlanBoardProjector.project(
          boardId = "board",
          columns = listOf(column("column")),
          items =
            listOf(
              item("root", rank = 0),
              item("child", parentId = "root", rank = 1),
              item("grandchild", parentId = "child", rank = 2),
              item("sibling", parentId = "root", rank = 3),
            ),
          collapsedItemIds = setOf("child"),
        )
        .single { it.kind == PlanBoardLaneKind.COLUMN }

    assertEquals(listOf("root", "child", "sibling"), lane.rows.map { it.item.id })
    assertEquals(listOf(0, 1, 1), lane.rows.map { it.depth })
    assertTrue(lane.rows.single { it.item.id == "child" }.hasChildren)
  }

  @Test
  fun `cycles and missing parents remain visible exactly once`() {
    val lane =
      PlanBoardProjector.project(
          boardId = "board",
          columns = listOf(column("column")),
          items =
            listOf(
              item("cycle-a", parentId = "cycle-b", rank = 0),
              item("cycle-b", parentId = "cycle-a", rank = 1),
              item("orphan", parentId = "missing", rank = 2),
              item("orphan-child", parentId = "orphan", rank = 3),
            ),
        )
        .single { it.kind == PlanBoardLaneKind.COLUMN }

    assertEquals(
      listOf("orphan", "orphan-child", "cycle-a", "cycle-b"),
      lane.rows.map { it.item.id },
    )
    assertEquals(4, lane.rows.map { it.item.id }.distinct().size)
    assertTrue(lane.rows.single { it.item.id == "orphan" }.isOrphan)
    assertFalse(lane.rows.single { it.item.id == "orphan-child" }.isOrphan)
    assertTrue(lane.rows.single { it.item.id == "cycle-a" }.isOrphan)
  }

  @Test
  fun `collapsing a member of a closed cycle terminates and keeps that member visible`() {
    val lane =
      PlanBoardProjector.project(
          boardId = "board",
          columns = listOf(column("column")),
          items =
            listOf(
              item("cycle-a", parentId = "cycle-b", rank = 0),
              item("cycle-b", parentId = "cycle-a", rank = 1),
            ),
          collapsedItemIds = setOf("cycle-a"),
        )
        .single { it.kind == PlanBoardLaneKind.COLUMN }

    assertEquals(listOf("cycle-a"), lane.rows.map { it.item.id })
    assertTrue(lane.rows.single().hasChildren)
  }

  private fun column(
    id: String,
    name: String = "Column",
    boardId: String = "board",
    rank: Long = 0,
    archivedAt: Long? = null,
    createdAt: Long = rank,
  ) =
    PlanColumn(
      id = id,
      boardId = boardId,
      name = name,
      nameKey = name.lowercase(),
      rank = rank,
      archivedAt = archivedAt,
      createdAt = createdAt,
      updatedAt = createdAt,
    )

  private fun item(
    id: String,
    boardId: String = "board",
    columnId: String? = "column",
    parentId: String? = null,
    rank: Long = 0,
    archivedAt: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = boardId,
      columnId = columnId,
      parentId = parentId,
      title = id,
      rank = rank,
      archivedAt = archivedAt,
      createdAt = rank,
      updatedAt = rank,
    )
}
