package com.example.data.repository

import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingWeekWindow
import com.example.data.model.PlanItem
import com.example.data.model.PlanItemSchedule
import com.example.data.model.WorkSchedule
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanHealthSchedulePolicyTest {
  private val default = calendar("default", isDefault = true)
  private val alternate = calendar("alternate", isDefault = false)

  @Test
  fun `default and explicitly assigned tasks resolve to their exact schedules`() {
    val result =
      PlanHealthSchedulePolicy.resolve(
        items = listOf(item("inherits"), item("explicit")),
        mappings = listOf(mapping("explicit", "alternate")),
        calendars = listOf(default, alternate),
        defaultScheduleId = "default",
      )

    assertTrue(result.isUsable)
    assertEquals(mapOf("explicit" to "alternate", "inherits" to "default"), result.scheduleIdByItemId)
    assertEquals(setOf("alternate", "default"), result.specsByScheduleId.keys)
  }

  @Test
  fun `unused alternate does not inflate the capacity schedule set`() {
    val result =
      PlanHealthSchedulePolicy.resolve(
        items = listOf(item("inherits")),
        mappings = emptyList(),
        calendars = listOf(default, alternate),
        defaultScheduleId = "default",
      )

    assertEquals(setOf("default"), result.specsByScheduleId.keys)
  }

  @Test
  fun `missing explicit assignment fails closed`() {
    val result =
      PlanHealthSchedulePolicy.resolve(
        items = listOf(item("task")),
        mappings = listOf(mapping("task", "missing")),
        calendars = listOf(default),
        defaultScheduleId = "default",
      )

    assertFalse(result.isUsable)
    assertTrue(result.problem.orEmpty().contains("unavailable"))
  }

  @Test
  fun `empty plan still exposes only the active default capacity`() {
    val result =
      PlanHealthSchedulePolicy.resolve(
        items = emptyList(),
        mappings = emptyList(),
        calendars = listOf(default, alternate),
        defaultScheduleId = "default",
      )

    assertTrue(result.isUsable)
    assertEquals(setOf("default"), result.specsByScheduleId.keys)
    assertTrue(result.scheduleIdByItemId.isEmpty())
  }

  @Test
  fun `multiple active defaults fail closed even when the requested id matches one`() {
    val result =
      PlanHealthSchedulePolicy.resolve(
        items = listOf(item("task")),
        mappings = emptyList(),
        calendars = listOf(default, calendar("other-default", isDefault = true)),
        defaultScheduleId = default.schedule.id,
      )

    assertFalse(result.isUsable)
    assertTrue(result.problem.orEmpty().contains("Exactly one"))
  }

  @Test
  fun `invalid chunk bounds make health unavailable instead of throwing`() {
    val invalid =
      calendar(
        id = "invalid",
        isDefault = true,
        minimum = 90,
        maximum = 30,
      )

    val result =
      PlanHealthSchedulePolicy.resolve(
        items = emptyList(),
        mappings = emptyList(),
        calendars = listOf(invalid),
        defaultScheduleId = invalid.schedule.id,
      )

    assertFalse(result.isUsable)
    assertTrue(result.problem.orEmpty().contains("invalid"))
    assertTrue(result.specsByScheduleId.isEmpty())
  }

  @Test
  fun `persisted and resolved chunk bounds must match`() {
    val inconsistent =
      calendar(
        id = "inconsistent",
        isDefault = true,
        minimum = 30,
        maximum = 120,
        specMinimum = 45,
        specMaximum = 90,
      )

    val result =
      PlanHealthSchedulePolicy.resolve(
        items = listOf(item("task")),
        mappings = emptyList(),
        calendars = listOf(inconsistent),
        defaultScheduleId = inconsistent.schedule.id,
      )

    assertFalse(result.isUsable)
    assertTrue(result.problem.orEmpty().contains("inconsistent"))
  }

  private fun calendar(
    id: String,
    isDefault: Boolean,
    minimum: Int = 30,
    maximum: Int = 120,
    specMinimum: Int = minimum,
    specMaximum: Int = maximum,
  ): PersistedWorkingCalendar {
    val spec =
      WorkingCalendarSpec(
        zoneId = "UTC",
        weeklyWindows = listOf(WorkingWeekWindow(Calendar.MONDAY, 9 * 60, 17 * 60)),
        minimumChunkMinutes = specMinimum,
        maximumChunkMinutes = specMaximum,
      )
    return PersistedWorkingCalendar(
      schedule =
        WorkSchedule(
          id = id,
          name = id,
          nameKey = id,
          timeZoneId = "UTC",
          isDefault = isDefault,
          minimumChunkMinutes = minimum,
          maximumChunkMinutes = maximum,
          rank = 1,
          createdAt = 1,
          updatedAt = 1,
        ),
      windows = emptyList(),
      spec = spec,
    )
  }

  private fun item(id: String) =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      rank = 1,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun mapping(itemId: String, scheduleId: String) =
    PlanItemSchedule(
      planItemId = itemId,
      workScheduleId = scheduleId,
      createdAt = 1,
      updatedAt = 1,
    )
}
