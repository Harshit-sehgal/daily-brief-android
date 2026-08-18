package com.example.contract

import kotlinx.serialization.Serializable

/**
 * The daily brief surface: the server holds the platform Gemini key and a per-tenant
 * monthly quota (`subscriptions.quota_json` + the `gemini_usage` ledger), so the client
 * never sees a credential — it asks for a brief and gets one, with its own usage next
 * to it (a total that cannot be complete must say so).
 *
 * The request pins the calendar day the brief describes; `date` is `YYYY-MM-DD` in UTC.
 */
@Serializable
data class SummaryRequestWire(
  val date: String? = null,
)

@Serializable
data class SummaryResponseWire(
  val v: Int = 1,
  val date: String,
  /** The brief itself — sentences, not shouty labels. */
  val text: String,
  /** "platform" when a real key backs the server, "fixture" in the journey's provider mode. */
  val source: String,
  val used: Int,
  val limit: Int,
)