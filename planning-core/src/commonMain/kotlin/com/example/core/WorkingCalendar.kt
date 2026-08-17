package com.example.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

data class WorkingDayWindow(val startMinute: Int, val endMinute: Int)

data class WorkingWeekWindow(
  val dayOfWeek: Int,
  val startMinute: Int,
  val endMinute: Int,
)

/** An empty window list deliberately closes a date that would otherwise use the weekly pattern. */
data class WorkingDateOverride(
  val localDate: String,
  val windows: List<WorkingDayWindow>,
)

data class WorkingCalendarSpec(
  val zoneId: String,
  val weeklyWindows: List<WorkingWeekWindow>,
  val overrides: List<WorkingDateOverride> = emptyList(),
  val minimumChunkMinutes: Int = 30,
  val maximumChunkMinutes: Int = 120,
  val bufferMinutes: Int = 0,
)

data class WorkingInterval(val startAt: Long, val endAt: Long) {
  init {
    require(endAt > startAt) { "A working interval must end after it starts" }
  }

  val durationMinutes: Int
    get() = ((endAt - startAt) / MILLIS_PER_MINUTE).toInt()
}

data class WorkingPlan(
  val blocks: List<WorkingInterval>,
  val requestedMinutes: Int,
  val scheduledMinutes: Int,
  val unscheduledMinutes: Int,
  val explanation: String,
) {
  val fits: Boolean
    get() = unscheduledMinutes == 0
}

/**
 * Wall-clock availability and deterministic placement for app-owned Plan work.
 *
 * Fixed calendar commitments enter only as [busy] intervals. This object never rewrites those
 * commitments and never performs a database mutation, so scheduling can be previewed before Apply.
 *
 * Two things survived the move off `java.util.Calendar` unchanged, because both are stored or
 * pinned rather than internal: weekday numbers keep `Calendar`'s Sunday-is-1 convention (they sit
 * in `work_schedule_windows.dayOfWeek` in every existing database), and daylight-saving edges keep
 * `Calendar`'s resolution — see [AmbiguousLocalTime].
 */
object WorkingCalendar {
  fun validate(spec: WorkingCalendarSpec) {
    require(spec.zoneId in TimeZone.availableZoneIds) { "Unknown working-calendar time zone" }
    require(spec.minimumChunkMinutes in 1..MINUTES_PER_DAY) {
      "Minimum work chunk must be between 1 minute and 24 hours"
    }
    require(spec.maximumChunkMinutes in spec.minimumChunkMinutes..MINUTES_PER_DAY) {
      "Maximum work chunk must not be shorter than the minimum"
    }
    require(spec.bufferMinutes in 0 until MINUTES_PER_DAY) {
      "Working-calendar buffer must be shorter than one day"
    }

    spec.weeklyWindows.forEach { window ->
      require(window.dayOfWeek in CALENDAR_SUNDAY..CALENDAR_SATURDAY) {
        "Working-calendar weekday is invalid"
      }
      validateWindow(window.startMinute, window.endMinute)
    }
    ensureNoOverlap(
      spec.weeklyWindows.groupBy { it.dayOfWeek }.mapValues { (_, windows) ->
        windows.map { WorkingDayWindow(it.startMinute, it.endMinute) }
      }
    )

    require(spec.overrides.map { it.localDate }.distinct().size == spec.overrides.size) {
      "A working-calendar date can have only one override"
    }
    spec.overrides.forEach { override ->
      require(isExactLocalDate(override.localDate)) {
        "Working-calendar override date must use YYYY-MM-DD"
      }
      override.windows.forEach { validateWindow(it.startMinute, it.endMinute) }
      ensureNoOverlap(mapOf(override.localDate to override.windows))
    }
  }

  fun workingIntervals(
    spec: WorkingCalendarSpec,
    startInclusive: Long,
    endExclusive: Long,
  ): List<WorkingInterval> {
    require(endExclusive > startInclusive) { "Working-calendar range must end after it starts" }
    validate(spec)
    val zone = TimeZone.of(spec.zoneId)
    val overrideByDate = spec.overrides.associateBy { it.localDate }
    val weeklyByDay =
      spec.weeklyWindows
        .groupBy { it.dayOfWeek }
        .mapValues { (_, windows) ->
          windows
            .map { WorkingDayWindow(it.startMinute, it.endMinute) }
            .sortedBy { it.startMinute }
        }
    var date = localDay(startInclusive, zone)
    val result = mutableListOf<WorkingInterval>()

    // Walk local days, never fixed millisecond steps: a DST day is 23 or 25 hours long.
    while (date.atStartOfDayIn(zone).toEpochMilliseconds() < endExclusive) {
      val key = date.toString()
      val windows = overrideByDate[key]?.windows ?: weeklyByDay[date.calendarDayOfWeek()].orEmpty()
      windows.sortedBy { it.startMinute }.forEach { window ->
        val start = atLocalMinute(date, window.startMinute, zone)
        val end = atLocalMinute(date, window.endMinute, zone)
        val clippedStart = maxOf(start, startInclusive)
        val clippedEnd = minOf(end, endExclusive)
        if (clippedEnd > clippedStart) result += WorkingInterval(clippedStart, clippedEnd)
      }
      date = date.plus(1, DateTimeUnit.DAY)
    }
    return merge(result)
  }

  fun freeIntervals(
    spec: WorkingCalendarSpec,
    startInclusive: Long,
    endExclusive: Long,
    busy: List<WorkingInterval>,
  ): List<WorkingInterval> {
    val bufferMs = spec.bufferMinutes * MILLIS_PER_MINUTE
    val blocked =
      merge(
        busy.mapNotNull { interval ->
          val start = maxOf(startInclusive, interval.startAt - bufferMs)
          val end = minOf(endExclusive, interval.endAt + bufferMs)
          if (end > start) WorkingInterval(start, end) else null
        }
      )
    return workingIntervals(spec, startInclusive, endExclusive).flatMap { window ->
      subtract(window, blocked)
    }
  }

  fun capacityMinutes(
    spec: WorkingCalendarSpec,
    startInclusive: Long,
    endExclusive: Long,
    busy: List<WorkingInterval>,
  ): Int = freeIntervals(spec, startInclusive, endExclusive, busy).sumOf { it.durationMinutes }

  /**
   * Places work chronologically without hidden trade-offs. The result is a preview; callers decide
   * whether to apply it. A final remainder smaller than the minimum is allowed only when it is all
   * that remains, avoiding a false "cannot fit" for a short task.
   */
  fun propose(
    spec: WorkingCalendarSpec,
    rangeStart: Long,
    rangeEnd: Long,
    effortMinutes: Int,
    busy: List<WorkingInterval>,
    notBefore: Long? = null,
    dueAt: Long? = null,
  ): WorkingPlan {
    require(effortMinutes > 0) { "Planned effort must be positive" }
    val usableStart = maxOf(rangeStart, notBefore ?: rangeStart)
    val usableEnd = minOf(rangeEnd, dueAt ?: rangeEnd)
    if (usableEnd <= usableStart) {
      return WorkingPlan(
        blocks = emptyList(),
        requestedMinutes = effortMinutes,
        scheduledMinutes = 0,
        unscheduledMinutes = effortMinutes,
        explanation = "No working time exists between the start constraint and deadline.",
      )
    }

    var remaining = effortMinutes
    val blocks = mutableListOf<WorkingInterval>()
    freeIntervals(spec, usableStart, usableEnd, busy).forEach { free ->
      if (remaining == 0) return@forEach
      val available = free.durationMinutes
      val wanted = minOf(remaining, spec.maximumChunkMinutes, available)
      val finalRemainder = remaining <= spec.minimumChunkMinutes
      if (wanted < spec.minimumChunkMinutes && !finalRemainder) return@forEach
      val minutes = minOf(wanted, remaining)
      if (minutes <= 0) return@forEach
      blocks += WorkingInterval(free.startAt, free.startAt + minutes * MILLIS_PER_MINUTE)
      remaining -= minutes
    }

    val scheduled = effortMinutes - remaining
    val explanation =
      when {
        remaining == 0 && blocks.size == 1 ->
          "The task fits in one working-time block without moving a fixed commitment."
        remaining == 0 ->
          "The task fits in ${blocks.size} working-time blocks around fixed commitments."
        scheduled == 0 ->
          "No free working-time block meets the minimum chunk size."
        else ->
          "$scheduled of $effortMinutes minutes fit before the deadline; $remaining minutes remain."
      }
    return WorkingPlan(blocks, effortMinutes, scheduled, remaining, explanation)
  }

  private fun validateWindow(startMinute: Int, endMinute: Int) {
    require(startMinute in 0 until MINUTES_PER_DAY) {
      "Working window must start within the local day"
    }
    require(endMinute in 1..MINUTES_PER_DAY && endMinute > startMinute) {
      "Working window must end after it starts"
    }
  }

  private fun ensureNoOverlap(groups: Map<*, List<WorkingDayWindow>>) {
    groups.values.forEach { windows ->
      windows.sortedBy { it.startMinute }.zipWithNext().forEach { (left, right) ->
        require(left.endMinute <= right.startMinute) { "Working windows cannot overlap" }
      }
    }
  }

  private fun localDay(epochMs: Long, zone: TimeZone): LocalDate =
    kotlin.time.Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

  /**
   * Stored weekday numbers are `java.util.Calendar`'s — Sunday is 1 — and they are in every
   * existing `work_schedule_windows` row, so the ISO numbering has to be translated rather than
   * adopted. ISO Monday 1 becomes 2, ISO Sunday 7 becomes 1.
   */
  private fun LocalDate.calendarDayOfWeek(): Int = dayOfWeek.isoDayNumber % 7 + 1

  /**
   * Minute-of-local-day to an instant, with [AmbiguousLocalTime] deciding the daylight-saving
   * edges. Minute 1440 is midnight *tomorrow*, which is how a window may end at 24:00.
   */
  private fun atLocalMinute(date: LocalDate, minute: Int, zone: TimeZone): Long {
    val day = if (minute == MINUTES_PER_DAY) date.plus(1, DateTimeUnit.DAY) else date
    val minuteOfDay = if (minute == MINUTES_PER_DAY) 0 else minute
    val local = LocalDateTime(day.year, day.month, day.day, minuteOfDay / 60, minuteOfDay % 60)
    return AmbiguousLocalTime.resolve(local, zone).toEpochMilliseconds()
  }

  private fun subtract(
    window: WorkingInterval,
    blocked: List<WorkingInterval>,
  ): List<WorkingInterval> {
    var cursor = window.startAt
    val result = mutableListOf<WorkingInterval>()
    blocked.forEach { busy ->
      if (busy.endAt <= cursor || busy.startAt >= window.endAt) return@forEach
      if (busy.startAt > cursor) {
        result += WorkingInterval(cursor, minOf(busy.startAt, window.endAt))
      }
      cursor = maxOf(cursor, busy.endAt)
    }
    if (cursor < window.endAt) result += WorkingInterval(cursor, window.endAt)
    return result
  }

  private fun merge(intervals: List<WorkingInterval>): List<WorkingInterval> {
    if (intervals.isEmpty()) return emptyList()
    val result = mutableListOf<WorkingInterval>()
    intervals.sortedBy { it.startAt }.forEach { interval ->
      val previous = result.lastOrNull()
      if (previous == null || interval.startAt > previous.endAt) result += interval
      else {
        result[result.lastIndex] =
          WorkingInterval(previous.startAt, maxOf(previous.endAt, interval.endAt))
      }
    }
    return result
  }

  private fun isExactLocalDate(value: String): Boolean {
    if (!EXACT_LOCAL_DATE.matches(value)) return false
    // The regex accepts 2026-13-45; LocalDate does not, which is the real check.
    return runCatching { LocalDate.parse(value) }.isSuccess
  }
}

private const val MINUTES_PER_DAY = 24 * 60
private const val MILLIS_PER_MINUTE = 60_000L
private const val CALENDAR_SUNDAY = 1
private const val CALENDAR_SATURDAY = 7
private val EXACT_LOCAL_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
