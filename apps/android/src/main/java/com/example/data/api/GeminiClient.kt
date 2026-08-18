package com.example.data.api

import android.util.Log
import com.example.core.ScheduleAnalysis
import com.example.data.model.BriefingEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Either a real generated brief, or a locally derived one plus the reason why. */
sealed interface BriefOutcome {
  val markdown: String

  data class Generated(override val markdown: String, val model: String) : BriefOutcome

  data class LocalFallback(override val markdown: String, val reason: String) : BriefOutcome
}

object GeminiClient {
  private const val TAG = "GeminiClient"

  /**
   * Tried in order. If the endpoint reports a model as unavailable we move to
   * the next one instead of surfacing an error, so the app keeps working when
   * model names are rotated. A specific model can be pinned from Settings.
   */
  private val MODEL_CANDIDATES =
    listOf("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash")

  private val client =
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .build()

  private val jsonMediaType = "application/json".toMediaType()

  private const val SYSTEM_INSTRUCTION =
    """
You write a short daily schedule brief. Output Markdown only, in exactly this shape,
with no preamble, no closing remarks, no emoji and no code fences:

## Focus
One sentence, at most 18 words, naming what actually matters today.

## Watch out
Up to three bullets, each at most 12 words, covering overlaps, tight gaps and
deadlines. If there is nothing to flag, write the single bullet: "- Nothing conflicting today."

## Plan
Two or three bullets, each at most 10 words, phrased as concrete next actions.

Do not restate the timeline; the app already shows it. Do not invent events.
"""

  suspend fun generateDailyBrief(
    events: List<BriefingEvent>,
    targetDate: Date,
    apiKeyOverride: String? = null,
    modelOverride: String? = null,
  ): BriefOutcome {
    val apiKey = apiKeyOverride?.takeIf { it.isNotBlank() }
    if (apiKey == null) {
      return BriefOutcome.LocalFallback(
        localBrief(events, targetDate.time),
        "No Gemini API key configured. Add your own key in Settings to get an AI brief.",
      )
    }

    val models =
      modelOverride?.takeIf { it.isNotBlank() }?.let { listOf(it) + MODEL_CANDIDATES }
        ?: MODEL_CANDIDATES

    val payload = requestBody(events, targetDate)
    var lastProblem = "Couldn't reach Gemini"

    for (model in models.distinct()) {
      when (val attempt = callModel(model, apiKey, payload)) {
        is Attempt.Success -> return BriefOutcome.Generated(attempt.text, model)
        is Attempt.ModelUnavailable -> {
          Log.w(TAG, "Model $model unavailable, trying next candidate")
          lastProblem = attempt.reason
        }
        is Attempt.Failed ->
          return BriefOutcome.LocalFallback(localBrief(events, targetDate.time), attempt.reason)
      }
    }
    return BriefOutcome.LocalFallback(localBrief(events, targetDate.time), lastProblem)
  }

  private sealed interface Attempt {
    data class Success(val text: String) : Attempt

    data class ModelUnavailable(val reason: String) : Attempt

    data class Failed(val reason: String) : Attempt
  }

  private fun callModel(model: String, apiKey: String, payload: String): Attempt {
    val request =
      Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        // Header rather than a query parameter so the key never lands in a URL log.
        .addHeader("x-goog-api-key", apiKey)
        .addHeader("Content-Type", "application/json")
        .post(payload.toRequestBody(jsonMediaType))
        .build()

    return try {
      client.newCall(request).execute().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
          val detail = errorMessage(body)
          Log.e(TAG, "Gemini ${response.code} for $model: $body")
          return when (response.code) {
            404 -> Attempt.ModelUnavailable("Model $model is not available")
            400 ->
              if (detail.contains("API key", ignoreCase = true))
                Attempt.Failed("Gemini rejected the API key")
              else Attempt.Failed(detail)
            401,
            403 -> Attempt.Failed("Gemini rejected the API key")
            429 -> Attempt.Failed("Gemini rate limit reached — try again shortly")
            in 500..599 -> Attempt.Failed("Gemini is having trouble right now")
            else -> Attempt.Failed(detail)
          }
        }
        val json = JSONObject(body)
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
          ?.let {
            return Attempt.Failed("Gemini blocked the request ($it)")
          }

        val text = extractText(json)
        if (text.isBlank()) Attempt.Failed("Gemini returned no usable text") else Attempt.Success(text)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Gemini request failed", e)
      Attempt.Failed("Couldn't reach Gemini — check your connection")
    }
  }

  private fun extractText(json: JSONObject): String {
    val candidates = json.optJSONArray("candidates") ?: return ""
    if (candidates.length() == 0) return ""
    val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
    val parts = content.optJSONArray("parts") ?: return ""
    val builder = StringBuilder()
    for (i in 0 until parts.length()) {
      builder.append(parts.optJSONObject(i)?.optString("text").orEmpty())
    }
    return builder.toString().trim()
  }

  private fun errorMessage(body: String?): String {
    val parsed =
      body?.let {
        runCatching { JSONObject(it).optJSONObject("error")?.optString("message") }.getOrNull()
      }
    return parsed?.takeIf { it.isNotBlank() } ?: "Gemini request failed"
  }

  private fun requestBody(events: List<BriefingEvent>, targetDate: Date): String {
    val dayFormat = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US)
    val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    val lines = StringBuilder()
    if (events.isEmpty()) {
      lines.append("Nothing is scheduled.\n")
    } else {
      events
        .sortedBy { it.startTime }
        .forEach { event ->
          if (event.isAllDay) {
            lines.append("- All day ")
          } else {
            lines.append("- ${timeFormat.format(Date(event.startTime))}")
            lines.append("–${timeFormat.format(Date(event.endTime))} ")
          }
          lines.append(event.title)
          val tags = buildList {
            if (event.isUrgent) add("urgent")
            if (event.isDeadline) add("deadline")
            if (ScheduleAnalysis.isAllDay(event)) add("all day")
            add(event.source)
          }
          lines.append(" [${tags.joinToString(", ")}]")
          event.location?.takeIf { it.isNotBlank() }?.let { lines.append(" @ $it") }
          lines.append("\n")
        }
    }

    val conflicts = ScheduleAnalysis.findConflicts(events)
    if (conflicts.isNotEmpty()) {
      lines.append("\nOverlaps already detected:\n")
      conflicts.take(5).forEach {
        lines.append("- \"${it.first.title}\" overlaps \"${it.second.title}\"\n")
      }
    }

    val prompt =
      "Schedule for ${dayFormat.format(targetDate)} (times are local):\n\n$lines\nWrite the brief."

    return JSONObject()
      .apply {
        put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        put(
          "systemInstruction",
          JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION.trimIndent()))),
        )
        // Newer Gemini models choose their own sampling behavior; pinning
        // temperature is deprecated and can make future models reject a request.
        put("generationConfig", JSONObject().put("maxOutputTokens", 512))
      }
      .toString()
  }

  /**
   * Deterministic brief built from the same analysis the UI uses. Shown whenever
   * the model is unreachable so the screen is never empty.
   */
  fun localBrief(events: List<BriefingEvent>, dayMs: Long? = null): String {
    val stats =
      if (dayMs == null) {
        ScheduleAnalysis.statsFor(events)
      } else {
        val bounds = ScheduleAnalysis.dayBounds(dayMs)
        ScheduleAnalysis.statsFor(events, bounds.first, bounds.last + 1)
      }
    val conflicts = ScheduleAnalysis.findConflicts(events)
    val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    val sb = StringBuilder()

    sb.append("## Focus\n")
    sb.append(
      when {
        stats.total == 0 -> "Nothing scheduled — a clear day to use as you like.\n"
        stats.urgent > 0 ->
          "${stats.urgent} priority item${if (stats.urgent == 1) "" else "s"} across ${stats.total} entries.\n"
        else -> "${stats.total} entries, roughly ${stats.bookedMinutes / 60}h booked.\n"
      }
    )

    sb.append("\n## Watch out\n")
    if (conflicts.isEmpty()) {
      sb.append("- Nothing conflicting today.\n")
    } else {
      conflicts.take(3).forEach {
        sb.append(
          "- ${timeFormat.format(Date(it.second.startTime))} \"${it.first.title}\" overlaps \"${it.second.title}\"\n"
        )
      }
    }

    sb.append("\n## Plan\n")
    val firstDeadline = events.filter { it.isDeadline }.minByOrNull { it.startTime }
    if (firstDeadline != null) {
      sb.append("- Clear \"${firstDeadline.title}\" first.\n")
    }
    if (conflicts.isNotEmpty()) {
      sb.append("- Move one of the overlapping blocks.\n")
    }
    sb.append(if (stats.total == 0) "- Block time for deep work.\n" else "- Protect the gaps between blocks.\n")

    return sb.toString()
  }
}
