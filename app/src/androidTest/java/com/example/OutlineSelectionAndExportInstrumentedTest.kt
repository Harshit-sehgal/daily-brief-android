package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Selecting work in the Outline, and taking a plan out of the app.
 *
 * The move must be the same atomic command the Board uses — selecting somewhere else cannot mean
 * weaker guarantees — and the export must state its own scope before anything else.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OutlineSelectionAndExportInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "outline_select_board_$token"
  private val columnId = "outline_select_column_$token"
  private val firstTitle = "Selectable one $token"
  private val secondTitle = "Selectable two $token"
  private var settingsSnapshot: Map<String, String?> = emptyMap()

  @After
  fun cleanUp() {
    runBlocking {
      settingsSnapshot.forEach { (key, value) ->
        if (value == null) briefingRepository.deleteSetting(key)
        else briefingRepository.writeSetting(key, value)
      }
      database.planDao().getAllItems(boardId).forEach { database.planDao().deleteItem(it) }
      database.openHelper.writableDatabase.execSQL(
        "DELETE FROM plan_mutations WHERE boardId = ?",
        arrayOf(boardId),
      )
      database.planDao().getColumn(columnId)?.let { database.planDao().deleteColumn(it) }
      database.planDao().getBoard(boardId)?.let { database.planDao().deleteBoard(it) }
    }
  }

  @Test
  fun selectingInTheOutlineMovesThroughTheSameAtomicCommand() {
    seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId && viewModel.planItems.value.size == 2
    }

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasText(firstTitle, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    // Long press starts a selection; the bar that appears says how many are in it.
    composeRule
      .onNode(hasTestTag("plan_item") and hasText(firstTitle, substring = true))
      .performTouchInput { longClick() }
    composeRule.onNodeWithTag("outline_selection_bar").assertIsDisplayed()
    composeRule.onNodeWithText("1 selected").assertIsDisplayed()

    composeRule
      .onNode(hasTestTag("plan_item") and hasText(secondTitle, substring = true))
      .performClick()
    composeRule.onNodeWithText("2 selected").assertIsDisplayed()

    val historyBefore = runBlocking { planRepository.observeMutationHistory(boardId, 200).first() }.size
    composeRule.onNodeWithTag("outline_move_selected").performClick()
    composeRule.onNodeWithTag("outline_move_$columnId").performClick()

    composeRule.waitUntil(timeoutMillis = 10_000) {
      runBlocking { database.planDao().getAllItems(boardId) }.all { it.columnId == columnId }
    }
    // One journal entry for the whole selection, exactly as the Board produces.
    val historyAfter = runBlocking { planRepository.observeMutationHistory(boardId, 200).first() }.size
    assertEquals(1, historyAfter - historyBefore)

    // The selection clears itself once it has been acted on.
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("outline_selection_bar")).fetchSemanticsNodes().isEmpty()
    }
  }

  @Test
  fun anExportStatesItsOwnScopeBeforeAnythingElse() {
    seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId && viewModel.planItems.value.size == 2
    }

    var csv: String? = null
    var mime: String? = null
    composeRule.runOnIdle {
      viewModel.exportActivePlan(asCalendar = false) { body, type ->
        csv = body
        mime = type
      }
    }
    composeRule.waitUntil(timeoutMillis = 10_000) { csv != null }

    val text = requireNotNull(csv)
    assertEquals("text/csv", mime)
    assertTrue(text, text.lineSequence().first().startsWith("# Daily Brief plan export"))
    assertTrue(text, text.contains("empty effort means the task states none, not zero"))
    assertTrue(text, text.contains(firstTitle))

    var ics: String? = null
    composeRule.runOnIdle {
      viewModel.exportActivePlan(asCalendar = true) { body, _ -> ics = body }
    }
    composeRule.waitUntil(timeoutMillis = 10_000) { ics != null }
    assertTrue(requireNotNull(ics).startsWith("BEGIN:VCALENDAR"))
  }

  private fun seed() {
    runBlocking {
      planRepository.ensureCatalog()
      settingsSnapshot =
        listOf(SettingKeys.ACTIVE_PLAN_BOARD_ID, SettingKeys.PLAN_VIEW)
          .associateWith { briefingRepository.readSetting(it) }
      val now = System.currentTimeMillis()
      database.planDao().insertBoard(
        PlanBoard(
          id = boardId,
          name = "Selection $token",
          nameKey = "selection $token",
          rank = database.planDao().maxBoardRank() + 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      database.planDao().insertColumn(
        PlanColumn(
          id = columnId,
          boardId = boardId,
          name = "Doing",
          nameKey = "doing",
          rank = 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      planRepository.saveItem(PlanItemInput(boardId = boardId, title = firstTitle, effortMinutes = 60))
      planRepository.saveItem(PlanItemInput(boardId = boardId, title = secondTitle))
    }
  }
}
