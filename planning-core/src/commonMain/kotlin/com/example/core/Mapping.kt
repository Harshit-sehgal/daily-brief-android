package com.example.core

import com.example.contract.DeadlinePolicyWire
import com.example.contract.ExternalEventWire
import com.example.contract.IntervalWire
import com.example.contract.PlanHealthAssessmentWire
import com.example.contract.PlanHealthWire
import com.example.contract.PlanProposalWire
import com.example.contract.PlanningRequest
import com.example.contract.PlanningResult
import com.example.contract.ScheduledBlockWire
import com.example.contract.TaskDependencyWire
import com.example.contract.TaskWire
import com.example.contract.UnplacedTaskWire
import com.example.contract.WorkScheduleWire
import com.example.data.model.BriefingEvent
import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem

/**
 * The contract wire ⇄ engine mapping. The contract is the frozen agreement and the engine is
 * the authority; this file is the only place the two meet, so the service never reimplements
 * a decision the engine owns.
 */
object Mapping {
  fun toEngine(request: PlanningRequest, nowMs: Long): EngineInput {
    val itemsById = request.items.associateBy { it.id }
    return EngineInput(
      items = request.items.map { wire -> wire.toEngine(nowMs) },
      blocks = request.blocks.map { it.toEngine(nowMs) },
      fixedCommitments = request.fixedCommitments.map { WorkingInterval(it.startAt, it.endAt) },
      dependencies =
        request.dependencies.map { dep ->
          val boardId = itemsById[dep.predecessorId]?.boardId ?: ""
          dep.toEngine(boardId, nowMs)
        },
      schedule = request.schedules.toEngineSpec(itemsById),
    )
  }

  fun propose(input: EngineInput, request: PlanningRequest): PlanningResult {
    val result =
      AutoPlan.propose(
        items = input.items,
        blocks = input.blocks,
        fixedCommitments = input.fixedCommitments,
        dependencies = input.dependencies,
        schedule = input.schedule,
        rangeStartMs = request.rangeStartMs,
        rangeEndMs = request.rangeEndMs,
        nowMs = request.nowMs,
        preferredOrder = request.preferredOrder,
        deadlinePolicy =
          when (request.deadlinePolicy) {
            DeadlinePolicyWire.SOFT -> DeadlinePolicy.SOFT
            else -> DeadlinePolicy.HARD
          },
      )
    val health =
      PlanHealth.evaluate(
        spec = input.schedule,
        rangeStart = request.rangeStartMs,
        rangeEnd = request.rangeEndMs,
        now = request.nowMs,
        items = input.items,
        blocks = input.blocks,
        fixedCommitments = input.fixedCommitments,
        dependencies = input.dependencies,
      )
    return PlanningResult(
      proposals = result.proposals.map { PlanProposalWire(it.itemId, it.startAt, it.endAt, it.reason) },
      unplaced = result.unplaced.map { UnplacedTaskWire(it.itemId, it.reason) },
      explanation = result.explanation,
      health = health.toWire(),
    )
  }

  fun TaskWire.toEngine(nowMs: Long): PlanItem =
    PlanItem(
      id = id,
      boardId = boardId,
      columnId = columnId,
      parentId = parentId,
      title = title,
      notes = notes,
      rank = rank,
      startConstraint = startConstraint,
      dueAt = dueAt,
      effortMinutes = effortMinutes,
      progress = progress,
      priority = priority,
      owner = owner,
      schedulingMode = schedulingMode,
      locked = locked,
      isMilestone = isMilestone,
      completedAt = completedAt,
      archivedAt = archivedAt,
      createdAt = nowMs,
      updatedAt = nowMs,
    )

  fun ScheduledBlockWire.toEngine(nowMs: Long): PlanBlock =
    PlanBlock(
      id = id,
      planItemId = planItemId,
      startAt = startAt,
      endAt = endAt,
      position = position,
      locked = locked,
      linkedEventId = linkedEventId,
      createdAt = nowMs,
      updatedAt = nowMs,
    )

  fun TaskDependencyWire.toEngine(boardId: String, nowMs: Long): PlanDependency =
    PlanDependency(
      id = id,
      boardId = boardId,
      predecessorId = predecessorId,
      successorId = successorId,
      type = type,
      lagMinutes = lagMinutes,
      createdAt = nowMs,
      updatedAt = nowMs,
    )

  fun List<WorkScheduleWire>.toEngineSpec(itemsById: Map<String, TaskWire>): WorkingCalendarSpec {
    val chosen = firstOrNull { it.archivedAt == null && it.isDefault } ?: first()
    val windows = chosen.windows.sortedBy { it.rank }
    return WorkingCalendarSpec(
      zoneId = chosen.timeZoneId,
      weeklyWindows =
        windows
          .filter { it.kind == "weekly" }
          .map { WorkingWeekWindow(it.dayOfWeek ?: 0, it.startMinute, it.endMinute) },
      overrides =
        windows
          .filter { it.kind == "date_override" }
          .map {
            WorkingDateOverride(
              localDate = requireNotNull(it.localDate),
              windows = if (it.isClosed) emptyList() else listOf(WorkingDayWindow(it.startMinute, it.endMinute)),
            )
          },
      minimumChunkMinutes = chosen.minimumChunkMinutes,
      maximumChunkMinutes = chosen.maximumChunkMinutes,
      bufferMinutes = chosen.bufferMinutes,
    )
  }

  fun PlanHealthResult.toWire(): PlanHealthWire =
    PlanHealthWire(
      assessment = assessment.toWire(),
      workingMinutes = workingMinutes,
      capacityAfterCommitmentsMinutes = capacityAfterCommitmentsMinutes,
      overloadMinutes = overloadMinutes,
      missingEstimateCount = missingEstimateCount,
      unscheduledDemandMinutes = unscheduledDemandMinutes,
      demands =
        demands.map {
          com.example.contract.PlanTaskDemandWire(
            itemId = it.item.id,
            remainingEffortMinutes = it.remainingEffortMinutes,
            scheduledMinutes = it.scheduledMinutes,
            unscheduledMinutes = it.unscheduledMinutes,
          )
        },
      risks = risks.map { com.example.contract.PlanRiskWire(it.kind.name, it.itemId, it.explanation) },
      repairs = repairs.map { com.example.contract.PlanRepairCandidateWire(it.action, it.explanation, it.itemId) },
      warnings = warnings,
      explanation = explanation,
    )

  fun PlanHealthAssessment.toWire(): String =
    when (this) {
      PlanHealthAssessment.ON_TRACK -> PlanHealthAssessmentWire.ON_TRACK
      PlanHealthAssessment.AT_RISK -> PlanHealthAssessmentWire.AT_RISK
      PlanHealthAssessment.OVERCOMMITTED -> PlanHealthAssessmentWire.OVERCOMMITTED
      PlanHealthAssessment.INCOMPLETE_DATA -> PlanHealthAssessmentWire.INCOMPLETE_DATA
    }

  fun BriefingEvent.toWire(): ExternalEventWire =
    ExternalEventWire(
      id = id,
      title = title,
      startTime = startTime,
      endTime = endTime,
      source = source,
      description = description,
      isDeadline = isDeadline,
      isUrgent = isUrgent,
      isAllDay = isAllDay,
      location = location,
      kanbanStatus = kanbanStatus,
      kanbanBoard = kanbanBoard,
      userEdited = userEdited,
    )

  fun ExternalEventWire.toEngine(): BriefingEvent =
    BriefingEvent(
      id = id,
      title = title,
      startTime = startTime,
      endTime = endTime,
      source = source,
      description = description,
      isDeadline = isDeadline,
      isUrgent = isUrgent,
      isAllDay = isAllDay,
      location = location,
      kanbanStatus = kanbanStatus,
      kanbanBoard = kanbanBoard,
      userEdited = userEdited,
    )

  data class EngineInput(
    val items: List<PlanItem>,
    val blocks: List<PlanBlock>,
    val fixedCommitments: List<WorkingInterval>,
    val dependencies: List<PlanDependency>,
    val schedule: WorkingCalendarSpec,
  )
}