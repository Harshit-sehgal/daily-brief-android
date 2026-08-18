package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem

/** One line of the review, with the number and the sentence that explains it. */
data class ReviewFinding(val label: String, val value: String, val detail: String)

data class WeeklyReviewResult(
  val rangeStartMs: Long,
  val rangeEndMs: Long,
  val plannedMinutes: Int,
  val completedMinutes: Int,
  val finishedTaskIds: List<String>,
  val carriedOverTaskIds: List<String>,
  val rescheduleCount: Int,
  val createdTaskCount: Int,
  val findings: List<ReviewFinding>,
  /** Set when the week cannot be described honestly, in which case the numbers are omitted. */
  val unavailableReason: String? = null,
) {
  val isComplete: Boolean
    get() = unavailableReason == null
}

/**
 * What actually happened to the plan in a week.
 *
 * This is a mirror, not a scoreboard: it counts what was planned, what finished, what moved and
 * what carried over, and says plainly when it cannot tell. There is no streak, no score and no
 * encouragement — the point is a person deciding what to do next week, and a number they cannot
 * trust is worse than no number.
 *
 * Effort is only counted where a task states it. Unknown effort is reported as unknown rather than
 * being treated as zero, which would quietly flatter every week that contained an unestimated task.
 */
object WeeklyReview {
  fun summarise(
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    rescheduleCount: Int,
    createdTaskCount: Int,
    rangeStartMs: Long,
    rangeEndMs: Long,
  ): WeeklyReviewResult {
    if (rangeEndMs <= rangeStartMs) {
      return empty(rangeStartMs, rangeEndMs, "A review needs a week that ends after it starts")
    }
    val active = items.filter { it.archivedAt == null }
    val byId = active.associateBy(PlanItem::id)
    val weekBlocks =
      blocks.filter { it.startAt < rangeEndMs && it.endAt > rangeStartMs && it.planItemId in byId }

    val plannedMinutes =
      weekBlocks.sumOf { block ->
        val start = maxOf(block.startAt, rangeStartMs)
        val end = minOf(block.endAt, rangeEndMs)
        ((end - start) / 60_000L).coerceAtLeast(0L).toInt()
      }

    val touchedTaskIds = weekBlocks.map { it.planItemId }.distinct()
    val finished = touchedTaskIds.filter { byId.getValue(it).progress == 100 }.sorted()
    val carriedOver = touchedTaskIds.filter { byId.getValue(it).progress < 100 }.sorted()

    // Only finished work counts as completed effort, and only where the task states its effort.
    val completedMinutes = finished.sumOf { byId.getValue(it).effortMinutes ?: 0 }
    val unknownEffort = finished.count { byId.getValue(it).effortMinutes == null }

    val findings =
      buildList {
        add(
          ReviewFinding(
            label = "Planned",
            value = "${plannedMinutes / 60}h ${plannedMinutes % 60}m",
            detail = "Time this week's blocks occupied, clipped to the week itself.",
          )
        )
        add(
          ReviewFinding(
            label = "Finished",
            value = "${finished.size} ${if (finished.size == 1) "task" else "tasks"}",
            detail =
              if (unknownEffort > 0) {
                "$unknownEffort of them state no effort, so completed time is a floor, not a total."
              } else {
                "Every finished task stated its effort, so completed time is exact."
              },
          )
        )
        add(
          ReviewFinding(
            label = "Carried over",
            value = "${carriedOver.size} ${if (carriedOver.size == 1) "task" else "tasks"}",
            detail = "Worked on this week and still unfinished. These are next week's first claim.",
          )
        )
        add(
          ReviewFinding(
            label = "Rescheduled",
            value = "$rescheduleCount ${if (rescheduleCount == 1) "change" else "changes"}",
            detail =
              if (rescheduleCount > carriedOver.size * 2 && rescheduleCount > 4) {
                "The plan moved more than it progressed. Worth asking what keeps displacing it."
              } else {
                "Block times changed this often while the week ran."
              },
          )
        )
        add(
          ReviewFinding(
            label = "Captured",
            value = "$createdTaskCount new",
            detail = "Tasks created during the week, whether or not they were scheduled.",
          )
        )
      }

    return WeeklyReviewResult(
      rangeStartMs = rangeStartMs,
      rangeEndMs = rangeEndMs,
      plannedMinutes = plannedMinutes,
      completedMinutes = completedMinutes,
      finishedTaskIds = finished,
      carriedOverTaskIds = carriedOver,
      rescheduleCount = rescheduleCount,
      createdTaskCount = createdTaskCount,
      findings = findings,
    )
  }

  private fun empty(startMs: Long, endMs: Long, reason: String) =
    WeeklyReviewResult(
      rangeStartMs = startMs,
      rangeEndMs = endMs,
      plannedMinutes = 0,
      completedMinutes = 0,
      finishedTaskIds = emptyList(),
      carriedOverTaskIds = emptyList(),
      rescheduleCount = 0,
      createdTaskCount = 0,
      findings = emptyList(),
      unavailableReason = reason,
    )
}
