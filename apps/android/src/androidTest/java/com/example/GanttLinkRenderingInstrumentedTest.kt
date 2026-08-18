package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Schedule map draws links between scheduled work — and says so when it cannot.
 *
 * The geometry is unit-tested; this is the rendered half: that the overlay exists on the real
 * canvas, and that a dependency pointing at unscheduled work produces a disclosure rather than a
 * line to nowhere or a silent omission.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GanttLinkRenderingInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "link_board_$token"
  private var settingsSnapshot: Map<String, String?> = emptyMap()

  @After
  fun cleanUp() {
    runBlocking {
      settingsSnapshot.forEach { (key, value) ->
        if (value == null) briefingRepository.deleteSetting(key)
        else briefingRepository.writeSetting(key, value)
      }
      database.planDao().getDependenciesForBoard(boardId).forEach {
        database.planDao().deleteDependency(it)
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
  fun linksAreDrawnBetweenScheduledWorkAndUndrawableOnesAreDisclosed() {
    val firstDay = seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle {
      viewModel.setGanttRangeDays(7)
      viewModel.selectDay(ScheduleAnalysis.startOfDay(firstDay))
    }
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId &&
        viewModel.planGanttItems.value.count { it.blocks.isNotEmpty() } == 2
    }

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Gantt").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("screen_gantt")).fetchSemanticsNodes().isNotEmpty()
    }

    // The overlay is part of the canvas, and it takes no pointer input of its own.
    composeRule.onNodeWithTag("gantt_link_overlay").assertIsDisplayed()

    // One dependency joins two scheduled tasks and one points at work with no blocks, so the
    // map must draw what it can and say what it could not.
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("gantt_link_disclosure")).fetchSemanticsNodes().isNotEmpty()
    }
    val disclosure =
      composeRule.onNodeWithTag("gantt_link_disclosure").fetchSemanticsNode()
    val text = disclosure.config.getOrElseNullable(
      androidx.compose.ui.semantics.SemanticsProperties.Text
    ) { null }.orEmpty().joinToString(" ") { it.text }
    assertTrue(text, text.contains("not scheduled"))
    assertTrue("the ledger is named as the complete list: $text", text.contains("ledger"))
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
          name = "Links $token",
          nameKey = "links $token",
          rank = database.planDao().maxBoardRank() + 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      briefingRepository.writeSetting(SettingKeys.GANTT_RANGE_DAYS, "7")

      val spec = requireNotNull(planRepository.observeDefaultWorkingCalendar().first()).spec
      val rangeStart = ScheduleAnalysis.startOfDayOffset(now, 1)
      val windows =
        WorkingCalendar.workingIntervals(spec, rangeStart, ScheduleAnalysis.startOfDayOffset(rangeStart, 14))
          .filter { it.durationMinutes >= 120 }
      val first =
        planRepository.saveItem(PlanItemInput(boardId = boardId, title = "Predecessor $token", effortMinutes = 60))
      val second =
        planRepository.saveItem(PlanItemInput(boardId = boardId, title = "Successor $token", effortMinutes = 60))
      val unscheduled =
        planRepository.saveItem(PlanItemInput(boardId = boardId, title = "Unscheduled $token", effortMinutes = 60))

      val firstStart = windows[0].startAt + 30 * 60_000L
      planRepository.saveBlock(
        PlanBlockInput(planItemId = first.id, startAt = firstStart, endAt = firstStart + 60 * 60_000L)
      )
      val secondStart = windows[2].startAt + 30 * 60_000L
      planRepository.saveBlock(
        PlanBlockInput(planItemId = second.id, startAt = secondStart, endAt = secondStart + 60 * 60_000L)
      )

      planRepository.addDependency(first.id, second.id)
      planRepository.addDependency(second.id, unscheduled.id)
      firstStart
    }
}
