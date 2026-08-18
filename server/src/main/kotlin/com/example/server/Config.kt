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
  /** Dev key filling the KMS envelope's place (32 bytes, hex). KMS replaces it in production. */
  val envelopeKeyHex: String? = null,
  /** KMS wrapping authority (09-server-invariants §3): wins over the dev key when configured. */
  val kmsKeyId: String? = null,
  val kmsRegion: String? = null,
  val kmsAccessKeyId: String? = null,
  val kmsSecretAccessKey: String? = null,
  val kmsSessionToken: String? = null,
  val googleClientId: String? = null,
  val googleClientSecret: String? = null,
  val googleCalendarId: String? = null,
  val fixtureProvider: Boolean = false,
  /** The web client's origin; CORS admits exactly this host. */
  val webOrigin: String = "http://localhost:3000",
  /** Stage 4.5: billing activates only when Stripe keys are present. */
  val stripeSecretKey: String? = null,
  val stripeWebhookSecret: String? = null,
  val stripePriceId: String? = null,
  /** The daily brief's platform key (invariant 4): the server holds it, tenants never see it. */
  val geminiApiKey: String? = null,
  val geminiModel: String = "gemini-2.0-flash",
  /** Push: the Expo service's access token; absent means a deployment never rings a phone. */
  val expoAccessToken: String? = null,
) {
  companion object {
    fun fromEnv(env: Map<String, String> = System.getenv()): Config =
      Config(
        port = env["PORT"]?.toIntOrNull() ?: 8080,
        databaseUrl = requireEnv(env, "DATABASE_URL"),
        databaseUser = requireEnv(env, "DATABASE_USER"),
        databasePassword = requireEnv(env, "DATABASE_PASSWORD"),
        sessionSecret = requireEnv(env, "SESSION_SECRET"),
        envelopeKeyHex = env["ENVELOPE_KEY_HEX"],
        kmsKeyId = env["AWS_KMS_KEY_ID"],
        kmsRegion = env["AWS_KMS_REGION"],
        kmsAccessKeyId = env["AWS_ACCESS_KEY_ID"],
        kmsSecretAccessKey = env["AWS_SECRET_ACCESS_KEY"],
        kmsSessionToken = env["AWS_SESSION_TOKEN"],
        googleClientId = env["GOOGLE_CLIENT_ID"],
        googleClientSecret = env["GOOGLE_CLIENT_SECRET"],
        googleCalendarId = env["GOOGLE_CALENDAR_ID"],
        fixtureProvider = env["FIXTURE_PROVIDER"] == "1",
        stripeSecretKey = env["STRIPE_SECRET_KEY"],
        stripeWebhookSecret = env["STRIPE_WEBHOOK_SECRET"],
        stripePriceId = env["STRIPE_PRICE_ID"],
        geminiApiKey = env["GEMINI_API_KEY"],
        geminiModel = env["GEMINI_MODEL"] ?: "gemini-2.0-flash",
        expoAccessToken = env["EXPO_ACCESS_TOKEN"],
      )

    private fun requireEnv(env: Map<String, String>, name: String): String =
      env[name] ?: error("Missing required environment variable $name")
  }
}