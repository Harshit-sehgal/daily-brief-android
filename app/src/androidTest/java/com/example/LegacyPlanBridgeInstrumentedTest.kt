package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BoardDeleteOutcome
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the temporary name-based Board to stable-ID Plan compatibility bridge in Room. */
@RunWith(AndroidJUnit4::class)
class LegacyPlanBridgeInstrumentedTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val repository by lazy { BriefingRepository(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val asciiBoard = "Work $token"
  private val fullWidthBoard = "Ｗｏｒｋ $token"
  private val renamedBoard = "Renamed 🚀 $token"
  private val eventId = "legacy_plan_bridge_event_$token"
  private val cleanupBoardNames = setOf(asciiBoard, fullWidthBoard, renamedBoard)

  private var boardsBefore: String? = null
  private var activeBoardBefore: String? = null
  private var activePlanBoardBefore: String? = null

  @Before
  fun rememberSettings() = runBlocking {
    boardsBefore = repository.readSetting(SettingKeys.BOARDS)
    activeBoardBefore = repository.readSetting(SettingKeys.ACTIVE_BOARD)
    activePlanBoardBefore = repository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID)
  }

  @After
  fun removeTestRowsAndRestoreSettings() = runBlocking {
    database.eventDao().deleteEventById(eventId)
    cleanupBoardNames.forEach { name ->
      database.planDao().getBoardByName(name)?.let { board ->
        database.planDao().getAllItems(board.id).forEach { database.planDao().deleteItem(it) }
        database.planDao().deleteBoard(board)
      }
      repository.deleteSetting(SettingKeys.columnsForBoard(name))
    }
    restoreSetting(SettingKeys.BOARDS, boardsBefore)
    restoreSetting(SettingKeys.ACTIVE_BOARD, activeBoardBefore)
    restoreSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, activePlanBoardBefore)
  }

  @Test
  fun exactUnicodeBoardsGetDistinctStableIdentitiesAndSynchronizedSelection() = runBlocking {
    assertTrue(repository.createBoard(asciiBoard))
    val asciiPlanBoard = requireNotNull(database.planDao().getBoardByName(asciiBoard))

    assertTrue(repository.createBoard(fullWidthBoard))
    val fullWidthPlanBoard = requireNotNull(database.planDao().getBoardByName(fullWidthBoard))

    assertNotEquals(asciiPlanBoard.id, fullWidthPlanBoard.id)
    assertNotEquals(asciiPlanBoard.nameKey, fullWidthPlanBoard.nameKey)
    assertEquals(fullWidthBoard, repository.readSetting(SettingKeys.ACTIVE_BOARD))
    assertEquals(
      fullWidthPlanBoard.id,
      repository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID),
    )
    assertEquals(
      SettingKeys.DEFAULT_COLUMNS,
      database.planDao().getColumns(fullWidthPlanBoard.id).map { it.name },
    )

    assertTrue(repository.selectBoard(asciiBoard))
    assertEquals(asciiBoard, repository.readSetting(SettingKeys.ACTIVE_BOARD))
    assertEquals(asciiPlanBoard.id, repository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID))
  }

  @Test
  fun planSelectionMaterializesExactEventOnlyBoardAndColumnsInLegacyCatalog() = runBlocking {
    val now = System.currentTimeMillis()
    val planOnlyBoard =
      PlanBoard(
        id = "plan_only_board_$token",
        name = fullWidthBoard,
        nameKey = "plan-only-board-$token",
        rank = database.planDao().maxBoardRank() + 1_000_000L,
        createdAt = now,
        updatedAt = now,
      )
    val exactColumns = listOf("Inbox, 精査", "Ｄｏｎｅ ✅")
    val planColumns =
      exactColumns.mapIndexed { index, name ->
        PlanColumn(
          id = "plan_only_column_${index}_$token",
          boardId = planOnlyBoard.id,
          name = name,
          nameKey = "plan-only-column-$index-$token",
          rank = (index + 1L) * 1_000_000L,
          createdAt = now,
          updatedAt = now,
        )
      }
    database.planDao().insertBoard(planOnlyBoard)
    database.planDao().insertColumns(planColumns)
    val eventBefore = seedEvent(fullWidthBoard, exactColumns.last())
    assertFalse(fullWidthBoard in storedBoards())
    assertNull(repository.readSetting(SettingKeys.columnsForBoard(fullWidthBoard)))

    assertTrue(planRepository.setActiveBoard(planOnlyBoard.id))

    assertTrue(fullWidthBoard in storedBoards())
    assertEquals(exactColumns, storedColumns(fullWidthBoard))
    assertEquals(fullWidthBoard, repository.readSetting(SettingKeys.ACTIVE_BOARD))
    assertEquals(planOnlyBoard.id, repository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID))
    assertEquals(eventBefore, database.eventDao().getEventById(eventId))
    assertEquals(
      exactColumns,
      database.planDao().getColumns(planOnlyBoard.id).map { it.name },
    )
  }

  @Test
  fun boardRenameKeepsPlanIdsAndOnlyMovesTheEventLabel() = runBlocking {
    assertTrue(repository.createBoard(asciiBoard))
    val planBoardBefore = requireNotNull(database.planDao().getBoardByName(asciiBoard))
    val columnIdsBefore = database.planDao().getColumns(planBoardBefore.id).map { it.name to it.id }
    val eventBefore = seedEvent(asciiBoard, "In Progress")

    assertTrue(repository.renameBoard(asciiBoard, renamedBoard))

    val planBoardAfter = requireNotNull(database.planDao().getBoardByName(renamedBoard))
    assertEquals(planBoardBefore.id, planBoardAfter.id)
    assertEquals(
      columnIdsBefore,
      database.planDao().getColumns(planBoardAfter.id).map { it.name to it.id },
    )
    assertNull(database.planDao().getBoardByName(asciiBoard))
    assertEquals(
      eventBefore.copy(kanbanBoard = renamedBoard),
      database.eventDao().getEventById(eventId),
    )
    assertEquals(renamedBoard, repository.readSetting(SettingKeys.ACTIVE_BOARD))
    assertEquals(planBoardAfter.id, repository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID))
  }

  @Test
  fun boardDeletionFailsClosedWhenAnyPlanTaskStillBelongsToIt() = runBlocking {
    assertTrue(repository.createBoard(asciiBoard))
    val planBoard = requireNotNull(database.planDao().getBoardByName(asciiBoard))
    val task =
      planRepository.saveItem(
        PlanItemInput(boardId = planBoard.id, title = "Task that must not disappear")
      )
    val eventBefore = seedEvent(asciiBoard, "Done")

    assertEquals(
      BoardDeleteOutcome.ContainsPlanItems,
      repository.deleteBoardWithOutcome(asciiBoard),
    )

    assertNotNull(database.planDao().getBoardByName(asciiBoard))
    assertEquals(task, database.planDao().getItem(task.id))
    assertEquals(eventBefore, database.eventDao().getEventById(eventId))
    assertTrue(asciiBoard in storedBoards())
    assertEquals(asciiBoard, repository.readSetting(SettingKeys.ACTIVE_BOARD))
    assertEquals(planBoard.id, repository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID))
  }

  @Test
  fun boardDeletionArchivesIdentityAndReroutesStatusExactly() = runBlocking {
    val fallbackBoard = storedBoards().first()
    val fallbackColumns = storedColumns(fallbackBoard)
    assertTrue(repository.createBoard(asciiBoard))
    val deletedPlanBoard = requireNotNull(database.planDao().getBoardByName(asciiBoard))
    val customStatus = "Custom status $token"
    assertFalse(customStatus in fallbackColumns)
    val eventBefore = seedEvent(asciiBoard, customStatus)

    val outcome = repository.deleteBoardWithOutcome(asciiBoard)

    assertEquals(
      BoardDeleteOutcome.Deleted(fallbackBoard),
      outcome,
    )
    val archivedPlanBoard = requireNotNull(database.planDao().getBoard(deletedPlanBoard.id))
    assertNotNull(archivedPlanBoard.archivedAt)
    assertFalse(database.planDao().getBoards().any { it.id == deletedPlanBoard.id })
    assertFalse(asciiBoard in storedBoards())
    assertEquals(
      eventBefore.copy(
        kanbanBoard = fallbackBoard,
        kanbanStatus = fallbackColumns.first(),
      ),
      database.eventDao().getEventById(eventId),
    )
    val fallbackPlanBoard =
      requireNotNull(database.planDao().getBoardByName(fallbackBoard))
    assertEquals(fallbackBoard, repository.readSetting(SettingKeys.ACTIVE_BOARD))
    assertEquals(
      fallbackPlanBoard.id,
      repository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID),
    )
  }

  @Test
  fun columnMutationsKeepExactNamesAndRerouteTasksBeforeDeletion() = runBlocking {
    assertTrue(repository.createBoard(asciiBoard))
    val planBoard = requireNotNull(database.planDao().getBoardByName(asciiBoard))
    val doneColumn = requireNotNull(database.planDao().getColumnByName(planBoard.id, "Done"))

    val fullWidthDone = "Ｄｏｎｅ"
    assertTrue(repository.createColumn(fullWidthDone))
    val fullWidthColumn =
      requireNotNull(database.planDao().getColumnByName(planBoard.id, fullWidthDone))
    assertNotEquals(doneColumn.id, fullWidthColumn.id)
    assertNotEquals(doneColumn.nameKey, fullWidthColumn.nameKey)

    val oldFirstColumn =
      requireNotNull(database.planDao().getColumnByName(planBoard.id, "To Do"))
    val renamedFirstColumn = "Ｔｏ Ｄｏ 🚀"
    val renameEventBefore = seedEvent(asciiBoard, oldFirstColumn.name)
    assertTrue(repository.renameColumn(oldFirstColumn.name, renamedFirstColumn))
    val renamedColumn =
      requireNotNull(database.planDao().getColumnByName(planBoard.id, renamedFirstColumn))
    assertEquals(oldFirstColumn.id, renamedColumn.id)
    assertEquals(
      renameEventBefore.copy(kanbanStatus = renamedFirstColumn),
      database.eventDao().getEventById(eventId),
    )

    val taskBefore =
      planRepository.saveItem(
        PlanItemInput(
          boardId = planBoard.id,
          columnId = doneColumn.id,
          title = "Reroute me without changing my work",
          notes = "Keep these details",
          dueAt = 1_888_000_000_000L,
          effortMinutes = 45,
          owner = "Owner $token",
        )
      )
    val deleteEventBefore = seedEvent(asciiBoard, doneColumn.name)

    assertTrue(repository.deleteColumn(doneColumn.name))

    val taskAfter = requireNotNull(database.planDao().getItem(taskBefore.id))
    assertEquals(
      taskBefore.copy(columnId = renamedColumn.id, updatedAt = taskAfter.updatedAt),
      taskAfter,
    )
    assertNull(database.planDao().getColumn(doneColumn.id))
    assertNotNull(database.planDao().getColumn(fullWidthColumn.id))
    assertEquals(
      deleteEventBefore.copy(kanbanStatus = renamedFirstColumn),
      database.eventDao().getEventById(eventId),
    )
  }

  private suspend fun seedEvent(board: String, column: String): BriefingEvent {
    val event =
      BriefingEvent(
        id = eventId,
        title = "Provider-owned commitment",
        startTime = 1_777_000_000_000L,
        endTime = 1_777_003_600_000L,
        source = EventSource.GOOGLE,
        description = "Provider details",
        isDeadline = true,
        isUrgent = true,
        location = "Meeting room",
        kanbanBoard = board,
        kanbanStatus = column,
      )
    database.eventDao().insertEvents(listOf(event))
    return event
  }

  private suspend fun storedBoards(): List<String> =
    SettingKeys.decodeList(repository.readSetting(SettingKeys.BOARDS))
      ?: listOf(SettingKeys.DEFAULT_BOARD)

  private suspend fun storedColumns(board: String): List<String> =
    SettingKeys.decodeList(repository.readSetting(SettingKeys.columnsForBoard(board)))
      ?: SettingKeys.DEFAULT_COLUMNS

  private suspend fun restoreSetting(key: String, value: String?) {
    if (value == null) repository.deleteSetting(key) else repository.writeSetting(key, value)
  }
}
