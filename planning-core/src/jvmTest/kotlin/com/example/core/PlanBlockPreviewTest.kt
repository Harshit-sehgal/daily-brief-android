package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanBlockPreviewTest {
  private val day = at(2026, Calendar.AUGUST, 10, 0, 0)
  private val item = item("task")
  private val schedule =
    WorkingCalendarSpec(
      zoneId = "UTC",
      weeklyWindows = listOf(WorkingWeekWindow(Calendar.MONDAY, 9 * 60, 17 * 60)),
      minimumChunkMinutes = 30,
      maximumChunkMinutes = 120,
    )

  @Test
  fun `valid working-time proposal can commit`() {
    val result = preview(startHour = 9, endHour = 10)

    assertTrue(result.canCommit)
    assertTrue(result.issues.isEmpty())
  }

  @Test
  fun `fixed commitments and nonworking time block every input path`() {
    val fixed = WorkingInterval(atHour(10), atHour(11))

    val result = preview(startHour = 8, endHour = 11, fixed = listOf(fixed))

    assertFalse(result.canCommit)
    assertTrue(result.issues.any { it.kind == PlanBlockIssueKind.OUTSIDE_WORKING_TIME })
    assertTrue(result.issues.any { it.kind == PlanBlockIssueKind.FIXED_COMMITMENT })
    assertTrue(result.issues.any { it.kind == PlanBlockIssueKind.INVALID_DURATION })
  }

  @Test
  fun `plan overlap warns but does not silently forbid parallel work`() {
    val otherTask = item("other-task")
    val other = block("other", "other-task", 9, 10)

    val result =
      preview(
        startHour = 9,
        endHour = 10,
        items = listOf(item, otherTask),
        blocks = listOf(other),
      )

    assertTrue(result.canCommit)
    assertEquals(PlanBlockIssueKind.PLAN_OVERLAP, result.issues.single().kind)
    assertFalse(result.issues.single().blocking)
  }

  @Test
  fun `locked task locked block milestone and stale block fail closed`() {
    val lockedTask = preview(item = item.copy(locked = true), startHour = 9, endHour = 10)
    val movedLockedBlock =
      preview(
        blockId = "current",
        startHour = 10,
        endHour = 11,
        blocks = listOf(block("current", item.id, 9, 10).copy(locked = true)),
      )
    val unchangedLockedBlock =
      preview(
        blockId = "current",
        startHour = 9,
        endHour = 10,
        blocks = listOf(block("current", item.id, 9, 10).copy(locked = true)),
      )
    val milestone = preview(item = item.copy(isMilestone = true), startHour = 9, endHour = 10)
    val stale = preview(blockId = "missing", startHour = 9, endHour = 10)

    listOf(lockedTask, movedLockedBlock, milestone, stale).forEach { assertFalse(it.canCommit) }
    assertTrue("An exact-time preview must allow the explicit unlock save", unchangedLockedBlock.canCommit)
    assertTrue(lockedTask.issues.any { it.kind == PlanBlockIssueKind.LOCKED })
    assertTrue(movedLockedBlock.issues.any { it.kind == PlanBlockIssueKind.LOCKED })
    assertTrue(milestone.issues.any { it.kind == PlanBlockIssueKind.MILESTONE })
    assertTrue(stale.issues.any { it.kind == PlanBlockIssueKind.CORRUPT_DATA })
  }

  @Test
  fun `dependency impact and affected successors are explicit`() {
    val successor = item("successor")
    val dependency =
      PlanDependency(
        id = "edge",
        boardId = "board",
        predecessorId = item.id,
        successorId = successor.id,
        createdAt = 1,
        updatedAt = 1,
      )
    val successorBlock = block("successor-block", successor.id, 10, 11)

    val result =
      preview(
        startHour = 10,
        endHour = 12,
        items = listOf(item, successor),
        blocks = listOf(successorBlock),
        dependencies = listOf(dependency),
      )

    assertFalse(result.canCommit)
    assertEquals(listOf(successor.id), result.affectedSuccessorIds)
    assertTrue(result.issues.any { it.kind == PlanBlockIssueKind.DEPENDENCY })
  }

  @Test
  fun `commitment buffer and sub-minute geometry fail before mutation`() {
    val fixed = WorkingInterval(atHour(10), atHour(11))
    val bufferedSchedule = schedule.copy(bufferMinutes = 15)
    val buffered =
      PlanBlockPreview.evaluate(
        item = item,
        blockId = null,
        proposedStart = atHour(9),
        proposedEnd = atHour(10) - 1,
        items = listOf(item),
        blocks = emptyList(),
        fixedCommitments = listOf(fixed),
        dependencies = emptyList(),
        workSchedule = bufferedSchedule,
      )

    assertFalse(buffered.canCommit)
    assertTrue(buffered.issues.any { it.kind == PlanBlockIssueKind.INVALID_DURATION })
    assertTrue(buffered.issues.any { it.kind == PlanBlockIssueKind.FIXED_COMMITMENT })
  }

  private fun preview(
    item: PlanItem = this.item,
    blockId: String? = null,
    startHour: Int,
    endHour: Int,
    items: List<PlanItem> = listOf(item),
    blocks: List<PlanBlock> = emptyList(),
    fixed: List<WorkingInterval> = emptyList(),
    dependencies: List<PlanDependency> = emptyList(),
  ) =
    PlanBlockPreview.evaluate(
      item = item,
      blockId = blockId,
      proposedStart = atHour(startHour),
      proposedEnd = atHour(endHour),
      items = items,
      blocks = blocks,
      fixedCommitments = fixed,
      dependencies = dependencies,
      workSchedule = schedule,
    )

  private fun item(id: String) =
    PlanItem(
      id = id,
      boardId = "board",
      title = id,
      rank = 1,
      effortMinutes = 60,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(id: String, itemId: String, startHour: Int, endHour: Int) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = atHour(startHour),
      endAt = atHour(endHour),
      createdAt = 1,
      updatedAt = 1,
    )

  private fun atHour(hour: Int) = day + hour * 60 * 60 * 1000L

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
      clear()
      set(year, month, day, hour, minute, 0)
    }.timeInMillis
}
