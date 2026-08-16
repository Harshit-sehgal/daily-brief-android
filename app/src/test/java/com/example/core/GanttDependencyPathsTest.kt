package com.example.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GanttDependencyPathsTest {
  @Test
  fun `a link is drawn only when both ends are scheduled on this canvas`() {
    val rows =
      listOf(
        row("a", topDp = 0f, start = 0.10, end = 0.20),
        row("b", topDp = 60f, start = 0.30, end = 0.40),
        row("unscheduled", topDp = 120f, start = null, end = null),
      )
    val plan =
      GanttDependencyPaths.connectors(
        rows,
        listOf(
          Triple("d1", "a", "b"),
          Triple("d2", "b", "unscheduled"),
          Triple("d3", "a", "missing-row"),
          Triple("d4", "a", "a"),
        ),
        criticalItemIds = setOf("a", "b"),
      )

    assertEquals(listOf("d1"), plan.connectors.map { it.dependencyId })
    val drawn = plan.connectors.single()
    assertEquals(0.20, drawn.fromPosition, 0.0001)
    assertEquals(0.30, drawn.toPosition, 0.0001)
    // The link is anchored at each row's vertical centre.
    assertEquals(30f, drawn.fromDp, 0.01f)
    assertEquals(90f, drawn.toDp, 0.01f)
    assertTrue(drawn.critical)

    // The three it could not draw are disclosed rather than silently dropped.
    assertEquals(3, plan.undrawnCount)
    assertTrue(plan.undrawnReason.orEmpty(), plan.undrawnReason.orEmpty().contains("not scheduled"))
  }

  @Test
  fun `a successor that starts before its predecessor ends is marked backwards`() {
    val rows =
      listOf(row("a", topDp = 0f, start = 0.4, end = 0.8), row("b", topDp = 50f, start = 0.1, end = 0.3))
    val plan = GanttDependencyPaths.connectors(rows, listOf(Triple("d", "a", "b")))

    val connector = plan.connectors.single()
    assertTrue("a link that runs right to left is the one worth noticing", connector.backwards)
    assertEquals(0, plan.undrawnCount)
    assertNull(plan.undrawnReason)
  }

  @Test
  fun `a dense graph draws a readable subset and says how many it kept back`() {
    val rows = (0 until 200).map { row("i$it", topDp = it * 10f, start = 0.1, end = 0.2) }
    val dependencies = (0 until 199).map { Triple("d$it", "i$it", "i${it + 1}") }

    val plan = GanttDependencyPaths.connectors(rows, dependencies)

    assertEquals(GanttDependencyPaths.MAXIMUM_DRAWN, plan.connectors.size)
    assertEquals(199 - GanttDependencyPaths.MAXIMUM_DRAWN, plan.undrawnCount)
    assertTrue(plan.undrawnReason.orEmpty(), plan.undrawnReason.orEmpty().contains("hidden"))
  }

  @Test
  fun `slack is drawn after the bar and stops at the edge of the range`() {
    val rows =
      listOf(
        row("has-slack", topDp = 0f, start = 0.1, end = 0.2),
        row("critical", topDp = 30f, start = 0.2, end = 0.3),
        row("runs-past-the-edge", topDp = 60f, start = 0.8, end = 0.95),
        row("unscheduled", topDp = 90f, start = null, end = null),
      )
    val rangeMinutes = 10_000L

    val bands =
      GanttDependencyPaths.slackBands(
        rows,
        mapOf(
          "has-slack" to 1_000L,
          "critical" to 0L,
          "runs-past-the-edge" to 5_000L,
          "unscheduled" to 500L,
        ),
        rangeMinutes,
      )

    assertEquals(listOf("has-slack", "runs-past-the-edge"), bands.map { it.itemId })
    assertEquals(0.2, bands.first().fromPosition, 0.0001)
    assertEquals(0.3, bands.first().toPosition, 0.0001)
    // Slack never claims space beyond the visible range.
    assertEquals(1.0, bands.last().toPosition, 0.0001)

    assertTrue(GanttDependencyPaths.slackBands(rows, mapOf("has-slack" to 100L), 0L).isEmpty())
  }

  private fun row(id: String, topDp: Float, start: Double?, end: Double?) =
    GanttRowGeometry(
      itemId = id,
      topDp = topDp,
      heightDp = 60f,
      startPosition = start,
      endPosition = end,
    )
}
