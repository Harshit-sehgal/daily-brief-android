package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
import com.example.data.model.PlanSurface
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanRepository
import com.example.data.repository.SavedPlanViewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A saved view may rename, copy, pin and restore a presentation — and never touch the work. */
@RunWith(AndroidJUnit4::class)
class SavedPlanViewLifecycleInstrumentedTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val database = AppDatabase.getDatabase(context)
  private val planRepository = PlanRepository(context)
  private val briefingRepository = BriefingRepository(context)
  private val token = System.nanoTime().toString(16)
  private val boardId = "view_board_$token"
  private var settingsSnapshot: Map<String, String?> = emptyMap()

  @Before
  fun seedBoard() { runBlocking {
    planRepository.ensureCatalog()
    settingsSnapshot =
      listOf(
          SettingKeys.PLAN_OUTLINE_SORT,
          SettingKeys.PLAN_OUTLINE_HIDE_COMPLETED,
          SettingKeys.PLAN_OUTLINE_COLLAPSED,
          SettingKeys.PLAN_VIEW,
          SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID,
        )
        .associateWith { briefingRepository.readSetting(it) }
    val now = System.currentTimeMillis()
    database.planDao().insertBoard(
      PlanBoard(
        id = boardId,
        name = "Views $token",
        nameKey = "views $token",
        rank = database.planDao().maxBoardRank() + 10_000L,
        createdAt = now,
        updatedAt = now,
      )
    )
  } }

  @After
  fun cleanUp() { runBlocking {
    settingsSnapshot.forEach { (key, value) ->
      if (value == null) briefingRepository.deleteSetting(key)
      else briefingRepository.writeSetting(key, value)
    }
    database.planDao().getSavedViews(boardId).forEach { database.planDao().deleteSavedView(it) }
    database.planDao().getBoard(boardId)?.let { database.planDao().deleteBoard(it) }
  } }

  @Test
  fun applyingAViewRestoresEveryPresentationFieldItStored() { runBlocking {
    val state =
      SavedPlanViewState(
        surface = PlanSurface.OUTLINE,
        filters = mapOf(SavedPlanViewState.FILTER_HIDE_COMPLETED to "true"),
        sort = listOf("due"),
        collapsedItemIds = setOf("task-a", "task-b"),
      )
    val saved = planRepository.saveSavedView(boardId, "Focus $token", state)

    // Move every field away from what the view stores, then apply it.
    briefingRepository.writeSetting(SettingKeys.PLAN_OUTLINE_SORT, "priority")
    briefingRepository.writeSetting(SettingKeys.PLAN_OUTLINE_HIDE_COMPLETED, "false")
    briefingRepository.writeSetting(SettingKeys.PLAN_OUTLINE_COLLAPSED, SettingKeys.encodeList(emptyList()))
    briefingRepository.writeSetting(SettingKeys.PLAN_VIEW, "Gantt")

    planRepository.activateSavedView(saved.view.id, boardId)

    assertEquals("Outline", briefingRepository.readSetting(SettingKeys.PLAN_VIEW))
    assertEquals("due", briefingRepository.readSetting(SettingKeys.PLAN_OUTLINE_SORT))
    assertEquals("true", briefingRepository.readSetting(SettingKeys.PLAN_OUTLINE_HIDE_COMPLETED))
    assertEquals(
      listOf("task-a", "task-b"),
      SettingKeys.decodeList(briefingRepository.readSetting(SettingKeys.PLAN_OUTLINE_COLLAPSED)),
    )
    assertEquals(saved.view.id, briefingRepository.readSetting(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID))
  } }

  @Test
  fun renameUpdateAndDuplicateKeepTheViewsDistinct() { runBlocking {
    val original =
      planRepository.saveSavedView(
        boardId,
        "Week $token",
        SavedPlanViewState(surface = PlanSurface.OUTLINE, sort = listOf("manual")),
      )

    val renamed = planRepository.renameSavedView(original.view.id, boardId, "  Week ahead $token  ")
    assertEquals("Week ahead $token", renamed.view.name)
    // Renaming changes the label and nothing else.
    assertEquals(original.state, renamed.state)

    val updated =
      planRepository.updateSavedView(
        original.view.id,
        boardId,
        SavedPlanViewState(
          surface = PlanSurface.GANTT,
          sort = listOf("due"),
          rangeDays = 30,
          collapsedItemIds = setOf("kept"),
        ),
      )
    assertEquals("Week ahead $token", updated.view.name)
    assertEquals(PlanSurface.GANTT, updated.state.surface)
    assertEquals(30, updated.state.rangeDays)
    assertEquals(setOf("kept"), updated.state.collapsedItemIds)

    val copy = planRepository.duplicateSavedView(original.view.id, boardId)
    assertNotEquals(original.view.id, copy.view.id)
    assertTrue(copy.view.name, copy.view.name.endsWith("(2)"))
    assertEquals(updated.state, copy.state)
    assertFalse("a copy never inherits the pin", copy.view.pinned)

    // A second copy cannot collide with the first.
    val secondCopy = planRepository.duplicateSavedView(original.view.id, boardId)
    assertNotEquals(copy.view.name, secondCopy.view.name)

    // A rename onto an existing name on the same surface is refused rather than merged.
    runCatching { planRepository.renameSavedView(copy.view.id, boardId, updated.view.name) }
      .onSuccess { throw AssertionError("duplicate view names must be refused") }
  } }

  @Test
  fun pinningIsExclusivePerBoardAndReversible() { runBlocking {
    val first =
      planRepository.saveSavedView(boardId, "First $token", SavedPlanViewState(PlanSurface.OUTLINE))
    val second =
      planRepository.saveSavedView(boardId, "Second $token", SavedPlanViewState(PlanSurface.BOARD))

    planRepository.setSavedViewPinned(first.view.id, boardId, true)
    assertTrue(requireNotNull(database.planDao().getSavedView(first.view.id)).pinned)

    // Pinning another view moves the pin rather than creating two "opens this board" answers.
    planRepository.setSavedViewPinned(second.view.id, boardId, true)
    assertFalse(requireNotNull(database.planDao().getSavedView(first.view.id)).pinned)
    assertTrue(requireNotNull(database.planDao().getSavedView(second.view.id)).pinned)

    planRepository.setSavedViewPinned(second.view.id, boardId, false)
    assertTrue(database.planDao().getSavedViews(boardId).none { it.pinned })
  } }
}
