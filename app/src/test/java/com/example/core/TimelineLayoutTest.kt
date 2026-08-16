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

  private fun localAt(
    timeZone: TimeZone,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int = 0,
  ): Long =
    Calendar.getInstance(timeZone)
      .apply {
        clear()
        set(year, month, day, hour, minute, 0)
      }
      .timeInMillis

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
        utc,
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
        utc,
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
        utc,
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
        utc,
      )

    val byId = slots.associateBy { it.event.id }
    assertEquals(byId.getValue("a").lane, byId.getValue("c").lane)
    assertEquals(2, byId.getValue("a").lanes)
  }

  @Test
  fun `very short events are given a readable minimum height`() {
    val slots = TimelineLayout.layout(listOf(event("a", at(9), at(9, 5))), dayStart, utc)

    assertEquals(TimelineLayout.MIN_VISIBLE_MINUTES, slots.single().endMinute - slots.single().startMinute)
  }

  @Test
  fun `all-day entries are kept out of the grid`() {
    val events = listOf(event("banner", dayStart, dayStart + 24 * 3_600_000L, allDay = true))

    assertTrue(TimelineLayout.layout(events, dayStart, utc).isEmpty())
    assertEquals(listOf("banner"), TimelineLayout.allDay(events).map { it.id })
  }

  @Test
  fun `an event running past midnight is clamped to the day`() {
    val slots =
      TimelineLayout.layout(
        listOf(event("late", at(23), at(23) + 3 * 3_600_000L)),
        dayStart,
        utc,
      )

    assertEquals(24 * 60, slots.single().endMinute)
  }

  @Test
  fun `the now marker only exists inside its own day`() {
    assertEquals(9 * 60 + 30, TimelineLayout.nowMinute(at(9, 30), dayStart, utc))
    assertNull(TimelineLayout.nowMinute(dayStart - 1, dayStart, utc))
    assertNull(TimelineLayout.nowMinute(dayStart + 24 * 3_600_000L, dayStart, utc))
  }

  @Test
  fun `spring-forward uses local day bounds and wall-clock positions`() {
    val newYork = TimeZone.getTimeZone("America/New_York")
    val springStart = localAt(newYork, 2026, Calendar.MARCH, 8, 0)
    val springEnd = ScheduleAnalysis.startOfDayOffset(springStart, 1, newYork)
    val afterGapStart = localAt(newYork, 2026, Calendar.MARCH, 8, 3, 30)
    val lateNow = localAt(newYork, 2026, Calendar.MARCH, 8, 23, 30)
    val nextDayStart = localAt(newYork, 2026, Calendar.MARCH, 9, 0, 15)

    val slots =
      TimelineLayout.layout(
        listOf(
          event(
            "after-gap",
            afterGapStart,
            localAt(newYork, 2026, Calendar.MARCH, 8, 4, 30),
          ),
          event(
            "next-day",
            nextDayStart,
            localAt(newYork, 2026, Calendar.MARCH, 9, 0, 45),
          ),
        ),
        springStart,
        newYork,
      )

    assertEquals(23 * 3_600_000L, springEnd - springStart)
    assertEquals(listOf("after-gap"), slots.map { it.event.id })
    assertEquals(3 * 60 + 30, slots.single().startMinute)
    assertEquals(4 * 60 + 30, slots.single().endMinute)
    assertEquals(23 * 60 + 30, TimelineLayout.nowMinute(lateNow, springStart, newYork))
    assertNull(TimelineLayout.nowMinute(springEnd, springStart, newYork))
  }

  @Test
  fun `fall-back uses local day bounds and wall-clock positions`() {
    val newYork = TimeZone.getTimeZone("America/New_York")
    val fallStart = localAt(newYork, 2026, Calendar.NOVEMBER, 1, 0)
    val fallEnd = ScheduleAnalysis.startOfDayOffset(fallStart, 1, newYork)
    val lateStart = localAt(newYork, 2026, Calendar.NOVEMBER, 1, 23, 30)
    val nextDayStart = localAt(newYork, 2026, Calendar.NOVEMBER, 2, 0, 15)

    val slots =
      TimelineLayout.layout(
        listOf(
          event("late", lateStart, localAt(newYork, 2026, Calendar.NOVEMBER, 2, 0, 30)),
          event(
            "next-day",
            nextDayStart,
            localAt(newYork, 2026, Calendar.NOVEMBER, 2, 0, 45),
          ),
        ),
        fallStart,
        newYork,
      )

    assertEquals(25 * 3_600_000L, fallEnd - fallStart)
    assertEquals(listOf("late"), slots.map { it.event.id })
    assertEquals(23 * 60 + 30, slots.single().startMinute)
    assertEquals(24 * 60, slots.single().endMinute)
    assertEquals(23 * 60 + 30, TimelineLayout.nowMinute(lateStart, fallStart, newYork))
    assertNull(TimelineLayout.nowMinute(fallEnd, fallStart, newYork))
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
