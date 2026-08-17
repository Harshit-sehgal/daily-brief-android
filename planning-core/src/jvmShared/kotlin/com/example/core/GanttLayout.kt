package com.example.core

import com.example.data.model.BriefingEvent
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Framework-independent geometry for a horizontally scrolling Gantt view.
 *
 * The output uses normalized positions so Compose, a PDF export, and tests can all consume the
 * same layout. Calendar concepts remain in epoch milliseconds until that final conversion: local
 * day boundaries are not a fixed 24 hours around daylight-saving transitions.
 */
object GanttLayout {

  /** A half-open visible interval: [startInclusiveMs, endExclusiveMs). */
  data class VisibleRange(
    val startInclusiveMs: Long,
    val endExclusiveMs: Long,
  ) {
    init {
      require(startInclusiveMs < endExclusiveMs) {
        "A Gantt range must end after it starts."
      }
    }

    /** Maps a timestamp onto the viewport and clamps off-screen values to its edges. */
    fun positionOf(timeMs: Long): Double {
      if (timeMs <= startInclusiveMs) return 0.0
      if (timeMs >= endExclusiveMs) return 1.0

      val offset = BigInteger.valueOf(timeMs).subtract(BigInteger.valueOf(startInclusiveMs))
      val span = BigInteger.valueOf(endExclusiveMs).subtract(BigInteger.valueOf(startInclusiveMs))
      return BigDecimal(offset)
        .divide(BigDecimal(span), MathContext.DECIMAL64)
        .toDouble()
        .coerceIn(0.0, 1.0)
    }

    /**
     * Maps a viewport position back to time, clamping overscroll and rounding to the nearest
     * millisecond. Big-integer arithmetic keeps narrow ranges stable even near Long's limits.
     */
    fun timeAt(position: Double): Long {
      require(position.isFinite()) { "A Gantt position must be finite." }
      val clamped = position.coerceIn(0.0, 1.0)
      if (clamped == 0.0) return startInclusiveMs
      if (clamped == 1.0) return endExclusiveMs

      val start = BigInteger.valueOf(startInclusiveMs)
      val span = BigInteger.valueOf(endExclusiveMs).subtract(start)
      val offset =
        BigDecimal(span)
          .multiply(BigDecimal.valueOf(clamped))
          .setScale(0, RoundingMode.HALF_UP)
          .toBigIntegerExact()
      // Clamping keeps the sum between the two Long endpoints, so this conversion
      // is exact without BigInteger.longValueExact(), which is Android API 31+.
      return start.add(offset).toLong()
    }

    fun contains(timeMs: Long): Boolean =
      timeMs >= startInclusiveMs && timeMs < endExclusiveMs
  }

  data class GroupKey(val board: String, val status: String)

  enum class ItemKind {
    BAR,
    MILESTONE,
  }

  /** One visible event row, clipped to [range] and ready for horizontal drawing. */
  data class Item(
    val event: BriefingEvent,
    val kind: ItemKind,
    val group: GroupKey,
    val rowIndex: Int,
    val startPosition: Double,
    val endPosition: Double,
    val clippedAtStart: Boolean,
    val clippedAtEnd: Boolean,
  )

  data class Group(val key: GroupKey, val items: List<Item>)

  data class Layout(val range: VisibleRange, val groups: List<Group>) {
    val items: List<Item> = groups.flatMap(Group::items)
  }

  enum class TickKind {
    DAY,
    MONTH,
    YEAR,
  }

  /** A local-midnight grid line. [stepDays] reports the zoom-safe stride actually selected. */
  data class Tick(
    val timeMs: Long,
    val position: Double,
    val kind: TickKind,
    val stepDays: Int,
  )

  /** The visible part of today's local calendar day, plus the current-time line when visible. */
  data class Today(
    val dayStartMs: Long,
    val nextDayStartMs: Long,
    val startPosition: Double,
    val endPosition: Double,
    val clippedAtStart: Boolean,
    val clippedAtEnd: Boolean,
    val nowPosition: Double?,
  )

  /**
   * Returns how an event can honestly be represented by the current schema.
   *
   * A duration-bearing deadline is still a bar. Only an explicit deadline that is already a
   * point in time becomes a milestone; zero-length ordinary events are not silently re-labelled.
   */
  fun kindOf(event: BriefingEvent): ItemKind? =
    when {
      event.endTime > event.startTime -> ItemKind.BAR
      event.endTime == event.startTime && event.isDeadline -> ItemKind.MILESTONE
      else -> null
    }

  /** Groups by board then status, with rows ordered by time and deterministic tie-breakers. */
  fun layout(events: List<BriefingEvent>, range: VisibleRange): Layout {
    val visible =
      events
        .mapNotNull { event ->
          val kind = kindOf(event) ?: return@mapNotNull null
          if (!isVisible(event, kind, range)) return@mapNotNull null
          event to kind
        }
        .sortedWith(
          compareBy<Pair<BriefingEvent, ItemKind>>(
            { it.first.kanbanBoard },
            { it.first.kanbanStatus },
            { it.first.startTime },
            { it.first.endTime },
            { it.first.title },
            { it.first.id },
          ),
        )

    var rowIndex = 0
    val groups =
      visible
        .groupByTo(linkedMapOf()) { (event, _) ->
          GroupKey(event.kanbanBoard, event.kanbanStatus)
        }
        .map { (key, entries) ->
          Group(
            key = key,
            items =
              entries.map { (event, kind) ->
                val visibleStart = maxOf(event.startTime, range.startInclusiveMs)
                val visibleEnd =
                  if (kind == ItemKind.MILESTONE) visibleStart
                  else minOf(event.endTime, range.endExclusiveMs)
                Item(
                  event = event,
                  kind = kind,
                  group = key,
                  rowIndex = rowIndex++,
                  startPosition = range.positionOf(visibleStart),
                  endPosition = range.positionOf(visibleEnd),
                  clippedAtStart = event.startTime < range.startInclusiveMs,
                  clippedAtEnd = event.endTime > range.endExclusiveMs,
                )
              },
          )
        }
    return Layout(range, groups)
  }

  /**
   * Local-midnight ticks, automatically coarsened until no more than [maximumTicks] are returned.
   * Callers can derive [maximumTicks] from their viewport width and desired label spacing.
   */
  fun dayTicks(
    range: VisibleRange,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    minimumStepDays: Int = 1,
    maximumTicks: Int = 256,
  ): List<Tick> {
    require(minimumStepDays > 0) { "Tick spacing must be at least one calendar day." }
    require(maximumTicks in 1..MAX_TICK_CAPACITY) {
      "The tick limit must be between 1 and $MAX_TICK_CAPACITY."
    }

    var stepDays = minimumStepDays
    while (true) {
      val instants = tickInstants(range, timeZone, stepDays, maximumTicks + 1)
      if (instants.size <= maximumTicks) {
        return instants.map { timeMs ->
          Tick(
            timeMs = timeMs,
            position = range.positionOf(timeMs),
            kind = tickKind(timeMs, timeZone),
            stepDays = stepDays,
          )
        }
      }
      check(stepDays <= Int.MAX_VALUE / 2) { "The requested Gantt range is too wide." }
      stepDays *= 2
    }
  }

  /** Number of labels that fit when width and spacing use the same arbitrary unit. */
  fun tickCapacity(viewportWidth: Double, minimumLabelSpacing: Double): Int {
    require(viewportWidth.isFinite() && viewportWidth > 0.0) {
      "Viewport width must be positive and finite."
    }
    require(minimumLabelSpacing.isFinite() && minimumLabelSpacing > 0.0) {
      "Tick label spacing must be positive and finite."
    }
    return (viewportWidth / minimumLabelSpacing).toInt().coerceIn(1, MAX_TICK_CAPACITY)
  }

  fun today(
    range: VisibleRange,
    nowMs: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): Today? {
    val dayStart = ScheduleAnalysis.startOfDay(nowMs, timeZone)
    val nextDayStart = ScheduleAnalysis.startOfDayOffset(dayStart, 1, timeZone)
    if (nextDayStart <= range.startInclusiveMs || dayStart >= range.endExclusiveMs) return null

    return Today(
      dayStartMs = dayStart,
      nextDayStartMs = nextDayStart,
      startPosition = range.positionOf(maxOf(dayStart, range.startInclusiveMs)),
      endPosition = range.positionOf(minOf(nextDayStart, range.endExclusiveMs)),
      clippedAtStart = dayStart < range.startInclusiveMs,
      clippedAtEnd = nextDayStart > range.endExclusiveMs,
      nowPosition = nowMs.takeIf(range::contains)?.let(range::positionOf),
    )
  }

  private fun isVisible(event: BriefingEvent, kind: ItemKind, range: VisibleRange): Boolean =
    if (kind == ItemKind.MILESTONE) {
      range.contains(event.startTime)
    } else {
      event.endTime > range.startInclusiveMs && event.startTime < range.endExclusiveMs
    }

  private fun tickInstants(
    range: VisibleRange,
    timeZone: TimeZone,
    stepDays: Int,
    limit: Int,
  ): List<Long> {
    var tick = ScheduleAnalysis.startOfDay(range.startInclusiveMs, timeZone)
    if (tick < range.startInclusiveMs) {
      tick = ScheduleAnalysis.startOfDayOffset(tick, 1, timeZone)
    }

    val result = mutableListOf<Long>()
    while (tick <= range.endExclusiveMs && result.size < limit) {
      result.add(tick)
      if (tick == range.endExclusiveMs) break
      val next = ScheduleAnalysis.startOfDayOffset(tick, stepDays, timeZone)
      check(next > tick) { "The time zone did not produce an increasing calendar day." }
      tick = next
    }
    return result
  }

  private fun tickKind(timeMs: Long, timeZone: TimeZone): TickKind {
    val local = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(timeZone)
    return when {
      local.dayOfMonth != 1 -> TickKind.DAY
      local.monthNumber == 1 -> TickKind.YEAR
      else -> TickKind.MONTH
    }
  }

  private const val MAX_TICK_CAPACITY = 10_000
}
