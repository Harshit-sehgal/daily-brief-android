package com.example.data.prefs

/**
 * How long a change stays undoable.
 *
 * Recovery used to expire after a fixed thirty seconds, which is about how long it takes a screen
 * reader to reach the snackbar action and far less than it takes to notice a wrong move, open Plan
 * History and read the entry. A time limit the product imposes has to be adjustable
 * ([WCAG 2.2.1 Timing Adjustable](https://www.w3.org/WAI/WCAG22/Understanding/timing-adjustable.html)),
 * so the window is a setting.
 *
 * Widening it cannot corrupt anything: Undo is compare-and-set, so a stale entry still refuses
 * rather than overwriting a newer edit. The only thing a longer window costs is journal rows.
 */
object UndoWindowPolicy {
  /** Seconds, shortest first. The snackbar is unaffected — it is only an accelerator. */
  val Choices = listOf(30, 5 * 60, 60 * 60, 24 * 60 * 60)

  const val DEFAULT_SECONDS = 5 * 60

  /** Unknown or malformed storage falls back to the default rather than to the shortest window. */
  fun seconds(raw: String?): Int =
    raw?.trim()?.toIntOrNull()?.takeIf { it in Choices } ?: DEFAULT_SECONDS

  fun windowMs(raw: String?): Long = seconds(raw) * 1_000L

  fun label(seconds: Int): String =
    when (seconds) {
      30 -> "30 seconds"
      5 * 60 -> "5 minutes"
      60 * 60 -> "1 hour"
      24 * 60 * 60 -> "24 hours"
      else -> "$seconds seconds"
    }

  fun store(seconds: Int): String {
    require(seconds in Choices) { "Unsupported undo window" }
    return seconds.toString()
  }
}
