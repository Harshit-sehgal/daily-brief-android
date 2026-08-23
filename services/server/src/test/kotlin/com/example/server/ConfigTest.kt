package com.example.server

import com.example.server.key.KeyProviders
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigTest {
  @Test
  fun `production web origin is read from the environment`() {
    assertEquals("https://app.dailybrief.example", Config.fromEnv(env("WEB_ORIGIN" to "https://app.dailybrief.example")).webOrigin)
  }

  @Test
  fun `local web origin remains the development default`() {
    assertEquals("http://localhost:3000", Config.fromEnv(env()).webOrigin)
  }

  @Test
  fun `cors parsing preserves the configured scheme and authority`() {
    assertEquals(WebOrigin("app.dailybrief.example:443", "https"), parseWebOrigin("https://app.dailybrief.example:443"))
  }

  @Test
  fun `cors parsing refuses a path or unsupported scheme`() {
    try {
      parseWebOrigin("https://app.dailybrief.example/callback")
      throw AssertionError("a path must not be accepted as an origin")
    } catch (_: IllegalArgumentException) {
      // expected
    }
    try {
      parseWebOrigin("ftp://app.dailybrief.example")
      throw AssertionError("an unsupported scheme must not be accepted")
    } catch (_: IllegalArgumentException) {
      // expected
    }
  }

  @Test
  fun `partial KMS configuration refuses a local-key fallback`() {
    val config =
      Config(
        databaseUrl = "jdbc:postgresql://localhost/dailybrief",
        databaseUser = "postgres",
        databasePassword = "postgres",
        sessionSecret = "test-session-secret",
        envelopeKeyHex = "00".repeat(32),
        kmsKeyId = "arn:aws:kms:eu-west-1:123:key/test",
      )
    try {
      KeyProviders.from(config)
      throw AssertionError("partial KMS configuration must not fall back to the local key")
    } catch (expected: IllegalArgumentException) {
      assertEquals("KMS configuration is incomplete; refusing to fall back to ENVELOPE_KEY_HEX", expected.message)
    }
  }

  @Test
  fun `environment configuration refuses a weak session secret`() {
    try {
      Config.fromEnv(env("SESSION_SECRET" to "too-short"))
      throw AssertionError("weak session secrets must be refused at boot")
    } catch (expected: IllegalArgumentException) {
      assertEquals("SESSION_SECRET must contain at least 32 characters", expected.message)
    }
  }

  @Test
  fun `partial provider credentials fail closed before boot`() {
    try {
      Config.fromEnv(env("GOOGLE_CLIENT_ID" to "client-id"))
      throw AssertionError("partial Google credentials must be refused")
    } catch (expected: IllegalArgumentException) {
      assertEquals("GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET must be configured together", expected.message)
    }

    try {
      Config.fromEnv(env("STRIPE_SECRET_KEY" to "sk_test_key"))
      throw AssertionError("partial Stripe credentials must be refused")
    } catch (expected: IllegalArgumentException) {
      assertEquals("STRIPE_SECRET_KEY, STRIPE_WEBHOOK_SECRET, and STRIPE_PRICE_ID must be configured together", expected.message)
    }
  }

  private fun env(vararg overrides: Pair<String, String>): Map<String, String> =
    mapOf(
      "DATABASE_URL" to "jdbc:postgresql://localhost/dailybrief",
      "DATABASE_USER" to "postgres",
      "DATABASE_PASSWORD" to "postgres",
      "SESSION_SECRET" to "test-session-secret-with-more-than-32-bytes",
    ) + overrides
}
