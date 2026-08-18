package com.example.server

import com.example.server.db.Envelope
import com.example.server.key.KeyProvider
import com.example.server.key.LocalKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class EnvelopeTest {
  private val key = hex(SecureRandom().generateSeed(32))
  private val otherKey = hex(SecureRandom().generateSeed(32))
  private val provider = LocalKeyProvider(key)
  private val otherProvider = LocalKeyProvider(otherKey)

  @Test
  fun `wrap then unwrap returns the plaintext`() {
    val envelope = Envelope.wrap(provider, "ws-1", "gemini_key", "my-secret")
    assertEquals("my-secret", Envelope.unwrap(provider, "ws-1", "gemini_key", envelope))
  }

  @Test
  fun `the wrong workspace cannot open an envelope`() {
    val envelope = Envelope.wrap(provider, "ws-1", "gemini_key", "my-secret")
    assertNull(Envelope.unwrap(provider, "ws-2", "gemini_key", envelope))
  }

  @Test
  fun `the wrong setting key cannot open an envelope`() {
    val envelope = Envelope.wrap(provider, "ws-1", "gemini_key", "my-secret")
    assertNull(Envelope.unwrap(provider, "ws-1", "notion_token", envelope))
  }

  @Test
  fun `the wrong master key cannot open an envelope`() {
    val envelope = Envelope.wrap(provider, "ws-1", "gemini_key", "my-secret")
    assertNull(Envelope.unwrap(otherProvider, "ws-1", "gemini_key", envelope))
  }

  @Test
  fun `each write gets a fresh data key`() {
    assertNotEquals(Envelope.wrap(provider, "ws-1", "k", "same"), Envelope.wrap(provider, "ws-1", "k", "same"))
  }

  @Test
  fun `a failed read never destroys the envelope`() {
    val envelope = Envelope.wrap(provider, "ws-1", "gemini_key", "my-secret")
    assertNull(Envelope.unwrap(otherProvider, "ws-1", "gemini_key", envelope))
    // The stored envelope is untouched and still opens with the right key.
    assertEquals("my-secret", Envelope.unwrap(provider, "ws-1", "gemini_key", envelope))
  }

  @Test
  fun `any provider that round-trips bytes opens the envelope - the shape is provider-agnostic`() {
    val fake = FakeKeyProvider("fake-key")
    val envelope = Envelope.wrap(fake, "ws-1", "k", "secret")
    assertEquals("secret", Envelope.unwrap(fake, "ws-1", "k", envelope))
  }

  private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  /** The envelope must not care WHO wraps the data key — that is the KMS seam's whole point. */
  private class FakeKeyProvider(override val keyId: String) : KeyProvider {
    override fun wrap(dataKey: ByteArray): ByteArray = dataKey.reversedArray()

    override fun unwrap(wrappedKey: ByteArray): ByteArray = wrappedKey.reversedArray()
  }
}