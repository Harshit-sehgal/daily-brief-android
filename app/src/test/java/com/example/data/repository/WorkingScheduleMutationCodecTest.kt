package com.example.data.repository

import com.example.data.database.LegacyPlanCatalogBuilder
import com.example.data.model.PlanItemSchedule
import com.example.data.model.WorkSchedule
import com.example.data.model.WorkScheduleWindow
import com.example.data.model.WorkScheduleWindowKind
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingScheduleMutationCodecTest {
  @Test
  fun `aggregate schedule windows and assignment scopes round trip exactly`() {
    val default = schedule("default", "Default week", "UTC", isDefault = true)
    val alternate = schedule("alternate", "Night shift", "America/New_York")
    val assignment = PlanItemSchedule("task-1", alternate.id, createdAt = 7, updatedAt = 8)
    val state =
      WorkingScheduleMutationState(
        scopeId = alternate.id,
        schedules = linkedMapOf(default.id to default, alternate.id to alternate),
        windows =
          linkedMapOf(
            default.id to listOf(window("default-window", default.id, Calendar.MONDAY, 540, 1020)),
            alternate.id to
              listOf(window("alternate-window", alternate.id, Calendar.SUNDAY, 90, 150)),
          ),
        assignmentScopes =
          linkedMapOf(default.id to emptyList(), alternate.id to listOf(assignment)),
      )

    val encoded = PlanMutationCodec.encode(state)
    val decoded =
      PlanMutationCodec.decode(
        mutationType = PlanMutationType.WORK_SCHEDULE_ARCHIVE,
        targetIdsJson = encoded.targetIdsJson,
        stateJson = encoded.stateJson,
      )

    assertEquals(state, decoded)
  }

  @Test
  fun `absent created schedule retains an explicit empty assignment scope`() {
    val state =
      WorkingScheduleMutationState(
        scopeId = "new-schedule",
        schedules = mapOf("new-schedule" to null),
        windows = mapOf("new-schedule" to emptyList()),
        assignmentScopes = mapOf("new-schedule" to emptyList()),
      )

    val encoded = PlanMutationCodec.encode(state)

    assertEquals(
      state,
      PlanMutationCodec.decode(
        mutationType = PlanMutationType.WORK_SCHEDULE_CREATE,
        targetIdsJson = encoded.targetIdsJson,
        stateJson = encoded.stateJson,
      ),
    )
  }

  @Test
  fun `invalid specs and malformed aggregate payloads fail closed`() {
    val invalid =
      schedule("invalid", "Invalid", "Not/AZone").let { schedule ->
        WorkingScheduleMutationState(
          scopeId = schedule.id,
          schedules = mapOf(schedule.id to schedule),
          windows = mapOf(schedule.id to emptyList()),
        )
      }

    assertTrue(runCatching { PlanMutationCodec.encode(invalid) }.isFailure)
    assertNull(
      PlanMutationCodec.decode(
        mutationType = PlanMutationType.WORK_SCHEDULE_UPDATE,
        targetIdsJson = "[\"invalid\"]",
        stateJson = "{}",
      )
    )
  }

  private fun schedule(
    id: String,
    name: String,
    zoneId: String,
    isDefault: Boolean = false,
  ) =
    WorkSchedule(
      id = id,
      name = name,
      nameKey = LegacyPlanCatalogBuilder.nameKey(name),
      timeZoneId = zoneId,
      isDefault = isDefault,
      minimumChunkMinutes = 30,
      maximumChunkMinutes = 120,
      rank = if (isDefault) 1 else 2,
      createdAt = 1,
      updatedAt = 2,
    )

  private fun window(
    id: String,
    scheduleId: String,
    dayOfWeek: Int,
    startMinute: Int,
    endMinute: Int,
  ) =
    WorkScheduleWindow(
      id = id,
      scheduleId = scheduleId,
      kind = WorkScheduleWindowKind.WEEKLY,
      dayOfWeek = dayOfWeek,
      startMinute = startMinute,
      endMinute = endMinute,
      rank = 1,
      createdAt = 1,
      updatedAt = 2,
    )
}
