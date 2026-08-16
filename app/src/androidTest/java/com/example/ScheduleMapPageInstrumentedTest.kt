package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.ScheduleAnalysis
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBlock
import com.example.data.model.PlanBoard
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The schedule map as its own page.
 *
 * Inside the Plan it is a chart under a root header, a tab row and a board line, and what is left on
 * a phone is a few rows — readable, not workable. As a page it gets the window the day timeline
 * already had, and a bar can simply be dragged: no long press to discover first, and still nothing
 * saved until Apply, which is the rule every move path in this app follows.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ScheduleMapPageInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "map_page_$token"
  private val taskTitle = "Draggable work $token"
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
  fun theMapOpensAsAPageWhereABarIsDraggedWithoutALongPressAndNothingSavesUntilApply() {
    val original = seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 20_000) {
      viewModel.activePlanBoardId.value == boardId && viewModel.planItems.value.isNotEmpty()
    }
    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Gantt").performClick()
    composeRule.waitUntilExactlyOneExists(hasTestTag("gantt_open_focus"), timeoutMillis = 15_000)

    composeRule.onNodeWithTag("gantt_open_focus").performClick()
    composeRule.waitUntilExactlyOneExists(hasTestTag("gantt_exit_focus"), timeoutMillis = 15_000)

    // A page takes the window: the bottom bar stands down while it is open.
    composeRule.onNodeWithTag("tab_Home").assertDoesNotExist()
    composeRule.onNodeWithTag("screen_gantt").assertIsDisplayed()

    // One gesture picks the block up and moves it — no long press, and no write.
    val bar = hasTestTag("plan_gantt_item") and hasContentDescription(taskTitle, substring = true)
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(bar).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onAllNodes(bar)[0].performTouchInput { swipeRight() }
    composeRule.waitUntilExactlyOneExists(hasTestTag("gantt_move_mode"), timeoutMillis = 10_000)
    assertEquals(
      "dragging must not write; only Apply does",
      original.startAt,
      runBlocking { database.planDao().getBlocksForBoard(boardId) }.single().startAt,
    )

    // Cancel leaves the schedule exactly as it was, and the page is still a page.
    composeRule.onNodeWithTag("gantt_move_cancel").performClick()
    composeRule.waitUntil(timeoutMillis = 10_000) {
      composeRule.onAllNodes(hasTestTag("gantt_move_mode")).fetchSemanticsNodes().isEmpty()
    }
    assertEquals(
      original.startAt,
      runBlocking { database.planDao().getBlocksForBoard(boardId) }.single().startAt,
    )

    // And the way back out returns to the Plan, with the bars back.
    composeRule.onNodeWithTag("gantt_exit_focus").performClick()
    composeRule.waitUntilExactlyOneExists(hasTestTag("tab_Home"), timeoutMillis = 15_000)
    composeRule.onNodeWithTag("screen_plan").assertIsDisplayed()
  }

  private fun seed(): PlanBlock {
    lateinit var block: PlanBlock
    runBlocking {
      planRepository.ensureCatalog()
      settingsSnapshot =
        listOf(SettingKeys.ACTIVE_PLAN_BOARD_ID, SettingKeys.PLAN_VIEW)
          .associateWith { briefingRepository.readSetting(it) }
      val now = System.currentTimeMillis()
      database.planDao().insertBoard(
        PlanBoard(
          id = boardId,
          name = "Map page $token",
          nameKey = "map page $token",
          rank = database.planDao().maxBoardRank() + 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      val task =
        planRepository.saveItem(
          PlanItemInput(boardId = boardId, title = taskTitle, effortMinutes = 90)
        )
      val start = ScheduleAnalysis.startOfDay(now) + 10 * 3_600_000L
      block =
        PlanBlock(
          id = "block_$token",
          planItemId = task.id,
          startAt = start,
          endAt = start + 90 * 60_000L,
          createdAt = now,
          updatedAt = now,
        )
      database.planDao().insertBlock(block)
    }
    return block
  }
}
