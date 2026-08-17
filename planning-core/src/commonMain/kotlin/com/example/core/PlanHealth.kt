package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority

enum class PlanHealthAssessment {
  ON_TRACK,
  AT_RISK,
  OVERCOMMITTED,
  INCOMPLETE_DATA,
}

enum class PlanRiskKind {
  OVERDUE,
  DEADLINE_CAPACITY,
  DEPENDENCY,
}

data class PlanTaskDemand(
  val item: PlanItem,
  val remainingEffortMinutes: Int?,
  val scheduledMinutes: Int,
  val unscheduledMinutes: Int?,
)

data class PlanRisk(
  val kind: PlanRiskKind,
  val itemId: String?,
  val explanation: String,
)

data class PlanRepairCandidate(
  val action: String,
  val explanation: String,
  val itemId: String? = null,
)

data class PlanHealthResult(
  val assessment: PlanHealthAssessment,
  val workingMinutes: Int,
  val capacityAfterCommitmentsMinutes: Int,
  val scheduledPlanMinutes: Int,
  val freeAfterPlannedMinutes: Int,
  val unscheduledDemandMinutes: Int,
  val overloadMinutes: Int,
  val missingEstimateCount: Int,
  val invalidPlanningFieldCount: Int,
  val planOutsideWorkingMinutes: Int,
  val planCommitmentOverlapMinutes: Int,
  val planPlanOverlapMinutes: Int,
  val demands: List<PlanTaskDemand>,
  val risks: List<PlanRisk>,
  val warnings: List<String>,
  val repairs: List<PlanRepairCandidate>,
  val explanation: String,
)

/**
 * Explainable, non-mutating capacity assessment for one Plan and one range.
 *
 * Calendar commitments remain fixed. Existing Plan blocks consume capacity but are never silently
 * moved. Unknown effort is reported as incomplete data instead of being treated as zero demand.
 */
object PlanHealth {
  fun evaluate(
    spec: WorkingCalendarSpec,
    rangeStart: Long,
    rangeEnd: Long,
    now: Long,
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    fixedCommitments: List<WorkingInterval>,
    dependencies: List<PlanDependency> = emptyList(),
  ): PlanHealthResult {
    require(rangeEnd > rangeStart) { "Plan Health range must end after it starts" }
    WorkingCalendar.validate(spec)
    val activeItems = items.filter { it.archivedAt == null }
    val byId = activeItems.associateBy { it.id }
    val duplicateItemCount = activeItems.size - byId.size
    val validBlocks =
      blocks.filter { block ->
        block.planItemId in byId && block.endAt > block.startAt &&
          block.endAt > rangeStart && block.startAt < rangeEnd
      }
    val clippedFixed = fixedCommitments.mapNotNull { clip(it, rangeStart, rangeEnd) }
    val working = WorkingCalendar.workingIntervals(spec, rangeStart, rangeEnd)
    val freeAfterCommitments =
      WorkingCalendar.freeIntervals(spec, rangeStart, rangeEnd, clippedFixed)
    val workingMinutes = working.sumOf(WorkingInterval::durationMinutes)
    val capacityMinutes = freeAfterCommitments.sumOf(WorkingInterval::durationMinutes)

    val scheduledIntervals =
      validBlocks.mapNotNull { block ->
        val start = maxOf(block.startAt, rangeStart)
        val end = minOf(block.endAt, rangeEnd)
        if (end > start) WorkingInterval(start, end) else null
      }
    val mergedScheduledIntervals = merge(scheduledIntervals)
    val scheduledWithinCapacity = intersectionMinutes(mergedScheduledIntervals, freeAfterCommitments)
    val freeAfterPlanned = (capacityMinutes - scheduledWithinCapacity).coerceAtLeast(0)
    val planPlanOverlap =
      (scheduledIntervals.sumOf(WorkingInterval::durationMinutes) -
          mergedScheduledIntervals.sumOf(WorkingInterval::durationMinutes))
        .coerceAtLeast(0)
    val outsideWorking =
      validBlocks.sumOf { block ->
        val clipped = clip(WorkingInterval(block.startAt, block.endAt), rangeStart, rangeEnd)
          ?: return@sumOf 0
        (clipped.durationMinutes - intersectionMinutes(listOf(clipped), working)).coerceAtLeast(0)
      }
    val commitmentOverlap =
      intersectionMinutes(mergedScheduledIntervals, merge(clippedFixed))

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
    val unscheduledDemand = demands.sumOf { it.unscheduledMinutes ?: 0 }
    val overload = (unscheduledDemand - freeAfterPlanned).coerceAtLeast(0)
    val warnings = mutableListOf<String>()
    if (missingEstimateCount > 0) {
      warnings += "$missingEstimateCount unfinished ${plural(missingEstimateCount, "task has", "tasks have")} no effort estimate"
    }
    if (invalidPlanningFieldCount > 0) {
      warnings += "$invalidPlanningFieldCount invalid or duplicate planning records make the totals incomplete"
    }
    if (outsideWorking > 0) warnings += "$outsideWorking planned minutes sit outside working time"
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
          val occupied = clippedFixed + scheduledIntervalsForOtherTasks(validBlocks, demand.item.id)
          val proposal =
            WorkingCalendar.propose(
              spec = spec,
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
          action = "Review work outside working hours",
          explanation = "Keep it intentionally or move it through a preview; fixed events stay unchanged.",
        )
    }
    if (overload > 0) {
      repairs +=
        PlanRepairCandidate(
          action = "Reduce or defer $overload minutes",
          explanation = "Unscheduled demand exceeds the remaining configured capacity in this range.",
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
        missingEstimateCount > 0 || invalidPlanningFieldCount > 0 || dependencyEvaluation.errors.isNotEmpty() ->
          PlanHealthAssessment.INCOMPLETE_DATA
        overload > 0 -> PlanHealthAssessment.OVERCOMMITTED
        risks.isNotEmpty() || outsideWorking > 0 || commitmentOverlap > 0 ->
          PlanHealthAssessment.AT_RISK
        else -> PlanHealthAssessment.ON_TRACK
      }
    val explanation =
      when (assessment) {
        PlanHealthAssessment.INCOMPLETE_DATA ->
          "Capacity is only a partial result because required planning data is missing or invalid."
        PlanHealthAssessment.OVERCOMMITTED ->
          "$unscheduledDemand unscheduled minutes compete for $freeAfterPlanned free minutes; $overload minutes do not fit."
        PlanHealthAssessment.AT_RISK ->
          "The range fits in aggregate, but ${risks.size + warnings.size} specific risks need review."
        PlanHealthAssessment.ON_TRACK ->
          "$freeAfterPlanned working minutes remain after fixed commitments and planned blocks."
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

  private fun remainingEffort(effortMinutes: Int, progress: Int): Int {
    require(effortMinutes > 0) { "Task effort must be positive" }
    require(progress in 0..100) { "Task progress must be between 0 and 100" }
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
      .thenBy { it.rank }
      .thenBy { it.id }

  private fun intersectionMinutes(
    left: List<WorkingInterval>,
    right: List<WorkingInterval>,
  ): Int =
    left.sumOf { a -> right.sumOf { b -> overlapMinutes(a.startAt, a.endAt, b.startAt, b.endAt) } }

  private fun overlapMinutes(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Int =
    ((minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L) / MINUTE_MS)
      .coerceAtMost(Int.MAX_VALUE.toLong())
      .toInt()

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

  private fun plural(count: Int, singular: String, plural: String) = if (count == 1) singular else plural

  private const val MINUTE_MS = 60_000L
}
