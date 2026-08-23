package com.example.data.plan

import com.example.data.model.PlanMutation
import com.example.data.model.PlanMutationStatus
import com.example.data.repository.PlanMutationTarget
import com.example.data.repository.PlanMutationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeDigestTest {
  @Test
  fun firstReviewIncludesAllJournalCategoriesAndNewestFirst() {
    val history =
      listOf(
        mutation("created", PlanMutationType.ITEM_CREATE, 10),
        mutation("moved", PlanMutationType.BLOCK_EDIT, 20),
        mutation("removed", PlanMutationType.ITEM_DELETE, 30),
        mutation("updated", PlanMutationType.ITEM_EDIT, 40),
      )

    val result = ChangeDigest.summarise(history, sinceMs = null, throughMs = 40)

    assertEquals(listOf("updated", "removed", "moved", "created"), result.changes.map { it.id })
    assertEquals(1, result.createdCount)
    assertEquals(1, result.updatedCount)
    assertEquals(1, result.movedCount)
    assertEquals(1, result.removedCount)
    assertEquals(0, result.undoneCount)
  }

  @Test
  fun acknowledgedTimestampExcludesOldEntriesButCountsUndoStatus() {
    val history =
      listOf(
        mutation("old", PlanMutationType.ITEM_CREATE, 10),
        mutation("new", PlanMutationType.ITEM_PROGRESS, 20, PlanMutationStatus.UNDONE),
      )

    val result = ChangeDigest.summarise(history, sinceMs = 10, throughMs = 20)

    assertEquals(listOf("new"), result.changes.map { it.id })
    assertEquals(1, result.updatedCount)
    assertEquals(1, result.undoneCount)
    assertTrue(result.changes.single().status == PlanMutationStatus.UNDONE)
  }

  private fun mutation(
    id: String,
    type: String,
    createdAt: Long,
    status: String = PlanMutationStatus.APPLIED,
  ) =
    PlanMutation(
      id = id,
      boardId = "board",
      mutationType = type,
      targetType = PlanMutationTarget.ITEM,
      summary = id,
      status = status,
      createdAt = createdAt,
      updatedAt = createdAt,
    )
}
