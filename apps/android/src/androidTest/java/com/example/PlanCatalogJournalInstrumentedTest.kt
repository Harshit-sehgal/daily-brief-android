package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority
import com.example.data.model.PlanSchedulingMode
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanRepository
import com.example.data.repository.PlanUndoStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanCatalogJournalInstrumentedTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database
    get() = AppDatabase.getDatabase(context)

  @Test
  fun unicodeBoardRenameUndoRestoresCatalogSelectionSettingsAndEventsExactly() = runBlocking {
    val suffix = System.nanoTime().toString()
    val oldName = "计划 | Café e\u0301 $suffix"
    val newName = "Ｐｌａｎ, 🚀 | $suffix"
    val eventId = "catalog_unicode_event_$suffix"
    val briefingRepository = BriefingRepository(context)
    val planRepository = PlanRepository(context)
    planRepository.ensureCatalog()

    val creation = briefingRepository.createBoardWithUndo(oldName)
    assertTrue(creation.value)
    val createdBoard = requireNotNull(database.planDao().getBoardByName(oldName))
    val originalColumnsSetting =
      briefingRepository.readSetting(SettingKeys.columnsForBoard(oldName))
    val originalEvent =
      BriefingEvent(
        id = eventId,
        title = "Unicode catalog subject",
        startTime = 1_800_000_000_000L,
        endTime = 1_800_000_900_000L,
        source = EventSource.MANUAL,
        description = "Keeps commas, | delimiters, emoji 🧭, and combining e\u0301",
        isDeadline = true,
        isUrgent = false,
        location = "東京",
        kanbanStatus = "In Progress",
        kanbanBoard = oldName,
        userEdited = true,
      )
    database.eventDao().insertEvents(listOf(originalEvent))

    val rename = briefingRepository.renameBoardWithUndo(oldName, newName)
    try {
      assertTrue(rename.value)
      val renamed = requireNotNull(database.planDao().getBoard(createdBoard.id))
      assertEquals(newName, renamed.name)
      assertEquals(newName, database.eventDao().getEventById(eventId)?.kanbanBoard)
      assertEquals(newName, briefingRepository.readSetting(SettingKeys.ACTIVE_BOARD))
      assertNull(briefingRepository.readSetting(SettingKeys.columnsForBoard(oldName)))
      assertEquals(
        originalColumnsSetting,
        briefingRepository.readSetting(SettingKeys.columnsForBoard(newName)),
      )

      val recreatedRepository = PlanRepository(context)
      assertEquals(
        PlanUndoStatus.UNDONE,
        recreatedRepository.undoMutation(requireNotNull(rename.mutationId)).status,
      )
      assertEquals(createdBoard, database.planDao().getBoard(createdBoard.id))
      assertEquals(originalEvent, database.eventDao().getEventById(eventId))
      assertEquals(oldName, briefingRepository.readSetting(SettingKeys.ACTIVE_BOARD))
      assertEquals(createdBoard.id, briefingRepository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID))
      assertEquals(
        originalColumnsSetting,
        briefingRepository.readSetting(SettingKeys.columnsForBoard(oldName)),
      )
      assertNull(briefingRepository.readSetting(SettingKeys.columnsForBoard(newName)))
    } finally {
      // If an assertion failed before Undo, make the rename inverse eligible before removing the
      // board created by this test. Both commands are durable and safe to retry.
      rename.mutationId?.let { PlanRepository(context).undoMutation(it) }
      database.eventDao().deleteEventById(eventId)
      creation.mutationId?.let { PlanRepository(context).undoMutation(it) }
    }
  }

  @Test
  fun columnDeleteUndoRestoresExactColumnTaskEventAndLegacyList() = runBlocking {
    val suffix = System.nanoTime().toString()
    val boardName = "Catalog column board $suffix"
    val eventId = "catalog_column_event_$suffix"
    val taskId = "catalog_column_task_$suffix"
    val briefingRepository = BriefingRepository(context)
    val planRepository = PlanRepository(context)
    planRepository.ensureCatalog()

    val creation = briefingRepository.createBoardWithUndo(boardName)
    assertTrue(creation.value)
    val board = requireNotNull(database.planDao().getBoardByName(boardName))
    val deletedColumn =
      requireNotNull(database.planDao().getColumnByName(board.id, SettingKeys.DEFAULT_COLUMNS.last()))
    val beforeColumnsSetting =
      briefingRepository.readSetting(SettingKeys.columnsForBoard(boardName))
    val originalTask =
      PlanItem(
        id = taskId,
        boardId = board.id,
        columnId = deletedColumn.id,
        title = "Exact task snapshot",
        notes = "Do not lose this metadata",
        rank = 8_000_000L,
        effortMinutes = 75,
        progress = 35,
        priority = PlanPriority.HIGH,
        owner = "Owner 🧑🏽‍💻",
        schedulingMode = PlanSchedulingMode.MANUAL,
        locked = true,
        isMilestone = false,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
      )
    val originalEvent =
      BriefingEvent(
        id = eventId,
        title = "Exact event snapshot",
        startTime = 1_800_100_000_000L,
        endTime = 1_800_103_600_000L,
        source = EventSource.MANUAL,
        description = "Column delete subject",
        isDeadline = false,
        isUrgent = true,
        kanbanStatus = deletedColumn.name,
        kanbanBoard = boardName,
        userEdited = true,
      )
    database.planDao().insertItem(originalTask)
    database.eventDao().insertEvents(listOf(originalEvent))

    val deletion = briefingRepository.deleteColumnWithUndo(deletedColumn.name)
    try {
      assertTrue(deletion.value)
      assertNull(database.planDao().getColumn(deletedColumn.id))
      assertEquals(
        requireNotNull(database.planDao().getColumnByName(board.id, "To Do")).id,
        database.planDao().getItem(taskId)?.columnId,
      )
      assertEquals("To Do", database.eventDao().getEventById(eventId)?.kanbanStatus)
      assertFalse(
        deletedColumn.name in
          requireNotNull(
            SettingKeys.decodeList(
              briefingRepository.readSetting(SettingKeys.columnsForBoard(boardName))
            )
          )
      )

      assertEquals(
        PlanUndoStatus.UNDONE,
        PlanRepository(context).undoMutation(requireNotNull(deletion.mutationId)).status,
      )
      assertEquals(deletedColumn, database.planDao().getColumn(deletedColumn.id))
      assertEquals(originalTask, database.planDao().getItem(taskId))
      assertEquals(originalEvent, database.eventDao().getEventById(eventId))
      assertEquals(
        beforeColumnsSetting,
        briefingRepository.readSetting(SettingKeys.columnsForBoard(boardName)),
      )
      assertEquals(boardName, briefingRepository.readSetting(SettingKeys.ACTIVE_BOARD))
      assertEquals(board.id, briefingRepository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID))
    } finally {
      deletion.mutationId?.let { PlanRepository(context).undoMutation(it) }
      database.eventDao().deleteEventById(eventId)
      database.planDao().getItem(taskId)?.let { database.planDao().deleteItem(it) }
      creation.mutationId?.let { PlanRepository(context).undoMutation(it) }
    }
  }

  @Test
  fun boardCreateUndoFailsClosedWhenALaterEventDependsOnTheBoard() = runBlocking {
    val suffix = System.nanoTime().toString()
    val boardName = "Dependent event board $suffix"
    val eventId = "catalog_dependent_event_$suffix"
    val briefingRepository = BriefingRepository(context)
    val creation = briefingRepository.createBoardWithUndo(boardName)
    assertTrue(creation.value)
    database.eventDao().insertEvents(
      listOf(
        BriefingEvent(
          id = eventId,
          title = "Newer dependent event",
          startTime = 1_801_000_000_000L,
          endTime = 1_801_000_060_000L,
          source = EventSource.MANUAL,
          description = null,
          isDeadline = false,
          isUrgent = false,
          kanbanBoard = boardName,
        )
      )
    )
    try {
      assertEquals(
        PlanUndoStatus.STALE,
        PlanRepository(context).undoMutation(requireNotNull(creation.mutationId)).status,
      )
      assertNotNull(database.planDao().getBoardByName(boardName))
      assertEquals(boardName, database.eventDao().getEventById(eventId)?.kanbanBoard)
      assertTrue(
        boardName in
          requireNotNull(SettingKeys.decodeList(briefingRepository.readSetting(SettingKeys.BOARDS)))
      )
    } finally {
      database.eventDao().deleteEventById(eventId)
      creation.mutationId?.let { PlanRepository(context).undoMutation(it) }
    }
  }
}
