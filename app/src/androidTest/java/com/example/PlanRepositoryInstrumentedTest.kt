package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.model.PlanBoard
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.core.ScheduleAnalysis
import com.example.core.WorkingCalendar
import com.example.core.WorkingInterval
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanRepositoryInstrumentedTest {
  @Test
  fun savingFlexibleWorkNeverCreatesOrMutatesACalendarCommitment() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val eventId = "plan-separation-event-${System.nanoTime()}"
    val taskTitle = "Repository task ${System.nanoTime()}"
    database.eventDao().insertEvents(
      listOf(
        BriefingEvent(
          id = eventId,
          title = "Fixed commitment",
          startTime = 1_777_000_000_000L,
          endTime = 1_777_003_600_000L,
          source = EventSource.MANUAL,
          description = null,
          isDeadline = false,
          isUrgent = false,
        )
      )
    )

    val task = repository.saveItem(PlanItemInput(boardId = board.id, title = taskTitle))
    try {
      assertNotNull(database.planDao().getItem(task.id))
      assertNull(database.eventDao().getEventById(task.id))
      assertEquals("Fixed commitment", database.eventDao().getEventById(eventId)?.title)
    } finally {
      repository.deleteItem(task.id)
      database.eventDao().deleteEventById(eventId)
    }
  }

  @Test
  fun milestonesAndDurationBlocksAreMutuallyExclusiveInBothMutationOrders() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val ordinary =
      repository.saveItem(PlanItemInput(boardId = board.id, title = "Blocked milestone conversion"))
    val milestone =
      repository.saveItem(
        PlanItemInput(boardId = board.id, title = "Point milestone", isMilestone = true)
      )
    try {
      val slot = validWorkingSlots(repository).first()
      repository.saveBlock(
        PlanBlockInput(planItemId = ordinary.id, startAt = slot.startAt, endAt = slot.endAt)
      )
      expectIllegalArgument {
        repository.saveItem(
          PlanItemInput(
            id = ordinary.id,
            boardId = ordinary.boardId,
            columnId = ordinary.columnId,
            title = ordinary.title,
            isMilestone = true,
          )
        )
      }
      expectIllegalArgument {
        repository.saveBlock(
          PlanBlockInput(
            planItemId = milestone.id,
            startAt = slot.startAt,
            endAt = slot.endAt,
          )
        )
      }
    } finally {
      repository.deleteItem(ordinary.id)
      repository.deleteItem(milestone.id)
    }
  }

  @Test
  fun existingTaskCannotMoveToAnotherBoard() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val source = repository.observeBoards().first().first()
    val now = System.currentTimeMillis()
    val suffix = System.nanoTime().toString()
    val target =
      PlanBoard(
        id = "test-board-$suffix",
        name = "Target $suffix",
        nameKey = "target-$suffix",
        rank = database.planDao().maxBoardRank() + 1_000_000L,
        createdAt = now,
        updatedAt = now,
      )
    database.planDao().insertBoard(target)
    val task = repository.saveItem(PlanItemInput(boardId = source.id, title = "Anchored task"))
    try {
      expectIllegalArgument {
        repository.saveItem(
          PlanItemInput(id = task.id, boardId = target.id, title = task.title)
        )
      }
      assertEquals(source.id, database.planDao().getItem(task.id)?.boardId)
    } finally {
      repository.deleteItem(task.id)
      database.planDao().deleteBoard(target)
    }
  }

  @Test
  fun newChildrenCannotDivergeButExistingHierarchyMovesTogetherAcrossSections() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val columns = repository.observeColumns(board.id).first()
    assertTrue("The default catalog must expose at least two workflow columns", columns.size >= 2)
    val parent =
      repository.saveItem(
        PlanItemInput(boardId = board.id, columnId = columns[0].id, title = "Parent")
      )
    var childId: String? = null
    try {
      expectIllegalArgument {
        repository.saveItem(
          PlanItemInput(
            boardId = board.id,
            columnId = columns[1].id,
            parentId = parent.id,
            title = "Misrouted child",
          )
        )
      }
      val child =
        repository.saveItem(
          PlanItemInput(
            boardId = board.id,
            columnId = columns[0].id,
            parentId = parent.id,
            title = "Aligned child",
          )
        )
      childId = child.id
      val movedParent =
        repository.saveItem(
          PlanItemInput(
            id = parent.id,
            boardId = board.id,
            columnId = columns[1].id,
            title = parent.title,
          )
        )

      val storedParent = requireNotNull(database.planDao().getItem(parent.id))
      val movedChild = requireNotNull(database.planDao().getItem(child.id))
      assertEquals(columns[1].id, movedParent.columnId)
      assertEquals(columns[1].id, storedParent.columnId)
      assertEquals(columns[1].id, movedChild.columnId)
      assertEquals(parent.id, movedChild.parentId)
      assertEquals(child.title, movedChild.title)
    } finally {
      childId?.let { repository.deleteItem(it) }
      repository.deleteItem(parent.id)
    }
  }

  @Test
  fun focusedMovesAppendTheWholeHierarchyAndPreserveFreshMetadata() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val columns = repository.observeColumns(board.id).first()
    assertTrue("The default catalog must expose at least two workflow columns", columns.size >= 2)
    val suffix = System.nanoTime()
    val targetAnchor =
      repository.saveItem(
        PlanItemInput(
          boardId = board.id,
          columnId = columns[1].id,
          title = "Target anchor $suffix",
        )
      )
    val parent =
      repository.saveItem(
        PlanItemInput(
          boardId = board.id,
          columnId = columns[0].id,
          title = "Move parent $suffix",
          notes = "Metadata must survive",
          progress = 35,
          priority = "high",
          owner = "Owner",
          locked = true,
        )
      )
    val child =
      repository.saveItem(
        PlanItemInput(
          boardId = board.id,
          columnId = columns[0].id,
          parentId = parent.id,
          title = "Move child $suffix",
        )
      )
    try {
      val inboxMove = repository.moveItemToColumn(parent.id, null)
      assertEquals(2, inboxMove.movedCount)
      assertNull(database.planDao().getItem(parent.id)?.columnId)
      assertNull(database.planDao().getItem(child.id)?.columnId)

      val targetMove = repository.moveItemToColumn(child.id, columns[1].id)
      assertEquals("Moving any hierarchy member moves one disclosed component", 2, targetMove.movedCount)
      val storedParent = requireNotNull(database.planDao().getItem(parent.id))
      val storedChild = requireNotNull(database.planDao().getItem(child.id))
      assertEquals(columns[1].id, storedParent.columnId)
      assertEquals(columns[1].id, storedChild.columnId)
      assertTrue(storedParent.rank > targetAnchor.rank)
      assertTrue(storedChild.rank > storedParent.rank)
      assertEquals("Metadata must survive", storedParent.notes)
      assertEquals(35, storedParent.progress)
      assertEquals("high", storedParent.priority)
      assertEquals("Owner", storedParent.owner)
      assertTrue(storedParent.locked)

      expectIllegalArgument { repository.moveItemToColumn(parent.id, "missing-column") }
      assertEquals(columns[1].id, database.planDao().getItem(parent.id)?.columnId)

      val completed = repository.setItemProgress(parent.id, 100)
      assertEquals(100, completed.progress)
      assertNotNull(completed.completedAt)
      val reopened = repository.setItemProgress(parent.id, 20)
      assertEquals(20, reopened.progress)
      assertNull(reopened.completedAt)
      assertEquals("Metadata must survive", reopened.notes)
    } finally {
      repository.deleteItem(child.id)
      repository.deleteItem(parent.id)
      repository.deleteItem(targetAnchor.id)
    }
  }

  @Test
  fun ganttRelationKeepsEverySplitBlockAndAppendsStablePositions() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val task =
      repository.saveItem(
        PlanItemInput(boardId = board.id, title = "Split schedule ${System.nanoTime()}")
      )
    try {
      val slots = validWorkingSlots(repository)
      val first =
        repository.saveBlock(
          PlanBlockInput(
            planItemId = task.id,
            startAt = slots[0].startAt,
            endAt = slots[0].endAt,
          )
        )
      val second =
        repository.saveBlock(
          PlanBlockInput(
            planItemId = task.id,
            startAt = slots[1].startAt,
            endAt = slots[1].endAt,
          )
        )

      assertEquals(0, first.position)
      assertEquals(1, second.position)
      val relation =
        repository.observeGanttItems(board.id).first().single { it.item.id == task.id }
      assertEquals(setOf(first.id, second.id), relation.blocks.mapTo(mutableSetOf()) { it.id })
      assertFalse("The relation must not be viewport-filtered", relation.blocks.isEmpty())
    } finally {
      repository.deleteItem(task.id)
    }
  }

  @Test
  fun userBlockWritesAreRevalidatedAtomicallyAgainstWorkingTimeAndFixedCommitments() =
    runBlocking {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val database = AppDatabase.getDatabase(context)
      val repository = PlanRepository(context)
      repository.ensureCatalog()
      val board = repository.observeBoards().first().first()
      val task =
        repository.saveItem(
          PlanItemInput(boardId = board.id, title = "Validated block ${System.nanoTime()}")
        )
      val slot = validWorkingSlots(repository).first()
      val eventId = "fixed-validation-${System.nanoTime()}"
      try {
        database.eventDao().insertEvents(
          listOf(
            BriefingEvent(
              id = eventId,
              title = "Fixed collision",
              startTime = slot.startAt,
              endTime = slot.endAt,
              source = EventSource.MANUAL,
              description = null,
              isDeadline = false,
              isUrgent = false,
              kanbanBoard = board.name,
            )
          )
        )
        val historyBefore = repository.observeMutationHistory(board.id, 200).first().size
        expectIllegalArgument {
          repository.saveBlock(
            PlanBlockInput(planItemId = task.id, startAt = slot.startAt, endAt = slot.endAt)
          )
        }
        assertTrue(database.planDao().getBlocksForItem(task.id).isEmpty())
        assertEquals(historyBefore, repository.observeMutationHistory(board.id, 200).first().size)

        database.eventDao().deleteEventById(eventId)
        val saved =
          repository.saveBlock(
            PlanBlockInput(planItemId = task.id, startAt = slot.startAt, endAt = slot.endAt)
          )
        assertNotNull(database.planDao().getBlock(saved.id))
      } finally {
        database.eventDao().deleteEventById(eventId)
        database.planDao().getBlocksForItem(task.id).forEach { database.planDao().deleteBlock(it) }
        database.planDao().getItem(task.id)?.let { database.planDao().deleteItem(it) }
      }
    }

  @Test
  fun archivedTaskBlocksRemainUndoableButDoNotPoisonTheLiveSchedulingGraph() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val database = AppDatabase.getDatabase(context)
    val repository = PlanRepository(context)
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val slot = validWorkingSlots(repository).first()
    val archivedOwner =
      repository.saveItem(
        PlanItemInput(boardId = board.id, title = "Archived schedule ${System.nanoTime()}")
      )
    val archivedBlock =
      repository.saveBlock(
        PlanBlockInput(
          planItemId = archivedOwner.id,
          startAt = slot.startAt,
          endAt = slot.endAt,
        )
      )
    val liveOwner =
      repository.saveItem(
        PlanItemInput(boardId = board.id, title = "Live schedule ${System.nanoTime()}")
      )
    var liveBlockId: String? = null
    try {
      repository.deleteItemWithUndo(archivedOwner.id)
      assertNotNull(database.planDao().getItem(archivedOwner.id)?.archivedAt)
      assertNotNull(database.planDao().getBlock(archivedBlock.id))

      val liveBlock =
        repository.saveBlock(
          PlanBlockInput(
            planItemId = liveOwner.id,
            startAt = slot.startAt,
            endAt = slot.endAt,
          )
        )
      liveBlockId = liveBlock.id
      assertNotNull(database.planDao().getBlock(liveBlock.id))
    } finally {
      liveBlockId?.let { id ->
        database.planDao().getBlock(id)?.let { database.planDao().deleteBlock(it) }
      }
      database.planDao().getBlock(archivedBlock.id)?.let { database.planDao().deleteBlock(it) }
      database.planDao().getItem(liveOwner.id)?.let { database.planDao().deleteItem(it) }
      database.planDao().getItem(archivedOwner.id)?.let { database.planDao().deleteItem(it) }
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

  private suspend fun validWorkingSlots(repository: PlanRepository): List<WorkingInterval> {
    val spec = requireNotNull(repository.observeDefaultWorkingCalendar().first()).spec
    val start = ScheduleAnalysis.startOfDayOffset(System.currentTimeMillis(), 1)
    val end = ScheduleAnalysis.startOfDayOffset(start, 21)
    return WorkingCalendar.workingIntervals(spec, start, end)
      .filter { it.durationMinutes >= spec.minimumChunkMinutes }
      .take(2)
      .map { interval ->
        WorkingInterval(
          interval.startAt,
          interval.startAt + spec.minimumChunkMinutes * 60_000L,
        )
      }
      .also { require(it.size == 2) { "Test schedule needs two working intervals" } }
  }
}
