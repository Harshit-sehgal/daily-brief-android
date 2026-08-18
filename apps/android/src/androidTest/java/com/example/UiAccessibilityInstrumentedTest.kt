package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.ScheduleAnalysis
import com.example.ui.viewmodel.BaselineComparisonUiState
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.screens.PortfolioDialog
import com.example.ui.screens.PlanScenarioDialog
import com.example.ui.screens.BaselinesDialog
import com.example.ui.screens.BaselineComparisonDialog
import com.example.data.model.PlanBaseline
import com.example.core.TaskVariance
import com.example.core.PortfolioRow
import com.example.core.PortfolioRollupResult
import com.example.core.PlanScenario
import com.example.core.PlanProposal
import com.example.core.BaselineComparison
import com.example.core.AutoPlanResult
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.ui.components.InlineAddRow
import com.example.ui.components.RowIconButton
import com.example.ui.components.SectionToggle
import com.example.ui.components.ViewSwitcher
import com.example.ui.components.HourLabelSamples
import com.example.ui.components.WorkspaceRow
import com.example.ui.components.gutterWidthFor
import com.example.ui.components.priorityState
import com.example.ui.theme.DailyBriefTheme
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalUiDensity
import com.example.ui.theme.UiDensity
import com.example.ui.theme.tokensFor
import java.util.Locale
import kotlin.math.ceil
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiAccessibilityInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  private val formatter = TimeFormatter(true, Locale.US)

  @Test
  fun priorityEventExposesBothStatesOnItsClickTarget() {
    val day = ScheduleAnalysis.startOfDay(1_725_000_000_000L)
    val event =
      BriefingEvent(
        id = "priority_event",
        title = "Ship release notes",
        startTime = day + 9 * 60 * 60 * 1000L,
        endTime = day + 10 * 60 * 60 * 1000L,
        source = EventSource.MANUAL,
        description = null,
        isDeadline = true,
        isUrgent = true,
      )

    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(
          LocalUiDensity provides UiDensity.Compact,
          LocalDensityTokens provides tokensFor(UiDensity.Compact),
        ) {
          WorkspaceRow(
            title = event.title,
            gutterText = formatter.time(event.startTime),
            onClick = {},
            stateDescription = priorityState(event.isDeadline, event.isUrgent),
            modifier = Modifier.testTag("priority_event"),
          )
        }
      }
    }

    composeRule
      .onNodeWithTag("priority_event")
      .assertHasClickAction()
      .assert(
        SemanticsMatcher.expectValue(
          SemanticsProperties.StateDescription,
          "Deadline, Urgent",
        )
      )
      .assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun compactControlsKeepMinimumTargetsAndExposeDisclosureState() {
    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(
          LocalUiDensity provides UiDensity.Compact,
          LocalDensityTokens provides tokensFor(UiDensity.Compact),
        ) {
          var expanded by remember { mutableStateOf(false) }
          Column {
            RowIconButton(
              icon = Icons.Default.Edit,
              contentDescription = "Edit item",
              onClick = {},
              modifier = Modifier.testTag("compact_icon_button"),
            )
            SectionToggle(
              title = "Details",
              expanded = expanded,
              onToggle = { expanded = !expanded },
            ) {
              Text("Section content")
            }
            InlineAddRow(
              label = "New item",
              onClick = {},
              modifier = Modifier.testTag("compact_inline_add"),
            )
          }
        }
      }
    }

    composeRule
      .onNodeWithTag("compact_icon_button")
      .assertHasClickAction()
      .assertWidthIsAtLeast(48.dp)
      .assertHeightIsAtLeast(48.dp)
    composeRule
      .onNodeWithTag("compact_inline_add")
      .assertHasClickAction()
      .assertHeightIsAtLeast(48.dp)

    val collapsed =
      SemanticsMatcher.expectValue(
        SemanticsProperties.StateDescription,
        "Collapsed",
      )
    composeRule
      .onNode(collapsed)
      .assertHasClickAction()
      .assertHeightIsAtLeast(48.dp)
      .performClick()
    composeRule
      .onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
      .assertHasClickAction()
      .assertHeightIsAtLeast(48.dp)
  }

  /**
   * A clipped time reads as a different time — "11:00 AM" cut to "11:0" is not a
   * near miss, it is wrong. The gutter is sized in dp and the text in sp, so the
   * two only stay in step if the gutter follows the user's font-size setting.
   */
  @Test
  fun theTimeGutterSurvivesTheLargestFontScale() {
    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(
          LocalUiDensity provides UiDensity.Compact,
          LocalDensityTokens provides tokensFor(UiDensity.Compact),
          // The largest scale Android's accessibility settings offer.
          LocalDensity provides
            Density(LocalDensity.current.density, fontScale = 2f),
        ) {
          WorkspaceRow(
            title = "Client check-in",
            gutterText = "11:00 AM",
            gutterSubtext = "45m",
            onClick = {},
            modifier = Modifier.testTag("scaled_row"),
          )
        }
      }
    }

    listOf("11:00 AM", "45m").forEach(::assertNotClipped)
  }

  /** The day timeline aligns to its own hour column, sized the same way. */
  @Test
  fun theTimelineHourColumnSurvivesTheLargestFontScale() {
    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(
          LocalUiDensity provides UiDensity.Compact,
          LocalDensityTokens provides tokensFor(UiDensity.Compact),
          LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
        ) {
          val tokens = LocalDensityTokens.current
          val width = gutterWidthFor(52.dp, tokens.label, HourLabelSamples, FontWeight.Normal)
          // Every hour of a 12-hour clock, so the widest one has to fit.
          Column(modifier = Modifier.width(width)) {
            listOf("12 AM", "10 AM", "11 AM", "12 PM").forEach {
              Text(text = it, fontSize = tokens.label, maxLines = 1)
            }
          }
        }
      }
    }

    listOf("12 AM", "10 AM", "11 AM", "12 PM").forEach(::assertNotClipped)
  }

  /**
   * Does the string need more room than its column gives it?
   *
   * Deliberately not `hasVisualOverflow`: that also reports true when a Text
   * settles narrower than the constraint it was offered, which is the normal,
   * healthy case here and would make this assertion meaningless.
   */
  private fun assertNotClipped(text: String) {
    val layout = mutableListOf<TextLayoutResult>()
    composeRule
      .onNodeWithText(text)
      .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
    val result = layout.first()
    val needed = ceil(result.multiParagraph.maxIntrinsicWidth).toInt()
    val available = result.layoutInput.constraints.maxWidth
    assertTrue(
      "\"$text\" needs ${needed}px but its column offers ${available}px at the largest font scale",
      needed <= available,
    )
  }

  @Test
  fun compactViewSwitcherUsesSelectableTabSemanticsAndMinimumTargets() {
    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(
          LocalUiDensity provides UiDensity.Compact,
          LocalDensityTokens provides tokensFor(UiDensity.Compact),
        ) {
          var selected by remember { mutableIntStateOf(0) }
          ViewSwitcher(
            options = listOf("Time", "Source"),
            selectedIndex = selected,
            onSelect = { selected = it },
          )
        }
      }
    }

    composeRule
      .onNodeWithText("Time")
      .assertIsSelected()
      .assertWidthIsAtLeast(48.dp)
      .assertHeightIsAtLeast(48.dp)
    composeRule
      .onNodeWithText("Source")
      .assertIsNotSelected()
      .assertWidthIsAtLeast(48.dp)
      .assertHeightIsAtLeast(48.dp)
      .performClick()
    composeRule.onNodeWithText("Source").assertIsSelected()
    composeRule.onNodeWithText("Time").assertIsNotSelected()
  }
  /**
   * The analysis dialogs at the largest font scale Android offers.
   *
   * A dialog is the easiest place to lose text: its width is fixed by the window, its body has a
   * height ceiling, and every string in it is sp while the ceiling is dp. These are the sentences
   * that carry the meaning — a truncated "so the ordering does not change this week" or a clipped
   * drift line would state the opposite of what the engine computed.
   */
  @Test
  fun theAnalysisDialogsSurviveTheLargestFontScale() {
    val captured = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
    val baseline =
      PlanBaseline(
        id = "b1",
        boardId = "board",
        name = "Start of the week",
        nameKey = "start of the week",
        capturedAt = captured,
        itemsJson = "{}",
        blocksJson = "{}",
      )
    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(
          LocalUiDensity provides UiDensity.Compact,
          LocalDensityTokens provides tokensFor(UiDensity.Compact),
          LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
        ) {
          BaselinesDialog(
            visible = true,
            baselines = listOf(baseline),
            // Deliberately not the saved baseline's name: the field and the row must stay
            // distinguishable, or the assertion below would match either of them.
            name = "Week two",
            formatter = formatter,
            onNameChange = {},
            onCapture = {},
            onCompare = {},
            onDelete = {},
            onDismiss = {},
          )
        }
      }
    }
    // The two row actions stay separately reachable rather than colliding at 2x.
    composeRule.onNodeWithTag("baseline_compare_b1").assertHeightIsAtLeast(MinimumTouchTarget)
    composeRule.onNodeWithTag("baseline_delete_b1").assertHeightIsAtLeast(MinimumTouchTarget)
    composeRule.onNodeWithTag("baseline_capture").assertHeightIsAtLeast(MinimumTouchTarget)
    assertNotClipped("Take a baseline")
    assertNotClipped("Start of the week")
  }

  @Test
  fun theBaselineComparisonSurvivesTheLargestFontScale() {
    val captured = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
    val comparison =
      BaselineComparison(
        rows =
          listOf(
            TaskVariance(
              itemId = "a",
              title = "Draft the launch brief",
              baselineStartMs = captured,
              currentStartMs = captured + 2 * 86_400_000L,
              baselineMinutes = 60,
              currentMinutes = 60,
            )
          ),
        summary = "1 later.",
      )
    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(
          LocalUiDensity provides UiDensity.Compact,
          LocalDensityTokens provides tokensFor(UiDensity.Compact),
          LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
        ) {
          BaselineComparisonDialog(
            state =
              BaselineComparisonUiState(
                baselineId = "b1",
                name = "Start of the week",
                capturedAt = captured,
                comparison = comparison,
              ),
            items = emptyList(),
            formatter = formatter,
            onRestore = {},
            onDismiss = {},
          )
        }
      }
    }
    // Restore is the consequential control here; it must stay pressable, not shrink to fit.
    composeRule.onNodeWithTag("baseline_restore").assertHeightIsAtLeast(MinimumTouchTarget)
    assertNotClipped("Draft the launch brief")
    assertNotClipped("2 days later than the baseline")
  }

  @Test
  fun theScenarioDialogSurvivesTheLargestFontScale() {
    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(
          LocalUiDensity provides UiDensity.Compact,
          LocalDensityTokens provides tokensFor(UiDensity.Compact),
          LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
        ) {
          PlanScenarioDialog(
            scenarios = listOf(scenario("due", "Due date first"), scenario("short", "Quick wins first")),
            formatter = formatter,
            onChoose = {},
            onDismiss = {},
          )
        }
      }
    }
    composeRule.onNodeWithTag("scenario_choose_due").assertHeightIsAtLeast(MinimumTouchTarget)
    assertNotClipped("Due date first")
    assertNotClipped("Quick wins first")
  }

  @Test
  fun thePortfolioRollupSurvivesTheLargestFontScale() {
    composeRule.setContent {
      DailyBriefTheme {
        CompositionLocalProvider(
          LocalUiDensity provides UiDensity.Compact,
          LocalDensityTokens provides tokensFor(UiDensity.Compact),
          LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
        ) {
          PortfolioDialog(
            result =
              PortfolioRollupResult(
                rows =
                  listOf(
                    PortfolioRow(
                      boardId = "b1",
                      boardName = "Atlas launch",
                      openTaskCount = 4,
                      doneTaskCount = 2,
                      statedEffortMinutes = 300,
                      scheduledMinutes = 120,
                      overdueTaskCount = 1,
                      unestimatedTaskCount = 1,
                    )
                  ),
                note = "1 open task states no effort, so every effort total here is a floor rather than a total.",
              ),
            timeline = null,
            onDismiss = {},
          )
        }
      }
    }
    composeRule.onNodeWithTag("portfolio_close").assertHeightIsAtLeast(MinimumTouchTarget)
    assertNotClipped("Atlas launch")
  }

  private fun scenario(key: String, name: String) =
    PlanScenario(
      key = key,
      name = name,
      rationale = "Whatever is due soonest gets the first free slot.",
      result =
        AutoPlanResult(
          proposals =
            listOf(
              PlanProposal(
                itemId = "a",
                startAt = 0L,
                endAt = 3_600_000L,
                reason = "First free hour of working time.",
              )
            ),
          unplaced = emptyList(),
          explanation = "1 block proposed.",
        ),
    )
}
