package com.example.server.summary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The daily brief's quota decisions — pure, so the boundary is unit-tested without a
 * database. Limits live in `subscriptions.quota_json` (`summaries_per_month`); the ledger
 * is `gemini_usage`, and reserve-then-refuse happens in the request transaction (invariant
 * 4): exceed → roll back and say so, never a silent fallback.
 */
object GeminiQuota {
  /** The default when quota_json says nothing: enough for a free workspace's month, cheap to outgrow on purpose. */
  const val DEFAULT_SUMMARIES_PER_MONTH = 3

  fun limitFrom(quotaJson: String): Int =
    try {
      Json.parseToJsonElement(quotaJson)
        .jsonObject["summaries_per_month"]
        ?.jsonPrimitive
        ?.intOrNull
        ?: DEFAULT_SUMMARIES_PER_MONTH
    } catch (_: Exception) {
      DEFAULT_SUMMARIES_PER_MONTH
    }

  /** Malformed storage falls back to the default, never to the shortest window. */
  fun refuses(used: Long, limit: Int): Boolean = used >= limit

  /** The month bucket is UTC so every workspace's month ends on the same boundary. */
  fun periodMonth(nowMs: Long): String {
    val utc = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneOffset.UTC)
    return "%04d-%02d".format(utc.year, utc.monthValue)
  }
}