package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem

/** Pure geometry for app-owned work on the Gantt time spine. */
object PlanGanttLayout {
  enum class RowState {
    SCHEDULED,
    MILESTONE,
    UNSCHEDULED,
    OUTSIDE_RANGE,
  }

  data class Segment(
    val block: PlanBlock,
    val lane: Int,
    val startPosition: Double,
    val endPosition: Double,
    val clippedAtStart: Boolean,
    val clippedAtEnd: Boolean,
  )

  data class Row(
    val item: PlanItem,
    val segments: List<Segment>,
    val milestonePosition: Double?,
    val state: RowState,
    /** Nearest real date for an off-range row; null when there is nothing honest to reveal. */
    val revealAt: Long?,
  ) {
    val laneCount: Int = (segments.maxOfOrNull { it.lane } ?: 0) + 1
  }

  data class Layout(val range: GanttLayout.VisibleRange, val rows: List<Row>)

  /**
   * Keeps every active task visible even when it has no bar in [range]. That is
   * deliberate: an empty canvas must disclose unscheduled or off-range work
   * instead of looking like an empty project.
   */
  fun layout(
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    range: GanttLayout.VisibleRange,
  ): Layout {
    val activeItems =
      items
        .filter { it.archivedAt == null }
        .sortedWith(compareBy<PlanItem> { it.rank }.thenBy { it.createdAt }.thenBy { it.id })
    val itemIds = activeItems.mapTo(mutableSetOf()) { it.id }
    val blocksByItem =
      blocks
        .asSequence()
        .filter { it.planItemId in itemIds && it.endAt > it.startAt }
        .sortedWith(
          compareBy<PlanBlock> { it.planItemId }
            .thenBy { it.position }
            .thenBy { it.startAt }
            .thenBy { it.id }
        )
        .groupBy { it.planItemId }

    val rows =
      activeItems.map { item ->
        if (item.isMilestone) {
          milestoneRow(item, range)
        } else {
          scheduledRow(item, blocksByItem[item.id].orEmpty(), range)
        }
      }
    return Layout(range = range, rows = rows)
  }

  private fun milestoneRow(item: PlanItem, range: GanttLayout.VisibleRange): Row {
    val anchor = item.dueAt
    val visibleAnchor = anchor?.takeIf(range::contains)
    return Row(
      item = item,
      segments = emptyList(),
      milestonePosition = visibleAnchor?.let(range::positionOf),
      state =
        when {
          anchor == null -> RowState.UNSCHEDULED
          visibleAnchor == null -> RowState.OUTSIDE_RANGE
          else -> RowState.MILESTONE
        },
      revealAt = anchor?.takeIf { visibleAnchor == null },
    )
  }

  private fun scheduledRow(
    item: PlanItem,
    blocks: List<PlanBlock>,
    range: GanttLayout.VisibleRange,
  ): Row {
    val laneEnds = mutableListOf<Long>()
    val visibleByTime =
      blocks
        .asSequence()
        .filter { it.endAt > range.startInclusiveMs && it.startAt < range.endExclusiveMs }
        .sortedWith(compareBy<PlanBlock> { it.startAt }.thenBy { it.endAt }.thenBy { it.id })
        .map { block ->
          val reusableLane = laneEnds.indexOfFirst { previousEnd -> block.startAt >= previousEnd }
          val lane = if (reusableLane >= 0) reusableLane else laneEnds.size
          if (reusableLane >= 0) laneEnds[reusableLane] = block.endAt else laneEnds += block.endAt
          val visibleStart = maxOf(block.startAt, range.startInclusiveMs)
          val visibleEnd = minOf(block.endAt, range.endExclusiveMs)
          Segment(
            block = block,
            lane = lane,
            startPosition = range.positionOf(visibleStart),
            endPosition = range.positionOf(visibleEnd),
            clippedAtStart = block.startAt < range.startInclusiveMs,
            clippedAtEnd = block.endAt > range.endExclusiveMs,
          )
        }
        .toList()
    val visible =
      visibleByTime.sortedWith(
        compareBy<Segment> { it.block.position }
          .thenBy { it.block.startAt }
          .thenBy { it.block.id }
      )
    return Row(
      item = item,
      segments = visible,
      milestonePosition = null,
      state =
        when {
          blocks.isEmpty() -> RowState.UNSCHEDULED
          visible.isEmpty() -> RowState.OUTSIDE_RANGE
          else -> RowState.SCHEDULED
        },
      revealAt =
        if (blocks.isNotEmpty() && visible.isEmpty()) {
          blocks.minByOrNull { block -> distanceFromRange(block, range) }?.startAt
        } else {
          null
        },
    )
  }

  private fun distanceFromRange(block: PlanBlock, range: GanttLayout.VisibleRange): Long =
    when {
      block.endAt <= range.startInclusiveMs ->
        runCatching { Math.subtractExact(range.startInclusiveMs, block.endAt) }
          .getOrDefault(Long.MAX_VALUE)
      block.startAt >= range.endExclusiveMs ->
        runCatching { Math.subtractExact(block.startAt, range.endExclusiveMs) }
          .getOrDefault(Long.MAX_VALUE)
      else -> 0L
    }
}
