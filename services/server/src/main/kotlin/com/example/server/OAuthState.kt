package com.example.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Redirects accepted by the OAuth clients in this repository. */
internal object OAuthRedirects {
  private const val MOBILE_CALLBACK = "mobile://auth/callback"

  fun webCallback(config: Config): String = config.webOrigin.trimEnd('/') + "/auth/callback"

  fun validate(config: Config, redirectUri: String): String {
    require(redirectUri == webCallback(config) || redirectUri == MOBILE_CALLBACK) {
      "redirect URI is not registered"
    }
    return redirectUri
  }
}

/** A short-lived signed state value binds a callback to the redirect URI chosen at start. */
internal class OAuthState(private val secret: String) {
  private val random = SecureRandom()

  fun issue(redirectUri: String, nowMs: Long = System.currentTimeMillis()): String {
    val expiresAt = nowMs + MAX_AGE_MS
    val nonce = ByteArray(18).also(random::nextBytes)
    val body = "$redirectUri|$expiresAt|${Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)}"
    val encodedBody = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())
    return "$encodedBody.${signature(encodedBody)}"
  }

  fun verify(state: String?, redirectUri: String, nowMs: Long = System.currentTimeMillis()): Boolean {
    return parse(state, redirectUri)?.let { (_, expiresAt, _) -> nowMs <= expiresAt } == true
  }

  /** Derives the PKCE verifier from the signed nonce; the verifier never travels in the URL. */
  fun codeVerifier(state: String?): String? =
    parse(state, expectedRedirect = null)?.let { (_, _, nonce) ->
      Base64.getUrlEncoder().withoutPadding().encodeToString(hmac("pkce|$nonce"))
    }

  fun codeChallenge(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

  private fun parse(state: String?, expectedRedirect: String?): Triple<String, Long, String>? {
    if (state.isNullOrBlank()) return null
    val parts = state.split('.', limit = 2)
    if (parts.size != 2) return null
    val (encodedBody, givenSignature) = parts
    if (!MessageDigest.isEqual(signature(encodedBody).toByteArray(), givenSignature.toByteArray())) return null
    val body = runCatching { String(Base64.getUrlDecoder().decode(encodedBody)) }.getOrNull() ?: return null
    val fields = body.split('|')
    if (fields.size != 3 || (expectedRedirect != null && fields[0] != expectedRedirect) || fields[2].isBlank()) return null
    val expiresAt = fields[1].toLongOrNull() ?: return null
    return Triple(fields[0], expiresAt, fields[2])
  }

  private fun signature(encodedBody: String): String =
    hmac(encodedBody).joinToString("") { "%02x".format(it) }

  private fun hmac(value: String): ByteArray =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
      doFinal(value.toByteArray())
    }

  private companion object {
    const val MAX_AGE_MS = 10 * 60 * 1000L
  }
}
