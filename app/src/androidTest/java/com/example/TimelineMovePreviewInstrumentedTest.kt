package com.example

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.ScheduleAnalysis
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendered proof that every Timeline move remains a preview until one exact commit. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TimelineMovePreviewInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val eventId = "timeline-preview-${System.nanoTime()}"
  private val conflictId = "timeline-conflict-${System.nanoTime()}"
  private val title = "Preview move ${System.nanoTime()}"

  @After
  fun removeTestRows() {
    runBlocking {
      val dao = AppDatabase.getDatabase(context).eventDao()
      dao.deleteEventById(eventId)
      dao.deleteEventById(conflictId)
    }
  }

  @Test
  fun accessibilityMovePreviewsConflictCancelApplyAndExactUndo() {
    val dayStart = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
    val before = event(dayStart)
    val conflict =
      event(
        dayStart = dayStart,
        id = conflictId,
        eventTitle = "Overlapping review",
        startMinute = 15 * 60 + 45,
        durationMinutes = 30,
      )
    runBlocking {
      AppDatabase.getDatabase(context).eventDao().insertEvents(listOf(before, conflict))
    }
    openTodayTimeline()

    val block = hasTestTag("timeline_block") and hasText(title)
    waitForOne(block)
    composeRule
      .onNode(block)
      .performCustomAccessibilityActionWithLabel("Move 15 minutes later")

    composeRule.onNodeWithText("Move $title?").assertIsDisplayed()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("Conflicts · 1:", substring = true).fetchSemanticsNodes().size ==
        1
    }
    assertEquals(before, storedEvent())
    composeRule.onNodeWithTag("timeline_move_cancel").performClick()
    assertEquals(before, storedEvent())

    composeRule
      .onNode(block)
      .performCustomAccessibilityActionWithLabel("Move 15 minutes later")
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("Conflicts · 1:", substring = true).fetchSemanticsNodes().size ==
        1
    }
    composeRule.onNodeWithTag("timeline_move_apply").performClick()

    val expected =
      before.copy(
        startTime = before.startTime + 15 * 60_000L,
        endTime = before.endTime + 15 * 60_000L,
        userEdited = true,
      )
    composeRule.waitUntil(timeoutMillis = 5_000) { storedEvent() == expected }
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Undo").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) { storedEvent() == before }
  }

  @Test
  fun stalePreviewFailsClosedAndDateAndKeyboardPathsReachTheSamePreview() {
    val dayStart = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
    val before = event(dayStart)
    runBlocking { AppDatabase.getDatabase(context).eventDao().insertEvents(listOf(before)) }
    openTodayTimeline()

    val originalBlock = hasTestTag("timeline_block") and hasText(title)
    waitForOne(originalBlock)
    composeRule
      .onNode(originalBlock)
      .performCustomAccessibilityActionWithLabel("Move 15 minutes later")
    composeRule.onNodeWithText("Move $title?").assertIsDisplayed()

    val newer = before.copy(description = "A newer edit", userEdited = true)
    runBlocking { AppDatabase.getDatabase(context).eventDao().insertEvents(listOf(newer)) }
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule
        .onAllNodesWithText("This event changed after the preview", substring = true)
        .fetchSemanticsNodes()
        .size == 1
    }
    composeRule.onNodeWithTag("timeline_move_apply").assertIsNotEnabled()
    assertEquals(newer, storedEvent())
    composeRule.onNodeWithTag("timeline_move_cancel").performClick()

    val currentBlock = hasTestTag("timeline_block") and hasText(title)
    composeRule
      .onNode(currentBlock)
      .performCustomAccessibilityActionWithLabel("Move to another date")
    composeRule.onNodeWithTag("timeline_move_date_apply").assertIsDisplayed().performClick()
    composeRule.onNodeWithText("Move $title?").assertIsDisplayed()
    composeRule.onNodeWithTag("timeline_move_cancel").performClick()

    composeRule
      .onNode(currentBlock)
      .requestFocus()
      .performKeyInput {
        withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) }
      }
    composeRule.onNodeWithText("Move $title?").assertIsDisplayed()
    composeRule.onNodeWithTag("timeline_move_cancel").performClick()
    assertEquals(newer, storedEvent())
  }

  private fun openTodayTimeline() {
    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Timeline").performClick()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle { viewModel.selectToday() }
  }

  private fun waitForOne(matcher: androidx.compose.ui.test.SemanticsMatcher) {
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(matcher).fetchSemanticsNodes().size == 1
    }
  }

  private fun storedEvent(): BriefingEvent? =
    runBlocking { AppDatabase.getDatabase(context).eventDao().getEventById(eventId) }

  private fun event(
    dayStart: Long,
    id: String = eventId,
    eventTitle: String = title,
    startMinute: Int = 15 * 60,
    durationMinutes: Int = 60,
  ): BriefingEvent {
    val start = dayStart + startMinute * 60_000L
    return BriefingEvent(
      id = id,
      title = eventTitle,
      startTime = start,
      endTime = start + durationMinutes * 60_000L,
      source = EventSource.MANUAL,
      description = "Timeline preview",
      isDeadline = false,
      isUrgent = false,
    )
  }
}
