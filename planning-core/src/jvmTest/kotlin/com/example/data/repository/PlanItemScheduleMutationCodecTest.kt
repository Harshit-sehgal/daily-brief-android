package com.example.data.repository

import com.example.data.model.PlanItemSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanItemScheduleMutationCodecTest {
  @Test
  fun `assigned and inherited states round trip without changing journal version`() {
    val assigned =
      PlanItemSchedule(
          planItemId = "task_二",
          workScheduleId = "schedule:focus",
          createdAt = 10,
          updatedAt = 20,
        )
        .toMutationState()
    val inherited = PlanItemScheduleState.inherited("task_二")

    listOf(assigned, inherited).forEach { state ->
      val encoded = PlanMutationCodec.encode(state)
      assertEquals(
        state,
        PlanMutationCodec.decode(
          mutationType = PlanMutationType.ITEM_SCHEDULE_ASSIGN,
          targetIdsJson = encoded.targetIdsJson,
          stateJson = encoded.stateJson,
          schemaVersion = 1,
        ),
      )
    }
    assertEquals(1, PlanMutationCodec.SCHEMA_VERSION)
  }

  @Test
  fun `schedule payload does not decode as an unrelated mutation type`() {
    val encoded = PlanMutationCodec.encode(PlanItemScheduleState.inherited("task"))

    assertNull(
      PlanMutationCodec.decode(
        mutationType = PlanMutationType.ITEM_PROGRESS,
        targetIdsJson = encoded.targetIdsJson,
        stateJson = encoded.stateJson,
      )
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun `inherited state cannot retain row timestamps`() {
    PlanMutationCodec.encode(
      PlanItemScheduleState(
        planItemId = "task",
        workScheduleId = null,
        createdAt = 1,
        updatedAt = 2,
      )
    )
  }
}
