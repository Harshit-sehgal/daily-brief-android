package com.example

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.data.database.AppDatabase
import com.example.data.repository.PersistedWorkingCalendar
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkingScheduleManagerJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database
    get() = AppDatabase.getDatabase(context)

  @Test
  fun createEditDefaultArchiveRerouteAndUndoStayExplicit() {
    val repository = PlanRepository(context)
    val suffix = System.nanoTime().toString()
    val initialName = "Focus schedule $suffix"
    val renamed = "Deep focus $suffix"
    var original: PersistedWorkingCalendar? = null
    var taskId: String? = null
    var createdScheduleId: String? = null

    try {
      runBlocking {
        repository.ensureCatalog()
        val defaultCalendar = requireNotNull(repository.observeDefaultWorkingCalendar().first())
        original = defaultCalendar
        val board = database.planDao().getBoards().first()
        val task = repository.saveItem(PlanItemInput(boardId = board.id, title = "Reroute $suffix"))
        taskId = task.id
        repository.assignItemWorkingScheduleWithUndo(task.id, defaultCalendar.schedule.id)
      }
      val originalCalendar = requireNotNull(original)

      openManager()
      composeRule
        .onNodeWithTag("work_schedule_choice_${originalCalendar.schedule.id}")
        .performScrollTo()
        .performClick()
      composeRule.onNodeWithTag("add_work_schedule").performScrollTo().performClick()
      composeRule
        .onNodeWithTag("new_work_schedule_name")
        .performTextReplacement(originalCalendar.schedule.name)
      composeRule.onNodeWithTag("confirm_add_work_schedule").assertIsNotEnabled()
      composeRule
        .onNodeWithTag("new_work_schedule_name")
        .performTextReplacement(initialName)
      composeRule.onNodeWithTag("confirm_add_work_schedule").assertIsEnabled().performClick()

      composeRule.waitUntil(timeoutMillis = 10_000) {
        val created = runBlocking {
          database.planDao().getWorkSchedules().firstOrNull { it.name == initialName }
        }
        createdScheduleId = created?.id
        created != null
      }
      val newId = requireNotNull(createdScheduleId)
      dismissUndoSnackbar()
      composeRule.waitUntil(timeoutMillis = 10_000) {
        composeRule.onAllNodes(hasTestTag("working_schedule_name")).fetchSemanticsNodes().size == 1
      }
      composeRule.onNodeWithTag("working_schedule_name").assertTextContains(initialName)
      composeRule
        .onNodeWithTag("work_schedule_minimum")
        .assertTextContains(originalCalendar.spec.minimumChunkMinutes.toString())

      composeRule.onNodeWithTag("working_schedule_name").performTextReplacement(renamed)
      composeRule.onNodeWithTag("work_schedule_buffer").performTextReplacement("7")
      revealPlanningFooter()
      composeRule
        .onNodeWithTag("save_working_schedule")
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
      composeRule.waitUntil(timeoutMillis = 10_000) {
        runBlocking {
          database.planDao().getWorkSchedule(newId)?.let {
            it.name == renamed && it.bufferMinutes == 7
          } == true
        }
      }
      dismissUndoSnackbar()

      revealPlanningFooter()
      composeRule.onNodeWithTag("make_work_schedule_default").performScrollTo().performClick()
      composeRule
        .onNodeWithText("Tasks set to inherit the default", substring = true)
        .assertIsDisplayed()
      composeRule
        .onNodeWithTag("confirm_make_work_schedule_default")
        .assertIsEnabled()
        .performClick()
      composeRule.waitUntil(timeoutMillis = 10_000) {
        runBlocking { database.planDao().getDefaultWorkSchedule()?.id == newId }
      }
      dismissUndoSnackbar()

      composeRule
        .onNodeWithTag("work_schedule_choice_${originalCalendar.schedule.id}")
        .performScrollTo()
        .performClick()
      revealPlanningFooter()
      composeRule.onNodeWithTag("archive_work_schedule").performScrollTo().performClick()
      composeRule
        .onNodeWithText("Every task explicitly assigned", substring = true)
        .assertIsDisplayed()
      composeRule.onNodeWithTag("confirm_archive_work_schedule").assertIsNotEnabled()
      composeRule
        .onNodeWithTag("archive_replacement_$newId")
        .assertIsDisplayed()
        .performClick()
      composeRule
        .onNodeWithTag("confirm_archive_work_schedule")
        .assertIsEnabled()
        .performClick()
      composeRule.waitUntil(timeoutMillis = 10_000) {
        runBlocking {
          database.planDao().getWorkSchedule(originalCalendar.schedule.id)?.archivedAt != null &&
            database.planDao().getPlanItemSchedule(requireNotNull(taskId))?.workScheduleId == newId
        }
      }

      composeRule.waitUntil(timeoutMillis = 10_000) {
        composeRule.onAllNodes(hasText("Undo")).fetchSemanticsNodes().size == 1
      }
      composeRule.onNodeWithText("Undo").performClick()
      composeRule.waitUntil(timeoutMillis = 10_000) {
        runBlocking {
          database.planDao().getWorkSchedule(originalCalendar.schedule.id)?.archivedAt == null &&
            database.planDao().getPlanItemSchedule(requireNotNull(taskId))?.workScheduleId ==
              originalCalendar.schedule.id
        }
      }
      composeRule
        .onNodeWithTag("work_schedule_choice_${originalCalendar.schedule.id}")
        .performScrollTo()
        .assertIsDisplayed()
    } finally {
      runBlocking {
        database.withTransaction {
          taskId?.let { id ->
            database.planDao().getPlanItemSchedule(id)?.let {
              database.planDao().deletePlanItemSchedule(it)
            }
            database.planDao().getItem(id)?.let { database.planDao().deleteItem(it) }
          }
          createdScheduleId?.let { id ->
            database.planDao().getWorkSchedule(id)?.let { created ->
              database.planDao().updateWorkSchedule(created.copy(isDefault = false))
            }
          }
          original?.let { snapshot ->
            database.planDao().getWorkSchedule(snapshot.schedule.id)?.let {
              database.planDao().updateWorkSchedule(snapshot.schedule)
              database.planDao().deleteWorkScheduleWindows(snapshot.schedule.id)
              if (snapshot.windows.isNotEmpty()) {
                database.planDao().insertWorkScheduleWindows(snapshot.windows)
              }
            }
          }
          createdScheduleId?.let { id ->
            database.planDao().getWorkSchedule(id)?.let { created ->
              database.planDao().deleteWorkSchedule(created)
            }
          }
        }
      }
    }
  }

  private fun openManager() {
    composeRule.onNodeWithTag("open_settings").performClick()
    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_category_notifications"))
    composeRule.onNodeWithTag("settings_category_planning").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("working_schedule_list")).fetchSemanticsNodes().size == 1
    }
  }

  private fun revealPlanningFooter() {
    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_category_brief"))
  }

  private fun dismissUndoSnackbar() {
    composeRule.waitUntil(timeoutMillis = 10_000) {
      composeRule.onAllNodes(hasText("Undo")).fetchSemanticsNodes().size == 1
    }
    composeRule.onNodeWithContentDescription("Dismiss").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isEmpty()
    }
  }
}
