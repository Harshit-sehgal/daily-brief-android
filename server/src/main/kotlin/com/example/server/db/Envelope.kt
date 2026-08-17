package com.example.server.db

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The WP-11 envelope shape (docs/saas/09-server-invariants.md §3), with the deployment KMS
 * key standing in for the per-deployment master key: a fresh AES-GCM data key per write,
 * wrapped by the master key, AAD bound to `workspace_id + ":" + setting_key`. Production
 * swaps the unwrap step for a KMS call — the stored envelope does not change, so rotation
 * and migration stay out of the data path. A failed read returns null and never touches the
 * stored envelope, so a transient key outage cannot erase a token.
 */
object Envelope {
  private const val GCM_TAG_BITS = 128
  private const val NONCE_BYTES = 12

  fun wrap(masterKeyHex: String, workspaceId: String, settingKey: String, plaintext: String): String {
    val master = SecretKeySpec(hexToBytes(masterKeyHex), "AES")
    val dataKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val wrapped = wrapKey(master, dataKey)
    val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
    cipher.updateAAD(aad(workspaceId, settingKey))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return "v1:${bytesToHex(wrapped)}:${bytesToHex(nonce)}:${bytesToHex(ciphertext)}"
  }

  fun unwrap(masterKeyHex: String, workspaceId: String, settingKey: String, envelope: String): String? =
    runCatching {
      val parts = envelope.split(":")
      require(parts.size == 4 && parts[0] == "v1") { "unrecognised envelope shape" }
      val master = SecretKeySpec(hexToBytes(masterKeyHex), "AES")
      val dataKey = unwrapKey(master, hexToBytes(parts[1]))
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(GCM_TAG_BITS, hexToBytes(parts[2])))
      cipher.updateAAD(aad(workspaceId, settingKey))
      String(cipher.doFinal(hexToBytes(parts[3])), Charsets.UTF_8)
    }.getOrNull()

  private fun aad(workspaceId: String, settingKey: String): ByteArray = "$workspaceId:$settingKey".toByteArray()

  // GCM does not support WRAP mode; raw ECB wrapping of the data key is the dev stand-in.
  // Production KMS does the wrapping with its own key types, and the stored envelope shape
  // (wrapped bytes + nonce + ciphertext) does not change.
  private fun wrapKey(master: SecretKey, key: SecretKey): ByteArray {
    val c = Cipher.getInstance("AES/ECB/NoPadding")
    c.init(Cipher.WRAP_MODE, master)
    return c.wrap(key)
  }

  private fun unwrapKey(master: SecretKey, wrapped: ByteArray): SecretKey {
    val c = Cipher.getInstance("AES/ECB/NoPadding")
    c.init(Cipher.UNWRAP_MODE, master)
    return c.unwrap(wrapped, "AES", Cipher.SECRET_KEY) as SecretKey
  }

  private fun hexToBytes(hex: String): ByteArray =
    requireNotNull(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().takeIf { it.isNotEmpty() })

  private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}