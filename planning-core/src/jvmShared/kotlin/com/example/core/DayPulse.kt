package com.example.core

import com.example.data.model.BriefingEvent
import kotlinx.datetime.TimeZone

/**
 * The state of a day at a moment in time.
 *
 * The landing page exists to answer three questions — what am I in, what is next,
 * and how much of the day is still ahead — and none of those are answerable from
 * a list of events without doing this arithmetic first. Keeping it here means the
 * answers are testable and the screen stays a rendering of them.
 */
object DayPulse {

  data class Snapshot(
    /** Running right now, if anything is. */
    val current: BriefingEvent?,
    /** The next thing to start today, if anything is left. */
    val next: BriefingEvent?,
    /** Minutes until [next] starts. */
    val minutesToNext: Int?,
    /** Minutes until [current] ends. */
    val minutesLeft: Int?,
    /** Timed events that have not finished yet. */
    val upcoming: List<BriefingEvent>,
    /** All-day entries, which have no place in a countdown. */
    val allDay: List<BriefingEvent>,
    val finished: Int,
    val total: Int,
    val bookedMinutes: Int,
    /** Booked minutes already behind you, as a fraction. Zero when nothing is booked. */
    val progress: Float,
    /** Clear minutes between now and [next]. Null when nothing is next. */
    val freeMinutes: Int?,
    /** True when the day being described is the one actually happening. */
    val isLive: Boolean,
  ) {
    val isClear: Boolean
      get() = total == 0

    val isDone: Boolean
      get() = total > 0 && current == null && next == null
  }

  /**
   * @param dayStart local midnight of the day being described
   * @param nowMs the current instant; a day that is not [dayStart]'s produces a
   *   snapshot with no live parts, which is what lets the page describe tomorrow
   * @param timeZone zone whose calendar date [dayStart] represents
   */
  fun of(
    events: List<BriefingEvent>,
    nowMs: Long,
    dayStart: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): Snapshot {
    val dayEnd = ScheduleAnalysis.startOfDayOffset(dayStart, 1, timeZone)
    val live = nowMs in dayStart until dayEnd

    val allDay = events.filter { ScheduleAnalysis.isAllDay(it) }.sortedBy { it.title }
    val timed =
      events
        .filterNot { ScheduleAnalysis.isAllDay(it) }
        .filter { it.endTime > dayStart && it.startTime < dayEnd }
        .sortedWith(compareBy({ it.startTime }, { it.endTime }, { it.id }))

    val bookedMinutes =
      timed.sumOf { event ->
        val from = maxOf(event.startTime, dayStart)
        val to = minOf(event.endTime, dayEnd)
        ((to - from) / 60_000L).coerceAtLeast(0L)
      }

    // A day you are not currently in has no "now": describe its shape, not its clock.
    val reference = if (live) nowMs else dayStart

    val current =
      if (live) timed.firstOrNull { it.startTime <= nowMs && it.endTime > nowMs } else null
    val next = timed.firstOrNull { it.startTime > reference }
    val upcoming = timed.filter { it.endTime > reference }
    val finished = timed.count { it.endTime <= reference }

    val elapsedBooked =
      timed.sumOf { event ->
        val from = maxOf(event.startTime, dayStart)
        val to = minOf(event.endTime, reference)
        ((to - from) / 60_000L).coerceAtLeast(0L)
      }

    return Snapshot(
      current = current,
      next = next,
      minutesToNext = next?.let { ceilMinutes(it.startTime - reference) },
      minutesLeft = current?.let { ceilMinutes(it.endTime - nowMs) },
      upcoming = upcoming,
      allDay = allDay,
      finished = finished,
      total = timed.size,
      bookedMinutes = bookedMinutes.toInt(),
      progress = if (bookedMinutes == 0L) 0f else (elapsedBooked.toFloat() / bookedMinutes),
      // Only clear time counts as free: mid-event, the gap is zero, not negative.
      freeMinutes =
        next?.let { if (current != null) 0 else ceilMinutes(it.startTime - reference) },
      isLive = live,
    )
  }

  /** Rounds up, so "1 minute left" never reads as "0 minutes left". */
  private fun ceilMinutes(millis: Long): Int {
    if (millis <= 0L) return 0
    return ((millis + 59_999L) / 60_000L).toInt()
  }

  /** "45m", "2h", "2h 10m" — a duration at a glance, never a bare minute count. */
  fun humanDuration(minutes: Int): String {
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
  }
}
