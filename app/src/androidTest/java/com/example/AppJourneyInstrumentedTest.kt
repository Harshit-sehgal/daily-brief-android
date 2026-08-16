package com.example

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.example.core.ScheduleAnalysis
import com.example.ui.viewmodel.BriefingViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun primaryNavigationIsMinimalAndSettingsRemainsEasyToReach() {
    composeRule.onNodeWithTag("tab_Home").performClick()

    listOf("Home", "Calendar", "Plan").forEach { destination ->
      composeRule.onNodeWithTag("tab_$destination").assertIsDisplayed()
    }
    composeRule.onNodeWithTag("tab_Settings").assertDoesNotExist()

    composeRule.onNodeWithTag("open_settings").performClick()
    composeRule.onNodeWithText("Settings").assertIsDisplayed()
    composeRule.activity.onBackPressedDispatcher.onBackPressed()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("home_focus").assertIsDisplayed()
  }

  @Test
  fun calendarMakesEveryDateViewAndGlobalActionVisible() {
    composeRule.onNodeWithTag("tab_Calendar").performClick()

    composeRule.onNodeWithTag("calendar_view_switcher").assertIsDisplayed()
    composeRule.onNodeWithText("Agenda").assertIsDisplayed()
    composeRule.onNodeWithText("Timeline").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("timeline_grid").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Search and commands").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
  }

  @Test
  fun paletteSettingsReturnsToTheRootThatOpenedIt() {
    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithContentDescription("Search and commands").performClick()
    composeRule.onNodeWithTag("palette_query").performTextInput("Go to Settings")
    composeRule.onNodeWithTag("palette_query").performImeAction()
    composeRule.onNodeWithText("Settings").assertIsDisplayed()

    composeRule.activity.onBackPressedDispatcher.onBackPressed()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("screen_calendar").assertIsDisplayed()
  }

  @Test
  fun planKeepsBoardAndScheduleMapTogether() {
    composeRule.onNodeWithTag("tab_Plan").performClick()

    composeRule.onNodeWithTag("plan_view_switcher").assertIsDisplayed()
    composeRule.onNodeWithText("Gantt").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("screen_gantt").assertIsDisplayed()
    listOf(7, 30, 90).forEach { days ->
      composeRule.onNodeWithTag("gantt_range_$days").assertIsDisplayed()
    }
  }

  @Test
  fun creatingFlexibleWorkFromGanttReturnsToItsVisibleOutlineRow() {
    val title = "Gantt capture ${System.nanoTime()}"

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Gantt").performClick()
    composeRule.onNodeWithTag("screen_gantt").assertIsDisplayed()
    composeRule.onNodeWithTag("add_task").performClick()
    composeRule.onNodeWithTag("task_title").performTextInput(title)
    composeRule.onNodeWithTag("task_save").performScrollTo().assertIsEnabled().performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("screen_plan_outline")).fetchSemanticsNodes().size == 1
    }
    composeRule.onNode(hasTestTag("plan_item") and hasText(title)).assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("task_delete").performScrollTo().performClick()
    composeRule.onNodeWithTag("task_delete_confirm").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("plan_item") and hasText(title))
        .fetchSemanticsNodes()
        .isEmpty()
    }
  }

  @Test
  fun taskCapturePersistsInPlanInboxWithoutCreatingACalendarEvent() {
    val title = "Plan task ${System.nanoTime()}"

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("screen_plan_outline")).fetchSemanticsNodes().size == 1
    }

    composeRule.onNodeWithTag("add_task").performClick()
    composeRule.onNodeWithTag("task_editor").assertIsDisplayed()
    composeRule.onNodeWithTag("task_title").performTextInput(title)
    composeRule.onNodeWithTag("task_effort").performTextInput("45")
    composeRule.onNodeWithTag("task_save").performScrollTo().assertIsEnabled().performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("task_editor")).fetchSemanticsNodes().isEmpty()
    }
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("plan_item") and hasText(title))
        .fetchSemanticsNodes()
        .size == 1
    }

    composeRule.onNodeWithText("Board").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("plan_board_item") and hasText(title))
        .fetchSemanticsNodes()
        .size == 1
    }

    // The Board's "Schedule" button duplicated the Gantt tab directly above it, so the tab is the
    // only way now — which is also the way a person would look for it.
    composeRule.onNodeWithText("Gantt").performClick()
    composeRule.onNodeWithTag("screen_gantt").assertIsDisplayed()
    composeRule
      .onNodeWithContentDescription("Add schedule block for $title")
      .performScrollTo()
      .performClick()
    composeRule.onNodeWithTag("gantt_block_duration").assertTextContains("45")
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(
          hasTestTag("gantt_block_work_schedule") and
            hasText("Default working week", substring = true)
        )
        .fetchSemanticsNodes()
        .size == 1 &&
        composeRule
          .onAllNodes(
            hasText(
              "Ready to schedule within working time and fixed commitments.",
              substring = true,
            )
          )
          .fetchSemanticsNodes()
          .size == 1
    }
    composeRule
      .onNodeWithTag("gantt_block_save")
      .assertIsEnabled()
      .performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(
          hasTestTag("plan_gantt_item") and
            hasContentDescription(title, substring = true)
        )
        .fetchSemanticsNodes()
        .size == 1
    }

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()
    composeRule.onNode(hasTestTag("agenda_item") and hasText(title)).assertDoesNotExist()

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    composeRule.onNode(hasTestTag("plan_item") and hasText(title)).performClick()
    composeRule.onNodeWithTag("task_delete").performScrollTo().performClick()
    composeRule.onNodeWithTag("task_delete_confirm").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("plan_item") and hasText(title))
        .fetchSemanticsNodes()
        .isEmpty()
    }
  }

  @Test
  fun taskSaveSurvivesActivityRecreationWithoutStrandingTheEditor() {
    val title = "Recreated task save ${System.nanoTime()}"

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    composeRule.onNodeWithTag("add_task").performClick()
    composeRule.onNodeWithTag("task_title").performTextInput(title)
    composeRule.onNodeWithTag("task_save").performScrollTo().assertIsEnabled().performClick()
    composeRule.activityRule.scenario.recreate()

    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("task_editor")).fetchSemanticsNodes().isEmpty()
    }
    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule
        .onAllNodes(hasTestTag("plan_item") and hasText(title))
        .fetchSemanticsNodes()
        .size == 1
    }

    composeRule.onNode(hasTestTag("plan_item") and hasText(title)).performClick()
    composeRule.onNodeWithTag("task_delete").performScrollTo().performClick()
    composeRule.onNodeWithTag("task_delete_confirm").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("plan_item") and hasText(title))
        .fetchSemanticsNodes()
        .isEmpty()
    }
  }

  @Test
  fun taskAndEventCaptureSwitchWithoutLosingTheTitle() {
    val title = "Capture handoff ${System.nanoTime()}"

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    composeRule.onNodeWithTag("add_task").performClick()
    composeRule.onNodeWithTag("task_title").performTextInput(title)

    val captureTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    composeRule.onNode(hasText("Event") and captureTab).performScrollTo().performClick()
    waitUntilDisplayed("event_editor")
    composeRule.onNodeWithTag("editor_title").assertTextContains(title)

    composeRule.onNode(hasText("Task") and captureTab).performClick()
    waitUntilDisplayed("task_editor")
    composeRule.onNodeWithTag("task_title").assertTextContains(title)
  }

  @Test
  fun cancellingEventAfterTaskOnlyEditsRequiresDiscardConfirmation() {
    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    composeRule.onNodeWithTag("add_task").performClick()
    composeRule.onNodeWithTag("task_editor").assertIsDisplayed()

    // Effort has no Event equivalent. The capture session must therefore remain
    // dirty after switching types even when the shared title and notes are blank.
    composeRule.onNodeWithTag("task_effort").performTextInput("45")
    val captureTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    composeRule.onNode(hasText("Event") and captureTab).performScrollTo().performClick()
    waitUntilDisplayed("event_editor")

    composeRule.onNodeWithText("Cancel").performScrollTo().performClick()
    composeRule.onNodeWithText("Discard unsaved changes?").assertIsDisplayed()
    composeRule.onNodeWithText("Discard").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("event_editor")).fetchSemanticsNodes().isEmpty() &&
        composeRule.onAllNodes(hasTestTag("task_editor")).fetchSemanticsNodes().isEmpty()
    }
    composeRule.onNodeWithTag("screen_plan_outline").assertIsDisplayed()
  }

  @Test
  fun createEditAndDeleteJourneyPersistsThroughRoom() {
    val title = "Emulator journey ${System.nanoTime()}"

    // The app opens on Home; event rows live in Calendar's Agenda view.
    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()

    composeRule.onNodeWithTag("add_event").performClick()
    composeRule.onNodeWithTag("editor_title").performTextInput(title)
    composeRule.onNodeWithTag("editor_save").performScrollTo().performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("event_editor")).fetchSemanticsNodes().isEmpty()
    }
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("agenda_item") and hasText(title))
        .fetchSemanticsNodes()
        .size == 1
    }

    // Timeline keeps the bar visually proportional to time while exposing a
    // full-sized, labelled edit target for touch, TalkBack and keyboard users.
    composeRule.onNodeWithText("Timeline").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("timeline_block") and hasText(title))
        .fetchSemanticsNodes()
        .size == 1
    }
    composeRule
      .onNode(hasTestTag("timeline_block") and hasText(title))
      .assertHasClickAction()
      .assertHeightIsAtLeast(48.dp)

    // The same Room row must become a real bar in Plan's schedule map, not a
    // disconnected mock or empty-state-only implementation.
    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Gantt").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("gantt_item"))
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeRule
      .onNodeWithContentDescription("$title, fixed calendar commitment", substring = true)
      .assertHasClickAction()

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()
    composeRule.onNode(hasTestTag("agenda_item") and hasText(title)).performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("editor_delete")).fetchSemanticsNodes().size == 1
    }
    composeRule.onNodeWithTag("editor_delete").performClick()
    composeRule.onNodeWithTag("editor_delete_confirm").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("agenda_item") and hasText(title))
        .fetchSemanticsNodes()
        .isEmpty()
    }
  }

  @Test
  fun homeCreateUsesTodayAfterBrowsingAnotherCalendarDate() {
    val title = "Home create ${System.nanoTime()}"
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    val today = ScheduleAnalysis.startOfDay(System.currentTimeMillis())

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()
    composeRule.runOnIdle { viewModel.selectToday() }
    composeRule.onNodeWithContentDescription("Next day").performClick()
    composeRule.runOnIdle {
      org.junit.Assert.assertEquals(ScheduleAnalysis.startOfDayOffset(today, 1), viewModel.selectedDay.value)
    }

    composeRule.onNodeWithTag("tab_Home").performClick()
    composeRule.onNodeWithTag("add_event").performClick()
    composeRule.onNodeWithTag("editor_title").performTextInput(title)
    composeRule.onNodeWithTag("editor_save").performScrollTo().performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("event_editor")).fetchSemanticsNodes().isEmpty()
    }
    composeRule.runOnIdle { org.junit.Assert.assertEquals(today, viewModel.selectedDay.value) }

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("agenda_item") and hasText(title))
        .fetchSemanticsNodes()
        .size == 1
    }
    composeRule.onNode(hasTestTag("agenda_item") and hasText(title)).performClick()
    composeRule.onNodeWithTag("editor_delete").performScrollTo().performClick()
    composeRule.onNodeWithTag("editor_delete_confirm").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("agenda_item") and hasText(title))
        .fetchSemanticsNodes()
        .isEmpty()
    }
  }

  @Test
  fun deferringAnEventKeepsTheCurrentDayOpen() {
    val title = "Defer journey ${System.nanoTime()}"

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle { viewModel.selectToday() }
    composeRule.onNodeWithTag("add_event").performClick()
    composeRule.onNodeWithTag("editor_title").performTextInput(title)
    composeRule.onNodeWithTag("editor_deadline").performScrollTo().performClick()
    composeRule.onNodeWithTag("editor_save").performScrollTo().performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("event_editor")).fetchSemanticsNodes().isEmpty()
    }

    // Priority grouping places this deadline at the top even on a populated device.
    composeRule.onNodeWithTag("agenda_grouping").performClick()
    composeRule.onNodeWithText("Priority").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("agenda_item") and hasText(title))
        .fetchSemanticsNodes()
        .size == 1
    }
    val selectedBeforeMove = viewModel.selectedDay.value
    org.junit.Assert.assertEquals(
      ScheduleAnalysis.startOfDay(System.currentTimeMillis()),
      selectedBeforeMove,
    )
    composeRule
      .onNode(hasTestTag("agenda_item") and hasText(title))
      .performTouchInput { swipeRight() }

    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("agenda_item") and hasText(title))
        .fetchSemanticsNodes()
        .isEmpty()
    }
    composeRule.runOnIdle {
      org.junit.Assert.assertEquals(selectedBeforeMove, viewModel.selectedDay.value)
    }
    composeRule.onNodeWithText("Tomorrow").assertDoesNotExist()

    // Prove the event moved, then remove the test data from the device.
    composeRule.onNodeWithContentDescription("Next day").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("agenda_item") and hasText(title))
        .fetchSemanticsNodes()
        .size == 1
    }
    composeRule.onNode(hasTestTag("agenda_item") and hasText(title)).performClick()
    composeRule.onNodeWithTag("editor_delete").performScrollTo().performClick()
    composeRule.onNodeWithTag("editor_delete_confirm").performClick()
    composeRule.onNodeWithTag("agenda_grouping").performClick()
    composeRule.onNodeWithText("Time").performClick()
    composeRule.onNodeWithContentDescription("Previous day").performClick()
  }

  private fun waitUntilDisplayed(tag: String, timeoutMillis: Long = 15_000) {
    composeRule.waitUntil(timeoutMillis = timeoutMillis) {
      runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
    }
  }
}
