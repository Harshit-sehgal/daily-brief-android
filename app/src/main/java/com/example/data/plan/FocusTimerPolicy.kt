package com.example.data.plan

import com.example.data.model.PlanBlock

/**
 * A focus session is a countdown anchored to a plan block: it runs for the block's own length,
 * capped so a long block does not become an endless timer, and it cannot start for a block that
 * has already ended.
 *
 * Pure on purpose - the cap and the "already ended" rule are the two ways a focus timer can be
 * wrong, and both are arithmetic.
 */
data class FocusSpec(
  val blockId: String,
  val itemId: String,
  val title: String,
  val endAtMs: Long,
)

object FocusTimerPolicy {
  /** A session longer than this is capped; anything else is a full work block. */
  const val MAX_SESSION_MINUTES = 120

  fun specFor(block: PlanBlock, nowMs: Long, maxMinutes: Int = MAX_SESSION_MINUTES): FocusSpec? {
    if (block.endAt <= nowMs) return null
    val cappedEnd = minOf(block.endAt, nowMs + maxMinutes * 60_000L)
    if (cappedEnd <= nowMs) return null
    return FocusSpec(blockId = block.id, itemId = block.planItemId, title = "", endAtMs = cappedEnd)
  }

  /** The title is carried separately: a block has no title of its own, its item does. */
  fun withTitle(spec: FocusSpec, title: String): FocusSpec = spec.copy(title = title)
}