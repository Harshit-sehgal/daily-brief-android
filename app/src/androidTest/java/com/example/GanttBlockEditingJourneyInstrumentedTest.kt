package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBlock
import com.example.data.model.PlanBoard
import com.example.data.model.PlanMutationStatus
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanMutationType
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendered proof that existing app-owned Gantt blocks edit, cancel, delete and Undo exactly. */
@RunWith(AndroidJUnit4::class)
class GanttBlockEditingJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "gantt_edit_board_$token"
  private val boardName = "Gantt edit $token"
  private val taskTitle = "Editable Gantt block $token"
  private var taskId: String? = null
  private var blockId: String? = null
  private var settingsSnapshot: Map<String, String?> = emptyMap()

  @After
  fun cleanUp() {
    runBlocking {
      if (settingsSnapshot.isNotEmpty()) restoreSettings(settingsSnapshot)
      blockId?.let { database.planDao().getBlock(it) }?.let { database.planDao().deleteBlock(it) }
      taskId?.let { database.planDao().getItem(it) }?.let { database.planDao().deleteItem(it) }
      database.openHelper.writableDatabase.execSQL(
        "DELETE FROM plan_mutations WHERE boardId = ?",
        arrayOf(boardId),
      )
      database.planDao().getBoard(boardId)?.let { database.planDao().deleteBoard(it) }
    }
  }

  @Test
  fun editCancelIncrementsDeleteAndHistoryUndoPreserveTheExactPlanBlock() {
    val original = seedIsolatedBlock()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle { viewModel.selectDay(ScheduleAnalysis.startOfDay(original.startAt)) }
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId &&
        viewModel.planGanttItems.value.any { it.item.id == taskId && it.blocks.contains(original) }
    }

    openGantt()
    openBlockEditor()
    composeRule.onNodeWithTag("gantt_block_start_date").assertIsDisplayed()
    composeRule.onNodeWithTag("gantt_block_start_time").assertIsDisplayed()
    composeRule.onNodeWithTag("gantt_block_end_date").assertIsDisplayed()
    composeRule.onNodeWithTag("gantt_block_end_time").assertIsDisplayed()
    composeRule.onNodeWithTag("gantt_block_duration").assertTextContains("60")
    composeRule.onNodeWithTag("gantt_block_lock").assertIsNotSelected()

    val historyBeforeCancel = runBlocking { planRepository.observeMutationHistory(boardId).first() }
    composeRule
      .onNode(hasText("Cancel") and hasAnyAncestor(isDialog()))
      .performClick()
    waitUntilGone("gantt_block_editor")
    assertEquals(original, runBlocking { database.planDao().getBlock(original.id) })
    assertEquals(
      historyBeforeCancel.map { it.id },
      runBlocking { planRepository.observeMutationHistory(boardId).first() }.map { it.id },
    )

    openBlockEditor()
    composeRule
      .onNodeWithTag("gantt_block_move_later")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()
    composeRule
      .onNodeWithTag("gantt_block_extend")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasText("Ready to schedule within working time and fixed commitments."))
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeRule
      .onNodeWithTag("gantt_block_save")
      .assertIsEnabled()
      .performClick()

    val expected =
      original.copy(
        startAt = original.startAt + 15 * 60_000L,
        endAt = original.endAt + 30 * 60_000L,
      )
    val edited = waitForBlock(expected)
    val editMutation =
      runBlocking {
        planRepository
          .observeMutationHistory(boardId)
          .first()
          .first { it.mutationType == PlanMutationType.BLOCK_EDIT }
      }
    assertEquals(PlanMutationStatus.APPLIED, editMutation.status)
    assertEquals(original.position, edited.position)
    assertEquals(original.linkedEventId, edited.linkedEventId)

    openBlockEditor()
    composeRule
      .onNodeWithTag("gantt_block_delete")
      .assertIsDisplayed()
      .performClick()
    composeRule.onNodeWithTag("gantt_block_delete_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("gantt_block_delete_confirm").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      runBlocking { database.planDao().getBlock(original.id) == null }
    }
    assertNull(runBlocking { database.planDao().getBlock(original.id) })

    val deleteMutation =
      runBlocking {
        planRepository
          .observeMutationHistory(boardId)
          .first()
          .first { it.mutationType == PlanMutationType.BLOCK_DELETE }
      }
    assertEquals(PlanMutationStatus.APPLIED, deleteMutation.status)

    composeRule.openPlanHistory()
    composeRule.onNodeWithText("Recent Plan changes").assertIsDisplayed()
    composeRule.onNodeWithText("Removed schedule for $taskTitle").performScrollTo().assertIsDisplayed()
    composeRule
      .onAllNodes(hasText("Undo") and hasAnyAncestor(isDialog()))[0]
      .performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      runBlocking {
        database.planDao().getBlock(original.id) == edited &&
          database.planDao().getPlanMutation(deleteMutation.id)?.status ==
            PlanMutationStatus.UNDONE
      }
    }
    assertEquals(edited, runBlocking { database.planDao().getBlock(original.id) })
  }

  private fun seedIsolatedBlock(): PlanBlock =
    runBlocking {
      planRepository.ensureCatalog()
      settingsSnapshot =
        listOf(
            SettingKeys.ACTIVE_PLAN_BOARD_ID,
            SettingKeys.PLAN_VIEW,
            SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID,
            SettingKeys.HOME_DESTINATION,
          )
          .associateWith { briefingRepository.readSetting(it) }

      val now = System.currentTimeMillis()
      database.planDao().insertBoard(
        PlanBoard(
          id = boardId,
          name = boardName,
          nameKey = boardName.lowercase(),
          rank = database.planDao().maxBoardRank() + 10_000L,
          createdAt = now,
          updatedAt = now,
        )
      )
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      val task =
        planRepository.saveItem(
          PlanItemInput(
            boardId = boardId,
            title = taskTitle,
            effortMinutes = 60,
          )
        )
      taskId = task.id

      val spec = requireNotNull(planRepository.observeDefaultWorkingCalendar().first()).spec
      val rangeStart = ScheduleAnalysis.startOfDayOffset(System.currentTimeMillis(), 1)
      val rangeEnd = ScheduleAnalysis.startOfDayOffset(rangeStart, 21)
      val interval =
        WorkingCalendar.workingIntervals(spec, rangeStart, rangeEnd).first {
          it.durationMinutes >= 180
        }
      val startAt = interval.startAt + 60 * 60_000L
      val block =
        planRepository.saveBlock(
          PlanBlockInput(
            planItemId = task.id,
            startAt = startAt,
            endAt = startAt + 60 * 60_000L,
          )
        )
      blockId = block.id
      block
    }

  private fun openGantt() {
    composeRule.onNodeWithTag("tab_Plan").performClick()
    waitUntilDisplayed("screen_plan")
    composeRule.onNodeWithText("Gantt").performClick()
    waitUntilDisplayed("screen_gantt")
  }

  private fun openBlockEditor() {
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(
          hasTestTag("plan_gantt_item") and
            hasContentDescription(taskTitle, substring = true)
        )
        .fetchSemanticsNodes()
        .size == 1
    }
    composeRule
      .onNode(
        hasTestTag("plan_gantt_item") and
          hasContentDescription(taskTitle, substring = true)
      )
      .performScrollTo()
      .performClick()
    waitUntilDisplayed("gantt_block_editor")
    composeRule.onNodeWithText("Edit schedule block").assertIsDisplayed()
  }

  private fun waitForBlock(expected: PlanBlock): PlanBlock {
    var stored: PlanBlock? = null
    composeRule.waitUntil(timeoutMillis = 5_000) {
      stored = runBlocking { database.planDao().getBlock(expected.id) }
      stored?.let {
        it.startAt == expected.startAt &&
          it.endAt == expected.endAt &&
          it.locked == expected.locked &&
          it.position == expected.position &&
          it.linkedEventId == expected.linkedEventId
      } == true
    }
    assertNotNull(stored)
    return requireNotNull(stored)
  }

  private fun waitUntilDisplayed(tag: String, timeoutMillis: Long = 15_000) {
    composeRule.waitUntil(timeoutMillis = timeoutMillis) {
      composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag(tag).assertIsDisplayed()
  }

  private fun waitUntilGone(tag: String) {
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
    }
  }

  private suspend fun restoreSettings(snapshot: Map<String, String?>) {
    snapshot.forEach { (key, value) ->
      if (value == null) briefingRepository.deleteSetting(key)
      else briefingRepository.writeSetting(key, value)
    }
  }
}
