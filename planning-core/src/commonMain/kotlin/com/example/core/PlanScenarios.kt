package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority

/** One way of ordering the work, and what that ordering costs. */
data class PlanScenario(
  val key: String,
  val name: String,
  val rationale: String,
  val result: AutoPlanResult,
) {
  val placedTaskCount: Int
    get() = result.proposals.map(PlanProposal::itemId).distinct().size

  val unplacedTaskCount: Int
    get() = result.unplaced.map(UnplacedTask::itemId).distinct().size

  val lastEndMs: Long?
    get() = result.proposals.maxOfOrNull(PlanProposal::endAt)
}

/**
 * The same week, planned two or three defensible ways.
 *
 * A single auto-plan hides the fact that it made a choice. Ordering by due date, by priority, or by
 * getting the small things out of the way are all reasonable, and they produce different weeks —
 * showing them side by side puts the choice back where it belongs. Every scenario runs the same
 * engine with the same rules, so the comparison is about ordering and nothing else.
 */
object PlanScenarios {
  fun compare(
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    fixedCommitments: List<WorkingInterval>,
    dependencies: List<PlanDependency>,
    schedule: WorkingCalendarSpec,
    rangeStartMs: Long,
    rangeEndMs: Long,
    nowMs: Long,
  ): List<PlanScenario> =
    listOf(
      scenario(
        key = "due",
        name = "Due date first",
        rationale = "Whatever is due soonest gets the first free slot.",
        ordered = items,
        items = items,
        blocks = blocks,
        fixedCommitments = fixedCommitments,
        dependencies = dependencies,
        schedule = schedule,
        rangeStartMs = rangeStartMs,
        rangeEndMs = rangeEndMs,
        nowMs = nowMs,
      ),
      scenario(
        key = "priority",
        name = "Priority first",
        rationale = "Urgent and high-priority work is placed before anything else.",
        ordered = items.sortedWith(compareBy({ priorityOrder(it.priority) }, { it.rank }, { it.id })),
        items = items,
        blocks = blocks,
        fixedCommitments = fixedCommitments,
        dependencies = dependencies,
        schedule = schedule,
        rangeStartMs = rangeStartMs,
        rangeEndMs = rangeEndMs,
        nowMs = nowMs,
      ),
      scenario(
        key = "short",
        name = "Quick wins first",
        rationale = "The smallest pieces of stated effort go first, clearing the list faster.",
        ordered =
          items.sortedWith(compareBy({ it.effortMinutes ?: Int.MAX_VALUE }, { it.rank }, { it.id })),
        items = items,
        blocks = blocks,
        fixedCommitments = fixedCommitments,
        dependencies = dependencies,
        schedule = schedule,
        rangeStartMs = rangeStartMs,
        rangeEndMs = rangeEndMs,
        nowMs = nowMs,
      ),
    )

  /**
   * Ordering is handed to the planner rather than baked into the tasks, so every scenario is the
   * same engine under the same rules — and comparing them cannot change the plan being compared.
   */
  private fun scenario(
    key: String,
    name: String,
    rationale: String,
    ordered: List<PlanItem>,
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    fixedCommitments: List<WorkingInterval>,
    dependencies: List<PlanDependency>,
    schedule: WorkingCalendarSpec,
    rangeStartMs: Long,
    rangeEndMs: Long,
    nowMs: Long,
  ): PlanScenario {
    return PlanScenario(
      key = key,
      name = name,
      rationale = rationale,
      result =
        AutoPlan.propose(
          items = items,
          blocks = blocks,
          fixedCommitments = fixedCommitments,
          dependencies = dependencies,
          schedule = schedule,
          rangeStartMs = rangeStartMs,
          rangeEndMs = rangeEndMs,
          nowMs = nowMs,
          // Ordering is passed, never baked into the tasks: a scenario is a question, not an edit.
          preferredOrder = if (key == "due") emptyList() else ordered.map(PlanItem::id),
        ),
    )
  }

  /**
   * The sentence above the list: what, if anything, the choice is between.
   *
   * When every ordering places the same work and finishes at the same moment, saying so is the
   * honest answer. Three identical rows under "Compare approaches" imply a decision that does not
   * exist, and a person who acts on that implication has been misled by the interface rather than
   * informed by it.
   */
  fun describeSpread(scenarios: List<PlanScenario>): String =
    when {
      scenarios.isEmpty() -> "Nothing to compare yet."
      scenarios.all { it.result.proposals.isEmpty() } ->
        "None of these orderings can place anything — the work has no stated effort, or no working " +
          "time is left in range."
      scenarios.distinctBy { Triple(it.placedTaskCount, it.unplacedTaskCount, it.lastEndMs) }
        .size == 1 ->
        "All three finish at the same point and place the same work, so the ordering does not " +
          "change this week. Pick whichever you would rather do first."
      else -> "Same work, same working hours, three orderings. Pick one to review it in detail."
    }

  private fun priorityOrder(priority: String): Int =
    when (priority) {
      PlanPriority.URGENT -> 0
      PlanPriority.HIGH -> 1
      PlanPriority.NORMAL -> 2
      PlanPriority.LOW -> 3
      else -> 4
    }
}
