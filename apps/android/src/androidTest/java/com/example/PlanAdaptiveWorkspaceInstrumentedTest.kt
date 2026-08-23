package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.example.ui.theme.UiDensity
import com.example.ui.theme.gutter
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import com.example.ui.theme.LocalWindowWidthDp
import androidx.compose.ui.test.assertWidthIsAtLeast
import com.example.ui.screens.OutlineGrouping
import com.example.ui.screens.OutlineSort
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.TimeFormatter
import com.example.data.model.PlanMutation
import com.example.ui.screens.PlanAdaptiveWorkspace
import com.example.ui.screens.PlanToolsDialog
import com.example.ui.screens.PlanOverflowMenu
import com.example.ui.screens.PlanViewOptionsDialog
import com.example.ui.screens.PlanLedgerRail
import com.example.ui.theme.DailyBriefTheme
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.WindowWidth
import com.example.ui.viewmodel.PlanHealthUiState
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanAdaptiveWorkspaceInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun adaptiveFrameMovesControlsIntoTheLedgerAtExpanded() {
    val width = mutableStateOf(WindowWidth.Medium)
    composeRule.setContent {
      DailyBriefTheme {
        PlanAdaptiveWorkspace(
          windowWidth = width.value,
          compactControls = {
            Box(Modifier.fillMaxWidth().height(48.dp).testTag("compact_content"))
          },
          ledger = { Box(Modifier.fillMaxSize().testTag("ledger_content")) },
          workspace = { Box(Modifier.fillMaxSize().testTag("workspace_content")) },
        )
      }
    }

    composeRule.onNodeWithTag("plan_stacked_workspace").assertIsDisplayed()
    composeRule.onNodeWithTag("compact_content").assertIsDisplayed()
    composeRule.onNodeWithTag("plan_ledger_container").assertDoesNotExist()

    composeRule.runOnIdle { width.value = WindowWidth.Expanded }
    composeRule.onNodeWithTag("plan_wide_workspace").assertIsDisplayed()
    composeRule.onNodeWithTag("compact_content").assertDoesNotExist()
    composeRule.onNodeWithTag("ledger_content").assertIsDisplayed()
    composeRule.onNodeWithTag("plan_ledger_container").assertWidthIsEqualTo(300.dp)

    composeRule.runOnIdle { width.value = WindowWidth.Large }
    composeRule.onNodeWithTag("plan_ledger_container").assertWidthIsEqualTo(320.dp)

    composeRule.runOnIdle { width.value = WindowWidth.ExtraLarge }
    composeRule.onNodeWithTag("plan_ledger_container").assertWidthIsEqualTo(336.dp)
  }

  @Test
  fun ledgerActionsRemainReachableAtDoubleTextScale() {
    composeRule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        DailyBriefTheme {
          Box(modifier = Modifier.width(336.dp).height(620.dp)) {
            PlanLedgerRail(
              views = emptyList(),
              activeViewId = null,
              selectedView = null,
              history = emptyList(),
              health = PlanHealthUiState(unavailableReason = "Schedule evidence is still loading"),
              formatter = TimeFormatter(true, Locale.US),
              onApply = {},
              onSave = {},
              onShowHealth = {},
              onShowHistory = {},
              onDelete = {},
              onUpdate = {},
              onRename = {},
              onDuplicate = {},
              onPin = {},
              onReset = {},
              onWeeklyReview = {},
              onExport = {},
              onProposePlan = {},
              onCompareScenarios = {},
              onBaselines = {},
              onPortfolio = {},
              onUndo = {},
              contentBottomPadding = 0.dp,
            )
          }
        }
      }
    }

    composeRule.onNodeWithTag("plan_ledger").assertIsDisplayed()
    composeRule
      .onNodeWithTag("save_plan_view")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
    composeRule
      .onNodeWithTag("plan_health_summary")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
    composeRule
      .onNodeWithTag("plan_history")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun directUndoDisappearsWhenItsJournalWindowExpires() {
    val now = System.currentTimeMillis()
    val mutation =
      PlanMutation(
        id = "expiring",
        boardId = "board",
        mutationType = "item_move",
        targetType = "item",
        summary = "Moved task",
        createdAt = now,
        updatedAt = now,
        expiresAt = now + 1_500L,
      )

    composeRule.setContent {
      DailyBriefTheme {
        Box(modifier = Modifier.width(336.dp).height(620.dp)) {
          PlanLedgerRail(
            views = emptyList(),
            activeViewId = null,
            selectedView = null,
            history = listOf(mutation),
            health = PlanHealthUiState(),
            formatter = TimeFormatter(true, Locale.US),
            onApply = {},
            onSave = {},
            onShowHealth = {},
            onShowHistory = {},
            onDelete = {},
            onUpdate = {},
            onRename = {},
            onDuplicate = {},
            onPin = {},
            onReset = {},
            onWeeklyReview = {},
            onExport = {},
            onProposePlan = {},
            onCompareScenarios = {},
            onBaselines = {},
            onPortfolio = {},
            onUndo = {},
            contentBottomPadding = 0.dp,
          )
        }
      }
    }

    composeRule
      .onNodeWithTag("plan_history_undo_expiring")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
    composeRule.waitUntil(timeoutMillis = 5_000L) {
      composeRule
        .onAllNodes(hasTestTag("plan_history_undo_expiring"))
        .fetchSemanticsNodes()
        .isEmpty()
    }
    composeRule.onNodeWithTag("plan_history_undo_expiring").assertDoesNotExist()
  }
    @Test
  fun theWideLedgerMenuOffersThatSameSet() {
    val commands =
      listOf(
        "plan_view_update",
        "plan_view_rename",
        "plan_view_duplicate",
        "plan_view_pin",
        "plan_view_delete",
        "plan_view_reset",
        "plan_auto_plan",
        "plan_scenarios_open",
        "plan_weekly_review",
        "plan_baselines_open",
        "plan_portfolio_open",
        "plan_export_csv",
        "plan_export_ics",
        "plan_export_pdf",
        "plan_export_png",
        "plan_print",
      )

    composeRule.setContent {
      DailyBriefTheme {
        Box(modifier = Modifier.width(336.dp).height(620.dp)) {
          PlanLedgerRail(
            views = emptyList(),
            activeViewId = null,
            selectedView = null,
            history = emptyList(),
            health = PlanHealthUiState(unavailableReason = "Schedule evidence is still loading"),
            formatter = TimeFormatter(true, Locale.US),
            onApply = {},
            onSave = {},
            onShowHealth = {},
            onShowHistory = {},
            onDelete = {},
            onUpdate = {},
            onRename = {},
            onDuplicate = {},
            onPin = {},
            onReset = {},
            onWeeklyReview = {},
            onExport = {},
            onProposePlan = {},
            onCompareScenarios = {},
            onBaselines = {},
            onPortfolio = {},
            onUndo = {},
            contentBottomPadding = 0.dp,
          )
        }
      }
    }
    composeRule.onNodeWithTag("plan_view_actions").performScrollTo().performClick()
    commands.forEach { composeRule.onNodeWithTag(it).assertExists() }
  }
      /**
   * The presentation controls on the smallest phone this app supports.
   *
   * They used to sit in a `Row` above every task, where at 360 dp the last chip rendered one letter
   * per line. They live in View options now, and the same rule applies there: a chip wider than it
   * is tall, or it is a column of letters.
   */
  @Test
  fun theViewOptionsStayUsableOnASmallPhone() {
    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(LocalWindowWidthDp provides 360.dp) {
          Box(modifier = Modifier.width(360.dp).height(640.dp)) {
            PlanViewOptionsDialog(
              visible = true,
              sort = OutlineSort.MANUAL,
              grouping = OutlineGrouping.SECTION,
              hideCompleted = false,
              query = "",
              density = UiDensity.Compact,
              collapsedCount = 2,
              savedViews = emptyList(),
              activeViewId = null,
              onSort = {},
              onGrouping = {},
              onHideCompleted = {},
              onQueryChange = {},
              onDensity = {},
              onExpandAll = {},
              onApplyView = {},
              onSaveView = {},
              onManageViews = {},
              onDismiss = {},
            )
          }
        }
      }
    }

    val tags =
      OutlineSort.entries.map { "outline_sort_${it.key}" } +
        OutlineGrouping.entries.map { "outline_grouping_${it.key}" } +
        listOf("outline_hide_completed", "outline_expand_all")
    tags.forEach { tag ->
      composeRule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(40.dp)
      val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
      assertTrue(
        "$tag is ${bounds.width} x ${bounds.height} — that is a column of letters, not a chip",
        bounds.width > bounds.height,
      )
    }
  }

  /**
   * One menu holds everything the Plan can do that is not the work.
   *
   * Every entry opens a focused place rather than adding a control to the screen, which is what
   * keeps the workspace to the tasks themselves.
   */
  @Test
  fun theOverflowMenuOffersEveryPlanCommand() {
    composeRule.setContent {
      DailyBriefTheme {
        Box(modifier = Modifier.width(360.dp).height(640.dp)) {
          PlanOverflowMenu(
            onViewOptions = {},
            onHealth = {},
            onTools = {},
            onHistory = {},
            onExport = {},
            onExportPdf = {},
            onExportPng = {},
          )
        }
      }
    }

    composeRule.onNodeWithTag("plan_overflow").assertHeightIsAtLeast(48.dp).performClick()
    listOf(
        "plan_menu_view_options",
        "plan_menu_health",
        "plan_menu_tools",
        "plan_menu_history",
        "plan_export_csv",
        "plan_export_ics",
        "plan_export_pdf",
        "plan_export_png",
        "plan_print",
      )
      .forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
  }

  /** Every tool is reachable from the one dialog, with a line saying what it does. */
  @Test
  fun thePlanToolsDialogNamesWhatEachToolDoes() {
    composeRule.setContent {
      DailyBriefTheme {
        Box(modifier = Modifier.width(360.dp).height(640.dp)) {
          PlanToolsDialog(
            visible = true,
            onProposePlan = {},
            onCompareScenarios = {},
            onWeeklyReview = {},
            onBaselines = {},
            onPortfolio = {},
            onFocusTimer = {},
            onDismiss = {},
          )
        }
      }
    }
    listOf(
        "plan_auto_plan",
        "plan_scenarios_open",
        "plan_weekly_review",
        "plan_baselines_open",
        "plan_portfolio_open",
      )
      .forEach { composeRule.onNodeWithTag(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
  }
}
