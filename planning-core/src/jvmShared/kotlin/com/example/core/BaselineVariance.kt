package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem

/** How one task's schedule differs from the baseline. */
data class TaskVariance(
  val itemId: String,
  val title: String,
  val baselineStartMs: Long?,
  val currentStartMs: Long?,
  val baselineMinutes: Int,
  val currentMinutes: Int,
) {
  /** Positive means later than the baseline; null when there is nothing to compare. */
  val driftMinutes: Long?
    get() =
      if (baselineStartMs == null || currentStartMs == null) null
      else (currentStartMs - baselineStartMs) / 60_000L

  val addedSinceBaseline: Boolean
    get() = baselineStartMs == null && currentStartMs != null

  val removedSinceBaseline: Boolean
    get() = baselineStartMs != null && currentStartMs == null
}

data class BaselineComparison(
  val rows: List<TaskVariance>,
  val summary: String,
) {
  val slipped: List<TaskVariance>
    get() = rows.filter { (it.driftMinutes ?: 0L) > 0L }

  val pulledIn: List<TaskVariance>
    get() = rows.filter { (it.driftMinutes ?: 0L) < 0L }
}

/**
 * What changed since a baseline was taken.
 *
 * The point of a baseline is to make drift visible rather than to make the plan look stable, so
 * this reports movement in both directions, work that appeared afterwards, and work that has since
 * lost its schedule. A task the baseline never knew about is not "on time" — it is new, and says so.
 */
object BaselineVariance {
  /**
   * Movement first, largest slip at the top, then work pulled forward, then work that appeared or
   * disappeared, and finally everything that did not move. Titles break ties so the order is stable
   * and readable rather than stable and arbitrary.
   */
  private val byMovement: Comparator<TaskVariance> =
    compareBy<TaskVariance> { row ->
        when {
          (row.driftMinutes ?: 0L) > 0L -> 0
          (row.driftMinutes ?: 0L) < 0L -> 1
          row.addedSinceBaseline -> 2
          row.removedSinceBaseline -> 3
          else -> 4
        }
      }
      .thenByDescending { kotlin.math.abs(it.driftMinutes ?: 0L) }
      .thenBy { it.title.lowercase() }
      .thenBy { it.itemId }

  fun compare(
    baselineItems: List<PlanItem>,
    baselineBlocks: List<PlanBlock>,
    currentItems: List<PlanItem>,
    currentBlocks: List<PlanBlock>,
  ): BaselineComparison {
    val titles =
      (currentItems + baselineItems).associate { it.id to it.title }
    val baselineById = baselineBlocks.groupBy(PlanBlock::planItemId)
    val currentById = currentBlocks.groupBy(PlanBlock::planItemId)
    val ids = (baselineById.keys + currentById.keys).toList()

    val unordered =
      ids.map { itemId ->
        val before = baselineById[itemId].orEmpty()
        val after = currentById[itemId].orEmpty()
        TaskVariance(
          itemId = itemId,
          title = titles[itemId] ?: "Removed task",
          baselineStartMs = before.minOfOrNull(PlanBlock::startAt),
          currentStartMs = after.minOfOrNull(PlanBlock::startAt),
          baselineMinutes = before.sumOf { ((it.endAt - it.startAt) / 60_000L).toInt() },
          currentMinutes = after.sumOf { ((it.endAt - it.startAt) / 60_000L).toInt() },
        )
      }

    // Ordered by how far the work moved, not by id. The id is a UUID, so sorting by it puts the
    // biggest slip in an arbitrary place and asks the reader to scan for what the report is about.
    val rows = unordered.sortedWith(byMovement)

    val slipped = rows.count { (it.driftMinutes ?: 0L) > 0L }
    val pulled = rows.count { (it.driftMinutes ?: 0L) < 0L }
    val added = rows.count(TaskVariance::addedSinceBaseline)
    val removed = rows.count(TaskVariance::removedSinceBaseline)
    val summary =
      if (rows.isEmpty()) {
        "Nothing was scheduled then or now."
      } else {
        buildList {
            if (slipped > 0) add("$slipped later")
            if (pulled > 0) add("$pulled earlier")
            if (added > 0) add("$added scheduled since")
            if (removed > 0) add("$removed no longer scheduled")
          }
          .ifEmpty { listOf("nothing has moved") }
          .joinToString(", ")
          .replaceFirstChar(Char::uppercase) + "."
      }
    return BaselineComparison(rows, summary)
  }
}
