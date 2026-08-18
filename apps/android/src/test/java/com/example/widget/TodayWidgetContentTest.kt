package com.example.widget

import com.example.data.model.BriefingEvent
import com.example.data.model.PlanBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayWidgetContentTest {

  private fun minutes(hour: Int, minute: Int = 0): Long =
    (hour * 60L + minute) * 60_000L

  private fun event(id: String, startMs: Long, endMs: Long, allDay: Boolean = false) =
    BriefingEvent(
      id = id,
      title = id,
      startTime = startMs,
      endTime = endMs,
      source = "Manual",
      description = null,
      isDeadline = false,
      isUrgent = false,
      isAllDay = allDay,
      location = null,
    )

  private fun block(id: String, itemId: String, startMs: Long, endMs: Long) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = startMs,
      endAt = endMs,
      position = 0,
      locked = false,
      linkedEventId = null,
      createdAt = 0L,
      updatedAt = 0L,
    )

  private val dayStart = 0L
  private val dayEnd = 24 * 60 * 60_000L

  @Test
  fun `ongoing and upcoming entries win, past ones are dropped`() {
    val content =
      TodayWidgetContent.from(
        now = minutes(10, 30),
        dayStart = dayStart,
        dayEnd = dayEnd,
        events =
          listOf(
            event("over", minutes(9), minutes(10)),
            event("running", minutes(10), minutes(11)),
            event("later", minutes(11), minutes(12)),
          ),
        blocks = emptyList(),
        itemTitles = emptyMap(),
      )

    assertEquals(listOf("running", "later"), content.rows.map { it.title })
    assertEquals("Now · Up next", content.headline)
  }

  @Test
  fun `all-day events are markers and never take a row`() {
    val content =
      TodayWidgetContent.from(
        now = minutes(8),
        dayStart = dayStart,
        dayEnd = dayEnd,
        events = listOf(event("holiday", dayStart, dayEnd, allDay = true)),
        blocks = emptyList(),
        itemTitles = emptyMap(),
      )

    assertTrue(content.isEmpty)
    assertEquals("Nothing scheduled today", content.headline)
  }

  @Test
  fun `blocks are labelled with their task and merged with events in start order`() {
    val content =
      TodayWidgetContent.from(
        now = minutes(8),
        dayStart = dayStart,
        dayEnd = dayEnd,
        events = listOf(event("standup", minutes(9), minutes(9, 30))),
        blocks = listOf(block("b1", "item-1", minutes(10), minutes(11))),
        itemTitles = mapOf("item-1" to "Ship the widget"),
      )

    assertEquals(listOf("standup", "Ship the widget"), content.rows.map { it.title })
  }

  @Test
  fun `at most four rows are shown`() {
    val content =
      TodayWidgetContent.from(
        now = minutes(8),
        dayStart = dayStart,
        dayEnd = dayEnd,
        events =
          (1..7).map { i ->
            event("e$i", minutes(8 + i), minutes(9 + i))
          },
        blocks = emptyList(),
        itemTitles = emptyMap(),
      )

    assertEquals(TodayWidgetContent.MAX_ROWS, content.rows.size)
  }
}
