package com.example.ui.components

import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import kotlin.math.abs

/** Repository-ready values entered by the dependency editor. */
data class DependencyDraft(
  val predecessorId: String,
  val successorId: String,
  val type: String,
  val lagMinutes: Int,
)

internal data class DependencyTaskOption(
  val item: PlanItem,
  /** Title-first label, with a short id only when duplicate titles need disambiguation. */
  val label: String,
)

internal data class DependencyUiRow(
  val dependency: PlanDependency,
  val predecessorTitle: String,
  val successorTitle: String,
  val typeCode: String,
  val typeLabel: String,
  val offsetLabel: String,
  val accessibilityLabel: String,
  val issue: String?,
)

/** Pure projection and validation shared by Compose and focused JVM tests. */
internal object DependencyUiProjector {
  val Types =
    listOf(
      PlanDependencyType.FINISH_TO_START,
      PlanDependencyType.START_TO_START,
      PlanDependencyType.FINISH_TO_FINISH,
      PlanDependencyType.START_TO_FINISH,
    )

  fun taskOptions(boardId: String, items: List<PlanItem>): List<DependencyTaskOption> {
    val active =
      items
        .filter { it.boardId == boardId && it.archivedAt == null }
        .sortedWith(compareBy<PlanItem>({ it.rank }, { it.createdAt }, { it.id }))
    val titleCounts = active.groupingBy(::baseTitle).eachCount()
    return active.map { item ->
      val title = baseTitle(item)
      DependencyTaskOption(
        item = item,
        label = if (titleCounts.getValue(title) > 1) "$title · ${shortId(item.id)}" else title,
      )
    }
  }

  fun rows(
    boardId: String,
    items: List<PlanItem>,
    dependencies: List<PlanDependency>,
  ): List<DependencyUiRow> {
    val allItemsById = items.associateBy { it.id }
    val options = taskOptions(boardId, items)
    val optionLabels = options.associate { it.item.id to it.label }

    return dependencies
      .filter { it.boardId == boardId }
      .map { dependency ->
        val predecessor = allItemsById[dependency.predecessorId]
        val successor = allItemsById[dependency.successorId]
        val predecessorTitle = endpointLabel(dependency.predecessorId, predecessor, optionLabels)
        val successorTitle = endpointLabel(dependency.successorId, successor, optionLabels)
        val issue =
          relationshipIssue(
            boardId = boardId,
            dependency = dependency,
            predecessor = predecessor,
            successor = successor,
          )
        val code = typeCode(dependency.type)
        val typeLabel = typeLabel(dependency.type)
        val offset = offsetLabel(dependency.lagMinutes)
        DependencyUiRow(
          dependency = dependency,
          predecessorTitle = predecessorTitle,
          successorTitle = successorTitle,
          typeCode = code,
          typeLabel = typeLabel,
          offsetLabel = offset,
          accessibilityLabel =
            "$predecessorTitle precedes $successorTitle. $code, $typeLabel. $offset." +
              if (issue == null) "" else " Needs attention: $issue",
          issue = issue,
        )
      }
      .sortedWith(
        compareBy<DependencyUiRow>(
          { it.predecessorTitle.lowercase() },
          { it.successorTitle.lowercase() },
          { Types.indexOf(it.dependency.type).let { index -> if (index < 0) Int.MAX_VALUE else index } },
          { it.dependency.id },
        )
      )
  }

  fun draftError(
    draft: DependencyDraft,
    options: List<DependencyTaskOption>,
    dependencies: List<PlanDependency>,
    editingDependencyId: String?,
  ): String? {
    val candidateIds = options.mapTo(mutableSetOf()) { it.item.id }
    return when {
      draft.predecessorId !in candidateIds || draft.successorId !in candidateIds ->
        "Choose active tasks from this Plan."
      draft.predecessorId == draft.successorId -> "Choose two different tasks."
      draft.type !in Types -> "Choose a supported dependency type."
      dependencies.any {
        it.id != editingDependencyId &&
          it.predecessorId == draft.predecessorId &&
          it.successorId == draft.successorId
      } -> "That relationship already exists."
      else -> null
    }
  }

  fun typeCode(type: String): String =
    when (type) {
      PlanDependencyType.FINISH_TO_START -> "FS"
      PlanDependencyType.START_TO_START -> "SS"
      PlanDependencyType.FINISH_TO_FINISH -> "FF"
      PlanDependencyType.START_TO_FINISH -> "SF"
      else -> "?"
    }

  fun typeLabel(type: String): String =
    when (type) {
      PlanDependencyType.FINISH_TO_START -> "Finish to start"
      PlanDependencyType.START_TO_START -> "Start to start"
      PlanDependencyType.FINISH_TO_FINISH -> "Finish to finish"
      PlanDependencyType.START_TO_FINISH -> "Start to finish"
      else -> "Unknown type: $type"
    }

  fun offsetLabel(lagMinutes: Int): String {
    if (lagMinutes == 0) return "0m · no lead or lag"
    val magnitude = durationLabel(abs(lagMinutes.toLong()))
    return if (lagMinutes > 0) "+$magnitude lag" else "−$magnitude lead"
  }

  private fun relationshipIssue(
    boardId: String,
    dependency: PlanDependency,
    predecessor: PlanItem?,
    successor: PlanItem?,
  ): String? =
    when {
      predecessor == null -> "Predecessor task is missing."
      successor == null -> "Successor task is missing."
      predecessor.boardId != boardId || successor.boardId != boardId ->
        "Both tasks must belong to this Plan."
      predecessor.archivedAt != null -> "Predecessor task is archived."
      successor.archivedAt != null -> "Successor task is archived."
      dependency.predecessorId == dependency.successorId -> "A task cannot depend on itself."
      dependency.type !in Types -> "Dependency type is not supported."
      else -> null
    }

  private fun endpointLabel(
    id: String,
    item: PlanItem?,
    activeLabels: Map<String, String>,
  ): String =
    activeLabels[id]
      ?: when {
        item == null -> "Missing task · ${shortId(id)}"
        item.archivedAt != null -> "${baseTitle(item)} (archived)"
        else -> baseTitle(item)
      }

  private fun baseTitle(item: PlanItem): String = item.title.trim().ifEmpty { "Untitled task" }

  private fun shortId(id: String): String = id.takeLast(6).ifEmpty { "unknown" }

  private fun durationLabel(minutes: Long): String {
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
      hours == 0L -> "${remainingMinutes}m"
      remainingMinutes == 0L -> "${hours}h"
      else -> "${hours}h ${remainingMinutes}m"
    }
  }
}
