package com.example.core

/** Exact, saveable state for creating or editing one app-owned Plan block. */
data class GanttBlockDraft(
  val itemId: String,
  val blockId: String? = null,
  val startAt: Long,
  val endAt: Long,
  val locked: Boolean = false,
) {
  init {
    require(itemId.isNotBlank()) { "A Gantt block draft needs a task ID" }
  }
}

/** Pure time operations shared by visible buttons and any later keyboard or gesture input. */
object GanttBlockEditPolicy {
  const val STEP_MINUTES = 15

  fun moveByMinutes(draft: GanttBlockDraft, minutes: Int): GanttBlockDraft? {
    val delta = minuteDelta(minutes) ?: return null
    val start = add(draft.startAt, delta) ?: return null
    val end = add(draft.endAt, delta) ?: return null
    return draft.copy(startAt = start, endAt = end)
  }

  fun resizeEndByMinutes(draft: GanttBlockDraft, minutes: Int): GanttBlockDraft? {
    val delta = minuteDelta(minutes) ?: return null
    val end = add(draft.endAt, delta) ?: return null
    return end.takeIf { it > draft.startAt }?.let { draft.copy(endAt = it) }
  }

  fun resizeStartByMinutes(draft: GanttBlockDraft, minutes: Int): GanttBlockDraft? {
    val delta = minuteDelta(minutes) ?: return null
    val start = add(draft.startAt, delta) ?: return null
    return start.takeIf { it < draft.endAt }?.let { draft.copy(startAt = it) }
  }

  /** Moving a start field preserves the exact duration instead of silently resizing the block. */
  fun replaceStartPreservingDuration(
    draft: GanttBlockDraft,
    startAt: Long,
  ): GanttBlockDraft? {
    val duration = subtract(draft.endAt, draft.startAt) ?: return null
    val endAt = add(startAt, duration) ?: return null
    return draft.copy(startAt = startAt, endAt = endAt)
  }

  fun replaceEnd(draft: GanttBlockDraft, endAt: Long): GanttBlockDraft =
    draft.copy(endAt = endAt)

  fun replaceDurationMinutes(
    draft: GanttBlockDraft,
    durationMinutes: Int,
  ): GanttBlockDraft? {
    if (durationMinutes <= 0) return null
    val duration = minuteDelta(durationMinutes) ?: return null
    val endAt = add(draft.startAt, duration) ?: return null
    return draft.copy(endAt = endAt)
  }

  fun exactDurationMinutes(draft: GanttBlockDraft): Int? {
    val duration = subtract(draft.endAt, draft.startAt) ?: return null
    if (duration <= 0L || duration % MILLIS_PER_MINUTE != 0L) return null
    val minutes = duration / MILLIS_PER_MINUTE
    return minutes.takeIf { it <= Int.MAX_VALUE }?.toInt()
  }

  private fun minuteDelta(minutes: Int): Long? =
    runCatching { GuardedArithmetic.multiplyExact(minutes.toLong(), MILLIS_PER_MINUTE) }.getOrNull()

  private fun add(left: Long, right: Long): Long? =
    runCatching { GuardedArithmetic.addExact(left, right) }.getOrNull()

  private fun subtract(left: Long, right: Long): Long? =
    runCatching { GuardedArithmetic.subtractExact(left, right) }.getOrNull()

  private const val MILLIS_PER_MINUTE = 60_000L
}

enum class GanttDragTarget {
  BLOCK,
  START,
  END,
}

/** Complements real working intervals, retaining partial days, split shifts, exceptions and DST. */
object GanttWorkingBands {
  fun nonWorkingIntervals(
    spec: WorkingCalendarSpec,
    startInclusive: Long,
    endExclusive: Long,
  ): List<WorkingInterval> {
    require(endExclusive > startInclusive) { "A Gantt working-time range must end after it starts" }
    val working = WorkingCalendar.workingIntervals(spec, startInclusive, endExclusive)
    val result = mutableListOf<WorkingInterval>()
    var cursor = startInclusive
    working.forEach { interval ->
      if (interval.startAt > cursor) {
        result += WorkingInterval(cursor, interval.startAt)
      }
      cursor = maxOf(cursor, interval.endAt)
    }
    if (cursor < endExclusive) result += WorkingInterval(cursor, endExclusive)
    return result
  }
}