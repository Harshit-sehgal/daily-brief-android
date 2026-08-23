package com.example.data.plan

import com.example.data.model.PlanMutation
import com.example.data.model.PlanMutationStatus
import com.example.data.repository.PlanMutationType

data class ChangeDigestResult(
  val sinceMs: Long?,
  val throughMs: Long,
  val changes: List<PlanMutation>,
  val createdCount: Int,
  val updatedCount: Int,
  val movedCount: Int,
  val removedCount: Int,
  val undoneCount: Int,
) {
  val totalCount: Int
    get() = changes.size

  val isEmpty: Boolean
    get() = changes.isEmpty()
}

/** Pure projection of journal entries since the person last acknowledged the digest. */
object ChangeDigest {
  private val createdTypes =
    setOf(
      PlanMutationType.ITEM_CREATE,
      PlanMutationType.BLOCK_CREATE,
      PlanMutationType.DEPENDENCY_CREATE,
      PlanMutationType.BOARD_CREATE,
      PlanMutationType.COLUMN_CREATE,
      PlanMutationType.WORK_SCHEDULE_CREATE,
    )

  private val removedTypes =
    setOf(
      PlanMutationType.ITEM_DELETE,
      PlanMutationType.BLOCK_DELETE,
      PlanMutationType.DEPENDENCY_DELETE,
      PlanMutationType.BOARD_DELETE,
      PlanMutationType.COLUMN_DELETE,
    )

  private val movedTypes =
    setOf(
      PlanMutationType.ITEM_MOVE,
      PlanMutationType.BLOCK_EDIT,
      PlanMutationType.ITEM_SCHEDULE_ASSIGN,
    )

  fun summarise(
    history: List<PlanMutation>,
    sinceMs: Long?,
    throughMs: Long,
  ): ChangeDigestResult {
    val changes =
      history
        .asSequence()
        .filter { it.createdAt <= throughMs }
        .filter { sinceMs == null || it.createdAt > sinceMs }
        .sortedWith(compareByDescending<PlanMutation> { it.createdAt }.thenByDescending { it.id })
        .toList()
    return ChangeDigestResult(
      sinceMs = sinceMs,
      throughMs = throughMs,
      changes = changes,
      createdCount = changes.count { it.mutationType in createdTypes },
      updatedCount =
        changes.count {
          it.mutationType !in createdTypes &&
            it.mutationType !in removedTypes &&
            it.mutationType !in movedTypes
        },
      movedCount = changes.count { it.mutationType in movedTypes },
      removedCount = changes.count { it.mutationType in removedTypes },
      undoneCount = changes.count { it.status == PlanMutationStatus.UNDONE },
    )
  }
}
