package com.example.core

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Lenient ISO-8601 parsing for values coming back from APIs.
 *
 * Notion alone emits several shapes — date only, with and without milliseconds, with a
 * colon-separated offset, a compact offset, or a trailing Z — so each is accepted.
 *
 * This was a stack of `SimpleDateFormat` patterns tried in turn. The patterns are now one
 * regex, because `SimpleDateFormat` is JVM-only and the engine has to run on a server and on
 * iOS. The accepted shapes and the failure mode are unchanged, and `IsoDatesTest` is the proof:
 * a bare date is a *local* day, an explicit offset beats the caller's zone, a timestamp without
 * an offset is wall-clock time in the caller's zone, and anything unrecognised returns 0 rather
 * than a plausible wrong date.
 */
object IsoDates {

  /**
   * Date, then optionally a time, then optionally an offset.
   *
   * Seconds are optional; a fractional part is only accepted after seconds, which is what the
   * old pattern list allowed. `matchEntire` is what rejects trailing rubbish — the old code
   * needed an explicit `ParsePosition` check for the same reason, or `2024-01-02T03:04` would
   * have satisfied a bare `yyyy-MM-dd`.
   */
  private val ISO =
    Regex(
      """(\d{4})-(\d{2})-(\d{2})""" +
        """(?:[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?([Zz]|[+-]\d{2}:?\d{2})?)?""",
    )

  /** UTC instants for files that leave this device: exports, calendars, anything shared. */
  fun isoUtc(epochMillis: Long): String {
    val t = utcParts(epochMillis)
    return "${t.year}-${t.month}-${t.day}T${t.hour}:${t.minute}:${t.second}Z"
  }

  /** The compact form iCalendar requires. */
  fun icsUtc(epochMillis: Long): String {
    val t = utcParts(epochMillis)
    return "${t.year}${t.month}${t.day}T${t.hour}${t.minute}${t.second}Z"
  }

  /** Epoch millis, or 0 when nothing matches. */
  fun parse(value: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): Long {
    val raw = value.trim()
    if (raw.isEmpty()) return 0L
    val match = ISO.matchEntire(raw) ?: return 0L
    val g = match.groupValues

    // LocalDate and LocalDateTime reject impossible values, which is how "2026-13-45" fails.
    return try {
      val date = LocalDate(g[1].toInt(), g[2].toInt(), g[3].toInt())
      if (g[4].isEmpty()) {
        // A bare date is a local day, not a UTC instant.
        return date.atStartOfDayIn(timeZone).toEpochMilliseconds()
      }
      val nanos = g[7].takeIf { it.isNotEmpty() }?.padEnd(9, '0')?.take(9)?.toInt() ?: 0
      val moment =
        LocalDateTime(
          year = date.year,
          month = date.month,
          day = date.day,
          hour = g[4].toInt(),
          minute = g[5].toInt(),
          second = g[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0,
          nanosecond = nanos,
        )
      val offset = g[8]
      if (offset.isEmpty()) {
        // No offset in the string: read it as wall-clock time in the given zone.
        moment.toInstant(timeZone).toEpochMilliseconds()
      } else {
        moment.toInstant(parseOffset(offset)).toEpochMilliseconds()
      }
    } catch (e: IllegalArgumentException) {
      0L
    }
  }

  private fun parseOffset(raw: String): UtcOffset {
    if (raw.equals("Z", ignoreCase = true)) return UtcOffset.ZERO
    val sign = if (raw[0] == '-') -1 else 1
    val digits = raw.drop(1).replace(":", "")
    return UtcOffset(hours = sign * digits.take(2).toInt(), minutes = sign * digits.drop(2).toInt())
  }

  private class Parts(
    val year: String,
    val month: String,
    val day: String,
    val hour: String,
    val minute: String,
    val second: String,
  )

  private fun utcParts(epochMillis: Long): Parts {
    val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC)
    return Parts(
      year = t.year.toString().padStart(4, '0'),
      month = t.month.number.pad(),
      day = t.day.pad(),
      hour = t.hour.pad(),
      minute = t.minute.pad(),
      second = t.second.pad(),
    )
  }

  private fun Int.pad(): String = toString().padStart(2, '0')
}
