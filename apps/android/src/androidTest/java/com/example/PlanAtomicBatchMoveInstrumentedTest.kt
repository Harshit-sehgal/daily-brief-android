package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.data.repository.PlanUndoStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanAtomicBatchMoveInstrumentedTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val repository by lazy { PlanRepository(context) }

  @Test
  fun oneBatchMutationMovesEveryHierarchyAndUndoRestoresExactPlacements() = runBlocking {
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val columns = repository.observeColumns(board.id).first()
    val suffix = System.nanoTime().toString(16)
    val root = create(board.id, columns.first().id, "Atomic root $suffix")
    val child = create(board.id, columns.first().id, "Atomic child $suffix", root.id)
    val solo = create(board.id, columns.first().id, "Atomic solo $suffix")
    val ids = listOf(root.id, child.id, solo.id)

    try {
      val before = ids.associateWith { requireNotNull(database.planDao().getItem(it)) }
      val historyBefore = database.planDao().countPlanMutationsForBoard(board.id)
      val result =
        repository.moveItemsToColumnAtomicallyWithUndo(
          itemIds = listOf(root.id, solo.id),
          targetColumnId = columns.last().id,
          expectedBoardId = board.id,
        )

      assertEquals(3, result.value.movedCount)
      assertEquals(setOf(child.id), result.value.includedHierarchyItemIds)
      assertNotNull(result.mutationId)
      assertEquals(historyBefore + 1, database.planDao().countPlanMutationsForBoard(board.id))
      ids.forEach { id ->
        assertEquals(columns.last().id, database.planDao().getItem(id)?.columnId)
      }

      assertEquals(
        PlanUndoStatus.UNDONE,
        repository.undoMutation(requireNotNull(result.mutationId)).status,
      )
      ids.forEach { id -> assertEquals(before.getValue(id), database.planDao().getItem(id)) }
    } finally {
      deleteItems(child, root, solo)
    }
  }

  @Test
  fun invalidCycleAndCrossPlanInputsWriteNothing() = runBlocking {
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val columns = repository.observeColumns(board.id).first()
    val suffix = System.nanoTime().toString(16)
    val root = create(board.id, columns.first().id, "Invalid root $suffix")
    val child = create(board.id, columns.first().id, "Invalid child $suffix", root.id)
    val now = System.currentTimeMillis()
    val otherBoard =
      PlanBoard(
        id = "atomic-board-$suffix",
        name = "Atomic board $suffix",
        nameKey = "atomic-board-$suffix",
        rank = database.planDao().maxBoardRank() + 1_000_000L,
        createdAt = now,
        updatedAt = now,
      )
    val otherColumn =
      PlanColumn(
        id = "atomic-column-$suffix",
        boardId = otherBoard.id,
        name = "Other",
        nameKey = "other-$suffix",
        rank = 1_000_000L,
        createdAt = now,
        updatedAt = now,
      )
    database.planDao().insertBoard(otherBoard)
    database.planDao().insertColumn(otherColumn)
    val other = create(otherBoard.id, otherColumn.id, "Other task $suffix")

    try {
      val placementsBefore = placements(root.id, child.id, other.id)
      val historyBefore = database.planDao().countPlanMutationsForBoard(board.id)
      expectFailure {
        repository.moveItemsToColumnAtomicallyWithUndo(
          listOf(root.id, other.id),
          columns.last().id,
          expectedBoardId = board.id,
        )
      }
      expectFailure {
        repository.moveItemsToColumnAtomicallyWithUndo(
          listOf(root.id),
          otherColumn.id,
          expectedBoardId = board.id,
        )
      }

      database.planDao().updateItem(root.copy(parentId = child.id))
      expectFailure {
        repository.moveItemsToColumnAtomicallyWithUndo(
          listOf(root.id),
          columns.last().id,
          expectedBoardId = board.id,
        )
      }
      database.planDao().updateItem(root)

      assertEquals(placementsBefore, placements(root.id, child.id, other.id))
      assertEquals(historyBefore, database.planDao().countPlanMutationsForBoard(board.id))
    } finally {
      database.planDao().getItem(root.id)?.let { current ->
        if (current.parentId != null) database.planDao().updateItem(current.copy(parentId = null))
      }
      deleteItems(child, root, other)
      database.planDao().deleteColumn(otherColumn)
      database.planDao().deleteBoard(otherBoard)
    }
  }

  @Test
  fun journalFailureRollsBackEveryMovedRow() = runBlocking {
    repository.ensureCatalog()
    val board = repository.observeBoards().first().first()
    val columns = repository.observeColumns(board.id).first()
    val suffix = System.nanoTime().toString(16)
    val first = create(board.id, columns.first().id, "Rollback one $suffix")
    val second = create(board.id, columns.first().id, "Rollback two $suffix")
    val trigger = "abort_atomic_move_$suffix"
    val writable = database.openHelper.writableDatabase

    try {
      val before = placements(first.id, second.id)
      val historyBefore = database.planDao().countPlanMutationsForBoard(board.id)
      writable.execSQL(
        "CREATE TRIGGER $trigger BEFORE INSERT ON plan_mutations " +
          "BEGIN SELECT RAISE(ABORT, 'forced journal failure'); END"
      )
      expectFailure {
        repository.moveItemsToColumnAtomicallyWithUndo(
          listOf(first.id, second.id),
          columns.last().id,
          expectedBoardId = board.id,
        )
      }

      assertEquals(before, placements(first.id, second.id))
      assertEquals(historyBefore, database.planDao().countPlanMutationsForBoard(board.id))
    } finally {
      writable.execSQL("DROP TRIGGER IF EXISTS $trigger")
      deleteItems(first, second)
    }
  }

  private suspend fun create(
    boardId: String,
    columnId: String?,
    title: String,
    parentId: String? = null,
  ): PlanItem =
    repository.saveItem(
      PlanItemInput(
        boardId = boardId,
        columnId = columnId,
        parentId = parentId,
        title = title,
      )
    )

  private suspend fun placements(vararg ids: String): Map<String, Triple<String?, Long, Long>> =
    ids.associateWith { id ->
      val item = requireNotNull(database.planDao().getItem(id))
      Triple(item.columnId, item.rank, item.updatedAt)
    }

  private suspend fun deleteItems(vararg items: PlanItem) {
    items.forEach { item ->
      database.planDao().getItem(item.id)?.let { database.planDao().deleteItem(it) }
    }
  }

  private suspend fun expectFailure(block: suspend () -> Unit) {
    try {
      block()
      fail("Expected the atomic move to fail closed")
    } catch (_: Exception) {
      // Expected: transaction validation or the deliberately failing journal trigger.
    }
  }
}
