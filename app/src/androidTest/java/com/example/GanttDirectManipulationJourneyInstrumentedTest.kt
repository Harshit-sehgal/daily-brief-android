package com.example

import android.text.format.DateFormat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
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
import com.example.core.TimeFormatter
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
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendered proof that Gantt direct movement previews first and journals only explicit Apply. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GanttDirectManipulationJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val formatter by lazy {
    TimeFormatter(DateFormat.is24HourFormat(context), Locale.getDefault())
  }
  private val token = System.nanoTime().toString(16)
  private val boardId = "gantt_move_board_$token"
  private val boardName = "Gantt movement $token"
  private val taskTitle = "Directly movable block $token"
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
  fun cancelledPointerAndCancelWriteNothingWhileApplyJournalsOnceAndUndoIsExact() {
    val original = seedIsolatedBlock()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle {
      viewModel.setGanttRangeDays(7)
      viewModel.selectDay(ScheduleAnalysis.startOfDay(original.startAt))
    }
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId &&
        viewModel.ganttRangeDays.value == 7 &&
        viewModel.planGanttItems.value.any { it.item.id == taskId && it.blocks.contains(original) }
    }

    openGantt()
    enterMoveMode()
    assertExactPreview(original)
    composeRule
      .onNodeWithTag("gantt_move_start_handle")
      .assertIsDisplayed()
      .assertWidthIsAtLeast(48.dp)
      .assertHeightIsAtLeast(48.dp)
    composeRule
      .onNodeWithTag("gantt_move_end_handle")
      .assertIsDisplayed()
      .assertWidthIsAtLeast(48.dp)
      .assertHeightIsAtLeast(48.dp)

    // A one-hour block is under 3 dp wide at this zoom, so the move target, both handles and the
    // panel have to be laid out as neighbours. Anything overlapping the bar swallows its drag.
    assertDoesNotCover("gantt_move_start_handle", "gantt_move_bar")
    assertDoesNotCover("gantt_move_end_handle", "gantt_move_bar")
    assertDoesNotCover("gantt_move_mode", "gantt_move_bar")

    val historyBefore = runBlocking { planRepository.observeMutationHistory(boardId).first() }
    composeRule
      .onNodeWithTag("gantt_move_bar")
      .performTouchInput {
        down(center)
        moveBy(Offset(120f, 0f))
        cancel()
      }
    composeRule.waitForIdle()
    assertExactPreview(original)
    assertEquals(original, runBlocking { database.planDao().getBlock(original.id) })
    assertEquals(
      historyBefore.map { it.id },
      runBlocking { planRepository.observeMutationHistory(boardId).first() }.map { it.id },
    )

    composeRule
      .onNodeWithTag("gantt_move_bar")
      .performTouchInput {
        down(center)
        moveBy(Offset(120f, 0f))
        up()
      }
    composeRule.waitForIdle()
    assertNotEquals(exactStartText(original), previewStartText())
    assertEquals(original, runBlocking { database.planDao().getBlock(original.id) })
    assertEquals(
      historyBefore.map { it.id },
      runBlocking { planRepository.observeMutationHistory(boardId).first() }.map { it.id },
    )

    composeRule.onNodeWithTag("gantt_move_cancel").performClick()
    waitUntilGone("gantt_move_mode")
    assertEquals(original, runBlocking { database.planDao().getBlock(original.id) })
    assertEquals(
      historyBefore.map { it.id },
      runBlocking { planRepository.observeMutationHistory(boardId).first() }.map { it.id },
    )

    enterMoveMode()
    composeRule.onNodeWithTag("gantt_move_mode").performKeyInput {
      pressKey(Key.DirectionRight)
    }
    composeRule
      .onNodeWithTag("gantt_move_mode")
      .performCustomAccessibilityActionWithLabel("Extend 15 minutes")

    val expected =
      original.copy(
        startAt = original.startAt + 15 * 60_000L,
        endAt = original.endAt + 30 * 60_000L,
      )
    assertExactPreview(expected)
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasText("Ready to apply", substring = true)).fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeRule.onNodeWithTag("gantt_move_apply").assertIsEnabled().performClick()

    val edited = waitForBlock(expected)
    val historyAfter = runBlocking { planRepository.observeMutationHistory(boardId).first() }
    val newMutations = historyAfter.filter { candidate -> historyBefore.none { it.id == candidate.id } }
    assertEquals(1, newMutations.size)
    val editMutation = newMutations.single()
    assertEquals(PlanMutationType.BLOCK_EDIT, editMutation.mutationType)
    assertEquals(PlanMutationStatus.APPLIED, editMutation.status)
    assertEquals(original.position, edited.position)
    assertEquals(original.linkedEventId, edited.linkedEventId)

    composeRule.openPlanHistory()
    composeRule.onNodeWithText("Recent Plan changes").assertIsDisplayed()
    composeRule.onNodeWithText("Updated schedule for $taskTitle").performScrollTo().assertIsDisplayed()
    composeRule.onAllNodes(hasText("Undo") and hasAnyAncestor(isDialog()))[0].performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      runBlocking {
        database.planDao().getBlock(original.id) == original &&
          database.planDao().getPlanMutation(editMutation.id)?.status == PlanMutationStatus.UNDONE
      }
    }
    assertEquals(original, runBlocking { database.planDao().getBlock(original.id) })

    val locked = original.copy(locked = true, updatedAt = System.currentTimeMillis())
    runBlocking { database.planDao().updateBlock(locked) }
    composeRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.planGanttItems.value
        .flatMap { it.blocks }
        .any { it.id == locked.id && it.locked }
    }
    val lockedBar =
      hasTestTag("plan_gantt_item") and hasContentDescription(taskTitle, substring = true)
    composeRule.onNode(lockedBar).performScrollTo().performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("gantt_move_mode").assertDoesNotExist()
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
            SettingKeys.GANTT_RANGE_DAYS,
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
      briefingRepository.writeSetting(SettingKeys.GANTT_RANGE_DAYS, "7")
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

  private fun enterMoveMode() {
    val bar =
      hasTestTag("plan_gantt_item") and hasContentDescription(taskTitle, substring = true)
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(bar).fetchSemanticsNodes().size == 1
    }
    composeRule.onNode(bar).performScrollTo().performTouchInput { longClick() }
    waitUntilDisplayed("gantt_move_mode")
  }

  private fun assertExactPreview(expected: PlanBlock) {
    composeRule
      .onNodeWithTag("gantt_move_start")
      .assertTextEquals(exactStartText(expected))
    composeRule
      .onNodeWithTag("gantt_move_end")
      .assertTextEquals(
        "End · ${formatter.mediumDay(expected.endAt)} · ${formatter.time(expected.endAt)}"
      )
  }

  private fun assertDoesNotCover(coveringTag: String, coveredTag: String) {
    val covering = composeRule.onNodeWithTag(coveringTag).fetchSemanticsNode().boundsInRoot
    val covered = composeRule.onNodeWithTag(coveredTag).fetchSemanticsNode().boundsInRoot
    assertFalse(
      "$coveringTag $covering must not overlap $coveredTag $covered",
      covering.overlaps(covered),
    )
  }

  private fun exactStartText(block: PlanBlock): String =
    "Start · ${formatter.mediumDay(block.startAt)} · ${formatter.time(block.startAt)}"

  private fun previewStartText(): String =
    composeRule
      .onNodeWithTag("gantt_move_start")
      .fetchSemanticsNode()
      .config[SemanticsProperties.Text]
      .joinToString(separator = "") { it.text }

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
