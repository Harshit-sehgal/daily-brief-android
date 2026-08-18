package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.ScheduleAnalysis
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBlock
import com.example.data.model.PlanBoard
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.data.repository.PlanUndoStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a baseline refuses to do.
 *
 * The happy path is covered by the journey; these are the fail-closed edges, which is where a
 * feature that stores a copy of someone's plan can do real damage — restoring one board's schedule
 * onto another, half-applying a corrupt record, or quietly wiping a plan because the snapshot was
 * empty.
 */
@RunWith(AndroidJUnit4::class)
class PlanBaselineRepositoryInstrumentedTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val database = AppDatabase.getDatabase(context)
  private val repository = PlanRepository(context)
  private val token = System.nanoTime().toString(16)
  private val boardIds = mutableListOf<String>()

  @After
  fun cleanUp() {
    runBlocking {
      boardIds.forEach { boardId ->
        database.planDao().getBaselines(boardId).forEach { database.planDao().deleteBaseline(it) }
        database.planDao().getAllItems(boardId).forEach { item ->
          database.planDao().getBlocksForItem(item.id).forEach { database.planDao().deleteBlock(it) }
          database.planDao().deleteItem(item)
        }
        database.openHelper.writableDatabase.execSQL(
          "DELETE FROM plan_mutations WHERE boardId = ?",
          arrayOf(boardId),
        )
        database.planDao().getBoard(boardId)?.let { database.planDao().deleteBoard(it) }
      }
    }
  }

  @Test
  fun aBaselineRefusesEveryInputItCannotHonour() = runBlocking {
    repository.ensureCatalog()
    val boardId = newBoard("Baseline rules")
    val otherBoardId = newBoard("Another plan")
    val task =
      repository.saveItem(PlanItemInput(boardId = boardId, title = "Work $token", effortMinutes = 60))
    scheduleFixture(task.id, days = 2)

    // A name has to be a name.
    listOf("", "   ", "\n").forEach { blank ->
      assertTrue(
        "\"$blank\" is not a baseline name",
        runCatching { repository.captureBaseline(boardId, blank) }.isFailure,
      )
    }
    assertTrue(database.planDao().getBaselines(boardId).isEmpty())

    val baseline = repository.captureBaseline(boardId, "First $token")
    // Case and surrounding space do not make a second baseline a different one.
    assertTrue(runCatching { repository.captureBaseline(boardId, "  first $token  ") }.isFailure)
    assertEquals(1, database.planDao().getBaselines(boardId).size)

    // The same name on a different plan is a different baseline, and is allowed.
    assertNotNull(repository.captureBaseline(otherBoardId, "First $token"))

    // A baseline never restores onto a plan it does not belong to.
    assertTrue(
      runCatching { repository.restoreBaselineWithUndo(baseline.id, otherBoardId) }.isFailure,
    )
    // ...and deleting it through the wrong board reports "not deleted" rather than deleting it.
    assertFalse(repository.deleteBaseline(baseline.id, otherBoardId))
    assertNotNull(database.planDao().getBaseline(baseline.id))

    // An unknown baseline is a refusal, not a crash or a silent no-op that looks like success.
    assertTrue(runCatching { repository.restoreBaselineWithUndo("missing_$token", boardId) }.isFailure)
    assertNull(repository.readBaseline("missing_$token"))
    assertFalse(repository.deleteBaseline("missing_$token", boardId))
  }

  @Test
  fun aCorruptBaselineIsUnreadableAndUnrestorableRatherThanHalfApplied() = runBlocking {
    repository.ensureCatalog()
    val boardId = newBoard("Corrupt baseline")
    val task = repository.saveItem(PlanItemInput(boardId = boardId, title = "Work $token"))
    val block = scheduleFixture(task.id, days = 3)
    val baseline = repository.captureBaseline(boardId, "Corruptible $token")

    // Simulate a damaged row: storage that cannot be trusted must not be partly believed.
    database.openHelper.writableDatabase.execSQL(
      "UPDATE plan_baselines SET blocksJson = ? WHERE id = ?",
      arrayOf("{\"broken\":\"not-a-record\"}", baseline.id),
    )

    assertNull("A damaged baseline reads as unavailable", repository.readBaseline(baseline.id))
    assertTrue(
      "A damaged baseline must not be restored",
      runCatching { repository.restoreBaselineWithUndo(baseline.id, boardId) }.isFailure,
    )
    // The live schedule is untouched by the failed restore.
    val blocks = database.planDao().getBlocksForBoard(boardId)
    assertEquals(1, blocks.size)
    assertEquals(block.startAt, blocks.single().startAt)
  }

  @Test
  fun restoringAnEmptyBaselineOntoAnEmptyPlanIsRefusedInsteadOfLookingLikeSuccess() = runBlocking {
    repository.ensureCatalog()
    val boardId = newBoard("Empty baseline")
    repository.saveItem(PlanItemInput(boardId = boardId, title = "Unscheduled $token"))
    val baseline = repository.captureBaseline(boardId, "Nothing yet $token")

    assertTrue(
      runCatching { repository.restoreBaselineWithUndo(baseline.id, boardId) }.isFailure,
    )

    // But an empty baseline over a schedule that has since been made *does* clear it, as one
    // undoable command — that is a real restore, not an empty one.
    val task = database.planDao().getAllItems(boardId).single()
    scheduleFixture(task.id, days = 4)
    val result = repository.restoreBaselineWithUndo(baseline.id, boardId)
    assertEquals(0, result.value)
    assertTrue(database.planDao().getBlocksForBoard(boardId).isEmpty())

    val mutationId = requireNotNull(result.mutationId)
    repository.undoMutation(mutationId)
    assertEquals(1, database.planDao().getBlocksForBoard(boardId).size)
  }

  @Test
  fun aBaselineIgnoresArchivedWorkAndSurvivesTheTasksItDescribesBeingArchived() = runBlocking {
    repository.ensureCatalog()
    val boardId = newBoard("Archived work")
    val kept =
      repository.saveItem(PlanItemInput(boardId = boardId, title = "Kept $token", effortMinutes = 60))
    val dropped =
      repository.saveItem(PlanItemInput(boardId = boardId, title = "Dropped $token", effortMinutes = 60))
    scheduleFixture(kept.id, days = 5)
    val droppedBlock = scheduleFixture(dropped.id, days = 5, hour = 12)

    val baseline = repository.captureBaseline(boardId, "Both $token")
    val (items, blocks) = requireNotNull(repository.readBaseline(baseline.id))
    assertEquals(2, items.size)
    assertEquals(2, blocks.size)

    // Archiving a task afterwards must leave the baseline readable — it is history, not a view.
    repository.deleteItem(dropped.id)
    val (afterItems, afterBlocks) = requireNotNull(repository.readBaseline(baseline.id))
    assertEquals(2, afterItems.size)
    assertEquals(2, afterBlocks.size)

    // Restoring rewrites the live schedule only. The archived task keeps its own blocks: deleting a
    // task here is a reversible archive, so discarding its schedule would make un-archiving return
    // an empty task.
    val result = repository.restoreBaselineWithUndo(baseline.id, boardId)
    assertEquals(1, result.value)
    val afterRestore = database.planDao().getBlocksForBoard(boardId)
    assertEquals(setOf(kept.id, dropped.id), afterRestore.map { it.planItemId }.toSet())
    assertEquals(
      "the archived task's block is left exactly as it was",
      droppedBlock.startAt,
      afterRestore.single { it.planItemId == dropped.id }.startAt,
    )
  }

  /**
   * A block written straight to storage.
   *
   * These tests are about what a baseline will and will not do; routing the fixtures through block
   * validation would make them pass or fail on whichever weekday they happened to run.
   */
  private suspend fun scheduleFixture(itemId: String, days: Int, hour: Int = 9): PlanBlock {
    val start = ScheduleAnalysis.startOfDayOffset(System.currentTimeMillis(), days) + hour * 3_600_000L
    val block =
      PlanBlock(
        id = "block_${itemId}_${days}_$hour",
        planItemId = itemId,
        startAt = start,
        endAt = start + 3_600_000L,
        createdAt = start,
        updatedAt = start,
      )
    database.planDao().insertBlock(block)
    return block
  }

  private suspend fun newBoard(label: String): String {
    val id = "baseline_${label.filter(Char::isLetterOrDigit)}_$token"
    val now = System.currentTimeMillis()
    database.planDao().insertBoard(
      PlanBoard(
        id = id,
        name = "$label $token",
        nameKey = "${label.lowercase()} $token",
        rank = database.planDao().maxBoardRank() + 10_000L,
        createdAt = now,
        updatedAt = now,
      )
    )
    boardIds += id
    return id
  }
  /**
   * A restore replaces the schedule, and the id sets on either side rarely match: a block deleted
   * since the baseline, or one created since, means the row identities differ. The journal records
   * one entry either way, and Undo has to put back exactly what was there.
   */
  @Test
  fun aRestoreWhoseRowsChangedIdentityIsStillOneUndoableEntry() = runBlocking {
    repository.ensureCatalog()
    val boardId = newBoard("Replaced rows")
    val task = repository.saveItem(PlanItemInput(boardId = boardId, title = "Work $token"))
    val original = scheduleFixture(task.id, days = 2)
    val baseline = repository.captureBaseline(boardId, "Before the rework $token")

    // The block is deleted and a different one is made in its place — new row, new id.
    database.planDao().deleteBlock(original)
    val replacement = scheduleFixture(task.id, days = 6, hour = 14)

    val result = repository.restoreBaselineWithUndo(baseline.id, boardId)
    assertEquals(1, result.value)
    val restored = database.planDao().getBlocksForBoard(boardId)
    assertEquals(1, restored.size)
    assertEquals(original.startAt, restored.single().startAt)

    // Undo puts back the row that was actually there, and does not leave both.
    repository.undoMutation(requireNotNull(result.mutationId))
    val afterUndo = database.planDao().getBlocksForBoard(boardId)
    assertEquals(1, afterUndo.size)
    assertEquals(replacement.startAt, afterUndo.single().startAt)
    assertEquals(replacement.id, afterUndo.single().id)
  }

  /**
   * Applying a whole week's plan is one entry, so undoing it has to remove every block it wrote —
   * not just the first, and not report success while leaving them all in place.
   */
  @Test
  fun undoingAMultiBlockPlanRemovesEveryBlockItWrote() = runBlocking {
    repository.ensureCatalog()
    val boardId = newBoard("Batch undo")
    val first = repository.saveItem(PlanItemInput(boardId = boardId, title = "First $token"))
    val second = repository.saveItem(PlanItemInput(boardId = boardId, title = "Second $token"))
    val start = workingStart()
    val proposals =
      listOf(
        com.example.core.PlanProposal(
          itemId = first.id,
          startAt = start,
          endAt = start + 3_600_000L,
          reason = "First free hour",
        ),
        com.example.core.PlanProposal(
          itemId = second.id,
          startAt = start + 3_600_000L,
          endAt = start + 2 * 3_600_000L,
          reason = "Next free hour",
        ),
      )

    val applied = repository.applyPlanProposals(proposals, boardId)
    assertEquals(2, applied.value.size)
    assertEquals(2, database.planDao().getBlocksForBoard(boardId).size)

    val undo = repository.undoMutation(requireNotNull(applied.mutationId))
    assertEquals(PlanUndoStatus.UNDONE, undo.status)
    assertTrue(
      "every block the plan wrote has to go back",
      database.planDao().getBlocksForBoard(boardId).isEmpty(),
    )
  }

  /** The next working start the default schedule offers, so block validation is not the subject. */
  private suspend fun workingStart(): Long {
    val calendar = requireNotNull(repository.observeDefaultWorkingCalendar().first())
    val from = ScheduleAnalysis.startOfDayOffset(System.currentTimeMillis(), 1)
    val windows =
      com.example.core.WorkingCalendar.workingIntervals(
        calendar.spec,
        from,
        ScheduleAnalysis.startOfDayOffset(from, 14),
      )
    return windows.first { it.endAt - it.startAt >= 2 * 3_600_000L }.startAt
  }
  /**
   * A baseline belongs to its plan. Deleting the plan has to take its baselines with it, exactly as
   * it takes its saved views — otherwise the rows sit in storage forever, describing a board that no
   * longer exists.
   */
  @Test
  fun deletingAPlanTakesItsBaselinesWithIt() = runBlocking {
    repository.ensureCatalog()
    val boardId = newBoard("Doomed plan")
    val task = repository.saveItem(PlanItemInput(boardId = boardId, title = "Work $token"))
    scheduleFixture(task.id, days = 2)
    repository.captureBaseline(boardId, "Kept until the plan goes $token")
    assertEquals(1, database.planDao().getBaselines(boardId).size)

    database.planDao().getBlocksForBoard(boardId).forEach { database.planDao().deleteBlock(it) }
    database.planDao().getAllItems(boardId).forEach { database.planDao().deleteItem(it) }
    database.planDao().deleteBoard(requireNotNull(database.planDao().getBoard(boardId)))

    assertTrue(
      "a deleted plan must not leave its baselines behind",
      database.planDao().getBaselines(boardId).isEmpty(),
    )
  }
}
