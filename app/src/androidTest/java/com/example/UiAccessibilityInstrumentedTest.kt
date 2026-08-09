package com.example

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.ui.components.DateStrip
import com.example.ui.components.WorkspaceRow
import com.example.ui.components.priorityState
import com.example.ui.theme.DailyBriefTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiAccessibilityInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  private val formatter = TimeFormatter(true, Locale.US)

  @Test
  fun selectedTodayWithEventsExposesStateAndMinimumTarget() {
    val day = ScheduleAnalysis.startOfDay(1_725_000_000_000L)

    composeRule.setContent {
      DailyBriefTheme {
        DateStrip(
          days = listOf(day),
          selectedDay = day,
          todayStart = day,
          daysWithEvents = setOf(day),
          formatter = formatter,
          onSelect = {},
        )
      }
    }

    composeRule
      .onNodeWithContentDescription(formatter.fullDay(day))
      .assertIsSelected()
      .assert(
        SemanticsMatcher.expectValue(
          SemanticsProperties.StateDescription,
          "Today, Has events",
        )
      )
      .assertWidthIsAtLeast(48.dp)
      .assertHeightIsAtLeast(48.dp)
  }

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
        WorkspaceRow(
          title = event.title,
          gutterText = formatter.time(event.startTime),
          onClick = {},
          stateDescription = priorityState(event.isDeadline, event.isUrgent),
          modifier = Modifier.testTag("priority_event"),
        )
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
}
