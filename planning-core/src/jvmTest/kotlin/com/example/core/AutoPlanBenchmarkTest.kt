package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server-shaped AutoPlan workload: 800 tasks over a four-week horizon.
 *
 * The V1 architecture runs the planner server-side for many tenants over longer horizons, so the
 * phone-shaped input (one week, tens of tasks) is the wrong budget. This benchmark is a regression
 * tripwire, not a profiler: it asserts the planner stays interactive at the scale a server would
 * actually see. Measure, then optimise, never the other way around.
 */
class AutoPlanBenchmarkTest {
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

  private val start = at(2036, Calendar.JANUARY, 7, 0, 0)
  private val fourWeeksLater = start + 4 * 7 * DAY

  @Test
  fun `800 tasks over four weeks stay under the interactive budget`() {
    val items =
      (0 until 800).map { index ->
        val week = index / 200
        item(
          "task-${index.toString().padStart(3, '0')}",
          effort = 20 + (index * 7) % 70,
          dueAt = start + week * WEEK + ((index * 13) % 4) * DAY + 17 * HOUR,
        )
      }
    val commitments =
      (0 until 20).map { index ->
        WorkingInterval(
          start + (index * 7) % 28 * DAY + 9 * HOUR,
          start + (index * 7) % 28 * DAY + 10 * HOUR,
        )
      }

    val started = System.nanoTime()
    val result =
      AutoPlan.propose(
        items = items,
        blocks = emptyList(),
        fixedCommitments = commitments,
        dependencies = emptyList(),
        schedule = schedule,
        rangeStartMs = start,
        rangeEndMs = fourWeeksLater,
        nowMs = start,
      )
    val elapsedMs = (System.nanoTime() - started) / 1_000_000L

    // A plan worth showing is worth showing promptly; this is the server-side budget.
    assertTrue("800 tasks took $elapsedMs ms", elapsedMs < 5_000L)
    // The workload still demands several times the horizon's working minutes, so a complete
    // plan is impossible by construction — the benchmark measures search cost, not placement.
    assertTrue("the search should still place a substantial share", result.proposals.size > 100)
    // Whole-minute invariants hold at scale too.
    result.proposals.forEach { proposal ->
      assertTrue(proposal.startAt % 60_000L == 0L)
      assertTrue(proposal.endAt % 60_000L == 0L)
    }
  }

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance(zone).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis

  private fun item(
    id: String,
    effort: Int,
    dueAt: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      rank = 1,
      effortMinutes = effort,
      progress = 0,
      locked = false,
      dueAt = dueAt,
      createdAt = 1,
      updatedAt = 1,
    )

  private companion object {
    const val HOUR = 60 * 60 * 1000L
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY
  }
}