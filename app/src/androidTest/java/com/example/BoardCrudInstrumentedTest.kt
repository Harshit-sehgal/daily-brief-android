package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boards and columns are the one place the app rewrites rows in bulk: a rename
 * has to reach every event, and a delete has to re-home them rather than
 * stranding them on a board that no longer exists. All of it runs in a Room
 * transaction, so it is exercised here against a real database.
 */
@RunWith(AndroidJUnit4::class)
class BoardCrudInstrumentedTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val repository by lazy { BriefingRepository(context) }
  private val database by lazy { AppDatabase.getDatabase(context) }

  private val eventId = "manual_board_crud_${System.nanoTime()}"
  private lateinit var boardsBefore: String
  private lateinit var activeBefore: String

  @Before
  fun rememberExistingSetup() = runBlocking {
    boardsBefore = repository.readSetting(SettingKeys.BOARDS).orEmpty()
    activeBefore = repository.readSetting(SettingKeys.ACTIVE_BOARD).orEmpty()
  }

  @After
  fun restoreSetup() = runBlocking {
    database.eventDao().deleteEventById(eventId)
    listOf("Work trip", "Renamed trip").forEach {
      repository.deleteSetting(SettingKeys.columnsForBoard(it))
    }
    if (boardsBefore.isEmpty()) repository.deleteSetting(SettingKeys.BOARDS)
    else repository.writeSetting(SettingKeys.BOARDS, boardsBefore)
    if (activeBefore.isEmpty()) repository.deleteSetting(SettingKeys.ACTIVE_BOARD)
    else repository.writeSetting(SettingKeys.ACTIVE_BOARD, activeBefore)
  }

  private suspend fun seedEventOn(board: String, column: String) {
    database
      .eventDao()
      .insertEvents(
        listOf(
          BriefingEvent(
            id = eventId,
            title = "Board CRUD subject",
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 60_000L,
            source = EventSource.MANUAL,
            description = null,
            isDeadline = false,
            isUrgent = false,
            kanbanBoard = board,
            kanbanStatus = column,
          )
        )
      )
  }

  private suspend fun storedEvent() = database.eventDao().getEventById(eventId)

  /** An unset list is not an empty one: it means the default board, as the repository reads it. */
  private suspend fun boards(): List<String> =
    SettingKeys.decodeList(repository.readSetting(SettingKeys.BOARDS))
      ?: listOf(SettingKeys.DEFAULT_BOARD)

  private suspend fun columns(board: String): List<String> =
    SettingKeys.decodeList(repository.readSetting(SettingKeys.columnsForBoard(board)))
      ?: SettingKeys.DEFAULT_COLUMNS

  @Test
  fun aNewBoardStartsWithTheDefaultColumnsAndBecomesActive() = runBlocking {
    assertTrue(repository.createBoard("Work trip"))

    assertTrue("Work trip" in boards())
    assertEquals(SettingKeys.DEFAULT_COLUMNS, columns("Work trip"))
    assertEquals("Work trip", repository.readSetting(SettingKeys.ACTIVE_BOARD))

    // Names are compared case-insensitively, so near-duplicates cannot pile up.
    assertFalse(repository.createBoard("work trip"))
    assertEquals(1, boards().count { it.equals("Work trip", ignoreCase = true) })
  }

  @Test
  fun renamingABoardCarriesItsEventsAndColumnsAcross() = runBlocking {
    repository.createBoard("Work trip")
    seedEventOn("Work trip", "In Progress")

    assertTrue(repository.renameBoard("Work trip", "Renamed trip"))

    assertEquals("Renamed trip", storedEvent()?.kanbanBoard)
    assertEquals("In Progress", storedEvent()?.kanbanStatus)
    assertTrue("Renamed trip" in boards())
    assertFalse("Work trip" in boards())
    assertEquals(SettingKeys.DEFAULT_COLUMNS, columns("Renamed trip"))
    // The old board's column list must not linger as an orphan.
    assertNull(repository.readSetting(SettingKeys.columnsForBoard("Work trip")))
    assertEquals("Renamed trip", repository.readSetting(SettingKeys.ACTIVE_BOARD))
  }

  @Test
  fun theDefaultBoardCannotBeRenamedOrDeleted() = runBlocking {
    assertFalse(repository.renameBoard(SettingKeys.DEFAULT_BOARD, "Something else"))
    assertNull(repository.deleteBoard(SettingKeys.DEFAULT_BOARD))
    assertTrue(SettingKeys.DEFAULT_BOARD in boards())
  }

  @Test
  fun deletingABoardRehomesItsEventsRatherThanStrandingThem() = runBlocking {
    repository.createBoard("Work trip")
    seedEventOn("Work trip", "Done")

    val fallback = repository.deleteBoard("Work trip")

    assertEquals(SettingKeys.DEFAULT_BOARD, fallback)
    assertEquals(SettingKeys.DEFAULT_BOARD, storedEvent()?.kanbanBoard)
    assertFalse("Work trip" in boards())
    assertNull(repository.readSetting(SettingKeys.columnsForBoard("Work trip")))
    assertEquals(SettingKeys.DEFAULT_BOARD, repository.readSetting(SettingKeys.ACTIVE_BOARD))
  }

  @Test
  fun deletingAColumnMovesItsEventsToTheFirstOneLeft() = runBlocking {
    repository.createBoard("Work trip")
    seedEventOn("Work trip", "Done")

    assertTrue(repository.deleteColumn("Done"))

    assertEquals(SettingKeys.DEFAULT_COLUMNS.first(), storedEvent()?.kanbanStatus)
    assertFalse("Done" in columns("Work trip"))
  }

  @Test
  fun renamingAColumnCarriesItsEventsAndRejectsDuplicates() = runBlocking {
    repository.createBoard("Work trip")
    seedEventOn("Work trip", "To Do")

    assertTrue(repository.renameColumn("To Do", "Up next"))
    assertEquals("Up next", storedEvent()?.kanbanStatus)

    assertFalse("A column cannot take a name already in use", repository.renameColumn("Up next", "Done"))
    assertEquals("Up next", storedEvent()?.kanbanStatus)
  }

  @Test
  fun aBoardKeepsAtLeastOneColumn() = runBlocking {
    repository.createBoard("Work trip")
    columns("Work trip").dropLast(1).forEach { assertTrue(repository.deleteColumn(it)) }

    val last = columns("Work trip").single()
    assertFalse("Removing the final column would leave nowhere to file work", repository.deleteColumn(last))
    assertEquals(listOf(last), columns("Work trip"))
  }
}
