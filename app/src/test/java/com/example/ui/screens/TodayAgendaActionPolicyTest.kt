package com.example.ui.screens

import com.example.data.model.EventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayAgendaActionPolicyTest {
  @Test
  fun timedAppOwnedEventOffersBothQuickActions() {
    val policy = agendaRowActionPolicy(EventSource.MANUAL, isAllDay = false)

    assertTrue(policy.canMoveToTomorrow)
    assertEquals("Delete event", policy.deleteLabel)
  }

  @Test
  fun allDayAppOwnedEventCannotBeDeferredButCanBeDeleted() {
    val policy = agendaRowActionPolicy(EventSource.SAMPLE, isAllDay = true)

    assertFalse(policy.canMoveToTomorrow)
    assertEquals("Delete event", policy.deleteLabel)
  }

  @Test
  fun deviceCalendarEventNamesItsProviderSideDeleteAndCannotBeDeferred() {
    EventSource.DEVICE_WRITABLE.forEach { source ->
      val policy = agendaRowActionPolicy(source, isAllDay = false)

      assertFalse(source, policy.canMoveToTomorrow)
      assertEquals(source, "Delete from calendar", policy.deleteLabel)
    }
  }

  @Test
  fun readOnlyAndUnknownSourcesExposeNoMutationAction() {
    listOf(EventSource.NOTION, "Future integration").forEach { source ->
      val policy = agendaRowActionPolicy(source, isAllDay = false)

      assertFalse(source, policy.canMoveToTomorrow)
      assertNull(source, policy.deleteLabel)
    }
  }
}
