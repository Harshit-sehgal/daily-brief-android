package com.example.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsrfTokensTest {
  private val tokens = CsrfTokens("test-session-secret-with-more-than-32-bytes")

  @Test
  fun `a signed token verifies until its expiry`() {
    val token = tokens.issue(nowMs = 1_000L)

    assertTrue(tokens.verify(token, nowMs = 1_000L))
    assertTrue(tokens.verify(token, nowMs = 1_000L + 7 * 24 * 60 * 60 * 1000L - 1))
    assertFalse(tokens.verify(token, nowMs = 1_000L + 7 * 24 * 60 * 60 * 1000L))
  }

  @Test
  fun `tampering and missing values are refused`() {
    val token = tokens.issue(nowMs = 1_000L)
    val tampered = token.replaceFirst('.', 'x')

    assertFalse(tokens.verify(tampered, nowMs = 1_000L))
    assertFalse(tokens.verify(null, nowMs = 1_000L))
    assertFalse(tokens.verify("", nowMs = 1_000L))
  }
}
