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
   * Defect 1, characterised, not asserted correct.
   *
   * The engine sorts by due date and *claims* in its reason string to place work "ahead of its
   * due date", but nothing stops a proposal from ending after `dueAt`. This test pins today's
   * behaviour so the defect stays visible: a task due at 10:00 with a full day of effort is
   * planned anyway, and the explanation still says it is ahead of its due date. The product
   * decision (treat a deadline as a hard constraint, or disclose the breach) is tracked as
   * engine defect 1 — this test flips the moment it is fixed.
   */
  @Test
  fun `a deadline is not yet a constraint`() {
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

    assertTrue("the task is still planned past its deadline", result.proposals.isNotEmpty())
    val last = result.proposals.sortedBy(PlanProposal::startAt).last()
    assertTrue("proposal ends after the due date", last.endAt > dueAt)
    assertTrue(
      "the reason still claims it is ahead of its due date",
      result.proposals.any { it.reason.contains("ahead of its due date") },
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
      createdAt = monday,
      updatedAt = monday,
    )

  private companion object {
    const val MINUTE_MS = 60 * 1000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
  }
}