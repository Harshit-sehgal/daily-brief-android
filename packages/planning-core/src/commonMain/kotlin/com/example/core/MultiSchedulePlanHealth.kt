package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority

/**
 * Explainable capacity for one person whose Plan items may use different working schedules.
 *
 * Availability is unioned on the absolute time line, then assigned to schedule-specific demand
 * with a maximum-flow calculation. This matters when two schedules overlap: the same hour is one
 * hour of human capacity, not one hour per schedule. Existing blocks occupy the shared time line
 * for every schedule and are never moved by this assessment.
 */
object MultiSchedulePlanHealth {
  fun evaluate(
    schedules: Map<String, WorkingCalendarSpec>,
    scheduleIdByItemId: Map<String, String>,
    rangeStart: Long,
    rangeEnd: Long,
    now: Long,
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    fixedCommitments: List<WorkingInterval>,
    dependencies: List<PlanDependency> = emptyList(),
  ): PlanHealthResult {
    require(rangeEnd > rangeStart) { "Plan Health range must end after it starts" }
    require(schedules.isNotEmpty()) { "At least one working schedule is required" }
    require(schedules.keys.none(String::isBlank)) { "Working-schedule IDs cannot be blank" }
    schedules.values.forEach(WorkingCalendar::validate)

    val activeItems = items.filter { it.archivedAt == null }
    val byId = activeItems.associateBy(PlanItem::id)
    val duplicateItemCount = activeItems.size - byId.size
    val missingScheduleItems =
      activeItems.filter { item ->
        val scheduleId = scheduleIdByItemId[item.id]
        scheduleId == null || scheduleId !in schedules
      }
    require(missingScheduleItems.isEmpty()) {
      "Every active task must resolve to one active working schedule"
    }

    val clippedFixed = fixedCommitments.mapNotNull { clip(it, rangeStart, rangeEnd) }
    val mergedFixed = merge(clippedFixed)
    val workingBySchedule =
      schedules.mapValues { (_, spec) -> WorkingCalendar.workingIntervals(spec, rangeStart, rangeEnd) }
    val freeBySchedule =
      schedules.mapValues { (_, spec) ->
        WorkingCalendar.freeIntervals(spec, rangeStart, rangeEnd, clippedFixed)
      }
    val workingMinutes = durationMinutes(merge(workingBySchedule.values.flatten()))
    val capacityMinutes = durationMinutes(merge(freeBySchedule.values.flatten()))

    val validBlocks =
      blocks.filter { block ->
        block.planItemId in byId && block.endAt > block.startAt &&
          block.endAt > rangeStart && block.startAt < rangeEnd
      }
    val scheduledIntervals =
      validBlocks.mapNotNull { block ->
        clip(WorkingInterval(block.startAt, block.endAt), rangeStart, rangeEnd)
      }
    val mergedScheduledIntervals = merge(scheduledIntervals)
    val scheduleValidScheduledIntervals =
      validBlocks.flatMap { block ->
        val scheduleId = requireNotNull(scheduleIdByItemId[block.planItemId])
        val clipped = clip(WorkingInterval(block.startAt, block.endAt), rangeStart, rangeEnd)
          ?: return@flatMap emptyList()
        intersections(listOf(clipped), freeBySchedule.getValue(scheduleId))
      }
    val scheduledWithinCapacity = durationMinutes(merge(scheduleValidScheduledIntervals))
    val freeAfterExistingBySchedule =
      freeBySchedule.mapValues { (_, free) -> subtractAll(free, mergedScheduledIntervals) }
    val freeAfterPlanned = durationMinutes(merge(freeAfterExistingBySchedule.values.flatten()))
    val planPlanOverlap =
      (durationMinutes(scheduledIntervals) - durationMinutes(mergedScheduledIntervals)).coerceAtLeast(0)
    val outsideWorking =
      validBlocks.sumOf { block ->
        val clipped = clip(WorkingInterval(block.startAt, block.endAt), rangeStart, rangeEnd)
          ?: return@sumOf 0
        val scheduleId = requireNotNull(scheduleIdByItemId[block.planItemId])
        (clipped.durationMinutes -
            intersectionMinutes(listOf(clipped), workingBySchedule.getValue(scheduleId)))
          .coerceAtLeast(0)
      }
    val commitmentOverlap = intersectionMinutes(mergedScheduledIntervals, mergedFixed)

    val blocksByItem = validBlocks.groupBy(PlanBlock::planItemId)
    val invalidPlanningFieldCount =
      activeItems.count { item ->
        item.progress !in 0..100 || (item.effortMinutes != null && item.effortMinutes <= 0)
      } + duplicateItemCount
    val demands =
      activeItems
        .filter { it.progress in 0..99 && !it.isMilestone }
        .sortedWith(taskOrder())
        .map { item ->
          val remaining =
            item.effortMinutes
              ?.takeIf { it > 0 && item.progress in 0..100 }
              ?.let { remainingEffort(it, item.progress) }
          val scheduled =
            blocksByItem[item.id].orEmpty().sumOf { block ->
              overlapMinutes(block.startAt, block.endAt, rangeStart, rangeEnd)
            }
          PlanTaskDemand(
            item = item,
            remainingEffortMinutes = remaining,
            scheduledMinutes = scheduled,
            unscheduledMinutes = remaining?.let { (it - scheduled).coerceAtLeast(0) },
          )
        }
    val missingEstimateCount = demands.count { it.item.effortMinutes == null }
    val demandTotal = minuteTotal(demands.mapNotNull(PlanTaskDemand::unscheduledMinutes))
    val unscheduledDemand = demandTotal.value
    val demandBySchedule =
      demands
        .filter { it.unscheduledMinutes != null }
        .groupBy { demand -> requireNotNull(scheduleIdByItemId[demand.item.id]) }
        .mapValues { (_, scheduleDemands) ->
          minuteTotal(scheduleDemands.mapNotNull(PlanTaskDemand::unscheduledMinutes)).value
        }
    val minuteFlowUpperBound =
      maximumSchedulableMinutes(demandBySchedule, freeAfterExistingBySchedule)
    val confirmedOverloadLong =
      (demandTotal.total - minuteFlowUpperBound.toLong()).coerceAtLeast(0L)
    val overload = confirmedOverloadLong.cappedMinutes()
    val chunkPlacement =
      deterministicChunkPlacement(
        demands = demands,
        schedules = schedules,
        scheduleIdByItemId = scheduleIdByItemId,
        freeBySchedule = freeAfterExistingBySchedule,
      )
    val deterministicUnplacedLong =
      (demandTotal.total - chunkPlacement.scheduledMinutes).coerceAtLeast(0L)
    val deterministicUnplaced = deterministicUnplacedLong.cappedMinutes()
    val chunkFeasibilityUncertain = deterministicUnplacedLong > confirmedOverloadLong

    val warnings = mutableListOf<String>()
    if (missingEstimateCount > 0) {
      val missingVerb = plural(missingEstimateCount, "task has", "tasks have")
      warnings += "$missingEstimateCount unfinished $missingVerb no effort estimate"
    }
    if (invalidPlanningFieldCount > 0) {
      warnings +=
        "$invalidPlanningFieldCount invalid or duplicate planning records make the totals incomplete"
    }
    if (demandTotal.exceedsSupportedRange) {
      warnings +=
        "Known task demand exceeds the supported minute range; displayed demand totals are capped"
    }
    if (chunkFeasibilityUncertain) {
      warnings +=
        if (overload > 0) {
          "Configured minimum and maximum chunks leave up to $deterministicUnplaced known minutes " +
            "unplaced in the deterministic preview; at least $overload minutes are over capacity"
        } else {
          "$deterministicUnplaced known minutes could not be placed into the configured minimum " +
            "and maximum chunks; aggregate free minutes alone do not prove they fit"
        }
    }
    if (outsideWorking > 0) warnings += "$outsideWorking planned minutes sit outside their working schedule"
    if (commitmentOverlap > 0) {
      warnings += "$commitmentOverlap planned minutes overlap fixed calendar commitments"
    }
    if (planPlanOverlap > 0) warnings += "$planPlanOverlap planned minutes overlap other Plan work"
    if (workingMinutes == 0) warnings += "This range contains no configured working time"
    val invalidBlocks = blocks.size - blocks.count { it.planItemId in byId && it.endAt > it.startAt }
    if (invalidBlocks > 0) warnings += "$invalidBlocks invalid or orphaned plan blocks were excluded"

    val dependencyEvaluation = DependencyAnalysis.evaluate(activeItems, blocks, dependencies)
    warnings += dependencyEvaluation.errors
    val risks = mutableListOf<PlanRisk>()
    demands.forEach { demand ->
      val dueAt = demand.item.dueAt ?: return@forEach
      when {
        dueAt < now ->
          risks +=
            PlanRisk(
              PlanRiskKind.OVERDUE,
              demand.item.id,
              "${demand.item.title} is unfinished past its deadline",
            )
        demand.unscheduledMinutes != null && demand.unscheduledMinutes > 0 && dueAt <= rangeEnd -> {
          val scheduleId = requireNotNull(scheduleIdByItemId[demand.item.id])
          val occupied = clippedFixed + scheduledIntervalsForOtherTasks(validBlocks, demand.item.id)
          val proposal =
            WorkingCalendar.propose(
              spec = schedules.getValue(scheduleId),
              rangeStart = rangeStart,
              rangeEnd = rangeEnd,
              effortMinutes = demand.unscheduledMinutes,
              busy = occupied,
              notBefore = demand.item.startConstraint,
              dueAt = dueAt,
            )
          if (!proposal.fits) {
            risks +=
              PlanRisk(
                PlanRiskKind.DEADLINE_CAPACITY,
                demand.item.id,
                "${demand.item.title}: ${proposal.explanation}",
              )
          }
        }
      }
    }
    dependencyEvaluation.constraints
      .filter { it.status == DependencyConstraintStatus.VIOLATED }
      .forEach { result ->
        risks +=
          PlanRisk(
            PlanRiskKind.DEPENDENCY,
            result.dependency.successorId,
            result.explanation,
          )
      }

    val repairs = mutableListOf<PlanRepairCandidate>()
    if (missingEstimateCount > 0) {
      repairs +=
        PlanRepairCandidate(
          action = "Estimate unfinished work",
          explanation = "Add effort to the missing tasks before trusting the capacity total.",
        )
    }
    if (invalidPlanningFieldCount > 0) {
      repairs +=
        PlanRepairCandidate(
          action = "Repair invalid planning data",
          explanation = "Correct invalid progress, effort, or duplicate task records before scheduling.",
        )
    }
    if (demandTotal.exceedsSupportedRange) {
      repairs +=
        PlanRepairCandidate(
          action = "Review extreme effort estimates",
          explanation =
            "Known demand is too large for an exact minute total; split or correct the estimates.",
        )
    }
    if (commitmentOverlap > 0) {
      repairs +=
        PlanRepairCandidate(
          action = "Resolve fixed-time overlaps",
          explanation = "Review the $commitmentOverlap planned minutes that collide with commitments.",
        )
    }
    if (outsideWorking > 0) {
      repairs +=
        PlanRepairCandidate(
          action = "Review work outside assigned hours",
          explanation = "Keep it intentionally or move it through a preview; fixed events stay unchanged.",
        )
    }
    if (overload > 0 && !demandTotal.exceedsSupportedRange) {
      repairs +=
        PlanRepairCandidate(
          action = "Reduce or defer $overload minutes",
          explanation =
            "Schedule-specific demand exceeds the compatible remaining capacity in this range.",
        )
    }
    if (chunkFeasibilityUncertain) {
      repairs +=
        PlanRepairCandidate(
          action = "Review task and schedule chunk sizes",
          explanation =
            "Split work or adjust the assigned schedule's minimum and maximum chunks before " +
              "trusting aggregate free time.",
        )
    }
    risks.firstOrNull { it.itemId != null }?.let { risk ->
      repairs +=
        PlanRepairCandidate(
          action = "Review the first at-risk task",
          explanation = risk.explanation,
          itemId = risk.itemId,
        )
    }

    val assessment =
      when {
        missingEstimateCount > 0 ||
          invalidPlanningFieldCount > 0 ||
          dependencyEvaluation.errors.isNotEmpty() ||
          demandTotal.exceedsSupportedRange -> PlanHealthAssessment.INCOMPLETE_DATA
        overload > 0 -> PlanHealthAssessment.OVERCOMMITTED
        chunkFeasibilityUncertain ||
          risks.isNotEmpty() ||
          outsideWorking > 0 ||
          commitmentOverlap > 0 ->
          PlanHealthAssessment.AT_RISK
        else -> PlanHealthAssessment.ON_TRACK
      }
    val explanation =
      when (assessment) {
        PlanHealthAssessment.INCOMPLETE_DATA ->
          "Capacity is only a partial result because required planning data is missing or invalid."
        PlanHealthAssessment.OVERCOMMITTED ->
          if (chunkFeasibilityUncertain) {
            "$unscheduledDemand unscheduled minutes compete for schedule-compatible time; " +
              "at least $overload minutes do not fit, and chunk limits may increase the shortfall."
          } else {
            "$unscheduledDemand unscheduled minutes compete for schedule-compatible time; " +
              "$overload minutes do not fit."
          }
        PlanHealthAssessment.AT_RISK ->
          "The schedules fit in aggregate, but ${risks.size + warnings.size} specific risks need review."
        PlanHealthAssessment.ON_TRACK ->
          "$freeAfterPlanned working minutes remain across ${schedules.size} " +
            plural(schedules.size, "schedule", "schedules") + "."
      }
    return PlanHealthResult(
      assessment = assessment,
      workingMinutes = workingMinutes,
      capacityAfterCommitmentsMinutes = capacityMinutes,
      scheduledPlanMinutes = scheduledWithinCapacity,
      freeAfterPlannedMinutes = freeAfterPlanned,
      unscheduledDemandMinutes = unscheduledDemand,
      overloadMinutes = overload,
      missingEstimateCount = missingEstimateCount,
      invalidPlanningFieldCount = invalidPlanningFieldCount,
      planOutsideWorkingMinutes = outsideWorking,
      planCommitmentOverlapMinutes = commitmentOverlap,
      planPlanOverlapMinutes = planPlanOverlap,
      demands = demands,
      risks = risks,
      warnings = warnings.distinct(),
      repairs = repairs,
      explanation = explanation,
    )
  }

  /**
   * Builds one concrete preview in stable task order using the same chunk rule as
   * [WorkingCalendar.propose]. Every placed block is removed from all schedules, so overlapping
   * calendars still share one human timeline. A complete placement proves feasibility; an
   * incomplete placement is only a lower bound and must not be described as a proven overload.
   */
  private fun deterministicChunkPlacement(
    demands: List<PlanTaskDemand>,
    schedules: Map<String, WorkingCalendarSpec>,
    scheduleIdByItemId: Map<String, String>,
    freeBySchedule: Map<String, List<WorkingInterval>>,
  ): ChunkPlacementResult {
    val remainingFree =
      freeBySchedule.mapValuesTo(linkedMapOf()) { (_, intervals) -> intervals.toList() }
    var scheduledMinutes = 0L
    demands.forEach { demand ->
      val requested = demand.unscheduledMinutes?.takeIf { it > 0 } ?: return@forEach
      val scheduleId = requireNotNull(scheduleIdByItemId[demand.item.id])
      val spec = schedules.getValue(scheduleId)
      val blocks = placeTaskChunks(spec, requested, remainingFree.getValue(scheduleId))
      scheduledMinutes += blocks.sumOf { it.durationMinutes.toLong() }
      if (blocks.isNotEmpty()) {
        val occupied = merge(blocks)
        remainingFree.keys.toList().forEach { id ->
          remainingFree[id] = subtractAll(remainingFree.getValue(id), occupied)
        }
      }
    }
    return ChunkPlacementResult(scheduledMinutes)
  }

  /** Mirrors WorkingCalendar.propose: one block per free interval, including its final remainder. */
  private fun placeTaskChunks(
    spec: WorkingCalendarSpec,
    requestedMinutes: Int,
    free: List<WorkingInterval>,
  ): List<WorkingInterval> {
    var remaining = requestedMinutes
    val blocks = mutableListOf<WorkingInterval>()
    free.sortedBy(WorkingInterval::startAt).forEach { interval ->
      if (remaining == 0) return@forEach
      val available = interval.durationMinutes
      val wanted = minOf(remaining, spec.maximumChunkMinutes, available)
      val finalRemainder = remaining <= spec.minimumChunkMinutes
      if (wanted < spec.minimumChunkMinutes && !finalRemainder) return@forEach
      val minutes = minOf(wanted, remaining)
      if (minutes <= 0) return@forEach
      blocks += WorkingInterval(interval.startAt, interval.startAt + minutes * MINUTE_MS)
      remaining -= minutes
    }
    return blocks
  }

  /**
   * Maximum flow from schedule-specific demand into shared absolute-time segments. Each segment has
   * one capacity edge, so overlapping calendars can never count the same human minute twice.
   */
  private fun maximumSchedulableMinutes(
    demandBySchedule: Map<String, Int>,
    freeBySchedule: Map<String, List<WorkingInterval>>,
  ): Int {
    val positiveDemand = demandBySchedule.filterValues { it > 0 }.toList().sortedBy { it.first }.toMap()
    if (positiveDemand.isEmpty()) return 0
    val boundaries =
      freeBySchedule.values
        .flatten()
        .flatMap { listOf(it.startAt, it.endAt) }
        .distinct()
        .sorted()
    val segments =
      boundaries.zipWithNext().mapNotNull { (start, end) ->
        val minutes = ((end - start) / MINUTE_MS).toInt()
        if (minutes <= 0) return@mapNotNull null
        val allowed =
          freeBySchedule
            .filterValues { intervals ->
              intervals.any { interval -> interval.startAt <= start && interval.endAt >= end }
            }
            .keys
            .intersect(positiveDemand.keys)
        if (allowed.isEmpty()) null else CapacitySegment(minutes, allowed)
      }
    if (segments.isEmpty()) return 0

    val scheduleIds = positiveDemand.keys.toList()
    val source = 0
    val scheduleOffset = 1
    val segmentOffset = scheduleOffset + scheduleIds.size
    val sink = segmentOffset + segments.size
    val flow = IntMaxFlow(sink + 1)
    scheduleIds.forEachIndexed { index, scheduleId ->
      flow.addEdge(source, scheduleOffset + index, positiveDemand.getValue(scheduleId))
    }
    segments.forEachIndexed { segmentIndex, segment ->
      val segmentNode = segmentOffset + segmentIndex
      flow.addEdge(segmentNode, sink, segment.minutes)
      scheduleIds.forEachIndexed { scheduleIndex, scheduleId ->
        if (scheduleId in segment.allowedScheduleIds) {
          flow.addEdge(scheduleOffset + scheduleIndex, segmentNode, segment.minutes)
        }
      }
    }
    return flow.maxFlow(source, sink)
  }

  private fun remainingEffort(effortMinutes: Int, progress: Int): Int {
    val numerator = effortMinutes.toLong() * (100 - progress)
    return ((numerator + 99) / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  }

  private fun scheduledIntervalsForOtherTasks(
    blocks: List<PlanBlock>,
    itemId: String,
  ): List<WorkingInterval> =
    blocks.filter { it.planItemId != itemId }.map { WorkingInterval(it.startAt, it.endAt) }

  private fun taskOrder() =
    compareBy<PlanItem> { item ->
        when (item.priority) {
          PlanPriority.URGENT -> 0
          PlanPriority.HIGH -> 1
          PlanPriority.NORMAL -> 2
          else -> 3
        }
      }
      .thenBy { it.dueAt ?: Long.MAX_VALUE }
      .thenBy(PlanItem::rank)
      .thenBy(PlanItem::id)

  private fun intersections(
    left: List<WorkingInterval>,
    right: List<WorkingInterval>,
  ): List<WorkingInterval> =
    left.flatMap { a ->
      right.mapNotNull { b ->
        val start = maxOf(a.startAt, b.startAt)
        val end = minOf(a.endAt, b.endAt)
        if (end > start) WorkingInterval(start, end) else null
      }
    }

  private fun intersectionMinutes(
    left: List<WorkingInterval>,
    right: List<WorkingInterval>,
  ): Int = durationMinutes(intersections(merge(left), merge(right)))

  private fun subtractAll(
    windows: List<WorkingInterval>,
    blocked: List<WorkingInterval>,
  ): List<WorkingInterval> =
    windows.flatMap { window ->
      var cursor = window.startAt
      val result = mutableListOf<WorkingInterval>()
      blocked.forEach { busy ->
        if (busy.endAt <= cursor || busy.startAt >= window.endAt) return@forEach
        if (busy.startAt > cursor) result += WorkingInterval(cursor, minOf(busy.startAt, window.endAt))
        cursor = maxOf(cursor, busy.endAt)
      }
      if (cursor < window.endAt) result += WorkingInterval(cursor, window.endAt)
      result
    }

  private fun clip(interval: WorkingInterval, start: Long, end: Long): WorkingInterval? {
    val clippedStart = maxOf(interval.startAt, start)
    val clippedEnd = minOf(interval.endAt, end)
    return if (clippedEnd > clippedStart) WorkingInterval(clippedStart, clippedEnd) else null
  }

  private fun merge(intervals: List<WorkingInterval>): List<WorkingInterval> {
    val result = mutableListOf<WorkingInterval>()
    intervals.sortedBy(WorkingInterval::startAt).forEach { interval ->
      val previous = result.lastOrNull()
      if (previous == null || interval.startAt > previous.endAt) result += interval
      else result[result.lastIndex] = WorkingInterval(previous.startAt, maxOf(previous.endAt, interval.endAt))
    }
    return result
  }

  private fun durationMinutes(intervals: List<WorkingInterval>): Int =
    intervals.sumOf(WorkingInterval::durationMinutes)

  private fun overlapMinutes(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Int =
    ((minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L) / MINUTE_MS)
      .coerceAtMost(Int.MAX_VALUE.toLong())
      .toInt()

  private fun plural(count: Int, singular: String, plural: String) = if (count == 1) singular else plural

  private fun minuteTotal(minutes: Iterable<Int>): MinuteTotal {
    var total = 0L
    minutes.forEach { value ->
      require(value >= 0) { "Minute totals cannot contain negative demand" }
      total += value.toLong()
    }
    return MinuteTotal(
      total = total,
      value = total.cappedMinutes(),
      exceedsSupportedRange = total > Int.MAX_VALUE,
    )
  }

  private fun Long.cappedMinutes(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

  private data class MinuteTotal(
    val total: Long,
    val value: Int,
    val exceedsSupportedRange: Boolean,
  )

  private data class ChunkPlacementResult(val scheduledMinutes: Long)

  private data class CapacitySegment(
    val minutes: Int,
    val allowedScheduleIds: Set<String>,
  )

  private class IntMaxFlow(nodeCount: Int) {
    private data class Edge(val to: Int, val reverse: Int, var capacity: Int)

    private val graph = List(nodeCount) { mutableListOf<Edge>() }

    fun addEdge(from: Int, to: Int, capacity: Int) {
      require(capacity >= 0) { "Flow capacity cannot be negative" }
      val forward = Edge(to, graph[to].size, capacity)
      val reverse = Edge(from, graph[from].size, 0)
      graph[from] += forward
      graph[to] += reverse
    }

    fun maxFlow(source: Int, sink: Int): Int {
      var total = 0
      while (true) {
        val parentNode = IntArray(graph.size) { -1 }
        val parentEdge = IntArray(graph.size) { -1 }
        val queue = ArrayDeque<Int>()
        parentNode[source] = source
        queue.add(source)
        while (queue.isNotEmpty() && parentNode[sink] == -1) {
          val node = queue.removeFirst()
          graph[node].forEachIndexed { edgeIndex, edge ->
            if (edge.capacity > 0 && parentNode[edge.to] == -1) {
              parentNode[edge.to] = node
              parentEdge[edge.to] = edgeIndex
              queue.add(edge.to)
            }
          }
        }
        if (parentNode[sink] == -1) return total
        var amount = Int.MAX_VALUE
        var node = sink
        while (node != source) {
          val parent = parentNode[node]
          amount = minOf(amount, graph[parent][parentEdge[node]].capacity)
          node = parent
        }
        node = sink
        while (node != source) {
          val parent = parentNode[node]
          val edgeIndex = parentEdge[node]
          val edge = graph[parent][edgeIndex]
          edge.capacity -= amount
          graph[node][edge.reverse].capacity += amount
          node = parent
        }
        total += amount
      }
    }
  }

  private const val MINUTE_MS = 60_000L
}
