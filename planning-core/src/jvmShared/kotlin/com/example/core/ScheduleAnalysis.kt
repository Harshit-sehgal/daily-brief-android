package com.example.core

import com.example.data.model.BriefingEvent
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure schedule maths, shared by the UI, the AI prompt builder and the
 * notification receiver. Previously each of those carried its own copy of the
 * overlap loop; they are all this one now.
 */
object ScheduleAnalysis {

  data class Conflict(val first: BriefingEvent, val second: BriefingEvent) {
    /** Milliseconds the two events actually share. */
    val overlapMs: Long
      get() = minOf(first.endTime, second.endTime) - maxOf(first.startTime, second.startTime)
  }

  data class DayStats(val total: Int, val conflicts: Int, val urgent: Int, val bookedMinutes: Int)

  fun isAllDay(event: BriefingEvent): Boolean = event.isAllDay

  /**
   * Pairs of events whose times genuinely overlap, ordered by start time.
   *
   * All-day entries are skipped: they overlap everything by definition and
   * reporting them as clashes buries the real ones. Zero-length markers are
   * skipped for the same reason — they cannot occupy a slot.
   */
  fun findConflicts(events: List<BriefingEvent>): List<Conflict> {
    val candidates =
      events
        .filter { it.endTime > it.startTime && !isAllDay(it) }
        .sortedWith(compareBy({ it.startTime }, { it.endTime }, { it.id }))
    if (candidates.size < 2) return emptyList()

    val conflicts = mutableListOf<Conflict>()
    // Sweep line: only events still running when the next one starts can clash.
    val active = mutableListOf<BriefingEvent>()
    for (event in candidates) {
      active.removeAll { it.endTime <= event.startTime }
      for (open in active) {
        conflicts.add(Conflict(open, event))
      }
      active.add(event)
    }
    return conflicts
  }

  fun statsFor(
    events: List<BriefingEvent>,
    startInclusive: Long? = null,
    endExclusive: Long? = null,
  ): DayStats {
    val conflicts = findConflicts(events)
    val urgent = events.count { it.isUrgent || it.isDeadline }
    val booked =
      events.filterNot { isAllDay(it) }.sumOf { event ->
        val start = startInclusive?.let { maxOf(event.startTime, it) } ?: event.startTime
        val end = endExclusive?.let { minOf(event.endTime, it) } ?: event.endTime
        maxOf(0L, end - start)
      } /
        (60L * 1000L)
    return DayStats(
      total = events.size,
      conflicts = conflicts.size,
      urgent = urgent,
      bookedMinutes = booked.toInt(),
    )
  }

  /**
   * Fingerprint of everything a brief depends on. Two schedules with the same
   * signature produce the same brief, so a cached one can be reused.
   */
  fun signature(events: List<BriefingEvent>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.putInt(events.size)
    events.sortedBy { it.id }.forEach { event ->
      digest.putString(event.id)
      digest.putLong(event.startTime)
      digest.putLong(event.endTime)
      digest.putString(event.title)
      digest.putBoolean(event.isUrgent)
      digest.putBoolean(event.isDeadline)
      digest.putBoolean(event.isAllDay)
      digest.putString(event.source)
      // The Gemini prompt omits blank locations, so canonicalize them alike.
      digest.putString(event.location?.takeIf { it.isNotBlank() }.orEmpty())
    }
    return digest.digest().toHex()
  }

  /** Inclusive millisecond bounds of the local calendar day containing [timeMs]. */
  fun dayBounds(timeMs: Long, timeZone: TimeZone = TimeZone.getDefault()): LongRange =
    startOfDay(timeMs, timeZone)..(startOfDayOffset(timeMs, 1, timeZone) - 1)

  fun startOfDay(timeMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
    val cal =
      Calendar.getInstance(timeZone).apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
    return cal.timeInMillis
  }

  /** Start of the day [days] later, hopping via noon so DST cannot skip a day. */
  fun startOfDayOffset(
    timeMs: Long,
    days: Int,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): Long {
    val cal =
      Calendar.getInstance(timeZone).apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, days)
      }
    return startOfDay(cal.timeInMillis, timeZone)
  }

  /**
   * Material's date picker reports a UTC midnight. Reading the calendar fields
   * back out in UTC and rebuilding them locally keeps the user on the date they
   * actually tapped, whatever their offset.
   */
  fun localDayFromUtcMillis(utcMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
    return Calendar.getInstance(timeZone)
      .apply {
        clear()
        set(
          utc.get(Calendar.YEAR),
          utc.get(Calendar.MONTH),
          utc.get(Calendar.DAY_OF_MONTH),
          0,
          0,
          0,
        )
      }
      .timeInMillis
  }

  /**
   * Calendar providers store all-day boundaries as UTC midnights representing
   * calendar dates, not instants that should be shifted into the device zone.
   * Rebuild those dates at local midnight and keep the end exclusive.
   */
  fun normalizeAllDayUtcRange(
    startUtcMs: Long,
    endUtcExclusiveMs: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): Pair<Long, Long> {
    val localStart = localDayFromUtcMillis(startUtcMs, timeZone)
    val candidateEnd =
      if (endUtcExclusiveMs > startUtcMs) localDayFromUtcMillis(endUtcExclusiveMs, timeZone)
      else localStart
    val localEnd =
      if (candidateEnd > localStart) candidateEnd
      else startOfDayOffset(localStart, 1, timeZone)
    return localStart to localEnd
  }

  /**
   * Inverse of [localDayFromUtcMillis]: the UTC midnight the date picker needs
   * in order to pre-select the local date the user is actually on.
   */
  fun utcMillisFromLocalDay(localMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
    val local = Calendar.getInstance(timeZone).apply { timeInMillis = localMs }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"))
      .apply {
        clear()
        set(
          local.get(Calendar.YEAR),
          local.get(Calendar.MONTH),
          local.get(Calendar.DAY_OF_MONTH),
          0,
          0,
          0,
        )
      }
      .timeInMillis
  }

  /** Replaces the clock time on [dayMs] while keeping its date. */
  fun withTimeOfDay(
    dayMs: Long,
    hour: Int,
    minute: Int,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): Long =
    Calendar.getInstance(timeZone)
      .apply {
        timeInMillis = dayMs
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
      .timeInMillis

  /** Suggests a start on the day being viewed without rounding into another day. */
  fun suggestedEventStart(
    dayMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
  ): Long {
    if (!isSameDay(dayMs, nowMs, timeZone)) return withTimeOfDay(dayMs, 9, 0, timeZone)
    val quarter = 15 * 60 * 1000L
    val rounded = ((nowMs + quarter - 1) / quarter) * quarter
    return rounded.takeIf { isSameDay(it, dayMs, timeZone) } ?: nowMs
  }

  /**
   * Moves an event by local calendar days instead of fixed 24-hour periods.
   *
   * Timed events keep both endpoint wall-clock times. All-day events keep local-midnight
   * boundaries and their calendar-day span, including across 23- and 25-hour days.
   */
  fun moveEventByDays(
    event: BriefingEvent,
    days: Int,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): Pair<Long, Long> {
    if (days == 0) return event.startTime to event.endTime
    if (event.isAllDay) {
      val start = startOfDayOffset(event.startTime, days, timeZone)
      val end = startOfDayOffset(event.endTime, days, timeZone)
      return start to if (end > start) end else startOfDayOffset(start, 1, timeZone)
    }

    val start = calendarTimeOffset(event.startTime, days, timeZone)
    val end = calendarTimeOffset(event.endTime, days, timeZone)
    return start to if (end > start) end else start + 60_000L
  }

  /** Moves an event onto an exact local calendar date while preserving its wall-clock span. */
  fun moveEventToDay(
    event: BriefingEvent,
    targetDayMs: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): Pair<Long, Long> {
    val delta = localDateOrdinal(targetDayMs, timeZone) - localDateOrdinal(event.startTime, timeZone)
    require(delta in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
      "The target date is outside the supported calendar range"
    }
    return moveEventByDays(event, delta.toInt(), timeZone)
  }

  fun hourOf(timeMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Int =
    Calendar.getInstance(timeZone).apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)

  fun minuteOf(timeMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Int =
    Calendar.getInstance(timeZone).apply { timeInMillis = timeMs }.get(Calendar.MINUTE)

  private fun calendarTimeOffset(timeMs: Long, days: Int, timeZone: TimeZone): Long {
    val source = Calendar.getInstance(timeZone).apply { timeInMillis = timeMs }
    val hour = source.get(Calendar.HOUR_OF_DAY)
    val minute = source.get(Calendar.MINUTE)
    val second = source.get(Calendar.SECOND)
    val millisecond = source.get(Calendar.MILLISECOND)

    // Move a noon anchor to resolve the target calendar date independently of
    // offset changes, then rebuild the original wall clock on that date. Java's
    // lenient calendar moves a nonexistent spring-gap time forward (02:30 ->
    // 03:30) instead of silently pulling it back onto 01:30.
    val targetDate =
      Calendar.getInstance(timeZone).apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, days)
      }
    return Calendar.getInstance(timeZone)
      .apply {
        isLenient = true
        clear()
        set(
          targetDate.get(Calendar.YEAR),
          targetDate.get(Calendar.MONTH),
          targetDate.get(Calendar.DAY_OF_MONTH),
          hour,
          minute,
          second,
        )
        set(Calendar.MILLISECOND, millisecond)
      }
      .timeInMillis
  }

  /** A zone-independent ordinal for the local date fields of one instant. */
  private fun localDateOrdinal(timeMs: Long, timeZone: TimeZone): Long {
    val local = Calendar.getInstance(timeZone).apply { timeInMillis = timeMs }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"))
      .apply {
        clear()
        set(
          local.get(Calendar.YEAR),
          local.get(Calendar.MONTH),
          local.get(Calendar.DAY_OF_MONTH),
          0,
          0,
          0,
        )
      }
      .timeInMillis / DAY_MS
  }

  fun isSameDay(a: Long, b: Long, timeZone: TimeZone = TimeZone.getDefault()): Boolean =
    startOfDay(a, timeZone) == startOfDay(b, timeZone)

  /** Events bucketed by local day, days ordered ascending, events within a day by start. */
  fun groupByDay(
    events: List<BriefingEvent>,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): List<Pair<Long, List<BriefingEvent>>> =
    events
      .groupBy { startOfDay(it.startTime, timeZone) }
      .toSortedMap()
      .map { (day, items) -> day to items.sortedWith(compareBy({ it.startTime }, { it.title })) }

  const val DAY_MS: Long = 24L * 60 * 60 * 1000

  private fun MessageDigest.putBoolean(value: Boolean) {
    update((if (value) 1 else 0).toByte())
  }

  private fun MessageDigest.putInt(value: Int) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
  }

  private fun MessageDigest.putLong(value: Long) {
    update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
  }

  private fun MessageDigest.putString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    putInt(bytes.size)
    update(bytes)
  }

  private fun ByteArray.toHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
      for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(alphabet[value ushr 4])
        append(alphabet[value and 0x0f])
      }
    }
  }
}
