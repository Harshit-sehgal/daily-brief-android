package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.viewmodel.BriefingViewModel
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five width classes, checked at the dp either side of every boundary on a real window.
 *
 * The classification itself is pinned by unit tests; this is the other half — that the rendered
 * shell actually changes at 600, 840, 1200 and 1600 dp, and that crossing a boundary keeps the
 * person where they were with their draft intact.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WidthBoundaryMatrixInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @After
  fun restoreWindow() {
    shell("wm size reset")
    shell("wm density reset")
    composeRule.waitForIdle()
  }

  @Test
  fun everyBoundaryFlipsTheShellAtTheExactDp() {
    composeRule.onNodeWithTag("tab_Plan").performClick()
    composeRule.onNodeWithText("Outline").performClick()

    // Compact keeps navigation at the bottom; every wider class moves it to a side rail.
    setWindow(599)
    assertBottomNavigation()
    assertNoLedger()

    setWindow(600)
    assertSideRail()
    assertNoLedger()

    setWindow(839)
    assertSideRail()
    assertNoLedger()

    // Expanded is where a second pane earns its place.
    setWindow(840)
    assertSideRail()
    assertEquals(300, ledgerWidthDp())

    setWindow(1_199)
    assertEquals(300, ledgerWidthDp())

    setWindow(1_200)
    assertEquals(320, ledgerWidthDp())

    setWindow(1_599)
    assertEquals(320, ledgerWidthDp())

    setWindow(1_600)
    assertEquals(336, ledgerWidthDp())
  }

  @Test
  fun crossingABoundaryKeepsTheRootTheDayAndAnUnsavedDraft() {
    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    val title = "Boundary draft ${System.nanoTime().toString(16)}"

    composeRule.onNodeWithTag("tab_Calendar").performClick()
    composeRule.onNodeWithText("Timeline").performClick()
    composeRule.runOnIdle { viewModel.selectDay(viewModel.selectedDay.value + 24 * 60 * 60 * 1000L) }
    val selectedDay = viewModel.selectedDay.value

    composeRule.onNodeWithTag("add_event").performClick()
    composeRule.waitUntil(timeoutMillis = 10_000) {
      composeRule.onAllNodes(hasTestTag("event_editor")).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("editor_title").performTextInput(title)

    // Cross Medium → Expanded, the boundary that changes the most about the screen.
    setWindow(839)
    setWindow(840)

    val restored = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodes(hasTestTag("event_editor")).fetchSemanticsNodes().isNotEmpty()
    }
    // The same place, the same day, and the same words the person had typed.
    assertEquals(selectedDay, restored.selectedDay.value)
    composeRule.onNodeWithTag("screen_calendar").assertIsDisplayed()
    composeRule.onNodeWithText(title).assertIsDisplayed()
  }

  private fun assertBottomNavigation() {
    val bounds = composeRule.onNodeWithTag("tab_Home").fetchSemanticsNode().boundsInRoot
    val root = composeRule.onRoot().fetchSemanticsNode().size
    assertTrue(
      "compact navigation should sit at the bottom, was $bounds in $root",
      bounds.top > root.height / 2f,
    )
  }

  private fun assertSideRail() {
    val bounds = composeRule.onNodeWithTag("tab_Home").fetchSemanticsNode().boundsInRoot
    val root = composeRule.onRoot().fetchSemanticsNode().size
    assertTrue(
      "wide navigation should sit on the side, was $bounds in $root",
      bounds.top < root.height / 2f && bounds.left < root.width / 4f,
    )
  }

  private fun assertNoLedger() {
    assertTrue(
      "the planning ledger belongs to Expanded and wider",
      composeRule.onAllNodes(hasTestTag("plan_ledger_container")).fetchSemanticsNodes().isEmpty(),
    )
  }

  private fun ledgerWidthDp(): Int {
    composeRule.waitUntil(timeoutMillis = 10_000) {
      composeRule.onAllNodes(hasTestTag("plan_ledger_container")).fetchSemanticsNodes().isNotEmpty()
    }
    val node = composeRule.onNodeWithTag("plan_ledger_container").fetchSemanticsNode()
    // The window runs at 160 dpi for this test, so a pixel is a dp.
    return node.size.width
  }

  /** 160 dpi makes dp and px the same number, so a boundary can be hit exactly. */
  private fun setWindow(widthDp: Int, heightDp: Int = 900) {
    shell("wm density 160")
    shell("wm size ${widthDp}x$heightDp")
    composeRule.waitForIdle()
    Thread.sleep(700)
    composeRule.waitForIdle()
  }

  private fun shell(command: String) {
    val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
    FileInputStream(descriptor.fileDescriptor).use { stream -> stream.readBytes() }
  }
}
