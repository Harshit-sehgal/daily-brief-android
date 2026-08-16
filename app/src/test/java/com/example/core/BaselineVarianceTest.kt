package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import com.example.data.repository.PlanBaselineCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A baseline is only worth keeping if it reports drift honestly, including the drift that makes the
 * plan look worse.
 */
class BaselineVarianceTest {
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
  fun aTaskThatMovedLaterReportsPositiveDrift() {
    val items = listOf(item("a", "Draft"))
    val comparison =
      BaselineVariance.compare(
        baselineItems = items,
        baselineBlocks = listOf(block("b1", "a", 10 * hour)),
        currentItems = items,
        currentBlocks = listOf(block("b1", "a", 10 * hour + day)),
      )
    val row = comparison.rows.single()
    assertEquals(24 * 60L, row.driftMinutes)
    assertEquals(listOf(row), comparison.slipped)
    assertTrue(comparison.pulledIn.isEmpty())
    assertTrue(comparison.summary, comparison.summary.startsWith("1 later"))
  }

  @Test
  fun aTaskPulledForwardReportsNegativeDriftRatherThanNone() {
    val items = listOf(item("a"))
    val comparison =
      BaselineVariance.compare(
        baselineItems = items,
        baselineBlocks = listOf(block("b1", "a", 3 * day)),
        currentItems = items,
        currentBlocks = listOf(block("b1", "a", 2 * day)),
      )
    assertEquals(-24 * 60L, comparison.rows.single().driftMinutes)
    assertEquals(1, comparison.pulledIn.size)
    assertTrue(comparison.summary, comparison.summary.contains("earlier"))
  }

  @Test
  fun workAddedAfterTheBaselineIsNamedAsNewRatherThanOnTime() {
    val comparison =
      BaselineVariance.compare(
        baselineItems = listOf(item("a")),
        baselineBlocks = listOf(block("b1", "a", hour)),
        currentItems = listOf(item("a"), item("b", "Escalation")),
        currentBlocks = listOf(block("b1", "a", hour), block("b2", "b", 5 * hour)),
      )
    val added = comparison.rows.single { it.itemId == "b" }
    assertTrue(added.addedSinceBaseline)
    assertNull("New work has no baseline to drift from", added.driftMinutes)
    // Drift-free work must not be counted as movement in either direction.
    assertTrue(comparison.slipped.isEmpty() && comparison.pulledIn.isEmpty())
    assertTrue(comparison.summary, comparison.summary.contains("1 scheduled since"))
  }

  @Test
  fun workThatLostItsScheduleIsReportedAndKeepsItsTitleFromTheBaseline() {
    val comparison =
      BaselineVariance.compare(
        baselineItems = listOf(item("a", "Dropped work")),
        baselineBlocks = listOf(block("b1", "a", hour)),
        currentItems = emptyList(),
        currentBlocks = emptyList(),
      )
    val row = comparison.rows.single()
    assertTrue(row.removedSinceBaseline)
    assertEquals("Dropped work", row.title)
    assertEquals(60, row.baselineMinutes)
    assertEquals(0, row.currentMinutes)
    assertTrue(comparison.summary, comparison.summary.contains("no longer scheduled"))
  }

  @Test
  fun anUnchangedPlanSaysSoInsteadOfListingNothing() {
    val items = listOf(item("a"))
    val blocks = listOf(block("b1", "a", 9 * hour))
    val comparison = BaselineVariance.compare(items, blocks, items, blocks)
    assertEquals(0L, comparison.rows.single().driftMinutes)
    assertEquals("Nothing has moved.", comparison.summary)
  }

  @Test
  fun anEmptyComparisonDoesNotClaimStability() {
    val comparison = BaselineVariance.compare(emptyList(), emptyList(), emptyList(), emptyList())
    assertTrue(comparison.rows.isEmpty())
    assertEquals("Nothing was scheduled then or now.", comparison.summary)
  }

  @Test
  fun driftIsMeasuredFromTheEarliestBlockWhenWorkIsSplit() {
    val items = listOf(item("a"))
    val comparison =
      BaselineVariance.compare(
        baselineItems = items,
        baselineBlocks = listOf(block("b1", "a", 14 * hour), block("b2", "a", 9 * hour)),
        currentItems = items,
        currentBlocks = listOf(block("b1", "a", 15 * hour), block("b2", "a", 10 * hour)),
      )
    val row = comparison.rows.single()
    assertEquals(60L, row.driftMinutes)
    assertEquals(120, row.baselineMinutes)
    assertEquals(120, row.currentMinutes)
  }

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

  @Test
  fun theBiggestMovementIsReadFirst() {
    // Ids are UUIDs in the real app, so an id-ordered report puts the headline in a random place.
    // These are named so that id order, title order and movement order all disagree.
    val items =
      listOf(
        item("zzz", "Almost unmoved"),
        item("aaa", "Slipped a week"),
        item("mmm", "Pulled forward"),
        item("kkk", "New work"),
        item("bbb", "Dropped work"),
      )
    val comparison =
      BaselineVariance.compare(
        baselineItems = items,
        baselineBlocks =
          listOf(
            block("b1", "zzz", 9 * hour),
            block("b2", "aaa", 9 * hour),
            block("b3", "mmm", 5 * day),
            block("b5", "bbb", 9 * hour),
          ),
        currentItems = items,
        currentBlocks =
          listOf(
            block("b1", "zzz", 9 * hour + 15 * 60_000L),
            block("b2", "aaa", 9 * hour + 7 * day),
            block("b3", "mmm", 2 * day),
            block("b4", "kkk", 3 * day),
          ),
      )

    assertEquals(
      listOf("Slipped a week", "Almost unmoved", "Pulled forward", "New work", "Dropped work"),
      comparison.rows.map(TaskVariance::title),
    )
    assertEquals(listOf("Slipped a week", "Almost unmoved"), comparison.slipped.map(TaskVariance::title))
    assertEquals(listOf("Pulled forward"), comparison.pulledIn.map(TaskVariance::title))
  }

  @Test
  fun unmovedWorkSinksBelowEverythingThatChanged() {
    val items = listOf(item("a", "Anchor"), item("b", "Moved"))
    val comparison =
      BaselineVariance.compare(
        baselineItems = items,
        baselineBlocks = listOf(block("b1", "a", 9 * hour), block("b2", "b", 9 * hour)),
        currentItems = items,
        currentBlocks = listOf(block("b1", "a", 9 * hour), block("b2", "b", 10 * hour)),
      )
    assertEquals(listOf("Moved", "Anchor"), comparison.rows.map(TaskVariance::title))
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
