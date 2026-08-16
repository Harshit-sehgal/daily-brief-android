package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.PortfolioRollupResult
import com.example.core.TimeFormatter
import com.example.ui.screens.BaselinesDialog
import com.example.ui.screens.PlanScenarioDialog
import com.example.ui.screens.PortfolioDialog
import com.example.ui.theme.DailyBriefTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the analysis surfaces say when there is nothing to say.
 *
 * An empty state is where an app most easily lies: a rollup with no plans that prints "0 open" looks
 * like an answer, and a dialog that renders a blank box looks broken. Each of these has to say which
 * it is.
 */
@RunWith(AndroidJUnit4::class)
class PlanAnalysisEmptyStatesInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  private val formatter = TimeFormatter(true, Locale.US)

  @Test
  fun theBaselineListSaysThereAreNoneRatherThanShowingAnEmptyBox() {
    composeRule.setContent {
      DailyBriefTheme {
        BaselinesDialog(
          visible = true,
          baselines = emptyList(),
          name = "",
          formatter = formatter,
          onNameChange = {},
          onCapture = {},
          onCompare = {},
          onDelete = {},
          onDismiss = {},
        )
      }
    }

    composeRule.onNodeWithTag("baselines_dialog").assertIsDisplayed()
    composeRule.onNodeWithText("No baselines yet.").assertIsDisplayed()
    // Capture stays refused until the baseline has a name, and says so by being visibly disabled.
    composeRule.onNodeWithTag("baseline_capture").assertIsNotEnabled()
  }

  @Test
  fun aPortfolioWithNoPlansSaysSoInsteadOfTotallingZero() {
    composeRule.setContent {
      DailyBriefTheme {
        PortfolioDialog(
          result = PortfolioRollupResult(rows = emptyList(), note = "No plans yet."),
          onDismiss = {},
        )
      }
    }

    composeRule.onNodeWithTag("portfolio_dialog").assertIsDisplayed()
    composeRule.onNodeWithText("No plans yet.").assertIsDisplayed()
    // A total of nothing is not a finding; the headline line stays away entirely.
    composeRule.onNodeWithTag("portfolio_total").assertDoesNotExist()
  }

  @Test
  fun theScenarioDialogStaysClosedWhenThereIsNothingToCompare() {
    composeRule.setContent {
      DailyBriefTheme {
        PlanScenarioDialog(scenarios = emptyList(), formatter = formatter, onChoose = {}, onDismiss = {})
      }
    }

    // Nothing to compare means no dialog at all, rather than an empty one asking to be dismissed.
    composeRule.onNodeWithTag("plan_scenarios_dialog").assertDoesNotExist()
  }
}
