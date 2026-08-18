package com.example.server.summary

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The daily brief's provider seam (09-server-invariants §4): one platform key held by the
 * server, never a tenant row and never a client credential. [FixtureSummaryProvider] runs
 * the journey headless, [NotConfiguredSummaryProvider] answers honestly when no key is set,
 * and [GeminiSummaryProvider] is the real path — a plain REST call, no SDK dependency,
 * activating only when GEMINI_API_KEY is present.
 */
data class SummaryContext(
  /** `YYYY-MM-DD` (UTC) — the calendar day the brief describes. */
  val date: String,
  val dayName: String,
  val eventTitles: List<String>,
  val blockCount: Int,
  val conflictCount: Int,
  val busyMinutes: Int,
  val freeMinutes: Int,
)

data class SummaryResult(val text: String)

interface SummaryProvider {
  val configured: Boolean

  fun summarize(context: SummaryContext): SummaryResult
}

class NotConfiguredSummaryProvider : SummaryProvider {
  override val configured = false

  override fun summarize(context: SummaryContext): SummaryResult =
    error("no Gemini key configured on this deployment")
}

/** Deterministic canned brief — sentences, not shouty labels — so the journey can pin the wire. */
class FixtureSummaryProvider : SummaryProvider {
  override val configured = true

  override fun summarize(context: SummaryContext): SummaryResult {
    val titles = context.eventTitles.take(3).joinToString(", ")
    val overlap =
      when {
        context.conflictCount == 0 -> "with no overlaps to sort out"
        context.conflictCount == 1 -> "with one overlap to sort out"
        else -> "with ${context.conflictCount} overlaps to sort out"
      }
    val busy = "You have ${context.freeMinutes} minutes free across the day."
    return SummaryResult(
      "Your ${context.dayName} has ${context.eventTitles.size} events and ${context.blockCount} plan blocks, $overlap. $busy",
    )
  }
}

/** The real seam: generateContent against the platform key. Plain REST, bounded timeouts. */
class GeminiSummaryProvider(
  private val apiKey: String,
  private val model: String = "gemini-2.0-flash",
) : SummaryProvider {
  override val configured = true

  override fun summarize(context: SummaryContext): SummaryResult {
    val prompt =
      "You write the Daily Brief for a personal planner. Today is ${context.date} (${context.dayName}). " +
        "The person has ${context.eventTitles.size} calendar events: ${context.eventTitles.take(6).joinToString(", ")}. " +
        "They have ${context.blockCount} plan blocks, ${context.conflictCount} overlapping meetings, " +
        "${context.freeMinutes} free minutes and ${context.busyMinutes} busy minutes. " +
        "Write 2-3 plain sentences: what to watch out for and where the room is. No labels, no lists, no markdown."
    val requestBody =
      """{"contents":[{"parts":[{"text":${JsonPrimitive(prompt)}}]}],"generationConfig":{"temperature":0.4,"maxOutputTokens":120}}"""
    val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
      .openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Content-Type", "application/json")
    connection.doOutput = true
    try {
      connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      if (code != 200) error("Gemini generateContent failed with HTTP $code")
      val body = BufferedReader(connection.inputStream.reader()).use { it.readText() }
      val text =
        Json.parseToJsonElement(body)
          .jsonObject["candidates"]
          ?.jsonArray
          ?.firstOrNull()
          ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
          ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
      return SummaryResult(text ?: error("Gemini returned no text"))
    } finally {
      connection.disconnect()
    }
  }
}