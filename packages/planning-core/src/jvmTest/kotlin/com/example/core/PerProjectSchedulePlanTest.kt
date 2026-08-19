package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 — per-project schedules: when a request assigns tasks to schedules via
 * scheduleIdByTaskId, each proposal must land inside that task's own working calendar, and the
 * plan's health must assess against the assigned schedules, not one global calendar.
 */
class PerProjectSchedulePlanTest {
  private val zone = TimeZone.getTimeZone("UTC")
  private val weekday =
    WorkingCalendarSpec(
      zoneId = zone.id,
      weeklyWindows =
        (Calendar.MONDAY..Calendar.FRIDAY).map { WorkingWeekWindow(it, 9 * 60, 17 * 60) },
      minimumChunkMinutes = 30,
      maximumChunkMinutes = 120,
      bufferMinutes = 15,
    )
  private val weekend =
    WorkingCalendarSpec(
      zoneId = zone.id,
      weeklyWindows =
        listOf(Calendar.SATURDAY, Calendar.SUNDAY).map { WorkingWeekWindow(it, 9 * 60, 17 * 60) },
      minimumChunkMinutes = 30,
      maximumChunkMinutes = 120,
      bufferMinutes = 15,
    )

  // 2036-02-04 is a Monday.
  private val monday = at(2036, Calendar.FEBRUARY, 4, 0, 0)
  private val weekEnd = monday + 7 * DAY
  private val saturday9 = at(2036, Calendar.FEBRUARY, 9, 9, 0)

  @Test
  fun `assigned tasks are proposed inside their own schedule's working time`() {
    val result =
      AutoPlan.propose(
        items = listOf(item("weekday-task", effort = 60), item("weekend-task", effort = 60)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = weekday,
        schedules = mapOf("weekdays" to weekday, "weekends" to weekend),
        scheduleIdByItemId = mapOf("weekday-task" to "weekdays", "weekend-task" to "weekends"),
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    val proposalsByItem = result.proposals.associateBy { it.itemId }
    val weekdayStart = proposalsByItem.getValue("weekday-task").startAt
    val weekendStart = proposalsByItem.getValue("weekend-task").startAt

    assertFalse("the weekend task must not sit in weekday working time", weekdayStart >= saturday9)
    assertTrue("weekday task starts on a weekday", startInside(weekdayStart, weekday))
    assertTrue("weekend task starts on the weekend", startInside(weekendStart, weekend))
    assertEquals(saturday9, weekendStart)
    assertTrue(result.proposals.none { it.itemId == "weekday-task" && it.startAt >= saturday9 })
    assertEquals(0, result.unplaced.size)
  }

  @Test
  fun `items without an assignment fall back to the default schedule`() {
    val result =
      AutoPlan.propose(
        items = listOf(item("unassigned", effort = 60)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = weekday,
        schedules = mapOf("weekends" to weekend),
        scheduleIdByItemId = emptyMap(),
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    assertEquals(1, result.proposals.size)
    assertTrue(startInside(result.proposals[0].startAt, weekday))
  }

  @Test
  fun `two schedules never double-book the shared timeline`() {
    val result =
      AutoPlan.propose(
        items =
          listOf(
            item("weekday-a", effort = 480),
            item("weekday-b", effort = 480),
            item("weekend-c", effort = 480),
          ),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = weekday,
        schedules = mapOf("weekdays" to weekday, "weekends" to weekend),
        scheduleIdByItemId =
          mapOf("weekday-a" to "weekdays", "weekday-b" to "weekdays", "weekend-c" to "weekends"),
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    val placed = result.proposals.sortedBy { it.startAt }
    placed.zipWithNext().forEach { (a, b) ->
      assertTrue("proposals must never overlap", a.endAt <= b.startAt)
    }
    assertTrue(result.explanation, result.explanation.isNotBlank())
  }

  private fun item(id: String, effort: Int): PlanItem =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      effortMinutes = effort,
      rank = 1,
      createdAt = monday,
      updatedAt = monday,
    )

  private fun startInside(start: Long, spec: WorkingCalendarSpec): Boolean =
    WorkingCalendar.workingIntervals(spec, start, start + MINUTE)
      .any { it.startAt <= start && start < it.endAt }
}

private const val MINUTE = 60_000L
private const val DAY = 24L * 60L * MINUTE
private const val HOUR = 60L * MINUTE

private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
  java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).run {
    clear()
    set(year, month, day, hour, minute, 0)
    timeInMillis
  }
