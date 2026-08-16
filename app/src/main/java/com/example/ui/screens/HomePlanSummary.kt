package com.example.ui.screens

import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem

internal data class HomePlanLaneSummary(
  val key: String,
  val title: String,
  val count: Int,
  val kind: PlanBoardLaneKind,
)

internal data class HomePlanPreview(
  val item: PlanItem,
  val laneTitle: String,
)

internal data class HomePlanSummary(
  val boardId: String,
  val boardName: String,
  val totalItems: Int,
  val visibleLanes: List<HomePlanLaneSummary>,
  val hiddenLaneCount: Int,
  val preview: List<HomePlanPreview>,
)

/** Projects the exact Board lanes into a bounded Home glance without hiding recovery work. */
internal object HomePlanSummaryProjector {
  fun project(
    board: PlanBoard?,
    columns: List<PlanColumn>,
    items: List<PlanItem>,
  ): HomePlanSummary? {
    board ?: return null
    val lanes = PlanBoardProjector.project(board.id, columns, items)
    val inbox = lanes.first { it.kind == PlanBoardLaneKind.INBOX }
    val needsRouting = lanes.firstOrNull { it.kind == PlanBoardLaneKind.NEEDS_ROUTING }
    val workflow = lanes.filter { it.kind == PlanBoardLaneKind.COLUMN }
    val workflowSlots = (MAX_VISIBLE_LANES - 1 - if (needsRouting == null) 0 else 1).coerceAtLeast(0)
    val visible = buildList {
      add(inbox)
      addAll(workflow.take(workflowSlots))
      needsRouting?.let(::add)
    }

    return HomePlanSummary(
      boardId = board.id,
      boardName = board.name,
      totalItems = lanes.sumOf { it.rows.size },
      visibleLanes =
        visible.map { lane ->
          HomePlanLaneSummary(
            key = lane.key,
            title = lane.title,
            count = lane.rows.size,
            kind = lane.kind,
          )
        },
      hiddenLaneCount = lanes.size - visible.size,
      preview =
        lanes
          .asSequence()
          .flatMap { lane -> lane.rows.asSequence().map { HomePlanPreview(it.item, lane.title) } }
          .take(MAX_PREVIEW_ITEMS)
          .toList(),
    )
  }

  private const val MAX_VISIBLE_LANES = 4
  private const val MAX_PREVIEW_ITEMS = 3
}
