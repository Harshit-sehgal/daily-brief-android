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
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
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
 * The planner proposes, the person decides, and one Undo puts the week back.
 *
 * The interesting assertions are the refusals: nothing is written before Apply, a task with no
 * stated effort is named rather than given an invented duration, and the whole batch is one
 * History entry.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AutoPlanJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "autoplan_board_$token"
  private val estimated = "Estimated work $token"
  private val unestimated = "Unestimated work $token"
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
  fun aProposalWritesNothingUntilApplyAndThenUndoesInOneStep() {
    seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId && viewModel.planItems.value.size == 2
    }
    composeRule.onNodeWithTag("tab_Plan").performClick()

    val blocksBefore = runBlocking { database.planDao().getBlocksForBoard(boardId) }.size
    val historyBefore = runBlocking { planRepository.observeMutationHistory(boardId, 200).first() }.size

    composeRule.runOnIdle { viewModel.proposePlan(days = 14) }
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.autoPlan.value != null }
    composeRule.onNodeWithTag("auto_plan_dialog").assertIsDisplayed()

    // Proposing writes nothing at all.
    assertEquals(blocksBefore, runBlocking { database.planDao().getBlocksForBoard(boardId) }.size)

    val proposal = requireNotNull(viewModel.autoPlan.value)
    assertTrue("the estimated task should get a proposal", proposal.proposals.isNotEmpty())
    proposal.proposals.forEach { assertTrue(it.reason, it.reason.isNotBlank()) }
    // The task with no effort is named, not silently dropped.
    val unplacedTitles =
      proposal.unplaced.mapNotNull { unplaced ->
        viewModel.planItems.value.firstOrNull { it.id == unplaced.itemId }?.title
      }
    assertTrue(unplacedTitles.toString(), unplacedTitles.any { it == unestimated })
    composeRule.onNodeWithTag("auto_plan_unplaced").assertIsDisplayed()

    composeRule.onNodeWithTag("auto_plan_apply").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      runBlocking { database.planDao().getBlocksForBoard(boardId) }.size > blocksBefore
    }

    // One entry in History for the whole plan, not one per block.
    val history = runBlocking { planRepository.observeMutationHistory(boardId, 200).first() }
    assertEquals(1, history.size - historyBefore)
    val applied = history.first()

    // And undoing it takes the whole week back.
    composeRule.runOnIdle { viewModel.undoPlanMutation(applied.id) }
    composeRule.waitUntil(timeoutMillis = 15_000) {
      runBlocking { database.planDao().getBlocksForBoard(boardId) }.size == blocksBefore
    }
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
          name = "Auto plan $token",
          nameKey = "auto plan $token",
          rank = database.planDao().maxBoardRank() + 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      planRepository.saveItem(
        PlanItemInput(boardId = boardId, title = estimated, effortMinutes = 60)
      )
      planRepository.saveItem(PlanItemInput(boardId = boardId, title = unestimated))
    }
  }
}
