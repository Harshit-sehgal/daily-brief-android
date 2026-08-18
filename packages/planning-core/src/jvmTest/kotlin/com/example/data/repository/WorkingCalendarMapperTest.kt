package com.example.data.repository

import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingDateOverride
import com.example.core.WorkingDayWindow
import com.example.core.WorkingWeekWindow
import com.example.data.database.nameKey
import com.example.data.database.stableId
import com.example.data.database.WorkScheduleDefaults
import com.example.data.model.WorkSchedule
import com.example.data.model.WorkScheduleWindow
import com.example.data.model.WorkScheduleWindowKind
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkingCalendarMapperTest {
  @Test
  fun `entities map weekly open and closed overrides without partial loss`() {
    val schedule = schedule(zoneId = "Asia/Kolkata")
    val rows =
      listOf(
        window(
          id = "closed",
          kind = WorkScheduleWindowKind.DATE_OVERRIDE,
          localDate = "2026-08-15",
          start = 600,
          end = 600,
          closed = true,
        ),
        window(id = "monday-afternoon", day = Calendar.MONDAY, start = 780, end = 1020),
        window(
          id = "special-late",
          kind = WorkScheduleWindowKind.DATE_OVERRIDE,
          localDate = "2026-08-16",
          start = 840,
          end = 960,
        ),
        window(id = "monday-morning", day = Calendar.MONDAY, start = 540, end = 720),
        window(
          id = "special-early",
          kind = WorkScheduleWindowKind.DATE_OVERRIDE,
          localDate = "2026-08-16",
          start = 480,
          end = 600,
        ),
      )

    val mapped = WorkingCalendarMapper.fromEntities(schedule, rows)

    assertEquals("Asia/Kolkata", mapped.spec.zoneId)
    assertEquals(
      listOf(
        WorkingWeekWindow(Calendar.MONDAY, 540, 720),
        WorkingWeekWindow(Calendar.MONDAY, 780, 1020),
      ),
      mapped.spec.weeklyWindows,
    )
    assertEquals(
      listOf(
        WorkingDateOverride("2026-08-15", emptyList()),
        WorkingDateOverride(
          "2026-08-16",
          listOf(WorkingDayWindow(480, 600), WorkingDayWindow(840, 960)),
        ),
      ),
      mapped.spec.overrides,
    )
    assertEquals(15, mapped.spec.minimumChunkMinutes)
    assertEquals(90, mapped.spec.maximumChunkMinutes)
    assertEquals(5, mapped.spec.bufferMinutes)
    assertEquals(rows.map { it.id }.toSet(), mapped.windows.map { it.id }.toSet())
  }

  @Test
  fun `malformed entity combinations reject the complete mapping`() {
    val valid = window(id = "valid", day = Calendar.MONDAY, start = 540, end = 1020)
    val malformedSets =
      listOf(
        listOf(valid.copy(kind = "future_kind")),
        listOf(valid.copy(scheduleId = "another-schedule")),
        listOf(valid.copy(localDate = "2026-08-17")),
        listOf(valid.copy(isClosed = true, endMinute = valid.startMinute)),
        listOf(
          window(
            id = "override-with-day",
            kind = WorkScheduleWindowKind.DATE_OVERRIDE,
            day = Calendar.MONDAY,
            localDate = "2026-08-17",
            start = 540,
            end = 600,
          )
        ),
        listOf(
          window(
            id = "closed",
            kind = WorkScheduleWindowKind.DATE_OVERRIDE,
            localDate = "2026-08-17",
            start = 0,
            end = 0,
            closed = true,
          ),
          window(
            id = "open",
            kind = WorkScheduleWindowKind.DATE_OVERRIDE,
            localDate = "2026-08-17",
            start = 540,
            end = 600,
          ),
        ),
        listOf(
          window(
            id = "not-zero-duration",
            kind = WorkScheduleWindowKind.DATE_OVERRIDE,
            localDate = "2026-08-17",
            start = 0,
            end = 1,
            closed = true,
          )
        ),
        listOf(
          window(
            id = "bad-date",
            kind = WorkScheduleWindowKind.DATE_OVERRIDE,
            localDate = "2026-02-30",
            start = 540,
            end = 600,
          )
        ),
        listOf(valid, valid.copy(dayOfWeek = Calendar.TUESDAY)),
        listOf(valid, valid.copy(id = "overlap", startMinute = 600, endMinute = 900)),
      )

    malformedSets.forEach { rows ->
      expectIllegalArgument { WorkingCalendarMapper.fromEntities(schedule(), rows) }
    }
  }

  @Test
  fun `spec becomes canonical rows and round trips its explicit time zone`() {
    val input =
      WorkingCalendarSpec(
        zoneId = "America/New_York",
        weeklyWindows =
          listOf(
            WorkingWeekWindow(Calendar.FRIDAY, 780, 1020),
            WorkingWeekWindow(Calendar.FRIDAY, 540, 720),
          ),
        overrides =
          listOf(
            WorkingDateOverride("2026-12-25", emptyList()),
            WorkingDateOverride("2026-12-26", listOf(WorkingDayWindow(600, 720))),
          ),
        minimumChunkMinutes = 20,
        maximumChunkMinutes = 80,
        bufferMinutes = 10,
      )

    val mapped = WorkingCalendarMapper.forUpdate(schedule(), input, updatedAt = 99L)

    assertEquals("America/New_York", mapped.schedule.timeZoneId)
    assertEquals(99L, mapped.schedule.updatedAt)
    assertEquals(
      input.copy(weeklyWindows = input.weeklyWindows.sortedBy { it.startMinute }),
      mapped.spec,
    )
    val closed = mapped.windows.single { it.localDate == "2026-12-25" }
    assertTrue(closed.isClosed)
    assertEquals(0, closed.startMinute)
    assertEquals(0, closed.endMinute)
    assertNull(closed.dayOfWeek)
    assertTrue(mapped.windows.filter { it.dayOfWeek == Calendar.FRIDAY }.all { !it.isClosed })
  }

  @Test
  fun `invalid spec produces no replacement value`() {
    expectIllegalArgument {
      WorkingCalendarMapper.forUpdate(
        schedule(),
        WorkingCalendarSpec(
          zoneId = "Not/AZone",
          weeklyWindows = listOf(WorkingWeekWindow(Calendar.MONDAY, 540, 1020)),
        ),
        updatedAt = 99L,
      )
    }
    expectIllegalArgument {
      WorkingCalendarMapper.forUpdate(
        schedule(),
        WorkingCalendarSpec(
          zoneId = "UTC",
          weeklyWindows = emptyList(),
          overrides =
            listOf(
              WorkingDateOverride("2026-08-15", emptyList()),
              WorkingDateOverride("2026-08-15", listOf(WorkingDayWindow(540, 600))),
            ),
        ),
        updatedAt = 99L,
      )
    }
  }

  @Test
  fun `fresh install seed exactly matches the migration contract`() {
    val seeded = WorkingCalendarMapper.defaultSchedule(timeZoneId = "UTC", now = 42L)

    assertEquals(WorkScheduleDefaults.ID, seeded.schedule.id)
    assertEquals(WorkScheduleDefaults.NAME, seeded.schedule.name)
    assertEquals(
      nameKey(WorkScheduleDefaults.NAME),
      seeded.schedule.nameKey,
    )
    assertEquals("UTC", seeded.schedule.timeZoneId)
    assertTrue(seeded.schedule.isDefault)
    assertNull(seeded.schedule.archivedAt)
    assertEquals(30, seeded.schedule.minimumChunkMinutes)
    assertEquals(120, seeded.schedule.maximumChunkMinutes)
    assertEquals(0, seeded.schedule.bufferMinutes)
    assertEquals(42L, seeded.schedule.createdAt)
    assertEquals((Calendar.MONDAY..Calendar.FRIDAY).toList(), seeded.spec.weeklyWindows.map { it.dayOfWeek })
    seeded.windows.forEachIndexed { index, row ->
      assertEquals(
        "${WorkScheduleDefaults.ID}-weekday-${Calendar.MONDAY + index}",
        row.id,
      )
      assertEquals(9 * 60, row.startMinute)
      assertEquals(17 * 60, row.endMinute)
      assertEquals((index + 1L) * 1_000_000L, row.rank)
      assertEquals(42L, row.updatedAt)
    }
  }

  private fun schedule(zoneId: String = "UTC") =
    WorkSchedule(
      id = "schedule",
      name = "Focused week",
      nameKey = "focused week",
      timeZoneId = zoneId,
      isDefault = true,
      minimumChunkMinutes = 15,
      maximumChunkMinutes = 90,
      bufferMinutes = 5,
      rank = 1L,
      createdAt = 1L,
      updatedAt = 1L,
    )

  private fun window(
    id: String,
    kind: String = WorkScheduleWindowKind.WEEKLY,
    day: Int? = null,
    localDate: String? = null,
    start: Int,
    end: Int,
    closed: Boolean = false,
  ) =
    WorkScheduleWindow(
      id = id,
      scheduleId = "schedule",
      kind = kind,
      dayOfWeek = day,
      localDate = localDate,
      startMinute = start,
      endMinute = end,
      isClosed = closed,
      rank = 1L,
      createdAt = 1L,
      updatedAt = 1L,
    )

  private fun expectIllegalArgument(block: () -> Unit) {
    try {
      block()
      fail("Expected an IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
      // Expected strict mapping failure.
    }
  }
}
