package com.example.core

/** One Plan row as the canvas has already placed it: dp down the canvas, 0..1 across it. */
data class GanttRowGeometry(
  val itemId: String,
  val topDp: Float,
  val heightDp: Float,
  /** Null when the row has nothing scheduled to connect to. */
  val startPosition: Double?,
  val endPosition: Double?,
)

/** A drawn link between two scheduled rows, in the same units the rows were given in. */
data class GanttConnector(
  val dependencyId: String,
  val fromPosition: Double,
  val fromDp: Float,
  val toPosition: Double,
  val toDp: Float,
  val critical: Boolean,
  /** True when the successor starts before its predecessor ends, which is the interesting case. */
  val backwards: Boolean,
)

data class GanttConnectorPlan(
  val connectors: List<GanttConnector>,
  /** Links that exist but are not drawn, so the screen can say so instead of implying none. */
  val undrawnCount: Int,
  val undrawnReason: String?,
)

/** The slack after a bar, expressed in the same 0..1 canvas positions as the bar itself. */
data class GanttSlackBand(val itemId: String, val fromPosition: Double, val toPosition: Double)

/**
 * Where dependency links and slack are drawn, given rows the canvas has already laid out.
 *
 * Kept pure and unit-tested because the interesting cases are all arithmetic: a link whose ends are
 * not both scheduled, a successor that starts before its predecessor finishes, slack that runs past
 * the edge of the visible range. Drawing a line to a row that is not there would invent a
 * relationship the plan does not have.
 */
object GanttDependencyPaths {
  /** Beyond this the canvas becomes a mesh rather than a diagram, so the rest is disclosed. */
  const val MAXIMUM_DRAWN = 120

  fun connectors(
    rows: List<GanttRowGeometry>,
    dependencies: List<Triple<String, String, String>>,
    criticalItemIds: Set<String> = emptySet(),
  ): GanttConnectorPlan {
    val byId = rows.associateBy(GanttRowGeometry::itemId)
    val eligible =
      dependencies.mapNotNull { (id, predecessorId, successorId) ->
        if (predecessorId == successorId) return@mapNotNull null
        val predecessor = byId[predecessorId] ?: return@mapNotNull null
        val successor = byId[successorId] ?: return@mapNotNull null
        val from = predecessor.endPosition ?: return@mapNotNull null
        val to = successor.startPosition ?: return@mapNotNull null
        GanttConnector(
          dependencyId = id,
          fromPosition = from,
          fromDp = predecessor.topDp + predecessor.heightDp / 2f,
          toPosition = to,
          toDp = successor.topDp + successor.heightDp / 2f,
          critical =
            predecessorId in criticalItemIds && successorId in criticalItemIds,
          backwards = to < from,
        )
      }
    val drawn = eligible.take(MAXIMUM_DRAWN)
    val hidden = eligible.size - drawn.size
    val unresolved = dependencies.size - eligible.size
    val reason =
      when {
        hidden > 0 && unresolved > 0 ->
          "$hidden more links are hidden to keep the map readable, and $unresolved connect work " +
            "that is not scheduled here"
        hidden > 0 -> "$hidden more links are hidden to keep the map readable"
        unresolved > 0 -> "$unresolved links connect work that is not scheduled in this range"
        else -> null
      }
    return GanttConnectorPlan(drawn, hidden + unresolved, reason)
  }

  /**
   * Slack is drawn as the room a task has after its own bar. It stops at the edge of the range
   * rather than implying the plan continues past what is on screen.
   */
  fun slackBands(
    rows: List<GanttRowGeometry>,
    slackMinutesByItemId: Map<String, Long>,
    rangeMinutes: Long,
  ): List<GanttSlackBand> {
    if (rangeMinutes <= 0L) return emptyList()
    return rows.mapNotNull { row ->
      val end = row.endPosition ?: return@mapNotNull null
      val slack = slackMinutesByItemId[row.itemId] ?: return@mapNotNull null
      if (slack <= 0L) return@mapNotNull null
      val width = slack.toDouble() / rangeMinutes.toDouble()
      val to = (end + width).coerceAtMost(1.0)
      if (to <= end) null else GanttSlackBand(row.itemId, end, to)
    }
  }
}
