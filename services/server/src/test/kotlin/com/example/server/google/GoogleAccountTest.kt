package com.example.server.google

import com.example.server.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class GoogleAccountTest {
  private val config =
    Config(
      databaseUrl = "jdbc:postgresql://localhost/dailybrief",
      databaseUser = "postgres",
      databasePassword = "postgres",
      sessionSecret = "test-session-secret-with-more-than-32-bytes",
      googleClientId = "client",
      googleClientSecret = "secret",
    )

  private fun token(refresh: String?, scope: String?) =
    GoogleOAuth.TokenResponse(access_token = "access", token_type = "Bearer", expires_in = 3600, refresh_token = refresh, scope = scope)

  @Test
  fun `a later sign-in without a refresh token keeps the stored one and every granted scope`() {
    val merged =
      GoogleAccount.merge(
        old = token("stored-refresh", null),
        oldScopes = setOf("openid", GoogleScopes.CALENDAR),
        fresh = token(null, "openid email"),
      )

    assertEquals("stored-refresh", merged.refresh_token)
    assertEquals(setOf("openid", "email", GoogleScopes.CALENDAR), GoogleScopes.parse(merged.scope))
  }

  @Test
  fun `a fresh consent replaces the refresh token`() {
    val merged = GoogleAccount.merge(token("old", null), emptySet(), token("new", GoogleScopes.DRIVE))

    assertEquals("new", merged.refresh_token)
    assertEquals(setOf(GoogleScopes.DRIVE), GoogleScopes.parse(merged.scope))
  }

  @Test
  fun `signing in asks only who you are and connecting adds one scope with consent`() {
    fun url(connect: String?) =
      URLDecoder.decode(
        GoogleOAuth.authorizeUrl(config, "http://localhost:3000/auth/callback", "state", "challenge", GoogleScopes.requested(connect), consent = connect != null),
        Charsets.UTF_8,
      )

    val signIn = url(null)
    assertTrue(signIn.contains("scope=openid email profile&"))
    assertTrue(signIn.contains("prompt=select_account"))
    assertFalse(signIn.contains("calendar"))
    assertTrue(signIn.contains("include_granted_scopes=true"))

    assertTrue(url("calendar").contains("scope=openid email profile ${GoogleScopes.CALENDAR}&"))
    assertTrue(url("calendar").contains("prompt=consent"))
    assertTrue(url("drive").contains("scope=openid email profile ${GoogleScopes.DRIVE}&"))
    assertFalse(url("drive").contains("calendar"))
  }
}
