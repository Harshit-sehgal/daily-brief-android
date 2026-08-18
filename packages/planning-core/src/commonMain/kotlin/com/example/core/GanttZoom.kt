package com.example.core

/**
 * Zooming and overview geometry for the schedule map.
 *
 * A pinch is an accelerator for the 7/30/90 buttons, never a second model: it lands on the same
 * three ranges those buttons produce, so what a person zooms to is always a range they could have
 * chosen by tapping, and the buttons still report the current state afterwards.
 */
object GanttZoom {
  /** The ranges the visible buttons offer, widest last. */
  val Ranges = listOf(7, 30, 90)

  /**
   * The range a pinch lands on. Scale above 1 is a spread — fingers apart, less time on screen.
   *
   * Small movements are ignored so a two-finger scroll does not silently change the range.
   */
  fun rangeForScale(currentDays: Int, totalScale: Float): Int {
    val current = Ranges.indexOf(currentDays).takeIf { it >= 0 } ?: return Ranges.last()
    if (!totalScale.isFinite() || totalScale <= 0f) return Ranges[current]
    val step =
      when {
        totalScale >= ZOOM_IN_THRESHOLD -> -1
        totalScale <= ZOOM_OUT_THRESHOLD -> 1
        else -> 0
      }
    return Ranges[(current + step).coerceIn(0, Ranges.lastIndex)]
  }

  /**
   * The day a range should start at so the moment under the pinch stays under the pinch.
   *
   * Without this, zooming appears to teleport: the range changes width around its own start and
   * whatever a person was looking at slides off the screen.
   */
  fun focusPreservingStart(
    visibleStartMs: Long,
    visibleDays: Int,
    focusFraction: Float,
    nextDays: Int,
  ): Long {
    require(visibleDays > 0 && nextDays > 0) { "A Gantt range needs at least one day" }
    val fraction = focusFraction.coerceIn(0f, 1f).toDouble()
    val focusedMs = visibleStartMs + (visibleDays * DAY_MS * fraction).toLong()
    val nextStart = focusedMs - (nextDays * DAY_MS * fraction).toLong()
    return ScheduleAnalysis.startOfDay(nextStart)
  }

  /**
   * Where the visible range sits inside everything the plan covers, as 0..1 positions.
   *
   * Returns null when there is nothing to overview — an empty plan gets no minimap rather than an
   * empty rectangle implying work exists somewhere off-screen.
   */
  fun overview(
    planStartMs: Long?,
    planEndMs: Long?,
    visibleStartMs: Long,
    visibleEndMs: Long,
  ): GanttOverview? {
    if (planStartMs == null || planEndMs == null || planEndMs <= planStartMs) return null
    // The overview always contains the visible range, so the viewport marker cannot leave the strip.
    val start = minOf(planStartMs, visibleStartMs)
    val end = maxOf(planEndMs, visibleEndMs)
    val span = (end - start).toDouble()
    if (span <= 0.0) return null
    return GanttOverview(
      startMs = start,
      endMs = end,
      viewportStart = ((visibleStartMs - start) / span).coerceIn(0.0, 1.0),
      viewportEnd = ((visibleEndMs - start) / span).coerceIn(0.0, 1.0),
      planStart = ((planStartMs - start) / span).coerceIn(0.0, 1.0),
      planEnd = ((planEndMs - start) / span).coerceIn(0.0, 1.0),
    )
  }

  /** The day a tap at [fraction] of the overview should bring into view, centred on that point. */
  fun dayForOverviewTap(overview: GanttOverview, fraction: Float, visibleDays: Int): Long {
    require(visibleDays > 0) { "A Gantt range needs at least one day" }
    val span = (overview.endMs - overview.startMs).toDouble()
    val target = overview.startMs + (span * fraction.coerceIn(0f, 1f)).toLong()
    return ScheduleAnalysis.startOfDay(target - visibleDays * DAY_MS / 2)
  }

  private const val DAY_MS = 24 * 60 * 60 * 1000L
  private const val ZOOM_IN_THRESHOLD = 1.35f
  private const val ZOOM_OUT_THRESHOLD = 0.75f
}

data class GanttOverview(
  val startMs: Long,
  val endMs: Long,
  val viewportStart: Double,
  val viewportEnd: Double,
  val planStart: Double,
  val planEnd: Double,
)
