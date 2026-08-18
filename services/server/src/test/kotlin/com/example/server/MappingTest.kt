package com.example.server

import com.example.contract.IntervalWire
import com.example.contract.PlannerApi
import com.example.contract.PlanningRequest
import com.example.contract.TaskWire
import com.example.contract.WorkScheduleWire
import com.example.contract.WorkScheduleWindowWire
import com.example.contract.PlanProposalWire
import com.example.contract.PlanHealthWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.core.Mapping
import com.example.core.Mapping.EngineInput
import com.example.core.Mapping.toEngineSpec
import kotlinx.serialization.json.Json

/**
 * The contract wire ⇄ engine mapping is the only place the two meet; these tests pin it
 * against the real engine behaviour without a database. The full journey (DB + HTTP) is
 * scripts/journey.sh.
 */
class MappingTest {
  private val monday = 1_735_689_600_000L // 2025-01-01T00:00:00Z (Wednesday)
  private val hour = 3_600_000L

  @Test
  fun `a wire request becomes an engine proposal with a reason`() {
    val request =
      PlanningRequest(
        workspaceId = "ws-1",
        rangeStartMs = monday,
        rangeEndMs = monday + 7 * 24 * hour,
        nowMs = monday,
        items = listOf(TaskWire(id = "t1", boardId = "b1", title = "Write schema", rank = 0, effortMinutes = 90)),
        schedules = weekdays(),
      )
    val result = Mapping.propose(Mapping.toEngine(request, monday), request)

    assertFalse(result.proposals.isEmpty())
    assertEquals("t1", result.proposals.first().itemId)
    assertTrue(result.proposals.first().reason.isNotBlank())
    assertTrue(result.proposals.first().startAt >= monday)
  }

  @Test
  fun `an invalid range is a refusal, never a plan`() {
    val request =
      PlanningRequest(
        workspaceId = "ws-1",
        rangeStartMs = monday,
        rangeEndMs = monday,
        nowMs = monday,
        items = listOf(TaskWire(id = "t1", boardId = "b1", title = "t", rank = 0, effortMinutes = 30)),
        schedules = weekdays(),
      )
    assertNotNull(request.refusalReason())
  }

  @Test
  fun `a hard deadline refuses work that would end after it`() {
    val request =
      PlanningRequest(
        workspaceId = "ws-1",
        rangeStartMs = monday,
        rangeEndMs = monday + 7 * 24 * hour,
        nowMs = monday,
        items =
          listOf(
            TaskWire(id = "t1", boardId = "b1", title = "Due at 08:30, 2 hours of work", rank = 0, effortMinutes = 120, dueAt = monday + 8 * hour + 30 * 60_000L),
          ),
        schedules = weekdays(),
        deadlinePolicy = "HARD",
      )
    val result = Mapping.propose(Mapping.toEngine(request, monday), request)
    assertEquals("t1", result.unplaced.single().itemId)
    assertTrue(result.unplaced.single().reason.isNotBlank())
  }

  @Test
  fun `a soft deadline still plans past the due date`() {
    val request =
      PlanningRequest(
        workspaceId = "ws-1",
        rangeStartMs = monday,
        rangeEndMs = monday + 7 * 24 * hour,
        nowMs = monday,
        items =
          listOf(
            TaskWire(id = "t1", boardId = "b1", title = "Due at 08:30", rank = 0, effortMinutes = 120, dueAt = monday + 8 * hour + 30 * 60_000L),
          ),
        schedules = weekdays(),
        deadlinePolicy = "SOFT",
      )
    val result = Mapping.propose(Mapping.toEngine(request, monday), request)
    assertEquals("t1", result.proposals.single().itemId)
  }

  @Test
  fun `the proposal never overlaps a fixed commitment`() {
    val request =
      PlanningRequest(
        workspaceId = "ws-1",
        rangeStartMs = monday,
        rangeEndMs = monday + 24 * hour,
        nowMs = monday,
        items = listOf(TaskWire(id = "t1", boardId = "b1", title = "Write", rank = 0, effortMinutes = 300)),
        fixedCommitments = listOf(IntervalWire(startAt = monday + 9 * hour, endAt = monday + 17 * hour)),
        schedules = weekdays(),
      )
    val result = Mapping.propose(Mapping.toEngine(request, monday), request)
    result.proposals.forEach { p ->
      assertTrue(
        "proposal $p overlaps the commitment",
        p.endAt <= monday + 9 * hour || p.startAt >= monday + 17 * hour,
      )
    }
  }

  @Test
  fun `the default schedule is chosen, overrides become closed windows`() {
    val schedules =
      listOf(
        WorkScheduleWire(id = "s1", name = "A", timeZoneId = "UTC", isDefault = false, rank = 0, windows = emptyList()),
        WorkScheduleWire(
          id = "s2",
          name = "B",
          timeZoneId = "UTC",
          isDefault = true,
          minimumChunkMinutes = 25,
          maximumChunkMinutes = 90,
          rank = 1,
          windows =
            listOf(
              WorkScheduleWindowWire(id = "w1", dayOfWeek = 1, startMinute = 540, endMinute = 1020, rank = 0),
              WorkScheduleWindowWire(id = "w2", kind = "date_override", localDate = "2025-01-02", startMinute = 0, endMinute = 0, isClosed = true, rank = 1),
            ),
        ),
      )
    val spec = schedules.toEngineSpec(emptyMap())
    assertEquals("UTC", spec.zoneId)
    assertEquals(25, spec.minimumChunkMinutes)
    assertEquals(1, spec.weeklyWindows.size)
    assertEquals(1, spec.overrides.size)
    assertTrue(spec.overrides.single().windows.isEmpty())
  }

  @Test
  fun `the health wire carries the unknown-effort floor`() {
    val request =
      PlanningRequest(
        workspaceId = "ws-1",
        rangeStartMs = monday,
        rangeEndMs = monday + 7 * 24 * hour,
        nowMs = monday,
        items =
          listOf(
            TaskWire(id = "t1", boardId = "b1", title = "No estimate", rank = 0, effortMinutes = null),
          ),
        schedules = weekdays(),
      )
    val result = Mapping.propose(Mapping.toEngine(request, monday), request)
    assertTrue(result.unplaced.isNotEmpty())
    assertEquals(1, result.health.missingEstimateCount)
  }

  @Test
  fun `the wire request round-trips through the contract JSON unchanged`() {
    val request =
      PlanningRequest(
        workspaceId = "ws-9",
        rangeStartMs = monday,
        rangeEndMs = monday + 24 * hour,
        nowMs = monday,
        items = listOf(TaskWire(id = "t1", boardId = "b1", title = "Write", rank = 0, effortMinutes = 45, priority = "urgent")),
        blocks = listOf(com.example.contract.ScheduledBlockWire(id = "b", planItemId = "t2", startAt = monday + 10 * hour, endAt = monday + 11 * hour)),
        schedules = weekdays(),
        preferredOrder = listOf("t1"),
      )
    val json = PlannerApi.json.encodeToString(request)
    val back = PlannerApi.json.decodeFromString<PlanningRequest>(json)
    assertEquals(request, back)
  }

  private fun weekdays(): List<WorkScheduleWire> =
    listOf(
      WorkScheduleWire(
        id = "s1",
        name = "Weekdays",
        timeZoneId = "UTC",
        isDefault = true,
        rank = 0,
        windows =
          (1..5).map { d ->
            WorkScheduleWindowWire(id = "w$d", dayOfWeek = d, startMinute = 540, endMinute = 1020, rank = d.toLong())
          },
      ),
    )
}