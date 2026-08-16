package com.example

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A source we cannot mutate must never advertise or execute a mutation locally. */
@RunWith(AndroidJUnit4::class)
class ReadOnlyEventInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val eventId = "notion_readonly_${System.nanoTime()}"
  private val title = "Read-only ${System.nanoTime()}"

  @After
  fun removeTestRow() {
    runBlocking { AppDatabase.getDatabase(context).eventDao().deleteEventById(eventId) }
  }

  @Test
  fun readOnlyAgendaRowOffersNoMutationGestureOrMenuCommand() {
    val dayStart = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
    runBlocking {
      AppDatabase.getDatabase(context)
        .eventDao()
        .insertEvents(
          listOf(
            BriefingEvent(
              id = eventId,
              title = title,
              startTime = dayStart + 15 * 60 * 60 * 1000L,
              endTime = dayStart + 16 * 60 * 60 * 1000L,
              source = EventSource.NOTION,
              description = null,
              isDeadline = false,
              isUrgent = false,
            )
          )
        )
    }

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle { viewModel.selectToday() }

    val row = hasTestTag("agenda_item") and hasText(title)
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(row).fetchSemanticsNodes().size == 1
    }

    val restingLeft = composeRule.onNode(row).getUnclippedBoundsInRoot().left

    composeRule
      .onNode(row)
      .assert(
        SemanticsMatcher("has no read-only mutation actions") { node ->
          node.config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()
            .none {
              it.label == "Move to tomorrow" || it.label.startsWith("Delete")
            }
        }
      )

    composeRule.onNode(row).performTouchInput { swipeLeft() }

    // The unsupported direction has no anchor, so it cannot imply a deletion.
    composeRule.waitForIdle()
    composeRule.onNode(row).assertIsDisplayed().assertLeftPositionInRootIsEqualTo(restingLeft)
    assertNotNull(
      "A read-only gesture must leave the stored event alone",
      runBlocking { AppDatabase.getDatabase(context).eventDao().getEventById(eventId) },
    )

    composeRule.onNodeWithTag("agenda_actions_$eventId").performClick()
    composeRule.onNodeWithText("Open details").assertIsDisplayed()
    assertFalse(
      "A read-only menu must not advertise delete",
      composeRule.onAllNodesWithText("Delete", substring = true).fetchSemanticsNodes().isNotEmpty(),
    )
    assertFalse(
      "A read-only menu must not advertise rescheduling",
      composeRule.onAllNodesWithText("Move to tomorrow").fetchSemanticsNodes().isNotEmpty(),
    )
  }

  @Test
  fun NotionEditorExplainsOwnershipAndLimitsChangesToLocalLabels() {
    val dayStart = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
    runBlocking {
      AppDatabase.getDatabase(context)
        .eventDao()
        .insertEvents(
          listOf(
            BriefingEvent(
              id = eventId,
              title = title,
              startTime = dayStart + 15 * 60 * 60 * 1000L,
              endTime = dayStart + 16 * 60 * 60 * 1000L,
              source = EventSource.NOTION,
              description = "Source notes",
              isDeadline = false,
              isUrgent = false,
            )
          )
        )
    }

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Agenda").performClick()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle { viewModel.selectToday() }

    val row = hasTestTag("agenda_item") and hasText(title)
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(row).fetchSemanticsNodes().size == 1
    }
    composeRule.onNode(row).performClick()
    composeRule.onNodeWithTag("event_ownership").assertIsDisplayed()
    composeRule.onNodeWithText("controlled by Notion", substring = true).assertIsDisplayed()

    composeRule.onNodeWithTag("editor_title").assertIsNotEnabled()
    composeRule.onNodeWithTag("editor_notes").assertIsNotEnabled()
    composeRule.onNodeWithTag("editor_date").assertIsNotEnabled()
    composeRule.onNodeWithTag("editor_start").assertIsNotEnabled()
    composeRule.onNodeWithTag("editor_end").assertIsNotEnabled()
    composeRule.onNodeWithTag("editor_all_day").assertIsNotEnabled()
    composeRule.onNodeWithTag("editor_urgent").assertIsEnabled()
    composeRule.onNodeWithTag("editor_deadline").assertIsEnabled()
    composeRule.onNodeWithContentDescription("Board: Default").assertIsEnabled()
    composeRule.onNodeWithContentDescription("Column: To Do").assertIsEnabled()
    composeRule.onNodeWithTag("editor_delete").assertDoesNotExist()
  }

  @Test
  fun NotionTimelineBlockIsMarkedFixedAndNamesItsSource() {
    val dayStart = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
    runBlocking {
      AppDatabase.getDatabase(context)
        .eventDao()
        .insertEvents(
          listOf(
            BriefingEvent(
              id = eventId,
              title = title,
              startTime = dayStart + 15 * 60 * 60 * 1000L,
              endTime = dayStart + 16 * 60 * 60 * 1000L,
              source = EventSource.NOTION,
              description = null,
              isDeadline = false,
              isUrgent = false,
            )
          )
        )
    }

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Timeline").performClick()
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle { viewModel.selectToday() }

    val block = hasTestTag("timeline_block") and hasText(title)
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(block).fetchSemanticsNodes().size == 1
    }
    composeRule
      .onNode(block)
      .assert(hasText("FIXED"))
      .assert(
        SemanticsMatcher.expectValue(
          SemanticsProperties.StateDescription,
          "Fixed Notion event; reschedule it in its source app",
        )
      )
  }
}
