package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.PlanMenu.openMapOption
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen belongs to the schedule, not to the explanations of it.
 *
 * The Gantt once carried a title, a five-line legend, range chips, a date line and a critical-path
 * line before the first bar — roughly 300 dp of standing chrome on a phone, which left the canvas
 * about a fifth of the window and was the real reason the move panel had nowhere to go. This pins
 * the ratio so that creeping back is a failing test rather than a slow disappointment.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ScheduleMapProportionInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "proportion_board_$token"
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
  fun theScheduleMapKeepsMostOfThePhoneScreenAndItsLegendIsOnRequest() {
    val start = seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle {
      viewModel.setGanttRangeDays(7)
      viewModel.selectDay(ScheduleAnalysis.startOfDay(start))
    }
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId &&
        viewModel.planGanttItems.value.any { it.blocks.isNotEmpty() }
    }

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Gantt").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("gantt_link_overlay")).fetchSemanticsNodes().isNotEmpty()
    }

    val screenHeight = composeRule.onRoot().fetchSemanticsNode().size.height
    val canvasTop = composeRule.onNodeWithTag("gantt_link_overlay").fetchSemanticsNode().boundsInRoot.top
    val chromeShare = canvasTop / screenHeight
    assertTrue(
      "the schedule map should start in the top half of the screen, started at ${chromeShare * 100}%",
      chromeShare < 0.5f,
    )

    // The legend is not gone, it is asked for — and it says which schedule the shading used.
    assertTrue(
      "the legend should be closed by default",
      composeRule.onAllNodes(hasTestTag("gantt_legend")).fetchSemanticsNodes().isEmpty(),
    )
    composeRule.openMapOption("gantt_legend_toggle")
    composeRule.onNodeWithTag("gantt_legend").assertIsDisplayed()
    composeRule.onNodeWithText("Solid bars are Plan blocks", substring = true).assertIsDisplayed()
    composeRule.openMapOption("gantt_legend_toggle")
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("gantt_legend")).fetchSemanticsNodes().isEmpty()
    }

    // The range and what the critical path can say share one line rather than owning two.
    val status = composeRule.onNodeWithTag("gantt_status_line").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "the status line should sit above the canvas, was $status with canvas top $canvasTop",
      status.height > 0f && status.top < canvasTop,
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
          name = "Proportion $token",
          nameKey = "proportion $token",
          rank = database.planDao().maxBoardRank() + 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      briefingRepository.writeSetting(SettingKeys.GANTT_RANGE_DAYS, "7")
      val task =
        planRepository.saveItem(
          PlanItemInput(boardId = boardId, title = "Proportion task $token", effortMinutes = 60)
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
