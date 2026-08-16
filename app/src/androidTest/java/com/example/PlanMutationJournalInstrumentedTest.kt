package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.data.repository.PlanUndoStatus
import com.example.core.ScheduleAnalysis
import com.example.core.WorkingCalendar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanMutationJournalInstrumentedTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun createAndProgressUndoSurviveRepositoryRecreationAndCannotApplyTwice() = runBlocking {
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val created =
      repository.saveItemWithUndo(
        PlanItemInput(boardId = board.id, title = "Journal create ${System.nanoTime()}")
      )
    try {
      val createMutationId = requireNotNull(created.mutationId)
      assertNotNull(database.planDao().getPlanMutation(createMutationId))
      assertNotNull(database.planDao().getItem(created.value.id))

      val progress = repository.setItemProgressWithUndo(created.value.id, 100)
      val progressMutationId = requireNotNull(progress.mutationId)
      assertEquals(100, database.planDao().getItem(created.value.id)?.progress)

      val recreatedRepository = PlanRepository(context)
      assertEquals(
        PlanUndoStatus.UNDONE,
        recreatedRepository.undoMutation(progressMutationId).status,
      )
      assertEquals(0, database.planDao().getItem(created.value.id)?.progress)
      assertEquals(
        PlanUndoStatus.UNAVAILABLE,
        recreatedRepository.undoMutation(progressMutationId).status,
      )

      assertEquals(
        PlanUndoStatus.UNDONE,
        recreatedRepository.undoMutation(createMutationId).status,
      )
      assertNull(database.planDao().getItem(created.value.id))
    } finally {
      database.planDao().getItem(created.value.id)?.let { database.planDao().deleteItem(it) }
    }
  }

  @Test
  fun staleUndoNeverOverwritesANewerEdit() = runBlocking {
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val task =
      repository.saveItem(
        PlanItemInput(boardId = board.id, title = "Stale guard ${System.nanoTime()}")
      )
    try {
      val progress = repository.setItemProgressWithUndo(task.id, 100)
      val mutationId = requireNotNull(progress.mutationId)
      val current = requireNotNull(database.planDao().getItem(task.id))
      database.planDao().updateItem(
        current.copy(title = "Newer title", updatedAt = current.updatedAt + 1)
      )

      assertEquals(PlanUndoStatus.STALE, repository.undoMutation(mutationId).status)
      assertEquals("Newer title", database.planDao().getItem(task.id)?.title)
      assertEquals(100, database.planDao().getItem(task.id)?.progress)
    } finally {
      database.planDao().getItem(task.id)?.let { database.planDao().deleteItem(it) }
    }
  }

  @Test
  fun removalKeepsScheduledDataAndUndoRestoresTheExactTaskRow() = runBlocking {
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val task =
      repository.saveItem(
        PlanItemInput(
          boardId = board.id,
          title = "Reversible removal ${System.nanoTime()}",
          notes = "Keep this metadata",
        )
      )
    val spec = requireNotNull(repository.observeDefaultWorkingCalendar().first()).spec
    val rangeStart = ScheduleAnalysis.startOfDayOffset(System.currentTimeMillis(), 1)
    val rangeEnd = ScheduleAnalysis.startOfDayOffset(rangeStart, 14)
    val working =
      WorkingCalendar.workingIntervals(spec, rangeStart, rangeEnd)
        .first { it.durationMinutes >= spec.minimumChunkMinutes }
    val block =
      repository.saveBlock(
        PlanBlockInput(
          planItemId = task.id,
          startAt = working.startAt,
          endAt = working.startAt + spec.minimumChunkMinutes * 60_000L,
        )
      )
    try {
      val removal = repository.deleteItemWithUndo(task.id)
      assertNotNull(database.planDao().getItem(task.id)?.archivedAt)
      assertNotNull("Archiving must not cascade the schedule", database.planDao().getBlock(block.id))
      assertTrue(repository.observeItems(board.id).first().none { it.id == task.id })

      assertEquals(
        PlanUndoStatus.UNDONE,
        repository.undoMutation(requireNotNull(removal.mutationId)).status,
      )
      assertEquals(task, database.planDao().getItem(task.id))
      assertNotNull(database.planDao().getBlock(block.id))
    } finally {
      database.planDao().getBlock(block.id)?.let { database.planDao().deleteBlock(it) }
      database.planDao().getItem(task.id)?.let { database.planDao().deleteItem(it) }
    }
  }
}
