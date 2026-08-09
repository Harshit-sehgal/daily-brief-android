package com.example.core

import com.example.data.model.BriefingEvent
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLayoutTest {

  private val utc = TimeZone.getTimeZone("UTC")

  private val dayStart =
    Calendar.getInstance(utc)
      .apply {
        clear()
        set(2026, 2, 5, 0, 0, 0)
      }
      .timeInMillis

  private fun at(hour: Int, minute: Int = 0) = dayStart + (hour * 60L + minute) * 60_000L

  private fun event(id: String, from: Long, to: Long, allDay: Boolean = false) =
    BriefingEvent(
      id = id,
      title = id,
      startTime = from,
      endTime = to,
      source = "Manual",
      description = null,
      isDeadline = false,
      isUrgent = false,
      isAllDay = allDay,
    )

  @Test
  fun `separate events each get the full width`() {
    val slots =
      TimelineLayout.layout(
        listOf(event("a", at(9), at(10)), event("b", at(11), at(12))),
        dayStart,
      )

    assertEquals(2, slots.size)
    assertTrue(slots.all { it.lanes == 1 && it.lane == 0 })
  }

  @Test
  fun `two overlapping events split into two lanes`() {
    val slots =
      TimelineLayout.layout(
        listOf(event("a", at(9), at(11)), event("b", at(10), at(12))),
        dayStart,
      )

    assertEquals(setOf(0, 1), slots.map { it.lane }.toSet())
    assertTrue(slots.all { it.lanes == 2 })
  }

  @Test
  fun `a chain of overlaps shares one width but reuses freed lanes`() {
    // A/B overlap and B/C overlap, but A and C never do. All three belong to one
    // cluster so they must agree on the width — yet C can take A's lane back,
    // which keeps the blocks as wide as possible. Two lanes, not three.
    val slots =
      TimelineLayout.layout(
        listOf(
          event("a", at(9), at(10, 30)),
          event("b", at(10), at(11, 30)),
          event("c", at(11), at(12)),
        ),
        dayStart,
      )

    assertEquals(3, slots.size)
    assertTrue("every event in a cluster shares its width", slots.all { it.lanes == 2 })
    val byId = slots.associateBy { it.event.id }
    assertEquals(byId.getValue("a").lane, byId.getValue("c").lane)
    assertNotEquals(byId.getValue("a").lane, byId.getValue("b").lane)
  }

  @Test
  fun `a lane is reused once its previous event has finished`() {
    val slots =
      TimelineLayout.layout(
        listOf(
          event("a", at(9), at(10)),
          event("b", at(9, 30), at(10, 30)),
          event("c", at(10), at(11)),
        ),
        dayStart,
      )

    val byId = slots.associateBy { it.event.id }
    assertEquals(byId.getValue("a").lane, byId.getValue("c").lane)
    assertEquals(2, byId.getValue("a").lanes)
  }

  @Test
  fun `very short events are given a readable minimum height`() {
    val slots = TimelineLayout.layout(listOf(event("a", at(9), at(9, 5))), dayStart)

    assertEquals(TimelineLayout.MIN_VISIBLE_MINUTES, slots.single().endMinute - slots.single().startMinute)
  }

  @Test
  fun `all-day entries are kept out of the grid`() {
    val events = listOf(event("banner", dayStart, dayStart + 24 * 3_600_000L, allDay = true))

    assertTrue(TimelineLayout.layout(events, dayStart).isEmpty())
    assertEquals(listOf("banner"), TimelineLayout.allDay(events).map { it.id })
  }

  @Test
  fun `an event running past midnight is clamped to the day`() {
    val slots =
      TimelineLayout.layout(listOf(event("late", at(23), at(23) + 3 * 3_600_000L)), dayStart)

    assertEquals(24 * 60, slots.single().endMinute)
  }

  @Test
  fun `the now marker only exists inside its own day`() {
    assertEquals(9 * 60 + 30, TimelineLayout.nowMinute(at(9, 30), dayStart))
    assertNull(TimelineLayout.nowMinute(dayStart - 1, dayStart))
    assertNull(TimelineLayout.nowMinute(dayStart + 24 * 3_600_000L, dayStart))
  }

  @Test
  fun `dragging snaps to the nearest quarter hour and stays in the day`() {
    assertEquals(600, TimelineLayout.snapMinute(597))
    assertEquals(615, TimelineLayout.snapMinute(608))
    assertEquals(0, TimelineLayout.snapMinute(-40))
    // A 60-minute block cannot start later than 23:00.
    assertEquals(24 * 60 - 60, TimelineLayout.snapMinute(24 * 60, durationMinutes = 60))
  }
}
