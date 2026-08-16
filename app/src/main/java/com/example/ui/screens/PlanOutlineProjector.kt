package com.example.ui.screens

import com.example.core.ScheduleAnalysis
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority

internal data class OutlineRow(
  val item: PlanItem,
  val depth: Int,
  val hasChildren: Boolean,
  val isOrphan: Boolean,
  /** True when this row's subtasks are hidden, so the control can say Expand rather than Collapse. */
  val isCollapsed: Boolean = false,
  /** Subtasks hidden underneath this row, counted through the whole branch. */
  val hiddenDescendants: Int = 0,
)

/** How the outline is ordered. Stored in saved views as the lowercase name. */
enum class OutlineSort(val key: String, val label: String) {
  MANUAL("manual", "Plan order"),
  DUE("due", "Due date"),
  PRIORITY("priority", "Priority"),
  EFFORT("effort", "Effort");

  companion object {
    fun byKey(key: String?): OutlineSort = entries.firstOrNull { it.key == key } ?: MANUAL
  }
}

/** How the outline is banded. Stored in saved views as the lowercase name. */
enum class OutlineGrouping(val key: String, val label: String) {
  SECTION("none", "Section"),
  PRIORITY("priority", "Priority"),
  DUE("status", "Due");

  companion object {
    fun byKey(key: String?): OutlineGrouping = entries.firstOrNull { it.key == key } ?: SECTION
  }
}

/**
 * The presentation a person chose for the outline: what to show, in what order, how it is banded,
 * and what is folded away. Deliberately not task data — a saved view may reorder and hide, never
 * edit.
 */
data class OutlinePresentation(
  val sort: OutlineSort = OutlineSort.MANUAL,
  val grouping: OutlineGrouping = OutlineGrouping.SECTION,
  val hideCompleted: Boolean = false,
  val collapsedItemIds: Set<String> = emptySet(),
)

/** A band of rows under one heading. */
internal data class OutlineGroup(val label: String, val rows: List<OutlineRow>)

/** Turns parent IDs into one cycle-safe, deterministic list for the outline renderer. */
internal object PlanOutlineProjector {
  fun project(
    items: List<PlanItem>,
    presentation: OutlinePresentation = OutlinePresentation(),
  ): List<OutlineRow> {
    if (items.isEmpty()) return emptyList()
    // Hiding finished work must not hide its unfinished subtasks; those surface as orphan rows,
    // which is exactly how the outline already reports a parent it cannot place.
    val visible = if (presentation.hideCompleted) items.filter { it.progress < 100 } else items
    if (visible.isEmpty()) return emptyList()

    val ordered = visible.sortedWith(comparatorFor(presentation.sort))
    val byId = ordered.associateBy { it.id }
    val children = ordered.groupBy { it.parentId }
    val emitted = mutableSetOf<String>()
    val rows = mutableListOf<OutlineRow>()

    fun countBranch(item: PlanItem, stack: Set<String>): Int {
      val directChildren = children[item.id].orEmpty().filterNot { it.id in stack }
      return directChildren.sumOf { 1 + countBranch(it, stack + item.id) }
    }

    fun visit(item: PlanItem, depth: Int, stack: Set<String>, orphan: Boolean) {
      if (item.id in emitted || item.id in stack) return
      emitted += item.id
      val directChildren = children[item.id].orEmpty().filterNot { it.id in stack }
      val collapsed = directChildren.isNotEmpty() && item.id in presentation.collapsedItemIds
      rows +=
        OutlineRow(
          item = item,
          depth = depth.coerceAtMost(MAX_VISIBLE_DEPTH),
          hasChildren = directChildren.isNotEmpty(),
          isOrphan = orphan,
          isCollapsed = collapsed,
          hiddenDescendants = if (collapsed) countBranch(item, stack) else 0,
        )
      if (collapsed) {
        // Mark the branch emitted so a collapsed subtask cannot reappear as an orphan below.
        fun swallow(parent: PlanItem, inner: Set<String>) {
          children[parent.id].orEmpty().filterNot { it.id in inner }.forEach { child ->
            if (emitted.add(child.id)) swallow(child, inner + parent.id)
          }
        }
        swallow(item, stack + item.id)
        return
      }
      val nextStack = stack + item.id
      directChildren.forEach { child -> visit(child, depth + 1, nextStack, orphan = false) }
    }

    ordered
      .filter { it.parentId == null || it.parentId !in byId }
      .forEach { root -> visit(root, 0, emptySet(), orphan = root.parentId != null) }

    // A closed cycle has no root. Render each remaining member once at the top
    // level rather than dropping work or recursing forever.
    ordered.filterNot { it.id in emitted }.forEach { item ->
      visit(item, 0, emptySet(), orphan = true)
    }
    return rows
  }

  /** Every order ends in the same tiebreak, so a redraw never shuffles equal rows. */
  private fun comparatorFor(sort: OutlineSort): Comparator<PlanItem> {
    val tiebreak = compareBy<PlanItem>({ it.rank }, { it.createdAt }, { it.id })
    return when (sort) {
      OutlineSort.MANUAL -> tiebreak
      // Undated work sorts last rather than pretending to be due at the epoch.
      OutlineSort.DUE -> compareBy<PlanItem> { it.dueAt ?: Long.MAX_VALUE }.then(tiebreak)
      OutlineSort.PRIORITY -> compareBy<PlanItem> { priorityOrder(it.priority) }.then(tiebreak)
      // Largest first: the work worth planning around is the work that takes the day.
      OutlineSort.EFFORT ->
        compareByDescending<PlanItem> { it.effortMinutes ?: -1 }.then(tiebreak)
    }
  }

  /**
   * Bands already-projected rows under headings.
   *
   * Grouping is applied after projection so a subtask never leaves its parent: a row moves band only
   * when it is a root, and a branch travels whole. Anything else would show a hierarchy that is not
   * the hierarchy.
   */
  internal fun group(rows: List<OutlineRow>, grouping: OutlineGrouping, nowMs: Long): List<OutlineGroup> {
    if (grouping == OutlineGrouping.SECTION || rows.isEmpty()) return emptyList()
    val branches = mutableListOf<Pair<OutlineRow, MutableList<OutlineRow>>>()
    rows.forEach { row ->
      if (row.depth == 0 || branches.isEmpty()) branches += row to mutableListOf(row)
      else branches.last().second += row
    }
    val ordered = LinkedHashMap<String, MutableList<OutlineRow>>()
    labelsFor(grouping).forEach { ordered[it] = mutableListOf() }
    branches.forEach { (root, branch) ->
      ordered.getOrPut(labelFor(root.item, grouping, nowMs)) { mutableListOf() } += branch
    }
    return ordered.filterValues { it.isNotEmpty() }.map { (label, banded) -> OutlineGroup(label, banded) }
  }

  private fun labelsFor(grouping: OutlineGrouping): List<String> =
    when (grouping) {
      OutlineGrouping.PRIORITY -> listOf("Urgent", "High", "Normal", "Low")
      OutlineGrouping.DUE -> listOf("Overdue", "Today", "This week", "Later", "No due date")
      OutlineGrouping.SECTION -> emptyList()
    }

  private fun labelFor(item: PlanItem, grouping: OutlineGrouping, nowMs: Long): String =
    when (grouping) {
      OutlineGrouping.PRIORITY ->
        when (item.priority) {
          PlanPriority.URGENT -> "Urgent"
          PlanPriority.HIGH -> "High"
          PlanPriority.LOW -> "Low"
          else -> "Normal"
        }
      OutlineGrouping.DUE -> {
        val due = item.dueAt
        val startOfToday = ScheduleAnalysis.startOfDay(nowMs)
        when {
          due == null -> "No due date"
          due < startOfToday -> "Overdue"
          due < ScheduleAnalysis.startOfDayOffset(startOfToday, 1) -> "Today"
          due < ScheduleAnalysis.startOfDayOffset(startOfToday, 7) -> "This week"
          else -> "Later"
        }
      }
      OutlineGrouping.SECTION -> ""
    }

  private fun priorityOrder(priority: String): Int =
    when (priority) {
      PlanPriority.URGENT -> 0
      PlanPriority.HIGH -> 1
      PlanPriority.NORMAL -> 2
      PlanPriority.LOW -> 3
      else -> 4
    }

  private const val MAX_VISIBLE_DEPTH = 6
}
