package com.example.data.repository

import com.example.core.WorkingCalendarSpec
import com.example.data.model.PlanItemSchedule
import com.example.data.model.WorkSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanItemScheduleResolverTest {
  @Test
  fun `unmapped task inherits the one active default`() {
    val default = calendar("default", isDefault = true)

    val result =
      PlanItemScheduleResolver.resolve(
        itemId = "task",
        mappings = emptyList(),
        calendars = listOf(calendar("alternate"), default),
      )

    assertEquals(default, result.calendar)
    assertTrue(result.inheritsDefault)
    assertNull(result.problem)
  }

  @Test
  fun `explicit assignment resolves only its exact active schedule`() {
    val alternate = calendar("alternate")

    val result =
      PlanItemScheduleResolver.resolve(
        itemId = "task",
        mappings = listOf(mapping("task", "alternate")),
        calendars = listOf(calendar("default", isDefault = true), alternate),
      )

    assertEquals(alternate, result.calendar)
    assertFalse(result.inheritsDefault)
    assertNull(result.problem)
  }

  @Test
  fun `missing explicit assignment fails closed instead of falling back`() {
    val result =
      PlanItemScheduleResolver.resolve(
        itemId = "task",
        mappings = listOf(mapping("task", "missing")),
        calendars = listOf(calendar("default", isDefault = true)),
      )

    assertNull(result.calendar)
    assertFalse(result.inheritsDefault)
    assertNotNull(result.problem)
  }

  @Test
  fun `missing or ambiguous default fails closed for inherited task`() {
    listOf(
        emptyList(),
        listOf(calendar("first", isDefault = true), calendar("second", isDefault = true)),
      )
      .forEach { calendars ->
        val result = PlanItemScheduleResolver.resolve("task", emptyList(), calendars)
        assertNull(result.calendar)
        assertTrue(result.inheritsDefault)
        assertNotNull(result.problem)
      }
  }

  @Test
  fun `Plan Health compatibility accepts only explicit mappings to the active default`() {
    val default = calendar("default", isDefault = true)
    val alternate = calendar("alternate")

    assertEquals(
      DefaultScheduleCompatibility.DEFAULT_ONLY,
      PlanItemScheduleResolver.compareExplicitAssignmentsToDefault(
          itemIds = listOf("task"),
          mappings = listOf(mapping("task", default.schedule.id)),
          calendars = listOf(default, alternate),
          defaultScheduleId = default.schedule.id,
        )
        .status,
    )
    assertEquals(
      DefaultScheduleCompatibility.DIFFERENT_SCHEDULE,
      PlanItemScheduleResolver.compareExplicitAssignmentsToDefault(
          itemIds = listOf("task"),
          mappings = listOf(mapping("task", alternate.schedule.id)),
          calendars = listOf(default, alternate),
          defaultScheduleId = default.schedule.id,
        )
        .status,
    )
    assertEquals(
      DefaultScheduleCompatibility.UNRESOLVED_ASSIGNMENT,
      PlanItemScheduleResolver.compareExplicitAssignmentsToDefault(
          itemIds = listOf("task"),
          mappings = listOf(mapping("task", "missing")),
          calendars = listOf(default),
          defaultScheduleId = default.schedule.id,
        )
        .status,
    )
  }

  @Test
  fun `Plan Health compatibility ignores mappings outside the active item set`() {
    val default = calendar("default", isDefault = true)

    val result =
      PlanItemScheduleResolver.compareExplicitAssignmentsToDefault(
        itemIds = listOf("active"),
        mappings = listOf(mapping("other", "missing")),
        calendars = listOf(default),
        defaultScheduleId = default.schedule.id,
      )

    assertEquals(DefaultScheduleCompatibility.DEFAULT_ONLY, result.status)
  }

  private fun calendar(id: String, isDefault: Boolean = false): PersistedWorkingCalendar {
    val schedule =
      WorkSchedule(
        id = id,
        name = id,
        nameKey = id,
        timeZoneId = "UTC",
        isDefault = isDefault,
        rank = 1,
        createdAt = 1,
        updatedAt = 1,
      )
    return PersistedWorkingCalendar(
      schedule = schedule,
      windows = emptyList(),
      spec = WorkingCalendarSpec(zoneId = "UTC", weeklyWindows = emptyList()),
    )
  }

  private fun mapping(itemId: String, scheduleId: String) =
    PlanItemSchedule(itemId, scheduleId, createdAt = 1, updatedAt = 2)
}
