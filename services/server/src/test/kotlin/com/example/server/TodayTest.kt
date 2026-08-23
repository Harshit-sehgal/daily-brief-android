package com.example.server

import com.example.data.model.BriefingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayTest {
  @Test
  fun `planned work is included in conflict detection with its human title`() {
    val event = event("calendar", "Client call", 10 * HOUR, 11 * HOUR, "Google Calendar")
    val block = event("plan:block-1", "Write proposal", 10 * HOUR + 30 * MINUTE, 11 * HOUR + 30 * MINUTE, "plan")

    val conflicts = Today.conflictWires(listOf(event), listOf(block))

    assertEquals(1, conflicts.size)
    assertEquals("Client call", conflicts.single().first.title)
    assertEquals("Write proposal", conflicts.single().second.title)
    assertEquals(30 * MINUTE, conflicts.single().overlapMs)
    assertTrue(conflicts.single().second.source == "plan")
  }

  @Test
  fun `all-day provider markers do not create false plan conflicts`() {
    val allDay = event("holiday", "Holiday", 0, 24 * HOUR, "Google Calendar", allDay = true)
    val block = event("plan:block-1", "Write proposal", 10 * HOUR, 11 * HOUR, "plan")

    assertTrue(Today.conflictWires(listOf(allDay), listOf(block)).isEmpty())
  }

  private fun event(id: String, title: String, start: Long, end: Long, source: String, allDay: Boolean = false) =
    BriefingEvent(
      id = id,
      title = title,
      startTime = start,
      endTime = end,
      source = source,
      description = null,
      isDeadline = false,
      isUrgent = false,
      isAllDay = allDay,
    )

  private companion object {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
  }
}
