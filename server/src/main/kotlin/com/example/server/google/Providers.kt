package com.example.server.google

import com.example.data.model.BriefingEvent
import com.example.server.Config
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Envelope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Calendar provider boundary: the fixture mode the journey test drives (no Google
 * credentials → deterministic rows), and the real Google Calendar API for the runbook. The
 * stored row id convention is the provider's own id — the same role `providerEventId` plays
 * on the device, so SyncMergePolicy's ambiguity rules behave identically.
 */
interface CalendarProvider {
  fun name(): String
  fun fetchEvents(startMs: Long, endMs: Long): List<BriefingEvent>
  fun providerIdOf(event: BriefingEvent): Long?
}

/** Deterministic, no network: used by the journey test and the fixture runbook leg. */
class FixtureCalendarProvider(
  private val connectionId: String,
  private val events: List<BriefingEvent> = defaultFixture(),
) : CalendarProvider {
  override fun name(): String = "Fixture Calendar"
  override fun fetchEvents(startMs: Long, endMs: Long): List<BriefingEvent> =
    events.filter { it.endTime > startMs && it.startTime < endMs }

  override fun providerIdOf(event: BriefingEvent): Long? {
    val prefix = "fixture_"
    return if (event.id.startsWith(prefix)) event.id.removePrefix(prefix).toLongOrNull() else null
  }

  companion object {
    /**
     * Day-relative to boot so the journey's "see it on Today" step lands on the real
     * calendar date: today 14:00-15:00 and tomorrow 09:00-10:30.
     */
    fun defaultFixture(nowMs: Long = System.currentTimeMillis()): List<BriefingEvent> {
      val today = nowMs - (nowMs % 86_400_000L)
      val hour = 3_600_000L
      return listOf(
        BriefingEvent(
          id = "fixture_1",
          title = "Client stand-up",
          startTime = today + 14 * hour,
          endTime = today + 15 * hour,
          source = "Fixture Calendar",
          description = null,
          isDeadline = false,
          isUrgent = false,
          isAllDay = false,
          location = null,
          kanbanStatus = "To Do",
          kanbanBoard = "Default",
          userEdited = false,
        ),
        BriefingEvent(
          id = "fixture_2",
          title = "Focus writing",
          startTime = today + 33 * hour,
          endTime = today + 34 * hour + 30 * 60_000L,
          source = "Fixture Calendar",
          description = null,
          isDeadline = false,
          isUrgent = false,
          isAllDay = false,
          location = null,
          kanbanStatus = "To Do",
          kanbanBoard = "Default",
          userEdited = false,
        ),
      )
    }
  }
}

/**
 * Google Calendar API. OAuth lives in the web client's consent flow; this side only ever
 * holds server-side tokens, stored envelope-encrypted in `calendar_connections`.
 */
class GoogleCalendarProvider(
  private val config: Config,
  private val connectionId: String,
  private val accessToken: String,
) : CalendarProvider {
  private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  override fun name(): String = "Google Calendar"

  override fun fetchEvents(startMs: Long, endMs: Long): List<BriefingEvent> {
    val calendarId = config.googleCalendarId ?: "primary"
    val url =
      "https://www.googleapis.com/calendar/v3/calendars/${calendarId.encodeURL()}/events" +
        "?timeMin=${iso(startMs)}&timeMax=${iso(endMs)}&singleEvents=true&orderBy=startTime"
    val request =
      Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("calendar fetch failed: ${response.code}")
      val body = response.body?.string() ?: return emptyList()
      val parsed = json.decodeFromString<CalendarList>(body)
      return parsed.items.map { it.toEvent() }
    }
  }

  override fun providerIdOf(event: BriefingEvent): Long? = null

  private fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()

  private fun CalendarEvent.toEvent(): BriefingEvent {
    val startValue = start?.dateTime ?: start?.date ?: ""
    val endValue = end?.dateTime ?: end?.date ?: startValue
    val allDay = start?.date != null
    val startMs = java.time.Instant.parse(startValue).toEpochMilli()
    val endMs = java.time.Instant.parse(endValue).toEpochMilli()
    return BriefingEvent(
      id = "google_${id}",
      title = summary ?: "(no title)",
      startTime = startMs,
      endTime = endMs,
      source = name(),
      description = description,
      isDeadline = false,
      isUrgent = false,
      isAllDay = allDay,
      location = location,
      kanbanStatus = "To Do",
      kanbanBoard = "Default",
      userEdited = false,
    )
  }

  @Serializable
  private data class CalendarList(val items: List<CalendarEvent> = emptyList())

  @Serializable
  private data class CalendarEvent(
    val id: String,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val start: EventTime? = null,
    val end: EventTime? = null,
  )

  @Serializable
  private data class EventTime(val dateTime: String? = null, val date: String? = null)
}

private fun String.encodeURL(): String =
  java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

object GoogleOAuth {
  private val json = Json { ignoreUnknownKeys = true }
  private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()

  @Serializable
  data class TokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int,
    val refresh_token: String? = null,
  )

  /** Exchanges the web client's one-time code for a server-side token pair. */
  fun exchangeCode(config: Config, code: String, redirectUri: String): TokenResponse {
    val body =
      "code=${code.encodeURL()}" +
        "&client_id=${config.googleClientId!!.encodeURL()}" +
        "&client_secret=${config.googleClientSecret!!.encodeURL()}" +
        "&redirect_uri=${redirectUri.encodeURL()}" +
        "&grant_type=authorization_code"
    val request =
      Request.Builder()
        .url("https://oauth2.googleapis.com/token")
        .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("token exchange failed: ${response.code}")
      return json.decodeFromString(response.body!!.string())
    }
  }

  fun storeToken(config: Config, workspaceId: String, provider: String, account: String, tokenJson: String) {
    val connectionId = java.util.UUID.randomUUID().toString()
    val envelope = Envelope.wrap(config.envelopeKeyHex, workspaceId, "calendar_connection_$connectionId", tokenJson)
    Db.inTransaction { conn ->
      conn.execute(
        "INSERT INTO calendar_connections (id, workspace_id, provider, external_account, token_ciphertext, token_kms_key_id, status, created_at, updated_at) " +
          "VALUES (?, ?, ?, ?, ?, ?, 'connected', ?, ?)",
        listOf(connectionId, workspaceId, provider, account, envelope, "dev-key", System.currentTimeMillis(), System.currentTimeMillis()),
      )
    }
  }
}