package com.example.core

import com.example.data.model.PlanItem
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonical product contracts for the scheduling engine.
 *
 * These scenarios pin behaviour at the *product* level — the week a person plans, the deadline
 * they set, the order they asked for — so a reimplementation on another platform (a server
 * running this same engine, an iOS port) is caught diverging on the contracts users rely on,
 * not just on unit-level details. Where today's behaviour is a known defect, the test documents
 * it as such rather than pretending it is correct.
 */
class EngineContractTest {
  private val zone = TimeZone.getTimeZone("UTC")
  private val schedule =
    WorkingCalendarSpec(
      zoneId = zone.id,
      weeklyWindows =
        (Calendar.MONDAY..Calendar.FRIDAY).map { WorkingWeekWindow(it, 9 * 60, 17 * 60) },
      minimumChunkMinutes = 30,
      maximumChunkMinutes = 120,
      bufferMinutes = 0,
    )

  // A Monday.
  private val monday = at(2036, Calendar.FEBRUARY, 4, 0, 0)
  private val weekEnd = monday + 5 * DAY_MS

  /**
   * Defect 1, fixed: the deadline is a hard constraint by default.
   *
   * A task due at 10:00 with a full day of effort gets only the working minutes before its
   * deadline, and the remainder is refused and named — never silently planned past the due date,
   * and never claimed to be "ahead of its due date" when it is not.
   */
  @Test
  fun `a deadline is a hard constraint by default`() {
    val dueAt = at(2036, Calendar.FEBRUARY, 4, 10, 0)
    val result =
      AutoPlan.propose(
        items = listOf(item("deliverable", effort = 8 * 60, dueAt = dueAt)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    assertTrue("something is still proposed before the deadline", result.proposals.isNotEmpty())
    result.proposals.forEach { proposal ->
      assertTrue("no proposal may end after its due date", proposal.endAt <= dueAt)
    }
    val placed = result.proposals.sumOf { minutes(it) }
    assertTrue("only the working minutes before the deadline are placed", placed <= 60)
    val refused = result.unplaced.single { it.itemId == "deliverable" }
    assertTrue(
      "the remainder is refused and named against the deadline",
      refused.reason.contains("past its due date"),
    )
  }

  /**
   * Defect 1, disclosed: under SOFT the planner still places work past a due date, but the reason
   * says so instead of claiming to be ahead of it.
   */
  @Test
  fun `a deadline is disclosed not enforced under SOFT`() {
    val dueAt = at(2036, Calendar.FEBRUARY, 4, 10, 0)
    val result =
      AutoPlan.propose(
        items = listOf(item("deliverable", effort = 8 * 60, dueAt = dueAt)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
        deadlinePolicy = DeadlinePolicy.SOFT,
      )

    val pastDeadline = result.proposals.filter { it.endAt > dueAt }
    assertTrue("SOFT still plans past the deadline", pastDeadline.isNotEmpty())
    pastDeadline.forEach { proposal ->
      assertTrue(
        "the reason must not claim to be ahead of a deadline it runs past",
        !proposal.reason.contains("ahead of its due date"),
      )
    }
  }

  /**
   * Defect 2, fixed: a start constraint is honoured — nothing is proposed before it, however the
   * ordering falls.
   */
  @Test
  fun `a start constraint is honoured`() {
    val wednesday = at(2036, Calendar.FEBRUARY, 6, 0, 0)
    val result =
      AutoPlan.propose(
        items = listOf(item("contract", effort = 60, startConstraint = wednesday)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
      )

    assertEquals(1, result.proposals.size)
    assertTrue(
      "no proposal may start before the start constraint",
      result.proposals.single().startAt >= wednesday,
    )
    assertEquals(
      "the earliest legal slot is 09:00 on the constraint day",
      at(2036, Calendar.FEBRUARY, 6, 9, 0),
      result.proposals.single().startAt,
    )
  }

  /**
   * DST contract at the planner level: proposals across a spring-forward week keep local-day
   * working windows. The gap normalises forward, so a Sunday window around the transition still
   * delivers its wall-clock minutes, and the planner's slot is never an invented one.
   */
  @Test
  fun `a spring-forward week keeps local day semantics end to end`() {
    val nyZone = TimeZone.getTimeZone("America/New_York")
    val springSunday = at("America/New_York", 2026, Calendar.MARCH, 8, 0, 0)
    val dstSpec =
      WorkingCalendarSpec(
        zoneId = "America/New_York",
        weeklyWindows =
          listOf(WorkingWeekWindow(Calendar.SUNDAY, 1 * 60, 4 * 60)),
      )

    val result =
      AutoPlan.propose(
        items = listOf(item("sunday-shift", effort = 120)),
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = dstSpec,
        rangeStartMs = springSunday,
        rangeEndMs = springSunday + DAY_MS,
        nowMs = springSunday,
      )

    assertEquals(1, result.proposals.size)
    val proposal = result.proposals.single()
    assertEquals(120, minutes(proposal))
    // 01:00–04:00 local survived the 02:00→03:00 gap: the window still delivers three local hours.
    assertEquals(at("America/New_York", 2026, Calendar.MARCH, 8, 1, 0), proposal.startAt)
    assertEquals(at("America/New_York", 2026, Calendar.MARCH, 8, 4, 0), proposal.endAt)
    assertTrue("the zone really is the DST zone", nyZone.observesDaylightTime())
  }

  /**
   * Determinism contract: an explicit preferred order is honoured and repeatable — the same
   * request twice produces the same plan, so a server can cache by request and a scenario
   * comparison never changes the plan it is comparing.
   */
  @Test
  fun `an explicit order is honoured and the plan is repeatable`() {
    val items =
      listOf(
        item("alpha", effort = 60, rank = 3),
        item("beta", effort = 60, rank = 2),
        item("gamma", effort = 60, rank = 1),
      )
    val request = { first: String ->
      AutoPlan.propose(
        items = items,
        blocks = emptyList(),
        fixedCommitments = emptyList(),
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = monday,
        rangeEndMs = weekEnd,
        nowMs = monday,
        preferredOrder = listOf(first, "beta", "gamma"),
      )
    }

    val reversed = request("alpha")
    assertEquals(
      listOf("alpha", "beta", "gamma"),
      reversed.proposals.map { it.itemId },
    )

    val again = request("alpha")
    assertEquals("the same request gives the same plan", reversed, again)
    assertEquals(
      "the tasks themselves are never reordered",
      listOf("alpha", "beta", "gamma"),
      items.map(PlanItem::id),
    )
  }

  private fun minutes(proposal: PlanProposal) =
    ((proposal.endAt - proposal.startAt) / 60_000L).toInt()

  private fun at(zoneId: String, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    at(zone.id, year, month, day, hour, minute)

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
      createdAt = monday,
      updatedAt = monday,
    )

  private companion object {
    const val MINUTE_MS = 60 * 1000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
  }
}