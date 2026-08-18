package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A stable planning container. Display names can change without breaking references. */
@Entity(
  tableName = "plan_boards",
  indices = [Index(value = ["nameKey"], unique = true), Index(value = ["rank"])],
)
data class PlanBoard(
  @PrimaryKey val id: String,
  val name: String,
  /** Trimmed, locale-independent lower-case name used only for uniqueness. */
  val nameKey: String,
  val rank: Long,
  val isDefault: Boolean = false,
  val archivedAt: Long? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "plan_columns",
  foreignKeys = [
    ForeignKey(
      entity = PlanBoard::class,
      parentColumns = ["id"],
      childColumns = ["boardId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [
    Index(value = ["boardId", "nameKey"], unique = true),
    Index(value = ["boardId", "id"], unique = true),
    Index(value = ["boardId", "rank"]),
  ],
)
data class PlanColumn(
  @PrimaryKey val id: String,
  val boardId: String,
  val name: String,
  val nameKey: String,
  val rank: Long,
  val archivedAt: Long? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

/** Flexible, app-owned work. Calendar commitments deliberately never become this entity. */
@Entity(
  tableName = "plan_items",
  foreignKeys = [
    ForeignKey(
      entity = PlanBoard::class,
      parentColumns = ["id"],
      childColumns = ["boardId"],
    ),
    ForeignKey(
      entity = PlanColumn::class,
      parentColumns = ["id"],
      childColumns = ["columnId"],
      onDelete = ForeignKey.SET_NULL,
    ),
    // The ID-only key supplies SET_NULL; this companion key makes a
    // mismatched board/column pair impossible through direct DAO writes.
    ForeignKey(
      entity = PlanColumn::class,
      parentColumns = ["boardId", "id"],
      childColumns = ["boardId", "columnId"],
    ),
    ForeignKey(
      entity = PlanItem::class,
      parentColumns = ["id"],
      childColumns = ["parentId"],
      onDelete = ForeignKey.SET_NULL,
    ),
    // Retain SET_NULL through the ID-only key while enforcing same-board
    // hierarchy through the composite key.
    ForeignKey(
      entity = PlanItem::class,
      parentColumns = ["boardId", "id"],
      childColumns = ["boardId", "parentId"],
    ),
  ],
  indices = [
    Index(value = ["boardId", "id"], unique = true),
    Index(value = ["boardId", "columnId", "rank"]),
    Index(value = ["boardId", "parentId"]),
    Index(value = ["columnId"]),
    Index(value = ["parentId"]),
    Index(value = ["dueAt"]),
    Index(value = ["archivedAt"]),
  ],
)
data class PlanItem(
  @PrimaryKey val id: String,
  val boardId: String,
  /** Null is the board Inbox: captured but not yet routed into a workflow column. */
  val columnId: String? = null,
  val parentId: String? = null,
  val title: String,
  val notes: String? = null,
  val rank: Long,
  val startConstraint: Long? = null,
  val dueAt: Long? = null,
  val effortMinutes: Int? = null,
  val progress: Int = 0,
  val priority: String = PlanPriority.NORMAL,
  val owner: String? = null,
  val schedulingMode: String = PlanSchedulingMode.AUTO,
  val locked: Boolean = false,
  val isMilestone: Boolean = false,
  val completedAt: Long? = null,
  val archivedAt: Long? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "plan_blocks",
  foreignKeys = [
    ForeignKey(
      entity = PlanItem::class,
      parentColumns = ["id"],
      childColumns = ["planItemId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [
    Index(value = ["planItemId", "startAt"]),
    Index(value = ["startAt", "endAt"]),
    Index(value = ["linkedEventId"]),
  ],
)
data class PlanBlock(
  @PrimaryKey val id: String,
  val planItemId: String,
  val startAt: Long,
  val endAt: Long,
  val position: Int = 0,
  val locked: Boolean = false,
  /** Optional reference only; provider rows may disappear, so this is intentionally not an FK. */
  val linkedEventId: String? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "plan_dependencies",
  foreignKeys = [
    ForeignKey(
      entity = PlanItem::class,
      parentColumns = ["id"],
      childColumns = ["predecessorId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = PlanItem::class,
      parentColumns = ["id"],
      childColumns = ["successorId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = PlanItem::class,
      parentColumns = ["boardId", "id"],
      childColumns = ["boardId", "predecessorId"],
    ),
    ForeignKey(
      entity = PlanItem::class,
      parentColumns = ["boardId", "id"],
      childColumns = ["boardId", "successorId"],
    ),
  ],
  indices = [
    Index(value = ["predecessorId", "successorId"], unique = true),
    Index(value = ["successorId"]),
    Index(value = ["boardId", "predecessorId"]),
    Index(value = ["boardId", "successorId"]),
  ],
)
data class PlanDependency(
  @PrimaryKey val id: String,
  /** Duplicated so SQLite can enforce that both endpoints stay on one board. */
  val boardId: String,
  val predecessorId: String,
  val successorId: String,
  val type: String = PlanDependencyType.FINISH_TO_START,
  val lagMinutes: Int = 0,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "saved_views",
  foreignKeys = [
    ForeignKey(
      entity = PlanBoard::class,
      parentColumns = ["id"],
      childColumns = ["boardId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [
    Index(value = ["boardId", "surface", "rank"]),
    Index(value = ["surface", "pinned"]),
    // The uniqueness PlanRepository checks in code (03 §4: "two constraints live only in
    // application code") is now real: one view per name per board+surface.
    Index(value = ["boardId", "surface", "nameKey"], unique = true),
  ],
)
data class SavedView(
  @PrimaryKey val id: String,
  val boardId: String? = null,
  val name: String,
  val nameKey: String,
  val surface: String,
  val filtersJson: String = "{}",
  val grouping: String = "none",
  val sortJson: String = "[]",
  val columnsJson: String = "[]",
  val rangeDays: Int? = null,
  val zoom: String? = null,
  val collapsedIdsJson: String = "[]",
  val pinned: Boolean = false,
  val rank: Long,
  val createdAt: Long,
  val updatedAt: Long,
)

/**
 * A named snapshot of what a plan looked like at one moment.
 *
 * The tasks and blocks inside it are encoded records rather than foreign keys on purpose: a baseline
 * has to survive the work it describes being edited, moved or archived, because that is the whole
 * point of keeping one. It is history, not a second copy of the live plan.
 *
 * The board it belongs to is the exception. A baseline of a plan that no longer exists describes
 * nothing and can never be read again, so it cascades — the same rule saved views follow.
 */
@Entity(
  tableName = "plan_baselines",
  foreignKeys = [
    ForeignKey(
      entity = PlanBoard::class,
      parentColumns = ["id"],
      childColumns = ["boardId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index(value = ["boardId", "capturedAt"]), Index(value = ["boardId", "nameKey"], unique = true)],
)
data class PlanBaseline(
  @PrimaryKey val id: String,
  val boardId: String,
  val name: String,
  val nameKey: String,
  val capturedAt: Long,
  /** Length-prefixed records, one per task, in the mutation codec's field encoding. */
  val itemsJson: String,
  /** Length-prefixed records, one per block. */
  val blocksJson: String,
)

/**
 * Reusable wall-clock availability for app-owned work.
 *
 * The time-zone ID is stored with the schedule so daylight-saving and travel behavior never depend
 * on the device zone at query time. Weekly and date-specific windows live in
 * [WorkScheduleWindow].
 */
@Entity(
  tableName = "work_schedules",
  indices = [
    Index(value = ["nameKey"], unique = true),
    Index(value = ["isDefault", "rank"]),
    Index(value = ["archivedAt"]),
  ],
)
data class WorkSchedule(
  @PrimaryKey val id: String,
  val name: String,
  val nameKey: String,
  val timeZoneId: String,
  val isDefault: Boolean = false,
  val minimumChunkMinutes: Int = 30,
  val maximumChunkMinutes: Int = 120,
  val bufferMinutes: Int = 0,
  val rank: Long,
  val archivedAt: Long? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

/**
 * One weekly availability window or one date-override window.
 *
 * Weekly rows use [dayOfWeek] and leave [localDate] null. Date overrides use an ISO local date and
 * leave [dayOfWeek] null. A single closed override row (zero-length with [isClosed]) records that a
 * normally-working date is intentionally unavailable; repository validation owns those invariants.
 */
@Entity(
  tableName = "work_schedule_windows",
  foreignKeys = [
    ForeignKey(
      entity = WorkSchedule::class,
      parentColumns = ["id"],
      childColumns = ["scheduleId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [
    Index(value = ["scheduleId", "kind", "dayOfWeek", "rank"]),
    Index(value = ["scheduleId", "kind", "localDate", "rank"]),
  ],
)
data class WorkScheduleWindow(
  @PrimaryKey val id: String,
  val scheduleId: String,
  val kind: String = WorkScheduleWindowKind.WEEKLY,
  val dayOfWeek: Int? = null,
  val localDate: String? = null,
  val startMinute: Int,
  val endMinute: Int,
  val isClosed: Boolean = false,
  val rank: Long,
  val createdAt: Long,
  val updatedAt: Long,
)

/**
 * Optional per-item override. An unmapped item follows the active default work schedule.
 *
 * Deleting an item removes its mapping. Deleting a referenced schedule is deliberately restricted
 * until the caller explicitly reassigns or removes every mapping.
 */
@Entity(
  tableName = "plan_item_schedules",
  foreignKeys = [
    ForeignKey(
      entity = PlanItem::class,
      parentColumns = ["id"],
      childColumns = ["planItemId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = WorkSchedule::class,
      parentColumns = ["id"],
      childColumns = ["workScheduleId"],
    ),
  ],
  indices = [Index(value = ["workScheduleId"])],
)
data class PlanItemSchedule(
  @PrimaryKey val planItemId: String,
  val workScheduleId: String,
  val createdAt: Long,
  val updatedAt: Long,
)

/**
 * Durable journal entry for one atomic planning command.
 *
 * Before/after payloads make Undo recoverable after process death. Payload interpretation is
 * versioned and kept outside Room so future command types do not require a wide schema.
 */
@Entity(
  tableName = "plan_mutations",
  foreignKeys = [
    ForeignKey(
      entity = PlanBoard::class,
      parentColumns = ["id"],
      childColumns = ["boardId"],
      onDelete = ForeignKey.SET_NULL,
    )
  ],
  indices = [
    Index(value = ["boardId", "createdAt"]),
    Index(value = ["status", "createdAt"]),
    Index(value = ["expiresAt"]),
  ],
)
data class PlanMutation(
  @PrimaryKey val id: String,
  val boardId: String? = null,
  val mutationType: String,
  val targetType: String,
  val targetIdsJson: String = "[]",
  val summary: String,
  val beforeJson: String? = null,
  val afterJson: String? = null,
  val status: String = PlanMutationStatus.APPLIED,
  val origin: String = PlanMutationOrigin.USER,
  val schemaVersion: Int = 1,
  val createdAt: Long,
  val updatedAt: Long,
  val undoneAt: Long? = null,
  val expiresAt: Long? = null,
)

object PlanPriority {
  const val LOW = "low"
  const val NORMAL = "normal"
  const val HIGH = "high"
  const val URGENT = "urgent"
  val All = setOf(LOW, NORMAL, HIGH, URGENT)
}

object PlanSchedulingMode {
  const val AUTO = "auto"
  const val MANUAL = "manual"
  val All = setOf(AUTO, MANUAL)
}

object PlanDependencyType {
  const val FINISH_TO_START = "finish_to_start"
  const val START_TO_START = "start_to_start"
  const val FINISH_TO_FINISH = "finish_to_finish"
  const val START_TO_FINISH = "start_to_finish"
  val All = setOf(FINISH_TO_START, START_TO_START, FINISH_TO_FINISH, START_TO_FINISH)
}

object PlanSurface {
  const val OUTLINE = "outline"
  const val BOARD = "board"
  const val GANTT = "gantt"
}

object WorkScheduleWindowKind {
  const val WEEKLY = "weekly"
  const val DATE_OVERRIDE = "date_override"
  val All = setOf(WEEKLY, DATE_OVERRIDE)
}

object PlanMutationStatus {
  const val APPLIED = "applied"
  const val UNDONE = "undone"
  const val EXPIRED = "expired"
  val All = setOf(APPLIED, UNDONE, EXPIRED)
}

object PlanMutationOrigin {
  const val USER = "user"
  const val AUTO_PLAN = "auto_plan"
  const val IMPORT = "import"
  val All = setOf(USER, AUTO_PLAN, IMPORT)
}
