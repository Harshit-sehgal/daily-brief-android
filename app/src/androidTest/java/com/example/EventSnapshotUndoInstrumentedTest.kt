package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.repository.BriefingRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventSnapshotUndoInstrumentedTest {
  @Test
  fun moveAndDeleteUndoRestoreOnlyTheExactExpectedEventState() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val database = AppDatabase.getDatabase(context)
    val repository = BriefingRepository(context)
    val id = "event-undo-${System.nanoTime()}"
    val before =
      BriefingEvent(
        id = id,
        title = "Exact event Undo",
        startTime = 1_800_000_000_000L,
        endTime = 1_800_003_600_000L,
        source = EventSource.MANUAL,
        description = "original",
        isDeadline = false,
        isUrgent = false,
      )
    val moved =
      before.copy(
        startTime = before.startTime + 15 * 60_000L,
        endTime = before.endTime + 15 * 60_000L,
        userEdited = true,
      )
    try {
      database.eventDao().insertEvents(listOf(before))
      assertEquals(
        moved,
        repository.moveAppOwnedEventIfUnchanged(before, moved.startTime, moved.endTime),
      )
      assertTrue(repository.restoreEventSnapshotIfUnchanged(before, moved))
      assertEquals(before, database.eventDao().getEventById(id))

      assertFalse(repository.restoreEventSnapshotIfUnchanged(before, moved))
      assertEquals(before, database.eventDao().getEventById(id))

      val newer = before.copy(title = "Newer edit", userEdited = true)
      database.eventDao().insertEvents(listOf(newer))
      assertFalse(repository.restoreEventSnapshotIfUnchanged(before, moved))
      assertEquals(newer, database.eventDao().getEventById(id))

      database.eventDao().deleteEventById(id)
      assertTrue(repository.restoreEventSnapshotIfUnchanged(before, expectedCurrent = null))
      assertEquals(before, database.eventDao().getEventById(id))
    } finally {
      database.eventDao().deleteEventById(id)
    }
  }

  @Test
  fun previewCommitFailsClosedForAStaleOrExternallyOwnedEvent() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val database = AppDatabase.getDatabase(context)
    val repository = BriefingRepository(context)
    val id = "event-preview-stale-${System.nanoTime()}"
    val before =
      BriefingEvent(
        id = id,
        title = "Preview snapshot",
        startTime = 1_810_000_000_000L,
        endTime = 1_810_003_600_000L,
        source = EventSource.MANUAL,
        description = "original",
        isDeadline = false,
        isUrgent = false,
      )
    val newer = before.copy(title = "Newer edit", userEdited = true)
    try {
      database.eventDao().insertEvents(listOf(newer))
      assertNull(
        repository.moveAppOwnedEventIfUnchanged(
          before,
          before.startTime + 15 * 60_000L,
          before.endTime + 15 * 60_000L,
        )
      )
      assertEquals(newer, database.eventDao().getEventById(id))

      val providerOwned = newer.copy(source = EventSource.GOOGLE)
      database.eventDao().insertEvents(listOf(providerOwned))
      assertNull(
        repository.moveAppOwnedEventIfUnchanged(
          providerOwned,
          providerOwned.startTime + 15 * 60_000L,
          providerOwned.endTime + 15 * 60_000L,
        )
      )
      assertEquals(providerOwned, database.eventDao().getEventById(id))
    } finally {
      database.eventDao().deleteEventById(id)
    }
  }
}
