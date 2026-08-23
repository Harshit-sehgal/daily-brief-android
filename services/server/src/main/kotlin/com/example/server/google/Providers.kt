package com.example.server.google

import com.example.data.model.BriefingEvent
import com.example.server.Config
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Envelope
import com.example.server.key.KeyProviders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneOffset
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
 * holds server-side tokens, stored envelope-encrypted in `calendar_connections`. An expired
 * access token (Google's live ~1 hour) is refreshed once through the OAuth token endpoint
 * before the fetch is retried, and the fresh pair is persisted back through the envelope —
 * so a long-running demo never needs a re-consent. [baseUrl] and the refresh callbacks are
 * injectable so the retry contract is unit-tested against a local HTTP server.
 */
class GoogleCalendarProvider(
  private val config: Config,
  private val connectionId: String,
  accessToken: String,
  private val baseUrl: String = "https://www.googleapis.com",
  private val refresher: (() -> GoogleOAuth.TokenResponse)? = null,
  private val onTokensRefreshed: ((GoogleOAuth.TokenResponse) -> Unit)? = null,
) : CalendarProvider {
  private var currentAccessToken = accessToken
  private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  override fun name(): String = "Google Calendar"

  override fun fetchEvents(startMs: Long, endMs: Long): List<BriefingEvent> {
    return try {
      fetchAllPages(currentAccessToken, startMs, endMs)
    } catch (failure: CalendarProviderFailure) {
      if (failure.statusCode != 401 && failure.statusCode != 403) throw failure
      if (refresher == null) {
        throw CalendarProviderFailure(failure.statusCode, "calendar fetch failed: ${failure.statusCode}")
      }

      // The stored pair expired or was revoked. Refresh exactly once, persist, retry once.
      // A second 401/403 means the refresh itself was refused — propagate, never loop.
      val fresh = refresher()
      currentAccessToken = fresh.access_token
      onTokensRefreshed?.invoke(fresh)
      try {
        fetchAllPages(currentAccessToken, startMs, endMs)
      } catch (retried: CalendarProviderFailure) {
        if (retried.statusCode == 401 || retried.statusCode == 403) {
          throw CalendarProviderFailure(
            retried.statusCode,
            "calendar fetch failed after token refresh: ${retried.statusCode}",
          )
        }
        throw retried
      }
    }
  }

  private fun fetchAllPages(token: String, startMs: Long, endMs: Long): List<BriefingEvent> {
    val events = mutableListOf<BriefingEvent>()
    var pageToken: String? = null
    do {
      val attempt = fetchWithToken(token, startMs, endMs, pageToken)
      if (attempt.code !in 200..299) {
        throw CalendarProviderFailure(attempt.code, "calendar fetch failed: ${attempt.code}")
      }
      val body = attempt.body ?: error("calendar fetch returned an empty response")
      val parsed = json.decodeFromString<CalendarList>(body)
      events += parsed.items.map { it.toEvent() }
      pageToken = parsed.nextPageToken?.takeIf { it.isNotBlank() }
    } while (pageToken != null)
    return events
  }

  private fun fetchWithToken(token: String, startMs: Long, endMs: Long, pageToken: String?): FetchAttempt {
    val calendarId = config.googleCalendarId ?: "primary"
    val url =
      "$baseUrl/calendar/v3/calendars/${calendarId.encodeURL()}/events" +
        "?timeMin=${iso(startMs)}&timeMax=${iso(endMs)}&singleEvents=true&orderBy=startTime" +
        (pageToken?.let { "&pageToken=${it.encodeURL()}" } ?: "")
    val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
    client.newCall(request).execute().use { response ->
      return FetchAttempt(response.code, response.body?.string())
    }
  }

  override fun providerIdOf(event: BriefingEvent): Long? = null

  private fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()

  private fun CalendarEvent.toEvent(): BriefingEvent {
    val startValue = start?.dateTime ?: start?.date ?: error("Google event $id has no start")
    val endValue = end?.dateTime ?: end?.date ?: startValue
    val allDay = start?.date != null
    val startMs = parseGoogleTime(startValue)
    val endMs = parseGoogleTime(endValue)
    require(endMs > startMs) { "Google event $id has an invalid time range" }
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

  /** Google represents all-day events as an inclusive start/exclusive end date. */
  private fun parseGoogleTime(value: String): Long =
    if (value.length == 10) {
      LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } else {
      java.time.Instant.parse(value).toEpochMilli()
    }

  @Serializable
  private data class CalendarList(
    val items: List<CalendarEvent> = emptyList(),
    val nextPageToken: String? = null,
  )

  private data class FetchAttempt(val code: Int, val body: String?)

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

/** A provider response with a status code, so the reconciliation worker can retry only
 * transient HTTP failures while failing closed on auth and validation responses. */
internal class CalendarProviderFailure(val statusCode: Int, message: String) : IllegalStateException(message)

private fun String.encodeURL(): String =
  java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

object GoogleOAuth {
  val json = Json { ignoreUnknownKeys = true }
  private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

  /** The web client's consent URL: identity + calendar scopes, with a server-bound PKCE challenge. */
  fun authorizeUrl(config: Config, redirectUri: String, state: String, codeChallenge: String): String {
    val base = "https://accounts.google.com/o/oauth2/v2/auth"
    val params =
      listOf(
        "client_id" to config.googleClientId!!,
        "redirect_uri" to redirectUri,
        "state" to state,
        "code_challenge" to codeChallenge,
        "code_challenge_method" to "S256",
        "response_type" to "code",
        "scope" to "openid email https://www.googleapis.com/auth/calendar.readonly",
        "access_type" to "offline",
        "prompt" to "consent",
      )
    return base + "?" + params.joinToString("&") { (k, v) -> "$k=${v.encodeURL()}" }
  }

  @Serializable
  data class TokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int,
    val refresh_token: String? = null,
  )

  @Serializable
  data class Identity(
    val sub: String,
    val email: String,
    val email_verified: Boolean = false,
    val name: String? = null,
  )

  /** Reads the verified Google identity instead of assigning every OAuth callback to one user. */
  fun fetchIdentity(
    accessToken: String,
    userInfoUrl: String = "https://openidconnect.googleapis.com/v1/userinfo",
  ): Identity {
    val request = Request.Builder().url(userInfoUrl).header("Authorization", "Bearer $accessToken").build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("Google identity lookup failed: ${response.code}")
      val identity = json.decodeFromString<Identity>(response.body?.string() ?: error("Google identity response was empty"))
      require(identity.sub.isNotBlank() && identity.email.isNotBlank() && identity.email_verified) {
        "Google identity is missing a verified email"
      }
      return identity
    }
  }

  /** Exchanges the web client's one-time code for a server-side token pair. */
  fun exchangeCode(config: Config, code: String, redirectUri: String, codeVerifier: String): TokenResponse {
    val body =
      "code=${code.encodeURL()}" +
        "&client_id=${config.googleClientId!!.encodeURL()}" +
        "&client_secret=${config.googleClientSecret!!.encodeURL()}" +
        "&redirect_uri=${redirectUri.encodeURL()}" +
        "&code_verifier=${codeVerifier.encodeURL()}" +
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
    val keyProvider = KeyProviders.from(config)
    val envelope = Envelope.wrap(keyProvider, workspaceId, "calendar_connection_$connectionId", tokenJson)
    Db.inTransaction { conn ->
      conn.execute(
        "INSERT INTO calendar_connections (id, workspace_id, provider, external_account, token_ciphertext, token_kms_key_id, status, created_at, updated_at) " +
          "VALUES (?, ?, ?, ?, ?, ?, 'connected', ?, ?)",
        listOf(connectionId, workspaceId, provider, account, envelope, keyProvider.keyId, System.currentTimeMillis(), System.currentTimeMillis()),
      )
    }
  }

  /** Exchanges a stored refresh token for a fresh access-token pair (grant_type=refresh_token). */
  fun refreshAccessToken(config: Config, refreshToken: String): TokenResponse {
    val body =
      "refresh_token=${refreshToken.encodeURL()}" +
        "&client_id=${config.googleClientId!!.encodeURL()}" +
        "&client_secret=${config.googleClientSecret!!.encodeURL()}" +
        "&grant_type=refresh_token"
    val request =
      Request.Builder()
        .url("https://oauth2.googleapis.com/token")
        .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("token refresh failed: ${response.code}")
      return json.decodeFromString(response.body!!.string())
    }
  }
}
