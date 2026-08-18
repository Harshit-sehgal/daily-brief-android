package com.example.contract

import kotlinx.serialization.Serializable

/**
 * The wire form of `AutoPlan.propose`'s inputs (docs/saas/04-planner-api-contract.md).
 *
 * Every field name and type is that document's, and the document is frozen by this module's
 * golden tests. Enums travel as the strings the device already stores (`"auto"`,
 * `"finish_to_start"`, `"low"`), except [deadlinePolicy], which is the engine enum's own name.
 *
 * A request whose range does not hold ([refusalReason] != null) must be refused, never
 * planned — the refusal is a first-class answer, not an exception.
 */
@Serializable
data class PlanningRequest(
  val v: Int = PlannerApi.VERSION,
  val workspaceId: String,
  val rangeStartMs: Long,
  val rangeEndMs: Long,
  val nowMs: Long,
  val items: List<TaskWire>,
  val blocks: List<ScheduledBlockWire> = emptyList(),
  val fixedCommitments: List<IntervalWire> = emptyList(),
  val dependencies: List<TaskDependencyWire> = emptyList(),
  val scheduleIdByTaskId: Map<String, String> = emptyMap(),
  val schedules: List<WorkScheduleWire> = emptyList(),
  val preferredOrder: List<String> = emptyList(),
  val deadlinePolicy: String = DeadlinePolicyWire.HARD,
) {
  init {
    PlannerApi.checkVersion(v)
  }

  /** Non-null when the request must be refused instead of planned. */
  fun refusalReason(): String? =
    when {
      rangeEndMs <= rangeStartMs -> "rangeEndMs must be greater than rangeStartMs"
      else -> null
    }
}

/** The wire form of [PlanItem] minus the timestamps no planner reads. */
@Serializable
data class TaskWire(
  val id: String,
  val boardId: String,
  val columnId: String? = null,
  val parentId: String? = null,
  val title: String,
  val notes: String? = null,
  val rank: Long,
  val startConstraint: Long? = null,
  val dueAt: Long? = null,
  val effortMinutes: Int? = null,
  val progress: Int = 0,
  val priority: String = "normal",
  val owner: String? = null,
  val schedulingMode: String = "auto",
  val locked: Boolean = false,
  val isMilestone: Boolean = false,
  val completedAt: Long? = null,
  val archivedAt: Long? = null,
)

/** The wire form of [PlanBlock]: existing blocks are worked around, never over. */
@Serializable
data class ScheduledBlockWire(
  val id: String,
  val planItemId: String,
  val startAt: Long,
  val endAt: Long,
  val position: Int = 0,
  val locked: Boolean = false,
  val linkedEventId: String? = null,
)

/** Busy time incl. buffers. The planner may never propose over an interval. */
@Serializable
data class IntervalWire(
  val startAt: Long,
  val endAt: Long,
)

/** The wire form of [PlanDependency]: type is FS/SS/FF/SF with a signed lag in minutes. */
@Serializable
data class TaskDependencyWire(
  val id: String,
  val predecessorId: String,
  val successorId: String,
  val type: String = "finish_to_start",
  val lagMinutes: Int = 0,
)

/** The wire form of [WorkSchedule], with its windows nested. */
@Serializable
data class WorkScheduleWire(
  val id: String,
  val name: String,
  val timeZoneId: String,
  val isDefault: Boolean = false,
  val minimumChunkMinutes: Int = 30,
  val maximumChunkMinutes: Int = 120,
  val bufferMinutes: Int = 0,
  val rank: Long,
  val archivedAt: Long? = null,
  val windows: List<WorkScheduleWindowWire> = emptyList(),
)

/**
 * The wire form of [WorkScheduleWindow]. Weekly rows use [dayOfWeek] and leave [localDate]
 * null; date overrides use an ISO local date and leave [dayOfWeek] null; `kind` is
 * `"weekly"` or `"date_override"`. A single closed override row records an intentionally
 * unavailable day.
 */
@Serializable
data class WorkScheduleWindowWire(
  val id: String,
  val kind: String = "weekly",
  val dayOfWeek: Int? = null,
  val localDate: String? = null,
  val startMinute: Int,
  val endMinute: Int,
  val isClosed: Boolean = false,
  val rank: Long,
)