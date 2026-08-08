package com.example.core

import com.example.data.model.BriefingEvent
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure schedule maths, shared by the UI, the AI prompt builder and the
 * notification receiver. Previously each of those carried its own copy of the
 * overlap loop; they are all this one now.
 */
object ScheduleAnalysis {

  /** Anything spanning most of a day behaves as a banner, not a booked slot. */
  const val ALL_DAY_THRESHOLD_MS: Long = 20L * 60 * 60 * 1000

  data class Conflict(val first: BriefingEvent, val second: BriefingEvent) {
    /** Milliseconds the two events actually share. */
    val overlapMs: Long
      get() = minOf(first.endTime, second.endTime) - maxOf(first.startTime, second.startTime)
  }

  data class DayStats(val total: Int, val conflicts: Int, val urgent: Int, val bookedMinutes: Int)

  fun isAllDay(event: BriefingEvent): Boolean =
    event.endTime - event.startTime >= ALL_DAY_THRESHOLD_MS

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

  fun statsFor(events: List<BriefingEvent>): DayStats {
    val conflicts = findConflicts(events)
    val urgent = events.count { it.isUrgent || it.isDeadline }
    val booked =
      events.filterNot { isAllDay(it) }.sumOf { maxOf(0L, it.endTime - it.startTime) } /
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
  fun signature(events: List<BriefingEvent>): String =
    events
      .sortedBy { it.id }
      .joinToString("|") {
        "${it.id}:${it.startTime}:${it.endTime}:${it.title}:${it.isUrgent}:${it.isDeadline}"
      }
      .hashCode()
      .toString()

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

  fun hourOf(timeMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Int =
    Calendar.getInstance(timeZone).apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)

  fun minuteOf(timeMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Int =
    Calendar.getInstance(timeZone).apply { timeInMillis = timeMs }.get(Calendar.MINUTE)

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
}
