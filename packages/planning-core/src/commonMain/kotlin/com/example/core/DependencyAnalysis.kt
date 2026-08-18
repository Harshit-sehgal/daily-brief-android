package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem

enum class DependencyConstraintStatus {
  SATISFIED,
  VIOLATED,
  UNSCHEDULED,
  INVALID,
}

data class PlanTimeSpan(val startAt: Long, val endAt: Long) {
  init {
    require(endAt >= startAt) { "A task span cannot end before it starts" }
  }
}

data class DependencyConstraintResult(
  val dependency: PlanDependency,
  val status: DependencyConstraintStatus,
  val predecessorSpan: PlanTimeSpan? = null,
  val successorSpan: PlanTimeSpan? = null,
  val requiredAt: Long? = null,
  val actualAt: Long? = null,
  /** Positive only when the successor misses the requirement. */
  val violationMinutes: Long = 0,
  val explanation: String,
)

data class DependencyEvaluation(
  val constraints: List<DependencyConstraintResult>,
  /** Dataset-level corruption such as cycles or invalid blocks. */
  val errors: List<String>,
) {
  val hasBlockingIssues: Boolean
    get() = errors.isNotEmpty() || constraints.any { it.status != DependencyConstraintStatus.SATISFIED }

  val violated: List<DependencyConstraintResult>
    get() = constraints.filter { it.status == DependencyConstraintStatus.VIOLATED }
}

/**
 * Pure dependency evaluator used by block forms, direct-manipulation previews and Plan Health.
 *
 * It never repairs corrupt data or invents dates for unscheduled work. A milestone's due date is a
 * zero-duration span; ordinary tasks derive their span from all valid split blocks.
 */
object DependencyAnalysis {
  fun evaluate(
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    dependencies: List<PlanDependency>,
    proposedSpans: Map<String, PlanTimeSpan> = emptyMap(),
  ): DependencyEvaluation {
    val activeItems = items.filter { it.archivedAt == null }
    val byId = activeItems.associateBy { it.id }
    val errors = mutableListOf<String>()
    if (byId.size != activeItems.size) errors += "Duplicate task IDs make dependency results unsafe"

    val blocksByItem = mutableMapOf<String, MutableList<PlanBlock>>()
    blocks.forEach { block ->
      when {
        block.planItemId !in byId -> errors += "Block ${block.id} refers to a missing task"
        block.endAt <= block.startAt -> errors += "Block ${block.id} has an invalid duration"
        else -> blocksByItem.getOrPut(block.planItemId, ::mutableListOf).add(block)
      }
    }
    proposedSpans.forEach { (itemId, _) ->
      if (itemId !in byId) errors += "A proposed span refers to missing task $itemId"
    }

    val spans =
      activeItems.associate { item ->
        item.id to
          (proposedSpans[item.id]
            ?: if (item.isMilestone) item.dueAt?.let { PlanTimeSpan(it, it) }
            else
              blocksByItem[item.id]
                .orEmpty()
                .takeIf(List<PlanBlock>::isNotEmpty)
                ?.let { taskBlocks ->
                  PlanTimeSpan(
                    startAt = taskBlocks.minOf(PlanBlock::startAt),
                    endAt = taskBlocks.maxOf(PlanBlock::endAt),
                  )
                })
      }

    errors += cycleErrors(dependencies, byId)
    val seenEdges = mutableSetOf<Pair<String, String>>()
    val results =
      dependencies
        .sortedWith(compareBy<PlanDependency> { it.predecessorId }.thenBy { it.successorId }.thenBy { it.id })
        .map { dependency ->
          val predecessor = byId[dependency.predecessorId]
          val successor = byId[dependency.successorId]
          val edge = dependency.predecessorId to dependency.successorId
          val invalidReason =
            when {
              !seenEdges.add(edge) -> "Duplicate dependency edge"
              predecessor == null -> "Predecessor task is missing or archived"
              successor == null -> "Successor task is missing or archived"
              predecessor.boardId != dependency.boardId || successor.boardId != dependency.boardId ->
                "Dependency tasks do not belong to its Plan"
              predecessor.id == successor.id -> "A task cannot depend on itself"
              dependency.type !in PlanDependencyType.All -> "Unknown dependency type"
              else -> null
            }
          if (invalidReason != null) {
            DependencyConstraintResult(
              dependency = dependency,
              status = DependencyConstraintStatus.INVALID,
              explanation = invalidReason,
            )
          } else {
            evaluateConstraint(
              dependency = dependency,
              predecessorSpan = spans[dependency.predecessorId],
              successorSpan = spans[dependency.successorId],
            )
          }
        }

    return DependencyEvaluation(constraints = results, errors = errors.distinct())
  }

  /** IDs downstream of [itemId], ordered breadth-first and protected against corrupt cycles. */
  fun affectedSuccessors(itemId: String, dependencies: List<PlanDependency>): List<String> {
    val outgoing = dependencies.groupBy { it.predecessorId }
    val queue = ArrayDeque<String>().apply { outgoing[itemId].orEmpty().forEach { add(it.successorId) } }
    val visited = mutableSetOf(itemId)
    val result = mutableListOf<String>()
    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      if (!visited.add(current)) continue
      result += current
      outgoing[current].orEmpty().forEach { queue.add(it.successorId) }
    }
    return result
  }

  private fun evaluateConstraint(
    dependency: PlanDependency,
    predecessorSpan: PlanTimeSpan?,
    successorSpan: PlanTimeSpan?,
  ): DependencyConstraintResult {
    if (predecessorSpan == null || successorSpan == null) {
      val missing =
        listOfNotNull(
            "predecessor".takeIf { predecessorSpan == null },
            "successor".takeIf { successorSpan == null },
          )
          .joinToString(" and ")
      return DependencyConstraintResult(
        dependency = dependency,
        status = DependencyConstraintStatus.UNSCHEDULED,
        predecessorSpan = predecessorSpan,
        successorSpan = successorSpan,
        explanation = "The $missing must be scheduled before this dependency can be checked",
      )
    }

    val (basis, actual, relationship) =
      when (dependency.type) {
        PlanDependencyType.FINISH_TO_START ->
          Triple(predecessorSpan.endAt, successorSpan.startAt, "start after predecessor finish")
        PlanDependencyType.START_TO_START ->
          Triple(predecessorSpan.startAt, successorSpan.startAt, "start after predecessor start")
        PlanDependencyType.FINISH_TO_FINISH ->
          Triple(predecessorSpan.endAt, successorSpan.endAt, "finish after predecessor finish")
        PlanDependencyType.START_TO_FINISH ->
          Triple(predecessorSpan.startAt, successorSpan.endAt, "finish after predecessor start")
        else -> error("Dependency type was validated before evaluation")
      }
    val required = safeAddMinutes(basis, dependency.lagMinutes)
      ?: return DependencyConstraintResult(
        dependency = dependency,
        status = DependencyConstraintStatus.INVALID,
        predecessorSpan = predecessorSpan,
        successorSpan = successorSpan,
        explanation = "Dependency lag overflows the supported date range",
      )
    val violationMs =
      if (actual >= required) 0L
      else
        runCatching { GuardedArithmetic.subtractExact(required, actual) }.getOrNull()
          ?: return DependencyConstraintResult(
            dependency = dependency,
            status = DependencyConstraintStatus.INVALID,
            predecessorSpan = predecessorSpan,
            successorSpan = successorSpan,
            explanation = "Dependency distance overflows the supported date range",
          )
    val violationMinutes = ceilMinutes(violationMs)
    val status =
      if (violationMs == 0L) DependencyConstraintStatus.SATISFIED
      else DependencyConstraintStatus.VIOLATED
    val lagText =
      when {
        dependency.lagMinutes > 0 -> " with ${dependency.lagMinutes} minutes of lag"
        dependency.lagMinutes < 0 -> " with ${-dependency.lagMinutes} minutes of lead"
        else -> ""
      }
    return DependencyConstraintResult(
      dependency = dependency,
      status = status,
      predecessorSpan = predecessorSpan,
      successorSpan = successorSpan,
      requiredAt = required,
      actualAt = actual,
      violationMinutes = violationMinutes,
      explanation =
        if (status == DependencyConstraintStatus.SATISFIED) {
          "Satisfied: successor may $relationship$lagText"
        } else {
          "Move the successor by at least $violationMinutes minutes to satisfy $relationship$lagText"
        },
    )
  }

  private fun cycleErrors(
    dependencies: List<PlanDependency>,
    items: Map<String, PlanItem>,
  ): List<String> {
    val outgoing =
      dependencies
        .filter { it.predecessorId in items && it.successorId in items }
        .groupBy { it.predecessorId }
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    var cycleFound = false

    fun visit(id: String) {
      if (id in visited || cycleFound) return
      if (!visiting.add(id)) {
        cycleFound = true
        return
      }
      outgoing[id].orEmpty().forEach { visit(it.successorId) }
      visiting.remove(id)
      visited.add(id)
    }
    items.keys.sorted().forEach(::visit)
    return if (cycleFound) listOf("Dependency cycle detected; scheduling must fail closed") else emptyList()
  }

  private fun safeAddMinutes(value: Long, minutes: Int): Long? =
    runCatching { GuardedArithmetic.addExact(value, GuardedArithmetic.multiplyExact(minutes.toLong(), MINUTE_MS)) }.getOrNull()

  private fun ceilMinutes(milliseconds: Long): Long =
    milliseconds / MINUTE_MS + if (milliseconds % MINUTE_MS == 0L) 0L else 1L

  private const val MINUTE_MS = 60_000L
}
