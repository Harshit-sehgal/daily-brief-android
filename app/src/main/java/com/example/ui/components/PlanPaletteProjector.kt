package com.example.ui.components

import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem

internal data class PlanPaletteTarget(
  val item: PlanItem,
  val boardName: String,
  val destination: String,
) {
  val paletteId: String = "plan_item_${item.id}"
  val group: String = if (item.isMilestone) "Milestone" else "Task"
  val subtitle: String = "$boardName · $destination"
  val searchTerms: String = "${item.id} $boardName $destination"
}

/** Search targets from the active Plan context, addressed by immutable entity IDs. */
internal object PlanPaletteProjector {
  fun project(
    activeBoardId: String?,
    boards: List<PlanBoard>,
    columns: List<PlanColumn>,
    items: List<PlanItem>,
  ): List<PlanPaletteTarget> {
    val board = boards.firstOrNull { it.id == activeBoardId && it.archivedAt == null } ?: return emptyList()
    val activeColumns =
      columns
        .filter { it.boardId == board.id && it.archivedAt == null }
        .associateBy { it.id }

    return items
      .asSequence()
      .filter { it.boardId == board.id && it.archivedAt == null }
      .sortedWith(compareBy<PlanItem> { it.rank }.thenBy { it.createdAt }.thenBy { it.id })
      .map { item ->
        val destination =
          when (val columnId = item.columnId) {
            null -> "Inbox"
            else -> activeColumns[columnId]?.name ?: "Needs routing"
          }
        PlanPaletteTarget(item = item, boardName = board.name, destination = destination)
      }
      .toList()
  }
}
