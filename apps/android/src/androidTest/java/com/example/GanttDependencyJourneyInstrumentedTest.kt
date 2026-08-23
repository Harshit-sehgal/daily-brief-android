package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.PlanMenu.openMapOption
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
import com.example.data.database.AppDatabase
import com.example.data.model.PlanBoard
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanMutationStatus
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanMutationType
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

/** Rendered proof of the non-drag dependency ledger, its CPM result, and durable Undo. */
@RunWith(AndroidJUnit4::class)
class GanttDependencyJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }
  private val token = System.nanoTime().toString(16)
  private val boardId = "dependency_journey_board_$token"
  private val boardName = "Dependency journey $token"
  private val predecessorTitle = "Research brief $token"
  private val successorTitle = "Publish brief $token"
  private var predecessorId: String? = null
  private var successorId: String? = null
  private var settingsSnapshot: Map<String, String?> = emptyMap()

  @After
  fun cleanUp() {
    runBlocking {
      if (settingsSnapshot.isNotEmpty()) restoreSettings(settingsSnapshot)

      database.planDao().getDependenciesForBoard(boardId).forEach {
        database.planDao().deleteDependency(it)
      }
      successorId?.let { database.planDao().getItem(it) }?.let {
        database.planDao().deleteItem(it)
      }
      predecessorId?.let { database.planDao().getItem(it) }?.let {
        database.planDao().deleteItem(it)
      }
      database.openHelper.writableDatabase.execSQL(
        "DELETE FROM plan_mutations WHERE boardId = ?",
        arrayOf(boardId),
      )
      database.planDao().getBoard(boardId)?.let { database.planDao().deleteBoard(it) }
    }
  }

  @Test
  fun addEditDeleteAndHistoryUndoKeepDependencyLedgerAndCriticalPathConsistent() {
    seedIsolatedPlan()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId &&
        viewModel.planItems.value.map { it.id }.containsAll(
          listOf(requireNotNull(predecessorId), requireNotNull(successorId))
        )
    }

    openGantt()
    openDependencies()

    // A self-edge is rejected in the visible form before any repository write can run.
    composeRule.onNodeWithTag("dependency_add").performClick()
    waitUntilDisplayed("dependency_editor")
    selectPopupOption("dependency_successor", predecessorTitle)
    composeRule.onNodeWithText("Choose two different tasks.").assertIsDisplayed()
    composeRule.onNodeWithTag("dependency_editor_confirm").assertIsNotEnabled()
    assertTrue(runBlocking { database.planDao().getDependenciesForBoard(boardId).isEmpty() })

    selectPopupOption("dependency_successor", successorTitle)
    composeRule.onNodeWithTag("dependency_lag").performTextReplacement("45")
    composeRule.onNodeWithTag("dependency_editor_confirm").performClick()

    val dependency = waitForSingleDependency(lagMinutes = 45)
    assertEquals(PlanDependencyType.FINISH_TO_START, dependency.type)
    assertDependencyLedger(dependency, "+45m lag")

    closeDialog()
    waitForCriticalPath(viewModel, expectedMinutes = 135)
    composeRule
      .onNodeWithText(
        "critical path 2 tasks, 135 min, all durations verified",
        substring = true,
      )
      .assertIsDisplayed()

    openDependencies()
    composeRule
      .onNodeWithTag("dependency_edit_${dependency.id}")
      .performScrollTo()
      .performClick()
    waitUntilDisplayed("dependency_editor")
    composeRule.onNodeWithTag("dependency_lag").performTextReplacement("90")
    composeRule.onNodeWithTag("dependency_editor_confirm").performClick()
    waitForSingleDependency(lagMinutes = 90)
    assertDependencyLedger(dependency, "+1h 30m lag")

    closeDialog()
    waitForCriticalPath(viewModel, expectedMinutes = 180)
    composeRule
      .onNodeWithText(
        "critical path 2 tasks, 180 min, all durations verified",
        substring = true,
      )
      .assertIsDisplayed()

    openDependencies()
    composeRule
      .onNodeWithTag("dependency_delete_${dependency.id}")
      .performScrollTo()
      .performClick()
    waitUntilDisplayed("dependency_delete_dialog")
    composeRule.onNodeWithTag("dependency_delete_confirm").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      runBlocking { database.planDao().getDependenciesForBoard(boardId).isEmpty() }
    }
    composeRule.onNodeWithTag("dependency_empty").assertIsDisplayed()
    val deleteMutation =
      runBlocking {
        planRepository
          .observeMutationHistory(boardId)
          .first()
          .first { it.mutationType == PlanMutationType.DEPENDENCY_DELETE }
      }
    assertEquals(PlanMutationStatus.APPLIED, deleteMutation.status)

    closeDialog()
    composeRule.openPlanHistory()
    composeRule.onNodeWithText("Recent Plan changes").assertIsDisplayed()
    composeRule.onNodeWithText("Removed task dependency").performScrollTo().assertIsDisplayed()
    composeRule
      .onAllNodes(hasText("Undo") and hasAnyAncestor(isDialog()))[0]
      .performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      runBlocking {
        database.planDao().getDependency(dependency.id)?.lagMinutes == 90 &&
          database.planDao().getPlanMutation(deleteMutation.id)?.status ==
            PlanMutationStatus.UNDONE
      }
    }

    openDependencies()
    assertDependencyLedger(dependency, "+1h 30m lag")

    // Undo restored the exact edge, so attempting the same pair again fails closed as a duplicate.
    composeRule.onNodeWithTag("dependency_add").performClick()
    waitUntilDisplayed("dependency_editor")
    composeRule.onNodeWithText("That relationship already exists.").assertIsDisplayed()
    composeRule.onNodeWithTag("dependency_editor_confirm").assertIsNotEnabled()
    assertEquals(
      1,
      runBlocking { database.planDao().getDependenciesForBoard(boardId).size },
    )
    composeRule
      .onNode(hasText("Cancel") and hasAnyAncestor(isDialog()))
      .performClick()
    composeRule.onNodeWithTag("dependency_editor").assertDoesNotExist()
  }

  @Test
  fun canvasLinkModeSeedsTheEditorAndCreatesTheSelectedRelationship() {
    seedIsolatedPlan()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.activePlanBoardId.value == boardId &&
        viewModel.planItems.value.map { it.id }.containsAll(
          listOf(requireNotNull(predecessorId), requireNotNull(successorId))
        )
    }

    openGantt()
    composeRule.openMapOption("gantt_link_tasks")
    waitUntilDisplayed("gantt_dependency_link_mode")

    composeRule.onNodeWithTag("gantt_dependency_task_${requireNotNull(predecessorId)}").performClick()
    composeRule
      .onNodeWithTag("gantt_dependency_link_source")
      .assertTextContains(predecessorTitle, substring = true)
    composeRule.onNodeWithTag("gantt_dependency_task_${requireNotNull(successorId)}").performClick()
    waitUntilDisplayed("dependency_editor")

    composeRule
      .onNodeWithTag("dependency_predecessor")
      .assertTextContains(predecessorTitle, substring = true)
    composeRule
      .onNodeWithTag("dependency_successor")
      .assertTextContains(successorTitle, substring = true)
    composeRule.onNodeWithTag("dependency_editor_confirm").performClick()

    val dependency = waitForSingleDependency(lagMinutes = 0)
    assertEquals(requireNotNull(predecessorId), dependency.predecessorId)
    assertEquals(requireNotNull(successorId), dependency.successorId)
    assertEquals(PlanDependencyType.FINISH_TO_START, dependency.type)
  }

  private fun seedIsolatedPlan() {
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
      predecessorId =
        planRepository
          .saveItem(
            PlanItemInput(
              boardId = boardId,
              title = predecessorTitle,
              effortMinutes = 60,
            )
          )
          .id
      successorId =
        planRepository
          .saveItem(
            PlanItemInput(
              boardId = boardId,
              title = successorTitle,
              effortMinutes = 30,
            )
          )
          .id
    }
  }

  private fun openGantt() {
    composeRule.onNodeWithTag("tab_Plan").performClick()
    waitUntilDisplayed("screen_plan")
    composeRule.onNodeWithText("Gantt").performClick()
    waitUntilDisplayed("screen_gantt")
  }

  private fun openDependencies() {
    composeRule.openMapOption("gantt_dependencies")
    waitUntilDisplayed("gantt_dependency_dialog")
    waitUntilDisplayed("dependency_manager")
  }

  private fun selectPopupOption(fieldTag: String, option: String) {
    composeRule.onNodeWithTag(fieldTag).performClick()
    composeRule.onNode(hasText(option) and hasAnyAncestor(isPopup())).performClick()
  }

  private fun waitForSingleDependency(lagMinutes: Int): PlanDependency {
    var match: PlanDependency? = null
    composeRule.waitUntil(timeoutMillis = 5_000) {
      match =
        runBlocking {
          database.planDao().getDependenciesForBoard(boardId).singleOrNull()?.takeIf {
            it.predecessorId == predecessorId &&
              it.successorId == successorId &&
              it.type == PlanDependencyType.FINISH_TO_START &&
              it.lagMinutes == lagMinutes
          }
        }
      match != null
    }
    assertNotNull(match)
    return requireNotNull(match)
  }

  private fun assertDependencyLedger(dependency: PlanDependency, offset: String) {
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("dependency_row_${dependency.id}"))
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeRule
      .onNodeWithTag("dependency_row_${dependency.id}")
      .performScrollTo()
      .assertIsDisplayed()
    composeRule
      .onNodeWithText("$predecessorTitle → $successorTitle")
      .assertIsDisplayed()
    composeRule.onNodeWithText("FS · Finish to start").assertIsDisplayed()
    composeRule.onNodeWithText(offset).assertIsDisplayed()
  }

  private fun waitForCriticalPath(viewModel: BriefingViewModel, expectedMinutes: Long) {
    composeRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.criticalPath.value.result?.let {
        it.isComplete &&
          it.projectDurationMinutes == expectedMinutes &&
          it.taskTimings.count { timing -> timing.isCritical == true } == 2
      } == true
    }
  }

  private fun closeDialog() {
    composeRule.onNode(hasText("Close") and hasAnyAncestor(isDialog())).performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("gantt_dependency_dialog")).fetchSemanticsNodes().isEmpty()
    }
  }

  private fun waitUntilDisplayed(tag: String, timeoutMillis: Long = 15_000) {
    composeRule.waitUntil(timeoutMillis = timeoutMillis) {
      composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag(tag).assertIsDisplayed()
  }

  private suspend fun restoreSettings(snapshot: Map<String, String?>) {
    snapshot.forEach { (key, value) ->
      if (value == null) briefingRepository.deleteSetting(key)
      else briefingRepository.writeSetting(key, value)
    }
  }
}
