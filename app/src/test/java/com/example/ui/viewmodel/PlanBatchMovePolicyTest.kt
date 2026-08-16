package com.example.ui.viewmodel

import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanBatchMovePolicyTest {
  @Test
  fun `duplicates and overlapping hierarchy selections become one atomic group`() {
    val items =
      listOf(
        item("root", column = "ready"),
        item("child", column = "ready", parent = "root"),
        item("sibling", column = "ready", parent = "root"),
        item("already", column = "done"),
      )

    val plan =
      PlanBatchMovePolicy.plan(
        items = items,
        selectedItemIds = listOf("root", "child", "root", "already", "missing", ""),
        targetColumnId = "done",
      )

    assertEquals(setOf("root", "child", "already", "missing"), plan.requestedItemIds)
    assertEquals(setOf("missing"), plan.unavailableItemIds)
    assertEquals(setOf("already"), plan.alreadyAtDestinationItemIds)
    assertEquals(2, plan.groups.size)
    assertEquals(setOf("root", "child", "sibling"), plan.affectedItemIds)
    assertEquals(setOf("sibling"), plan.includedHierarchyItemIds)
    assertEquals("1 selected task(s) are no longer available", plan.validationMessage)
  }

  @Test
  fun `a complete hierarchy is previewed once and can apply atomically`() {
    val plan =
      PlanBatchMovePolicy.plan(
        items =
          listOf(
            item("parent", column = "ready"),
            item("child", column = "ready", parent = "parent"),
          ),
        selectedItemIds = listOf("parent", "child"),
        targetColumnId = "done",
      )

    assertEquals(setOf("parent", "child"), plan.affectedItemIds)
    assertEquals(1, plan.groups.size)
    assertTrue(plan.alreadyAtDestinationItemIds.isEmpty())
    assertTrue(plan.canApply)
  }

  @Test
  fun `archived and unavailable selections fail closed`() {
    val plan =
      PlanBatchMovePolicy.plan(
        items = listOf(item("active"), item("archived", archivedAt = 4)),
        selectedItemIds = listOf("archived", "missing"),
        targetColumnId = "done",
      )

    assertEquals(setOf("archived", "missing"), plan.unavailableItemIds)
    assertTrue(plan.groups.isEmpty())
    assertTrue(!plan.canApply)
  }

  @Test
  fun `cross Plan selections fail closed before producing groups`() {
    val plan =
      PlanBatchMovePolicy.plan(
        items = listOf(item("one", board = "one"), item("two", board = "two")),
        selectedItemIds = listOf("one", "two"),
        targetColumnId = "done",
      )

    assertEquals(setOf("one", "two"), plan.crossBoardItemIds)
    assertTrue(plan.groups.isEmpty())
    assertEquals("A move cannot cross Plans", plan.validationMessage)
  }

  @Test
  fun `cycle and split column hierarchies are rejected`() {
    val cycle =
      PlanBatchMovePolicy.plan(
        items =
          listOf(
            item("one", parent = "two"),
            item("two", parent = "one"),
          ),
        selectedItemIds = listOf("one"),
        targetColumnId = "done",
      )
    val split =
      PlanBatchMovePolicy.plan(
        items =
          listOf(
            item("parent", column = "ready"),
            item("child", column = "done", parent = "parent"),
          ),
        selectedItemIds = listOf("parent"),
        targetColumnId = "done",
      )

    assertEquals(setOf("one", "two"), cycle.cyclicHierarchyItemIds)
    assertTrue(!cycle.canApply)
    assertEquals(setOf("parent", "child"), split.invalidHierarchyItemIds)
    assertTrue(!split.canApply)
  }

  @Test
  fun `a valid selection already at its destination is an explicit no op`() {
    val plan =
      PlanBatchMovePolicy.plan(
        items = listOf(item("parent", column = "done"), item("child", column = "done", parent = "parent")),
        selectedItemIds = listOf("parent"),
        targetColumnId = "done",
      )

    assertTrue(plan.isNoOp)
    assertEquals(setOf("parent"), plan.alreadyAtDestinationItemIds)
  }

  private fun item(
    id: String,
    board: String = "board",
    column: String? = "ready",
    parent: String? = null,
    archivedAt: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = board,
      columnId = column,
      parentId = parent,
      title = id,
      rank = id.hashCode().toLong(),
      archivedAt = archivedAt,
      createdAt = 1,
      updatedAt = 1,
    )
}
