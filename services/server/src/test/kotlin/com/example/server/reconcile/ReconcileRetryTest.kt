package com.example.server.reconcile

import com.example.server.google.CalendarProviderFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcileRetryTest {
  @Test
  fun `only transient provider failures are retryable`() {
    assertTrue(ReconcileRetry.isTransient(CalendarProviderFailure(408, "timeout")))
    assertTrue(ReconcileRetry.isTransient(CalendarProviderFailure(429, "rate limited")))
    assertTrue(ReconcileRetry.isTransient(CalendarProviderFailure(503, "unavailable")))
    assertTrue(ReconcileRetry.isTransient(IOException("connection reset")))
    assertFalse(ReconcileRetry.isTransient(CalendarProviderFailure(401, "unauthorized")))
    assertFalse(ReconcileRetry.isTransient(IllegalArgumentException("bad payload")))
  }

  @Test
  fun `transient failures retry to success and stop at the bound`() {
    var attempts = 0
    val value =
      ReconcileRetry.run(attempts = 3, backoffMs = 0) {
        attempts += 1
        if (attempts < 2) throw IOException("temporary")
        "ok"
      }
    assertEquals("ok", value)
    assertEquals(2, attempts)

    attempts = 0
    try {
      ReconcileRetry.run(attempts = 3, backoffMs = 0) {
        attempts += 1
        throw CalendarProviderFailure(503, "unavailable")
      }
      throw AssertionError("expected the bounded retry to throw")
    } catch (expected: CalendarProviderFailure) {
      assertEquals(3, attempts)
    }
  }
}
