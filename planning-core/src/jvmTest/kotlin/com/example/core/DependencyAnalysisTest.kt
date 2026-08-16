package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyAnalysisTest {
  @Test
  fun `all four dependency types evaluate their correct endpoints`() {
    val items = listOf(item("a"), item("b"))
    val blocks = listOf(block("a", 0, 60), block("b", 90, 150))
    val dependencies =
      listOf(
        dependency("fs", PlanDependencyType.FINISH_TO_START, lag = 30),
        dependency("ss", PlanDependencyType.START_TO_START, lag = 90),
        dependency("ff", PlanDependencyType.FINISH_TO_FINISH, lag = 90),
        dependency("sf", PlanDependencyType.START_TO_FINISH, lag = 150),
      )

    dependencies.forEach { dependency ->
      val evaluation = DependencyAnalysis.evaluate(items, blocks, listOf(dependency))
      assertTrue(evaluation.errors.isEmpty())
      assertEquals(
        "Expected ${dependency.type} to use its matching endpoints",
        DependencyConstraintStatus.SATISFIED,
        evaluation.constraints.single().status,
      )
    }
  }

  @Test
  fun `violation reports exact rounded-up minutes and a proposed span can repair it`() {
    val items = listOf(item("a"), item("b"))
    val blocks = listOf(block("a", 0, 60), block("b", 75, 135))
    val dependency = dependency("fs", PlanDependencyType.FINISH_TO_START, lag = 30)

    val before = DependencyAnalysis.evaluate(items, blocks, listOf(dependency)).constraints.single()
    val after =
      DependencyAnalysis.evaluate(
          items,
          blocks,
          listOf(dependency),
          proposedSpans = mapOf("b" to span(90, 150)),
        )
        .constraints
        .single()

    assertEquals(DependencyConstraintStatus.VIOLATED, before.status)
    assertEquals(15L, before.violationMinutes)
    assertEquals(DependencyConstraintStatus.SATISFIED, after.status)
  }

  @Test
  fun `negative lag is a lead and split blocks define the complete task span`() {
    val items = listOf(item("a"), item("b"))
    val blocks =
      listOf(
        block("a", 60, 90, id = "a-2"),
        block("a", 0, 30, id = "a-1"),
        block("b", 75, 120),
      )

    val result =
      DependencyAnalysis.evaluate(
          items,
          blocks,
          listOf(dependency("fs", PlanDependencyType.FINISH_TO_START, lag = -15)),
        )
        .constraints
        .single()

    assertEquals(PlanTimeSpan(ms(0), ms(90)), result.predecessorSpan)
    assertEquals(DependencyConstraintStatus.SATISFIED, result.status)
    assertTrue(result.explanation.contains("lead"))
  }

  @Test
  fun `milestones use their due date and unscheduled work is never assumed safe`() {
    val milestone = item("a", milestoneAt = ms(60))
    val successor = item("b")
    val result =
      DependencyAnalysis.evaluate(
          listOf(milestone, successor),
          emptyList(),
          listOf(dependency("fs", PlanDependencyType.FINISH_TO_START)),
        )
        .constraints
        .single()

    assertEquals(PlanTimeSpan(ms(60), ms(60)), result.predecessorSpan)
    assertEquals(DependencyConstraintStatus.UNSCHEDULED, result.status)
    assertTrue(result.explanation.contains("successor"))
  }

  @Test
  fun `missing endpoints duplicate edges invalid blocks and cycles fail closed`() {
    val items = listOf(item("a"), item("b"))
    val dependencies =
      listOf(
        dependency("ab", PlanDependencyType.FINISH_TO_START),
        dependency("duplicate", PlanDependencyType.START_TO_START),
        PlanDependency(
          id = "ba",
          boardId = "board",
          predecessorId = "b",
          successorId = "a",
          createdAt = 1,
          updatedAt = 1,
        ),
        PlanDependency(
          id = "missing",
          boardId = "board",
          predecessorId = "a",
          successorId = "gone",
          createdAt = 1,
          updatedAt = 1,
        ),
      )
    val invalidBlock = block("a", 10, 5)

    val evaluation = DependencyAnalysis.evaluate(items, listOf(invalidBlock), dependencies)

    assertTrue(evaluation.hasBlockingIssues)
    assertTrue(evaluation.errors.any { it.contains("cycle") })
    assertTrue(evaluation.errors.any { it.contains("invalid duration") })
    assertTrue(evaluation.constraints.any { it.status == DependencyConstraintStatus.INVALID })
  }

  @Test
  fun `affected successors are breadth first deterministic and terminate on cycles`() {
    val dependencies =
      listOf(
        edge("a", "b"),
        edge("a", "c"),
        edge("b", "d"),
        edge("c", "d"),
        edge("d", "a"),
      )

    assertEquals(listOf("b", "c", "d"), DependencyAnalysis.affectedSuccessors("a", dependencies))
    assertFalse(DependencyAnalysis.affectedSuccessors("missing", dependencies).contains("missing"))
  }

  @Test
  fun `lag overflow is invalid rather than wrapping into a satisfied result`() {
    val items = listOf(item("a"), item("b"))
    val blocks =
      listOf(
        PlanBlock("a", "a", Long.MAX_VALUE - 1, Long.MAX_VALUE, createdAt = 1, updatedAt = 1),
        PlanBlock("b", "b", 0, 1, createdAt = 1, updatedAt = 1),
      )

    val result =
      DependencyAnalysis.evaluate(
          items,
          blocks,
          listOf(dependency("overflow", PlanDependencyType.FINISH_TO_START, lag = 1)),
        )
        .constraints
        .single()

    assertEquals(DependencyConstraintStatus.INVALID, result.status)
    assertTrue(result.explanation.contains("overflows"))
  }

  private fun item(id: String, milestoneAt: Long? = null) =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      rank = 1,
      dueAt = milestoneAt,
      isMilestone = milestoneAt != null,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(itemId: String, startMinute: Int, endMinute: Int, id: String = "block-$itemId") =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = ms(startMinute),
      endAt = ms(endMinute),
      createdAt = 1,
      updatedAt = 1,
    )

  private fun dependency(id: String, type: String, lag: Int = 0) =
    PlanDependency(
      id = id,
      boardId = "board",
      predecessorId = "a",
      successorId = "b",
      type = type,
      lagMinutes = lag,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun edge(from: String, to: String) =
    PlanDependency(
      id = "$from-$to",
      boardId = "board",
      predecessorId = from,
      successorId = to,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun span(startMinute: Int, endMinute: Int) = PlanTimeSpan(ms(startMinute), ms(endMinute))

  private fun ms(minutes: Int): Long = minutes * 60_000L
}
