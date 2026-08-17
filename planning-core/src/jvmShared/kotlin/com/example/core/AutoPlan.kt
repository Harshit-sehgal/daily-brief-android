package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority

/** One proposed block, with the sentence that justifies it. */
data class PlanProposal(
  val itemId: String,
  val startAt: Long,
  val endAt: Long,
  val reason: String,
)

/** A task the planner would not place, and why. Never a silent omission. */
data class UnplacedTask(val itemId: String, val reason: String)

data class AutoPlanResult(
  val proposals: List<PlanProposal>,
  val unplaced: List<UnplacedTask>,
  val explanation: String,
) {
  val isEmpty: Boolean
    get() = proposals.isEmpty()
}

/**
 * How a task's due date limits the planner. Defaults to [HARD]: the engine only proposes work
 * that ends before `dueAt`, and names whatever does not fit — "know what you can commit to
 * before you commit to it". [SOFT] preserves the old behaviour of planning past a deadline, but
 * never claims the work is "ahead of its due date" when it is not.
 */
enum class DeadlinePolicy {
  SOFT,
  HARD,
}

/**
 * A proposal, never a decision.
 *
 * Auto-planning is the feature most able to destroy trust, because it moves a person's week while
 * they are not looking. So this engine is pure and writes nothing: it returns blocks it *would*
 * create, each with the reason it chose that slot, and the caller shows them before anything is
 * committed. Everything it cannot justify it refuses and names — a task with no stated effort is
 * reported as unplaceable rather than given an invented duration, and a dependency type it does not
 * yet schedule around is said out loud instead of ignored.
 *
 * It never touches existing blocks, fixed commitments, locked work, or time that has already passed.
 */
object AutoPlan {
  fun propose(
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    fixedCommitments: List<WorkingInterval>,
    dependencies: List<PlanDependency>,
    schedule: WorkingCalendarSpec,
    rangeStartMs: Long,
    rangeEndMs: Long,
    nowMs: Long,
    /**
     * Task IDs to attempt in this exact order. Empty means the planner's own order: soonest due,
     * then most urgent, then the plan's order. A scenario passes its ordering here rather than
     * editing the tasks to fake one.
     */
    preferredOrder: List<String> = emptyList(),
    deadlinePolicy: DeadlinePolicy = DeadlinePolicy.HARD,
  ): AutoPlanResult {
    if (rangeEndMs <= rangeStartMs) {
      return AutoPlanResult(emptyList(), emptyList(), "That range ends before it starts.")
    }
    val active = items.filter { it.archivedAt == null && !it.isMilestone }
    val byId = active.associateBy(PlanItem::id)
    val scheduledMinutes =
      blocks.groupBy(PlanBlock::planItemId).mapValues { (_, list) ->
        list.sumOf { ((it.endAt - it.startAt) / 60_000L).toInt() }
      }

    val unplaced = mutableListOf<UnplacedTask>()
    val candidates =
      active
        .filter { it.progress < 100 }
        .filter { item ->
          when {
            item.locked -> {
              unplaced += UnplacedTask(item.id, "Locked, so the planner left it alone.")
              false
            }
            item.effortMinutes == null -> {
              unplaced +=
                UnplacedTask(item.id, "No effort stated, and a guessed duration is not a plan.")
              false
            }
            (scheduledMinutes[item.id] ?: 0) >= item.effortMinutes -> false
            else -> true
          }
        }
        // Deterministic order: a caller's explicit order first, then what is due soonest, then
        // what is most urgent, then the plan's own order.
        .sortedWith(
          compareBy<PlanItem> { item ->
            preferredOrder.indexOf(item.id).takeIf { it >= 0 } ?: Int.MAX_VALUE
          }
            .thenBy { it.dueAt ?: Long.MAX_VALUE }
            .thenBy { priorityOrder(it.priority) }
            .thenBy { it.rank }
            .thenBy { it.id }
        )

    // Only finish-to-start is scheduled around for now; anything else is disclosed, not ignored.
    val predecessors = mutableMapOf<String, MutableList<String>>()
    dependencies.forEach { dependency ->
      if (dependency.predecessorId !in byId || dependency.successorId !in byId) return@forEach
      if (dependency.type == PlanDependencyType.FINISH_TO_START) {
        predecessors.getOrPut(dependency.successorId) { mutableListOf() } += dependency.predecessorId
      } else {
        unplaced +=
          UnplacedTask(
            dependency.successorId,
            "Depends on ${byId[dependency.predecessorId]?.title ?: "another task"} by a " +
              "${dependency.type} link, which this planner does not schedule around yet.",
          )
      }
    }
    val skipped = unplaced.map(UnplacedTask::itemId).toSet()

    // Time already spoken for: existing blocks and fixed commitments plus their buffer. The
    // buffer lives in WorkingCalendar.freeIntervals, the same single code path PlanHealth uses.
    val taken =
      blocks.map { WorkingInterval(it.startAt, it.endAt) }.toMutableList()
    // Blocks must start on a whole minute, and "now" almost never is. Rounding up rather than
    // down also keeps a proposal from starting a few seconds in the past.
    val earliest = ceilToMinute(maxOf(rangeStartMs, nowMs))
    val free =
      WorkingCalendar.freeIntervals(schedule, rangeStartMs, rangeEndMs, fixedCommitments)
        .mapNotNull { interval ->
          val start = maxOf(interval.startAt, earliest)
          if (start >= interval.endAt) null else WorkingInterval(start, interval.endAt)
        }
        .toMutableList()

    val proposals = mutableListOf<PlanProposal>()
    val finishByItem = mutableMapOf<String, Long>()
    blocks.forEach { block ->
      finishByItem[block.planItemId] =
        maxOf(finishByItem[block.planItemId] ?: Long.MIN_VALUE, block.endAt)
    }

    candidates.filterNot { it.id in skipped }.forEach { item ->
      var remaining = requireNotNull(item.effortMinutes) - (scheduledMinutes[item.id] ?: 0)
      val notBefore =
        listOfNotNull(
            earliest,
            item.startConstraint?.let { maxOf(it, earliest) },
            predecessors[item.id].orEmpty().mapNotNull { finishByItem[it] }.maxOrNull(),
          )
          .max()
      val notAfter =
        if (deadlinePolicy == DeadlinePolicy.HARD) item.dueAt else null
      val blockedByName =
        predecessors[item.id].orEmpty().firstOrNull { finishByItem.containsKey(it) }?.let {
          byId[it]?.title
        }
      var placedAny = false

      while (remaining >= schedule.minimumChunkMinutes) {
        val slot =
          nextSlot(free, taken, notBefore, schedule.minimumChunkMinutes, notAfter)
            ?: break
        val available = ((slot.endAt - slot.startAt) / 60_000L).toInt()
        val minutes = minOf(remaining, schedule.maximumChunkMinutes, available)
        if (minutes < schedule.minimumChunkMinutes) break
        val end = slot.startAt + minutes * 60_000L
        proposals +=
          PlanProposal(
            itemId = item.id,
            startAt = slot.startAt,
            endAt = end,
            reason =
              buildString {
                append("First free ")
                append(minutes)
                append("-minute slot in your working time")
                if (blockedByName != null) {
                  append(" after ")
                  append(blockedByName)
                  append(" finishes")
                }
                if (item.dueAt != null && end <= item.dueAt) {
                  append(", ahead of its due date")
                } else if (notAfter != null) {
                  append(", up to its due date")
                }
              },
          )
        taken += WorkingInterval(slot.startAt, end)
        finishByItem[item.id] = maxOf(finishByItem[item.id] ?: Long.MIN_VALUE, end)
        remaining -= minutes
        placedAny = true
      }

      if (!placedAny) {
        unplaced +=
          UnplacedTask(
            item.id,
            if (notAfter != null && earliest >= notAfter) {
              "Its due date has already passed, so nothing can be planned for it."
            } else {
              "No working slot of at least ${schedule.minimumChunkMinutes} minutes is free in this range."
            },
          )
      } else if (remaining >= schedule.minimumChunkMinutes) {
        unplaced +=
          UnplacedTask(
            item.id,
            if (notAfter != null) {
              "$remaining minutes of it remain, past its due date."
            } else {
              "$remaining minutes of it still have nowhere to go in this range."
            },
          )
      }
    }

    val explanation =
      when {
        proposals.isEmpty() && unplaced.isEmpty() -> "Everything with stated effort is already scheduled."
        proposals.isEmpty() -> "Nothing could be placed in this range without breaking a rule."
        else ->
          "${proposals.size} block${if (proposals.size == 1) "" else "s"} proposed for " +
            "${proposals.map(PlanProposal::itemId).distinct().size} task" +
            "${if (proposals.map(PlanProposal::itemId).distinct().size == 1) "" else "s"}, " +
            "inside working time, around fixed commitments and existing blocks. Nothing is saved " +
            "until you apply it."
      }
    return AutoPlanResult(proposals, unplaced.distinctBy { it.itemId to it.reason }, explanation)
  }

  /** The first free stretch at or after [notBefore] that is long enough to be worth booking. */
  private fun nextSlot(
    free: List<WorkingInterval>,
    taken: List<WorkingInterval>,
    notBefore: Long,
    minimumMinutes: Int,
    notAfter: Long?,
  ): WorkingInterval? {
    val minimumMs = minimumMinutes * 60_000L
    free
      .sortedBy(WorkingInterval::startAt)
      .forEach { interval ->
        var cursor = maxOf(interval.startAt, notBefore)
        // A deadline truncates the search window; it never hands out a slot past the due date.
        val windowEnd = if (notAfter != null) minOf(interval.endAt, notAfter) else interval.endAt
        if (windowEnd <= cursor) return@forEach
        val conflicts =
          taken.filter { it.endAt > cursor && it.startAt < windowEnd }.sortedBy(WorkingInterval::startAt)
        conflicts.forEach { conflict ->
          if (conflict.startAt - cursor >= minimumMs) {
            return WorkingInterval(cursor, conflict.startAt)
          }
          cursor = maxOf(cursor, conflict.endAt)
        }
        if (windowEnd - cursor >= minimumMs) return WorkingInterval(cursor, windowEnd)
      }
    return null
  }

  private fun ceilToMinute(value: Long): Long {
    val remainder = Math.floorMod(value, 60_000L)
    return if (remainder == 0L) value else value + (60_000L - remainder)
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
