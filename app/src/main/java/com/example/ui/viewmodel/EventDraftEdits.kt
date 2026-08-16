package com.example.ui.viewmodel

import com.example.core.ScheduleAnalysis
import java.util.TimeZone

/**
 * Every edit the event sheet can make to a draft.
 *
 * Pure, because this is date arithmetic — the part of an editor that quietly
 * goes wrong across midnight, across a DST boundary, or when someone changes
 * their mind about an all-day event.
 */
object EventDraftEdits {

  /** Shortest event the sheet will produce when the user leaves no room. */
  private const val MIN_LENGTH_MS = 15 * 60_000L

  /**
   * Moves the whole event to another day, keeping its clock times and length.
   *
   * The picker hands back UTC midnight for the chosen date, which is a different
   * instant from local midnight almost everywhere.
   */
  fun movedToDay(
    draft: EventDraft,
    pickedUtcDayMs: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): EventDraft {
    val newDay = ScheduleAnalysis.localDayFromUtcMillis(pickedUtcDayMs, timeZone)
    if (draft.isAllDay) {
      return draft.copy(
        startMs = newDay,
        endMs = ScheduleAnalysis.startOfDayOffset(newDay, 1, timeZone),
      )
    }
    val length = (draft.endMs - draft.startMs).coerceAtLeast(0L)
    val start =
      ScheduleAnalysis.withTimeOfDay(
        newDay,
        ScheduleAnalysis.hourOf(draft.startMs, timeZone),
        ScheduleAnalysis.minuteOf(draft.startMs, timeZone),
        timeZone,
      )
    return draft.copy(startMs = start, endMs = start + length)
  }

  /** Turns the draft into a whole-day entry, covering exactly one local day. */
  fun asAllDay(draft: EventDraft, timeZone: TimeZone = TimeZone.getDefault()): EventDraft {
    val day = ScheduleAnalysis.startOfDay(draft.startMs, timeZone)
    return draft.copy(
      startMs = day,
      endMs = ScheduleAnalysis.startOfDayOffset(day, 1, timeZone),
      isAllDay = true,
    )
  }

  /**
   * Turns all-day back off, restoring the clock times it replaced.
   *
   * Toggling a chip twice should leave you where you started. Without
   * [restoreStartMs] there is nothing to go back to, so a sensible morning
   * default stands in.
   */
  fun asTimed(
    draft: EventDraft,
    restoreStartMs: Long? = null,
    restoreEndMs: Long? = null,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): EventDraft {
    val day = ScheduleAnalysis.startOfDay(draft.startMs, timeZone)
    if (restoreStartMs == null || restoreEndMs == null || restoreEndMs <= restoreStartMs) {
      return draft.copy(
        startMs = ScheduleAnalysis.withTimeOfDay(day, 9, 0, timeZone),
        endMs = ScheduleAnalysis.withTimeOfDay(day, 10, 0, timeZone),
        isAllDay = false,
      )
    }
    // The day on the draft may have moved while it was all-day; keep that day
    // and bring back only the time of day and the length.
    val start =
      ScheduleAnalysis.withTimeOfDay(
        day,
        ScheduleAnalysis.hourOf(restoreStartMs, timeZone),
        ScheduleAnalysis.minuteOf(restoreStartMs, timeZone),
        timeZone,
      )
    val length = (restoreEndMs - restoreStartMs).coerceAtLeast(MIN_LENGTH_MS)
    return draft.copy(startMs = start, endMs = start + length, isAllDay = false)
  }

  /** Keeps the event's length when the start moves, so the end never precedes it. */
  fun withStart(
    draft: EventDraft,
    hour: Int,
    minute: Int,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): EventDraft {
    val length = (draft.endMs - draft.startMs).coerceAtLeast(MIN_LENGTH_MS)
    val start = ScheduleAnalysis.withTimeOfDay(draft.startMs, hour, minute, timeZone)
    return draft.copy(startMs = start, endMs = start + length)
  }

  /** An end at or before the start reads as "runs past midnight", not as a mistake. */
  fun withEnd(
    draft: EventDraft,
    hour: Int,
    minute: Int,
    timeZone: TimeZone = TimeZone.getDefault(),
  ): EventDraft {
    val sameDay = ScheduleAnalysis.withTimeOfDay(draft.startMs, hour, minute, timeZone)
    val end =
      if (sameDay > draft.startMs) sameDay
      else
        ScheduleAnalysis.withTimeOfDay(
          ScheduleAnalysis.startOfDayOffset(draft.startMs, 1, timeZone),
          hour,
          minute,
          timeZone,
        )
    return draft.copy(endMs = end)
  }
}
