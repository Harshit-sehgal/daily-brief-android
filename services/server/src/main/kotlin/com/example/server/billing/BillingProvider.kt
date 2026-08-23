package com.example.server.billing

/**
 * Where checkouts come from and webhooks land. The slice is verifiable without Stripe keys:
 * [FixtureBillingProvider] runs the journey's boundary end to end, [NotConfiguredBillingProvider]
 * answers honestly when no keys are set, and [StripeBillingProvider] is the real seam — a plain
 * REST client (no SDK dependency) that only activates when `STRIPE_SECRET_KEY` is present, and
 * fails loudly in the journey's absence rather than pretending.
 */
interface BillingProvider {
  /** A checkout URL for this workspace, or null when this deployment cannot bill. */
  fun checkoutUrl(workspaceId: String, tier: String): String?

  /**
   * Turn a webhook request into an upgrade event, or null when the payload is not ours.
   * Signature verification is the provider's job: the fixture checks a shared secret, Stripe
   * checks the HMAC with the webhook secret.
   */
  fun webhookEvent(payload: String, signature: String?): WebhookEvent?
}

sealed interface WebhookEvent {
  /** A checkout completed for this workspace, when the payload named one. */
  data class CheckoutCompleted(val workspaceId: String?) : WebhookEvent
  data object Ignored : WebhookEvent
}

class NotConfiguredBillingProvider : BillingProvider {
  override fun checkoutUrl(workspaceId: String, tier: String): String? = null
  override fun webhookEvent(payload: String, signature: String?): WebhookEvent? = null
}

class FixtureBillingProvider(
  private val secret: String = "fixture-billing-secret",
  private val baseUrl: String = "https://checkout.example/daily-brief",
) : BillingProvider {
  override fun checkoutUrl(workspaceId: String, tier: String): String? =
    "$baseUrl/$tier?workspace=$workspaceId"

  override fun webhookEvent(payload: String, signature: String?): WebhookEvent? {
    if (signature != secret) return null
    if (!payload.contains("checkout.completed")) return WebhookEvent.Ignored
    val workspaceId =
      Regex("\"client_reference_id\":\"([0-9a-fA-F-]+)\"|workspace=([0-9a-fA-F-]+)")
        .find(payload)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull { it.isNotEmpty() }
    return WebhookEvent.CheckoutCompleted(workspaceId)
  }
}

/**
 * The real provider: Stripe's REST API via HttpURLConnection, so the server gains no SDK
 * dependency. Checkout sessions are created form-encoded with Basic auth; webhooks are
 * verified with Stripe's `t=`/`v1=` signature scheme (HMAC-SHA256 over the payload).
 */
class StripeBillingProvider(
  private val secretKey: String,
  private val webhookSecret: String,
  private val priceId: String,
) : BillingProvider {
  override fun checkoutUrl(workspaceId: String, tier: String): String? {
    val body =
      "mode=subscription&success_url=$SUCCESS_URL&cancel_url=$CANCEL_URL" +
        "&client_reference_id=$workspaceId&line_items[0][price]=$priceId&line_items[0][quantity]=1"
    val response = post("/v1/checkout/sessions", body)
    val url = response?.let { extractUrl(it) }
    if (response != null && url == null) {
      throw IllegalStateException("Stripe checkout response had no url: ${response.take(200)}")
    }
    return url
  }

  override fun webhookEvent(payload: String, signature: String?): WebhookEvent? {
    if (signature == null || !verifySignature(payload, signature)) return null
    if (!payload.contains("\"type\":\"checkout.session.completed\"")) return WebhookEvent.Ignored
    val workspaceId =
      Regex("\"client_reference_id\":\"([0-9a-fA-F-]+)\"").find(payload)?.groupValues?.get(1)
    return WebhookEvent.CheckoutCompleted(workspaceId)
  }

  private fun post(path: String, body: String): String? {
    val connection = java.net.URI("https://api.stripe.com$path").toURL().openConnection() as java.net.HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.connectTimeout = 10_000
      connection.readTimeout = 30_000
      connection.setRequestProperty("Authorization", "Basic ${java.util.Base64.getEncoder().encodeToString("$secretKey:".toByteArray())}")
      connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      connection.outputStream.use { it.write(body.toByteArray()) }
      val code = connection.responseCode
      if (code !in 200..299) return null
      return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
      connection.disconnect()
    }
  }

  private fun verifySignature(payload: String, signature: String): Boolean {
    val parts =
      signature.split(",").mapNotNull { part ->
        val pieces = part.split("=", limit = 2)
        if (pieces.size == 2 && pieces[0].isNotBlank() && pieces[1].isNotBlank()) pieces[0] to pieces[1] else null
      }
    val timestamp = parts.firstOrNull { it.first == "t" }?.second?.toLongOrNull() ?: return false
    val expected = parts.filter { it.first == "v1" }.map { it.second }
    if (expected.isEmpty()) return false
    val now = System.currentTimeMillis() / 1_000L
    if (kotlin.math.abs(now - timestamp) > MAX_SIGNATURE_AGE_SECONDS) return false
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256"))
    val digest = mac.doFinal("$timestamp.$payload".toByteArray())
    val computed = java.util.HexFormat.of().formatHex(digest).toByteArray(Charsets.US_ASCII)
    return expected.any { candidate ->
      java.security.MessageDigest.isEqual(computed, candidate.toByteArray(Charsets.US_ASCII))
    }
  }

  private fun extractUrl(response: String): String? {
    val marker = "\"url\":\""
    val start = response.indexOf(marker)
    if (start < 0) return null
    val end = response.indexOf('"', start + marker.length)
    if (end < 0) return null
    return response.substring(start + marker.length, end).replace("\\/", "/")
  }

  companion object {
    private const val MAX_SIGNATURE_AGE_SECONDS = 300L
    private const val SUCCESS_URL = "https://dailybrief.dev/billing/success"
    private const val CANCEL_URL = "https://dailybrief.dev/billing/cancel"
  }
}
