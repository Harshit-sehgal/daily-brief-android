package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/**
 * How a journey reaches the Plan's secondary commands.
 *
 * They used to sit in a row above the work, so tests tapped them directly. They live behind the
 * header's one menu now, and every journey goes through here rather than each spelling out the same
 * two taps — the next time this moves, it moves in one file.
 */
@OptIn(ExperimentalTestApi::class)
object PlanMenu {
  private fun AndroidComposeTestRule<*, *>.openMenu() {
    waitUntilExactlyOneExists(hasTestTag("plan_overflow"), timeoutMillis = 15_000)
    onNodeWithTag("plan_overflow").performClick()
  }

  private fun AndroidComposeTestRule<*, *>.choose(tag: String) {
    openMenu()
    waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 10_000)
    onNodeWithTag(tag).performClick()
  }

  fun AndroidComposeTestRule<*, *>.openPlanHealth() = choose("plan_menu_health")

  fun AndroidComposeTestRule<*, *>.openPlanHistory() = choose("plan_menu_history")

  fun AndroidComposeTestRule<*, *>.openViewOptions() {
    choose("plan_menu_view_options")
    waitUntilExactlyOneExists(hasTestTag("plan_view_options_dialog"), timeoutMillis = 10_000)
  }

  /** Applying a view closes the sheet on its own; only the other options need this. */
  fun AndroidComposeTestRule<*, *>.closeViewOptions() {
    onNodeWithTag("plan_view_options_close").performClick()
  }

  fun AndroidComposeTestRule<*, *>.openManageViews() {
    openViewOptions()
    onNodeWithTag("plan_manage_views").performScrollTo().performClick()
    waitUntilExactlyOneExists(hasTestTag("plan_manage_views_dialog"), timeoutMillis = 10_000)
  }

  fun AndroidComposeTestRule<*, *>.closeManageViews() {
    onNodeWithTag("plan_manage_views_close").performClick()
  }

  /** Opens the tools dialog and taps one of its rows; the dialog closes itself on choosing. */
  fun AndroidComposeTestRule<*, *>.openPlanTool(tag: String) {
    choose("plan_menu_tools")
    waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 10_000)
    onNodeWithTag(tag).performClick()
  }

  /** Export lives directly on the menu, because it is one tap and has no options to review. */
  fun AndroidComposeTestRule<*, *>.exportPlan(asCalendar: Boolean) =
    choose(if (asCalendar) "plan_export_ics" else "plan_export_csv")

  /**
   * The Schedule map's own menu, which holds the references — dependencies and the legend — that
   * used to stand on a chip row above the canvas.
   */
  fun AndroidComposeTestRule<*, *>.openMapOption(tag: String) {
    waitUntilExactlyOneExists(hasTestTag("gantt_map_menu"), timeoutMillis = 15_000)
    onNodeWithTag("gantt_map_menu").performClick()
    waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 10_000)
    onNodeWithTag(tag).performClick()
  }

  /** Saving the current view is a View options command, like everything else about presentation. */
  fun AndroidComposeTestRule<*, *>.startSavingView() {
    openViewOptions()
    onNodeWithTag("save_plan_view").performScrollTo().performClick()
  }
}
