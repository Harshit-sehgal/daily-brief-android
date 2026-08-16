package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.ScheduleAnalysis
import com.example.core.WorkingCalendar
import com.example.core.WorkingInterval
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
import com.example.data.model.WorkSchedule
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanMutationType
import com.example.data.repository.PlanRepository
import com.example.data.repository.PlanUndoStatus
import com.example.data.repository.WorkingCalendarMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanItemScheduleInstrumentedTest {
  @Test
  fun assignmentIsObservedJournaledUndoableAndControlsRepositoryBlockValidation() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val suffix = System.nanoTime().toString()
    val now = System.currentTimeMillis()
    val board =
      PlanBoard(
        id = "schedule-board-$suffix",
        name = "Schedule board $suffix",
        nameKey = "schedule board $suffix",
        rank = database.planDao().maxBoardRank() + 1_000_000L,
        createdAt = now,
        updatedAt = now,
      )
    val unavailableSchedule =
      WorkSchedule(
        id = "closed-schedule-$suffix",
        name = "Closed schedule $suffix",
        nameKey = "closed schedule $suffix",
        timeZoneId = "UTC",
        isDefault = false,
        minimumChunkMinutes = 30,
        maximumChunkMinutes = 120,
        rank = now,
        createdAt = now,
        updatedAt = now,
      )
    database.planDao().insertBoard(board)
    database.planDao().insertWorkSchedule(unavailableSchedule)
    // An empty reusable schedule is valid but deliberately offers no schedulable interval.
    WorkingCalendarMapper.fromEntities(unavailableSchedule, emptyList())
    val task =
      repository.saveItem(
        PlanItemInput(boardId = board.id, title = "Assigned task $suffix", effortMinutes = 30)
      )
    var blockId: String? = null
    try {
      val defaultSlot = validDefaultSlot(repository)
      val assigned =
        repository.assignItemWorkingScheduleWithUndo(task.id, unavailableSchedule.id)
      val assignmentMutationId = requireNotNull(assigned.mutationId)

      assertEquals(
        unavailableSchedule.id,
        database.planDao().getPlanItemSchedule(task.id)?.workScheduleId,
      )
      assertEquals(
        unavailableSchedule.id,
        repository
          .observeItemScheduleAssignments(board.id)
          .first { rows -> rows.any { it.planItemId == task.id } }
          .single { it.planItemId == task.id }
          .workScheduleId,
      )
      assertTrue(
        repository
          .observeActiveWorkingCalendars()
          .first { calendars -> calendars.any { it.schedule.id == unavailableSchedule.id } }
          .any { it.schedule.id == unavailableSchedule.id },
      )
      assertEquals(
        PlanMutationType.ITEM_SCHEDULE_ASSIGN,
        database.planDao().getPlanMutation(assignmentMutationId)?.mutationType,
      )

      expectIllegalArgument {
        repository.saveBlock(
          PlanBlockInput(
            planItemId = task.id,
            startAt = defaultSlot.startAt,
            endAt = defaultSlot.endAt,
          )
        )
      }
      assertTrue(database.planDao().getBlocksForItem(task.id).isEmpty())

      val inherited = repository.assignItemWorkingScheduleWithUndo(task.id, null)
      val inheritedMutationId = requireNotNull(inherited.mutationId)
      assertNull(database.planDao().getPlanItemSchedule(task.id))

      // The older command cannot overwrite the newer inherited state.
      assertEquals(PlanUndoStatus.STALE, repository.undoMutation(assignmentMutationId).status)
      // Undoing in command order restores the exact timestamped row, allowing the older Undo.
      assertEquals(PlanUndoStatus.UNDONE, repository.undoMutation(inheritedMutationId).status)
      assertEquals(
        unavailableSchedule.id,
        database.planDao().getPlanItemSchedule(task.id)?.workScheduleId,
      )
      assertEquals(PlanUndoStatus.UNDONE, repository.undoMutation(assignmentMutationId).status)
      assertNull(database.planDao().getPlanItemSchedule(task.id))

      // Only the now-unmapped task falls back to the default calendar.
      val block =
        repository.saveBlock(
          PlanBlockInput(
            planItemId = task.id,
            startAt = defaultSlot.startAt,
            endAt = defaultSlot.endAt,
          )
        )
      blockId = block.id
      assertNotNull(database.planDao().getBlock(block.id))
    } finally {
      blockId?.let { id ->
        database.planDao().getBlock(id)?.let { database.planDao().deleteBlock(it) }
      }
      database.planDao().getPlanItemSchedule(task.id)?.let {
        database.planDao().deletePlanItemSchedule(it)
      }
      database.planDao().getItem(task.id)?.let { database.planDao().deleteItem(it) }
      database.planDao().deleteWorkSchedule(unavailableSchedule)
      database.planDao().deleteBoard(board)
    }
  }

  private suspend fun validDefaultSlot(repository: PlanRepository): WorkingInterval {
    val spec = requireNotNull(repository.observeDefaultWorkingCalendar().first()).spec
    val start = ScheduleAnalysis.startOfDayOffset(System.currentTimeMillis(), 1)
    val end = ScheduleAnalysis.startOfDayOffset(start, 21)
    return WorkingCalendar.workingIntervals(spec, start, end)
      .first { it.durationMinutes >= spec.minimumChunkMinutes }
      .let { interval ->
        WorkingInterval(
          interval.startAt,
          interval.startAt + spec.minimumChunkMinutes * 60_000L,
        )
      }
  }

  private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
    try {
      block()
      fail("Expected an IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
      // Expected fail-closed validation.
    }
  }
}
