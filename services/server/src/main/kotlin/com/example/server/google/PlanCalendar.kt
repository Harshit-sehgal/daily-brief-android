package com.example.server.google

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One block of planned work as it appears in the reader's Google Calendar. */
data class PlannedEvent(val id: String, val title: String, val startMs: Long, val endMs: Long)

/**
 * What must change in the plan calendar so it shows exactly the plan. Pure, so the rule
 * is pinned by test: an event's id is derived from the block, its times and its title,
 * which makes a moved or renamed block a different event (the old one is deleted, the
 * new one inserted) and makes writing the same plan twice a no-op.
 */
object PlanCalendarDiff {
  data class Changes(val insert: List<PlannedEvent>, val delete: List<String>)

  /** Google accepts client-chosen ids of base32hex characters; lowercase hex is a subset. */
  fun eventId(blockId: String, startMs: Long, endMs: Long, title: String): String =
    "db" + MessageDigest.getInstance("SHA-1").digest("$blockId|$startMs|$endMs|$title".toByteArray())
      .joinToString("") { "%02x".format(it) }

  fun changes(desired: List<PlannedEvent>, existingIds: Set<String>): Changes {
    val wanted = desired.associateBy { it.id }
    return Changes(
      insert = wanted.values.filter { it.id !in existingIds },
      delete = existingIds.filter { it !in wanted },
    )
  }
}

internal class PlanCalendarFailure(val statusCode: Int, message: String) : IllegalStateException(message)

/** The calendar the plan is written to. */
interface PlanCalendar {
  fun create(timeZone: String): String

  /** Ids of the events in the window, or null when the calendar no longer exists. */
  fun eventIds(calendarId: String, fromMs: Long, toMs: Long): Set<String>?

  fun insert(calendarId: String, event: PlannedEvent)

  fun delete(calendarId: String, eventId: String)
}

/**
 * Google Calendar under `calendar.app.created`: the notebook may create secondary
 * calendars and manage events on those alone. It cannot read or change any calendar
 * the reader made themselves.
 */
class GooglePlanCalendar(
  private val accessToken: String,
  private val baseUrl: String = "https://www.googleapis.com",
) : PlanCalendar {
  private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  @Serializable
  private data class Id(val id: String)

  @Serializable
  private data class EventPage(val items: List<Id> = emptyList(), val nextPageToken: String? = null)

  override fun create(timeZone: String): String {
    val body = buildJsonObject {
      put("summary", CALENDAR_NAME)
      put("description", "Your planned work, written here by Daily Brief. Changes made here are not read back.")
      put("timeZone", timeZone)
    }
    val (code, text) = send("POST", "$baseUrl/calendar/v3/calendars", body.toString())
    if (code !in 200..299) throw PlanCalendarFailure(code, "creating the plan calendar failed: $code")
    return json.decodeFromString<Id>(text).id
  }

  override fun eventIds(calendarId: String, fromMs: Long, toMs: Long): Set<String>? {
    val ids = mutableSetOf<String>()
    var pageToken: String? = null
    do {
      val url =
        "$baseUrl/calendar/v3/calendars/${calendarId.enc()}/events?timeMin=${iso(fromMs)}&timeMax=${iso(toMs)}" +
          "&showDeleted=false&maxResults=250&fields=items(id),nextPageToken" + (pageToken?.let { "&pageToken=${it.enc()}" } ?: "")
      val (code, text) = send("GET", url, null)
      if (code == 404 || code == 410) return null
      if (code !in 200..299) throw PlanCalendarFailure(code, "reading the plan calendar failed: $code")
      val page = json.decodeFromString<EventPage>(text)
      ids += page.items.map { it.id }
      pageToken = page.nextPageToken?.takeIf { it.isNotBlank() }
    } while (pageToken != null)
    return ids
  }

  override fun insert(calendarId: String, event: PlannedEvent) {
    val body = buildJsonObject {
      put("id", event.id)
      put("summary", event.title)
      put("status", "confirmed")
      putJsonObject("start") { put("dateTime", iso(event.startMs)) }
      putJsonObject("end") { put("dateTime", iso(event.endMs)) }
      putJsonObject("reminders") { put("useDefault", false) }
    }.toString()
    val events = "$baseUrl/calendar/v3/calendars/${calendarId.enc()}/events"
    val (code, _) = send("POST", events, body)
    if (code in 200..299) return
    // Google keeps a deleted event's id. A block moved away and back to the same time
    // has the same id again, so the insert is refused; writing over it brings it back.
    if (code == 409) {
      val (restored, _) = send("PUT", "$events/${event.id}", body)
      if (restored in 200..299) return
      throw PlanCalendarFailure(restored, "restoring a plan event failed: $restored")
    }
    throw PlanCalendarFailure(code, "writing a plan event failed: $code")
  }

  override fun delete(calendarId: String, eventId: String) {
    val (code, _) = send("DELETE", "$baseUrl/calendar/v3/calendars/${calendarId.enc()}/events/$eventId", null)
    // Already gone is what was wanted.
    if (code !in 200..299 && code != 404 && code != 410) throw PlanCalendarFailure(code, "removing a plan event failed: $code")
  }

  private fun send(method: String, url: String, body: String?): Pair<Int, String> {
    val request =
      Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")
        .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    client.newCall(request).execute().use { response -> return response.code to response.body?.string().orEmpty() }
  }

  private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

  private fun String.enc(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

  companion object {
    const val CALENDAR_NAME = "Daily Brief"
  }
}

/** Fixture mode's plan calendar: an in-memory set per workspace, readable by the journey. */
object FixturePlanCalendar {
  val events = ConcurrentHashMap<String, MutableMap<String, PlannedEvent>>()
  val pushedAt = ConcurrentHashMap<String, Long>()

  fun of(workspaceId: String): PlanCalendar =
    object : PlanCalendar {
      private val held get() = events.getOrPut(workspaceId) { ConcurrentHashMap() }

      override fun create(timeZone: String): String = "fixture-plan-calendar"

      override fun eventIds(calendarId: String, fromMs: Long, toMs: Long): Set<String> = held.keys.toSet()

      override fun insert(calendarId: String, event: PlannedEvent) {
        held[event.id] = event
      }

      override fun delete(calendarId: String, eventId: String) {
        held.remove(eventId)
      }
    }
}

/** Writes the plan to a calendar: creates it when missing, then makes it match. Returns the calendar's id. */
object PlanCalendarSync {
  fun run(calendar: PlanCalendar, calendarId: String?, timeZone: String, desired: List<PlannedEvent>, fromMs: Long, toMs: Long): String {
    var id = calendarId ?: calendar.create(timeZone)
    // The reader deleted the calendar: make it again rather than fail for ever.
    val existing = calendar.eventIds(id, fromMs, toMs) ?: run {
      id = calendar.create(timeZone)
      emptySet()
    }
    val changes = PlanCalendarDiff.changes(desired, existing)
    changes.delete.forEach { calendar.delete(id, it) }
    changes.insert.forEach { calendar.insert(id, it) }
    return id
  }
}
