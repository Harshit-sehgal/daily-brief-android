package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import com.example.core.ScheduleAnalysis
import com.example.core.WorkingCalendar
import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingWeekWindow
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.ui.screens.OutlineGrouping
import com.example.ui.screens.OutlineSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.ui.viewmodel.BriefingViewModel

/**
 * The analysis surfaces: a baseline that measures drift and can put the schedule back, a rollup
 * across every board, alternative orderings of the same week, and a saved view that remembers how
 * the Outline was grouped and which columns were showing.
 *
 * Each one is asserted through the rendered dialog rather than only through its pure engine, because
 * a correct engine wired to nothing is the failure mode these features actually have.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlanAnalysisJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "analysis_board_$token"
  private val taskTitle = "Baselined work $token"
  private var settingsSnapshot: Map<String, String?> = emptyMap()
  private var workingCalendarSnapshot: WorkingCalendarSpec? = null

  @After
  fun cleanUp() {
    runBlocking {
      settingsSnapshot.forEach { (key, value) ->
        if (value == null) briefingRepository.deleteSetting(key)
        else briefingRepository.writeSetting(key, value)
      }
      workingCalendarSnapshot?.let { planRepository.saveDefaultWorkingCalendar(it) }
      database.planDao().getBaselines(boardId).forEach { database.planDao().deleteBaseline(it) }
      database.planDao().getSavedViews(boardId).forEach { database.planDao().deleteSavedView(it) }
      database.planDao().getColumns(boardId).forEach { database.planDao().deleteColumn(it) }
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

  @Test
  fun aBaselineIsTakenComparedAndRestoredFromTheDialogs() {
    val start = seed()
    val viewModel = awaitBoard()

    composeRule.runOnIdle { viewModel.captureBaseline("Before the slip $token") }
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.planBaselines.value.isNotEmpty() }
    val baseline = viewModel.planBaselines.value.single()

    // Move the work a day later behind the dialog's back, exactly as real drift happens.
    val block = runBlocking { database.planDao().getBlocksForBoard(boardId).single() }
    val moved = block.copy(startAt = block.startAt + 86_400_000L, endAt = block.endAt + 86_400_000L)
    runBlocking { database.planDao().updateBlock(moved) }
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.planGanttItems.value.flatMap { it.blocks }.any { it.startAt == moved.startAt }
    }

    composeRule.runOnIdle { viewModel.compareBaseline(baseline.id) }
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.baselineComparison.value != null }
    composeRule.onNodeWithTag("baseline_comparison_dialog").assertIsDisplayed()
    val comparison = requireNotNull(viewModel.baselineComparison.value).comparison
    assertEquals(24 * 60L, comparison.rows.single().driftMinutes)
    assertTrue(comparison.summary, comparison.summary.contains("later"))

    // Restore from the comparison — the point being that the drift was visible first.
    composeRule.onNodeWithTag("baseline_restore").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      runBlocking { database.planDao().getBlocksForBoard(boardId) }.single().startAt == start
    }

    // It landed in History as one entry, and undoing it returns the drifted schedule.
    val history = runBlocking { planRepository.observeMutationHistory(boardId, 200).first() }
    val restore = history.first()
    composeRule.runOnIdle { viewModel.undoPlanMutation(restore.id) }
    composeRule.waitUntil(timeoutMillis = 15_000) {
      runBlocking { database.planDao().getBlocksForBoard(boardId) }.single().startAt == moved.startAt
    }
  }

  @Test
  fun theBaselineDialogRefusesASecondBaselineWithTheSameName() {
    seed()
    val viewModel = awaitBoard()
    composeRule.runOnIdle { viewModel.captureBaseline("Only one $token") }
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.planBaselines.value.size == 1 }

    composeRule.openPlanTool("plan_baselines_open")
    composeRule.onNodeWithTag("baselines_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("baseline_row_${viewModel.planBaselines.value.single().id}")
      .assertIsDisplayed()
    composeRule.onNodeWithTag("baseline_name").performTextReplacement("only one $token")
    composeRule.onNodeWithTag("baseline_capture").performClick()

    // The duplicate is refused rather than silently shadowing the first.
    composeRule.waitForIdle()
    assertEquals(1, viewModel.planBaselines.value.size)
    composeRule.onNodeWithTag("baselines_close").performClick()
  }

  @Test
  fun deletingABaselineAsksFirstAndCancellingKeepsIt() {
    seed()
    val viewModel = awaitBoard()
    composeRule.runOnIdle { viewModel.captureBaseline("Deletable $token") }
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.planBaselines.value.size == 1 }
    val baselineId = viewModel.planBaselines.value.single().id

    composeRule.openPlanTool("plan_baselines_open")
    composeRule.onNodeWithTag("baseline_delete_$baselineId").performClick()

    // Nothing is destroyed on the first tap: a baseline is the only record of where the plan was,
    // and unlike every other destructive act here it has no journal entry to undo.
    composeRule.onNodeWithTag("delete_baseline_dialog").assertIsDisplayed()
    assertEquals(1, viewModel.planBaselines.value.size)
    composeRule.onNodeWithTag("cancel_delete_baseline").performClick()
    composeRule.waitForIdle()
    assertEquals(1, viewModel.planBaselines.value.size)

    composeRule.onNodeWithTag("baseline_delete_$baselineId").performClick()
    composeRule.onNodeWithTag("confirm_delete_baseline").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.planBaselines.value.isEmpty() }
    composeRule.onNodeWithTag("baselines_close").performClick()
  }

  @Test
  fun thePortfolioTotalsEveryBoardAndSaysWhenTheTotalIsAFloor() {
    seed(withUnestimated = true)
    val viewModel = awaitBoard()

    composeRule.openPlanTool("plan_portfolio_open")
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.portfolio.value != null }
    composeRule.onNodeWithTag("portfolio_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("portfolio_row_$boardId").assertIsDisplayed()

    val result = requireNotNull(viewModel.portfolio.value)
    val row = result.rows.single { it.boardId == boardId }
    assertEquals(3, row.openTaskCount)
    assertEquals(1, row.unestimatedTaskCount)
    // Unknown effort is reported, never quietly counted as zero.
    assertTrue(result.note, result.note.contains("floor"))
    assertTrue("Other boards belong in the rollup too", result.rows.size >= 1)
    composeRule.onNodeWithTag("portfolio_close").performClick()
  }

  @Test
  fun comparingApproachesOffersOrderingsAndHandsTheChosenOneToReview() {
    seed(withUnestimated = true)
    val viewModel = awaitBoard()
    val blocksBefore = runBlocking { database.planDao().getBlocksForBoard(boardId) }.size

    composeRule.openPlanTool("plan_scenarios_open")
    composeRule.waitUntil(timeoutMillis = 20_000) { viewModel.planScenarios.value.isNotEmpty() }
    composeRule.onNodeWithTag("plan_scenarios_dialog").assertIsDisplayed()
    val scenarios = viewModel.planScenarios.value
    assertEquals(listOf("due", "priority", "short"), scenarios.map { it.key })
    scenarios.forEach { composeRule.onNodeWithTag("scenario_${it.key}").assertIsDisplayed() }

    // Comparing writes nothing; choosing one only opens the ordinary proposal review.
    assertEquals(blocksBefore, runBlocking { database.planDao().getBlocksForBoard(boardId) }.size)
    val chosen = scenarios.first { it.placedTaskCount > 0 }
    composeRule.onNodeWithTag("scenario_choose_${chosen.key}").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.autoPlan.value != null }
    composeRule.onNodeWithTag("auto_plan_dialog").assertIsDisplayed()
    assertEquals(blocksBefore, runBlocking { database.planDao().getBlocksForBoard(boardId) }.size)
    composeRule.onNodeWithTag("auto_plan_cancel").performClick()
  }

  @Test
  fun aSavedViewRestoresGroupingAndTheColumnsThatWereShowing() {
    seed()
    val viewModel = awaitBoard()
    val columns = runBlocking { planRepository.observeColumns(boardId).first() }
    assertTrue("The seeded board should have columns to hide", columns.size >= 2)
    val hidden = columns.first().id

    composeRule.runOnIdle {
      viewModel.setOutlineGrouping(OutlineGrouping.PRIORITY)
      viewModel.setOutlineSort(OutlineSort.DUE)
      viewModel.toggleBoardColumnVisible(hidden)
    }
    // An empty set means every lane, so a preset is only in force once the set is non-empty.
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.outlineGrouping.value == OutlineGrouping.PRIORITY &&
        viewModel.visibleBoardColumnIds.value.isNotEmpty() &&
        hidden !in viewModel.visibleBoardColumnIds.value
    }

    var saved = false
    composeRule.runOnIdle {
      viewModel.saveCurrentPlanView("Grouped view $token") { ok -> saved = ok }
    }
    composeRule.waitUntil(timeoutMillis = 15_000) { saved }
    val view = runBlocking { database.planDao().getSavedViews(boardId) }.first { it.name.contains(token) }

    // Change everything the view remembered, then apply it and expect all of it back.
    composeRule.runOnIdle {
      viewModel.setOutlineGrouping(OutlineGrouping.SECTION)
      viewModel.setOutlineSort(OutlineSort.MANUAL)
      viewModel.showAllBoardColumns()
    }
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.outlineGrouping.value == OutlineGrouping.SECTION &&
        viewModel.visibleBoardColumnIds.value.isEmpty()
    }

    composeRule.runOnIdle {
      val savedView = viewModel.savedPlanViews.value.first { it.view.id == view.id }
      viewModel.applySavedPlanView(savedView)
    }
    // Every field the view restores is fed by its own settings flow, so the applied-view id can
    // land a beat after the presentation does. Wait for all of it rather than racing one of them.
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.outlineGrouping.value == OutlineGrouping.PRIORITY &&
        viewModel.outlineSort.value == OutlineSort.DUE &&
        viewModel.visibleBoardColumnIds.value.isNotEmpty() &&
        hidden !in viewModel.visibleBoardColumnIds.value &&
        viewModel.activeSavedPlanViewId.value != null
    }
    assertNotNull(viewModel.activeSavedPlanViewId.value)
  }

  /** Waits for the seeded board to be the active one, with the Plan screen open. */
  private fun awaitBoard(): BriefingViewModel {
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 20_000) {
      viewModel.activePlanBoardId.value == boardId && viewModel.planItems.value.isNotEmpty()
    }
    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.waitUntilExactlyOneExists(hasTestTag("plan_overflow"), timeoutMillis = 15_000)
    return viewModel
  }

  /** One estimated, scheduled task; optionally a second with no stated effort. */
  private fun seed(withUnestimated: Boolean = false): Long {
    var start = 0L
    runBlocking {
      planRepository.ensureCatalog()
      val originalCalendar = requireNotNull(planRepository.observeDefaultWorkingCalendar().first())
      workingCalendarSnapshot = originalCalendar.spec
      val testSpec =
        originalCalendar.spec.copy(
          weeklyWindows =
            (2..6).map { day -> WorkingWeekWindow(day, startMinute = 9 * 60, endMinute = 17 * 60) },
          overrides = emptyList(),
          minimumChunkMinutes = 30,
          maximumChunkMinutes = 120,
          bufferMinutes = 0,
        )
      planRepository.saveDefaultWorkingCalendar(testSpec)
      settingsSnapshot =
        listOf(
            SettingKeys.ACTIVE_PLAN_BOARD_ID,
            SettingKeys.PLAN_VIEW,
            SettingKeys.PLAN_OUTLINE_GROUPING,
            SettingKeys.PLAN_OUTLINE_SORT,
            SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID,
          )
          .associateWith { briefingRepository.readSetting(it) }
      val now = System.currentTimeMillis()
      database.planDao().insertBoard(
        PlanBoard(
          id = boardId,
          name = "Analysis $token",
          nameKey = "analysis $token",
          rank = database.planDao().maxBoardRank() + 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      // A board made directly through the DAO has no lanes, and a column preset needs lanes.
      database.planDao().insertColumns(
        listOf("Doing", "Done").mapIndexed { index, label ->
          PlanColumn(
            id = "analysis_column_${index}_$token",
            boardId = boardId,
            name = "$label $token",
            nameKey = "${label.lowercase()} $token",
            rank = (index + 1) * 1_000L,
            createdAt = now,
            updatedAt = now,
          )
        }
      )
      val task =
        planRepository.saveItem(
          PlanItemInput(boardId = boardId, title = taskTitle, effortMinutes = 60)
        )
      val zone = TimeZone.of(testSpec.zoneId)
      val rangeStart = ScheduleAnalysis.startOfDay(now, zone)
      val rangeEnd = ScheduleAnalysis.startOfDayOffset(rangeStart, 14, zone)
      val interval =
        WorkingCalendar.workingIntervals(testSpec, rangeStart, rangeEnd)
          .first { it.durationMinutes >= 60 && it.startAt >= now }
      start = interval.startAt
      planRepository.saveBlock(
        PlanBlockInput(planItemId = task.id, startAt = start, endAt = start + 3_600_000L)
      )
      if (withUnestimated) {
        planRepository.saveItem(PlanItemInput(boardId = boardId, title = "No estimate $token"))
        // Unscheduled effort, so the planner has something to place; the first task already has a block.
        planRepository.saveItem(
          PlanItemInput(boardId = boardId, title = "Unscheduled work $token", effortMinutes = 45)
        )
      }
    }
    return start
  }
}
