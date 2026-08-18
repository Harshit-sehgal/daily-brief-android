package com.example.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionsTest {
  private val sessions = Sessions("test-secret-with-enough-entropy-1234")

  @Test
  fun `a session round-trips`() {
    val token = sessions.issue("ws-1")
    assertEquals(Session("ws-1"), sessions.verify(token))
  }

  @Test
  fun `a tampered token is refused`() {
    val token = sessions.issue("ws-1")
    val tampered = token.dropLast(1) + if (token.last() == 'a') 'b' else 'a'
    assertNull(sessions.verify(tampered))
  }

  @Test
  fun `an expired session is refused`() {
    val token = sessions.issue("ws-1")
    val parts = token.split(".")
    val expired = "${parts[0]}.${System.currentTimeMillis() - 1}.${parts[2]}"
    assertNull(sessions.verify(expired))
  }

  @Test
  fun `a malformed token is refused`() {
    assertNull(sessions.verify("not-a-token"))
    assertNull(sessions.verify("a.b"))
  }

  @Test
  fun `different workspaces issue different tokens`() {
    assertNotEquals(sessions.issue("ws-1"), sessions.issue("ws-2"))
  }
}