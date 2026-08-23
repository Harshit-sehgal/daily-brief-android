package com.example.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthStateTest {
  private val config =
    Config(
      databaseUrl = "jdbc:postgresql://localhost:1/none",
      databaseUser = "postgres",
      databasePassword = "postgres",
      sessionSecret = "test-session-secret-with-entropy",
      webOrigin = "https://app.dailybrief.example",
    )

  @Test
  fun `state binds the approved redirect and expires`() {
    val state = OAuthState(config.sessionSecret).issue(OAuthRedirects.webCallback(config), nowMs = 1_000L)
    val verifier = OAuthState(config.sessionSecret)

    assertTrue(verifier.verify(state, OAuthRedirects.webCallback(config), nowMs = 601_000L))
    val codeVerifier = verifier.codeVerifier(state)
    assertTrue(codeVerifier!!.isNotBlank())
    assertEquals(codeVerifier, OAuthState(config.sessionSecret).codeVerifier(state))
    assertTrue(verifier.codeChallenge(codeVerifier).isNotBlank())
    assertFalse(verifier.verify(state, "mobile://auth/callback", nowMs = 1_000L))
    assertFalse(verifier.verify(state, OAuthRedirects.webCallback(config), nowMs = 601_001L))
  }

  @Test
  fun `tampered or missing state is refused`() {
    val redirect = OAuthRedirects.webCallback(config)
    val state = OAuthState(config.sessionSecret).issue(redirect, nowMs = 1_000L)
    val tampered = state.dropLast(1) + if (state.last() == '0') '1' else '0'
    val verifier = OAuthState(config.sessionSecret)

    assertFalse(verifier.verify(null, redirect, nowMs = 1_000L))
    assertFalse(verifier.verify(tampered, redirect, nowMs = 1_000L))
    assertFalse(OAuthState("other-secret").verify(state, redirect, nowMs = 1_000L))
  }

  @Test
  fun `only the web and mobile callback URIs are accepted`() {
    assertTrue(OAuthRedirects.validate(config, "https://app.dailybrief.example/auth/callback").isNotBlank())
    assertTrue(OAuthRedirects.validate(config, "mobile://auth/callback").isNotBlank())
    assertFalse(runCatching { OAuthRedirects.validate(config, "https://attacker.example/callback") }.isSuccess)
    assertFalse(runCatching { OAuthRedirects.validate(config, "https://app.dailybrief.example/other") }.isSuccess)
  }
}
