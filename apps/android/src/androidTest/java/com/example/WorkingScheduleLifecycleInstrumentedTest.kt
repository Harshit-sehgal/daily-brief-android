package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingDateOverride
import com.example.core.WorkingDayWindow
import com.example.core.WorkingWeekWindow
import com.example.data.database.AppDatabase
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanMutationType
import com.example.data.repository.PlanRepository
import com.example.data.repository.PlanUndoStatus
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkingScheduleLifecycleInstrumentedTest {
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
  fun alternateCreateUpdateAndDefaultTransferAreValidatedUniqueAndUndoable() = runBlocking {
    repository.ensureCatalog()
    val originalDefault = requireNotNull(database.planDao().getDefaultWorkSchedule())
    val dstSpec =
      workingSpec(
        zoneId = "America/New_York",
        dayOfWeek = Calendar.SUNDAY,
        startMinute = 90,
        endMinute = 150,
      )

    val created = repository.createWorkingScheduleWithUndo("Night shift", dstSpec)
    assertEquals(PlanMutationType.WORK_SCHEDULE_CREATE, mutationType(created.mutationId))
    assertEquals("America/New_York", created.value.schedule.timeZoneId)
    assertEquals(dstSpec, created.value.spec)
    assertFalse(created.value.schedule.isDefault)
    assertSoleDefault(originalDefault.id)

    expectIllegalArgument {
      repository.createWorkingSchedule("  NIGHT SHIFT  ", workingSpec("UTC"))
    }
    expectIllegalArgument {
      repository.updateWorkingSchedule(
        created.value.schedule.id,
        originalDefault.name,
        dstSpec,
      )
    }
    val beforeInvalid = database.planDao().getAllWorkSchedules()
    expectIllegalArgument {
      repository.createWorkingSchedule("Broken zone", workingSpec("Not/AZone"))
    }
    assertEquals(beforeInvalid, database.planDao().getAllWorkSchedules())

    val updatedSpec =
      dstSpec.copy(
        overrides =
          listOf(
            WorkingDateOverride("2026-11-26", emptyList()),
            WorkingDateOverride("2026-11-27", listOf(WorkingDayWindow(600, 720))),
          ),
        minimumChunkMinutes = 15,
        maximumChunkMinutes = 90,
        bufferMinutes = 5,
      )
    val updated =
      repository.updateWorkingScheduleWithUndo(
        created.value.schedule.id,
        "Night and weekend",
        updatedSpec,
      )
    assertEquals(PlanMutationType.WORK_SCHEDULE_UPDATE, mutationType(updated.mutationId))
    assertEquals("Night and weekend", updated.value.schedule.name)
    assertEquals(updatedSpec, updated.value.spec)
    assertEquals(PlanUndoStatus.UNDONE, repository.undoMutation(requireNotNull(updated.mutationId)).status)
    assertEquals("Night shift", database.planDao().getWorkSchedule(created.value.schedule.id)?.name)

    val promoted = repository.makeWorkingScheduleDefaultWithUndo(created.value.schedule.id)
    assertEquals(PlanMutationType.WORK_SCHEDULE_DEFAULT, mutationType(promoted.mutationId))
    assertSoleDefault(created.value.schedule.id)
    assertNotNull(database.planDao().getWorkSchedule(originalDefault.id))
    assertEquals(
      PlanUndoStatus.UNDONE,
      repository.undoMutation(requireNotNull(promoted.mutationId)).status,
    )
    assertSoleDefault(originalDefault.id)
  }

  @Test
  fun createUndoBecomesStaleWhenTheNewScheduleGainsAnAssignment() = runBlocking {
    repository.ensureCatalog()
    val created = repository.createWorkingScheduleWithUndo("Focused work", workingSpec("UTC"))
    val board = database.planDao().getBoards().first()
    val task = repository.saveItem(PlanItemInput(boardId = board.id, title = "Deep task"))
    val assignment =
      repository.assignItemWorkingScheduleWithUndo(task.id, created.value.schedule.id)

    assertEquals(
      PlanUndoStatus.STALE,
      repository.undoMutation(requireNotNull(created.mutationId)).status,
    )
    assertNotNull(database.planDao().getWorkSchedule(created.value.schedule.id))
    assertEquals(
      PlanUndoStatus.UNDONE,
      repository.undoMutation(requireNotNull(assignment.mutationId)).status,
    )
    assertEquals(
      PlanUndoStatus.UNDONE,
      repository.undoMutation(requireNotNull(created.mutationId)).status,
    )
    assertNull(database.planDao().getWorkSchedule(created.value.schedule.id))
    assertSoleDefault(requireNotNull(database.planDao().getDefaultWorkSchedule()).id)
  }

  @Test
  fun defaultArchiveRequiresExplicitReplacementAndAtomicallyReroutesAssignments() = runBlocking {
    repository.ensureCatalog()
    val originalDefault = requireNotNull(database.planDao().getDefaultWorkSchedule())
    val originalWindows = database.planDao().getWorkScheduleWindows(originalDefault.id)
    val replacement = repository.createWorkingSchedule("Replacement", workingSpec("Asia/Kolkata"))
    val board = database.planDao().getBoards().first()
    val task = repository.saveItem(PlanItemInput(boardId = board.id, title = "Assigned task"))
    repository.assignItemWorkingScheduleWithUndo(task.id, originalDefault.id)

    expectIllegalArgument { repository.archiveWorkingSchedule(originalDefault.id) }
    assertNull(database.planDao().getWorkSchedule(originalDefault.id)?.archivedAt)
    assertEquals(
      originalDefault.id,
      database.planDao().getPlanItemSchedule(task.id)?.workScheduleId,
    )
    assertSoleDefault(originalDefault.id)

    val archived =
      repository.archiveWorkingScheduleWithUndo(originalDefault.id, replacement.schedule.id)
    assertEquals(PlanMutationType.WORK_SCHEDULE_ARCHIVE, mutationType(archived.mutationId))
    assertEquals(1, archived.value.reroutedAssignmentCount)
    assertNotNull(database.planDao().getWorkSchedule(originalDefault.id)?.archivedAt)
    assertEquals(originalWindows, database.planDao().getWorkScheduleWindows(originalDefault.id))
    assertEquals(
      replacement.schedule.id,
      database.planDao().getPlanItemSchedule(task.id)?.workScheduleId,
    )
    assertSoleDefault(replacement.schedule.id)

    assertEquals(
      PlanUndoStatus.UNDONE,
      repository.undoMutation(requireNotNull(archived.mutationId)).status,
    )
    assertNull(database.planDao().getWorkSchedule(originalDefault.id)?.archivedAt)
    assertEquals(originalWindows, database.planDao().getWorkScheduleWindows(originalDefault.id))
    assertEquals(
      originalDefault.id,
      database.planDao().getPlanItemSchedule(task.id)?.workScheduleId,
    )
    assertSoleDefault(originalDefault.id)
  }

  private suspend fun assertSoleDefault(expectedId: String) {
    val schedules = database.planDao().getAllWorkSchedules()
    val defaults = schedules.filter { it.isDefault }
    assertEquals(1, defaults.size)
    assertEquals(expectedId, defaults.single().id)
    assertNull(defaults.single().archivedAt)
  }

  private suspend fun mutationType(id: String?): String? =
    id?.let { database.planDao().getPlanMutation(it)?.mutationType }

  private fun workingSpec(
    zoneId: String,
    dayOfWeek: Int = Calendar.MONDAY,
    startMinute: Int = 540,
    endMinute: Int = 1020,
  ) =
    WorkingCalendarSpec(
      zoneId = zoneId,
      weeklyWindows = listOf(WorkingWeekWindow(dayOfWeek, startMinute, endMinute)),
    )

  private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
    try {
      block()
      fail("Expected an IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
      // Expected fail-closed validation.
    }
  }
}
