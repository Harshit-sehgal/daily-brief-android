package com.example.ui.screens

import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem

internal enum class PlanBoardLaneKind {
  INBOX,
  COLUMN,
  NEEDS_ROUTING,
}

/** A stable board lane whose rows retain the outline hierarchy used elsewhere in Plan. */
internal data class PlanBoardLane(
  val key: String,
  val title: String,
  val kind: PlanBoardLaneKind,
  val column: PlanColumn? = null,
  val rows: List<OutlineRow>,
)

/**
 * Projects one board's active tasks into Inbox, ranked workflow columns, and a recovery lane.
 *
 * Unknown or archived column destinations are deliberately kept visible in Needs routing. This
 * avoids silently dropping work when imported or legacy data refers to a column that no longer
 * exists. Hierarchy is lane-local: a parent in another lane is presented as a missing parent by
 * [PlanOutlineProjector] rather than making the child disappear.
 */
internal object PlanBoardProjector {
  fun project(
    boardId: String,
    columns: List<PlanColumn>,
    items: List<PlanItem>,
    collapsedItemIds: Set<String> = emptySet(),
  ): List<PlanBoardLane> {
    val activeColumns =
      columns
        .asSequence()
        .filter { it.boardId == boardId && it.archivedAt == null }
        .sortedWith(compareBy<PlanColumn> { it.rank }.thenBy { it.createdAt }.thenBy { it.id })
        .distinctBy { it.id }
        .toList()
    val knownColumnIds = activeColumns.mapTo(mutableSetOf()) { it.id }
    val activeItems = items.filter { it.boardId == boardId && it.archivedAt == null }

    val lanes =
      mutableListOf(
        PlanBoardLane(
          key = INBOX_KEY,
          title = "Inbox",
          kind = PlanBoardLaneKind.INBOX,
          rows = projectRows(activeItems.filter { it.columnId == null }, collapsedItemIds),
        )
      )

    activeColumns.forEach { column ->
      lanes +=
        PlanBoardLane(
          key = "$COLUMN_KEY_PREFIX${column.id}",
          title = column.name,
          kind = PlanBoardLaneKind.COLUMN,
          column = column,
          rows = projectRows(activeItems.filter { it.columnId == column.id }, collapsedItemIds),
        )
    }

    val needsRouting =
      activeItems.filter { item ->
        val columnId = item.columnId
        columnId != null && columnId !in knownColumnIds
      }
    if (needsRouting.isNotEmpty()) {
      lanes +=
        PlanBoardLane(
          key = NEEDS_ROUTING_KEY,
          title = "Needs routing",
          kind = PlanBoardLaneKind.NEEDS_ROUTING,
          rows = projectRows(needsRouting, collapsedItemIds),
        )
    }

    return lanes
  }

  private fun projectRows(
    items: List<PlanItem>,
    collapsedItemIds: Set<String>,
  ): List<OutlineRow> {
    val rows = PlanOutlineProjector.project(items)
    if (rows.isEmpty() || collapsedItemIds.isEmpty()) return rows

    val byId = items.associateBy { it.id }
    return rows.filterNot { row ->
      hasCollapsedAncestor(row.item, byId, collapsedItemIds)
    }
  }

  private fun hasCollapsedAncestor(
    item: PlanItem,
    byId: Map<String, PlanItem>,
    collapsedItemIds: Set<String>,
  ): Boolean {
    val visited = mutableSetOf(item.id)
    var parentId = item.parentId
    while (parentId != null && visited.add(parentId)) {
      val parent = byId[parentId] ?: return false
      if (parent.id in collapsedItemIds) return true
      parentId = parent.parentId
    }
    return false
  }

  private const val INBOX_KEY = "inbox"
  private const val COLUMN_KEY_PREFIX = "column:"
  private const val NEEDS_ROUTING_KEY = "needs-routing"
}
