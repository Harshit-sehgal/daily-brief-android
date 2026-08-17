package com.example.data.api

import android.util.Log
import com.example.core.IsoDates
import kotlinx.datetime.toKotlinTimeZone
import com.example.core.ScheduleAnalysis
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class NotionFetch(
  val events: List<BriefingEvent>,
  /** True only when every API page and every dated row was read successfully. */
  val complete: Boolean,
  val error: String? = null,
  val warnings: List<String> = emptyList(),
  val skippedUndatedCount: Int = 0,
)

internal data class NotionDateCandidate(
  val propertyName: String,
  val start: String,
  val end: String = "",
  val timeZoneId: String? = null,
)

internal data class NotionDateWindow(
  val startMs: Long,
  val endExclusiveMs: Long,
  val isAllDay: Boolean,
)

private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")
private val DATE_PROPERTY_PRIORITY =
  listOf("date", "start", "start date", "when", "schedule", "due", "due date", "deadline")

/** Stable across JSON property order; named schedule fields win, then lexical order. */
internal fun selectNotionDateCandidate(
  candidates: List<NotionDateCandidate>
): NotionDateCandidate? =
  candidates
    .filter { it.start.isNotBlank() }
    .minWithOrNull(
      compareBy<NotionDateCandidate>(
          {
            DATE_PROPERTY_PRIORITY.indexOf(it.propertyName.lowercase(Locale.US)).let { index ->
              if (index >= 0) index else Int.MAX_VALUE
            }
          },
          { it.propertyName.lowercase(Locale.US) },
          { it.propertyName },
        )
    )

/** Maps a Notion date property to a half-open event window. */
internal fun parseNotionDateWindow(
  candidate: NotionDateCandidate,
  fallbackTimeZone: TimeZone = TimeZone.getDefault(),
): NotionDateWindow? {
  val startRaw = candidate.start.trim()
  val endRaw = candidate.end.trim()
  if (startRaw.isEmpty()) return null

  val zone = candidate.timeZoneId?.let(::notionTimeZoneOrNull) ?: fallbackTimeZone
  // IsoDates has moved to commonMain and speaks kotlinx-datetime; ScheduleAnalysis has not yet.
  // The conversion is exact — no fallback zone, so a bad id still fails rather than silently
  // becoming UTC. Remove once the whole date-time cluster has moved.
  val commonZone = zone.toZoneId().toKotlinTimeZone()
  val startMs = IsoDates.parse(startRaw, commonZone)
  if (startMs == 0L) return null

  val allDay = DATE_ONLY.matches(startRaw) && (endRaw.isEmpty() || DATE_ONLY.matches(endRaw))
  if (allDay) {
    // Notion's end is the last calendar date shown in its inclusive date-range UI;
    // our storage/query convention is an exclusive end.
    val lastDay = if (endRaw.isEmpty()) startMs else IsoDates.parse(endRaw, commonZone)
    if (lastDay == 0L || lastDay < startMs) return null
    return NotionDateWindow(
      startMs = startMs,
      endExclusiveMs = ScheduleAnalysis.startOfDayOffset(lastDay, 1, commonZone),
      isAllDay = true,
    )
  }

  val endMs = if (endRaw.isEmpty()) startMs + HOUR_MS else IsoDates.parse(endRaw, commonZone)
  if (endMs <= startMs) return null
  return NotionDateWindow(startMs, endMs, isAllDay = false)
}

private fun notionTimeZoneOrNull(id: String): TimeZone? {
  val clean = id.trim()
  if (clean.isEmpty()) return null
  val zone = TimeZone.getTimeZone(clean)
  return zone.takeIf { it.id != "GMT" || clean.equals("GMT", true) || clean.equals("UTC", true) }
}

object NotionClient {
  private const val TAG = "NotionClient"
  private const val PAGE_SIZE = 100
  private const val API_RESULT_LIMIT = 10_000
  // Current data-source API; the old database query endpoint was deprecated in 2025.
  private const val NOTION_VERSION = "2026-03-11"

  private val client =
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()

  private val jsonMediaType = "application/json".toMediaType()

  /** Accepts either the legacy database ID or a current data-source ID. */
  fun fetchNotionEvents(token: String, databaseOrDataSourceId: String): NotionFetch {
    val cleanToken = token.trim()
    val cleanId = databaseOrDataSourceId.trim()
    if (cleanToken.isBlank() || cleanId.isBlank()) {
      return NotionFetch(
        events = emptyList(),
        complete = false,
        error = "Notion token or database/data-source ID is missing",
      )
    }

    val resolution = resolveDataSourceId(cleanToken, cleanId)
    if (resolution is DataSourceResolution.Failed) {
      return NotionFetch(emptyList(), complete = false, error = resolution.reason)
    }
    val dataSourceId = (resolution as DataSourceResolution.Ready).id

    val events = mutableListOf<BriefingEvent>()
    val warnings = linkedSetOf<String>()
    val seenCursors = mutableSetOf<String>()
    var cursor: String? = null
    var skippedUndated = 0
    var invalidDated = 0
    var resultsSeen = 0

    try {
      do {
        val pageCursor = cursor
        if (pageCursor != null && !seenCursors.add(pageCursor)) {
          return incomplete(
            events,
            warnings,
            skippedUndated,
            "Notion returned a repeated pagination cursor",
          )
        }

        val payload =
          JSONObject().apply {
            put("page_size", PAGE_SIZE)
            put("result_type", "page")
            if (pageCursor != null) put("start_cursor", pageCursor)
          }

        val request =
          Request.Builder()
            .url("https://api.notion.com/v1/data_sources/$dataSourceId/query")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .notionHeaders(cleanToken)
            .build()

        client.newCall(request).execute().use { response ->
          val body = response.body.string()
          if (!response.isSuccessful) {
            val message = friendlyError(response.code, body, "data source")
            Log.e(TAG, "Notion data-source query failed (${response.code}): $message")
            return incomplete(events, warnings, skippedUndated, message)
          }

          val json = JSONObject(body)
          val results =
            json.optJSONArray("results")
              ?: return incomplete(
                events,
                warnings,
                skippedUndated,
                "Notion returned a response without results",
              )
          if (!json.has("has_more") || json.isNull("has_more")) {
            return incomplete(
              events,
              warnings,
              skippedUndated,
              "Notion returned incomplete pagination metadata",
            )
          }
          resultsSeen += results.length()
          for (i in 0 until results.length()) {
            val page = results.optJSONObject(i) ?: continue
            if (page.optString("object") != "page") continue
            when (val parsed = parsePage(page)) {
              is PageParse.Parsed -> {
                events += parsed.event
                if (parsed.hadMultipleDates) {
                  warnings +=
                    "Some Notion rows have multiple date properties; a deterministic schedule field was selected"
                }
              }
              PageParse.Undated -> skippedUndated++
              PageParse.InvalidDate -> invalidDated++
            }
          }

          if (json.optBoolean("has_more", false)) {
            cursor = json.stringOrEmpty("next_cursor").takeIf { it.isNotBlank() }
            if (cursor == null) {
              return incomplete(
                events,
                warnings,
                skippedUndated,
                "Notion omitted the next pagination cursor",
              )
            }
          } else {
            cursor = null
          }
        }
      } while (cursor != null)
    } catch (e: Exception) {
      Log.e(TAG, "Error fetching Notion data source", e)
      return incomplete(
        events,
        warnings,
        skippedUndated,
        "Couldn't reach Notion — check your connection",
      )
    }

    if (skippedUndated > 0) {
      warnings +=
        "$skippedUndated undated Notion ${if (skippedUndated == 1) "row was" else "rows were"} skipped"
    }
    if (resultsSeen >= API_RESULT_LIMIT) {
      return incomplete(
        events,
        warnings,
        skippedUndated,
        "Notion's 10,000-row query limit was reached; narrow or split the data source",
      )
    }
    if (invalidDated > 0) {
      return incomplete(
        events,
        warnings,
        skippedUndated,
        "$invalidDated dated Notion ${if (invalidDated == 1) "row couldn't" else "rows couldn't"} be parsed",
      )
    }

    return NotionFetch(
      events = events.distinctBy { it.id },
      complete = true,
      warnings = warnings.toList(),
      skippedUndatedCount = skippedUndated,
    )
  }

  private fun resolveDataSourceId(token: String, suppliedId: String): DataSourceResolution {
    val request =
      Request.Builder()
        .url("https://api.notion.com/v1/databases/$suppliedId")
        .get()
        .notionHeaders(token)
        .build()

    return try {
      client.newCall(request).execute().use { response ->
        val body = response.body.string()
        if (response.code == 404) {
          // A data-source ID is not a database ID; let the query endpoint validate it.
          return DataSourceResolution.Ready(suppliedId)
        }
        if (!response.isSuccessful) {
          return DataSourceResolution.Failed(friendlyError(response.code, body, "database"))
        }

        val sources = JSONObject(body).optJSONArray("data_sources") ?: JSONArray()
        val ids =
          (0 until sources.length()).mapNotNull { index ->
            sources.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
          }
        when (ids.size) {
          0 -> DataSourceResolution.Failed("That Notion database has no queryable data source")
          1 -> DataSourceResolution.Ready(ids.single())
          else ->
            DataSourceResolution.Failed(
              "That database has multiple data sources; paste the specific data-source ID"
            )
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Could not resolve Notion data source", e)
      DataSourceResolution.Failed("Couldn't reach Notion — check your connection")
    }
  }

  private fun Request.Builder.notionHeaders(token: String): Request.Builder =
    addHeader("Authorization", "Bearer $token")
      .addHeader("Notion-Version", NOTION_VERSION)
      .addHeader("Content-Type", "application/json")

  private fun incomplete(
    events: List<BriefingEvent>,
    warnings: Collection<String>,
    skippedUndated: Int,
    error: String,
  ): NotionFetch {
    val completeWarnings = warnings.toMutableSet()
    if (skippedUndated > 0) {
      completeWarnings +=
        "$skippedUndated undated Notion ${if (skippedUndated == 1) "row was" else "rows were"} skipped"
    }
    return NotionFetch(
      events = events.distinctBy { it.id },
      complete = false,
      error = error,
      warnings = completeWarnings.toList(),
      skippedUndatedCount = skippedUndated,
    )
  }

  private fun friendlyError(code: Int, body: String?, resource: String): String =
    when (code) {
      400 -> "Notion rejected the $resource ID or query"
      401 -> "Notion rejected the token"
      403 -> "The integration doesn't have access to that $resource"
      404 -> "$resource not found — check the ID and that it's shared with the integration"
      409 -> "Notion couldn't complete the request because the data changed"
      429 -> "Notion rate limit reached, try again shortly"
      in 500..599 -> "Notion is having trouble right now"
      else -> {
        val message = body?.let { runCatching { JSONObject(it).optString("message") }.getOrNull() }
        if (message.isNullOrBlank()) "Notion request failed ($code)" else message
      }
    }

  private fun parsePage(page: JSONObject): PageParse {
    val pageId = page.optString("id").takeIf { it.isNotBlank() } ?: return PageParse.InvalidDate
    val properties = page.optJSONObject("properties") ?: JSONObject()

    var title = ""
    var isDeadline = false
    var isUrgent = false
    val details = mutableListOf<String>()
    val dates = mutableListOf<NotionDateCandidate>()

    val keys = properties.keys()
    while (keys.hasNext()) {
      val name = keys.next()
      val property = properties.optJSONObject(name) ?: continue
      when (property.optString("type")) {
        "title" -> title = richTextOf(property.optJSONArray("title"))
        "rich_text" -> {
          val text = richTextOf(property.optJSONArray("rich_text"))
          if (text.isNotBlank()) details += text
        }
        "date" -> {
          val date = property.optJSONObject("date") ?: continue
          dates +=
            NotionDateCandidate(
              propertyName = name,
              start = date.stringOrEmpty("start"),
              end = date.stringOrEmpty("end"),
              timeZoneId = date.stringOrEmpty("time_zone").takeIf { it.isNotBlank() },
            )
        }
        "checkbox" -> {
          val checked = property.optBoolean("checkbox", false)
          val lower = name.lowercase(Locale.US)
          if (lower.contains("urgent") || lower.contains("priority")) isUrgent = isUrgent || checked
          if (lower.contains("deadline") || lower.contains("due")) isDeadline = isDeadline || checked
        }
        "select" -> {
          val option = property.optJSONObject("select")?.optString("name").orEmpty()
          if (option.isNotBlank()) {
            details += "$name: $option"
            if (matchesUrgent(option)) isUrgent = true
            if (matchesDeadline(option)) isDeadline = true
          }
        }
        "status" -> {
          val option = property.optJSONObject("status")?.optString("name").orEmpty()
          if (option.isNotBlank()) details += "$name: $option"
        }
        "multi_select" -> {
          val array = property.optJSONArray("multi_select") ?: continue
          for (i in 0 until array.length()) {
            val option = array.optJSONObject(i)?.optString("name").orEmpty()
            if (option.isBlank()) continue
            details += option
            if (matchesUrgent(option)) isUrgent = true
            if (matchesDeadline(option)) isDeadline = true
          }
        }
      }
    }

    val chosen = selectNotionDateCandidate(dates) ?: return PageParse.Undated
    val window = parseNotionDateWindow(chosen) ?: return PageParse.InvalidDate
    val resolvedTitle = title.ifBlank { "Untitled Notion item" }
    if (matchesUrgent(resolvedTitle)) isUrgent = true
    if (matchesDeadline(resolvedTitle)) isDeadline = true

    return PageParse.Parsed(
      event =
        BriefingEvent(
          id = "notion_$pageId",
          title = resolvedTitle,
          startTime = window.startMs,
          endTime = window.endExclusiveMs,
          source = EventSource.NOTION,
          description = details.joinToString(" · ").takeIf { it.isNotBlank() },
          isDeadline = isDeadline,
          isUrgent = isUrgent,
          isAllDay = window.isAllDay,
        ),
      hadMultipleDates = dates.count { it.start.isNotBlank() } > 1,
    )
  }

  private fun matchesUrgent(value: String): Boolean {
    val v = value.lowercase(Locale.US)
    return v.contains("urgent") || v.contains("asap") || v == "high" || v.contains("high priority")
  }

  private fun matchesDeadline(value: String): Boolean {
    val v = value.lowercase(Locale.US)
    return v.contains("deadline") || v.contains("due")
  }

  private fun richTextOf(array: JSONArray?): String {
    if (array == null) return ""
    val builder = StringBuilder()
    for (i in 0 until array.length()) {
      val part = array.optJSONObject(i) ?: continue
      val text =
        part.optString("plain_text").takeIf { it.isNotBlank() }
          ?: part.optJSONObject("text")?.optString("content").orEmpty()
      builder.append(text)
    }
    return builder.toString().trim()
  }

  private fun JSONObject.stringOrEmpty(key: String): String =
    if (isNull(key)) "" else optString(key)

  private sealed interface DataSourceResolution {
    data class Ready(val id: String) : DataSourceResolution

    data class Failed(val reason: String) : DataSourceResolution
  }

  private sealed interface PageParse {
    data class Parsed(val event: BriefingEvent, val hadMultipleDates: Boolean) : PageParse

    data object Undated : PageParse

    data object InvalidDate : PageParse
  }
}

private const val HOUR_MS = 60L * 60 * 1000
