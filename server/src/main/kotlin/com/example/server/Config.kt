package com.example.server

import kotlinx.serialization.Serializable

/**
 * Everything the service needs from the environment. The slice's stance: fail fast at boot
 * with a named variable, never a silent default that changes behaviour between machines.
 * [fixtureMode] is the journey test's switch — no Google credentials means a fixture provider
 * and an auto-signed-in session, so the whole loop runs headless against docker Postgres.
 */
@Serializable
data class Config(
  val port: Int = 8080,
  val databaseUrl: String,
  val databaseUser: String,
  val databasePassword: String,
  val sessionSecret: String,
  /** Dev key filling the KMS envelope's place (32 bytes, hex). Production swaps this for KMS. */
  val envelopeKeyHex: String,
  val googleClientId: String? = null,
  val googleClientSecret: String? = null,
  val googleCalendarId: String? = null,
  val fixtureProvider: Boolean = false,
  /** The web client's origin; CORS admits exactly this host. */
  val webOrigin: String = "http://localhost:3000",
) {
  companion object {
    fun fromEnv(env: Map<String, String> = System.getenv()): Config =
      Config(
        port = env["PORT"]?.toIntOrNull() ?: 8080,
        databaseUrl = requireEnv(env, "DATABASE_URL"),
        databaseUser = requireEnv(env, "DATABASE_USER"),
        databasePassword = requireEnv(env, "DATABASE_PASSWORD"),
        sessionSecret = requireEnv(env, "SESSION_SECRET"),
        envelopeKeyHex = requireEnv(env, "ENVELOPE_KEY_HEX"),
        googleClientId = env["GOOGLE_CLIENT_ID"],
        googleClientSecret = env["GOOGLE_CLIENT_SECRET"],
        googleCalendarId = env["GOOGLE_CALENDAR_ID"],
        fixtureProvider = env["FIXTURE_PROVIDER"] == "1",
      )

    private fun requireEnv(env: Map<String, String>, name: String): String =
      env[name] ?: error("Missing required environment variable $name")
  }
}