package com.example.contract

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Round-trips and the contract's own refusal/validity rules, independent of the golden bytes. */
class ContractRoundTripTest {
  @Test
  fun `a request with every field populated round-trips`() {
    val request =
      PlanningRequest(
        workspaceId = "ws-9",
        rangeStartMs = 1_735_689_600_000L,
        rangeEndMs = 1_735_862_400_000L,
        nowMs = 1_735_689_600_000L,
        items =
          listOf(
            TaskWire(
              id = "t1",
              boardId = "b1",
              columnId = "c1",
              parentId = "p1",
              title = "Ship",
              notes = "n",
              rank = 7,
              startConstraint = 1_735_700_000_000L,
              dueAt = 1_735_800_000_000L,
              effortMinutes = 30,
              progress = 40,
              priority = "urgent",
              owner = "me",
              schedulingMode = "manual",
              locked = true,
              isMilestone = true,
              completedAt = 1_735_810_000_000L,
              archivedAt = null,
            ),
          ),
        blocks =
          listOf(
            ScheduledBlockWire(
              id = "blk1",
              planItemId = "t1",
              startAt = 1_735_700_000_000L,
              endAt = 1_735_701_800_000L,
              position = 2,
              locked = true,
              linkedEventId = "evt1",
            ),
          ),
        fixedCommitments = listOf(IntervalWire(startAt = 1, endAt = 3_600_001)),
        dependencies =
          listOf(
            TaskDependencyWire(
              id = "d1",
              predecessorId = "p1",
              successorId = "t1",
              type = "start_to_start",
              lagMinutes = -15,
            ),
          ),
        scheduleIdByTaskId = mapOf("t1" to "s1"),
        schedules =
          listOf(
            WorkScheduleWire(
              id = "s1",
              name = "Split",
              timeZoneId = "America/New_York",
              isDefault = false,
              minimumChunkMinutes = 15,
              maximumChunkMinutes = 240,
              bufferMinutes = 10,
              rank = 3,
              archivedAt = 1_735_800_000_000L,
              windows =
                listOf(
                  WorkScheduleWindowWire(
                    id = "w1",
                    kind = "date_override",
                    dayOfWeek = null,
                    localDate = "2025-01-02",
                    startMinute = 600,
                    endMinute = 780,
                    isClosed = true,
                    rank = 0,
                  ),
                ),
            ),
          ),
        preferredOrder = listOf("t1", "p1"),
        deadlinePolicy = DeadlinePolicyWire.SOFT,
      )
    val text = PlannerApi.json.encodeToString(PlanningRequest.serializer(), request)
    val back = PlannerApi.json.decodeFromString<PlanningRequest>(text)
    assertEquals(request, back)
  }

  @Test
  fun `an empty request refuses on the range contract`() {
    val bad = PlanningRequest(workspaceId = "ws", rangeStartMs = 10, rangeEndMs = 10, nowMs = 0, items = emptyList())
    assertEquals("rangeEndMs must be greater than rangeStartMs", bad.refusalReason())
    val good = bad.copy(rangeEndMs = 11)
    assertEquals(null, good.refusalReason())
  }

  @Test
  fun `a proposal that is not whole-minute is corrupt, not a plan`() {
    assertThrows(IllegalArgumentException::class.java) {
      PlanProposalWire(itemId = "t", startAt = 30_001, endAt = 90_001, reason = "x")
    }
  }

  @Test
  fun `a proposal that does not span forward is corrupt`() {
    assertThrows(IllegalArgumentException::class.java) {
      PlanProposalWire(itemId = "t", startAt = 60_000, endAt = 60_000, reason = "x")
    }
  }

  @Test
  fun `an all-day event survives the wire as state, never inferred`() {
    val event =
      ExternalEventWire(
        id = "e1",
        title = "Holiday",
        startTime = 1_735_689_600_000L,
        endTime = 1_735_698_240_000L,
        source = "Google Calendar",
        isAllDay = true,
        location = null,
      )
    val back = PlannerApi.json.decodeFromString<ExternalEventWire>(PlannerApi.json.encodeToString(event))
    assertEquals(true, back.isAllDay)
  }

  @Test
  fun `a conflict carries its computed overlap`() {
    val conflict =
      PlanConflictWire(
        first = ExternalEventWire(id = "a", title = "A", startTime = 0, endTime = 60_000, source = "s"),
        second = ExternalEventWire(id = "b", title = "B", startTime = 30_000, endTime = 90_000, source = "s"),
        overlapMs = 30_000,
      )
    val back = PlannerApi.json.decodeFromString<PlanConflictWire>(PlannerApi.json.encodeToString(conflict))
    assertEquals(conflict, back)
  }

  @Test
  fun `a fully populated health block round-trips`() {
    val health =
      PlanHealthWire(
        assessment = PlanHealthAssessmentWire.INCOMPLETE_DATA,
        workingMinutes = 2000,
        capacityAfterCommitmentsMinutes = 1200,
        overloadMinutes = 0,
        missingEstimateCount = 2,
        unscheduledDemandMinutes = 120,
        demands =
          listOf(
            PlanTaskDemandWire(itemId = "t1", remainingEffortMinutes = null, scheduledMinutes = 0, unscheduledMinutes = 90),
          ),
        risks = listOf(PlanRiskWire(kind = "OVERDUE", itemId = "t1", explanation = "due yesterday")),
        repairs = listOf(PlanRepairCandidateWire(action = "move", explanation = "to next week", itemId = "t1")),
        warnings = listOf("two tasks state no effort"),
        explanation = "Missing estimates are reported, never summed as zero",
      )
    val back = PlannerApi.json.decodeFromString<PlanHealthWire>(PlannerApi.json.encodeToString(health))
    assertEquals(health, back)
  }

  @Test
  fun `json without our postures is not the contract`() {
    assertThrows(kotlinx.serialization.SerializationException::class.java) {
      Json.decodeFromString<PlanningRequest>(
        """{"v":1,"workspaceId":"w","rangeStartMs":0,"rangeEndMs":1,"nowMs":0,"items":[],"futureField":1}""",
      )
    }
  }
}