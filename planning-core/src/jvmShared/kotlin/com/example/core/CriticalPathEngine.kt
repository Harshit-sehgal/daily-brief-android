package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import java.util.PriorityQueue

enum class CriticalPathIssueCode {
  DUPLICATE_ITEM_ID,
  DUPLICATE_BLOCK_ID,
  ORPHAN_BLOCK,
  INVALID_BLOCK_DURATION,
  OVERLAPPING_BLOCKS,
  BLOCK_DURATION_OVERFLOW,
  MILESTONE_HAS_BLOCKS,
  UNKNOWN_DURATION,
  NON_POSITIVE_DURATION,
  DUPLICATE_DEPENDENCY_ID,
  MISSING_PREDECESSOR,
  MISSING_SUCCESSOR,
  CROSS_BOARD_DEPENDENCY,
  SELF_DEPENDENCY,
  UNKNOWN_DEPENDENCY_TYPE,
  DUPLICATE_DEPENDENCY_EDGE,
  DEPENDENCY_CYCLE,
  CONSTRAINT_OVERFLOW,
  SCHEDULE_OVERFLOW,
}

enum class IncompleteDurationReason {
  UNKNOWN,
  NON_POSITIVE,
  INVALID_BLOCKS,
  OVERFLOW,
}

data class CriticalPathIssue(
  val code: CriticalPathIssueCode,
  val explanation: String,
  val itemId: String? = null,
  val blockId: String? = null,
  val dependencyId: String? = null,
)

data class IncompleteCriticalPathItem(
  val itemId: String,
  val reason: IncompleteDurationReason,
  val explanation: String,
)

/**
 * Relative CPM values in minutes from the calculated project origin.
 *
 * Schedule fields and [isCritical] remain null when any required input is incomplete or invalid.
 * That makes it impossible for a caller to accidentally present a partial schedule as authoritative.
 */
data class CriticalPathTaskTiming(
  val itemId: String,
  val durationMinutes: Long?,
  val earliestStartMinute: Long? = null,
  val earliestFinishMinute: Long? = null,
  val latestStartMinute: Long? = null,
  val latestFinishMinute: Long? = null,
  val totalSlackMinutes: Long? = null,
  val isCritical: Boolean? = null,
)

data class CriticalPathResult(
  val taskTimings: List<CriticalPathTaskTiming>,
  val projectDurationMinutes: Long?,
  val criticalPaths: List<List<String>>,
  val errors: List<CriticalPathIssue>,
  val incompleteItems: List<IncompleteCriticalPathItem>,
) {
  val isComplete: Boolean
    get() = errors.isEmpty() && incompleteItems.isEmpty()
}

/**
 * Pure generalized-precedence scheduler and critical-path calculator.
 *
 * Blocks provide duration by summed occupied time; an ordinary item without blocks falls back to
 * its positive effort estimate. Milestones are the only valid zero-duration items. Calendar dates,
 * working-time placement and persistence deliberately stay outside this relative network analysis.
 */
object CriticalPathEngine {
  fun analyze(
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    dependencies: List<PlanDependency>,
  ): CriticalPathResult {
    val issues = mutableListOf<CriticalPathIssue>()
    val incomplete = mutableListOf<IncompleteCriticalPathItem>()
    val activeItems = items.filter { it.archivedAt == null }
    val itemGroups = activeItems.groupBy(PlanItem::id)
    itemGroups
      .filterValues { it.size > 1 }
      .keys
      .sorted()
      .forEach { itemId ->
        issues +=
          CriticalPathIssue(
            code = CriticalPathIssueCode.DUPLICATE_ITEM_ID,
            itemId = itemId,
            explanation = "Duplicate active task ID " + itemId + " makes scheduling ambiguous.",
          )
      }
    val byId =
      itemGroups
        .toSortedMap()
        .mapValues { (_, duplicates) -> duplicates.minBy { it.toString() } }

    val duplicateBlockIds =
      blocks
        .groupBy(PlanBlock::id)
        .filterValues { it.size > 1 }
        .keys
        .toSortedSet()
    duplicateBlockIds.forEach { blockId ->
      issues +=
        CriticalPathIssue(
          code = CriticalPathIssueCode.DUPLICATE_BLOCK_ID,
          blockId = blockId,
          explanation = "Duplicate block ID " + blockId + " cannot contribute duration safely.",
        )
    }

    val blockedItems = mutableSetOf<String>()
    val validBlocksByItem = mutableMapOf<String, MutableList<PlanBlock>>()
    blocks
      .sortedWith(
        compareBy<PlanBlock> { it.planItemId }
          .thenBy { it.startAt }
          .thenBy { it.endAt }
          .thenBy { it.id }
      )
      .forEach { block ->
        when {
          block.planItemId !in byId ->
            issues +=
              CriticalPathIssue(
                code = CriticalPathIssueCode.ORPHAN_BLOCK,
                blockId = block.id,
                itemId = block.planItemId,
                explanation =
                  "Block " + block.id + " refers to a missing or archived task.",
              )
          block.id in duplicateBlockIds -> blockedItems += block.planItemId
          block.endAt <= block.startAt -> {
            blockedItems += block.planItemId
            issues +=
              CriticalPathIssue(
                code = CriticalPathIssueCode.INVALID_BLOCK_DURATION,
                blockId = block.id,
                itemId = block.planItemId,
                explanation = "Block " + block.id + " must have a positive duration.",
              )
          }
          runCatching { Math.subtractExact(block.endAt, block.startAt) }.isFailure -> {
            blockedItems += block.planItemId
            issues +=
              CriticalPathIssue(
                code = CriticalPathIssueCode.BLOCK_DURATION_OVERFLOW,
                blockId = block.id,
                itemId = block.planItemId,
                explanation = "Block " + block.id + " duration exceeds the supported range.",
              )
          }
          else ->
            validBlocksByItem.getOrPut(block.planItemId, ::mutableListOf).add(block)
        }
      }

    validBlocksByItem.toSortedMap().forEach { (itemId, taskBlocks) ->
      taskBlocks
        .sortedWith(compareBy<PlanBlock> { it.startAt }.thenBy { it.endAt }.thenBy { it.id })
        .zipWithNext()
        .firstOrNull { (left, right) -> right.startAt < left.endAt }
        ?.let { (left, right) ->
          blockedItems += itemId
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.OVERLAPPING_BLOCKS,
              itemId = itemId,
              explanation =
                "Blocks " + left.id + " and " + right.id + " overlap for task " + itemId + ".",
            )
        }
    }

    val durationById = mutableMapOf<String, Long?>()
    byId.forEach { (itemId, item) ->
      val taskBlocks = validBlocksByItem[itemId].orEmpty()
      when {
        item.isMilestone && (taskBlocks.isNotEmpty() || itemId in blockedItems) -> {
          durationById[itemId] = null
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.MILESTONE_HAS_BLOCKS,
              itemId = itemId,
              explanation = "Milestone " + itemId + " cannot own duration blocks.",
            )
          incomplete +=
            IncompleteCriticalPathItem(
              itemId,
              IncompleteDurationReason.INVALID_BLOCKS,
              "Milestone duration is invalid because blocks are attached.",
            )
        }
        item.isMilestone -> durationById[itemId] = 0L
        itemId in blockedItems -> {
          durationById[itemId] = null
          incomplete +=
            IncompleteCriticalPathItem(
              itemId,
              IncompleteDurationReason.INVALID_BLOCKS,
              "Task duration cannot be derived until its invalid blocks are repaired.",
            )
        }
        taskBlocks.isNotEmpty() -> {
          val totalMillis =
            taskBlocks.fold(0L as Long?) { total, block ->
              if (total == null) null
              else {
                val blockMillis =
                  runCatching { Math.subtractExact(block.endAt, block.startAt) }.getOrNull()
                    ?: return@fold null
                runCatching { Math.addExact(total, blockMillis) }.getOrNull()
              }
            }
          if (totalMillis == null) {
            durationById[itemId] = null
            issues +=
              CriticalPathIssue(
                code = CriticalPathIssueCode.BLOCK_DURATION_OVERFLOW,
                itemId = itemId,
                explanation = "Summed block duration overflows for task " + itemId + ".",
              )
            incomplete +=
              IncompleteCriticalPathItem(
                itemId,
                IncompleteDurationReason.OVERFLOW,
                "Task block duration exceeds the supported range.",
              )
          } else {
            durationById[itemId] =
              totalMillis / MILLIS_PER_MINUTE +
                if (totalMillis % MILLIS_PER_MINUTE == 0L) 0L else 1L
          }
        }
        item.effortMinutes == null -> {
          durationById[itemId] = null
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.UNKNOWN_DURATION,
              itemId = itemId,
              explanation =
                "Task " + itemId + " needs blocks or a positive effort estimate.",
            )
          incomplete +=
            IncompleteCriticalPathItem(
              itemId,
              IncompleteDurationReason.UNKNOWN,
              "No block-derived or effort-derived duration is available.",
            )
        }
        item.effortMinutes <= 0 -> {
          durationById[itemId] = null
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.NON_POSITIVE_DURATION,
              itemId = itemId,
              explanation = "Task " + itemId + " has a nonpositive effort duration.",
            )
          incomplete +=
            IncompleteCriticalPathItem(
              itemId,
              IncompleteDurationReason.NON_POSITIVE,
              "Effort-derived duration must be positive.",
            )
        }
        else -> durationById[itemId] = item.effortMinutes.toLong()
      }
    }

    val duplicateDependencyIds =
      dependencies
        .groupBy(PlanDependency::id)
        .filterValues { it.size > 1 }
        .keys
        .toSortedSet()
    duplicateDependencyIds.forEach { dependencyId ->
      issues +=
        CriticalPathIssue(
          code = CriticalPathIssueCode.DUPLICATE_DEPENDENCY_ID,
          dependencyId = dependencyId,
          explanation =
            "Duplicate dependency ID " + dependencyId + " makes the graph ambiguous.",
        )
    }

    val rawEdges = mutableListOf<DependencyEdge>()
    val seenPairs = mutableSetOf<Pair<String, String>>()
    dependencies
      .sortedWith(dependencyComparator())
      .forEach { dependency ->
        val predecessor = byId[dependency.predecessorId]
        val successor = byId[dependency.successorId]
        val pair = dependency.predecessorId to dependency.successorId
        var valid = true
        if (dependency.id in duplicateDependencyIds) valid = false
        if (!seenPairs.add(pair)) {
          valid = false
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.DUPLICATE_DEPENDENCY_EDGE,
              dependencyId = dependency.id,
              explanation =
                "Duplicate edge " +
                  dependency.predecessorId +
                  " to " +
                  dependency.successorId +
                  " is not allowed.",
            )
        }
        if (predecessor == null) {
          valid = false
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.MISSING_PREDECESSOR,
              dependencyId = dependency.id,
              itemId = dependency.predecessorId,
              explanation =
                "Dependency " + dependency.id + " has a missing or archived predecessor.",
            )
        }
        if (successor == null) {
          valid = false
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.MISSING_SUCCESSOR,
              dependencyId = dependency.id,
              itemId = dependency.successorId,
              explanation =
                "Dependency " + dependency.id + " has a missing or archived successor.",
            )
        }
        if (
          predecessor != null &&
            successor != null &&
            (predecessor.boardId != successor.boardId ||
              dependency.boardId != predecessor.boardId ||
              dependency.boardId != successor.boardId)
        ) {
          valid = false
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.CROSS_BOARD_DEPENDENCY,
              dependencyId = dependency.id,
              explanation =
                "Dependency " + dependency.id + " does not stay within one Plan board.",
            )
        }
        if (dependency.predecessorId == dependency.successorId) {
          valid = false
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.SELF_DEPENDENCY,
              dependencyId = dependency.id,
              itemId = dependency.predecessorId,
              explanation = "A task cannot depend on itself.",
            )
        }
        if (dependency.type !in PlanDependencyType.All) {
          valid = false
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.UNKNOWN_DEPENDENCY_TYPE,
              dependencyId = dependency.id,
              explanation =
                "Dependency " + dependency.id + " has unknown type " + dependency.type + ".",
            )
        }
        if (valid) {
          rawEdges +=
            DependencyEdge(
              dependency = dependency,
              predecessorId = dependency.predecessorId,
              successorId = dependency.successorId,
            )
        }
      }

    val topology = topologicalOrder(byId.keys, rawEdges)
    if (topology.residualIds.isNotEmpty()) {
      issues +=
        CriticalPathIssue(
          code = CriticalPathIssueCode.DEPENDENCY_CYCLE,
          explanation =
            "A dependency cycle prevents ordering residual tasks: " +
              topology.residualIds.joinToString(", ") +
              ".",
        )
    }

    fun failedResult(): CriticalPathResult =
      CriticalPathResult(
        taskTimings =
          byId.keys.sorted().map { itemId ->
            CriticalPathTaskTiming(itemId = itemId, durationMinutes = durationById[itemId])
          },
        projectDurationMinutes = null,
        criticalPaths = emptyList(),
        errors = sortedIssues(issues),
        incompleteItems =
          incomplete
            .distinct()
            .sortedWith(compareBy<IncompleteCriticalPathItem> { it.itemId }.thenBy { it.reason.name }),
      )

    if (issues.isNotEmpty() || incomplete.isNotEmpty()) return failedResult()

    val weightedEdges =
      rawEdges.mapNotNull { edge ->
        val predecessorDuration = durationById.getValue(edge.predecessorId)
        val successorDuration = durationById.getValue(edge.successorId)
        val offset =
          generalizedOffset(
            edge.dependency.type,
            predecessorDuration!!,
            successorDuration!!,
            edge.dependency.lagMinutes.toLong(),
          )
        if (offset == null) {
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.CONSTRAINT_OVERFLOW,
              dependencyId = edge.dependency.id,
              explanation =
                "Dependency " + edge.dependency.id + " offset exceeds the supported range.",
            )
          null
        } else {
          WeightedDependencyEdge(edge, offset)
        }
      }
    if (issues.isNotEmpty()) return failedResult()

    val outgoing =
      weightedEdges
        .groupBy { it.edge.predecessorId }
        .mapValues { (_, edges) ->
          edges.sortedWith(
            compareBy<WeightedDependencyEdge> { it.edge.successorId }
              .thenBy { it.edge.dependency.id }
          )
        }
    val earliest = byId.keys.associateWith { 0L }.toMutableMap()
    topology.order.forEach { itemId ->
      outgoing[itemId].orEmpty().forEach { edge ->
        val candidate =
          runCatching {
              Math.addExact(earliest.getValue(itemId), edge.startOffsetMinutes)
            }
            .getOrNull()
        if (candidate == null) {
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.SCHEDULE_OVERFLOW,
              dependencyId = edge.edge.dependency.id,
              explanation =
                "Earliest-start calculation overflows at dependency " +
                  edge.edge.dependency.id +
                  ".",
            )
        } else if (candidate > earliest.getValue(edge.edge.successorId)) {
          earliest[edge.edge.successorId] = candidate
        }
      }
      if (issues.isNotEmpty()) return failedResult()
    }

    val earliestFinish = mutableMapOf<String, Long>()
    byId.keys.sorted().forEach { itemId ->
      val finish =
        runCatching {
            Math.addExact(earliest.getValue(itemId), durationById.getValue(itemId)!!)
          }
          .getOrNull()
      if (finish == null) {
        issues +=
          CriticalPathIssue(
            code = CriticalPathIssueCode.SCHEDULE_OVERFLOW,
            itemId = itemId,
            explanation = "Earliest finish exceeds the supported range for task " + itemId + ".",
          )
      } else {
        earliestFinish[itemId] = finish
      }
    }
    if (issues.isNotEmpty()) return failedResult()
    val projectDuration = earliestFinish.values.maxOrNull() ?: 0L

    val latest = mutableMapOf<String, Long>()
    byId.keys.sorted().forEach { itemId ->
      val value =
        runCatching { Math.subtractExact(projectDuration, durationById.getValue(itemId)!!) }
          .getOrNull()
      if (value == null) {
        issues +=
          CriticalPathIssue(
            code = CriticalPathIssueCode.SCHEDULE_OVERFLOW,
            itemId = itemId,
            explanation = "Latest-start initialization overflows for task " + itemId + ".",
          )
      } else {
        latest[itemId] = value
      }
    }
    if (issues.isNotEmpty()) return failedResult()

    topology.order.asReversed().forEach { itemId ->
      outgoing[itemId].orEmpty().forEach { edge ->
        val candidate =
          runCatching {
              Math.subtractExact(
                latest.getValue(edge.edge.successorId),
                edge.startOffsetMinutes,
              )
            }
            .getOrNull()
        if (candidate == null) {
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.SCHEDULE_OVERFLOW,
              dependencyId = edge.edge.dependency.id,
              explanation =
                "Latest-start calculation overflows at dependency " +
                  edge.edge.dependency.id +
                  ".",
            )
        } else if (candidate < latest.getValue(itemId)) {
          latest[itemId] = candidate
        }
      }
      if (issues.isNotEmpty()) return failedResult()
    }

    val timings =
      byId.keys.sorted().map { itemId ->
        val duration = durationById.getValue(itemId)!!
        val earlyStart = earliest.getValue(itemId)
        val lateStart = latest.getValue(itemId)
        val lateFinish = runCatching { Math.addExact(lateStart, duration) }.getOrNull()
        val slack = runCatching { Math.subtractExact(lateStart, earlyStart) }.getOrNull()
        if (lateFinish == null || slack == null || slack < 0L) {
          issues +=
            CriticalPathIssue(
              code = CriticalPathIssueCode.SCHEDULE_OVERFLOW,
              itemId = itemId,
              explanation = "Slack or latest finish is invalid for task " + itemId + ".",
            )
          CriticalPathTaskTiming(itemId, duration)
        } else {
          CriticalPathTaskTiming(
            itemId = itemId,
            durationMinutes = duration,
            earliestStartMinute = earlyStart,
            earliestFinishMinute = earliestFinish.getValue(itemId),
            latestStartMinute = lateStart,
            latestFinishMinute = lateFinish,
            totalSlackMinutes = slack,
            isCritical = slack == 0L,
          )
        }
      }
    if (issues.isNotEmpty()) return failedResult()

    val timingById = timings.associateBy(CriticalPathTaskTiming::itemId)
    val criticalIds = timings.filter { it.isCritical == true }.mapTo(mutableSetOf()) { it.itemId }
    val criticalEdges =
      weightedEdges.filter { edge ->
        edge.edge.predecessorId in criticalIds &&
          edge.edge.successorId in criticalIds &&
          runCatching {
              Math.addExact(
                timingById.getValue(edge.edge.predecessorId).earliestStartMinute!!,
                edge.startOffsetMinutes,
              )
            }
            .getOrNull() == timingById.getValue(edge.edge.successorId).earliestStartMinute
      }
    val criticalPaths = enumerateCriticalPaths(criticalIds, criticalEdges)

    return CriticalPathResult(
      taskTimings = timings,
      projectDurationMinutes = projectDuration,
      criticalPaths = criticalPaths,
      errors = emptyList(),
      incompleteItems = emptyList(),
    )
  }

  /**
   * Converts every dependency into the lower-bound constraint
   * successor-start >= predecessor-start + offset.
   */
  private fun generalizedOffset(
    type: String,
    predecessorDuration: Long,
    successorDuration: Long,
    lag: Long,
  ): Long? =
    runCatching {
        when (type) {
          PlanDependencyType.FINISH_TO_START -> Math.addExact(predecessorDuration, lag)
          PlanDependencyType.START_TO_START -> lag
          PlanDependencyType.FINISH_TO_FINISH ->
            Math.addExact(Math.subtractExact(predecessorDuration, successorDuration), lag)
          PlanDependencyType.START_TO_FINISH -> Math.subtractExact(lag, successorDuration)
          else -> error("Dependency type must be validated before offset calculation")
        }
      }
      .getOrNull()

  private fun topologicalOrder(
    itemIds: Set<String>,
    edges: List<DependencyEdge>,
  ): Topology {
    val indegree = itemIds.associateWith { 0 }.toMutableMap()
    edges.forEach { edge -> indegree[edge.successorId] = indegree.getValue(edge.successorId) + 1 }
    val outgoing =
      edges
        .groupBy(DependencyEdge::predecessorId)
        .mapValues { (_, values) ->
          values.sortedWith(compareBy<DependencyEdge> { it.successorId }.thenBy { it.dependency.id })
        }
    val ready = PriorityQueue<String>()
    indegree.filterValues { it == 0 }.keys.forEach(ready::add)
    val order = mutableListOf<String>()
    while (ready.isNotEmpty()) {
      val itemId = ready.remove()
      order += itemId
      outgoing[itemId].orEmpty().forEach { edge ->
        val remaining = indegree.getValue(edge.successorId) - 1
        indegree[edge.successorId] = remaining
        if (remaining == 0) ready.add(edge.successorId)
      }
    }
    val residual = indegree.filterValues { it > 0 }.keys.sorted()
    return Topology(order, residual)
  }

  private fun enumerateCriticalPaths(
    criticalIds: Set<String>,
    edges: List<WeightedDependencyEdge>,
  ): List<List<String>> {
    if (criticalIds.isEmpty()) return emptyList()
    val outgoing =
      edges
        .groupBy { it.edge.predecessorId }
        .mapValues { (_, values) -> values.map { it.edge.successorId }.distinct().sorted() }
    val hasIncoming = edges.mapTo(mutableSetOf()) { it.edge.successorId }
    val roots = criticalIds.filterNot(hasIncoming::contains).sorted()
    val paths = mutableListOf<List<String>>()
    roots.forEach { root ->
      val stack = ArrayDeque<List<String>>()
      stack.addLast(listOf(root))
      while (stack.isNotEmpty()) {
        val path = stack.removeLast()
        val next = outgoing[path.last()].orEmpty()
        if (next.isEmpty()) {
          paths += path
        } else {
          next.asReversed().forEach { successor -> stack.addLast(path + successor) }
        }
      }
    }
    return paths
  }

  private fun dependencyComparator(): Comparator<PlanDependency> =
    compareBy<PlanDependency> { it.predecessorId }
      .thenBy { it.successorId }
      .thenBy { it.type }
      .thenBy { it.lagMinutes }
      .thenBy { it.boardId }
      .thenBy { it.id }

  private fun sortedIssues(issues: List<CriticalPathIssue>): List<CriticalPathIssue> =
    issues
      .distinct()
      .sortedWith(
        compareBy<CriticalPathIssue> { it.code.name }
          .thenBy { it.itemId.orEmpty() }
          .thenBy { it.blockId.orEmpty() }
          .thenBy { it.dependencyId.orEmpty() }
          .thenBy { it.explanation }
      )

  private data class DependencyEdge(
    val dependency: PlanDependency,
    val predecessorId: String,
    val successorId: String,
  )

  private data class WeightedDependencyEdge(
    val edge: DependencyEdge,
    val startOffsetMinutes: Long,
  )

  private data class Topology(val order: List<String>, val residualIds: List<String>)

  private const val MILLIS_PER_MINUTE = 60_000L
}
