package com.example.core

import kotlin.math.roundToLong

/**
 * Pure conversion between pointer movement and an exact Plan-block preview.
 *
 * A gesture never owns persistence. It can only derive a 15-minute-aligned draft inside the
 * currently visible range; the caller retains the last valid draft and commits it on Apply. This
 * is px/dp touch geometry, so it lives in the app rather than in the scheduling engine — the
 * engine keeps only the time policy in `GanttBlockEditPolicy`.
 */
object GanttDirectManipulationPolicy {
  fun preview(
    origin: GanttBlockDraft,
    target: GanttDragTarget,
    deltaPixels: Float,
    canvasWidthPixels: Float,
    rangeStartInclusive: Long,
    rangeEndExclusive: Long,
  ): GanttBlockDraft? {
    val deltaMinutes =
      snappedDeltaMinutes(
        deltaPixels = deltaPixels,
        canvasWidthPixels = canvasWidthPixels,
        rangeStartInclusive = rangeStartInclusive,
        rangeEndExclusive = rangeEndExclusive,
      ) ?: return null
    return adjustByMinutes(
      origin = origin,
      target = target,
      deltaMinutes = deltaMinutes,
      rangeStartInclusive = rangeStartInclusive,
      rangeEndExclusive = rangeEndExclusive,
    )
  }

  fun adjustByMinutes(
    origin: GanttBlockDraft,
    target: GanttDragTarget,
    deltaMinutes: Int,
    rangeStartInclusive: Long,
    rangeEndExclusive: Long,
  ): GanttBlockDraft? {
    if (
      rangeEndExclusive <= rangeStartInclusive ||
        deltaMinutes % GanttBlockEditPolicy.STEP_MINUTES != 0
    ) {
      return null
    }
    val candidate =
      when (target) {
        GanttDragTarget.BLOCK -> GanttBlockEditPolicy.moveByMinutes(origin, deltaMinutes)
        GanttDragTarget.START -> GanttBlockEditPolicy.resizeStartByMinutes(origin, deltaMinutes)
        GanttDragTarget.END -> GanttBlockEditPolicy.resizeEndByMinutes(origin, deltaMinutes)
      } ?: return null
    return candidate.takeIf {
      it.startAt >= rangeStartInclusive && it.endAt <= rangeEndExclusive
    }
  }

  fun snappedDeltaMinutes(
    deltaPixels: Float,
    canvasWidthPixels: Float,
    rangeStartInclusive: Long,
    rangeEndExclusive: Long,
  ): Int? {
    if (!deltaPixels.isFinite() || !canvasWidthPixels.isFinite() || canvasWidthPixels <= 0f) {
      return null
    }
    val rangeMillis =
      runCatching { Math.subtractExact(rangeEndExclusive, rangeStartInclusive) }.getOrNull()
        ?.takeIf { it > 0L } ?: return null
    val rawSteps =
      deltaPixels.toDouble() / canvasWidthPixels.toDouble() *
        rangeMillis.toDouble() / MILLIS_PER_MINUTE / GanttBlockEditPolicy.STEP_MINUTES
    if (!rawSteps.isFinite()) return null
    val steps = rawSteps.roundToLong()
    val minutes =
      runCatching { Math.multiplyExact(steps, GanttBlockEditPolicy.STEP_MINUTES.toLong()) }
        .getOrNull() ?: return null
    return minutes.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
  }

  /**
   * Returns a small synchronous scroll request only inside a measured viewport and clamps it to
   * the real ScrollState bounds. Unknown or inconsistent geometry deliberately yields no scroll.
   */
  fun boundedAutoScrollDelta(
    pointerViewportX: Float,
    viewportWidthPixels: Float,
    currentScrollPixels: Float,
    maximumScrollPixels: Float,
    edgeWidthPixels: Float,
    maximumStepPixels: Float,
  ): Float {
    if (
      !pointerViewportX.isFinite() ||
        !viewportWidthPixels.isFinite() ||
        !currentScrollPixels.isFinite() ||
        !maximumScrollPixels.isFinite() ||
        !edgeWidthPixels.isFinite() ||
        !maximumStepPixels.isFinite() ||
        viewportWidthPixels <= edgeWidthPixels * 2f ||
        edgeWidthPixels <= 0f ||
        maximumStepPixels <= 0f ||
        currentScrollPixels < 0f ||
        maximumScrollPixels < currentScrollPixels
    ) {
      return 0f
    }
    val requested =
      when {
        pointerViewportX < edgeWidthPixels -> {
          val pressure = ((edgeWidthPixels - pointerViewportX) / edgeWidthPixels).coerceIn(0f, 1f)
          -maximumStepPixels * pressure
        }
        pointerViewportX > viewportWidthPixels - edgeWidthPixels -> {
          val pressure =
            ((pointerViewportX - (viewportWidthPixels - edgeWidthPixels)) / edgeWidthPixels)
              .coerceIn(0f, 1f)
          maximumStepPixels * pressure
        }
        else -> 0f
      }
    return requested.coerceIn(-currentScrollPixels, maximumScrollPixels - currentScrollPixels)
  }

  private const val MILLIS_PER_MINUTE = 60_000.0
}

/**
 * Where the move target and its two resize handles sit, in dp along the Gantt canvas.
 *
 * Handles are drawn above the bar, so placing all three at their natural positions hides the
 * move target behind them: a one-hour block is under 3 dp wide at the 7-day zoom, while every
 * target must stay 48 dp. These regions are therefore laid out as neighbours instead — the move
 * target keeps the bar centred and the handles flank it — so a drag always reaches the command
 * the user aimed at.
 */
data class GanttManipulationTargets(
  val moveStartDp: Float,
  val moveWidthDp: Float,
  val startHandleStartDp: Float?,
  val endHandleStartDp: Float?,
  val handleDp: Float,
)

object GanttDirectManipulationTargets {
  /**
   * Returns three non-overlapping regions inside the canvas, or null when the geometry is unusable
   * — the caller then keeps the labelled increment and keyboard commands, which never depend on
   * pointer geometry.
   */
  fun compute(
    barStartDp: Float,
    barEndDp: Float,
    canvasWidthDp: Float,
    handleDp: Float,
    minimumMoveDp: Float,
  ): GanttManipulationTargets? {
    if (
      !barStartDp.isFinite() ||
        !barEndDp.isFinite() ||
        !canvasWidthDp.isFinite() ||
        !handleDp.isFinite() ||
        !minimumMoveDp.isFinite() ||
        barEndDp < barStartDp ||
        canvasWidthDp <= 0f ||
        handleDp <= 0f ||
        minimumMoveDp <= 0f
    ) {
      return null
    }
    val barWidth = barEndDp - barStartDp
    val centre = barStartDp + barWidth / 2f
    // Handles only earn their space when the canvas can still hold a full-size move target.
    val handlesFit = canvasWidthDp >= minimumMoveDp + handleDp * 2f
    val available = if (handlesFit) canvasWidthDp - handleDp * 2f else canvasWidthDp
    val moveWidth = maxOf(barWidth, minimumMoveDp).coerceAtMost(available)
    val lowestStart = if (handlesFit) handleDp else 0f
    val highestStart = (canvasWidthDp - moveWidth - if (handlesFit) handleDp else 0f)
    val moveStart = (centre - moveWidth / 2f).coerceIn(lowestStart, maxOf(lowestStart, highestStart))
    return GanttManipulationTargets(
      moveStartDp = moveStart,
      moveWidthDp = moveWidth,
      startHandleStartDp = if (handlesFit) moveStart - handleDp else null,
      endHandleStartDp = if (handlesFit) moveStart + moveWidth else null,
      handleDp = handleDp,
    )
  }
}