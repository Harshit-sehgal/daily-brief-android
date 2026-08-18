package com.example.core

import com.example.contract.CapacityRequestWire
import com.example.contract.CapacityVerdictWire
import com.example.contract.IntervalWire
import com.example.contract.PlanningRequest
import com.example.contract.ScheduledBlockWire
import com.example.contract.TaskDependencyWire
import com.example.contract.TaskWire
import com.example.contract.WorkScheduleWindowWire
import com.example.contract.WorkScheduleWire
import com.example.core.Mapping.answerCapacity
import com.example.core.Mapping.toWire
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The capacity golden files pin wire bytes; this test pins the numbers in them to the engine,
 * so the golden never records a fantasy answer. The canonical request's schedule works Monday
 * 09:00–17:00 Europe/Berlin and the range is that Monday, so available and spare are exactly
 * 480 minutes and an 8 h/week client fits at the boundary.
 */
class EngineCapacityTest {
  private val monday = 1_735_516_800_000L // 2024-12-30T00:00:00Z

  @Test
  fun `the canonical capacity request answers exactly the golden response`() {
    val result = canonicalRequest().answerCapacity()

    assertEquals(480, result.availableMinutes)
    assertEquals(135, result.plannedMinutes)
    assertEquals(480, result.spareMinutes)
    assertEquals(CapacityVerdict.CAN_TAKE, result.verdict)
    assertEquals("8 h available this week, 2.2 h planned, 8 h spare — an 8 h/week client fits.", result.sentence)
    assertEquals(emptyList<CapacityMove>(), result.moves)
    assertEquals(CapacityVerdictWire.CAN_TAKE, result.toWire().verdict)
  }

  @Test
  fun `an item with an unknown schedule falls back to the default schedule`() {
    val request = canonicalRequest().copy(plan = canonicalRequest().plan.copy(scheduleIdByTaskId = emptyMap()))
    val result = request.answerCapacity()
    assertEquals(CapacityVerdict.CAN_TAKE, result.verdict)
    assertEquals(480, result.availableMinutes)
  }

  private fun canonicalRequest(): CapacityRequestWire =
    CapacityRequestWire(
      plan =
        PlanningRequest(
          workspaceId = "ws-1",
          rangeStartMs = monday,
          rangeEndMs = monday + 24 * 3_600_000L,
          nowMs = monday,
          items =
            listOf(
              TaskWire(id = "task-a", boardId = "board-1", title = "Write schema", rank = 0, dueAt = monday + 3 * 24 * 3_600_000L + 21 * 3_600_000L, effortMinutes = 90, priority = "high"),
              TaskWire(id = "task-b", boardId = "board-1", title = "Review schema", rank = 1, effortMinutes = 45),
            ),
          blocks = listOf(ScheduledBlockWire(id = "block-a", planItemId = "task-b", startAt = monday + 2 * 24 * 3_600_000L + 11 * 3_600_000L, endAt = monday + 2 * 24 * 3_600_000L + 11 * 3_600_000L + 45 * 60_000L)),
          fixedCommitments = listOf(IntervalWire(startAt = monday + 2 * 24 * 3_600_000L + 16 * 3_600_000L, endAt = monday + 2 * 24 * 3_600_000L + 18 * 3_600_000L)),
          dependencies = listOf(TaskDependencyWire(id = "dep-1", predecessorId = "task-a", successorId = "task-b")),
          scheduleIdByTaskId = mapOf("task-a" to "schedule-1", "task-b" to "schedule-1"),
          schedules =
            listOf(
              WorkScheduleWire(
                id = "schedule-1",
                name = "Core hours",
                timeZoneId = "Europe/Berlin",
                isDefault = true,
                rank = 0,
                windows = listOf(WorkScheduleWindowWire(id = "window-1", dayOfWeek = 2, startMinute = 540, endMinute = 1020, rank = 0)),
              ),
            ),
          preferredOrder = listOf("task-b", "task-a"),
        ),
      newClientHoursPerWeek = 8,
    )
}