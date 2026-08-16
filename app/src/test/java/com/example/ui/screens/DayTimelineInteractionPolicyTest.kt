package com.example.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTimelineInteractionPolicyTest {
  @Test
  fun `only taps inside the appointment lane create events`() {
    assertFalse(isTimelineLaneTap(x = 51f, laneStart = 52f, laneEnd = 988f))
    assertTrue(isTimelineLaneTap(x = 52f, laneStart = 52f, laneEnd = 988f))
    assertTrue(isTimelineLaneTap(x = 500f, laneStart = 52f, laneEnd = 988f))
    assertTrue(isTimelineLaneTap(x = 988f, laneStart = 52f, laneEnd = 988f))
    assertFalse(isTimelineLaneTap(x = 989f, laneStart = 52f, laneEnd = 988f))
  }
}
