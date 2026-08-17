package com.example.contract

import kotlinx.serialization.Serializable

/**
 * The wire form of `AutoPlanResult` plus the post-commit health assessment
 * (docs/saas/04-planner-api-contract.md §PlanningResult).
 *
 * The result is a proposal, never a decision: the API writes nothing, and the caller applies
 * through the journal with compare-and-set undo.
 */
@Serializable
data class PlanningResult(
  val v: Int = PlannerApi.VERSION,
  val proposals: List<PlanProposalWire>,
  val unplaced: List<UnplacedTaskWire>,
  val explanation: String,
  val health: PlanHealthWire,
) {
  init {
    PlannerApi.checkVersion(v)
  }
}

/**
 * One block the planner *would* create, with the sentence that justifies the slot. The
 * contract pins whole-minute bounds and a positive span — a wire that violates either is
 * corrupt, not a plan.
 */
@Serializable
data class PlanProposalWire(
  val itemId: String,
  val startAt: Long,
  val endAt: Long,
  val reason: String,
) {
  init {
    require(endAt > startAt) { "A proposal must end after it starts" }
    require(startAt % 60_000L == 0L) { "Proposals start on a whole minute only" }
    require(endAt % 60_000L == 0L) { "Proposals end on a whole minute only" }
  }
}

/** A task the planner refused, named and explained — never silently dropped. */
@Serializable
data class UnplacedTaskWire(
  val itemId: String,
  val reason: String,
)

/** The wire form of `ScheduleAnalysis.findConflicts` (docs 04 §PlanConflict). */
@Serializable
data class PlanConflictWire(
  val first: ExternalEventWire,
  val second: ExternalEventWire,
  val overlapMs: Long,
)

/** The wire form of [BriefingEvent], incl. the all-day flag that is never inferred. */
@Serializable
data class ExternalEventWire(
  val id: String,
  val title: String,
  val startTime: Long,
  val endTime: Long,
  val source: String,
  val description: String? = null,
  val isDeadline: Boolean = false,
  val isUrgent: Boolean = false,
  val isAllDay: Boolean = false,
  val location: String? = null,
  val kanbanStatus: String = "To Do",
  val kanbanBoard: String = "Default",
  val userEdited: Boolean = false,
)

/**
 * The wire form of `PlanHealthResult` / `MultiSchedulePlanHealth.evaluate`. A total that
 * cannot be complete must say so: unknown effort is reported by [missingEstimateCount] and
 * [unscheduledDemandMinutes], never summed as zero.
 */
@Serializable
data class PlanHealthWire(
  val assessment: String,
  val workingMinutes: Int,
  val capacityAfterCommitmentsMinutes: Int,
  val overloadMinutes: Int,
  val missingEstimateCount: Int,
  val unscheduledDemandMinutes: Int,
  val demands: List<PlanTaskDemandWire> = emptyList(),
  val risks: List<PlanRiskWire> = emptyList(),
  val repairs: List<PlanRepairCandidateWire> = emptyList(),
  val warnings: List<String> = emptyList(),
  val explanation: String = "",
)

@Serializable
data class PlanTaskDemandWire(
  val itemId: String,
  val remainingEffortMinutes: Int? = null,
  val scheduledMinutes: Int,
  val unscheduledMinutes: Int? = null,
)

/** [PlanRiskKind] by name plus the item it concerns, when it concerns one. */
@Serializable
data class PlanRiskWire(
  val kind: String,
  val itemId: String? = null,
  val explanation: String,
)

/** What would have to move, and why — the seed of the repair path, never a decision. */
@Serializable
data class PlanRepairCandidateWire(
  val action: String,
  val explanation: String,
  val itemId: String? = null,
)