package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityAnswerTest {
  private val dayStart = at(2026, Calendar.AUGUST, 10, 0, 0) // a Monday
  private val hour = 3_600_000L
  private val week = 7 * 24 * hour
  private val rangeStart = dayStart
  private val rangeEnd = dayStart + week

  private fun health(
    items: List<PlanItem>,
    blocks: List<PlanBlock> = emptyList(),
    commitments: List<WorkingInterval> = emptyList(),
    spec: WorkingCalendarSpec = weekdays(),
  ): PlanHealthResult =
    MultiSchedulePlanHealth.evaluate(
      schedules = mapOf("s" to spec),
      scheduleIdByItemId = items.associate { it.id to "s" },
      rangeStart = rangeStart,
      rangeEnd = rangeEnd,
      now = rangeStart,
      items = items,
      blocks = blocks,
      fixedCommitments = commitments,
    )

  /** Monday–Friday 09:00–17:00, 30–120 minute chunks. */
  private fun weekdays() =
    WorkingCalendarSpec(
      zoneId = "UTC",
      weeklyWindows =
        (Calendar.MONDAY..Calendar.FRIDAY).map { WorkingWeekWindow(it, 9 * 60, 17 * 60) },
      minimumChunkMinutes = 30,
      maximumChunkMinutes = 120,
    )

  private fun item(
    id: String,
    effort: Int?,
    rank: Long = 1,
    dueAt: Long? = null,
    title: String = id,
  ) =
    PlanItem(
      id = id,
      boardId = "board",
      title = title,
      rank = rank,
      effortMinutes = effort,
      dueAt = dueAt,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(id: String, itemId: String, day: Int, startHour: Int, endHour: Int) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = at(2026, Calendar.AUGUST, day, startHour, 0),
      endAt = at(2026, Calendar.AUGUST, day, endHour, 0),
      createdAt = 1,
      updatedAt = 1,
    )

  @Test
  fun `an empty week takes a full-time client`() {
    val result =
      CapacityAnswer.answer(
        health(items = listOf(item("t1", 90))),
        newClientHoursPerWeek = 8,
        rangeEnd = rangeEnd,
      )

    assertEquals(CapacityVerdict.CAN_TAKE, result.verdict)
    assertEquals(2400, result.availableMinutes)
    assertEquals(90, result.plannedMinutes)
    assertEquals(2400, result.spareMinutes)
    assertTrue(result.sentence.contains("an 8 h/week client fits"))
  }

  /** Monday–Friday 09:00–16:00, leaving 16:00–17:00 spare each day. */
  private fun afternoonCommitments(): List<WorkingInterval> =
    (10..14).map { d ->
      WorkingInterval(at(2026, Calendar.AUGUST, d, 9, 0), at(2026, Calendar.AUGUST, d, 16, 0))
    }

  @Test
  fun `a week committed to 16-17 finds the client by moving undated work`() {
    val result =
      CapacityAnswer.answer(
        health(
          items = listOf(item("scheduled", 480, dueAt = rangeStart + 2 * hour), item("defer-a", 300, rank = 1), item("defer-b", 240, rank = 2)),
          blocks = listOf(block("b1", "scheduled", 10, 9, 17)),
          commitments = afternoonCommitments(),
        ),
        newClientHoursPerWeek = 8,
        rangeEnd = rangeEnd,
      )

    assertEquals(CapacityVerdict.MOVE, result.verdict)
    // The Monday–Friday 16:00–17:00 hours are free; the scheduled block takes Monday's.
    assertEquals(240, result.spareMinutes)
    // Only the minimal set that covers the shortfall moves: defer-a alone frees 300 ≥ 240.
    assertEquals(listOf("defer-a"), result.moves.map { it.itemId })
    assertTrue(result.sentence.contains("moving \"defer-a\""))
  }

  @Test
  fun `an item due inside the range cannot be the thing that moves`() {
    val result =
      CapacityAnswer.answer(
        health(
          items = listOf(item("due-now", 480, dueAt = rangeStart + 2 * hour), item("defer-a", 90)),
          blocks = listOf(block("b1", "due-now", 10, 9, 17)),
          commitments = afternoonCommitments(),
        ),
        newClientHoursPerWeek = 8,
        rangeEnd = rangeEnd,
      )

    assertEquals(CapacityVerdict.CANNOT, result.verdict)
    // 90 deferrable minutes are not enough for the 240-minute shortfall.
    assertTrue(result.sentence.contains("leaves 2.5 h missing"))
  }

  @Test
  fun `missing estimates make the answer say it cannot be trusted`() {
    val result =
      CapacityAnswer.answer(
        health(items = listOf(item("unestimated", null), item("t2", 60))),
        newClientHoursPerWeek = 8,
        rangeEnd = rangeEnd,
      )

    assertEquals(CapacityVerdict.INCOMPLETE, result.verdict)
    assertTrue(result.sentence.contains("not trustworthy"))
  }

  @Test
  fun `hours formatting shows one decimal and trims the zero`() {
    val result =
      CapacityAnswer.answer(
        health(items = listOf(item("t1", 90))),
        newClientHoursPerWeek = 8,
        rangeEnd = rangeEnd,
      )
    // 2400 → "40", 90 → "1.5", 2400 → "40".
    assertTrue(result.sentence.startsWith("40 h available this week, 1.5 h planned, 40 h spare"))
  }

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis
}
