package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingDateOverride
import com.example.core.WorkingDayWindow
import com.example.core.WorkingWeekWindow
import com.example.data.database.AppDatabase
import com.example.data.database.PlanMigrations
import com.example.data.model.WorkScheduleWindowKind
import com.example.data.repository.PlanRepository
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkingCalendarRepositoryInstrumentedTest {
  private lateinit var database: AppDatabase
  private lateinit var repository: PlanRepository

  @Before
  fun setUp() {
    database =
      Room.inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          AppDatabase::class.java,
        )
        .allowMainThreadQueries()
        .build()
    repository = PlanRepository(database)
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun ensureCatalogSeedsTheSameStableDefaultOnAFreshDatabase() = runBlocking {
    repository.ensureCatalog()

    val first = requireNotNull(repository.observeDefaultWorkingCalendar().first())
    assertEquals(PlanMigrations.DEFAULT_WORK_SCHEDULE_ID, first.schedule.id)
    assertEquals(PlanMigrations.DEFAULT_WORK_SCHEDULE_NAME, first.schedule.name)
    assertEquals(TimeZone.getDefault().id, first.schedule.timeZoneId)
    assertEquals((Calendar.MONDAY..Calendar.FRIDAY).toList(), first.spec.weeklyWindows.map { it.dayOfWeek })
    assertTrue(first.spec.weeklyWindows.all { it.startMinute == 540 && it.endMinute == 1020 })
    assertEquals(
      (Calendar.MONDAY..Calendar.FRIDAY).map {
        "${PlanMigrations.DEFAULT_WORK_SCHEDULE_ID}-weekday-$it"
      },
      first.windows.map { it.id },
    )

    repository.ensureCatalog()

    val second = requireNotNull(repository.observeDefaultWorkingCalendar().first())
    assertEquals(first, second)
    assertEquals(1, database.planDao().getWorkSchedules().count { it.isDefault })
    assertEquals(5, database.planDao().getWorkScheduleWindows(first.schedule.id).size)
  }

  @Test
  fun saveReplacesScheduleAndWindowsAtomicallyAfterStrictValidation() = runBlocking {
    repository.ensureCatalog()
    val input =
      WorkingCalendarSpec(
        zoneId = "Asia/Kolkata",
        weeklyWindows =
          listOf(
            WorkingWeekWindow(Calendar.MONDAY, 540, 720),
            WorkingWeekWindow(Calendar.MONDAY, 780, 1020),
            WorkingWeekWindow(Calendar.TUESDAY, 600, 900),
          ),
        overrides =
          listOf(
            WorkingDateOverride("2026-08-15", emptyList()),
            WorkingDateOverride("2026-08-16", listOf(WorkingDayWindow(660, 780))),
          ),
        minimumChunkMinutes = 20,
        maximumChunkMinutes = 80,
        bufferMinutes = 10,
      )

    val saved = repository.saveDefaultWorkingCalendar(input)
    val observed = requireNotNull(repository.observeDefaultWorkingCalendar().first())

    assertEquals(saved, observed)
    assertEquals(input, observed.spec)
    assertEquals("Asia/Kolkata", observed.schedule.timeZoneId)
    assertEquals(5, observed.windows.size)
    val closed = observed.windows.single { it.localDate == "2026-08-15" }
    assertTrue(closed.isClosed)
    assertEquals(WorkScheduleWindowKind.DATE_OVERRIDE, closed.kind)
    assertEquals(0, closed.startMinute)
    assertEquals(0, closed.endMinute)

    val beforeInvalid = observed
    try {
      repository.saveDefaultWorkingCalendar(
        input.copy(
          weeklyWindows =
            listOf(
              WorkingWeekWindow(Calendar.MONDAY, 540, 800),
              WorkingWeekWindow(Calendar.MONDAY, 700, 900),
            )
        )
      )
      fail("Expected an IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
      // Expected: validation occurs before the replacement transaction writes anything.
    }
    assertEquals(beforeInvalid, repository.observeDefaultWorkingCalendar().first())
    assertNotNull(database.planDao().getDefaultWorkSchedule())
  }
}
