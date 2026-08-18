package com.example

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.ScheduleAnalysis
import com.example.core.WorkingCalendar
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBlock
import com.example.data.model.PlanBoard
import com.example.data.prefs.SettingKeys
import com.example.data.prefs.UndoWindowPolicy
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendered proof for the interaction-principle fixes in `docs/HCI_PRINCIPLES.md`.
 *
 * Each test names the finding it guards, because a principle that cannot fail a build is
 * decoration.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HciPrinciplesJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "hci_board_$token"
  private val taskTitle = "Principle task $token"
  private var taskId: String? = null
  private var blockId: String? = null
  private var settingsSnapshot: Map<String, String?> = emptyMap()

  @After
  fun cleanUp() {
    runBlocking {
      settingsSnapshot.forEach { (key, value) ->
        if (value == null) briefingRepository.deleteSetting(key) else briefingRepository.writeSetting(key, value)
      }
      blockId?.let { database.planDao().getBlock(it) }?.let { database.planDao().deleteBlock(it) }
      taskId?.let { database.planDao().getItem(it) }?.let { database.planDao().deleteItem(it) }
      database.openHelper.writableDatabase.execSQL(
        "DELETE FROM plan_mutations WHERE boardId = ?",
        arrayOf(boardId),
      )
      database.planDao().getBoard(boardId)?.let { database.planDao().deleteBoard(it) }
    }
  }

  /** C-1 and R-1: Back leaves the mode, and leaves the work alone. */
  @Test
  fun backCancelsGanttMoveModeBoardSelectionAndHomeEditingWithoutWriting() {
    val original = seedBlock()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle {
      viewModel.setGanttRangeDays(7)
      viewModel.selectDay(ScheduleAnalysis.startOfDay(original.startAt))
    }
    awaitBlockVisible(original)

    openGantt()
    enterMoveMode()
    val historyBefore = runBlocking { planRepository.observeMutationHistory(boardId).first() }

    pressBack()
    waitUntilGone("gantt_move_mode")
    // Cancelling by Back has to mean exactly what the Cancel button means: nothing was written,
    // and the Plan root is still where the person was.
    composeRule.onNodeWithTag("screen_gantt").assertIsDisplayed()
    assertEquals(original, runBlocking { database.planDao().getBlock(original.id) })
    assertEquals(
      historyBefore.map { it.id },
      runBlocking { planRepository.observeMutationHistory(boardId).first() }.map { it.id },
    )

    composeRule.onNodeWithText("Board").performClick()
    waitUntilDisplayed("screen_plan_board")
    composeRule.onNodeWithTag("board_select").performClick()
    waitUntilDisplayed("board_selection_bar")
    pressBack()
    waitUntilGone("board_selection_bar")
    composeRule.onNodeWithTag("screen_plan_board").assertIsDisplayed()

    composeRule.onNodeWithTag("tab_Home").performClick()
    composeRule.onNodeWithTag("home_edit_layout").performClick()
    waitUntilDisplayed("home_layout_editor")
    pressBack()
    waitUntilGone("home_layout_editor")
    composeRule.onNodeWithTag("home_edit_layout").assertIsDisplayed()
  }

  /** SD-1 and R-2: a disabled Save says which field it is waiting on. */
  @Test
  fun aDisabledSaveNamesTheFieldItIsWaitingOn() {
    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithTag("add_event").performClick()
    waitUntilDisplayed("event_editor")

    composeRule.onNodeWithTag("editor_title").performTextClearance()
    composeRule.onNodeWithTag("editor_save").assertIsNotEnabled()
    // The field says how to fix it; the notice beside Save says what Save is waiting for.
    composeRule.onNodeWithText("Add a title to save this event.").assertIsDisplayed()
    composeRule
      .onNodeWithTag("editor_save_blocker")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextEquals("Save needs a title.")

    composeRule.onNodeWithTag("editor_title").performTextInput("Design review")
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("editor_save_blocker")).fetchSemanticsNodes().isEmpty()
    }
    composeRule.onNodeWithTag("editor_save").assertIsEnabled()
  }

  /** SD-2: the preview reports itself when it changes, without moving focus. */
  @Test
  fun theMovePreviewAnnouncesItsExactTimesAndStatus() {
    val original = seedBlock()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle {
      viewModel.setGanttRangeDays(7)
      viewModel.selectDay(ScheduleAnalysis.startOfDay(original.startAt))
    }
    awaitBlockVisible(original)
    openGantt()
    enterMoveMode()

    val status = composeRule.onNodeWithTag("gantt_move_status").fetchSemanticsNode()
    assertNotNull(
      "the status must be a live region",
      status.config.getOrElseNullable(SemanticsProperties.LiveRegion) { null },
    )
    val spoken =
      status.config
        .getOrElseNullable(SemanticsProperties.ContentDescription) { null }
        ?.joinToString(" ")
        .orEmpty()
    assertTrue("announcement should carry the start: $spoken", spoken.startsWith("Start "))
    assertTrue("announcement should carry the end: $spoken", spoken.contains("end "))

    composeRule.onNodeWithTag("gantt_move_apply").assertIsNotEnabled()
  }

  /** L-1: direct movement is reachable without knowing the long-press. */
  @Test
  fun theBlockEditorOffersAVisibleWayIntoDirectMovement() {
    val original = seedBlock()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle {
      viewModel.setGanttRangeDays(7)
      viewModel.selectDay(ScheduleAnalysis.startOfDay(original.startAt))
    }
    awaitBlockVisible(original)
    openGantt()

    val bar = hasTestTag("plan_gantt_item") and hasContentDescription(taskTitle, substring = true)
    composeRule.waitUntil(timeoutMillis = 10_000) {
      composeRule.onAllNodes(bar).fetchSemanticsNodes().size == 1
    }
    composeRule.onNode(bar).performScrollTo().performClick()
    waitUntilDisplayed("gantt_block_editor")
    composeRule.onNodeWithTag("gantt_block_move_on_map").performClick()

    waitUntilDisplayed("gantt_move_mode")
    assertEquals(original, runBlocking { database.planDao().getBlock(original.id) })
  }

  /** CT-1: the recovery window is the one the person chose. */
  @Test
  fun theChosenUndoWindowIsWhatTheJournalStamps() {
    settingsSnapshot =
      mapOf(SettingKeys.UNDO_WINDOW_SECONDS to runBlocking {
        briefingRepository.readSetting(SettingKeys.UNDO_WINDOW_SECONDS)
      })
    seedBlock()

    composeRule.onNodeWithTag("open_settings").performClick()
    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_category_notifications"))
    composeRule.onNodeWithTag("settings_category_planning").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("undo_window_choices")).fetchSemanticsNodes().size == 1
    }
    val hour = 60 * 60
    composeRule.onNodeWithTag("undo_window_$hour").performScrollTo().performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      runBlocking { briefingRepository.readSetting(SettingKeys.UNDO_WINDOW_SECONDS) } ==
        UndoWindowPolicy.store(hour)
    }

    val item = requireNotNull(taskId)
    val mutationId = runBlocking { planRepository.saveItemWithUndo(
      PlanItemInput(id = item, boardId = boardId, title = "$taskTitle renamed", effortMinutes = 60)
    ).mutationId }
    val mutation = runBlocking { database.planDao().getPlanMutation(requireNotNull(mutationId)) }
    val expiresAt = requireNotNull(requireNotNull(mutation).expiresAt)
    val window = expiresAt - mutation.createdAt
    assertEquals(hour * 1_000L, window)
    // The old fixed window would have expired while a screen reader was still reaching the action.
    assertTrue(window > PlanRepository.MINIMUM_UNDO_WINDOW_MS)
  }

  private fun seedBlock(): PlanBlock =
    runBlocking {
      planRepository.ensureCatalog()
      val now = System.currentTimeMillis()
      if (database.planDao().getBoard(boardId) == null) {
        database.planDao().insertBoard(
          PlanBoard(
            id = boardId,
            name = "Principles $token",
            nameKey = "principles $token",
            rank = database.planDao().maxBoardRank() + 10_000L,
            createdAt = now,
            updatedAt = now,
          )
        )
      }
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      val task =
        taskId?.let { database.planDao().getItem(it) }
          ?: planRepository.saveItem(
            PlanItemInput(boardId = boardId, title = taskTitle, effortMinutes = 60)
          )
      taskId = task.id
      blockId?.let { database.planDao().getBlock(it) }
        ?: run {
          val spec = requireNotNull(planRepository.observeDefaultWorkingCalendar().first()).spec
          val rangeStart = ScheduleAnalysis.startOfDayOffset(System.currentTimeMillis(), 1)
          val interval =
            WorkingCalendar.workingIntervals(
                spec,
                rangeStart,
                ScheduleAnalysis.startOfDayOffset(rangeStart, 21),
              )
              .first { it.durationMinutes >= 180 }
          val startAt = interval.startAt + 60 * 60_000L
          planRepository
            .saveBlock(
              PlanBlockInput(
                planItemId = task.id,
                startAt = startAt,
                endAt = startAt + 60 * 60_000L,
              )
            )
            .also { blockId = it.id }
        }
    }

  private fun awaitBlockVisible(block: PlanBlock) {
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId &&
        viewModel.planGanttItems.value.any { row -> row.blocks.any { it.id == block.id } }
    }
  }

  private fun openGantt() {
    composeRule.onNodeWithTag("tab_Plan").performClick()
    waitUntilDisplayed("screen_plan")
    composeRule.onNodeWithText("Gantt").performClick()
    waitUntilDisplayed("screen_gantt")
  }

  private fun enterMoveMode() {
    val bar = hasTestTag("plan_gantt_item") and hasContentDescription(taskTitle, substring = true)
    composeRule.waitUntil(timeoutMillis = 10_000) {
      composeRule.onAllNodes(bar).fetchSemanticsNodes().size == 1
    }
    composeRule.onNode(bar).performScrollTo().performTouchInput { longClick() }
    waitUntilDisplayed("gantt_move_mode")
  }

  private fun pressBack() {
    composeRule.runOnUiThread {
      composeRule.activity.onBackPressedDispatcher.onBackPressed()
    }
    composeRule.waitForIdle()
  }

  private fun waitUntilDisplayed(tag: String, timeoutMillis: Long = 15_000) {
    composeRule.waitUntil(timeoutMillis = timeoutMillis) {
      composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag(tag).assertIsDisplayed()
  }

  private fun waitUntilGone(tag: String, timeoutMillis: Long = 5_000) {
    composeRule.waitUntil(timeoutMillis = timeoutMillis) {
      composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
    }
  }
}
