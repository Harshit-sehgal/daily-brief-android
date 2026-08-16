package com.example.ui.components

import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingDateOverride
import com.example.core.WorkingDayWindow
import com.example.core.WorkingWeekWindow
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingScheduleEditPolicyTest {
  @Test
  fun `persisted spec becomes a Monday through Sunday draft and round trips exactly`() {
    val override = WorkingDateOverride("2026-08-15", emptyList())
    val spec =
      WorkingCalendarSpec(
        zoneId = "Asia/Kolkata",
        weeklyWindows =
          listOf(
            WorkingWeekWindow(Calendar.MONDAY, 540, 720),
            WorkingWeekWindow(Calendar.MONDAY, 780, 1020),
            WorkingWeekWindow(Calendar.SUNDAY, 600, 840),
          ),
        overrides = listOf(override),
        minimumChunkMinutes = 20,
        maximumChunkMinutes = 80,
        bufferMinutes = 10,
      )

    val state = WorkingScheduleEditPolicy.fromSpec(spec)
    val validation = WorkingScheduleEditPolicy.validate(state)

    assertEquals(
      listOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY,
      ),
      state.days.map { it.dayOfWeek },
    )
    assertEquals(2, state.days.first().windows.size)
    assertEquals("2026-08-15", state.dateOverrides.single().localDate)
    assertFalse(state.dateOverrides.single().isOpen)
    assertEquals("09:00", state.dateOverrides.single().windows.single().startText)
    assertTrue(validation.isValid)
    assertEquals(spec, validation.spec)
  }

  @Test
  fun `closing and reopening a day retains its draft times without persisting hidden windows`() {
    val initial = WorkingScheduleEditPolicy.fromSpec(standardSpec())
    val closed =
      WorkingScheduleEditPolicy.setDayOpen(initial, Calendar.MONDAY, isOpen = false)

    val closedSpec = requireNotNull(WorkingScheduleEditPolicy.validate(closed).spec)
    assertFalse(closedSpec.weeklyWindows.any { it.dayOfWeek == Calendar.MONDAY })
    assertEquals("09:00", closed.days.first().windows.single().startText)

    val reopened =
      WorkingScheduleEditPolicy.setDayOpen(closed, Calendar.MONDAY, isOpen = true)
    val reopenedSpec = requireNotNull(WorkingScheduleEditPolicy.validate(reopened).spec)
    assertEquals(
      WorkingWeekWindow(Calendar.MONDAY, 540, 1020),
      reopenedSpec.weeklyWindows.single { it.dayOfWeek == Calendar.MONDAY },
    )
  }

  @Test
  fun `time edits and added split windows produce one validated spec`() {
    val initial = WorkingScheduleEditPolicy.fromSpec(standardSpec())
    val edited =
      WorkingScheduleEditPolicy.updateWindow(
        state = initial,
        dayOfWeek = Calendar.MONDAY,
        index = 0,
        startText = "08:30",
        endText = "12:00",
      )
    val split = WorkingScheduleEditPolicy.addWindow(edited, Calendar.MONDAY)
    val validation = WorkingScheduleEditPolicy.validate(split)

    assertTrue(validation.isValid)
    assertEquals(
      listOf(
        WorkingWeekWindow(Calendar.MONDAY, 510, 720),
        WorkingWeekWindow(Calendar.MONDAY, 720, 780),
      ),
      requireNotNull(validation.spec).weeklyWindows.filter {
        it.dayOfWeek == Calendar.MONDAY
      },
    )
  }

  @Test
  fun `malformed time overlap zone and chunk bounds show specific errors and no spec`() {
    val initial = WorkingScheduleEditPolicy.fromSpec(standardSpec())
    val withSecondWindow = WorkingScheduleEditPolicy.addWindow(initial, Calendar.MONDAY)
    val overlapping =
      WorkingScheduleEditPolicy.updateWindow(
        state = withSecondWindow,
        dayOfWeek = Calendar.MONDAY,
        index = 1,
        startText = "10:00",
        endText = "bad",
      )
    val invalid =
      overlapping.copy(
        zoneId = "Not/AZone",
        minimumChunkMinutes = "90",
        maximumChunkMinutes = "30",
        bufferMinutes = "1440",
      )

    val validation = WorkingScheduleEditPolicy.validate(invalid)
    val messages = validation.fieldErrors.values

    assertFalse(validation.isValid)
    assertNull(validation.spec)
    assertTrue(messages.any { it.contains("IANA time zone") })
    assertTrue(messages.any { it.contains("Maximum chunk") })
    assertTrue(messages.any { it.contains("0 to 1439") })
    assertTrue(messages.any { it.contains("00:01 to 24:00") })
    assertNotNull(validation.summary)
  }

  @Test
  fun `an open day with no windows cannot be saved`() {
    val initial = WorkingScheduleEditPolicy.fromSpec(standardSpec())
    val removed = WorkingScheduleEditPolicy.removeWindow(initial, Calendar.MONDAY, 0)

    val validation = WorkingScheduleEditPolicy.validate(removed)

    assertNull(validation.spec)
    assertTrue(validation.fieldErrors.values.any { it.contains("Add a time window") })
  }

  @Test
  fun `new closed exception fails closed until its exact local date is valid`() {
    val initial = WorkingScheduleEditPolicy.fromSpec(standardSpec())
    val blank = WorkingScheduleEditPolicy.addDateOverride(initial)

    assertNull(WorkingScheduleEditPolicy.validate(blank).spec)
    assertTrue(
      WorkingScheduleEditPolicy.validate(blank).fieldErrors.values.any {
        it.contains("YYYY-MM-DD")
      }
    )

    val impossible = WorkingScheduleEditPolicy.updateDateOverride(blank, 0, "2026-02-30")
    assertNull(WorkingScheduleEditPolicy.validate(impossible).spec)

    val dated = WorkingScheduleEditPolicy.updateDateOverride(impossible, 0, "2026-02-28")
    val spec = requireNotNull(WorkingScheduleEditPolicy.validate(dated).spec)
    assertEquals(
      WorkingDateOverride("2026-02-28", emptyList()),
      spec.overrides.single(),
    )
  }

  @Test
  fun `date exception can replace weekly hours with multiple windows`() {
    val initial = WorkingScheduleEditPolicy.fromSpec(standardSpec())
    val dated =
      WorkingScheduleEditPolicy.updateDateOverride(
        WorkingScheduleEditPolicy.addDateOverride(initial),
        index = 0,
        localDate = "2026-08-15",
      )
    val opened = WorkingScheduleEditPolicy.setDateOverrideOpen(dated, 0, isOpen = true)
    val morning =
      WorkingScheduleEditPolicy.updateDateOverrideWindow(
        state = opened,
        overrideIndex = 0,
        windowIndex = 0,
        startText = "08:30",
        endText = "12:00",
      )
    val split = WorkingScheduleEditPolicy.addDateOverrideWindow(morning, 0)

    val validation = WorkingScheduleEditPolicy.validate(split)

    assertTrue(validation.isValid)
    assertEquals(
      WorkingDateOverride(
        localDate = "2026-08-15",
        windows =
          listOf(
            WorkingDayWindow(510, 720),
            WorkingDayWindow(720, 780),
          ),
      ),
      requireNotNull(validation.spec).overrides.single(),
    )
  }

  @Test
  fun `duplicate dates and overlapping exception windows cannot be saved`() {
    val initial = WorkingScheduleEditPolicy.fromSpec(standardSpec())
    val first = WorkingScheduleEditPolicy.addDateOverride(initial)
    val firstDated = WorkingScheduleEditPolicy.updateDateOverride(first, 0, "2026-08-15")
    val second = WorkingScheduleEditPolicy.addDateOverride(firstDated)
    val duplicate = WorkingScheduleEditPolicy.updateDateOverride(second, 1, "2026-08-15")

    val duplicateValidation = WorkingScheduleEditPolicy.validate(duplicate)
    assertNull(duplicateValidation.spec)
    assertEquals(
      2,
      duplicateValidation.fieldErrors.values.count { it.contains("only one exception") },
    )

    val unique = WorkingScheduleEditPolicy.updateDateOverride(duplicate, 1, "2026-08-16")
    val opened = WorkingScheduleEditPolicy.setDateOverrideOpen(unique, 0, isOpen = true)
    val malformed =
      WorkingScheduleEditPolicy.updateDateOverrideWindow(
        state = opened,
        overrideIndex = 0,
        windowIndex = 0,
        endText = "25:00",
      )
    assertNull(WorkingScheduleEditPolicy.validate(malformed).spec)
    assertTrue(
      WorkingScheduleEditPolicy.validate(malformed).fieldErrors.values.any {
        it.contains("00:01 to 24:00")
      }
    )

    val added = WorkingScheduleEditPolicy.addDateOverrideWindow(opened, 0)
    val overlapping =
      WorkingScheduleEditPolicy.updateDateOverrideWindow(
        state = added,
        overrideIndex = 0,
        windowIndex = 1,
        startText = "10:00",
        endText = "18:00",
      )
    val overlapValidation = WorkingScheduleEditPolicy.validate(overlapping)

    assertNull(overlapValidation.spec)
    assertTrue(
      overlapValidation.fieldErrors.values.any { it.contains("cannot overlap") }
    )
  }

  @Test
  fun `closing retains exception hours while removal drops the whole draft`() {
    val initial = WorkingScheduleEditPolicy.fromSpec(standardSpec())
    val dated =
      WorkingScheduleEditPolicy.updateDateOverride(
        WorkingScheduleEditPolicy.addDateOverride(initial),
        0,
        "2026-08-15",
      )
    val opened = WorkingScheduleEditPolicy.setDateOverrideOpen(dated, 0, isOpen = true)
    val changed =
      WorkingScheduleEditPolicy.updateDateOverrideWindow(
        state = opened,
        overrideIndex = 0,
        windowIndex = 0,
        startText = "10:00",
        endText = "14:00",
      )
    val closed = WorkingScheduleEditPolicy.setDateOverrideOpen(changed, 0, isOpen = false)

    assertEquals("10:00", closed.dateOverrides.single().windows.single().startText)
    assertEquals(
      emptyList<WorkingDayWindow>(),
      requireNotNull(WorkingScheduleEditPolicy.validate(closed).spec)
        .overrides
        .single()
        .windows,
    )

    val removed = WorkingScheduleEditPolicy.removeDateOverride(closed, 0)
    assertTrue(removed.dateOverrides.isEmpty())
    assertTrue(WorkingScheduleEditPolicy.validate(removed).isValid)
  }

  private fun standardSpec() =
    WorkingCalendarSpec(
      zoneId = "UTC",
      weeklyWindows =
        (Calendar.MONDAY..Calendar.FRIDAY).map { day ->
          WorkingWeekWindow(day, 540, 1020)
        },
      minimumChunkMinutes = 30,
      maximumChunkMinutes = 120,
      bufferMinutes = 0,
    )
}
