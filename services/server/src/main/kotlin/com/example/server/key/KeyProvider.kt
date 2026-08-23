package com.example.server.key

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The deployment key seam behind the envelope (09-server-invariants §3): the envelope stores
 * the wrapped data key, the nonce and the ciphertext; WHO wraps is a deployment choice.
 * [LocalKeyProvider] is the dev stand-in (the old master-hex wrap), [AwsKmsKeyProvider] is the
 * production path — KMS Encrypt/Decrypt over plain REST with SigV4, no SDK. The stored envelope
 * shape does not change between them, so rotation and migration stay out of the data path.
 */
interface KeyProvider {
  /** A name for logs and the envelope's key-id column; never a secret. */
  val keyId: String

  fun wrap(dataKey: ByteArray): ByteArray

  fun unwrap(wrappedKey: ByteArray): ByteArray
}

/** AES/ECB wrapping with the deployment master key — the documented dev stand-in. */
class LocalKeyProvider(private val masterKeyHex: String) : KeyProvider {
  override val keyId = "dev-key"

  override fun wrap(dataKey: ByteArray): ByteArray {
    val master = SecretKeySpec(hexToBytes(masterKeyHex), "AES")
    val c = Cipher.getInstance("AES/ECB/NoPadding")
    c.init(Cipher.WRAP_MODE, master)
    return c.wrap(SecretKeySpec(dataKey, "AES"))
  }

  override fun unwrap(wrappedKey: ByteArray): ByteArray {
    val master = SecretKeySpec(hexToBytes(masterKeyHex), "AES")
    val c = Cipher.getInstance("AES/ECB/NoPadding")
    c.init(Cipher.UNWRAP_MODE, master)
    return (c.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY) as SecretKey).encoded
  }

  private fun hexToBytes(hex: String): ByteArray =
    requireNotNull(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().takeIf { it.isNotEmpty() })
}

/**
 * KMS as the wrapping authority: Encrypt/Decrypt against the KMS key, SigV4-signed, with a
 * fixed encryption context (`daily-brief` envelope wrapping). The tenant-specific binding stays
 * where it belongs — the GCM AAD on the payload — so the KMS call is stateless across tenants.
 */
class AwsKmsKeyProvider(
  override val keyId: String,
  private val region: String,
  private val accessKeyId: String,
  private val secretAccessKey: String,
  private val sessionToken: String? = null,
) : KeyProvider {
  private val contextJson = """{"daily-brief":"envelope-v1"}"""

  override fun wrap(dataKey: ByteArray): ByteArray =
    kmsCall("TrentService.Encrypt", """{"KeyId":${JsonPrimitive(keyId)},"Plaintext":"${b64(dataKey)}","EncryptionContext":$contextJson}""")
      .jsonObject["CiphertextBlob"]
      ?.jsonPrimitive
      ?.let { Base64.getDecoder().decode(it.content) }
      ?: error("KMS Encrypt returned no CiphertextBlob")

  override fun unwrap(wrappedKey: ByteArray): ByteArray =
    kmsCall("TrentService.Decrypt", """{"CiphertextBlob":"${b64(wrappedKey)}","EncryptionContext":$contextJson}""")
      .jsonObject["Plaintext"]
      ?.jsonPrimitive
      ?.let { Base64.getDecoder().decode(it.content) }
      ?: error("KMS Decrypt returned no Plaintext")

  private fun kmsCall(target: String, body: String): JsonObject {
    val now = Instant.now().atZone(ZoneOffset.UTC)
    val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(now)
    val date = DateTimeFormatter.ofPattern("yyyyMMdd").format(now)
    val host = "kms.$region.amazonaws.com"
    val hashedPayload = SigV4.sha256Hex(body.toByteArray(Charsets.UTF_8))
    val headers =
      listOf(
        "content-type" to "application/x-amz-json-1.1",
        "host" to host,
        "x-amz-content-sha256" to hashedPayload,
        "x-amz-date" to amzDate,
        "x-amz-target" to target,
      )
    val scope = "$date/$region/kms/aws4_request"
    val canonical = SigV4.canonicalRequest("POST", "/", "", headers, hashedPayload)
    val signature = SigV4.hmacSha256Hex(SigV4.signingKey(secretAccessKey, date, region, "kms"), SigV4.stringToSign(amzDate, scope, SigV4.sha256Hex(canonical.toByteArray(Charsets.UTF_8))))
    val authorization = SigV4.authorizationHeader(accessKeyId, scope, headers.joinToString(";") { it.first }, signature)

    val connection = URL("https://$host/").openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Content-Type", "application/x-amz-json-1.1")
    connection.setRequestProperty("X-Amz-Date", amzDate)
    connection.setRequestProperty("X-Amz-Target", target)
    connection.setRequestProperty("X-Amz-Content-Sha256", hashedPayload)
    connection.setRequestProperty("Authorization", authorization)
    if (sessionToken != null) connection.setRequestProperty("X-Amz-Security-Token", sessionToken)
    connection.doOutput = true
    try {
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      if (code != 200) error("KMS $target failed with HTTP $code")
      val response = BufferedReader(connection.inputStream.reader()).use { it.readText() }
      return Json.parseToJsonElement(response).jsonObject
    } finally {
      connection.disconnect()
    }
  }

  private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/**
 * One deployment, one wrapping authority. KMS wins when it is configured; the dev master hex
 * is the fallback, and its absence is an explicit boot error rather than a silent default.
 */
object KeyProviders {
  fun from(config: com.example.server.Config): KeyProvider {
    val kmsValues =
      listOf(config.kmsKeyId, config.kmsRegion, config.kmsAccessKeyId, config.kmsSecretAccessKey)
    if (kmsValues.any { it != null }) {
      require(kmsValues.all { !it.isNullOrBlank() }) {
        "KMS configuration is incomplete; refusing to fall back to ENVELOPE_KEY_HEX"
      }
      return AwsKmsKeyProvider(
        keyId = requireNotNull(config.kmsKeyId),
        region = requireNotNull(config.kmsRegion),
        accessKeyId = requireNotNull(config.kmsAccessKeyId),
        secretAccessKey = requireNotNull(config.kmsSecretAccessKey),
        sessionToken = config.kmsSessionToken,
      )
    } else {
      return LocalKeyProvider(requireNotNull(config.envelopeKeyHex) { "ENVELOPE_KEY_HEX is required when KMS is not configured" })
    }
  }
}
