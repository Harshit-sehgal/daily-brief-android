package com.example.data.api

import android.util.Log
import com.example.data.model.BriefingEvent
import com.example.core.IsoDates
import com.example.data.model.EventSource
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class NotionFetch(val events: List<BriefingEvent>, val error: String? = null)

object NotionClient {
  private const val TAG = "NotionClient"
  private const val PAGE_SIZE = 100
  private const val MAX_PAGES = 10
  private const val NOTION_VERSION = "2022-06-28"

  private val client =
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()

  private val jsonMediaType = "application/json".toMediaType()

  fun fetchNotionEvents(token: String, databaseId: String): NotionFetch {
    val cleanId = databaseId.trim().replace("-", "")
    if (token.isBlank() || cleanId.isBlank()) {
      return NotionFetch(emptyList(), "Notion token or database ID is missing")
    }

    val events = mutableListOf<BriefingEvent>()
    var cursor: String? = null
    var page = 0

    try {
      // Notion caps a query at 100 rows; follow the cursor so large databases
      // are not silently truncated.
      do {
        val pageCursor = cursor
        val payload =
          JSONObject().apply {
            put("page_size", PAGE_SIZE)
            if (pageCursor != null) put("start_cursor", pageCursor)
          }

        val request =
          Request.Builder()
            .url("https://api.notion.com/v1/databases/${databaseId.trim()}/query")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${token.trim()}")
            .addHeader("Notion-Version", NOTION_VERSION)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
          val body = response.body?.string()
          if (!response.isSuccessful || body == null) {
            val message = friendlyError(response.code, body)
            Log.e(TAG, "Notion error ${response.code}: $body")
            return NotionFetch(events, message)
          }

          val json = JSONObject(body)
          val results = json.optJSONArray("results") ?: JSONArray()
          for (i in 0 until results.length()) {
            parsePage(results.getJSONObject(i))?.let(events::add)
          }
          cursor = if (json.optBoolean("has_more", false)) json.optString("next_cursor", "") else null
          if (cursor.isNullOrEmpty()) cursor = null
        }
        page++
      } while (cursor != null && page < MAX_PAGES)
    } catch (e: Exception) {
      Log.e(TAG, "Error fetching Notion database", e)
      return NotionFetch(events, "Couldn't reach Notion — check your connection")
    }

    return NotionFetch(events)
  }

  private fun friendlyError(code: Int, body: String?): String =
    when (code) {
      401 -> "Notion rejected the token"
      403 -> "The integration doesn't have access to that database"
      404 -> "Database not found — check the ID and that it's shared with the integration"
      429 -> "Notion rate limit reached, try again shortly"
      else -> {
        val message = body?.let { runCatching { JSONObject(it).optString("message") }.getOrNull() }
        if (message.isNullOrBlank()) "Notion request failed ($code)" else message
      }
    }

  private fun parsePage(page: JSONObject): BriefingEvent? {
    val pageId = page.optString("id").takeIf { it.isNotBlank() } ?: return null
    val properties = page.optJSONObject("properties") ?: JSONObject()

    var title = ""
    var startTime = 0L
    var endTime = 0L
    var isDeadline = false
    var isUrgent = false
    val details = mutableListOf<String>()

    val keys = properties.keys()
    while (keys.hasNext()) {
      val name = keys.next()
      val property = properties.optJSONObject(name) ?: continue
      when (property.optString("type")) {
        "title" -> title = richTextOf(property.optJSONArray("title"))
        "rich_text" -> {
          val text = richTextOf(property.optJSONArray("rich_text"))
          if (text.isNotBlank()) details.add(text)
        }
        "date" -> {
          val date = property.optJSONObject("date")
          if (date != null && startTime == 0L) {
            val start = date.optString("start")
            val end = date.optString("end")
            if (start.isNotBlank()) startTime = IsoDates.parse(start)
            endTime =
              if (end.isNotBlank()) IsoDates.parse(end)
              else if (startTime > 0L) startTime + 60 * 60 * 1000 else 0L
          }
        }
        "checkbox" -> {
          val checked = property.optBoolean("checkbox", false)
          val lower = name.lowercase()
          if (lower.contains("urgent") || lower.contains("priority")) isUrgent = isUrgent || checked
          if (lower.contains("deadline") || lower.contains("due")) isDeadline = isDeadline || checked
        }
        "select" -> {
          val option = property.optJSONObject("select")?.optString("name").orEmpty()
          if (option.isNotBlank()) {
            details.add("$name: $option")
            if (matchesUrgent(option)) isUrgent = true
            if (matchesDeadline(option)) isDeadline = true
          }
        }
        "status" -> {
          val option = property.optJSONObject("status")?.optString("name").orEmpty()
          if (option.isNotBlank()) details.add("$name: $option")
        }
        "multi_select" -> {
          val array = property.optJSONArray("multi_select") ?: continue
          for (i in 0 until array.length()) {
            val option = array.optJSONObject(i)?.optString("name").orEmpty()
            if (option.isBlank()) continue
            details.add(option)
            if (matchesUrgent(option)) isUrgent = true
            if (matchesDeadline(option)) isDeadline = true
          }
        }
      }
    }

    if (startTime == 0L) {
      // No date property at all — anchor to when the row was created so it is at
      // least reachable, rather than dropping the task entirely.
      startTime = IsoDates.parse(page.optString("created_time")).takeIf { it > 0L }
        ?: System.currentTimeMillis()
      endTime = startTime + 60 * 60 * 1000
    }
    if (endTime <= startTime) endTime = startTime + 60 * 60 * 1000

    val resolvedTitle = title.ifBlank { "Untitled Notion item" }
    if (matchesUrgent(resolvedTitle)) isUrgent = true
    if (matchesDeadline(resolvedTitle)) isDeadline = true

    return BriefingEvent(
      id = "notion_$pageId",
      title = resolvedTitle,
      startTime = startTime,
      endTime = endTime,
      source = EventSource.NOTION,
      description = details.joinToString(" · ").takeIf { it.isNotBlank() },
      isDeadline = isDeadline,
      isUrgent = isUrgent,
    )
  }

  private fun matchesUrgent(value: String): Boolean {
    val v = value.lowercase()
    return v.contains("urgent") || v.contains("asap") || v == "high" || v.contains("high priority")
  }

  private fun matchesDeadline(value: String): Boolean {
    val v = value.lowercase()
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

}
