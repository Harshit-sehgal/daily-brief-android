package com.example.server.key

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4, hand-rolled so the KMS seam needs no SDK — the same stance as the
 * Stripe and Gemini REST seams. Pure: every step is deterministic, so the documented AWS test
 * vector pins it byte-for-byte (SigV4Test).
 */
object SigV4 {
  private const val ALGORITHM = "AWS4-HMAC-SHA256"

  /** kDate → kRegion → kService → kSigning, exactly the docs' derivation chain. */
  fun signingKey(secretAccessKey: String, date: String, region: String, service: String): ByteArray {
    val kDate = hmac(("AWS4$secretAccessKey").toByteArray(), date)
    val kRegion = hmac(kDate, region)
    val kService = hmac(kRegion, service)
    return hmac(kService, "aws4_request")
  }

  fun canonicalRequest(
    method: String,
    canonicalUri: String,
    canonicalQuery: String,
    canonicalHeaders: List<Pair<String, String>>,
    hashedPayload: String,
  ): String {
    val headerLines = canonicalHeaders.joinToString("\n") { (name, value) -> "$name:$value" }
    val signedHeaders = canonicalHeaders.joinToString(";") { it.first }
    return "$method\n$canonicalUri\n$canonicalQuery\n$headerLines\n\n$signedHeaders\n$hashedPayload"
  }

  fun stringToSign(amzDate: String, scope: String, hashedCanonicalRequest: String): String =
    "$ALGORITHM\n$amzDate\n$scope\n$hashedCanonicalRequest"

  fun authorizationHeader(
    accessKeyId: String,
    scope: String,
    signedHeaders: String,
    signatureHex: String,
  ): String = "$ALGORITHM Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signatureHex"

  fun hmacSha256Hex(key: ByteArray, data: String): String = bytesToHex(hmac(key, data))

  fun sha256Hex(data: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return bytesToHex(digest.digest(data))
  }

  fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  private fun hmac(key: ByteArray, data: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8))
  }
}