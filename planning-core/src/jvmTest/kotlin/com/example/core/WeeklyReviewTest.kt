package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReviewTest {
  private val day = 24 * 60 * 60 * 1000L
  private val weekStart = ScheduleAnalysis.startOfDay(1_760_000_000_000L)
  private val weekEnd = weekStart + 7 * day

  @Test
  fun `planned time counts only the part of a block inside the week`() {
    val result =
      WeeklyReview.summarise(
        items = listOf(item("straddles", effort = 120)),
        blocks =
          listOf(
            // Starts the day before the week and runs two hours into it.
            block("b1", "straddles", weekStart - 2 * 60 * 60_000L, weekStart + 2 * 60 * 60_000L)
          ),
        rescheduleCount = 0,
        createdTaskCount = 0,
        rangeStartMs = weekStart,
        rangeEndMs = weekEnd,
      )

    assertTrue(result.isComplete)
    assertEquals(120, result.plannedMinutes)
    assertEquals(listOf("straddles"), result.carriedOverTaskIds)
  }

  @Test
  fun `finished and carried-over work are separated, and unknown effort is disclosed`() {
    val result =
      WeeklyReview.summarise(
        items =
          listOf(
            item("done-known", effort = 60, progress = 100),
            item("done-unknown", effort = null, progress = 100),
            item("still-going", effort = 90, progress = 40),
          ),
        blocks =
          listOf(
            block("b1", "done-known", weekStart + day, weekStart + day + 60 * 60_000L),
            block("b2", "done-unknown", weekStart + 2 * day, weekStart + 2 * day + 60 * 60_000L),
            block("b3", "still-going", weekStart + 3 * day, weekStart + 3 * day + 90 * 60_000L),
          ),
        rescheduleCount = 1,
        createdTaskCount = 2,
        rangeStartMs = weekStart,
        rangeEndMs = weekEnd,
      )

    assertEquals(listOf("done-known", "done-unknown"), result.finishedTaskIds)
    assertEquals(listOf("still-going"), result.carriedOverTaskIds)
    // Only stated effort is counted, and the shortfall is said out loud.
    assertEquals(60, result.completedMinutes)
    val finished = result.findings.single { it.label == "Finished" }
    assertTrue(finished.detail, finished.detail.contains("state no effort"))
    assertTrue(finished.detail, finished.detail.contains("floor"))
  }

  @Test
  fun `a week that churned more than it progressed says so`() {
    val churned =
      WeeklyReview.summarise(
        items = listOf(item("one", effort = 60), item("two", effort = 60)),
        blocks =
          listOf(
            block("b1", "one", weekStart + day, weekStart + day + 60 * 60_000L),
            block("b2", "two", weekStart + day, weekStart + day + 60 * 60_000L),
          ),
        rescheduleCount = 9,
        createdTaskCount = 0,
        rangeStartMs = weekStart,
        rangeEndMs = weekEnd,
      )
    val note = churned.findings.single { it.label == "Rescheduled" }
    assertTrue(note.detail, note.detail.contains("moved more than it progressed"))

    val steady = churned.copy()
    assertEquals(9, steady.rescheduleCount)

    val calm =
      WeeklyReview.summarise(
        items = listOf(item("one", effort = 60)),
        blocks = listOf(block("b1", "one", weekStart + day, weekStart + day + 60 * 60_000L)),
        rescheduleCount = 1,
        createdTaskCount = 0,
        rangeStartMs = weekStart,
        rangeEndMs = weekEnd,
      )
    assertFalse(
      calm.findings.single { it.label == "Rescheduled" }.detail.contains("moved more"),
    )
  }

  @Test
  fun `archived work and an impossible week are refused rather than guessed`() {
    val withArchived =
      WeeklyReview.summarise(
        items = listOf(item("gone", effort = 60, archivedAt = weekStart)),
        blocks = listOf(block("b1", "gone", weekStart + day, weekStart + day + 60 * 60_000L)),
        rescheduleCount = 0,
        createdTaskCount = 0,
        rangeStartMs = weekStart,
        rangeEndMs = weekEnd,
      )
    // An archived task's block is not this week's work.
    assertEquals(0, withArchived.plannedMinutes)
    assertTrue(withArchived.finishedTaskIds.isEmpty())

    val backwards =
      WeeklyReview.summarise(emptyList(), emptyList(), 0, 0, weekEnd, weekStart)
    assertFalse(backwards.isComplete)
    assertTrue(backwards.findings.isEmpty())
    assertTrue(requireNotNull(backwards.unavailableReason).isNotBlank())
  }

  private fun item(
    id: String,
    effort: Int?,
    progress: Int = 0,
    archivedAt: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      rank = 1,
      effortMinutes = effort,
      progress = progress,
      archivedAt = archivedAt,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(id: String, itemId: String, startAt: Long, endAt: Long) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = startAt,
      endAt = endAt,
      createdAt = 1,
      updatedAt = 1,
    )
}
