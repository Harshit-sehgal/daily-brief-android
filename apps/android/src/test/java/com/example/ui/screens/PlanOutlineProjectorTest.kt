package com.example.ui.screens

import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanOutlineProjectorTest {
  @Test
  fun `orders roots and siblings by rank then creation time then id`() {
    val items =
      listOf(
        item("root-b", rank = 2, createdAt = 1),
        item("child-b", parentId = "root-c", rank = 5, createdAt = 2),
        item("root-a", rank = 1, createdAt = 2),
        item("child-a", parentId = "root-c", rank = 5, createdAt = 2),
        item("root-c", rank = 1, createdAt = 1),
      )

    val expected = listOf("root-c", "child-a", "child-b", "root-a", "root-b")

    assertEquals(expected, PlanOutlineProjector.project(items).map { it.item.id })
    assertEquals(expected, PlanOutlineProjector.project(items.reversed()).map { it.item.id })
  }

  @Test
  fun `projects nested items in pre-order with depths and child flags`() {
    val rows =
      PlanOutlineProjector.project(
        listOf(
          item("sibling", parentId = "root", rank = 3),
          item("grandchild", parentId = "child", rank = 2),
          item("root", rank = 0),
          item("child", parentId = "root", rank = 1),
        )
      )

    assertEquals(listOf("root", "child", "grandchild", "sibling"), rows.map { it.item.id })
    assertEquals(listOf(0, 1, 2, 1), rows.map { it.depth })
    assertEquals(listOf(true, true, false, false), rows.map { it.hasChildren })
    assertTrue(rows.none { it.isOrphan })
  }

  @Test
  fun `missing parent makes only the detached root an orphan`() {
    val rows =
      PlanOutlineProjector.project(
        listOf(
          item("child", parentId = "orphan", rank = 2),
          item("orphan", parentId = "missing", rank = 1),
        )
      )

    assertEquals(listOf("orphan", "child"), rows.map { it.item.id })
    assertEquals(listOf(0, 1), rows.map { it.depth })
    assertTrue(rows.first().isOrphan)
    assertFalse(rows.last().isOrphan)
  }

  @Test
  fun `closed cycle renders each member once at deterministic depths`() {
    val rows =
      PlanOutlineProjector.project(
        listOf(
          item("c", parentId = "a", rank = 2),
          item("b", parentId = "c", rank = 1),
          item("a", parentId = "b", rank = 0),
        )
      )

    assertEquals(listOf("a", "c", "b"), rows.map { it.item.id })
    assertEquals(listOf(0, 1, 2), rows.map { it.depth })
    assertEquals(3, rows.map { it.item.id }.distinct().size)
    assertTrue(rows.first().isOrphan)
    assertTrue(rows.drop(1).none { it.isOrphan })
  }

  @Test
  fun `caps visible depth at six without dropping deeper descendants`() {
    val items =
      (0..9).map { index ->
        item(
          id = "node-$index",
          parentId = if (index == 0) null else "node-${index - 1}",
          rank = index.toLong(),
        )
      }

    val rows = PlanOutlineProjector.project(items.reversed())

    assertEquals(items.map { it.id }, rows.map { it.item.id })
    assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 6, 6, 6), rows.map { it.depth })
  }

  @Test
  fun `mixed forest emits every item exactly once`() {
    val items =
      listOf(
        item("root", rank = 0),
        item("child", parentId = "root", rank = 1),
        item("orphan", parentId = "missing", rank = 2),
        item("orphan-child", parentId = "orphan", rank = 3),
        item("cycle-a", parentId = "cycle-b", rank = 4),
        item("cycle-b", parentId = "cycle-a", rank = 5),
        item("self-cycle", parentId = "self-cycle", rank = 6),
      )

    val emittedIds = PlanOutlineProjector.project(items.shuffled()).map { it.item.id }

    assertEquals(items.size, emittedIds.size)
    assertEquals(items.size, emittedIds.distinct().size)
    assertEquals(items.map { it.id }.toSet(), emittedIds.toSet())
  }

  @Test
  fun `collapsing a parent folds its whole branch and counts what it hid`() {
    val items =
      listOf(
        item("root", rank = 0),
        item("child", parentId = "root", rank = 1),
        item("grandchild", parentId = "child", rank = 2),
        item("other", rank = 3),
      )

    val rows =
      PlanOutlineProjector.project(
        items,
        OutlinePresentation(collapsedItemIds = setOf("root")),
      )

    assertEquals(listOf("root", "other"), rows.map { it.item.id })
    val root = rows.first()
    assertTrue(root.isCollapsed)
    assertTrue(root.hasChildren)
    // Both descendants are hidden, and neither may reappear elsewhere as an orphan.
    assertEquals(2, root.hiddenDescendants)
    assertFalse(rows.any { it.item.id == "grandchild" })

    // A collapsed id that owns nothing is inert rather than an error.
    val leafCollapsed =
      PlanOutlineProjector.project(items, OutlinePresentation(collapsedItemIds = setOf("other")))
    assertEquals(4, leafCollapsed.size)
    assertFalse(leafCollapsed.single { it.item.id == "other" }.isCollapsed)
  }

  @Test
  fun `hiding completed work keeps its unfinished subtasks visible`() {
    val rows =
      PlanOutlineProjector.project(
        listOf(
          item("done", rank = 0, progress = 100),
          item("live-child", parentId = "done", rank = 1),
          item("open", rank = 2),
        ),
        OutlinePresentation(hideCompleted = true),
      )

    assertEquals(listOf("live-child", "open"), rows.map { it.item.id })
    // The child lost its parent from view, which the outline already has a word for.
    assertTrue(rows.first { it.item.id == "live-child" }.isOrphan)
    assertEquals(0, rows.first { it.item.id == "live-child" }.depth)
  }

  @Test
  fun `each sort orders siblings deterministically and puts unknowns last`() {
    val items =
      listOf(
        item("no-due", rank = 1, dueAt = null, priority = "low", effortMinutes = null),
        item("late", rank = 2, dueAt = 300, priority = "urgent", effortMinutes = 30),
        item("early", rank = 3, dueAt = 100, priority = "normal", effortMinutes = 240),
      )

    fun ids(sort: OutlineSort) =
      PlanOutlineProjector.project(items, OutlinePresentation(sort = sort)).map { it.item.id }

    assertEquals(listOf("no-due", "late", "early"), ids(OutlineSort.MANUAL))
    assertEquals(listOf("early", "late", "no-due"), ids(OutlineSort.DUE))
    assertEquals(listOf("late", "early", "no-due"), ids(OutlineSort.PRIORITY))
    assertEquals(listOf("early", "late", "no-due"), ids(OutlineSort.EFFORT))
    // Equal keys fall back to the manual order rather than shuffling between redraws.
    val tied = listOf(item("b", rank = 2, dueAt = 100), item("a", rank = 1, dueAt = 100))
    assertEquals(
      listOf("a", "b"),
      PlanOutlineProjector.project(tied, OutlinePresentation(sort = OutlineSort.DUE))
        .map { it.item.id },
    )
    assertEquals(OutlineSort.MANUAL, OutlineSort.byKey("nonsense"))
    assertEquals(OutlineSort.DUE, OutlineSort.byKey("due"))
  }

  @Test
  fun `query matches titles and notes case-insensitively and hides the rest`() {
    val rows =
      PlanOutlineProjector.project(
        listOf(
          item("launch", rank = 0, title = "Launch newsletter"),
          item("copy", parentId = "launch", rank = 1, title = "Draft copy"),
          item("invoice", rank = 2, title = "Invoice the client", notes = "mention the NEWSLETTER deal"),
          item("other", rank = 3, title = "Tidy desk"),
        ),
        OutlinePresentation(query = "newsletter"),
      )

    assertEquals(listOf("launch", "invoice"), rows.map { it.item.id })
    // A non-matching child of a matching parent is hidden, exactly as a hidden parent hides
    // nothing above it: the filter decides what exists, the walk decides how it is arranged.
    assertFalse(rows.first { it.item.id == "launch" }.hasChildren)
    assertFalse(rows.first { it.item.id == "invoice" }.isOrphan)
  }

  @Test
  fun `a matched child of a non-matching parent surfaces as an orphan`() {
    val rows =
      PlanOutlineProjector.project(
        listOf(
          item("inbox", rank = 0, title = "Tidy desk"),
          item("needle", parentId = "inbox", rank = 1, title = "Find the invoice"),
        ),
        OutlinePresentation(query = "invoice"),
      )

    assertEquals(listOf("needle"), rows.map { it.item.id })
    assertTrue(rows.first { it.item.id == "needle" }.isOrphan)
  }

  @Test
  fun `query combines with hiding completed work`() {
    val rows =
      PlanOutlineProjector.project(
        listOf(
          item("done-match", rank = 0, title = "Ship release", progress = 100),
          item("open-match", rank = 1, title = "Plan release notes"),
        ),
        OutlinePresentation(hideCompleted = true, query = "release"),
      )

    assertEquals(listOf("open-match"), rows.map { it.item.id })
  }

  private fun item(
    id: String,
    parentId: String? = null,
    rank: Long = 0,
    createdAt: Long = rank,
    dueAt: Long? = null,
    priority: String = "normal",
    effortMinutes: Int? = null,
    progress: Int = 0,
    title: String = id,
    notes: String? = null,
  ) =
    PlanItem(
      id = id,
      boardId = "board",
      columnId = "column",
      parentId = parentId,
      title = title,
      notes = notes,
      rank = rank,
      dueAt = dueAt,
      effortMinutes = effortMinutes,
      progress = progress,
      priority = priority,
      createdAt = createdAt,
      updatedAt = createdAt,
    )
}
