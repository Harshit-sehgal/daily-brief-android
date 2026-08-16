package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingWeekWindow
import com.example.data.model.WorkSchedule
import com.example.data.repository.PersistedWorkingCalendar
import com.example.ui.screens.WorkingScheduleManager
import com.example.ui.theme.DailyBriefTheme
import com.example.ui.viewmodel.ActiveWorkingCalendarsState
import java.util.Calendar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkingScheduleManagerTextScaleInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun managerActionsRemainReachableAtDoubleTextScale() {
    val default = calendar("default", "Default week", isDefault = true, updatedAt = 1)
    val alternate = calendar("alternate", "Client hours", isDefault = false, updatedAt = 2)

    composeRule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        DailyBriefTheme {
          Box(modifier = Modifier.width(360.dp).height(720.dp)) {
            Column(
              modifier =
                Modifier.width(360.dp)
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 16.dp)
            ) {
              WorkingScheduleManager(
                calendarsState =
                  ActiveWorkingCalendarsState(
                    calendars = listOf(default, alternate),
                    loaded = true,
                  ),
                defaultCalendar = default,
                isSaving = false,
                problem = null,
                onCreate = { _, _, done -> done(null) },
                onUpdate = { _, _, _, done -> done(null) },
                onMakeDefault = { _, done -> done(true) },
                onArchive = { _, _, done -> done(true) },
              )
            }
          }
        }
      }
    }

    composeRule
      .onNodeWithTag("work_schedule_choice_${default.schedule.id}")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
    composeRule
      .onNodeWithTag("work_schedule_choice_${alternate.schedule.id}")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
      .performClick()
    composeRule
      .onNodeWithTag("add_work_schedule")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
    composeRule
      .onNodeWithTag("save_working_schedule")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
    composeRule
      .onNodeWithTag("make_work_schedule_default")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
    composeRule
      .onNodeWithTag("archive_work_schedule")
      .performScrollTo()
      .assertIsDisplayed()
      .assertHeightIsAtLeast(48.dp)
  }

  private fun calendar(
    id: String,
    name: String,
    isDefault: Boolean,
    updatedAt: Long,
  ): PersistedWorkingCalendar {
    val spec =
      WorkingCalendarSpec(
        zoneId = "UTC",
        weeklyWindows =
          listOf(WorkingWeekWindow(Calendar.MONDAY, startMinute = 540, endMinute = 1020)),
      )
    return PersistedWorkingCalendar(
      schedule =
        WorkSchedule(
          id = id,
          name = name,
          nameKey = name.lowercase(),
          timeZoneId = spec.zoneId,
          isDefault = isDefault,
          rank = updatedAt,
          createdAt = 1,
          updatedAt = updatedAt,
        ),
      windows = emptyList(),
      spec = spec,
    )
  }
}
