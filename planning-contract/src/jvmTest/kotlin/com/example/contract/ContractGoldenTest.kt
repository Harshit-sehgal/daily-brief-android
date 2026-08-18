package com.example.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The golden files pin the wire bytes of a canonical request and result. A re-encode must be
 * byte-identical to the file, so an accidental field rename, reorder or default change fails
 * loudly instead of silently renegotiating the frozen contract.
 */
class ContractGoldenTest {
  private val requestPath = "golden/request-v1.json"
  private val resultPath = "golden/result-v1.json"
  private val capacityRequestPath = "golden/capacity-request-v1.json"
  private val capacityResponsePath = "golden/capacity-response-v1.json"
  private val boardPath = "golden/board-v1.json"

  @Test
  fun `golden request parses to the canonical request`() {
    val canonical = canonicalRequest()
    val parsed = PlannerApi.json.decodeFromString<PlanningRequest>(read(requestPath))
    assertEquals(canonical, parsed)
    assertEquals(canonical.v, PlannerApi.VERSION)
  }

  @Test
  fun `golden request re-encodes byte-identical`() {
    val encoded = PlannerApi.json.encodeToString(PlanningRequest.serializer(), canonicalRequest())
    assertEquals(read(requestPath), encoded)
  }

  @Test
  fun `golden result parses to the canonical result`() {
    val canonical = canonicalResult()
    val parsed = PlannerApi.json.decodeFromString<PlanningResult>(read(resultPath))
    assertEquals(canonical, parsed)
  }

  @Test
  fun `golden result re-encodes byte-identical`() {
    val encoded = PlannerApi.json.encodeToString(PlanningResult.serializer(), canonicalResult())
    assertEquals(read(resultPath), encoded)
  }

  @Test
  fun `the golden request holds the range contract`() {
    assertNull(canonicalRequest().refusalReason())
  }

  @Test
  fun `golden capacity request parses and re-encodes byte-identical`() {
    val canonical = canonicalCapacityRequest()
    val parsed = PlannerApi.json.decodeFromString<CapacityRequestWire>(read(capacityRequestPath))
    assertEquals(canonical, parsed)
    assertEquals(
      read(capacityRequestPath),
      PlannerApi.json.encodeToString(CapacityRequestWire.serializer(), canonical),
    )
  }

  @Test
  fun `golden capacity response parses and re-encodes byte-identical`() {
    val canonical = canonicalCapacityResponse()
    val parsed = PlannerApi.json.decodeFromString<CapacityResponse>(read(capacityResponsePath))
    assertEquals(canonical, parsed)
    assertEquals(
      read(capacityResponsePath),
      PlannerApi.json.encodeToString(CapacityResponse.serializer(), canonical),
    )
  }

  @Test
  fun `golden board parses and re-encodes byte-identical`() {
    val canonical = canonicalBoard()
    val parsed = PlannerApi.json.decodeFromString<BoardWire>(read(boardPath))
    assertEquals(canonical, parsed)
    assertEquals(read(boardPath), PlannerApi.json.encodeToString(BoardWire.serializer(), canonical))
  }

  private fun canonicalRequest(): PlanningRequest =
    PlanningRequest(
      workspaceId = "ws-1",
      rangeStartMs = 1735689600000L,
      rangeEndMs = 1735862400000L,
      nowMs = 1735689600000L,
      items =
        listOf(
          TaskWire(
            id = "task-a",
            boardId = "board-1",
            title = "Write schema",
            rank = 0,
            dueAt = 1735858800000L,
            effortMinutes = 90,
            priority = "high",
          ),
          TaskWire(
            id = "task-b",
            boardId = "board-1",
            title = "Review schema",
            rank = 1,
            effortMinutes = 45,
          ),
        ),
      blocks =
        listOf(
          ScheduledBlockWire(
            id = "block-a",
            planItemId = "task-b",
            startAt = 1735772400000L,
            endAt = 1735775100000L,
          ),
        ),
      fixedCommitments =
        listOf(
          IntervalWire(startAt = 1735776000000L, endAt = 1735783200000L),
        ),
      dependencies =
        listOf(
          TaskDependencyWire(
            id = "dep-1",
            predecessorId = "task-a",
            successorId = "task-b",
          ),
        ),
      scheduleIdByTaskId = mapOf("task-a" to "schedule-1", "task-b" to "schedule-1"),
      schedules =
        listOf(
          WorkScheduleWire(
            id = "schedule-1",
            name = "Core hours",
            timeZoneId = "Europe/Berlin",
            isDefault = true,
            rank = 0,
            windows =
              listOf(
                WorkScheduleWindowWire(
                  id = "window-1",
                  dayOfWeek = 1,
                  startMinute = 540,
                  endMinute = 1020,
                  rank = 0,
                ),
              ),
          ),
        ),
      preferredOrder = listOf("task-b", "task-a"),
    )

  private fun canonicalResult(): PlanningResult =
    PlanningResult(
      proposals =
        listOf(
          PlanProposalWire(
            itemId = "task-a",
            startAt = 1735693200000L,
            endAt = 1735698600000L,
            reason = "working time",
          ),
        ),
      unplaced = listOf(UnplacedTaskWire(itemId = "task-c", reason = "no effort stated")),
      explanation = "Nothing is saved until you apply it. 1 of 2 tasks placed.",
      health =
        PlanHealthWire(
          assessment = PlanHealthAssessmentWire.OVERCOMMITTED,
          workingMinutes = 2400,
          capacityAfterCommitmentsMinutes = 1500,
          overloadMinutes = 300,
          missingEstimateCount = 1,
          unscheduledDemandMinutes = 0,
          demands =
            listOf(
              PlanTaskDemandWire(
                itemId = "task-a",
                remainingEffortMinutes = 90,
                scheduledMinutes = 90,
                unscheduledMinutes = 0,
              ),
            ),
          risks =
            listOf(
              PlanRiskWire(
                kind = "DEADLINE_CAPACITY",
                explanation = "Capacity runs out before the deadline",
              ),
            ),
          warnings = listOf("One task states no effort"),
          explanation = "The week is overcommitted by 5 hours",
        ),
    )

  private fun read(path: String): String =
    requireNotNull(javaClass.classLoader?.getResource(path)) { "missing $path" }.readText()

  /**
   * The capacity golden uses a Monday-containing range so the pinned answer is a real one:
   * the canonical plan's schedule works Monday 09:00–17:00 Europe/Berlin (dayOfWeek 2 in the
   * Android calendar convention the engine uses), so available and spare are exactly 480
   * minutes and the 8 h/week client fits at the boundary. The values are the engine's —
   * `EngineCapacityTest` in planning-core pins the computation itself.
   */
  private fun canonicalCapacityRequest(): CapacityRequestWire {
    val plan = canonicalRequest()
    return CapacityRequestWire(
      plan =
        plan.copy(
          rangeStartMs = 1735516800000L, // Monday 2024-12-30 00:00 UTC
          rangeEndMs = 1735603200000L, // Tuesday 2024-12-31 00:00 UTC
          nowMs = 1735516800000L,
          schedules =
            plan.schedules.map { schedule ->
              schedule.copy(windows = schedule.windows.map { it.copy(dayOfWeek = 2) })
            },
        ),
      newClientHoursPerWeek = 8,
    )
  }

  private fun canonicalCapacityResponse(): CapacityResponse =
    CapacityResponse(
      availableMinutes = 480,
      plannedMinutes = 135,
      spareMinutes = 480,
      verdict = CapacityVerdictWire.CAN_TAKE,
      sentence = "8 h available this week, 2.2 h planned, 8 h spare — an 8 h/week client fits.",
      moves = emptyList(),
    )

  private fun canonicalBoard(): BoardWire {
    val taskA =
      canonicalRequest().items.first().copy(
        boardId = "project-1",
        columnId = "stage-todo",
      )
    return BoardWire(
      project =
        ProjectWire(
          id = "project-1",
          workspaceId = "ws-1",
          name = "Client A",
          isDefault = false,
          rank = 0,
        ),
      stages =
        listOf(
          StageWire(id = "stage-todo", name = "To Do", rank = 0),
          StageWire(id = "stage-progress", name = "In Progress", rank = 1),
          StageWire(id = "stage-done", name = "Done", rank = 2),
        ),
      tasks = listOf(taskA),
    )
  }
}