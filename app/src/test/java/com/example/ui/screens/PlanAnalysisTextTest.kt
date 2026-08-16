package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sentences the analysis dialogs put in front of a person.
 *
 * These read as prose, so they are held to prose rules: a single unit is singular, a rounded figure
 * never claims more precision than it has, and "unmoved" is only said when nothing moved.
 */
class PlanAnalysisTextTest {
  @Test
  fun `a single unit is singular in every band`() {
    assertEquals("1 minute later than the baseline", describeVariance(1L, added = false, removed = false))
    assertEquals("1 hour later than the baseline", describeVariance(60L, added = false, removed = false))
    assertEquals("1 day later than the baseline", describeVariance(1_440L, added = false, removed = false))
    assertEquals(
      "1 minute earlier than the baseline",
      describeVariance(-1L, added = false, removed = false),
    )
  }

  @Test
  fun `several units stay plural`() {
    assertEquals("45 minutes later than the baseline", describeVariance(45L, added = false, removed = false))
    assertEquals("2 hours earlier than the baseline", describeVariance(-120L, added = false, removed = false))
    assertEquals("3 days later than the baseline", describeVariance(3 * 1_440L, added = false, removed = false))
  }

  @Test
  fun `a task that did not move says so rather than reporting zero`() {
    assertEquals("Unmoved", describeVariance(0L, added = false, removed = false))
    assertEquals("Unmoved", describeVariance(null, added = false, removed = false))
  }

  @Test
  fun `work with no baseline is named as new, not as on time`() {
    assertEquals(
      "Scheduled after the baseline was taken",
      describeVariance(null, added = true, removed = false),
    )
    assertEquals("No longer scheduled", describeVariance(null, added = false, removed = true))
  }

  @Test
  fun `minutes are spoken the way a person would say them`() {
    assertEquals("45m", formatMinutes(45))
    assertEquals("1h", formatMinutes(60))
    assertEquals("2h 30m", formatMinutes(150))
    assertEquals("0m", formatMinutes(0))
  }
}
