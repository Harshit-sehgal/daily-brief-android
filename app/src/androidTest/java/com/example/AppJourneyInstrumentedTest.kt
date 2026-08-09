package com.example

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun createEditAndDeleteJourneyPersistsThroughRoom() {
    val title = "Emulator journey ${System.nanoTime()}"

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
}
