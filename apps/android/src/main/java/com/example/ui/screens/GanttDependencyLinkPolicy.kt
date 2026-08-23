package com.example.ui.screens

/** The two-tap state used by the Gantt's dependency creation mode. */
data class GanttDependencyLinkState(
  val predecessorId: String? = null,
)

data class GanttDependencyLinkResult(
  val state: GanttDependencyLinkState,
  val predecessorId: String? = null,
  val successorId: String? = null,
)

object GanttDependencyLinkPolicy {
  fun select(state: GanttDependencyLinkState, itemId: String): GanttDependencyLinkResult {
    val predecessorId = state.predecessorId
    return when {
      predecessorId == null ->
        GanttDependencyLinkResult(
          state = GanttDependencyLinkState(predecessorId = itemId),
        )
      predecessorId == itemId ->
        GanttDependencyLinkResult(state = state)
      else ->
        GanttDependencyLinkResult(
          state = GanttDependencyLinkState(),
          predecessorId = predecessorId,
          successorId = itemId,
        )
    }
  }
}
