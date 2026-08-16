package com.example.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoWindowPolicyTest {
  @Test
  fun `unset or unusable storage falls back to the default rather than the shortest window`() {
    listOf(null, "", "   ", "abc", "0", "-300", "45", "999999999999999999999").forEach { raw ->
      assertEquals("raw=$raw", UndoWindowPolicy.DEFAULT_SECONDS, UndoWindowPolicy.seconds(raw))
    }
    // The default is deliberately longer than the old fixed 30 seconds.
    assertTrue(UndoWindowPolicy.DEFAULT_SECONDS > 30)
    assertTrue(UndoWindowPolicy.DEFAULT_SECONDS in UndoWindowPolicy.Choices)
  }

  @Test
  fun `every stored choice round-trips to the same window and a readable label`() {
    UndoWindowPolicy.Choices.forEach { seconds ->
      val stored = UndoWindowPolicy.store(seconds)
      assertEquals(seconds, UndoWindowPolicy.seconds(stored))
      assertEquals(seconds * 1_000L, UndoWindowPolicy.windowMs(stored))
      assertTrue(UndoWindowPolicy.label(seconds).isNotBlank())
    }
    assertEquals(UndoWindowPolicy.seconds(" 300 "), 5 * 60)
    assertEquals(listOf("30 seconds", "5 minutes", "1 hour", "24 hours"),
      UndoWindowPolicy.Choices.map(UndoWindowPolicy::label))
  }

  @Test
  fun `an unsupported window cannot be stored`() {
    listOf(0, -1, 45, 7 * 24 * 60 * 60).forEach { seconds ->
      runCatching { UndoWindowPolicy.store(seconds) }
        .onSuccess { throw AssertionError("stored an unsupported window: $seconds") }
    }
  }
}
