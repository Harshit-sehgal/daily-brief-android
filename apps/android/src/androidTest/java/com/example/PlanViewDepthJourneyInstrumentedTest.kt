package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.PlanMenu.closeManageViews
import com.example.PlanMenu.closeViewOptions
import com.example.PlanMenu.exportPlan
import com.example.PlanMenu.openManageViews
import com.example.PlanMenu.openPlanHealth
import com.example.PlanMenu.openPlanHistory
import com.example.PlanMenu.openPlanTool
import com.example.PlanMenu.openViewOptions
import com.example.PlanMenu.startSavingView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The outline's presentation controls, and the saved view that remembers them.
 *
 * Folding, ordering and filtering are presentation; this journey also proves they never become
 * task edits — the same tasks are present before and after.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlanViewDepthJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "depth_board_$token"
  private val parentTitle = "Parent $token"
  private val childTitle = "Child $token"
  private val doneTitle = "Finished $token"
  private var settingsSnapshot: Map<String, String?> = emptyMap()

  @After
  fun cleanUp() {
    runBlocking {
      settingsSnapshot.forEach { (key, value) ->
        if (value == null) briefingRepository.deleteSetting(key)
        else briefingRepository.writeSetting(key, value)
      }
      database.planDao().getSavedViews(boardId).forEach { database.planDao().deleteSavedView(it) }
      database.planDao().getAllItems(boardId).forEach { database.planDao().deleteItem(it) }
      database.planDao().getBoard(boardId)?.let { database.planDao().deleteBoard(it) }
    }
  }

  @Test
  fun foldingFilteringAndSortingSurviveASavedViewWithoutChangingTheWork() {
    seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId && viewModel.planItems.value.size == 3
    }

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    waitForText(parentTitle)
    waitForText(childTitle)

    // Fold the parent: the subtask goes, the parent stays, and nothing is deleted.
    composeRule.onNodeWithContentDescription("Collapse $parentTitle").performScrollTo().performClick()
    waitUntilGone(childTitle)
    composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.outlineCollapsedIds.value.isNotEmpty() }
    assertEquals(3, runBlocking { database.planDao().getAllItems(boardId) }.size)

    // Hide finished work.
    composeRule.openViewOptions()
    composeRule.onNodeWithTag("outline_hide_completed").performScrollTo().performClick()
    composeRule.closeViewOptions()
    waitUntilGone(doneTitle)

    // Order by due date.
    composeRule.openViewOptions()
    composeRule.onNodeWithTag("outline_sort_due").performClick()
    composeRule.closeViewOptions()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.outlineSort.value.key == "due"
    }

    // Save that as a view, then move every control away from it.
    composeRule.startSavingView()
    composeRule.onNodeWithTag("saved_view_name").performTextInput("Focus $token")
    composeRule.onNodeWithTag("confirm_save_plan_view").performClick()
    composeRule.waitUntil(timeoutMillis = 10_000) {
      viewModel.savedPlanViews.value.any { it.view.name == "Focus $token" }
    }
    val saved = viewModel.savedPlanViews.value.first { it.view.name == "Focus $token" }

    composeRule.runOnIdle {
      viewModel.expandAllOutlineTasks()
      viewModel.setOutlineHideCompleted(false)
      viewModel.setOutlineSort(com.example.ui.screens.OutlineSort.PRIORITY)
    }
    waitForText(childTitle)

    // Applying the view brings all three back at once.
    composeRule.runOnIdle { viewModel.applySavedPlanView(saved) }
    composeRule.waitUntil(timeoutMillis = 10_000) {
      viewModel.outlineSort.value.key == "due" &&
        viewModel.outlineHideCompleted.value &&
        viewModel.outlineCollapsedIds.value.isNotEmpty()
    }
    waitUntilGone(childTitle)
    waitUntilGone(doneTitle)

    // The work itself is untouched by every one of those presentation changes.
    val stored = runBlocking { database.planDao().getAllItems(boardId) }
    assertEquals(3, stored.size)
    assertTrue(stored.map { it.title }.containsAll(listOf(parentTitle, childTitle, doneTitle)))
  }

  @Test
  fun aViewCanBeRenamedUpdatedDuplicatedAndPinned() {
    seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.activePlanBoardId.value == boardId }
    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()

    composeRule.startSavingView()
    composeRule.onNodeWithTag("saved_view_name").performTextInput("Original $token")
    composeRule.onNodeWithTag("confirm_save_plan_view").performClick()
    composeRule.waitUntil(timeoutMillis = 10_000) {
      viewModel.savedPlanViews.value.any { it.view.name == "Original $token" }
    }

    composeRule.openManageViews()
    composeRule.onAllNodesWithTag("plan_view_rename")[0].performClick()
    composeRule.onNodeWithTag("rename_view_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("rename_view_name").performTextClearance()
    composeRule.onNodeWithTag("rename_view_name").performTextInput("Renamed $token")
    composeRule.onNodeWithTag("confirm_rename_plan_view").performClick()
    composeRule.waitUntil(timeoutMillis = 10_000) {
      viewModel.savedPlanViews.value.any { it.view.name == "Renamed $token" }
    }

    composeRule.openManageViews()
    composeRule.onAllNodesWithTag("plan_view_duplicate")[0].performClick()
    composeRule.waitUntil(timeoutMillis = 10_000) { viewModel.savedPlanViews.value.size == 2 }

    composeRule.openManageViews()
    composeRule.onAllNodesWithTag("plan_view_pin")[0].performClick()
    composeRule.waitUntil(timeoutMillis = 10_000) {
      viewModel.savedPlanViews.value.count { it.view.pinned } == 1
    }

    // Reset stops following the view without deleting it.
    composeRule.openManageViews()
    composeRule.onNodeWithTag("plan_view_reset").performClick()
    composeRule.waitUntil(timeoutMillis = 10_000) {
      viewModel.activeSavedPlanViewId.value == null &&
        viewModel.outlineSort.value == com.example.ui.screens.OutlineSort.MANUAL
    }
    assertEquals(2, viewModel.savedPlanViews.value.size)
  }

  private fun seed() {
    runBlocking {
      planRepository.ensureCatalog()
      settingsSnapshot =
        listOf(
            SettingKeys.ACTIVE_PLAN_BOARD_ID,
            SettingKeys.PLAN_VIEW,
            SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID,
            SettingKeys.PLAN_OUTLINE_SORT,
            SettingKeys.PLAN_OUTLINE_HIDE_COMPLETED,
            SettingKeys.PLAN_OUTLINE_COLLAPSED,
          )
          .associateWith { briefingRepository.readSetting(it) }
      val now = System.currentTimeMillis()
      if (database.planDao().getBoard(boardId) == null) {
        database.planDao().insertBoard(
          PlanBoard(
            id = boardId,
            name = "Depth $token",
            nameKey = "depth $token",
            rank = database.planDao().maxBoardRank() + 10_000L,
            createdAt = now,
            updatedAt = now,
          )
        )
      }
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      briefingRepository.writeSetting(SettingKeys.PLAN_OUTLINE_SORT, "manual")
      briefingRepository.writeSetting(SettingKeys.PLAN_OUTLINE_HIDE_COMPLETED, "false")
      briefingRepository.writeSetting(
        SettingKeys.PLAN_OUTLINE_COLLAPSED,
        SettingKeys.encodeList(emptyList()),
      )
      if (database.planDao().getAllItems(boardId).isEmpty()) {
        val parent =
          planRepository.saveItem(PlanItemInput(boardId = boardId, title = parentTitle, dueAt = now + 86_400_000L))
        planRepository.saveItem(
          PlanItemInput(boardId = boardId, title = childTitle, parentId = parent.id)
        )
        val done = planRepository.saveItem(PlanItemInput(boardId = boardId, title = doneTitle))
        planRepository.saveItem(
          PlanItemInput(id = done.id, boardId = boardId, title = doneTitle, progress = 100)
        )
      }
    }
  }

  private fun waitForText(text: String) {
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitUntilGone(text: String) {
    composeRule.waitUntil(timeoutMillis = 10_000) {
      composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()
    }
  }
}
