package com.example.server.push

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * The push seam: the server holds the device registry (push_tokens) and delivers through a
 * provider. [FixturePushProvider] records deliveries in memory so the journey can assert the
 * payload and the tenant isolation, [NotConfiguredPushProvider] stays silent, and
 * [ExpoPushProvider] is the real path — the Expo push API over plain REST, no SDK — so a
 * deployment with an EXPO_ACCESS_TOKEN set can actually ring a phone.
 */
data class PushPayload(
  val title: String,
  val body: String,
  val data: Map<String, String> = emptyMap(),
)

interface PushProvider {
  val configured: Boolean

  /** Must be bounded: a push failure never fails the apply that triggered it. */
  fun send(token: String, payload: PushPayload)
}

class NotConfiguredPushProvider : PushProvider {
  override val configured = false

  override fun send(token: String, payload: PushPayload) {
    // Silent by design: without a provider there is nobody to notify, and the apply that
    // triggered this must not care.
  }
}

/** Records deliveries in memory — the journey reads them through /v1/fixture/push-deliveries. */
class FixturePushProvider : PushProvider {
  data class Delivery(
    val token: String,
    val title: String,
    val body: String,
    val data: Map<String, String>,
  )

  private val delivered = java.util.concurrent.CopyOnWriteArrayList<Delivery>()

  override val configured = true

  override fun send(token: String, payload: PushPayload) {
    delivered += Delivery(token, payload.title, payload.body, payload.data)
  }

  fun deliveriesFor(token: String?): List<Delivery> =
    if (token == null) delivered.toList() else delivered.filter { it.token == token }
}

/** The real seam: the Expo push service (https://exp.host/--/api/v2/push/send). Plain REST,
 *  bounded timeouts; the access token is optional and only sent when configured. */
class ExpoPushProvider(private val accessToken: String?) : PushProvider {
  override val configured = true

  override fun send(token: String, payload: PushPayload) {
    val body =
      """[{"to":${JsonPrimitive(token)},"title":${JsonPrimitive(payload.title)},"body":${JsonPrimitive(payload.body)},"data":${Json.encodeToString(payload.data)}}]"""
    val connection = URL("https://exp.host/--/api/v2/push/send").openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Content-Type", "application/json")
    if (accessToken != null) connection.setRequestProperty("Authorization", "Bearer $accessToken")
    connection.doOutput = true
    try {
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      if (code != 200) error("Expo push failed with HTTP $code")
      val response = BufferedReader(connection.inputStream.reader()).use { it.readText() }
      // The response reports per-recipient receipts; a data error on this token is a real
      // failure (e.g. an uninstalled app), but it must not throw out of apply — callers wrap.
      if (response.contains("\"isError\":true")) error("Expo push reported an error for the token")
    } finally {
      connection.disconnect()
    }
  }
}