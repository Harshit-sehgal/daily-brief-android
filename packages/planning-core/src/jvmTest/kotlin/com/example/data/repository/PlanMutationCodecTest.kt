package com.example.data.repository

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanMutationCodecTest {
  @Test
  fun `hierarchy move round trips null destinations unicode ids and canonical targets`() {
    val state =
      PlanMoveState(
        listOf(
          PlanPlacementState("task_二", "column:doing", 2, 20),
          PlanPlacementState("task_1", null, 1, 10),
        )
      )

    val encoded = PlanMutationCodec.encode(state)
    val decoded =
      PlanMutationCodec.decode(PlanMutationType.ITEM_MOVE, encoded.targetIdsJson, encoded.stateJson)

    assertEquals(state.items.sortedBy { it.itemId }, (decoded as PlanMoveState).items)
    assertEquals("[\"task_1\", \"task_二\"]", encoded.targetIdsJson)
  }

  @Test
  fun `full task snapshot preserves arbitrary user text and reconstructs the model`() {
    val model =
      PlanItem(
        id = "task",
        boardId = "board",
        columnId = null,
        parentId = "parent",
        title = "Launch | 🚀 : now",
        notes = "line one\nline two",
        rank = 42,
        startConstraint = 100,
        dueAt = 200,
        effortMinutes = 75,
        progress = 40,
        priority = "high",
        owner = "Rina",
        schedulingMode = "auto",
        locked = true,
        completedAt = null,
        createdAt = 1,
        updatedAt = 2,
      )
    val state = model.toMutationState()
    val encoded = PlanMutationCodec.encode(state)

    val decoded =
      PlanMutationCodec.decode(PlanMutationType.ITEM_EDIT, encoded.targetIdsJson, encoded.stateJson)
        as PlanItemState

    assertEquals(state, decoded)
    assertEquals(model, decoded.toModel())
  }

  @Test
  fun `block and progress snapshots round trip`() {
    val block =
      PlanBlock(
          id = "block",
          planItemId = "task",
          startAt = 10,
          endAt = 20,
          position = 2,
          locked = true,
          linkedEventId = "device:7",
          createdAt = 1,
          updatedAt = 2,
        )
        .toMutationState()
    val progress = PlanProgressState("task", 100, completedAt = 30, updatedAt = 31)

    listOf(
        PlanMutationType.BLOCK_EDIT to block,
        PlanMutationType.ITEM_PROGRESS to progress,
      )
      .forEach { (type, state) ->
        val encoded = PlanMutationCodec.encode(state)
        assertEquals(
          state,
          PlanMutationCodec.decode(type, encoded.targetIdsJson, encoded.stateJson),
        )
      }
  }

  @Test
  fun `hierarchy edit and dependency snapshots round trip without losing identity`() {
    val first =
      PlanItem(
          id = "parent",
          boardId = "board",
          title = "Parent",
          rank = 1,
          createdAt = 1,
          updatedAt = 2,
        )
        .toMutationState()
    val second =
      PlanItem(
          id = "child",
          boardId = "board",
          parentId = "parent",
          title = "Child",
          rank = 2,
          createdAt = 1,
          updatedAt = 3,
        )
        .toMutationState()
    val group = PlanItemGroupState(listOf(first, second).sortedBy(PlanItemState::id))
    val dependency =
      PlanDependency(
          id = "edge",
          boardId = "board",
          predecessorId = "parent",
          successorId = "child",
          createdAt = 1,
          updatedAt = 2,
        )
        .toMutationState()

    listOf(
        PlanMutationType.ITEM_EDIT to group,
        PlanMutationType.DEPENDENCY_CREATE to dependency,
      )
      .forEach { (type, state) ->
        val encoded = PlanMutationCodec.encode(state)
        assertEquals(state, PlanMutationCodec.decode(type, encoded.targetIdsJson, encoded.stateJson))
      }
  }

  @Test
  fun `target mismatch malformed records unknown versions and invalid state fail closed`() {
    val encoded = PlanMutationCodec.encode(PlanProgressState("task", 50, null, 1))

    assertNull(
      PlanMutationCodec.decode(
        PlanMutationType.ITEM_PROGRESS,
        "[\"different\"]",
        encoded.stateJson,
      )
    )
    assertNull(
      PlanMutationCodec.decode(
        PlanMutationType.ITEM_PROGRESS,
        encoded.targetIdsJson,
        encoded.stateJson + " trailing",
      )
    )
    assertNull(
      PlanMutationCodec.decode(
        PlanMutationType.ITEM_PROGRESS,
        encoded.targetIdsJson,
        encoded.stateJson,
        schemaVersion = 2,
      )
    )
    assertNull(
      PlanMutationCodec.decode(
        PlanMutationType.ITEM_PROGRESS,
        encoded.targetIdsJson,
        encoded.stateJson.replace("2:50", "3:150"),
      )
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun `duplicate move targets are rejected before journaling`() {
    PlanMutationCodec.encode(
      PlanMoveState(
        listOf(
          PlanPlacementState("task", null, 1, 1),
          PlanPlacementState("task", "column", 2, 2),
        )
      )
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun `invalid progress is rejected before journaling`() {
    PlanMutationCodec.encode(PlanProgressState("task", 101, null, 1))
  }
  /**
   * A replacement records what each side of it did *not* have, so Undo can remove what the command
   * created. The marker has to survive a round trip and must never be confused with a damaged
   * record — that would turn "this block is corrupt" into "this block was deliberately gone".
   */
  @Test
  fun `a block group round trips the rows that were absent on each side`() {
    val kept = block("kept", startAt = 10, endAt = 20)
    val made = block("made", startAt = 30, endAt = 40)
    val before = PlanBlockGroupState(blocks = listOf(kept), absentIds = listOf("made"))
    val after = PlanBlockGroupState(blocks = listOf(made), absentIds = listOf("kept"))

    // Both sides name the same targets, which is what lets them be one journal entry.
    assertEquals(before.targetIds, after.targetIds)
    assertEquals(listOf("kept", "made"), before.targetIds)

    listOf(before, after).forEach { state ->
      val encoded = PlanMutationCodec.encode(state)
      assertEquals(
        state,
        PlanMutationCodec.decode(PlanMutationType.BLOCK_EDIT, encoded.targetIdsJson, encoded.stateJson),
      )
    }
  }

  @Test
  fun `a side that holds nothing is still a well formed group`() {
    val cleared = PlanBlockGroupState(blocks = emptyList(), absentIds = listOf("a", "b"))
    val encoded = PlanMutationCodec.encode(cleared)
    assertEquals(
      cleared,
      PlanMutationCodec.decode(PlanMutationType.BLOCK_EDIT, encoded.targetIdsJson, encoded.stateJson),
    )
  }

  @Test
  fun `a damaged block record is not read as a deliberate absence`() {
    val group =
      PlanBlockGroupState(
        blocks = listOf(block("a", 10, 20), block("b", 30, 40)),
        absentIds = emptyList(),
      )
    val encoded = PlanMutationCodec.encode(group)

    // Truncating a field count, mangling a length, and emptying a record all fail closed rather
    // than decoding as "that block did not exist".
    listOf(
        encoded.stateJson.replaceFirst("v1|8|", "v1|7|"),
        encoded.stateJson.replaceFirst("v1|8|", "v1|8"),
        encoded.stateJson.replaceFirst("v1|8|", ""),
      )
      .forEach { damaged ->
        assertNull(
          damaged,
          PlanMutationCodec.decode(PlanMutationType.BLOCK_EDIT, encoded.targetIdsJson, damaged),
        )
      }
  }

  @Test(expected = IllegalArgumentException::class)
  fun `a block cannot be both present and absent in one state`() {
    PlanMutationCodec.encode(
      PlanBlockGroupState(blocks = listOf(block("a", 10, 20)), absentIds = listOf("a"))
    )
  }

  private fun block(id: String, startAt: Long, endAt: Long) =
    PlanBlock(
        id = id,
        planItemId = "task",
        startAt = startAt,
        endAt = endAt,
        createdAt = 1,
        updatedAt = 2,
      )
      .toMutationState()
}
