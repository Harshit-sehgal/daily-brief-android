package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import com.example.data.database.AppDatabase
import com.example.data.model.PlanMutationStatus
import com.example.data.model.PlanSurface
import com.example.data.model.SavedView
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendered proof that the durable Plan foundations remain truthful across recreation. */
@RunWith(AndroidJUnit4::class)
class PlanningFoundationsJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database
    get() = AppDatabase.getDatabase(context)

  @Test
  fun savedGanttViewCreatesAppliesRecreatesDeletesAndHidesMalformedRows() {
    val token = System.nanoTime().toString(16)
    val viewName = "Quarter view $token"
    val malformedName = "Malformed view $token"
    val malformedId = "malformed_saved_view_$token"
    val briefingRepository = BriefingRepository(context)
    val planRepository = PlanRepository(context)
    val restored =
      snapshotSettings(
        briefingRepository,
        SettingKeys.PLAN_VIEW,
        SettingKeys.GANTT_RANGE_DAYS,
        SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID,
      )
    var createdId: String? = null

    val boardId =
      runBlocking {
        planRepository.ensureCatalog()
        requireNotNull(briefingRepository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID))
      }
    val now = System.currentTimeMillis()
    val malformed =
      SavedView(
        id = malformedId,
        boardId = boardId,
        name = malformedName,
        nameKey = "malformed-view-$token",
        surface = PlanSurface.GANTT,
        filtersJson = "{bad",
        rangeDays = 90,
        rank = runBlocking { database.planDao().maxSavedViewRank(boardId) } + 1_000_000L,
        createdAt = now,
        updatedAt = now,
      )
    runBlocking { database.planDao().insertSavedView(malformed) }

    try {
      openPlan()
      composeRule.onNodeWithText("Gantt").performClick()
      waitUntilDisplayed("screen_gantt")
      composeRule.onNodeWithTag("gantt_range_90").performClick()
      composeRule.onNodeWithTag("gantt_range_90").assertIsSelected()

      composeRule.startSavingView()
      composeRule.onNodeWithTag("saved_view_name").performTextInput(viewName)
      composeRule.onNodeWithTag("confirm_save_plan_view").performClick()
      // The applied view is named inside View options now rather than on a control above the work.
      composeRule.openViewOptions()
      composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithText(viewName, substring = true).fetchSemanticsNodes().size == 1
      }
      composeRule.onNodeWithText(malformedName, substring = true).assertDoesNotExist()
      composeRule.closeViewOptions()
      createdId =
        runBlocking {
          planRepository
            .observeSavedViews(boardId)
            .first()
            .single { it.view.name == viewName }
            .view
            .id
        }

      composeRule.activityRule.scenario.recreate()
      openPlan()
      composeRule.openViewOptions()
      composeRule.waitUntil(timeoutMillis = 15_000) {
        composeRule.onAllNodesWithText(viewName, substring = true).fetchSemanticsNodes().size == 1
      }
      composeRule.onNodeWithText(malformedName, substring = true).assertDoesNotExist()
      composeRule.closeViewOptions()

      composeRule.onNodeWithText("Outline").performClick()
      waitUntilDisplayed("screen_plan_outline")
      composeRule.openViewOptions()
      composeRule.onNodeWithText(viewName, substring = true).performClick()
      waitUntilDisplayed("screen_gantt")
      composeRule.onNodeWithTag("gantt_range_90").assertIsSelected()

      composeRule.openManageViews()
      composeRule.onNodeWithTag("plan_view_delete").performClick()
      composeRule.onNodeWithText("Delete saved view?").assertIsDisplayed()
      // By tag: the manage dialog behind the confirmation has a Delete of its own.
      composeRule.onNodeWithTag("confirm_delete_plan_view").performClick()
      // Storage is the assertion that matters; the list behind the confirmation is just a list.
      composeRule.waitUntil(timeoutMillis = 10_000) {
        runBlocking { database.planDao().getSavedView(requireNotNull(createdId)) } == null
      }
      composeRule.closeManageViews()
      composeRule.onNodeWithTag("plan_manage_views_dialog").assertDoesNotExist()
    } finally {
      runBlocking {
        createdId?.let { id ->
          database.planDao().getSavedView(id)?.let { database.planDao().deleteSavedView(it) }
        }
        database.planDao().getSavedView(malformedId)?.let { database.planDao().deleteSavedView(it) }
        restoreSettings(briefingRepository, restored)
      }
    }
  }

  @Test
  fun importDisclosureIsAbsentForFreshStateThenPersistsAcknowledgementForUpgradeState() {
    val repository = BriefingRepository(context)
    val restored =
      snapshotSettings(
        repository,
        SettingKeys.PLAN_LEGACY_CATALOG_IMPORTED,
        SettingKeys.PLAN_IMPORT_DISCLOSURE_ACKNOWLEDGED,
      )

    try {
      runBlocking {
        repository.deleteSetting(SettingKeys.PLAN_LEGACY_CATALOG_IMPORTED)
        repository.deleteSetting(SettingKeys.PLAN_IMPORT_DISCLOSURE_ACKNOWLEDGED)
      }
      openPlan()
      val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
      composeRule.waitUntil(timeoutMillis = 5_000) {
        !viewModel.showLegacyPlanDisclosure.value
      }
      composeRule.onNodeWithTag("legacy_plan_disclosure").assertDoesNotExist()

      runBlocking {
        repository.writeSettingsAtomically(
          mapOf(
            SettingKeys.PLAN_LEGACY_CATALOG_IMPORTED to "true",
            SettingKeys.PLAN_IMPORT_DISCLOSURE_ACKNOWLEDGED to "false",
          )
        )
      }
      composeRule.waitUntil(timeoutMillis = 5_000) {
        viewModel.showLegacyPlanDisclosure.value
      }
      composeRule.onNodeWithTag("legacy_plan_disclosure").assertIsDisplayed()
      composeRule
        .onNodeWithText("Calendar and Notion items remain fixed commitments", substring = true)
        .assertIsDisplayed()
      composeRule.onNodeWithTag("ack_plan_import").performClick()
      composeRule.waitUntil(timeoutMillis = 5_000) {
        !viewModel.showLegacyPlanDisclosure.value
      }
      composeRule.onNodeWithTag("legacy_plan_disclosure").assertDoesNotExist()
      assertEquals(
        "true",
        runBlocking {
          repository.readSetting(SettingKeys.PLAN_IMPORT_DISCLOSURE_ACKNOWLEDGED)
        },
      )

      composeRule.activityRule.scenario.recreate()
      openPlan()
      composeRule.onNodeWithTag("legacy_plan_disclosure").assertDoesNotExist()
    } finally {
      runBlocking { restoreSettings(repository, restored) }
    }
  }

  @Test
  fun historyUndoUsesDurableMutationAfterRepositoryAndActivityRecreation() {
    val token = System.nanoTime().toString(16)
    val title = "History recovery $token"
    val creatingRepository = PlanRepository(context)
    val briefingRepository = BriefingRepository(context)
    val restored =
      snapshotSettings(
        briefingRepository,
        SettingKeys.PLAN_VIEW,
        SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID,
      )
    val created =
      runBlocking {
        creatingRepository.ensureCatalog()
        val boardId =
          requireNotNull(briefingRepository.readSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID))
        creatingRepository.saveItemWithUndo(PlanItemInput(boardId = boardId, title = title))
      }
    val mutationId = requireNotNull(created.mutationId)

    try {
      composeRule.activityRule.scenario.recreate()
      openPlan()
      composeRule.openPlanHistory()
      composeRule.onNodeWithText("Recent Plan changes").assertIsDisplayed()
      composeRule.onNodeWithText("Created $title").performScrollTo().assertIsDisplayed()
      composeRule.onAllNodesWithText("Undo")[0].performClick()

      composeRule.waitUntil(timeoutMillis = 5_000) {
        runBlocking { database.planDao().getItem(created.value.id) == null }
      }
      assertEquals(
        PlanMutationStatus.UNDONE,
        runBlocking { database.planDao().getPlanMutation(mutationId)?.status },
      )
    } finally {
      runBlocking {
        database.planDao().getItem(created.value.id)?.let { database.planDao().deleteItem(it) }
        restoreSettings(briefingRepository, restored)
      }
    }
  }

  @Test
  fun planHealthOpensASevenDayExplanationAndNamesEverySource() {
    openPlan()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.planHealth.value.result != null }

    composeRule.openPlanHealth()
    composeRule.onNodeWithText("Plan Health · next 7 days").assertIsDisplayed()
    composeRule
      .onNodeWithText("Uses active-board tasks", substring = true)
      .performScrollTo()
      .assertIsDisplayed()
    composeRule
      .onNodeWithText("Missing estimates are never treated as zero", substring = true)
      .assertIsDisplayed()
  }

  private fun openPlan() {
    composeRule.onNodeWithTag("tab_Plan").performClick()
    waitUntilDisplayed("screen_plan")
  }

  private fun waitUntilDisplayed(tag: String, timeoutMillis: Long = 15_000) {
    composeRule.waitUntil(timeoutMillis = timeoutMillis) {
      composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNode(hasTestTag(tag)).assertIsDisplayed()
  }

  private fun snapshotSettings(
    repository: BriefingRepository,
    vararg keys: String,
  ): Map<String, String?> = runBlocking { keys.associateWith { repository.readSetting(it) } }

  private suspend fun restoreSettings(
    repository: BriefingRepository,
    snapshot: Map<String, String?>,
  ) {
    snapshot.forEach { (key, value) ->
      if (value == null) repository.deleteSetting(key) else repository.writeSetting(key, value)
    }
  }
}
