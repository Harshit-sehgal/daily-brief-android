package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MultiSchedulePlanHealthTest {
  private val dayStart = at(2026, Calendar.AUGUST, 10, 0, 0)
  private val dayEnd = at(2026, Calendar.AUGUST, 11, 0, 0)

  @Test
  fun `overlapping schedules share one human capacity`() {
    val work = spec(9 * 60, 17 * 60)
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("a" to work, "b" to work),
        scheduleIdByItemId = mapOf("a-task" to "a", "b-task" to "b"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("a-task", 300), item("b-task", 300)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    assertEquals(480, result.workingMinutes)
    assertEquals(480, result.capacityAfterCommitmentsMinutes)
    assertEquals(120, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.OVERCOMMITTED, result.assessment)
  }

  @Test
  fun `disjoint schedules combine without double counting`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("morning" to spec(8 * 60, 12 * 60), "evening" to spec(13 * 60, 17 * 60)),
        scheduleIdByItemId = mapOf("morning-task" to "morning", "evening-task" to "evening"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("morning-task", 240), item("evening-task", 240)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    assertEquals(480, result.workingMinutes)
    assertEquals(0, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.ON_TRACK, result.assessment)
  }

  @Test
  fun `demand cannot borrow time from an incompatible schedule`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("short" to spec(9 * 60, 10 * 60), "long" to spec(10 * 60, 17 * 60)),
        scheduleIdByItemId = mapOf("short-task" to "short"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("short-task", 120)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    assertEquals(480, result.freeAfterPlannedMinutes)
    assertEquals(60, result.overloadMinutes)
    assertTrue(result.explanation.contains("schedule-compatible"))
  }

  @Test
  fun `an existing block consumes overlapping time for every schedule`() {
    val work = spec(9 * 60, 12 * 60)
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("a" to work, "b" to work),
        scheduleIdByItemId = mapOf("a-task" to "a", "b-task" to "b"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("a-task", 60), item("b-task", 180)),
        blocks = listOf(block("a-block", "a-task", 9, 0, 10, 0)),
        fixedCommitments = emptyList(),
      )

    assertEquals(120, result.freeAfterPlannedMinutes)
    assertEquals(60, result.scheduledPlanMinutes)
    assertEquals(60, result.overloadMinutes)
  }

  @Test
  fun `a block outside its assigned hours is disclosed`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("morning" to spec(9 * 60, 12 * 60)),
        scheduleIdByItemId = mapOf("task" to "morning"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("task", 60)),
        blocks = listOf(block("late", "task", 15, 0, 16, 0)),
        fixedCommitments = emptyList(),
      )

    assertEquals(60, result.planOutsideWorkingMinutes)
    assertEquals(PlanHealthAssessment.AT_RISK, result.assessment)
  }

  @Test
  fun `an unresolved assignment fails closed`() {
    val error =
      assertThrows(IllegalArgumentException::class.java) {
        MultiSchedulePlanHealth.evaluate(
          schedules = mapOf("known" to spec(9 * 60, 17 * 60)),
          scheduleIdByItemId = mapOf("task" to "missing"),
          rangeStart = dayStart,
          rangeEnd = dayEnd,
          now = dayStart,
          items = listOf(item("task", 60)),
          blocks = emptyList(),
          fixedCommitments = emptyList(),
        )
      }

    assertTrue(error.message.orEmpty().contains("resolve"))
  }

  @Test
  fun `aggregate minutes do not prove fragmented minimum chunks fit`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("focus" to spec(9 * 60, 10 * 60, minimum = 30, maximum = 120)),
        scheduleIdByItemId = mapOf("task" to "focus"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("task", 40)),
        blocks = emptyList(),
        fixedCommitments =
          listOf(
            WorkingInterval(
              at(2026, Calendar.AUGUST, 10, 9, 20),
              at(2026, Calendar.AUGUST, 10, 9, 40),
            )
          ),
      )

    assertEquals(40, result.capacityAfterCommitmentsMinutes)
    assertEquals(0, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.AT_RISK, result.assessment)
    assertTrue(result.warnings.any { it.contains("minimum") && it.contains("do not prove") })
  }

  @Test
  fun `maximum chunk is enforced per task and uncertainty is disclosed`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("focus" to spec(9 * 60, 13 * 60, minimum = 30, maximum = 120)),
        scheduleIdByItemId = mapOf("task" to "focus"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("task", 240)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    assertEquals(0, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.AT_RISK, result.assessment)
    assertTrue(result.warnings.any { it.contains("maximum chunks") })
  }

  @Test
  fun `multiple tasks produce a concrete maximum-chunk-feasible fit`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("focus" to spec(9 * 60, 13 * 60, minimum = 30, maximum = 120)),
        scheduleIdByItemId = mapOf("first" to "focus", "second" to "focus"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("first", 120), item("second", 120)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    assertEquals(0, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.ON_TRACK, result.assessment)
    assertTrue(result.warnings.none { it.contains("minimum and maximum chunks") })
  }

  @Test
  fun `large task sets stay deterministic without overflowing minute flow`() {
    val items = (0 until 500).map { index -> item("task-${index.toString().padStart(3, '0')}", 1) }
    val assignments = items.associate { it.id to "focus" }
    val arguments =
      { orderedItems: List<PlanItem> ->
        MultiSchedulePlanHealth.evaluate(
          schedules = mapOf("focus" to spec(9 * 60, 17 * 60, minimum = 1, maximum = 1)),
          scheduleIdByItemId = assignments,
          rangeStart = dayStart,
          rangeEnd = dayEnd,
          now = dayStart,
          items = orderedItems,
          blocks = emptyList(),
          fixedCommitments = emptyList(),
        )
      }

    val forward = arguments(items)
    val reversed = arguments(items.reversed())

    assertEquals(20, forward.overloadMinutes)
    assertEquals(PlanHealthAssessment.OVERCOMMITTED, forward.assessment)
    assertEquals(forward, reversed)
  }

  @Test
  fun `unsupported demand scale is capped and marked incomplete instead of wrapping`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("focus" to spec(9 * 60, 17 * 60)),
        scheduleIdByItemId = mapOf("first" to "focus", "second" to "focus"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("first", Int.MAX_VALUE), item("second", Int.MAX_VALUE)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    assertEquals(Int.MAX_VALUE, result.unscheduledDemandMinutes)
    assertEquals(Int.MAX_VALUE, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.INCOMPLETE_DATA, result.assessment)
    assertTrue(result.warnings.any { it.contains("capped") })
  }

  @Test
  fun `missing estimates keep otherwise feasible chunk totals partial`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("focus" to spec(9 * 60, 10 * 60)),
        scheduleIdByItemId = mapOf("known" to "focus", "unknown" to "focus"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("known", 60), item("unknown", null)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    assertEquals(60, result.unscheduledDemandMinutes)
    assertEquals(1, result.missingEstimateCount)
    assertEquals(PlanHealthAssessment.INCOMPLETE_DATA, result.assessment)
    assertTrue(result.warnings.any { it.contains("no effort estimate") })
  }


  /**
   * The greedy trap: a wide schedule can starve a narrow one if capacity is handed out in clock
   * order. Both tasks fit here — but only if the wide task leaves the narrow task's single hour
   * alone. Reporting an overload here would tell someone to cut work they can actually do.
   */
  @Test
  fun `a wide schedule does not consume the only hour a narrow one has`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("narrow" to spec(10 * 60, 11 * 60), "wide" to spec(9 * 60, 17 * 60)),
        scheduleIdByItemId = mapOf("narrow-task" to "narrow", "wide-task" to "wide"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("narrow-task", 60), item("wide-task", 420)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    assertEquals(480, result.workingMinutes)
    assertEquals(0, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.ON_TRACK, result.assessment)
  }

  /**
   * Every schedule fits its own tasks, and the plan still does not fit.
   *
   * Overlapping schedules describe the same person, so the sum of the parts is not the capacity of
   * the whole. Checking each schedule separately — the obvious implementation — calls this week on
   * track while it is two hours over.
   */
  @Test
  fun `schedules that each fit alone can still overload the person`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules =
          mapOf(
            "morning" to spec(9 * 60, 12 * 60),
            "afternoon" to spec(13 * 60, 16 * 60),
            "all-day" to spec(9 * 60, 16 * 60),
          ),
        scheduleIdByItemId =
          mapOf("m-task" to "morning", "a-task" to "afternoon", "d-task" to "all-day"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("m-task", 180), item("a-task", 180), item("d-task", 180)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    // The union of the three schedules is 09:00-16:00, not the 780 minutes they sum to.
    assertEquals(420, result.workingMinutes)
    assertEquals(120, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.OVERCOMMITTED, result.assessment)
  }

  /**
   * A commitment that lands on the only hour a task could use makes that task impossible, even
   * though the day still has hours left. Capacity arithmetic alone would say there is room.
   */
  @Test
  fun `a commitment on the only shared hour makes that work impossible`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules = mapOf("narrow" to spec(10 * 60, 11 * 60), "wide" to spec(9 * 60, 17 * 60)),
        scheduleIdByItemId = mapOf("narrow-task" to "narrow", "wide-task" to "wide"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("narrow-task", 60), item("wide-task", 60)),
        blocks = emptyList(),
        fixedCommitments = listOf(WorkingInterval(at(2026, Calendar.AUGUST, 10, 10, 0), at(2026, Calendar.AUGUST, 10, 11, 0))),
      )

    assertEquals(420, result.capacityAfterCommitmentsMinutes)
    // 120 minutes of demand against 420 free minutes, and still an hour of it cannot be placed.
    assertEquals(60, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.OVERCOMMITTED, result.assessment)
  }

  /** Work assigned to a schedule with no hours in range is impossible, not free. */
  @Test
  fun `a schedule with no hours in range makes its work overload rather than dividing by zero`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules =
          mapOf("weekday" to spec(9 * 60, 17 * 60), "weekend" to spec(9 * 60, 17 * 60, day = Calendar.SATURDAY)),
        scheduleIdByItemId = mapOf("weekday-task" to "weekday", "weekend-task" to "weekend"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("weekday-task", 60), item("weekend-task", 120)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    assertEquals(480, result.workingMinutes)
    assertEquals(120, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.OVERCOMMITTED, result.assessment)
  }

  /**
   * Overlap is not transitive: the first and last schedules share nothing, but both share time with
   * the middle one. Demand has to flow through that chain to be placed correctly.
   */
  @Test
  fun `a chain of partial overlaps places demand that no single pair could hold`() {
    val result =
      MultiSchedulePlanHealth.evaluate(
        schedules =
          mapOf(
            "early" to spec(9 * 60, 11 * 60),
            "middle" to spec(10 * 60, 13 * 60),
            "late" to spec(12 * 60, 14 * 60),
          ),
        scheduleIdByItemId =
          mapOf("early-task" to "early", "middle-task" to "middle", "late-task" to "late"),
        rangeStart = dayStart,
        rangeEnd = dayEnd,
        now = dayStart,
        items = listOf(item("early-task", 60), item("middle-task", 120), item("late-task", 60)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
      )

    // 09:00-14:00 is 300 minutes of one person's time; 240 of it is claimed, and it does fit.
    assertEquals(300, result.workingMinutes)
    assertEquals(0, result.overloadMinutes)
    assertEquals(PlanHealthAssessment.ON_TRACK, result.assessment)
  }

  private fun spec(
    startMinute: Int,
    endMinute: Int,
    minimum: Int = 1,
    maximum: Int = 24 * 60,
    day: Int = Calendar.MONDAY,
  ) =
    WorkingCalendarSpec(
      zoneId = "UTC",
      weeklyWindows = listOf(WorkingWeekWindow(day, startMinute, endMinute)),
      minimumChunkMinutes = minimum,
      maximumChunkMinutes = maximum,
    )

  private fun item(id: String, effort: Int?) =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      rank = 1,
      effortMinutes = effort,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(
    id: String,
    itemId: String,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
  ) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = at(2026, Calendar.AUGUST, 10, startHour, startMinute),
      endAt = at(2026, Calendar.AUGUST, 10, endHour, endMinute),
      createdAt = 1,
      updatedAt = 1,
    )

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis
}
