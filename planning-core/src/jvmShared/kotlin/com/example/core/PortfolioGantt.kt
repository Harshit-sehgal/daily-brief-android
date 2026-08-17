package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem

/** One board's scheduled work on the merged timeline, positioned against [PortfolioTimeline.range]. */
data class PortfolioBar(
  val boardId: String,
  val boardName: String,
  val itemId: String,
  val title: String,
  val startMs: Long,
  val endMs: Long,
  val startPosition: Double,
  val endPosition: Double,
  val clippedAtStart: Boolean,
  val clippedAtEnd: Boolean,
)

/** Every board's blocks on one shared time spine, read-only. */
data class PortfolioTimeline(
  val range: GanttLayout.VisibleRange,
  val bars: List<PortfolioBar>,
) {
  val isEmpty: Boolean
    get() = bars.isEmpty()
}

/**
 * The cross-board view of what is actually on the calendar: all boards' blocks merged onto one
 * time spine, so a person can see where the weeks are crowded without the rollup's per-board
 * totals hiding it. Positioned with the same geometry the Gantt uses ([GanttLayout.positionOf]);
 * it is a view, never an editor — bars on this timeline are read-only by construction.
 */
object PortfolioGantt {
  fun layout(
    boards: List<Pair<String, String>>,
    itemsByBoard: Map<String, List<PlanItem>>,
    blocksByItem: Map<String, List<PlanBlock>>,
    range: GanttLayout.VisibleRange,
  ): PortfolioTimeline {
    val titles =
      itemsByBoard.values.flatten().associate { it.id to it.title }
    val itemIdsByBoard = itemsByBoard.mapValues { (_, items) -> items.map(PlanItem::id).toSet() }
    val bars =
      boards.flatMap { (boardId, boardName) ->
        val ownItemIds = itemIdsByBoard[boardId].orEmpty()
        blocksByItem.entries
          .filter { it.key in ownItemIds }
          .flatMap { (itemId, blocks) ->
            blocks.map { block ->
              PortfolioBar(
                boardId = boardId,
                boardName = boardName,
                itemId = itemId,
                title = titles[itemId].orEmpty(),
                startMs = block.startAt,
                endMs = block.endAt,
                startPosition = range.positionOf(block.startAt),
                endPosition = range.positionOf(block.endAt),
                clippedAtStart = block.startAt <= range.startInclusiveMs,
                clippedAtEnd = block.endAt >= range.endExclusiveMs,
              )
            }
          }
      }
    return PortfolioTimeline(range = range, bars = bars)
  }
}
