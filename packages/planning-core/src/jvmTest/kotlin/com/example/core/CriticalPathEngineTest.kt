package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalPathEngineTest {
  @Test
  fun block_and_effort_durations_produce_a_deterministic_linear_schedule() {
    val items =
      listOf(
        item("a", effort = 999),
        item("b", effort = 30),
        item("c", effort = 45),
        item("independent", effort = 20),
      )
    val blocks =
      listOf(
        block("a-2", "a", 60, 90),
        block("a-1", "a", 0, 30),
      )
    val dependencies =
      listOf(
        dependency("ab", "a", "b", lag = 15),
        dependency("bc", "b", "c"),
      )

    val result = CriticalPathEngine.analyze(items, blocks, dependencies)
    val permuted =
      CriticalPathEngine.analyze(items.reversed(), blocks.reversed(), dependencies.reversed())

    assertTrue(result.isComplete)
    assertEquals(result, permuted)
    assertEquals(150L, result.projectDurationMinutes)
    assertEquals(listOf(listOf("a", "b", "c")), result.criticalPaths)
    assertTiming(result, "a", 60, 0, 60, 0, 60, 0, true)
    assertTiming(result, "b", 30, 75, 105, 75, 105, 0, true)
    assertTiming(result, "c", 45, 105, 150, 105, 150, 0, true)
    assertTiming(result, "independent", 20, 0, 20, 130, 150, 130, false)
  }

  @Test
  fun all_dependency_types_use_generalized_precedence_with_signed_lead_and_lag() {
    data class Case(
      val type: String,
      val lag: Int,
      val successorStart: Long,
      val successorLatestStart: Long,
      val successorSlack: Long,
      val projectDuration: Long,
      val criticalPaths: List<List<String>>,
    )

    val cases =
      listOf(
        Case(
          PlanDependencyType.FINISH_TO_START,
          -15,
          45,
          45,
          0,
          75,
          listOf(listOf("a", "b")),
        ),
        Case(
          PlanDependencyType.START_TO_START,
          20,
          20,
          30,
          10,
          60,
          listOf(listOf("a")),
        ),
        Case(
          PlanDependencyType.FINISH_TO_FINISH,
          10,
          40,
          40,
          0,
          70,
          listOf(listOf("a", "b")),
        ),
        Case(
          PlanDependencyType.START_TO_FINISH,
          45,
          15,
          30,
          15,
          60,
          listOf(listOf("a")),
        ),
      )

    cases.forEach { case ->
      val result =
        CriticalPathEngine.analyze(
          items = listOf(item("a", effort = 60), item("b", effort = 30)),
          blocks = emptyList(),
          dependencies = listOf(dependency("edge", "a", "b", case.type, case.lag)),
        )

      assertTrue("Expected complete result for " + case.type, result.isComplete)
      assertEquals(
        "Wrong generalized-precedence offset for " + case.type,
        case.successorStart,
        result.timing("b").earliestStartMinute,
      )
      assertEquals(case.successorLatestStart, result.timing("b").latestStartMinute)
      assertEquals(case.successorSlack, result.timing("b").totalSlackMinutes)
      assertEquals(case.projectDuration, result.projectDurationMinutes)
      assertEquals(case.criticalPaths, result.criticalPaths)
    }
  }

  @Test
  fun branching_network_reports_multiple_critical_paths_and_total_slack() {
    val result =
      CriticalPathEngine.analyze(
        items =
          listOf(
            item("a", 10),
            item("b", 10),
            item("c", 10),
            item("d", 10),
            item("e", 5),
          ),
        blocks = emptyList(),
        dependencies =
          listOf(
            dependency("ab", "a", "b"),
            dependency("ac", "a", "c"),
            dependency("bd", "b", "d"),
            dependency("cd", "c", "d"),
          ),
      )

    assertTrue(result.isComplete)
    assertEquals(30L, result.projectDurationMinutes)
    assertEquals(
      listOf(listOf("a", "b", "d"), listOf("a", "c", "d")),
      result.criticalPaths,
    )
    assertEquals(25L, result.timing("e").totalSlackMinutes)
    assertEquals(false, result.timing("e").isCritical)
    listOf("a", "b", "c", "d").forEach { id ->
      assertEquals(0L, result.timing(id).totalSlackMinutes)
      assertEquals(true, result.timing(id).isCritical)
    }
  }

  @Test
  fun incomplete_or_invalid_durations_never_claim_a_schedule_or_critical_path() {
    val result =
      CriticalPathEngine.analyze(
        items =
          listOf(
            item("known", 10),
            item("unknown", null),
            item("zero", 0),
            item("bad-block", 20),
            item("milestone", null, milestone = true),
          ),
        blocks =
          listOf(
            block("invalid", "bad-block", 10, 10),
            block("milestone-block", "milestone", 0, 5),
          ),
        dependencies = listOf(dependency("required", "known", "unknown")),
      )

    assertFalse(result.isComplete)
    assertNull(result.projectDurationMinutes)
    assertTrue(result.criticalPaths.isEmpty())
    assertTrue(result.taskTimings.all { it.isCritical == null })
    assertEquals(
      setOf("unknown", "zero", "bad-block", "milestone"),
      result.incompleteItems.map { it.itemId }.toSet(),
    )
    assertTrue(result.hasIssue(CriticalPathIssueCode.UNKNOWN_DURATION))
    assertTrue(result.hasIssue(CriticalPathIssueCode.NON_POSITIVE_DURATION))
    assertTrue(result.hasIssue(CriticalPathIssueCode.INVALID_BLOCK_DURATION))
    assertTrue(result.hasIssue(CriticalPathIssueCode.MILESTONE_HAS_BLOCKS))
  }

  @Test
  fun corrupt_dependency_graphs_report_every_required_validation_boundary() {
    val items =
      listOf(
        item("a", 10),
        item("b", 10),
        item("c", 10),
        item("other-board", 10, boardId = "other"),
      )
    val dependencies =
      listOf(
        dependency("missing", "a", "gone"),
        dependency("cross", "a", "other-board"),
        dependency("unknown", "b", "c", type = "mystery"),
        dependency("duplicate-1", "a", "b"),
        dependency("duplicate-2", "a", "b", type = PlanDependencyType.START_TO_START),
        dependency("cycle-1", "a", "c"),
        dependency("cycle-2", "c", "a"),
      )

    val result =
      CriticalPathEngine.analyze(
        items,
        blocks = listOf(block("orphan", "archived-or-missing", 0, 10)),
        dependencies,
      )

    assertFalse(result.isComplete)
    assertNull(result.projectDurationMinutes)
    assertTrue(result.criticalPaths.isEmpty())
    assertTrue(result.hasIssue(CriticalPathIssueCode.MISSING_SUCCESSOR))
    assertTrue(result.hasIssue(CriticalPathIssueCode.CROSS_BOARD_DEPENDENCY))
    assertTrue(result.hasIssue(CriticalPathIssueCode.UNKNOWN_DEPENDENCY_TYPE))
    assertTrue(result.hasIssue(CriticalPathIssueCode.DUPLICATE_DEPENDENCY_EDGE))
    assertTrue(result.hasIssue(CriticalPathIssueCode.DEPENDENCY_CYCLE))
    assertTrue(result.hasIssue(CriticalPathIssueCode.ORPHAN_BLOCK))
  }

  @Test
  fun duration_overflow_fails_closed_instead_of_wrapping() {
    val result =
      CriticalPathEngine.analyze(
        items = listOf(item("a", 10)),
        blocks =
          listOf(
            PlanBlock(
              id = "overflow",
              planItemId = "a",
              startAt = Long.MIN_VALUE,
              endAt = Long.MAX_VALUE,
              createdAt = 1,
              updatedAt = 1,
            )
          ),
        dependencies = emptyList(),
      )

    assertFalse(result.isComplete)
    assertTrue(result.hasIssue(CriticalPathIssueCode.BLOCK_DURATION_OVERFLOW))
    assertEquals(IncompleteDurationReason.INVALID_BLOCKS, result.incompleteItems.single().reason)
    assertNull(result.projectDurationMinutes)
    assertTrue(result.criticalPaths.isEmpty())
  }

  @Test
  fun milestones_are_the_only_valid_zero_duration_items() {
    val result =
      CriticalPathEngine.analyze(
        items = listOf(item("task", 10), item("milestone", null, milestone = true)),
        blocks = emptyList(),
        dependencies = listOf(dependency("finish", "task", "milestone")),
      )

    assertTrue(result.isComplete)
    assertEquals(10L, result.projectDurationMinutes)
    assertTiming(result, "milestone", 0, 10, 10, 10, 10, 0, true)
    assertEquals(listOf(listOf("task", "milestone")), result.criticalPaths)
  }

  private fun assertTiming(
    result: CriticalPathResult,
    itemId: String,
    duration: Long,
    earliestStart: Long,
    earliestFinish: Long,
    latestStart: Long,
    latestFinish: Long,
    slack: Long,
    critical: Boolean,
  ) {
    assertEquals(
      CriticalPathTaskTiming(
        itemId = itemId,
        durationMinutes = duration,
        earliestStartMinute = earliestStart,
        earliestFinishMinute = earliestFinish,
        latestStartMinute = latestStart,
        latestFinishMinute = latestFinish,
        totalSlackMinutes = slack,
        isCritical = critical,
      ),
      result.timing(itemId),
    )
  }

  private fun CriticalPathResult.timing(itemId: String): CriticalPathTaskTiming =
    taskTimings.single { it.itemId == itemId }

  private fun CriticalPathResult.hasIssue(code: CriticalPathIssueCode): Boolean =
    errors.any { it.code == code }

  @Test
  fun a_large_network_stays_deterministic_and_one_unknown_duration_collapses_it_honestly() {
    // A wide fan of parallel branches over one spine: big enough that an accidental
    // exponential traversal or a map-iteration-order dependency would show up.
    val branchCount = 40
    val branchLength = 10
    val items = mutableListOf(item("start", effort = 30))
    val dependencies = mutableListOf<PlanDependency>()
    repeat(branchCount) { branch ->
      var previous = "start"
      repeat(branchLength) { step ->
        // The last branch is the longest by one minute, so the critical path is knowable.
        val extra = if (branch == branchCount - 1) 1 else 0
        val id = "b${branch}_s$step"
        items += item(id, effort = 10 + extra)
        dependencies +=
          dependency("d_${id}", predecessorId = previous, successorId = id)
        previous = id
      }
    }

    val first = CriticalPathEngine.analyze(items, emptyList(), dependencies)
    val second =
      CriticalPathEngine.analyze(items.reversed(), emptyList(), dependencies.reversed())

    assertTrue(first.isComplete)
    assertEquals(first.projectDurationMinutes, second.projectDurationMinutes)
    assertEquals(first.criticalPaths, second.criticalPaths)
    assertEquals(
      first.taskTimings.map { it.itemId to it.earliestStartMinute },
      second.taskTimings.map { it.itemId to it.earliestStartMinute },
    )
    // 30 for the spine plus ten 11-minute steps on the longest branch.
    assertEquals(30L + branchLength * 11L, first.projectDurationMinutes)
    assertEquals(1, first.criticalPaths.size)
    assertEquals(branchLength + 1, first.criticalPaths.single().size)

    // One unknown estimate anywhere must retract the whole claim rather than guess a zero.
    val withUnknown =
      items.map { if (it.id == "b0_s0") it.copy(effortMinutes = null) else it }
    val partial = CriticalPathEngine.analyze(withUnknown, emptyList(), dependencies)
    assertFalse(partial.isComplete)
    assertNull(partial.projectDurationMinutes)
    assertTrue(partial.criticalPaths.isEmpty())
    assertTrue(partial.incompleteItems.any { it.itemId == "b0_s0" })
    // Every task is still listed, so the screen can say which one is missing.
    assertEquals(withUnknown.size, partial.taskTimings.size)
  }

  private fun item(
    id: String,
    effort: Int?,
    boardId: String = "board",
    milestone: Boolean = false,
  ) =
    PlanItem(
      id = id,
      boardId = boardId,
      title = id,
      rank = 1,
      effortMinutes = effort,
      isMilestone = milestone,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(
    id: String,
    itemId: String,
    startMinute: Long,
    endMinute: Long,
  ) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = startMinute * 60_000L,
      endAt = endMinute * 60_000L,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun dependency(
    id: String,
    predecessorId: String,
    successorId: String,
    type: String = PlanDependencyType.FINISH_TO_START,
    lag: Int = 0,
    boardId: String = "board",
  ) =
    PlanDependency(
      id = id,
      boardId = boardId,
      predecessorId = predecessorId,
      successorId = successorId,
      type = type,
      lagMinutes = lag,
      createdAt = 1,
      updatedAt = 1,
    )
}
