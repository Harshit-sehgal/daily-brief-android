package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioAndScenarioTest {
  private val zone = TimeZone.getTimeZone("UTC")
  private val schedule =
    WorkingCalendarSpec(
      zoneId = zone.id,
      weeklyWindows =
        (Calendar.MONDAY..Calendar.FRIDAY).map { WorkingWeekWindow(it, 9 * 60, 17 * 60) },
      minimumChunkMinutes = 30,
      maximumChunkMinutes = 120,
    )
  private val monday = at(2036, Calendar.FEBRUARY, 4, 0, 0)
  private val weekEnd = monday + 5 * DAY

  @Test
  fun `a rollup counts unestimated work instead of letting it sum to zero`() {
    val result =
      PortfolioRollup.summarise(
        boards = listOf("b1" to "Atlas", "b2" to "Backlog"),
        itemsByBoard =
          mapOf(
            "b1" to
              listOf(
                item("a", boardId = "b1", effort = 120),
                item("b", boardId = "b1", effort = null),
                item("done", boardId = "b1", effort = 60, progress = 100),
                item("gone", boardId = "b1", effort = 999, archivedAt = 1L),
              ),
            "b2" to listOf(item("c", boardId = "b2", effort = 60, dueAt = monday - DAY)),
          ),
        blocksByItem = mapOf("a" to listOf(block("blk", "a", monday, monday + 60 * 60_000L))),
        nowMs = monday,
      )

    val atlas = result.rows.single { it.boardName == "Atlas" }
    assertEquals(2, atlas.openTaskCount)
    assertEquals(1, atlas.doneTaskCount)
    assertEquals(120, atlas.statedEffortMinutes)
    assertEquals(60, atlas.scheduledMinutes)
    assertEquals(60, atlas.unscheduledMinutes)
    assertEquals(1, atlas.unestimatedTaskCount)
    assertFalse("a board with unestimated work is not a complete figure", atlas.isComplete)

    val backlog = result.rows.single { it.boardName == "Backlog" }
    assertEquals(1, backlog.overdueTaskCount)
    assertTrue(backlog.isComplete)

    // Archived work never reaches the totals, and the note says the totals are a floor.
    assertEquals(3, result.totalOpenTasks)
    assertFalse(result.isComplete)
    assertTrue(result.note, result.note.contains("floor"))
    // Boards come back in a stable, readable order.
    assertEquals(listOf("Atlas", "Backlog"), result.rows.map(PortfolioRow::boardName))
  }

  @Test
  fun `scenarios plan the same week different ways under the same rules`() {
    val items =
      listOf(
        item("big-urgent", effort = 120, priority = PlanPriority.URGENT, dueAt = monday + 4 * DAY, rank = 3),
        item("small-normal", effort = 30, dueAt = monday + 4 * DAY, rank = 2),
        item("due-soon", effort = 60, dueAt = monday + DAY, rank = 1),
      )
    val scenarios =
      PlanScenarios.compare(
        items = items,
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    assertEquals(listOf("due", "priority", "short"), scenarios.map(PlanScenario::key))
    scenarios.forEach { scenario ->
      assertTrue(scenario.rationale, scenario.rationale.isNotBlank())
      assertEquals(3, scenario.placedTaskCount)
      // Every scenario obeys the same rules, whatever order it chose.
      scenario.result.proposals.forEach { proposal ->
        assertEquals(0L, proposal.startAt % 60_000L)
        assertTrue(((proposal.endAt - proposal.startAt) / 60_000L) >= 30)
      }
    }

    fun firstOf(key: String) =
      scenarios.single { it.key == key }.result.proposals.first().itemId

    assertEquals("due-soon", firstOf("due"))
    assertEquals("big-urgent", firstOf("priority"))
    assertEquals("small-normal", firstOf("short"))
  }

  @Test
  fun `the summary says when the ordering makes no difference`() {
    // Two tasks, a whole free week: every ordering finishes at the same point, so offering the
    // choice as if it mattered would mislead. The wording has to admit that.
    val roomy =
      PlanScenarios.compare(
        items =
          listOf(
            item("a", effort = 60, dueAt = monday + 4 * DAY, rank = 1),
            item("b", effort = 60, dueAt = monday + 4 * DAY, rank = 2),
          ),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )
    val sameness = PlanScenarios.describeSpread(roomy)
    assertTrue(sameness, sameness.contains("does not change this week"))

    // Nothing placeable at all is stated outright rather than shown as three empty offers.
    val unplaceable =
      PlanScenarios.compare(
        items = listOf(item("no-effort", effort = null, dueAt = monday + DAY, rank = 1)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )
    val nothing = PlanScenarios.describeSpread(unplaceable)
    assertTrue(nothing, nothing.startsWith("None of these orderings can place anything"))
    assertEquals("Nothing to compare yet.", PlanScenarios.describeSpread(emptyList()))
  }

  @Test
  fun `a scenario never edits the tasks it reorders`() {
    val original = listOf(item("a", effort = 60, dueAt = monday + DAY, rank = 7))
    val snapshot = original.map { it.copy() }

    PlanScenarios.compare(
      items = original,
      blocks = emptyList(),
      fixedCommitments = emptyList(),
      dependencies = emptyList(),
      schedule = schedule,
      rangeStartMs = monday,
      rangeEndMs = weekEnd,
      nowMs = monday,
    )

    // Re-ranking happens on copies; the caller's tasks keep their rank and due date.
    assertEquals(snapshot, original)
  }

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance(zone).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis

  private fun item(
    id: String,
    boardId: String = "board",
    effort: Int?,
    progress: Int = 0,
    priority: String = PlanPriority.NORMAL,
    dueAt: Long? = null,
    rank: Long = 1,
    archivedAt: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = boardId,
      title = id,
      rank = rank,
      effortMinutes = effort,
      progress = progress,
      priority = priority,
      dueAt = dueAt,
      archivedAt = archivedAt,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(id: String, itemId: String, startAt: Long, endAt: Long) =
    PlanBlock(id = id, planItemId = itemId, startAt = startAt, endAt = endAt, createdAt = 1, updatedAt = 1)

  private companion object {
    const val DAY = 24 * 60 * 60 * 1000L
  }
  @Test
  fun `a portfolio with no plans says so instead of reporting an exact zero`() {
    val empty = PortfolioRollup.summarise(emptyList(), emptyMap(), emptyMap(), monday)
    assertEquals("No plans yet.", empty.note)
    assertEquals(0, empty.totalOpenTasks)
    assertEquals(0, empty.totalUnscheduledMinutes)

    // A plan that exists but holds nothing is a different statement from having no plans at all.
    val emptyBoard =
      PortfolioRollup.summarise(listOf("b1" to "Fresh"), mapOf("b1" to emptyList()), emptyMap(), monday)
    assertEquals(1, emptyBoard.rows.size)
    assertEquals(0, emptyBoard.rows.single().openTaskCount)
    assertTrue(emptyBoard.note, emptyBoard.note.contains("exact"))
  }

  @Test
  fun `finished work never counts as unscheduled effort`() {
    val result =
      PortfolioRollup.summarise(
        boards = listOf("b1" to "Atlas"),
        itemsByBoard =
          mapOf(
            "b1" to
              listOf(
                item("done", boardId = "b1", effort = 240, progress = 100),
                item("open", boardId = "b1", effort = 60),
              )
          ),
        blocksByItem = emptyMap(),
        nowMs = monday,
      )
    val row = result.rows.single()
    assertEquals(60, row.statedEffortMinutes)
    assertEquals(60, row.unscheduledMinutes)
    assertEquals(1, row.doneTaskCount)
  }

  @Test
  fun `over-scheduled work floors at zero rather than reporting negative effort`() {
    val result =
      PortfolioRollup.summarise(
        boards = listOf("b1" to "Atlas"),
        itemsByBoard = mapOf("b1" to listOf(item("a", boardId = "b1", effort = 30))),
        blocksByItem =
          mapOf("a" to listOf(block("blk", "a", monday, monday + 3 * 60 * 60_000L))),
        nowMs = monday,
      )
    val row = result.rows.single()
    assertEquals(180, row.scheduledMinutes)
    assertEquals(0, row.unscheduledMinutes)
  }
}
