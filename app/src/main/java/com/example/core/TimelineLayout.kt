package com.example.core

import com.example.data.model.BriefingEvent

/**
 * Places events side by side on a day timeline.
 *
 * The rule every calendar app follows: events that overlap share the width of
 * their cluster, and a cluster is a run of events transitively connected by
 * overlap — so A/B overlapping and B/C overlapping puts all three in one cluster
 * even when A and C do not touch. Getting that wrong is what makes home-grown
 * timelines render blocks on top of each other.
 *
 * Pure and independent of Compose so the packing can be tested directly.
 */
object TimelineLayout {

  /** One event's place in the grid: which lane it sits in, out of how many. */
  data class Slot(
    val event: BriefingEvent,
    val lane: Int,
    val lanes: Int,
    val startMinute: Int,
    val endMinute: Int,
  )

  private const val MINUTES_PER_DAY = 24 * 60

  /** Shortest block that stays readable and tappable. */
  const val MIN_VISIBLE_MINUTES = 20

  /**
   * @param dayStart local midnight of the day being drawn
   * @return timed events only; all-day entries are the caller's problem to banner
   */
  fun layout(events: List<BriefingEvent>, dayStart: Long): List<Slot> {
    val dayEnd = dayStart + ScheduleAnalysis.DAY_MS

    val placed =
      events
        .filterNot { ScheduleAnalysis.isAllDay(it) }
        .filter { it.endTime > dayStart && it.startTime < dayEnd }
        .map { event ->
          // Clamp to the day so an event running past midnight still draws sanely.
          val startMin =
            (((event.startTime - dayStart) / 60_000L).toInt()).coerceIn(0, MINUTES_PER_DAY)
          val rawEnd = (((event.endTime - dayStart) / 60_000L).toInt()).coerceIn(0, MINUTES_PER_DAY)
          val endMin = maxOf(rawEnd, startMin + MIN_VISIBLE_MINUTES).coerceAtMost(MINUTES_PER_DAY)
          Triple(event, startMin, endMin)
        }
        .sortedWith(compareBy({ it.second }, { it.third }, { it.first.id }))

    if (placed.isEmpty()) return emptyList()

    val slots = mutableListOf<Slot>()
    var cluster = mutableListOf<Triple<BriefingEvent, Int, Int>>()
    var clusterEnd = -1

    fun flush() {
      if (cluster.isEmpty()) return
      // Greedy lane assignment: reuse the first lane whose last event has finished.
      val laneEnds = mutableListOf<Int>()
      val assigned = mutableListOf<Pair<Triple<BriefingEvent, Int, Int>, Int>>()
      cluster.forEach { item ->
        val lane = laneEnds.indexOfFirst { it <= item.second }
        val index =
          if (lane >= 0) {
            laneEnds[lane] = item.third
            lane
          } else {
            laneEnds.add(item.third)
            laneEnds.lastIndex
          }
        assigned.add(item to index)
      }
      val lanes = laneEnds.size
      assigned.forEach { (item, lane) ->
        slots.add(Slot(item.first, lane, lanes, item.second, item.third))
      }
      cluster = mutableListOf()
      clusterEnd = -1
    }

    placed.forEach { item ->
      if (cluster.isNotEmpty() && item.second >= clusterEnd) flush()
      cluster.add(item)
      clusterEnd = maxOf(clusterEnd, item.third)
    }
    flush()

    return slots.sortedWith(compareBy({ it.startMinute }, { it.lane }))
  }

  /** All-day entries, which belong in a banner above the grid rather than in it. */
  fun allDay(events: List<BriefingEvent>): List<BriefingEvent> =
    events.filter { ScheduleAnalysis.isAllDay(it) }.sortedBy { it.title }

  /** Minute of the day, or null when [nowMs] is not inside this day. */
  fun nowMinute(nowMs: Long, dayStart: Long): Int? {
    val offset = nowMs - dayStart
    if (offset < 0 || offset >= ScheduleAnalysis.DAY_MS) return null
    return (offset / 60_000L).toInt()
  }

  /** Snaps a dragged minute to the nearest [step], kept inside the day. */
  fun snapMinute(minute: Int, step: Int = 15, durationMinutes: Int = 0): Int {
    val snapped = ((minute + step / 2) / step) * step
    return snapped.coerceIn(0, MINUTES_PER_DAY - durationMinutes.coerceAtLeast(0))
  }
}
