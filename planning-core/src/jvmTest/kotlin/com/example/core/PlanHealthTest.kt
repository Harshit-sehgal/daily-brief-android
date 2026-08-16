package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanHealthTest {
  private val zone = "UTC"
  private val monday = at(2026, Calendar.AUGUST, 10, 0, 0)
  private val fridayEnd = at(2026, Calendar.AUGUST, 15, 0, 0)
  private val spec =
    WorkingCalendarSpec(
      zoneId = zone,
      weeklyWindows =
        (Calendar.MONDAY..Calendar.FRIDAY).map {
          WorkingWeekWindow(it, 9 * 60, 17 * 60)
        },
    )

  @Test
  fun `capacity subtracts fixed commitments and merged planned blocks without moving either`() {
    val commitment = interval(Calendar.AUGUST, 10, 10, 0, 11, 0)
    val task = item("task", effort = 240)
    val blocks =
      listOf(
        block("one", task.id, Calendar.AUGUST, 10, 9, 0, 10, 0),
        block("two", task.id, Calendar.AUGUST, 10, 9, 30, 10, 30),
      )

    val result =
      PlanHealth.evaluate(spec, monday, fridayEnd, monday, listOf(task), blocks, listOf(commitment))

    assertEquals(2_400, result.workingMinutes)
    assertEquals(2_340, result.capacityAfterCommitmentsMinutes)
    assertEquals(60, result.scheduledPlanMinutes)
    assertEquals(2_280, result.freeAfterPlannedMinutes)
    assertEquals(30, result.planCommitmentOverlapMinutes)
    assertEquals(30, result.planPlanOverlapMinutes)
    assertEquals(PlanHealthAssessment.AT_RISK, result.assessment)
  }

  @Test
  fun `unknown effort produces incomplete data instead of a false green score`() {
    val result =
      PlanHealth.evaluate(
        spec,
        monday,
        fridayEnd,
        monday,
        listOf(item("known", effort = 60), item("unknown", effort = null)),
        emptyList(),
        emptyList(),
      )

    assertEquals(PlanHealthAssessment.INCOMPLETE_DATA, result.assessment)
    assertEquals(1, result.missingEstimateCount)
    assertTrue(result.explanation.contains("partial"))
    assertTrue(result.repairs.any { it.action.contains("Estimate") })
  }

  @Test
  fun `progress and scheduled work reduce unscheduled demand with deterministic rounding`() {
    val task = item("task", effort = 101, progress = 50)
    val block = block("block", task.id, Calendar.AUGUST, 10, 9, 0, 9, 30)

    val result =
      PlanHealth.evaluate(spec, monday, fridayEnd, monday, listOf(task), listOf(block), emptyList())

    val demand = result.demands.single()
    assertEquals(51, demand.remainingEffortMinutes)
    assertEquals(30, demand.scheduledMinutes)
    assertEquals(21, demand.unscheduledMinutes)
    assertEquals(21, result.unscheduledDemandMinutes)
  }

  @Test
  fun `overload and deadline risk are explained with a repair candidate`() {
    val oneDayEnd = at(2026, Calendar.AUGUST, 11, 0, 0)
    val task = item("large", effort = 600, due = at(2026, Calendar.AUGUST, 10, 17, 0))

    val result =
      PlanHealth.evaluate(spec, monday, oneDayEnd, monday, listOf(task), emptyList(), emptyList())

    assertEquals(PlanHealthAssessment.OVERCOMMITTED, result.assessment)
    assertEquals(120, result.overloadMinutes)
    assertTrue(result.risks.any { it.kind == PlanRiskKind.DEADLINE_CAPACITY })
    assertTrue(result.repairs.any { it.action.contains("120 minutes") })
  }

  @Test
  fun `dependency violations and corrupt cycles never produce on track`() {
    val first = item("a", effort = 60)
    val second = item("b", effort = 60)
    val blocks =
      listOf(
        block("a-block", "a", Calendar.AUGUST, 10, 10, 0, 11, 0),
        block("b-block", "b", Calendar.AUGUST, 10, 9, 0, 10, 0),
      )
    val dependencies = listOf(edge("a", "b"), edge("b", "a"))

    val result =
      PlanHealth.evaluate(spec, monday, fridayEnd, monday, listOf(first, second), blocks, emptyList(), dependencies)

    assertEquals(PlanHealthAssessment.INCOMPLETE_DATA, result.assessment)
    assertTrue(result.warnings.any { it.contains("cycle") })
    assertTrue(result.risks.any { it.kind == PlanRiskKind.DEPENDENCY })
  }

  @Test
  fun `planned work outside working time is visible rather than counted as capacity`() {
    val task = item("early", effort = 60)
    val early = block("early", task.id, Calendar.AUGUST, 10, 7, 0, 8, 0)

    val result =
      PlanHealth.evaluate(spec, monday, fridayEnd, monday, listOf(task), listOf(early), emptyList())

    assertEquals(60, result.planOutsideWorkingMinutes)
    assertEquals(0, result.scheduledPlanMinutes)
    assertEquals(PlanHealthAssessment.AT_RISK, result.assessment)
  }

  @Test
  fun `invalid effort and progress fail closed instead of crashing or reading as zero`() {
    val corrupt = item("corrupt", effort = -1, progress = 150)

    val result =
      PlanHealth.evaluate(spec, monday, fridayEnd, monday, listOf(corrupt), emptyList(), emptyList())

    assertEquals(PlanHealthAssessment.INCOMPLETE_DATA, result.assessment)
    assertEquals(1, result.invalidPlanningFieldCount)
    assertTrue(result.warnings.any { it.contains("invalid") })
  }

  private fun item(
    id: String,
    effort: Int?,
    progress: Int = 0,
    due: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      rank = 1,
      effortMinutes = effort,
      progress = progress,
      dueAt = due,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(
    id: String,
    itemId: String,
    month: Int,
    day: Int,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
  ) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = at(2026, month, day, startHour, startMinute),
      endAt = at(2026, month, day, endHour, endMinute),
      createdAt = 1,
      updatedAt = 1,
    )

  private fun interval(
    month: Int,
    day: Int,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
  ) =
    WorkingInterval(
      at(2026, month, day, startHour, startMinute),
      at(2026, month, day, endHour, endMinute),
    )

  private fun edge(from: String, to: String) =
    PlanDependency(
      id = "$from-$to",
      boardId = "board",
      predecessorId = from,
      successorId = to,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis
}
