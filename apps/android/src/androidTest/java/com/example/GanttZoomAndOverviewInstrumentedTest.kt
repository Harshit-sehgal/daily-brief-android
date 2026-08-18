package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.ScheduleAnalysis
import com.example.core.WorkingCalendar
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanBlockInput
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
 * The overview strip and the zoom that goes with it.
 *
 * Zoom is an accelerator: whatever a pinch does, the 7/30/90 buttons must still report it, and the
 * same travel must be possible without a gesture at all.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GanttZoomAndOverviewInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "overview_board_$token"
  private var settingsSnapshot: Map<String, String?> = emptyMap()

  @After
  fun cleanUp() {
    runBlocking {
      settingsSnapshot.forEach { (key, value) ->
        if (value == null) briefingRepository.deleteSetting(key)
        else briefingRepository.writeSetting(key, value)
      }
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
  fun theOverviewTravelsWithoutAGestureAndZoomKeepsTheButtonsHonest() {
    val blockStart = seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle {
      viewModel.setGanttRangeDays(7)
      viewModel.selectDay(ScheduleAnalysis.startOfDay(blockStart))
    }
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId &&
        viewModel.planGanttItems.value.any { it.blocks.isNotEmpty() }
    }

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Gantt").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("gantt_overview")).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("gantt_overview").assertIsDisplayed()

    // Travel without a gesture: the same jumps the strip offers by tap are labelled actions.
    val startedAt = viewModel.selectedDay.value
    composeRule
      .onNodeWithTag("gantt_overview")
      .performCustomAccessibilityActionWithLabel("Move forward one range")
    composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.selectedDay.value > startedAt }
    val movedTo = viewModel.selectedDay.value
    assertEquals(
      "one range forward is exactly the visible range",
      ScheduleAnalysis.startOfDayOffset(startedAt, 7),
      movedTo,
    )

    composeRule
      .onNodeWithTag("gantt_overview")
      .performCustomAccessibilityActionWithLabel("Jump to the start of the plan")
    composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.selectedDay.value < movedTo }

    // Zoom lands on a range the buttons offer, and the buttons say so afterwards.
    composeRule.runOnIdle { viewModel.setGanttRangeDays(30) }
    composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.ganttRangeDays.value == 30 }
    composeRule.onNodeWithTag("gantt_range_30").assertIsSelected()
    assertTrue(
      "the overview stays available at every range",
      composeRule.onAllNodes(hasTestTag("gantt_overview")).fetchSemanticsNodes().isNotEmpty(),
    )
  }

  private fun seed(): Long =
    runBlocking {
      planRepository.ensureCatalog()
      settingsSnapshot =
        listOf(SettingKeys.ACTIVE_PLAN_BOARD_ID, SettingKeys.PLAN_VIEW, SettingKeys.GANTT_RANGE_DAYS)
          .associateWith { briefingRepository.readSetting(it) }
      val now = System.currentTimeMillis()
      database.planDao().insertBoard(
        PlanBoard(
          id = boardId,
          name = "Overview $token",
          nameKey = "overview $token",
          rank = database.planDao().maxBoardRank() + 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      briefingRepository.writeSetting(SettingKeys.GANTT_RANGE_DAYS, "7")
      val task =
        planRepository.saveItem(
          PlanItemInput(boardId = boardId, title = "Overview task $token", effortMinutes = 60)
        )
      val spec = requireNotNull(planRepository.observeDefaultWorkingCalendar().first()).spec
      val rangeStart = ScheduleAnalysis.startOfDayOffset(now, 1)
      val window =
        WorkingCalendar.workingIntervals(
            spec,
            rangeStart,
            ScheduleAnalysis.startOfDayOffset(rangeStart, 21),
          )
          .first { it.durationMinutes >= 120 }
      val blockStart = window.startAt + 30 * 60_000L
      planRepository.saveBlock(
        PlanBlockInput(planItemId = task.id, startAt = blockStart, endAt = blockStart + 60 * 60_000L)
      )
      blockStart
    }
}
