package com.example.ui.viewmodel

import com.example.data.model.PlanItem

data class PlanBatchMoveGroup(
  val selectedItemIds: Set<String>,
  val hierarchyItemIds: Set<String>,
  val alreadyAtDestination: Boolean,
)

/** A fail-closed preview of the exact hierarchy scope submitted to one atomic repository move. */
data class PlanBatchMovePlan(
  val requestedItemIds: Set<String>,
  val unavailableItemIds: Set<String>,
  val crossBoardItemIds: Set<String>,
  val invalidHierarchyItemIds: Set<String>,
  val cyclicHierarchyItemIds: Set<String>,
  val groups: List<PlanBatchMoveGroup>,
) {
  val hierarchyItemIds: Set<String>
    get() = groups.flatMapTo(linkedSetOf(), PlanBatchMoveGroup::hierarchyItemIds)

  val affectedItemIds: Set<String>
    get() =
      groups
        .filterNot(PlanBatchMoveGroup::alreadyAtDestination)
        .flatMapTo(linkedSetOf(), PlanBatchMoveGroup::hierarchyItemIds)

  val includedHierarchyItemIds: Set<String>
    get() = hierarchyItemIds - requestedItemIds

  val alreadyAtDestinationItemIds: Set<String>
    get() =
      groups
        .filter(PlanBatchMoveGroup::alreadyAtDestination)
        .flatMapTo(linkedSetOf(), PlanBatchMoveGroup::selectedItemIds)

  val validationMessage: String?
    get() =
      when {
        requestedItemIds.isEmpty() -> "Choose at least one task"
        unavailableItemIds.isNotEmpty() ->
          "${unavailableItemIds.size} selected task(s) are no longer available"
        crossBoardItemIds.isNotEmpty() -> "A move cannot cross Plans"
        cyclicHierarchyItemIds.isNotEmpty() -> "The selected hierarchy contains a cycle"
        invalidHierarchyItemIds.isNotEmpty() ->
          "The selected hierarchy is incomplete or spans workflow sections"
        else -> null
      }

  val canApply: Boolean
    get() = validationMessage == null && affectedItemIds.isNotEmpty()

  val isNoOp: Boolean
    get() = validationMessage == null && affectedItemIds.isEmpty()
}

/**
 * Expands every selected task to its complete parent/child component. Overlapping selections are
 * collapsed, while stale, cross-Plan, cyclic, or split-column hierarchies fail closed. The
 * repository repeats these checks against fresh rows inside the write transaction.
 */
object PlanBatchMovePolicy {
  /** Expands a selection for lifted-state rendering before a destination is known. */
  fun scope(items: List<PlanItem>, selectedItemIds: Collection<String>): PlanBatchMovePlan =
    plan(items, selectedItemIds, targetColumnId = SCOPE_ONLY_DESTINATION)

  fun plan(
    items: List<PlanItem>,
    selectedItemIds: Collection<String>,
    targetColumnId: String?,
  ): PlanBatchMovePlan {
    val requested = selectedItemIds.filter(String::isNotBlank).toCollection(linkedSetOf())
    val activeItems = items.filter { it.archivedAt == null }
    val byId = activeItems.associateBy(PlanItem::id)
    val available = requested.filterTo(linkedSetOf(), byId::containsKey)
    val unavailable = requested.filterNotTo(linkedSetOf(), byId::containsKey)
    val selectedBoards = available.mapTo(linkedSetOf()) { byId.getValue(it).boardId }
    val crossBoard = if (selectedBoards.size > 1) available else emptySet()
    if (crossBoard.isNotEmpty()) {
      return PlanBatchMovePlan(
        requestedItemIds = requested,
        unavailableItemIds = unavailable,
        crossBoardItemIds = crossBoard,
        invalidHierarchyItemIds = emptySet(),
        cyclicHierarchyItemIds = emptySet(),
        groups = emptyList(),
      )
    }

    val boardId = selectedBoards.singleOrNull()
    val boardItems = activeItems.filter { boardId == null || it.boardId == boardId }
    val boardById = boardItems.associateBy(PlanItem::id)
    val invalidRelations = linkedSetOf<String>()
    val adjacent = mutableMapOf<String, MutableSet<String>>()
    boardItems.forEach { item ->
      val parentId = item.parentId ?: return@forEach
      val parent = boardById[parentId]
      if (parent == null || parent.boardId != item.boardId) {
        invalidRelations += item.id
      } else {
        adjacent.getOrPut(item.id, ::linkedSetOf).add(parent.id)
        adjacent.getOrPut(parent.id, ::linkedSetOf).add(item.id)
      }
    }

    val handled = mutableSetOf<String>()
    val invalid = linkedSetOf<String>()
    val cyclic = linkedSetOf<String>()
    val groups = mutableListOf<PlanBatchMoveGroup>()
    available.forEach { selectedId ->
      if (selectedId in handled) return@forEach
      val queue = ArrayDeque<String>().apply { add(selectedId) }
      val componentIds = linkedSetOf<String>()
      while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!componentIds.add(id)) continue
        adjacent[id].orEmpty().forEach(queue::add)
      }
      val selectedInComponent = available.filterTo(linkedSetOf()) { it in componentIds }
      handled += selectedInComponent
      invalid += componentIds.filter(invalidRelations::contains)
      val component = componentIds.mapNotNull(boardById::get)
      cyclic += cycleItemIds(component, boardById)
      if (component.map(PlanItem::columnId).distinct().size > 1) invalid += componentIds
      groups +=
        PlanBatchMoveGroup(
          selectedItemIds = selectedInComponent,
          hierarchyItemIds = componentIds,
          alreadyAtDestination = component.all { it.columnId == targetColumnId },
        )
    }

    return PlanBatchMovePlan(
      requestedItemIds = requested,
      unavailableItemIds = unavailable,
      crossBoardItemIds = crossBoard,
      invalidHierarchyItemIds = invalid,
      cyclicHierarchyItemIds = cyclic,
      groups = groups,
    )
  }

  private fun cycleItemIds(
    component: List<PlanItem>,
    byId: Map<String, PlanItem>,
  ): Set<String> {
    val componentIds = component.mapTo(linkedSetOf(), PlanItem::id)
    val cyclic = linkedSetOf<String>()
    component.forEach { start ->
      val path = mutableListOf<String>()
      val indexById = mutableMapOf<String, Int>()
      var cursor: String? = start.id
      while (cursor != null && cursor in componentIds) {
        val repeatedAt = indexById[cursor]
        if (repeatedAt != null) {
          cyclic += path.drop(repeatedAt)
          break
        }
        indexById[cursor] = path.size
        path += cursor
        cursor = byId[cursor]?.parentId
      }
    }
    return cyclic
  }

  private const val SCOPE_ONLY_DESTINATION = "\u0000board-drag-scope"
}

data class PlanBatchMoveOutcome(
  val requestedSelectionCount: Int,
  val completedSelectionCount: Int,
  val movedTaskCount: Int,
  val completedStepCount: Int,
  val unavailableSelectionCount: Int,
  val failureMessage: String? = null,
) {
  val completed: Boolean
    get() =
      failureMessage == null &&
        unavailableSelectionCount == 0 &&
        completedSelectionCount == requestedSelectionCount
}
