package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.ScheduleAnalysis
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Schedule map has to be a map at every size a phone comes in.
 *
 * On a 360 dp screen its chrome once left the canvas under one row: the day header drew and not a
 * single bar, so there was nothing to read and nothing to long-press. Run this with the device
 * resized (`adb shell wm size 720x1280 && adb shell wm density 320`) and it fails on exactly that.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GanttSmallScreenInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "gantt_small_$token"
  private var settingsSnapshot: Map<String, String?> = emptyMap()

  @After
  fun cleanUp() {
    runBlocking {
      settingsSnapshot.forEach { (key, value) ->
        if (value == null) briefingRepository.deleteSetting(key) else briefingRepository.writeSetting(key, value)
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
  fun theScheduleMapDrawsWorkAndAcceptsALongPressAtThisSize() {
    seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 20_000) {
      viewModel.activePlanBoardId.value == boardId && viewModel.planItems.value.isNotEmpty()
    }
    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.waitUntilExactlyOneExists(hasTestTag("plan_view_switcher"), timeoutMillis = 15_000)
    composeRule.onNodeWithText("Gantt").performClick()
    composeRule.waitUntilExactlyOneExists(hasTestTag("screen_gantt"), timeoutMillis = 15_000)

    // A bar for app-owned work has to be on screen, not merely in the tree.
    val bar = hasTestTag("plan_gantt_item") and hasContentDescription("Visible work $token", substring = true)
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(bar).fetchSemanticsNodes().isNotEmpty()
    }
    val bars = composeRule.onAllNodes(bar).fetchSemanticsNodes()
    val screen = composeRule.onNodeWithTag("screen_gantt").fetchSemanticsNode().boundsInRoot
    val visible =
      bars.filter { node ->
        val b = node.boundsInRoot
        b.width > 0f && b.height > 0f && b.left < screen.right && b.right > screen.left &&
          b.top < screen.bottom && b.bottom > screen.top
      }
    assertTrue(
      "no Plan bar is on screen: ${bars.size} in the tree, screen is $screen, " +
        "bars at ${bars.map { it.boundsInRoot }}",
      visible.isNotEmpty(),
    )

    // And it can still be taken into move mode, which is what a map is for.
    composeRule.onAllNodes(bar)[0].performTouchInput { longClick() }
    composeRule.waitUntilExactlyOneExists(hasTestTag("gantt_move_mode"), timeoutMillis = 10_000)
    composeRule.onNodeWithTag("gantt_move_mode").assertIsDisplayed()
    composeRule.onNodeWithTag("gantt_move_cancel").performClick()
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
          name = "Small screen $token",
          nameKey = "small screen $token",
          rank = database.planDao().maxBoardRank() + 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      val task =
        planRepository.saveItem(
          PlanItemInput(boardId = boardId, title = "Visible work $token", effortMinutes = 120)
        )
      // Today, so the bar sits in the first column the canvas shows.
      val start = ScheduleAnalysis.startOfDay(now) + 10 * 3_600_000L
      database.planDao().insertBlock(
        com.example.data.model.PlanBlock(
          id = "block_$token",
          planItemId = task.id,
          startAt = start,
          endAt = start + 2 * 3_600_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
    }
  }
}
