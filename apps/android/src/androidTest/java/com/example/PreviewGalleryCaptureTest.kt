package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.ScheduleAnalysis
import com.example.core.WorkingCalendar
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a gate — a capture harness for `preview.html`.
 *
 * It seeds one legible day and plan, walks the real screens, and writes PNGs that
 * `scripts/capture-preview.sh` turns into the gallery. [CaptureOnly] keeps it out of every
 * Gradle-launched device run, because it writes rows and settings the gate does not expect.
 */
@CaptureOnly
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PreviewGalleryCaptureTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val database by lazy { AppDatabase.getDatabase(context) }
  private val planRepository by lazy { PlanRepository(context) }
  private val briefingRepository by lazy { BriefingRepository(context) }

  private val outputDir: File
    get() = File(context.getExternalFilesDir(null), "preview").apply { mkdirs() }

  @Test
  fun capturePlates() {
    seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle { viewModel.selectToday() }

    setTheme(SettingKeys.THEME_MODE_LIGHT)
    composeRule.onNodeWithTag("tab_Home").performClick()
    settle()
    capture("home")

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()
    settle()
    capture("agenda")

    composeRule.onNodeWithText("Timeline").performClick()
    settle()
    capture("timeline")

    composeRule.onNodeWithText("Week").performClick()
    settle()
    capture("week")

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    settle()
    capture("outline")

    composeRule.onNodeWithText("Board").performClick()
    settle()
    capture("board")

    composeRule.onNodeWithText("Gantt").performClick()
    settle()
    capture("gantt")

    enterGanttMoveMode()
    capture("gantt_move")
    composeRule.onNodeWithTag("gantt_move_cancel").performClick()
    settle()

    captureAnalysisPlates(viewModel)

    composeRule.onNodeWithTag("open_settings").performClick()
    settle()
    capture("settings")

    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_category_notifications"))
    composeRule.onNodeWithTag("settings_category_planning").performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("working_schedule_list")).fetchSemanticsNodes().size == 1
    }
    composeRule.onNodeWithTag("undo_window_choices").performScrollTo()
    settle()
    capture("undo_window")
    composeRule.onNodeWithTag("working_schedule_list").performScrollTo()
    settle()
    capture("schedules")
    composeRule.activityRule.scenario.recreate()
    settle()

    setTheme(SettingKeys.THEME_MODE_DARK)
    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Gantt").performClick()
    settle()
    capture("gantt_dark")
    composeRule.onNodeWithTag("tab_Home").performClick()
    settle()
    capture("home_dark")
    setTheme(SettingKeys.THEME_MODE_LIGHT)
  }

  /** Run this one with the device resized to an Expanded window; see scripts/capture-preview.sh. */
  @Test
  fun captureWidePlates() {
    seed()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle { viewModel.selectToday() }
    setTheme(SettingKeys.THEME_MODE_LIGHT)

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()
    settle()
    capture("wide_calendar")

    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()
    settle()
    capture("wide_plan")
  }

  /**
   * The two analysis dialogs, photographed with real drift rather than an empty state.
   *
   * A baseline is taken, one block is moved a day later, and the comparison is opened on that — a
   * plate of "nothing has moved" would show the surface without showing what it is for.
   */
  private fun captureAnalysisPlates(viewModel: BriefingViewModel) {
    val boardId = viewModel.activePlanBoardId.value ?: return
    // Re-running the harness must not stack drift on drift, so start from no baseline at all.
    viewModel.planBaselines.value.forEach { existing ->
      composeRule.runOnIdle { viewModel.deleteBaseline(existing.id) }
    }
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.planBaselines.value.isEmpty() }
    composeRule.runOnIdle { viewModel.captureBaseline("Start of the week") }
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.planBaselines.value.isNotEmpty() }
    val baseline = viewModel.planBaselines.value.first()

    runBlocking {
      database.planDao().getBlocksForBoard(boardId).firstOrNull()?.let { block ->
        database.planDao().updateBlock(
          block.copy(startAt = block.startAt + 86_400_000L, endAt = block.endAt + 86_400_000L)
        )
      }
    }
    settle()
    composeRule.runOnIdle { viewModel.compareBaseline(baseline.id) }
    composeRule.waitUntil(timeoutMillis = 15_000) { viewModel.baselineComparison.value != null }
    settle()
    captureScreen("baseline_comparison")
    // Restore, so the plates that follow show the plan as seeded rather than the drift used here.
    composeRule.onNodeWithTag("baseline_restore").performClick()
    settle()

    composeRule.runOnIdle { viewModel.comparePlanScenarios() }
    composeRule.waitUntil(timeoutMillis = 25_000) { viewModel.planScenarios.value.isNotEmpty() }
    settle()
    captureScreen("plan_scenarios")
    composeRule.onNodeWithTag("plan_scenarios_close").performClick()
    settle()
  }

  private fun enterGanttMoveMode() {
    val bar = hasTestTag("plan_gantt_item") and hasContentDescription(PLAN_BLOCK_TASK, substring = true)
    composeRule.waitUntil(timeoutMillis = 10_000) {
      composeRule.onAllNodes(bar).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onAllNodes(bar)[0].performScrollTo().performTouchInput { longClick() }
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(hasTestTag("gantt_move_mode")).fetchSemanticsNodes().isNotEmpty()
    }
    settle()
  }

  private fun setTheme(mode: String) {
    runBlocking { briefingRepository.writeSetting(SettingKeys.THEME_MODE, mode) }
    composeRule.waitForIdle()
    Thread.sleep(400)
  }

  private fun settle() {
    composeRule.waitForIdle()
    Thread.sleep(600)
    composeRule.waitForIdle()
  }

  private fun capture(name: String) {
    write(name, composeRule.onRoot().captureToImage().asAndroidBitmap())
  }

  /**
   * The whole device screen, for plates that include a dialog.
   *
   * A dialog is its own window, so `onRoot()` finds two roots and refuses; capturing only the
   * dialog's root would also lose the screen it is standing on, which is half of what the plate is
   * showing.
   */
  private fun captureScreen(name: String) {
    write(name, InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
  }

  private fun write(name: String, bitmap: Bitmap) {
    File(outputDir, "$name.png").outputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
  }

  private fun seed() =
    runBlocking {
      planRepository.ensureCatalog()
      val dayStart = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
      fun at(hour: Int, minute: Int = 0) = dayStart + (hour * 60L + minute) * 60_000L
      database.eventDao().insertEvents(
        listOf(
          event("preview_standup", "Team stand-up", at(9, 30), at(9, 45), EventSource.DEVICE),
          event(
            "preview_review",
            "Design review · Atlas",
            at(11),
            at(12),
            EventSource.DEVICE,
            location = "Studio 2",
          ),
          event("preview_lunch", "Lunch with Priya", at(13), at(14), EventSource.DEVICE),
          event(
            "preview_deep",
            "Deep work · migration plan",
            at(14, 30),
            at(16),
            EventSource.MANUAL,
          ),
          event(
            "preview_notion",
            "Ship release notes",
            at(15, 30),
            at(16, 30),
            EventSource.NOTION,
            deadline = true,
          ),
          event("preview_call", "1:1 with Sam", at(17), at(17, 30), EventSource.DEVICE),
        )
      )

      val boardId = "preview_board"
      val now = System.currentTimeMillis()
      if (database.planDao().getBoard(boardId) == null) {
        database.planDao().insertBoard(
          PlanBoard(
            id = boardId,
            name = "Atlas launch",
            nameKey = "atlas launch",
            rank = database.planDao().maxBoardRank() + 10_000L,
            createdAt = now,
            updatedAt = now,
          )
        )
      }
      briefingRepository.writeSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, boardId)
      briefingRepository.writeSetting(SettingKeys.GANTT_RANGE_DAYS, "7")

      if (database.planDao().getColumns(boardId).isEmpty()) {
        listOf("In progress", "Review", "Done").forEachIndexed { index, name ->
          database.planDao().insertColumn(
            PlanColumn(
              id = "preview_column_$index",
              boardId = boardId,
              name = name,
              nameKey = name.lowercase(),
              rank = (index + 1) * 10_000L,
              createdAt = now,
              updatedAt = now,
            )
          )
        }
      }

      val tasks =
        listOf(
          Triple(PLAN_BLOCK_TASK, 180, 40),
          Triple("Rewrite onboarding copy", 120, 0),
          Triple("Migrate billing tables", 240, 15),
          Triple("Accessibility sweep", 90, 0),
          Triple("Launch checklist", 60, 80),
        )
      val existing = planRepository.observeItems(boardId).first().map { it.title }.toSet()
      val created =
        tasks.filterNot { it.first in existing }.map { (title, effort, progress) ->
          planRepository.saveItem(
            PlanItemInput(
              boardId = boardId,
              title = title,
              effortMinutes = effort,
              progress = progress,
            )
          )
        }

      // Give the Board something to be a board about: work spread across its lanes.
      val columns = database.planDao().getColumns(boardId)
      created.drop(2).forEachIndexed { index, item ->
        columns.getOrNull(index)?.let { column -> planRepository.moveItemToColumn(item.id, column.id) }
      }

      val spec = requireNotNull(planRepository.observeDefaultWorkingCalendar().first()).spec
      val rangeStart = ScheduleAnalysis.startOfDayOffset(System.currentTimeMillis(), 1)
      val windows =
        WorkingCalendar.workingIntervals(spec, rangeStart, ScheduleAnalysis.startOfDayOffset(rangeStart, 14))
          .filter { it.durationMinutes >= 120 }
      created.take(3).forEachIndexed { index, item ->
        val window = windows.getOrNull(index * 2) ?: return@forEachIndexed
        // One block stays inside the schedule's 30–120 minute chunk limits; longer effort is
        // meant to be split, which is exactly what the Gantt shows.
        val minutes = (item.effortMinutes ?: 60).coerceIn(30, 90)
        val startAt = window.startAt + 30 * 60_000L
        planRepository.saveBlock(
          PlanBlockInput(
            planItemId = item.id,
            startAt = startAt,
            endAt = startAt + minutes * 60_000L,
          )
        )
      }
    }

  private fun event(
    id: String,
    title: String,
    startTime: Long,
    endTime: Long,
    source: String,
    location: String? = null,
    deadline: Boolean = false,
  ) =
    BriefingEvent(
      id = id,
      title = title,
      startTime = startTime,
      endTime = endTime,
      source = source,
      description = null,
      isDeadline = deadline,
      isUrgent = false,
      location = location,
    )

  private companion object {
    const val PLAN_BLOCK_TASK = "Draft the launch brief"
  }
}
