package com.example.server.billing

import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StripeBillingProviderTest {
  private val secret = "whsec-test"
  private val provider = StripeBillingProvider("sk-test", secret, "price-test")
  private val payload =
    """{"type":"checkout.session.completed","client_reference_id":"11111111-1111-1111-1111-111111111111"}"""

  @Test
  fun `accepts a fresh valid signature`() {
    val timestamp = System.currentTimeMillis() / 1_000L

    assertEquals(
      WebhookEvent.CheckoutCompleted("11111111-1111-1111-1111-111111111111"),
      provider.webhookEvent(payload, signature(timestamp)),
    )
  }

  @Test
  fun `rejects stale signatures`() {
    val timestamp = System.currentTimeMillis() / 1_000L - 301

    assertNull(provider.webhookEvent(payload, signature(timestamp)))
  }

  @Test
  fun `rejects malformed signatures without throwing`() {
    assertNull(provider.webhookEvent(payload, "t=not-a-number,v1"))
    assertNull(provider.webhookEvent(payload, "not-a-header"))
  }

  private fun signature(timestamp: Long): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    val digest = mac.doFinal("$timestamp.$payload".toByteArray())
    return "t=$timestamp,v1=${HexFormat.of().formatHex(digest)}"
  }
}
