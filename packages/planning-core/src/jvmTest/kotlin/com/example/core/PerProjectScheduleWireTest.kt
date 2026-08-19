package com.example.core

import com.example.contract.PlanningRequest
import com.example.contract.PlanningResult
import com.example.contract.TaskWire
import com.example.contract.WorkScheduleWire
import com.example.contract.WorkScheduleWindowWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 — per-project schedules across the wire: a request that assigns tasks to schedules via
 * scheduleIdByTaskId must plan inside each task's own calendar and assess health against the
 * assigned calendars; a request with no assignments must be byte-for-byte what the single
 * global calendar always produced.
 */
class PerProjectScheduleWireTest {
  private val monday = 1_735_689_600_000L // 2025-01-01T00:00:00Z (Wednesday)
  private val hour = 3_600_000L

  private fun weekdays(): WorkScheduleWire =
    WorkScheduleWire(
      id = "weekdays",
      name = "Weekdays",
      timeZoneId = "UTC",
      isDefault = true,
      rank = 0,
      windows =
        (1..5).map { d ->
          WorkScheduleWindowWire(id = "w$d", dayOfWeek = d, startMinute = 540, endMinute = 1020, rank = d.toLong())
        },
    )

  private fun weekends(): WorkScheduleWire =
    WorkScheduleWire(
      id = "weekends",
      name = "Weekends",
      timeZoneId = "UTC",
      isDefault = false,
      rank = 1,
      windows =
        listOf(6, 7).map { d ->
          WorkScheduleWindowWire(id = "we$d", dayOfWeek = d, startMinute = 540, endMinute = 1020, rank = d.toLong())
        },
    )

  private fun request(
    items: List<TaskWire>,
    scheduleIdByTaskId: Map<String, String> = emptyMap(),
    schedules: List<WorkScheduleWire> = listOf(weekdays(), weekends()),
  ): PlanningRequest =
    PlanningRequest(
      workspaceId = "ws-1",
      rangeStartMs = monday,
      rangeEndMs = monday + 14 * 24 * hour,
      nowMs = monday,
      items = items,
      schedules = schedules,
      scheduleIdByTaskId = scheduleIdByTaskId,
    )

  private fun task(id: String): TaskWire =
    TaskWire(id = id, boardId = "b1", title = id, rank = 0, effortMinutes = 60)

  private fun plan(request: PlanningRequest): PlanningResult =
    Mapping.propose(Mapping.toEngine(request, request.nowMs), request)

  private fun inWorkingTime(request: PlanningRequest, startAt: Long, scheduleId: String): Boolean {
    val spec = Mapping.toEngine(request, request.nowMs).schedules.getValue(scheduleId)
    return WorkingCalendar.workingIntervals(spec, startAt, startAt + hour)
      .any { it.startAt <= startAt && startAt < it.endAt }
  }

  @Test
  fun `assigned tasks are proposed inside their own schedule and health agrees`() {
    val request =
      request(
        items = listOf(task("weekday"), task("weekend")),
        scheduleIdByTaskId = mapOf("weekday" to "weekdays", "weekend" to "weekends"),
      )

    val result = plan(request)

    val byItem = result.proposals.associateBy { it.itemId }
    assertEquals(2, byItem.size)
    assertTrue(inWorkingTime(request, byItem.getValue("weekday").startAt, "weekdays"))
    assertTrue(inWorkingTime(request, byItem.getValue("weekend").startAt, "weekends"))
    assertEquals(0, result.unplaced.size)
    assertTrue(
      "no health warning for an in-schedule plan, got ${result.health.warnings}",
      result.health.warnings.none { it.contains("outside working") },
    )
  }

  @Test
  fun `listing schedules without assigning keeps the single-schedule behaviour exactly`() {
    val multi = request(items = listOf(task("plain")))
    val single = multi.copy(schedules = listOf(weekdays()), scheduleIdByTaskId = emptyMap())

    val multiResult = plan(multi)
    val singleResult = plan(single)

    assertEquals(singleResult.proposals, multiResult.proposals)
    assertEquals(singleResult.explanation, multiResult.explanation)
    assertEquals(singleResult.health, multiResult.health)
  }

  @Test
  fun `health flags a block parked outside its assigned schedule`() {
    val request =
      request(
        items = listOf(task("weekend")),
        scheduleIdByTaskId = mapOf("weekend" to "weekends"),
      )
    // A block the user placed on a weekday, while the task's schedule only runs weekends.
    val monday9 = monday + 9 * hour
    val result =
      plan(
        request.copy(
          blocks =
            listOf(
              com.example.contract.ScheduledBlockWire(
                id = "blk",
                planItemId = "weekend",
                startAt = monday9,
                endAt = monday9 + hour,
              ),
            ),
        ),
      )

    assertTrue(
      "a block outside its schedule must show up in health, got warnings ${result.health.warnings}",
      result.health.warnings.any { it.contains("sit outside") },
    )
  }
}