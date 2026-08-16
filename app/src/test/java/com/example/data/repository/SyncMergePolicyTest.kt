package com.example.data.repository

import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergePolicyTest {

  private fun event(
    id: String,
    title: String = "Source title",
    startTime: Long = 1_000L,
    endTime: Long = 2_000L,
    source: String = EventSource.GOOGLE,
    isUrgent: Boolean = false,
    isDeadline: Boolean = false,
    kanbanBoard: String = "Default",
    kanbanStatus: String = "To Do",
    userEdited: Boolean = false,
    description: String? = null,
  ) =
    BriefingEvent(
      id = id,
      title = title,
      startTime = startTime,
      endTime = endTime,
      source = source,
      description = description,
      isDeadline = isDeadline,
      isUrgent = isUrgent,
      kanbanBoard = kanbanBoard,
      kanbanStatus = kanbanStatus,
      userEdited = userEdited,
    )

  @Test
  fun `a hand-edited event keeps its wording and flags but follows source times`() {
    val stored =
      event(
        id = "device_7",
        title = "My wording",
        description = "My note",
        startTime = 1_000L,
        endTime = 2_000L,
        isUrgent = true,
        isDeadline = true,
        kanbanBoard = "Work",
        kanbanStatus = "Doing",
        userEdited = true,
      )
    val fresh = event(id = "device_7", startTime = 5_000L, endTime = 6_000L)

    val merged = SyncMergePolicy.merge(listOf(fresh), listOf(stored)).single()

    assertEquals("My wording", merged.title)
    assertEquals("My note", merged.description)
    assertTrue(merged.isUrgent)
    assertTrue(merged.isDeadline)
    assertEquals("Work", merged.kanbanBoard)
    assertEquals("Doing", merged.kanbanStatus)
    assertTrue(merged.userEdited)
    // The source still owns when it happens.
    assertEquals(5_000L, merged.startTime)
    assertEquals(6_000L, merged.endTime)
  }

  @Test
  fun `an untouched event takes the source wording and keeps only its board place`() {
    val stored =
      event(
        id = "device_7",
        title = "Old source title",
        isUrgent = true,
        kanbanBoard = "Work",
        kanbanStatus = "Doing",
        userEdited = false,
      )
    val fresh = event(id = "device_7", title = "New source title")

    val merged = SyncMergePolicy.merge(listOf(fresh), listOf(stored)).single()

    assertEquals("New source title", merged.title)
    // Flags the user never set follow the source; where they filed it does not.
    assertEquals(false, merged.isUrgent)
    assertEquals("Work", merged.kanbanBoard)
    assertEquals("Doing", merged.kanbanStatus)
  }

  @Test
  fun `edits survive the upgrade that gave non-repeating events a stable id`() {
    val stored =
      event(id = "device_7_1700000000000", title = "My wording", userEdited = true)
    val fresh = event(id = "device_7", title = "Source title")

    val merged = SyncMergePolicy.merge(listOf(fresh), listOf(stored)).single()

    assertEquals("device_7", merged.id)
    assertEquals("My wording", merged.title)
    assertTrue(merged.userEdited)
  }

  @Test
  fun `an ambiguous provider id inherits from nothing rather than the wrong instance`() {
    val storedInstances =
      listOf(
        event(id = "device_7_1", title = "Monday's note", userEdited = true),
        event(id = "device_7_2", title = "Tuesday's note", userEdited = true),
      )
    val fresh = event(id = "device_7", title = "Source title")

    val merged = SyncMergePolicy.merge(listOf(fresh), storedInstances).single()

    assertEquals("Source title", merged.title)
    assertEquals(false, merged.userEdited)
  }

  @Test
  fun `a brand new event passes through untouched`() {
    val fresh = event(id = "device_9", title = "Source title")

    assertEquals(listOf(fresh), SyncMergePolicy.merge(listOf(fresh), emptyList()))
  }

  @Test
  fun `a non-device event only matches on its own id`() {
    val stored = event(id = "notion_a", title = "My wording", source = EventSource.NOTION, userEdited = true)
    val fresh = event(id = "notion_b", title = "Source title", source = EventSource.NOTION)

    val merged = SyncMergePolicy.merge(listOf(fresh), listOf(stored)).single()

    assertEquals("Source title", merged.title)
  }

  @Test
  fun `scoping drops other sources and collapses a duplicated id`() {
    val incoming =
      listOf(
        event(id = "device_1", source = EventSource.GOOGLE),
        event(id = "device_1", title = "Repeat of the same row", source = EventSource.GOOGLE),
        event(id = "notion_1", source = EventSource.NOTION),
      )

    val scoped =
      SyncMergePolicy.scopedToSources(incoming, listOf(EventSource.GOOGLE, EventSource.DEVICE))

    assertEquals(1, scoped.size)
    assertEquals("device_1", scoped.single().id)
  }
}
