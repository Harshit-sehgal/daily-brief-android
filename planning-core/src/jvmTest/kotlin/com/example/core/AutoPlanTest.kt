package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlanTest {
  private val zone = TimeZone.getTimeZone("UTC")
  private val schedule =
    WorkingCalendarSpec(
      zoneId = zone.id,
      weeklyWindows =
        (Calendar.MONDAY..Calendar.FRIDAY).map { WorkingWeekWindow(it, 9 * 60, 17 * 60) },
      minimumChunkMinutes = 30,
      maximumChunkMinutes = 120,
      bufferMinutes = 15,
    )

  // A Monday.
  private val monday = at(2036, Calendar.FEBRUARY, 4, 0, 0)
  private val weekEnd = monday + 5 * DAY

  @Test
  fun `proposals sit in working time, respect chunk limits and explain themselves`() {
    val result =
      AutoPlan.propose(
        items = listOf(item("write", effort = 180)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    // 180 minutes at a 120-minute ceiling is two blocks, not one long one.
    assertEquals(2, result.proposals.size)
    assertEquals(120, minutes(result.proposals[0]))
    assertEquals(60, minutes(result.proposals[1]))
    assertEquals(at(2036, Calendar.FEBRUARY, 4, 9, 0), result.proposals[0].startAt)
    assertEquals(at(2036, Calendar.FEBRUARY, 4, 11, 0), result.proposals[1].startAt)
    result.proposals.forEach { assertTrue(it.reason, it.reason.contains("working time")) }
    assertTrue(result.explanation, result.explanation.contains("Nothing is saved until you apply it"))
  }

  @Test
  fun `existing blocks and buffered commitments are worked around, never over`() {
    val commitment =
      WorkingInterval(at(2036, Calendar.FEBRUARY, 4, 9, 30), at(2036, Calendar.FEBRUARY, 4, 10, 30))
    val result =
      AutoPlan.propose(
        items = listOf(item("write", effort = 60)),
        blocks =
          listOf(block("existing", "other", at(2036, Calendar.FEBRUARY, 4, 11, 0), at(2036, Calendar.FEBRUARY, 4, 12, 0))),
        fixedCommitments = listOf(commitment),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    val first = result.proposals.single()
    // 09:00–09:15 is too short, 09:15–10:45 belongs to the meeting and its buffer, and 10:45–11:00
    // is under the 30-minute floor — so the first honest slot is after the existing block.
    assertEquals(at(2036, Calendar.FEBRUARY, 4, 12, 0), first.startAt)
    assertEquals(60, minutes(first))
    val bufferedCommitment =
      WorkingInterval(at(2036, Calendar.FEBRUARY, 4, 9, 15), at(2036, Calendar.FEBRUARY, 4, 10, 45))
    assertTrue(
      "a proposal never overlaps a commitment or its buffer",
      first.startAt >= bufferedCommitment.endAt || first.endAt <= bufferedCommitment.startAt,
    )
    assertTrue(
      "a proposal never overlaps an existing block",
      first.startAt >= at(2036, Calendar.FEBRUARY, 4, 12, 0),
    )
  }

  @Test
  fun `what it will not place, it names`() {
    val result =
      AutoPlan.propose(
        items =
          listOf(
            item("unknown", effort = null),
            item("locked", effort = 60, locked = true),
            item("done", effort = 60, progress = 100),
          ),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    assertTrue(result.proposals.isEmpty())
    val reasons = result.unplaced.associate { it.itemId to it.reason }
    assertTrue(reasons.getValue("unknown"), reasons.getValue("unknown").contains("No effort stated"))
    assertTrue(reasons.getValue("locked"), reasons.getValue("locked").contains("Locked"))
    // Finished work is simply not a candidate, and does not need an excuse.
    assertTrue(result.unplaced.none { it.itemId == "done" })
  }

  @Test
  fun `a finish-to-start successor waits for the predecessor to end`() {
    val result =
      AutoPlan.propose(
        items =
          listOf(
            item("first", effort = 60, rank = 1),
            item("second", effort = 60, rank = 2),
          ),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies =
          listOf(dependency("d1", "first", "second", PlanDependencyType.FINISH_TO_START)),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    val firstEnd = result.proposals.single { it.itemId == "first" }.endAt
    val second = result.proposals.single { it.itemId == "second" }
    assertTrue("a successor cannot start before its predecessor ends", second.startAt >= firstEnd)
    assertTrue(second.reason, second.reason.contains("after first finishes"))
  }

  @Test
  fun `a start-to-start successor may begin as soon as the predecessor does`() {
    val result =
      AutoPlan.propose(
        items =
          listOf(
            item("first", effort = 60, rank = 1),
            item("second", effort = 60, rank = 2),
          ),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies =
          listOf(dependency("d1", "first", "second", PlanDependencyType.START_TO_START)),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    val firstStart = result.proposals.single { it.itemId == "first" }.startAt
    val second = result.proposals.single { it.itemId == "second" }
    assertTrue("an SS successor may start from the predecessor's start", second.startAt >= firstStart)
    assertTrue(second.reason, second.reason.contains("from when first starts"))
  }

  @Test
  fun `a start-to-start lag pushes the successor past the predecessor's start plus the lag`() {
    val result =
      AutoPlan.propose(
        items =
          listOf(
            item("first", effort = 60, rank = 1),
            item("second", effort = 60, rank = 2),
          ),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies =
          listOf(
            PlanDependency(
              id = "d1",
              boardId = "board",
              predecessorId = "first",
              successorId = "second",
              type = PlanDependencyType.START_TO_START,
              lagMinutes = 60,
              createdAt = 1,
              updatedAt = 1,
            ),
          ),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    val firstStart = result.proposals.single { it.itemId == "first" }.startAt
    val second = result.proposals.single { it.itemId == "second" }
    assertTrue("an SS lag delays the successor by the full lag", second.startAt >= firstStart + 60 * 60_000L)
  }

  @Test
  fun `a finish-to-finish successor ends after the predecessor, lag included`() {
    val result =
      AutoPlan.propose(
        items =
          listOf(
            item("first", effort = 60, rank = 1),
            item("second", effort = 60, rank = 2),
          ),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies =
          listOf(
            PlanDependency(
              id = "d1",
              boardId = "board",
              predecessorId = "first",
              successorId = "second",
              type = PlanDependencyType.FINISH_TO_FINISH,
              lagMinutes = 30,
              createdAt = 1,
              updatedAt = 1,
            ),
          ),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    val firstEnd = result.proposals.single { it.itemId == "first" }.endAt
    val second = result.proposals.single { it.itemId == "second" }
    assertTrue("an FF successor ends after its predecessor plus the lag", second.endAt >= firstEnd + 30 * 60_000L)
  }

  @Test
  fun `a start-to-finish successor ends after the predecessor starts, however late the predecessor`() {
    val result =
      AutoPlan.propose(
        items =
          listOf(
            item("first", effort = 60, rank = 1, startConstraint = at(2036, Calendar.FEBRUARY, 4, 14, 0)),
            item("second", effort = 60, rank = 2),
          ),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies =
          listOf(dependency("d1", "first", "second", PlanDependencyType.START_TO_FINISH)),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    val firstStart = result.proposals.single { it.itemId == "first" }.startAt
    val second = result.proposals.single { it.itemId == "second" }
    assertEquals(at(2036, Calendar.FEBRUARY, 4, 14, 0), firstStart)
    // Without the SF link the successor would sit at 09:00; with it, it must end after 14:00,
    // so the greedy parks it at 13:00–14:00.
    assertEquals(at(2036, Calendar.FEBRUARY, 4, 13, 0), second.startAt)
    assertTrue(second.endAt >= firstStart)
  }

  @Test
  fun `the past is never planned into and the same inputs give the same plan`() {
    val midMorning = at(2036, Calendar.FEBRUARY, 4, 10, 20)
    val result =
      AutoPlan.propose(
        items = listOf(item("write", effort = 60)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = midMorning,
      )
    assertTrue(result.proposals.all { it.startAt >= midMorning })
    // Every block starts and ends on a whole minute, whatever the clock said when it was proposed.
    val ragged =
      AutoPlan.propose(
        items = listOf(item("write", effort = 60)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = midMorning + 37_123L,
      )
    ragged.proposals.forEach { proposal ->
      assertEquals(0L, proposal.startAt % 60_000L)
      assertEquals(0L, proposal.endAt % 60_000L)
      assertTrue(proposal.startAt >= midMorning + 37_123L)
    }

    val again =
      AutoPlan.propose(
        items = listOf(item("write", effort = 60)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = midMorning,
      )
    assertEquals(result, again)
  }

  private fun minutes(proposal: PlanProposal) = ((proposal.endAt - proposal.startAt) / 60_000L).toInt()

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance(zone).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis

  private fun item(
    id: String,
    effort: Int?,
    progress: Int = 0,
    locked: Boolean = false,
    rank: Long = 1,
    dueAt: Long? = null,
    startConstraint: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      rank = rank,
      effortMinutes = effort,
      progress = progress,
      locked = locked,
      dueAt = dueAt,
      startConstraint = startConstraint,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(id: String, itemId: String, startAt: Long, endAt: Long) =
    PlanBlock(id = id, planItemId = itemId, startAt = startAt, endAt = endAt, createdAt = 1, updatedAt = 1)

  private fun dependency(id: String, predecessor: String, successor: String, type: String) =
    PlanDependency(
      id = id,
      boardId = "board",
      predecessorId = predecessor,
      successorId = successor,
      type = type,
      lagMinutes = 0,
      createdAt = 1,
      updatedAt = 1,
    )

  private companion object {
    const val DAY = 24 * 60 * 60 * 1000L
  }
}
