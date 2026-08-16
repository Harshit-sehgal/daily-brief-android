package com.example.ui.components

import com.example.data.model.WorkSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskWorkSchedulePolicyTest {
  @Test
  fun `sole default hides redundant explicit pin until one already exists`() {
    val default = schedule("default", isDefault = true)

    val inherited = taskWorkScheduleOptions(listOf(default), assignedScheduleId = null)
    assertEquals(listOf(null), inherited.map(TaskWorkScheduleOption::scheduleId))
    assertTrue(inherited.single().selected)
    assertEquals("Inherit default", taskWorkScheduleLabel(listOf(default), null, true))

    val pinned = taskWorkScheduleOptions(listOf(default), assignedScheduleId = default.id)
    assertEquals(listOf(null, default.id), pinned.map(TaskWorkScheduleOption::scheduleId))
    assertTrue(pinned.single { it.scheduleId == default.id }.selected)
    assertEquals("Default (explicit)", taskWorkScheduleLabel(listOf(default), default.id, true))
  }

  @Test
  fun `inheritance is visibly unavailable without exactly one default`() {
    val alternate = schedule("alternate")
    val options = taskWorkScheduleOptions(listOf(alternate), assignedScheduleId = null)

    assertFalse(options.single { it.scheduleId == null }.enabled)
    assertEquals("Default schedule unavailable", taskWorkScheduleLabel(listOf(alternate), null, true))
    assertEquals("Loading working schedule…", taskWorkScheduleLabel(emptyList(), null, false))
  }

  @Test
  fun `alternate and explicit default options expose one selected choice`() {
    val default = schedule("default", isDefault = true)
    val alternate = schedule("focus")
    val options = taskWorkScheduleOptions(listOf(default, alternate), alternate.id)

    assertEquals(3, options.size)
    assertEquals(1, options.count(TaskWorkScheduleOption::selected))
    assertTrue(options.single { it.scheduleId == alternate.id }.label.endsWith("· Selected"))
    assertEquals("Focus", taskWorkScheduleLabel(listOf(default, alternate), alternate.id, true))
  }

  private fun schedule(id: String, isDefault: Boolean = false) =
    WorkSchedule(
      id = id,
      name = id.replaceFirstChar(Char::uppercase),
      nameKey = id,
      timeZoneId = "UTC",
      isDefault = isDefault,
      rank = 1,
      createdAt = 1,
      updatedAt = 1,
    )
}
