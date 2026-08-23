package com.example.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Stateless CSRF tokens for browser-cookie mutations; bearer clients do not need this header. */
internal class CsrfTokens(secret: String) {
  private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
  private val random = SecureRandom()

  fun issue(nowMs: Long = System.currentTimeMillis()): String {
    val nonce = ByteArray(24).also(random::nextBytes)
    val encodedNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
    val body = "$encodedNonce.${nowMs + MAX_AGE_MS}"
    return "$body.${sign(body)}"
  }

  fun verify(token: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (token.isNullOrBlank()) return false
    val parts = token.split('.', limit = 3)
    if (parts.size != 3) return false
    val (nonce, expiresAtText, givenSignature) = parts
    if (nonce.isBlank()) return false
    val expiresAt = expiresAtText.toLongOrNull() ?: return false
    if (expiresAt <= nowMs) return false
    val body = "$nonce.$expiresAtText"
    return MessageDigest.isEqual(sign(body).toByteArray(), givenSignature.toByteArray())
  }

  private fun sign(body: String): String =
    Mac.getInstance("HmacSHA256").run {
      init(key)
      doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

  private companion object {
    const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
  }
}
