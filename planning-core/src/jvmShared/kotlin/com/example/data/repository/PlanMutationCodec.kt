package com.example.data.repository

import com.example.data.model.PlanBlock
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import com.example.data.model.PlanItemSchedule
import com.example.data.model.PlanPriority
import com.example.data.model.PlanSchedulingMode

object PlanMutationType {
  const val ITEM_CREATE = "item_create"
  const val ITEM_EDIT = "item_edit"
  const val ITEM_DELETE = "item_delete"
  const val ITEM_MOVE = "item_move"
  const val ITEM_PROGRESS = "item_progress"
  const val BLOCK_CREATE = "block_create"
  const val BLOCK_EDIT = "block_edit"
  const val BLOCK_DELETE = "block_delete"
  const val DEPENDENCY_CREATE = "dependency_create"
  const val DEPENDENCY_EDIT = "dependency_edit"
  const val DEPENDENCY_DELETE = "dependency_delete"
  const val ITEM_SCHEDULE_ASSIGN = "item_schedule_assign"
  const val BOARD_CREATE = "board_create"
  const val BOARD_EDIT = "board_edit"
  const val BOARD_DELETE = "board_delete"
  const val COLUMN_CREATE = "column_create"
  const val COLUMN_EDIT = "column_edit"
  const val COLUMN_DELETE = "column_delete"
  const val WORK_SCHEDULE_CREATE = "work_schedule_create"
  const val WORK_SCHEDULE_UPDATE = "work_schedule_update"
  const val WORK_SCHEDULE_DEFAULT = "work_schedule_default"
  const val WORK_SCHEDULE_ARCHIVE = "work_schedule_archive"

  val Catalog =
    setOf(BOARD_CREATE, BOARD_EDIT, BOARD_DELETE, COLUMN_CREATE, COLUMN_EDIT, COLUMN_DELETE)

  val WorkingSchedules =
    setOf(
      WORK_SCHEDULE_CREATE,
      WORK_SCHEDULE_UPDATE,
      WORK_SCHEDULE_DEFAULT,
      WORK_SCHEDULE_ARCHIVE,
    )

  val All =
    setOf(
      ITEM_CREATE,
      ITEM_EDIT,
      ITEM_DELETE,
      ITEM_MOVE,
      ITEM_PROGRESS,
      BLOCK_CREATE,
      BLOCK_EDIT,
      BLOCK_DELETE,
      DEPENDENCY_CREATE,
      DEPENDENCY_EDIT,
      DEPENDENCY_DELETE,
      ITEM_SCHEDULE_ASSIGN,
      BOARD_CREATE,
      BOARD_EDIT,
      BOARD_DELETE,
      COLUMN_CREATE,
      COLUMN_EDIT,
      COLUMN_DELETE,
      WORK_SCHEDULE_CREATE,
      WORK_SCHEDULE_UPDATE,
      WORK_SCHEDULE_DEFAULT,
      WORK_SCHEDULE_ARCHIVE,
    )
}

object PlanMutationTarget {
  const val ITEM = "item"
  const val ITEM_GROUP = "item_group"
  const val BLOCK = "block"
  const val BLOCK_GROUP = "block_group"
  const val DEPENDENCY = "dependency"
  const val ITEM_SCHEDULE = "item_schedule"
  const val CATALOG = "catalog"
  const val WORK_SCHEDULE = "work_schedule"
}

sealed interface PlanMutationState {
  val targetIds: List<String>
}

data class PlanPlacementState(
  val itemId: String,
  val columnId: String?,
  val rank: Long,
  val updatedAt: Long,
)

data class PlanMoveState(val items: List<PlanPlacementState>) : PlanMutationState {
  override val targetIds: List<String>
    get() = items.map(PlanPlacementState::itemId)
}

data class PlanProgressState(
  val itemId: String,
  val progress: Int,
  val completedAt: Long?,
  val updatedAt: Long,
) : PlanMutationState {
  override val targetIds: List<String> = listOf(itemId)
}

data class PlanItemState(
  val id: String,
  val boardId: String,
  val columnId: String?,
  val parentId: String?,
  val title: String,
  val notes: String?,
  val rank: Long,
  val startConstraint: Long?,
  val dueAt: Long?,
  val effortMinutes: Int?,
  val progress: Int,
  val priority: String,
  val owner: String?,
  val schedulingMode: String,
  val locked: Boolean,
  val isMilestone: Boolean,
  val completedAt: Long?,
  val archivedAt: Long?,
  val createdAt: Long,
  val updatedAt: Long,
) : PlanMutationState {
  override val targetIds: List<String> = listOf(id)

  fun toModel(): PlanItem =
    PlanItem(
      id = id,
      boardId = boardId,
      columnId = columnId,
      parentId = parentId,
      title = title,
      notes = notes,
      rank = rank,
      startConstraint = startConstraint,
      dueAt = dueAt,
      effortMinutes = effortMinutes,
      progress = progress,
      priority = priority,
      owner = owner,
      schedulingMode = schedulingMode,
      locked = locked,
      isMilestone = isMilestone,
      completedAt = completedAt,
      archivedAt = archivedAt,
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
}

/** Full snapshots for edits which legitimately affect a whole task hierarchy. */
data class PlanItemGroupState(val items: List<PlanItemState>) : PlanMutationState {
  init {
    require(items.map(PlanItemState::id) == items.map(PlanItemState::id).sorted()) {
      "Task group journal must use canonical task order"
    }
  }

  override val targetIds: List<String>
    get() = items.map(PlanItemState::id)
}

data class PlanBlockState(
  val id: String,
  val planItemId: String,
  val startAt: Long,
  val endAt: Long,
  val position: Int,
  val locked: Boolean,
  val linkedEventId: String?,
  val createdAt: Long,
  val updatedAt: Long,
) : PlanMutationState {
  override val targetIds: List<String> = listOf(id)

  fun toModel(): PlanBlock =
    PlanBlock(
      id = id,
      planItemId = planItemId,
      startAt = startAt,
      endAt = endAt,
      position = position,
      locked = locked,
      linkedEventId = linkedEventId,
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
}

/**
 * Several blocks written as one command, so an auto-plan is one entry in History, not twenty.
 *
 * [absentIds] names blocks that must *not* exist in this state. Without it a command that replaces
 * a schedule — restoring a baseline — cannot be journalled at all, because before and after would
 * describe different sets of rows and Undo would have no way to remove what the command created.
 * Ids are sorted so that the two sides of one command always agree on their target set.
 */
data class PlanBlockGroupState(
  val blocks: List<PlanBlockState>,
  val absentIds: List<String> = emptyList(),
) : PlanMutationState {
  override val targetIds: List<String>
    get() = (blocks.map(PlanBlockState::id) + absentIds).sorted()
}

data class PlanDependencyState(
  val id: String,
  val boardId: String,
  val predecessorId: String,
  val successorId: String,
  val type: String,
  val lagMinutes: Int,
  val createdAt: Long,
  val updatedAt: Long,
) : PlanMutationState {
  override val targetIds: List<String> = listOf(id)

  fun toModel(): PlanDependency =
    PlanDependency(
      id = id,
      boardId = boardId,
      predecessorId = predecessorId,
      successorId = successorId,
      type = type,
      lagMinutes = lagMinutes,
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
}

/** A row snapshot, including the explicit absence used by the inherited-default state. */
data class PlanItemScheduleState(
  val planItemId: String,
  val workScheduleId: String?,
  val createdAt: Long?,
  val updatedAt: Long?,
) : PlanMutationState {
  override val targetIds: List<String> = listOf(planItemId)

  fun toModelOrNull(): PlanItemSchedule? =
    workScheduleId?.let { scheduleId ->
      PlanItemSchedule(
        planItemId = planItemId,
        workScheduleId = scheduleId,
        createdAt = requireNotNull(createdAt),
        updatedAt = requireNotNull(updatedAt),
      )
    }

  companion object {
    fun inherited(planItemId: String) = PlanItemScheduleState(planItemId, null, null, null)
  }
}

data class EncodedPlanMutationState(val targetIdsJson: String, val stateJson: String)

/**
 * Version-one journal payloads.
 *
 * The outer representation is real JSON, limited to string arrays/objects by [SavedViewCodec].
 * Each object's value is a length-prefixed record, so arbitrary Unicode, delimiters and nulls are
 * reversible without a JSON dependency. Decode validates type, field count, identity and bounds.
 */
object PlanMutationCodec {
  fun encode(state: PlanMutationState): EncodedPlanMutationState {
    if (state is PlanCatalogState) return PlanCatalogMutationCodec.encode(state)
    if (state is WorkingScheduleMutationState) return WorkingScheduleMutationCodec.encode(state)
    validateTargetIds(state.targetIds)
    val records =
      when (state) {
        is PlanMoveState -> {
          require(state.items.isNotEmpty()) { "A move journal needs at least one task" }
          state.items.associate { placement ->
            placement.itemId to
              Fields.encode(
                listOf(
                  placement.columnId,
                  placement.rank.toString(),
                  placement.updatedAt.toString(),
                )
              )
          }
        }
        is PlanProgressState -> {
          validateProgress(state)
          mapOf(
            state.itemId to
              Fields.encode(
                listOf(
                  state.progress.toString(),
                  state.completedAt?.toString(),
                  state.updatedAt.toString(),
                )
              )
          )
        }
        is PlanItemState -> {
          validateItem(state)
          mapOf(state.id to Fields.encode(state.fields()))
        }
        is PlanItemGroupState -> {
          validateItemGroup(state)
          state.items.associate { item -> item.id to Fields.encode(item.fields()) }
        }
        is PlanBlockState -> {
          validateBlock(state)
          mapOf(state.id to Fields.encode(state.fields()))
        }
        is PlanBlockGroupState -> {
          validateBlockGroup(state)
          state.blocks.associate { block -> block.id to Fields.encode(block.fields()) } +
            state.absentIds.associateWith { ABSENT_RECORD }
        }
        is PlanDependencyState -> {
          validateDependency(state)
          mapOf(state.id to Fields.encode(state.fields()))
        }
        is PlanItemScheduleState -> {
          validateItemSchedule(state)
          mapOf(state.planItemId to Fields.encode(state.fields()))
        }
        is PlanCatalogState -> error("Catalog mutations use their aggregate codec")
        is WorkingScheduleMutationState ->
          error("Working-schedule mutations use their aggregate codec")
      }
    require(records.size == state.targetIds.size) { "Mutation target IDs must be unique" }
    return EncodedPlanMutationState(
      targetIdsJson = SavedViewCodec.encodeStringArray(state.targetIds.sorted()),
      stateJson = SavedViewCodec.encodeStringObject(records),
    )
  }

  /** Returns null rather than attempting a partial or mismatched recovery. */
  fun decode(
    mutationType: String,
    targetIdsJson: String,
    stateJson: String?,
    schemaVersion: Int = SCHEMA_VERSION,
  ): PlanMutationState? {
    if (stateJson == null || schemaVersion != SCHEMA_VERSION || mutationType !in PlanMutationType.All) {
      return null
    }
    return runCatching {
        val expectedIds = SavedViewCodec.decodeStringArray(targetIdsJson)
        validateTargetIds(expectedIds)
        require(expectedIds == expectedIds.sorted()) { "Journal targets must be canonical" }
        if (mutationType in PlanMutationType.Catalog) {
          require(expectedIds.size == 1) { "Catalog mutation must target one command scope" }
          return@runCatching PlanCatalogMutationCodec.decode(expectedIds.single(), stateJson)
            ?: error("Invalid catalog mutation")
        }
        if (mutationType in PlanMutationType.WorkingSchedules) {
          require(expectedIds.size == 1) {
            "Working-schedule mutation must target one command scope"
          }
          return@runCatching WorkingScheduleMutationCodec.decode(expectedIds.single(), stateJson)
            ?: error("Invalid working-schedule mutation")
        }
        val records = SavedViewCodec.decodeStringObject(stateJson)
        require(records.keys == expectedIds.toSet()) { "Journal targets and records differ" }
        when (mutationType) {
          PlanMutationType.ITEM_MOVE ->
            PlanMoveState(
              expectedIds.map { id ->
                val fields = Fields.decode(records.getValue(id), 3)
                PlanPlacementState(
                  itemId = id,
                  columnId = fields[0],
                  rank = fields[1].requiredLong("rank"),
                  updatedAt = fields[2].requiredLong("updated time"),
                )
              }
            )
          PlanMutationType.ITEM_PROGRESS -> {
            require(expectedIds.size == 1) { "Progress mutation must target one task" }
            val id = expectedIds.single()
            val fields = Fields.decode(records.getValue(id), 3)
            PlanProgressState(
              itemId = id,
              progress = fields[0].requiredInt("progress").also { require(it in 0..100) },
              completedAt = fields[1].nullableLong("completion time"),
              updatedAt = fields[2].requiredLong("updated time"),
            ).also(::validateProgress)
          }
          PlanMutationType.ITEM_CREATE,
          PlanMutationType.ITEM_DELETE -> decodeItem(expectedIds, records)
          PlanMutationType.ITEM_EDIT ->
            if (expectedIds.size == 1) decodeItem(expectedIds, records)
            else {
              PlanItemGroupState(
                  expectedIds.map { id -> decodeItemRecord(id, records.getValue(id)) }
                )
                .also(::validateItemGroup)
            }
          PlanMutationType.BLOCK_CREATE,
          PlanMutationType.BLOCK_EDIT,
          PlanMutationType.BLOCK_DELETE ->
            if (expectedIds.size == 1 && records.getValue(expectedIds.single()) != ABSENT_RECORD) {
              decodeBlock(expectedIds, records)
            } else {
              val (absent, present) = expectedIds.partition { records.getValue(it) == ABSENT_RECORD }
              PlanBlockGroupState(
                  blocks = present.map { id -> decodeBlockRecord(id, records.getValue(id)) },
                  absentIds = absent,
                )
                .also(::validateBlockGroup)
            }
          PlanMutationType.DEPENDENCY_CREATE,
          PlanMutationType.DEPENDENCY_EDIT,
          PlanMutationType.DEPENDENCY_DELETE -> decodeDependency(expectedIds, records)
          PlanMutationType.ITEM_SCHEDULE_ASSIGN -> decodeItemSchedule(expectedIds, records)
          else -> error("Mutation type was checked")
        }
      }
      .getOrNull()
  }

  private fun decodeItem(ids: List<String>, records: Map<String, String>): PlanItemState {
    require(ids.size == 1) { "Item mutation must target one task" }
    val id = ids.single()
    return decodeItemRecord(id, records.getValue(id))
  }

  private fun decodeItemRecord(id: String, record: String): PlanItemState {
    val fields = Fields.decode(record, ITEM_FIELD_COUNT)
    return PlanItemState(
      id = id,
      boardId = fields[0].required("board ID"),
      columnId = fields[1],
      parentId = fields[2],
      title = fields[3].required("title"),
      notes = fields[4],
      rank = fields[5].requiredLong("rank"),
      startConstraint = fields[6].nullableLong("start constraint"),
      dueAt = fields[7].nullableLong("due time"),
      effortMinutes = fields[8].nullableInt("effort"),
      progress = fields[9].requiredInt("progress").also { require(it in 0..100) },
      priority = fields[10].required("priority"),
      owner = fields[11],
      schedulingMode = fields[12].required("scheduling mode"),
      locked = fields[13].requiredBoolean("locked"),
      isMilestone = fields[14].requiredBoolean("milestone"),
      completedAt = fields[15].nullableLong("completion time"),
      archivedAt = fields[16].nullableLong("archive time"),
      createdAt = fields[17].requiredLong("created time"),
      updatedAt = fields[18].requiredLong("updated time"),
    ).also(::validateItem)
  }

  private fun decodeDependency(
    ids: List<String>,
    records: Map<String, String>,
  ): PlanDependencyState {
    require(ids.size == 1) { "Dependency mutation must target one dependency" }
    val id = ids.single()
    val fields = Fields.decode(records.getValue(id), DEPENDENCY_FIELD_COUNT)
    return PlanDependencyState(
        id = id,
        boardId = fields[0].required("board ID"),
        predecessorId = fields[1].required("predecessor ID"),
        successorId = fields[2].required("successor ID"),
        type = fields[3].required("dependency type"),
        lagMinutes = fields[4].requiredInt("dependency lag"),
        createdAt = fields[5].requiredLong("created time"),
        updatedAt = fields[6].requiredLong("updated time"),
      )
      .also(::validateDependency)
  }

  private fun decodeBlock(ids: List<String>, records: Map<String, String>): PlanBlockState {
    require(ids.size == 1) { "Block mutation must target one block" }
    val id = ids.single()
    return decodeBlockRecord(id, records.getValue(id))
  }

  /** Baselines reuse these record encoders so they decode under the journal's own rules. */
  fun encodeStateRecord(state: PlanMutationState): String =
    when (state) {
      is PlanItemState -> Fields.encode(state.fields())
      is PlanBlockState -> Fields.encode(state.fields())
      else -> error("Only tasks and blocks are stored as standalone records")
    }

  fun decodeItemRecordFor(id: String, record: String): PlanItemState =
    decodeItemRecord(id, record)

  fun decodeBlockRecordFor(id: String, record: String): PlanBlockState =
    decodeBlockRecord(id, record)

  private fun decodeBlockRecord(id: String, record: String): PlanBlockState {
    val fields = Fields.decode(record, BLOCK_FIELD_COUNT)
    return PlanBlockState(
      id = id,
      planItemId = fields[0].required("task ID"),
      startAt = fields[1].requiredLong("start time"),
      endAt = fields[2].requiredLong("end time"),
      position = fields[3].requiredInt("position"),
      locked = fields[4].requiredBoolean("locked"),
      linkedEventId = fields[5],
      createdAt = fields[6].requiredLong("created time"),
      updatedAt = fields[7].requiredLong("updated time"),
    ).also(::validateBlock)
  }

  private fun decodeItemSchedule(
    ids: List<String>,
    records: Map<String, String>,
  ): PlanItemScheduleState {
    require(ids.size == 1) { "Schedule assignment must target one task" }
    val itemId = ids.single()
    val fields = Fields.decode(records.getValue(itemId), ITEM_SCHEDULE_FIELD_COUNT)
    return PlanItemScheduleState(
        planItemId = itemId,
        workScheduleId = fields[0],
        createdAt = fields[1].nullableLong("created time"),
        updatedAt = fields[2].nullableLong("updated time"),
      )
      .also(::validateItemSchedule)
  }

  private fun validateProgress(state: PlanProgressState) {
    require(state.progress in 0..100) { "Invalid progress" }
    require(state.progress == 100 || state.completedAt == null) {
      "Incomplete progress cannot retain a completion time"
    }
  }

  private fun validateItem(item: PlanItemState) {
    require(item.boardId.isNotBlank()) { "Board ID cannot be blank" }
    require(item.title.isNotBlank()) { "Task title cannot be blank" }
    require(item.effortMinutes == null || item.effortMinutes > 0) { "Invalid effort" }
    require(item.progress in 0..100) { "Invalid progress" }
    require(item.priority in PlanPriority.All) { "Invalid priority" }
    require(item.schedulingMode in PlanSchedulingMode.All) { "Invalid scheduling mode" }
    require(!item.isMilestone || item.effortMinutes == null) { "Milestone cannot have effort" }
    require(item.progress == 100 || item.completedAt == null) {
      "Incomplete task cannot retain a completion time"
    }
  }

  private fun validateBlockGroup(state: PlanBlockGroupState) {
    val ids = state.blocks.map(PlanBlockState::id) + state.absentIds
    require(ids.size >= 2) { "A block group journal needs at least two blocks" }
    require(ids.distinct().size == ids.size) { "Block group journal contains duplicate blocks" }
    state.absentIds.forEach { id -> require(id.isNotBlank()) { "A block ID is required" } }
    state.blocks.forEach(::validateBlock)
  }

  private fun validateItemGroup(state: PlanItemGroupState) {
    require(state.items.size >= 2) { "A task group journal needs at least two tasks" }
    require(state.items.map(PlanItemState::id).distinct().size == state.items.size) {
      "Task group journal contains duplicate tasks"
    }
    require(state.items.map(PlanItemState::boardId).distinct().size == 1) {
      "Task group journal must stay on one board"
    }
    state.items.forEach(::validateItem)
  }

  private fun validateBlock(block: PlanBlockState) {
    require(block.planItemId.isNotBlank()) { "Task ID cannot be blank" }
    require(block.endAt > block.startAt) { "Invalid block duration" }
    require(block.position >= 0) { "Invalid block position" }
  }

  private fun validateDependency(dependency: PlanDependencyState) {
    require(dependency.boardId.isNotBlank()) { "Board ID cannot be blank" }
    require(dependency.predecessorId.isNotBlank() && dependency.successorId.isNotBlank()) {
      "Dependency endpoints cannot be blank"
    }
    require(dependency.predecessorId != dependency.successorId) {
      "A task cannot depend on itself"
    }
    require(dependency.type in PlanDependencyType.All) { "Invalid dependency type" }
  }

  private fun validateItemSchedule(state: PlanItemScheduleState) {
    require(state.planItemId.isNotBlank()) { "Task ID cannot be blank" }
    if (state.workScheduleId == null) {
      require(state.createdAt == null && state.updatedAt == null) {
        "Inherited schedule state cannot retain row timestamps"
      }
    } else {
      require(state.workScheduleId.isNotBlank()) { "Working schedule ID cannot be blank" }
      require(state.createdAt != null && state.updatedAt != null) {
        "Assigned schedule state requires row timestamps"
      }
      require(state.updatedAt >= state.createdAt) { "Schedule assignment timestamps are invalid" }
    }
  }

  private fun PlanItemState.fields(): List<String?> =
    listOf(
      boardId,
      columnId,
      parentId,
      title,
      notes,
      rank.toString(),
      startConstraint?.toString(),
      dueAt?.toString(),
      effortMinutes?.toString(),
      progress.toString(),
      priority,
      owner,
      schedulingMode,
      locked.toString(),
      isMilestone.toString(),
      completedAt?.toString(),
      archivedAt?.toString(),
      createdAt.toString(),
      updatedAt.toString(),
    )

  private fun PlanBlockState.fields(): List<String?> =
    listOf(
      planItemId,
      startAt.toString(),
      endAt.toString(),
      position.toString(),
      locked.toString(),
      linkedEventId,
      createdAt.toString(),
      updatedAt.toString(),
    )

  private fun PlanDependencyState.fields(): List<String?> =
    listOf(
      boardId,
      predecessorId,
      successorId,
      type,
      lagMinutes.toString(),
      createdAt.toString(),
      updatedAt.toString(),
    )

  private fun PlanItemScheduleState.fields(): List<String?> =
    listOf(workScheduleId, createdAt?.toString(), updatedAt?.toString())

  private fun validateTargetIds(ids: List<String>) {
    require(ids.isNotEmpty()) { "A mutation needs a target" }
    require(ids.size <= MAX_TARGETS && ids.distinct().size == ids.size) {
      "Mutation targets are duplicated or too numerous"
    }
    ids.forEach { id ->
      require(id.isNotBlank() && id.length <= MAX_ID_LENGTH && id.none(Char::isISOControl)) {
        "Invalid mutation target ID"
      }
    }
  }

  private object Fields {
    fun encode(values: List<String?>): String =
      buildString {
        require(values.all { it == null || it.length <= MAX_FIELD_LENGTH }) {
          "Mutation field is too large"
        }
        append("v1|")
        append(values.size)
        append('|')
        values.forEach { value ->
          if (value == null) append("-1:")
          else {
            append(value.length)
            append(':')
            append(value)
          }
        }
        require(length <= MAX_RECORD_LENGTH) { "Mutation record is too large" }
      }

    fun decode(encoded: String, expectedCount: Int): List<String?> {
      require(encoded.length <= MAX_RECORD_LENGTH && encoded.startsWith("v1|")) {
        "Unsupported mutation record"
      }
      var cursor = 3
      val countEnd = encoded.indexOf('|', cursor)
      require(countEnd > cursor) { "Missing mutation field count" }
      val count = encoded.substring(cursor, countEnd).toIntOrNull()
      require(count == expectedCount) { "Wrong mutation field count" }
      cursor = countEnd + 1
      val result = ArrayList<String?>(expectedCount)
      repeat(expectedCount) {
        val lengthEnd = encoded.indexOf(':', cursor)
        require(lengthEnd > cursor) { "Missing mutation field length" }
        val length = encoded.substring(cursor, lengthEnd).toIntOrNull()
          ?: error("Invalid mutation field length")
        cursor = lengthEnd + 1
        if (length == -1) result += null
        else {
          require(length in 0..MAX_FIELD_LENGTH && cursor + length <= encoded.length) {
            "Invalid mutation field boundary"
          }
          result += encoded.substring(cursor, cursor + length)
          cursor += length
        }
      }
      require(cursor == encoded.length) { "Trailing mutation record content" }
      return result
    }
  }

  private fun String?.required(label: String): String =
    requireNotNull(this).also { require(it.isNotBlank()) { "$label cannot be blank" } }

  private fun String?.requiredLong(label: String): Long =
    required(label).toLongOrNull() ?: error("Invalid $label")

  private fun String?.nullableLong(label: String): Long? =
    this?.toLongOrNull() ?: this?.let { error("Invalid $label") }

  private fun String?.requiredInt(label: String): Int =
    required(label).toIntOrNull() ?: error("Invalid $label")

  private fun String?.nullableInt(label: String): Int? =
    this?.toIntOrNull() ?: this?.let { error("Invalid $label") }

  private fun String?.requiredBoolean(label: String): Boolean =
    when (required(label)) {
      "true" -> true
      "false" -> false
      else -> error("Invalid $label")
    }

  const val SCHEMA_VERSION = 1

  /**
   * The record that means "no such block in this state".
   *
   * It is a well-formed one-field record, so a truncated or corrupt block record still fails to
   * decode rather than being mistaken for a deliberate absence — the fail-closed rule the journal
   * depends on is unchanged.
   */
  private val ABSENT_RECORD: String = Fields.encode(listOf("absent"))
  private const val ITEM_FIELD_COUNT = 19
  private const val BLOCK_FIELD_COUNT = 8
  private const val DEPENDENCY_FIELD_COUNT = 7
  private const val ITEM_SCHEDULE_FIELD_COUNT = 3
  private const val MAX_TARGETS = 2_000
  private const val MAX_ID_LENGTH = 256
  private const val MAX_FIELD_LENGTH = 64_000
  private const val MAX_RECORD_LENGTH = 128_000
}

  fun PlanItem.toMutationState(): PlanItemState =
  PlanItemState(
    id = id,
    boardId = boardId,
    columnId = columnId,
    parentId = parentId,
    title = title,
    notes = notes,
    rank = rank,
    startConstraint = startConstraint,
    dueAt = dueAt,
    effortMinutes = effortMinutes,
    progress = progress,
    priority = priority,
    owner = owner,
    schedulingMode = schedulingMode,
    locked = locked,
    isMilestone = isMilestone,
    completedAt = completedAt,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

  fun PlanBlock.toMutationState(): PlanBlockState =
  PlanBlockState(
    id = id,
    planItemId = planItemId,
    startAt = startAt,
    endAt = endAt,
    position = position,
    locked = locked,
    linkedEventId = linkedEventId,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

  fun PlanDependency.toMutationState(): PlanDependencyState =
  PlanDependencyState(
    id = id,
    boardId = boardId,
    predecessorId = predecessorId,
    successorId = successorId,
    type = type,
    lagMinutes = lagMinutes,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

  fun PlanItemSchedule.toMutationState(): PlanItemScheduleState =
  PlanItemScheduleState(
    planItemId = planItemId,
    workScheduleId = workScheduleId,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
