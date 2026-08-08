package com.example.core

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Lenient ISO-8601 parsing for values coming back from APIs.
 *
 * Notion alone emits several shapes — date only, with and without milliseconds,
 * with a colon-separated offset, a compact offset, or a trailing Z — so each is
 * tried in turn rather than assuming one. Kept free of Android imports so it can
 * be unit tested directly.
 */
object IsoDates {

  private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

  private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")

  /** `X` patterns accept both `Z` and a numeric offset, so `Z` needs no rewriting. */
  private val ZONED_PATTERNS =
    listOf(
      "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
      "yyyy-MM-dd'T'HH:mm:ssXXX",
      "yyyy-MM-dd'T'HH:mm:ss.SSSXX",
      "yyyy-MM-dd'T'HH:mm:ssXX",
      "yyyy-MM-dd'T'HH:mmXXX",
    )

  private val LOCAL_PATTERNS =
    listOf("yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm")

  /** Epoch millis, or 0 when nothing matches. */
  fun parse(value: String, timeZone: TimeZone = TimeZone.getDefault()): Long {
    val raw = value.trim()
    if (raw.isEmpty()) return 0L

    // A bare date is a local day, not a UTC instant.
    if (DATE_ONLY.matches(raw)) return parseWith("yyyy-MM-dd", raw, timeZone)

    for (pattern in ZONED_PATTERNS) {
      val parsed = parseWith(pattern, raw, UTC)
      if (parsed != 0L) return parsed
    }
    // No offset in the string: read it as wall-clock time in the given zone.
    for (pattern in LOCAL_PATTERNS) {
      val parsed = parseWith(pattern, raw, timeZone)
      if (parsed != 0L) return parsed
    }
    return 0L
  }

  private fun parseWith(pattern: String, value: String, zone: TimeZone): Long =
    try {
      val format =
        SimpleDateFormat(pattern, Locale.US).apply {
          timeZone = zone
          isLenient = false
        }
      val position = ParsePosition(0)
      val parsed = format.parse(value, position)
      // Reject partial matches, or "2024-01-02T03:04" would satisfy "yyyy-MM-dd".
      if (parsed == null || position.index != value.length) 0L else parsed.time
    } catch (e: IllegalArgumentException) {
      0L
    }
}
