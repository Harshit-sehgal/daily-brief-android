package com.example.contract

import kotlinx.serialization.Serializable

/**
 * Projects and boards (docs/saas/02-v1-product-spec.md — Stage 4.2, progressive disclosure:
 * Projects → Tasks / Board, Timeline last).
 *
 * A project owns its tasks and its workflow stages (the board columns). The plan request stays
 * workspace-wide: items from any project compose into one week, keyed by `TaskWire.boardId`.
 */
@Serializable
data class ProjectWire(
  val v: Int = PlannerApi.VERSION,
  val id: String,
  val workspaceId: String,
  val name: String,
  val isDefault: Boolean,
  val rank: Long,
  val archivedAt: Long? = null,
)

@Serializable
data class CreateProjectWire(
  val v: Int = PlannerApi.VERSION,
  val name: String,
) {
  init {
    PlannerApi.checkVersion(v)
  }
}

/** One board column. Tasks carry the stage's id as `TaskWire.columnId`; null lands in the client's backlog. */
@Serializable
data class StageWire(
  val id: String,
  val name: String,
  val rank: Long,
  val archivedAt: Long? = null,
)

/** The board payload: one project, its columns, and its active tasks. */
@Serializable
data class BoardWire(
  val v: Int = PlannerApi.VERSION,
  val project: ProjectWire,
  val stages: List<StageWire>,
  val tasks: List<TaskWire>,
)