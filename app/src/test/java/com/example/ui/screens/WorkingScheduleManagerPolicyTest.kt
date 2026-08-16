package com.example.ui.screens

import com.example.core.WorkingCalendarSpec
import com.example.data.model.WorkSchedule
import com.example.data.repository.PersistedWorkingCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkingScheduleManagerPolicyTest {
  @Test
  fun nameIsRequiredAndBounded() {
    assertEquals("Enter a schedule name.", workingScheduleNameProblem("   ", emptyList()))
    assertEquals(
      "Use 80 characters or fewer without control characters.",
      workingScheduleNameProblem("a".repeat(81), emptyList()),
    )
    assertEquals(
      "Use 80 characters or fewer without control characters.",
      workingScheduleNameProblem("Focus\u0000", emptyList()),
    )
  }

  @Test
  fun duplicateCheckUsesCanonicalUnicodeName() {
    val existing = calendar(id = "focus", name = "Focus")

    assertEquals(
      "A working schedule with that name already exists.",
      workingScheduleNameProblem("  ＦＯＣＵＳ  ", listOf(existing)),
    )
  }

  @Test
  fun editExcludesTheScheduleBeingRenamed() {
    val existing = calendar(id = "focus", name = "Focus")

    assertNull(
      workingScheduleNameProblem(
        value = " focus ",
        calendars = listOf(existing),
        excludingId = existing.schedule.id,
      )
    )
  }

  private fun calendar(id: String, name: String): PersistedWorkingCalendar {
    val spec = WorkingCalendarSpec(zoneId = "UTC", weeklyWindows = emptyList())
    return PersistedWorkingCalendar(
      schedule =
        WorkSchedule(
          id = id,
          name = name,
          nameKey = name.lowercase(),
          timeZoneId = spec.zoneId,
          rank = 1,
          createdAt = 1,
          updatedAt = 1,
        ),
      windows = emptyList(),
      spec = spec,
    )
  }
}
