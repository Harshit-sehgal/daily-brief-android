package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem

enum class PlanBlockIssueKind {
  INVALID_DURATION,
  LOCKED,
  MILESTONE,
  OUTSIDE_WORKING_TIME,
  FIXED_COMMITMENT,
  PLAN_OVERLAP,
  DEPENDENCY,
  CORRUPT_DATA,
}

data class PlanBlockIssue(
  val kind: PlanBlockIssueKind,
  val blocking: Boolean,
  val explanation: String,
  val relatedId: String? = null,
)

data class PlanBlockPreviewResult(
  val proposed: WorkingInterval?,
  val issues: List<PlanBlockIssue>,
  val affectedSuccessorIds: List<String>,
  val dependencyEvaluation: DependencyEvaluation?,
) {
  val canCommit: Boolean
    get() = proposed != null && issues.none(PlanBlockIssue::blocking)
}

/** Shared validation for Gantt fields, accessibility increments, keyboard commands, and drag. */
object PlanBlockPreview {
  fun evaluate(
    item: PlanItem,
    blockId: String?,
    proposedStart: Long,
    proposedEnd: Long,
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    fixedCommitments: List<WorkingInterval>,
    dependencies: List<PlanDependency>,
    workSchedule: WorkingCalendarSpec,
  ): PlanBlockPreviewResult {
    val issues = mutableListOf<PlanBlockIssue>()
    if (proposedEnd <= proposedStart) {
      issues +=
        PlanBlockIssue(
          PlanBlockIssueKind.INVALID_DURATION,
          blocking = true,
          explanation = "The block must end after it starts.",
        )
      return PlanBlockPreviewResult(null, issues, emptyList(), null)
    }
    val proposed = WorkingInterval(proposedStart, proposedEnd)
    val durationMs = proposedEnd - proposedStart
    val existing = blockId?.let { id -> blocks.firstOrNull { it.id == id } }
    when {
      item.archivedAt != null ->
        issues += blocking(PlanBlockIssueKind.CORRUPT_DATA, "That task is no longer active.")
      item.isMilestone ->
        issues += blocking(PlanBlockIssueKind.MILESTONE, "Milestones use a date, not a duration block.")
      item.locked ->
        issues += blocking(PlanBlockIssueKind.LOCKED, "This task is locked.")
      blockId != null && existing == null ->
        issues += blocking(PlanBlockIssueKind.CORRUPT_DATA, "That plan block no longer exists.")
      existing?.planItemId != null && existing.planItemId != item.id ->
        issues += blocking(PlanBlockIssueKind.CORRUPT_DATA, "That block belongs to another task.")
      existing?.locked == true &&
        (existing.startAt != proposedStart || existing.endAt != proposedEnd) ->
        issues +=
          blocking(
            PlanBlockIssueKind.LOCKED,
            "This plan block is locked. Save it as unlocked before changing its time.",
          )
    }

    val minuteAligned =
      Math.floorMod(proposedStart, MINUTE_MS) == 0L && Math.floorMod(proposedEnd, MINUTE_MS) == 0L
    if (!minuteAligned ||
      durationMs < workSchedule.minimumChunkMinutes * MINUTE_MS ||
      durationMs > workSchedule.maximumChunkMinutes * MINUTE_MS
    ) {
      issues +=
        blocking(
          PlanBlockIssueKind.INVALID_DURATION,
          "Use ${workSchedule.minimumChunkMinutes}–${workSchedule.maximumChunkMinutes} minutes for one work block.",
        )
    }

    val workingCoverageMs =
      runCatching {
          WorkingCalendar.workingIntervals(workSchedule, proposed.startAt, proposed.endAt)
            .sumOf { overlapMillis(proposed, it) }
        }
        .getOrElse { error ->
          issues +=
            blocking(
              PlanBlockIssueKind.CORRUPT_DATA,
              error.message ?: "The work schedule is invalid.",
            )
          0
        }
    if (workingCoverageMs != durationMs) {
      issues +=
        blocking(
          PlanBlockIssueKind.OUTSIDE_WORKING_TIME,
          "${ceilMinutes(durationMs - workingCoverageMs)} minutes fall outside configured working time.",
        )
    }

    var overlapsFixedCommitment = false
    fixedCommitments.forEachIndexed { index, fixed ->
      val overlapMs = overlapMillis(proposed, fixed)
      if (overlapMs > 0) {
        overlapsFixedCommitment = true
        issues +=
          blocking(
            PlanBlockIssueKind.FIXED_COMMITMENT,
            "${ceilMinutes(overlapMs)} minutes overlap a fixed calendar commitment.",
            relatedId = "fixed-$index",
          )
      }
    }
    val freeCoverageMs =
      runCatching {
          WorkingCalendar.freeIntervals(
              workSchedule,
              proposed.startAt,
              proposed.endAt,
              fixedCommitments,
            )
            .sumOf { overlapMillis(proposed, it) }
        }
        .getOrDefault(0L)
    if (!overlapsFixedCommitment && freeCoverageMs < workingCoverageMs) {
      issues +=
        blocking(
          PlanBlockIssueKind.FIXED_COMMITMENT,
          "The configured buffer around a fixed commitment leaves too little free time.",
        )
    }
    blocks
      .asSequence()
      .filter {
        it.id != blockId && it.endAt > it.startAt &&
          it.endAt > proposed.startAt && it.startAt < proposed.endAt
      }
      .sortedWith(compareBy<PlanBlock> { it.startAt }.thenBy { it.id })
      .forEach { other ->
        val overlap = ceilMinutes(overlapMillis(proposed, WorkingInterval(other.startAt, other.endAt)))
        issues +=
          PlanBlockIssue(
            kind = PlanBlockIssueKind.PLAN_OVERLAP,
            blocking = false,
            explanation = "$overlap minutes overlap another Plan block.",
            relatedId = other.id,
          )
      }

    val replacementBlocks =
      blocks
        .filterNot { it.id == blockId }
        .plus(
          PlanBlock(
            id = blockId ?: "preview",
            planItemId = item.id,
            startAt = proposed.startAt,
            endAt = proposed.endAt,
            position = existing?.position ?: 0,
            locked = false,
            createdAt = existing?.createdAt ?: 0,
            updatedAt = existing?.updatedAt ?: 0,
          )
        )
    val itemBlocks = replacementBlocks.filter { it.planItemId == item.id }
    val proposedSpan =
      PlanTimeSpan(
        startAt = itemBlocks.minOf(PlanBlock::startAt),
        endAt = itemBlocks.maxOf(PlanBlock::endAt),
      )
    val dependencyEvaluation =
      DependencyAnalysis.evaluate(
        items = items,
        blocks = replacementBlocks,
        dependencies = dependencies,
        proposedSpans = mapOf(item.id to proposedSpan),
      )
    dependencyEvaluation.errors.forEach { error ->
      issues += blocking(PlanBlockIssueKind.CORRUPT_DATA, error)
    }
    dependencyEvaluation.constraints
      .filter { result ->
        result.status == DependencyConstraintStatus.VIOLATED &&
          (result.dependency.predecessorId == item.id || result.dependency.successorId == item.id)
      }
      .forEach { result ->
        issues +=
          blocking(
            PlanBlockIssueKind.DEPENDENCY,
            result.explanation,
            relatedId = result.dependency.id,
          )
      }
    dependencyEvaluation.constraints
      .filter { it.status == DependencyConstraintStatus.INVALID }
      .forEach { result ->
        issues +=
          blocking(
            PlanBlockIssueKind.CORRUPT_DATA,
            result.explanation,
            relatedId = result.dependency.id,
          )
      }

    return PlanBlockPreviewResult(
      proposed = proposed,
      issues = issues.distinct(),
      affectedSuccessorIds = DependencyAnalysis.affectedSuccessors(item.id, dependencies),
      dependencyEvaluation = dependencyEvaluation,
    )
  }

  private fun blocking(kind: PlanBlockIssueKind, text: String, relatedId: String? = null) =
    PlanBlockIssue(kind = kind, blocking = true, explanation = text, relatedId = relatedId)

  private fun overlapMillis(left: WorkingInterval, right: WorkingInterval): Long =
    (minOf(left.endAt, right.endAt) - maxOf(left.startAt, right.startAt)).coerceAtLeast(0L)

  private fun ceilMinutes(milliseconds: Long): Long =
    milliseconds / MINUTE_MS + if (milliseconds % MINUTE_MS == 0L) 0L else 1L

  private const val MINUTE_MS = 60_000L
}
