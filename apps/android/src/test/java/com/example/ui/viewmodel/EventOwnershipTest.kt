package com.example.ui.viewmodel

import com.example.data.model.EventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventOwnershipTest {
  @Test
  fun `app events are fully editable movable and undoable`() {
    listOf(EventSource.MANUAL, EventSource.SAMPLE).forEach { source ->
      val ownership = EventOwnership.forSource(source)

      assertEquals(EventOwnership.APP_OWNED, ownership)
      assertTrue(ownership.sourceFieldsEditable)
      assertTrue(ownership.deletableFromEditor)
      assertTrue(ownership.movableOnTimeline)
      assertTrue(ownership.deletionUndoable)
    }
  }

  @Test
  fun `device calendars allow editor write-back but not timeline movement or local undo`() {
    EventSource.DEVICE_WRITABLE.forEach { source ->
      val ownership = EventOwnership.forSource(source)

      assertEquals(EventOwnership.DEVICE_CALENDAR, ownership)
      assertTrue(ownership.sourceFieldsEditable)
      assertTrue(ownership.deletableFromEditor)
      assertFalse(ownership.movableOnTimeline)
      assertFalse(ownership.deletionUndoable)
    }
  }

  @Test
  fun `Notion and unknown sources fail closed`() {
    listOf(EventSource.NOTION, "Future provider").forEach { source ->
      val ownership = EventOwnership.forSource(source)

      assertEquals(EventOwnership.READ_ONLY_SOURCE, ownership)
      assertFalse(ownership.sourceFieldsEditable)
      assertFalse(ownership.deletableFromEditor)
      assertFalse(ownership.movableOnTimeline)
      assertFalse(ownership.deletionUndoable)
    }
  }

  @Test
  fun `draft derives ownership from its source`() {
    val draft = EventDraft(startMs = 1L, endMs = 2L, source = EventSource.NOTION)

    assertEquals(EventOwnership.READ_ONLY_SOURCE, draft.ownership)
  }
}
