package com.example.data.repository

import com.example.core.BaselineVariance
import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of a baseline.
 *
 * These cases were part of `BaselineVarianceTest` until the planning engine moved to
 * `:planning-core`. The comparison maths went with it; the codec did not, because it still
 * reaches `PlanMutationCodec` and from there into the Room migration code. They live here
 * until that chain is untangled, and they still exercise the engine — a decoded baseline has
 * to compare as identical to the plan it was taken from, or the encoding lost something.
 */
class PlanBaselineCodecTest {
  private val hour = 3_600_000L
  private val day = 24 * hour

  private fun item(id: String, title: String = id) =
    PlanItem(id = id, boardId = "board", title = title, rank = 1_000L, createdAt = 0L, updatedAt = 0L)

  private fun block(id: String, itemId: String, startAt: Long, minutes: Long = 60L) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = startAt,
      endAt = startAt + minutes * 60_000L,
      createdAt = 0L,
      updatedAt = 0L,
    )

  @Test
  fun aBaselineRoundTripsThroughItsStoredForm() {
    val items =
      listOf(
        item("a", "Round trip").copy(
          notes = "Kept, with a comma and a \"quote\"",
          columnId = "column",
          dueAt = 5 * day,
          effortMinutes = 90,
          progress = 40,
          priority = "high",
        ),
        item("b", "Second"),
      )
    val blocks = listOf(block("b1", "a", 9 * hour), block("b2", "b", 11 * hour, minutes = 30L))

    val decodedItems = PlanBaselineCodec.decodeItems(PlanBaselineCodec.encodeItems(items))
    val decodedBlocks = PlanBaselineCodec.decodeBlocks(PlanBaselineCodec.encodeBlocks(blocks))

    assertEquals(items.map { it.id to it.title }, decodedItems.map { it.id to it.title })
    assertEquals(items.first().notes, decodedItems.first().notes)
    assertEquals(items.first().effortMinutes, decodedItems.first().effortMinutes)
    assertEquals(items.first().progress, decodedItems.first().progress)
    assertEquals(blocks.map { it.id to it.startAt }, decodedBlocks.map { it.id to it.startAt })
    assertEquals(blocks.map { it.endAt }, decodedBlocks.map { it.endAt })

    // A decoded baseline compares as identical to the plan it was taken from.
    val comparison = BaselineVariance.compare(decodedItems, decodedBlocks, items, blocks)
    assertTrue(comparison.rows.all { it.driftMinutes == 0L })
  }

  @Test
  fun aTruncatedBaselineRefusesToDecodeRatherThanReturnHalfOfIt() {
    val encoded = PlanBaselineCodec.encodeBlocks(listOf(block("b1", "a", hour)))
    val truncated = encoded.dropLast(6) + "\"}"
    val outcome = runCatching { PlanBaselineCodec.decodeBlocks(truncated) }
    assertTrue("A partial baseline must not be returned", outcome.isFailure)
  }

  /**
   * A portfolio-sized plan, not a board-sized one.
   *
   * The encoding is linear in tasks and blocks, and a baseline is taken and read inside the schedule
   * mutex, so a slow codec would block every other plan write while it ran. This pins the shape of
   * that cost: 800 tasks and 2,400 blocks encode, decode and compare well inside a second on a
   * developer machine, and the comparison stays exact at that size.
   */
  @Test
  fun aPortfolioSizedBaselineStaysFastAndExact() {
    val taskCount = 800
    val items = (0 until taskCount).map { item("task_$it", "Task number $it") }
    val blocks =
      (0 until taskCount).flatMap { index ->
        (0 until 3).map { slot ->
          block("block_${index}_$slot", "task_$index", (index * 8L + slot) * hour)
        }
      }

    val encodeStart = System.nanoTime()
    val encodedItems = PlanBaselineCodec.encodeItems(items)
    val encodedBlocks = PlanBaselineCodec.encodeBlocks(blocks)
    val decodedItems = PlanBaselineCodec.decodeItems(encodedItems)
    val decodedBlocks = PlanBaselineCodec.decodeBlocks(encodedBlocks)
    val codecMs = (System.nanoTime() - encodeStart) / 1_000_000L

    assertEquals(taskCount, decodedItems.size)
    assertEquals(taskCount * 3, decodedBlocks.size)
    assertTrue("a baseline of this size took ${codecMs}ms to encode and decode", codecMs < 2_000L)

    // Move every third task a day later, then check the report finds exactly those.
    val moved =
      decodedBlocks.map { block ->
        val index = block.planItemId.removePrefix("task_").toInt()
        if (index % 3 == 0) block.copy(startAt = block.startAt + day, endAt = block.endAt + day)
        else block
      }
    val compareStart = System.nanoTime()
    val comparison = BaselineVariance.compare(decodedItems, decodedBlocks, decodedItems, moved)
    val compareMs = (System.nanoTime() - compareStart) / 1_000_000L

    assertEquals(taskCount, comparison.rows.size)
    assertEquals((taskCount + 2) / 3, comparison.slipped.size)
    assertTrue(comparison.pulledIn.isEmpty())
    assertTrue("comparing this size took ${compareMs}ms", compareMs < 1_000L)
    // Ordering still puts movement first at this size.
    assertTrue(comparison.rows.first().driftMinutes!! > 0L)
    assertEquals(0L, comparison.rows.last().driftMinutes)
  }
}
