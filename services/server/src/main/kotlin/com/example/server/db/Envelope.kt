package com.example.server.db

import com.example.server.key.KeyProvider
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The WP-11 envelope shape (docs/saas/09-server-invariants.md §3): a fresh AES-GCM data key
 * per write, wrapped by the deployment key provider, AAD bound to `workspace_id + ":" +
 * setting_key`. Who wraps is a deployment choice (KeyProvider): the dev master hex locally, or
 * KMS in production — the stored envelope does not change between them, so rotation and
 * migration stay out of the data path. A failed read returns null and never touches the stored
 * envelope, so a transient key outage cannot erase a token.
 */
object Envelope {
  private const val GCM_TAG_BITS = 128
  private const val NONCE_BYTES = 12

  fun wrap(provider: KeyProvider, workspaceId: String, settingKey: String, plaintext: String): String {
    val dataKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val wrapped = provider.wrap(dataKey.encoded)
    val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
    cipher.updateAAD(aad(workspaceId, settingKey))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return "v1:${bytesToHex(wrapped)}:${bytesToHex(nonce)}:${bytesToHex(ciphertext)}"
  }

  fun unwrap(provider: KeyProvider, workspaceId: String, settingKey: String, envelope: String): String? =
    runCatching {
      val parts = envelope.split(":")
      require(parts.size == 4 && parts[0] == "v1") { "unrecognised envelope shape" }
      val dataKey = provider.unwrap(hexToBytes(parts[1]))
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, hexToBytes(parts[2])))
      cipher.updateAAD(aad(workspaceId, settingKey))
      String(cipher.doFinal(hexToBytes(parts[3])), Charsets.UTF_8)
    }.getOrNull()

  private fun aad(workspaceId: String, settingKey: String): ByteArray = "$workspaceId:$settingKey".toByteArray()

  private fun hexToBytes(hex: String): ByteArray =
    requireNotNull(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().takeIf { it.isNotEmpty() })

  private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}