package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem

/** One board's line in the portfolio, and whether that line can be trusted. */
data class PortfolioRow(
  val boardId: String,
  val boardName: String,
  val openTaskCount: Int,
  val doneTaskCount: Int,
  val statedEffortMinutes: Int,
  val scheduledMinutes: Int,
  val overdueTaskCount: Int,
  val unestimatedTaskCount: Int,
) {
  /** Effort with nothing scheduled against it yet, floored at zero. */
  val unscheduledMinutes: Int
    get() = (statedEffortMinutes - scheduledMinutes).coerceAtLeast(0)

  /** A row with unestimated work cannot claim a complete effort total. */
  val isComplete: Boolean
    get() = unestimatedTaskCount == 0
}

data class PortfolioRollupResult(val rows: List<PortfolioRow>, val note: String) {
  val totalOpenTasks: Int
    get() = rows.sumOf(PortfolioRow::openTaskCount)

  val totalUnscheduledMinutes: Int
    get() = rows.sumOf(PortfolioRow::unscheduledMinutes)

  val isComplete: Boolean
    get() = rows.all(PortfolioRow::isComplete)
}

/**
 * Every plan at once, without pretending the numbers are more solid than they are.
 *
 * A rollup is the easiest place in an app to launder uncertainty: sum enough columns and unknown
 * effort silently becomes zero, which makes an under-planned portfolio look comfortable. So
 * unestimated tasks are counted and reported per board, and the note says plainly when a total is a
 * floor rather than a figure.
 */
object PortfolioRollup {
  fun summarise(
    boards: List<Pair<String, String>>,
    itemsByBoard: Map<String, List<PlanItem>>,
    blocksByItem: Map<String, List<PlanBlock>>,
    nowMs: Long,
  ): PortfolioRollupResult {
    val rows =
      boards.map { (boardId, boardName) ->
        val items = itemsByBoard[boardId].orEmpty().filter { it.archivedAt == null }
        val open = items.filter { it.progress < 100 }
        PortfolioRow(
          boardId = boardId,
          boardName = boardName,
          openTaskCount = open.size,
          doneTaskCount = items.size - open.size,
          statedEffortMinutes = open.sumOf { it.effortMinutes ?: 0 },
          scheduledMinutes =
            open.sumOf { item ->
              blocksByItem[item.id].orEmpty().sumOf { block ->
                ((block.endAt - block.startAt) / 60_000L).toInt()
              }
            },
          overdueTaskCount = open.count { item -> item.dueAt?.let { it < nowMs } == true },
          unestimatedTaskCount = open.count { it.effortMinutes == null },
        )
      }
    val unestimated = rows.sumOf(PortfolioRow::unestimatedTaskCount)
    val note =
      when {
        rows.isEmpty() -> "No plans yet."
        unestimated > 0 ->
          "$unestimated open task${if (unestimated == 1) "" else "s"} state no effort, so every " +
            "effort total here is a floor rather than a total."
        else -> "Every open task states its effort, so these totals are exact."
      }
    return PortfolioRollupResult(rows.sortedBy { it.boardName.lowercase() }, note)
  }
}
