package com.example.server

import com.example.server.db.Envelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class EnvelopeTest {
  private val key = hex(SecureRandom().generateSeed(32))
  private val otherKey = hex(SecureRandom().generateSeed(32))

  @Test
  fun `wrap then unwrap returns the plaintext`() {
    val envelope = Envelope.wrap(key, "ws-1", "gemini_key", "my-secret")
    assertEquals("my-secret", Envelope.unwrap(key, "ws-1", "gemini_key", envelope))
  }

  @Test
  fun `the wrong workspace cannot open an envelope`() {
    val envelope = Envelope.wrap(key, "ws-1", "gemini_key", "my-secret")
    assertNull(Envelope.unwrap(key, "ws-2", "gemini_key", envelope))
  }

  @Test
  fun `the wrong setting key cannot open an envelope`() {
    val envelope = Envelope.wrap(key, "ws-1", "gemini_key", "my-secret")
    assertNull(Envelope.unwrap(key, "ws-1", "notion_token", envelope))
  }

  @Test
  fun `the wrong master key cannot open an envelope`() {
    val envelope = Envelope.wrap(key, "ws-1", "gemini_key", "my-secret")
    assertNull(Envelope.unwrap(otherKey, "ws-1", "gemini_key", envelope))
  }

  @Test
  fun `each write gets a fresh data key`() {
    assertNotEquals(Envelope.wrap(key, "ws-1", "k", "same"), Envelope.wrap(key, "ws-1", "k", "same"))
  }

  @Test
  fun `a failed read never destroys the envelope`() {
    val envelope = Envelope.wrap(key, "ws-1", "gemini_key", "my-secret")
    assertNull(Envelope.unwrap(otherKey, "ws-1", "gemini_key", envelope))
    // The stored envelope is untouched and still opens with the right key.
    assertEquals("my-secret", Envelope.unwrap(key, "ws-1", "gemini_key", envelope))
  }

  private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}