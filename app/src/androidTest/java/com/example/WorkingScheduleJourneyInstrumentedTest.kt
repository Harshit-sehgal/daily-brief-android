package com.example

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingDateOverride
import com.example.core.WorkingDayWindow
import com.example.core.WorkingWeekWindow
import com.example.data.database.AppDatabase
import com.example.data.repository.PersistedWorkingCalendar
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import java.util.Calendar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendered proof for the persisted, saveable Settings working-week journey. */
@RunWith(AndroidJUnit4::class)
class WorkingScheduleJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database
    get() = AppDatabase.getDatabase(context)

  @Test
  fun draftSurvivesRecreationAndOnlyAValidExplicitSaveReplacesTheCalendar() {
    val repository = PlanRepository(context)
    val original =
      runBlocking {
        repository.ensureCatalog()
        requireNotNull(repository.observeDefaultWorkingCalendar().first())
      }
    val baseline =
      WorkingCalendarSpec(
        zoneId = "UTC",
        weeklyWindows =
          listOf(
            WorkingWeekWindow(Calendar.MONDAY, 9 * 60, 17 * 60),
            WorkingWeekWindow(Calendar.TUESDAY, 9 * 60, 17 * 60),
            WorkingWeekWindow(Calendar.WEDNESDAY, 9 * 60, 17 * 60),
            WorkingWeekWindow(Calendar.THURSDAY, 9 * 60, 17 * 60),
            WorkingWeekWindow(Calendar.FRIDAY, 9 * 60, 17 * 60),
          ),
        minimumChunkMinutes = 30,
        maximumChunkMinutes = 120,
        bufferMinutes = 0,
      )
    val expected =
      baseline.copy(
        zoneId = "Europe/London",
        weeklyWindows =
          baseline.weeklyWindows.map { window ->
            if (window.dayOfWeek == Calendar.MONDAY) {
              window.copy(startMinute = 8 * 60 + 15, endMinute = 16 * 60 + 45)
            } else {
              window
            }
          },
        overrides =
          listOf(
            WorkingDateOverride(
              localDate = "2036-02-29",
              windows = listOf(WorkingDayWindow(11 * 60 + 20, 14 * 60 + 10)),
            )
          ),
        minimumChunkMinutes = 25,
        maximumChunkMinutes = 95,
        bufferMinutes = 15,
      )

    try {
      runBlocking { repository.saveDefaultWorkingCalendar(baseline) }
      openWorkingSchedule()
      assertEditorMatchesBaseline()

      replaceText("work_schedule_minimum", "25")
      replaceText("work_schedule_maximum", "95")
      replaceText("work_schedule_buffer", "15")
      replaceTime("Monday window 1 start time", "08:15")
      replaceTime("Monday window 1 end time", "16:45")

      composeRule.onNodeWithTag("change_work_zone").performScrollTo().performClick()
      composeRule.onNodeWithTag("work_zone_input").performTextReplacement("Europe/London")
      composeRule.onNodeWithText("Apply").performClick()

      revealPlanningFooter()
      composeRule.onNodeWithTag("add_date_override").assertIsDisplayed().performClick()
      composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodes(hasTestTag("date_override_date_0")).fetchSemanticsNodes().size == 1
      }
      replaceText("date_override_date_0", "2036-02-29")
      revealPlanningFooter()
      composeRule.onNodeWithTag("date_override_open_0").assertIsDisplayed().performClick()
      replaceTime("Exception for 2036-02-29 window 1 start time", "11:20")
      replaceTime("Exception for 2036-02-29 window 1 end time", "14:10")
      revealPlanningFooter()
      composeRule.onNodeWithTag("save_working_schedule").assertIsDisplayed().assertIsEnabled()

      // No field writes through to Room before the explicit Save action.
      assertEquals(baseline, currentCalendar(repository).spec)
      composeRule.activityRule.scenario.recreate()
      waitForEditor()
      assertEditorMatchesDraft()
      assertEquals(baseline, currentCalendar(repository).spec)

      revealPlanningFooter()
      composeRule.onNodeWithTag("save_working_schedule").assertIsDisplayed().performClick()
      composeRule.waitUntil(timeoutMillis = 10_000) {
        currentCalendar(repository).spec == expected
      }
      val persisted = currentCalendar(PlanRepository(context))
      assertEquals(expected, persisted.spec)

      // A recreated Activity and a new repository both reopen the exact persisted contract.
      composeRule.activityRule.scenario.recreate()
      waitForEditor()
      assertEditorMatchesDraft()
      assertEquals(persisted, currentCalendar(PlanRepository(context)))

      replaceText("work_schedule_minimum", "0")
      composeRule
        .onNodeWithTag("save_working_schedule")
        .performScrollTo()
        .assertIsNotEnabled()
      composeRule
        .onNodeWithText("Enter 1 to 1440 minutes.")
        .performScrollTo()
        .assertIsDisplayed()
      assertEquals(persisted, currentCalendar(repository))

      // Invalid saveable state may survive recreation, but it never replaces durable data.
      composeRule.activityRule.scenario.recreate()
      waitForEditor()
      composeRule.onNodeWithTag("work_schedule_minimum").assertTextContains("0")
      composeRule
        .onNodeWithTag("save_working_schedule")
        .performScrollTo()
        .assertIsNotEnabled()
      assertEquals(persisted, currentCalendar(PlanRepository(context)))
    } finally {
      restoreCalendarExactly(original)
    }
  }

  private fun openWorkingSchedule() {
    composeRule.onNodeWithTag("open_settings").performClick()
    composeRule.onNodeWithText("Settings").assertIsDisplayed()
    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_category_notifications"))
    composeRule.onNodeWithTag("settings_category_planning").assertIsDisplayed().performClick()
    val expandedPlanning =
      SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodes(hasTestTag("settings_category_planning") and expandedPlanning)
        .fetchSemanticsNodes()
        .size == 1
    }
    composeRule.onNodeWithTag("settings_category_planning").assert(expandedPlanning)
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) {
      viewModel.workingCalendar.value != null || viewModel.workingCalendarProblem.value != null
    }
    check(viewModel.workingCalendarProblem.value == null) {
      "Working calendar failed to load: ${viewModel.workingCalendarProblem.value}"
    }
    checkNotNull(viewModel.workingCalendar.value) { "Working calendar stayed unavailable" }
    waitForEditor()
  }

  private fun waitForEditor() {
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("working_schedule_editor")).fetchSemanticsNodes().size == 1
    }
    composeRule.onNodeWithTag("working_schedule_editor").assertIsDisplayed()
  }

  /** Keeps pointer targets above the app's bottom navigation instead of merely composed behind it. */
  private fun revealPlanningFooter() {
    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_category_brief"))
  }

  private fun assertEditorMatchesBaseline() {
    composeRule.onNodeWithText("UTC").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("work_schedule_minimum").assertTextContains("30")
    composeRule.onNodeWithTag("work_schedule_maximum").assertTextContains("120")
    composeRule.onNodeWithTag("work_schedule_buffer").assertTextContains("0")
    composeRule
      .onNodeWithContentDescription("Monday window 1 start time")
      .performScrollTo()
      .assertTextContains("09:00")
    composeRule
      .onNodeWithContentDescription("Monday window 1 end time")
      .assertTextContains("17:00")
  }

  private fun assertEditorMatchesDraft() {
    composeRule.onNodeWithText("Europe/London").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("work_schedule_minimum").assertTextContains("25")
    composeRule.onNodeWithTag("work_schedule_maximum").assertTextContains("95")
    composeRule.onNodeWithTag("work_schedule_buffer").assertTextContains("15")
    composeRule
      .onNodeWithContentDescription("Monday window 1 start time")
      .performScrollTo()
      .assertTextContains("08:15")
    composeRule
      .onNodeWithContentDescription("Monday window 1 end time")
      .assertTextContains("16:45")
    composeRule
      .onNodeWithTag("date_override_date_0")
      .performScrollTo()
      .assertTextContains("2036-02-29")
    composeRule.onNodeWithTag("date_override_open_0").assertIsOn()
    composeRule
      .onNodeWithContentDescription("Exception for 2036-02-29 window 1 start time")
      .assertTextContains("11:20")
    composeRule
      .onNodeWithContentDescription("Exception for 2036-02-29 window 1 end time")
      .assertTextContains("14:10")
  }

  private fun replaceText(testTag: String, value: String) {
    composeRule
      .onNodeWithTag(testTag)
      .performScrollTo()
      .performTextReplacement(value)
  }

  private fun replaceTime(contentDescription: String, value: String) {
    composeRule
      .onNodeWithContentDescription(contentDescription)
      .performScrollTo()
      .performTextReplacement(value)
  }

  private fun currentCalendar(repository: PlanRepository): PersistedWorkingCalendar =
    runBlocking { requireNotNull(repository.observeDefaultWorkingCalendar().first()) }

  private fun restoreCalendarExactly(original: PersistedWorkingCalendar) {
    runBlocking {
      database.withTransaction {
        database.planDao().updateWorkSchedule(original.schedule)
        database.planDao().deleteWorkScheduleWindows(original.schedule.id)
        database.planDao().insertWorkScheduleWindows(original.windows)
      }
    }
  }
}
