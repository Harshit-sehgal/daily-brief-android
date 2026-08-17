package com.example.data.repository

import com.example.data.database.nameKey
import com.example.data.database.stableId
import com.example.data.model.PlanItemSchedule
import com.example.data.model.WorkSchedule
import com.example.data.model.WorkScheduleWindow

/**
 * Exact Room state touched by one working-schedule lifecycle command.
 *
 * [assignmentScopes] deliberately distinguishes "not observed by this command" from an observed
 * empty assignment set. Creation and archival track an empty set so a later task assignment makes
 * their Undo stale instead of allowing a foreign-key cascade or silently changing that assignment.
 */
data class WorkingScheduleMutationState(
  val scopeId: String,
  val schedules: Map<String, WorkSchedule?>,
  val windows: Map<String, List<WorkScheduleWindow>>,
  val assignmentScopes: Map<String, List<PlanItemSchedule>> = emptyMap(),
) : PlanMutationState {
  override val targetIds: List<String> = listOf(scopeId)
}

/** Strict, bounded aggregate codec for durable working-schedule Undo. */
object WorkingScheduleMutationCodec {
  fun encode(state: WorkingScheduleMutationState): EncodedPlanMutationState {
    validate(state)
    val records = linkedMapOf<String, String>()
    state.schedules.toList().sortedBy { it.first }.forEach { (id, schedule) ->
      records[key(SCHEDULE, id)] =
        Fields.encode(
          listOf(
            (schedule != null).toString(),
            (id in state.assignmentScopes).toString(),
            schedule?.name,
            schedule?.nameKey,
            schedule?.timeZoneId,
            schedule?.isDefault?.toString(),
            schedule?.minimumChunkMinutes?.toString(),
            schedule?.maximumChunkMinutes?.toString(),
            schedule?.bufferMinutes?.toString(),
            schedule?.rank?.toString(),
            schedule?.archivedAt?.toString(),
            schedule?.createdAt?.toString(),
            schedule?.updatedAt?.toString(),
          )
        )
    }
    state.windows.values.flatten().sortedBy(WorkScheduleWindow::id).forEach { window ->
      records[key(WINDOW, window.id)] =
        Fields.encode(
          listOf(
            window.scheduleId,
            window.kind,
            window.dayOfWeek?.toString(),
            window.localDate,
            window.startMinute.toString(),
            window.endMinute.toString(),
            window.isClosed.toString(),
            window.rank.toString(),
            window.createdAt.toString(),
            window.updatedAt.toString(),
          )
        )
    }
    state.assignmentScopes.values.flatten().sortedBy(PlanItemSchedule::planItemId).forEach {
      assignment ->
      records[key(ASSIGNMENT, assignment.planItemId)] =
        Fields.encode(
          listOf(
            assignment.workScheduleId,
            assignment.createdAt.toString(),
            assignment.updatedAt.toString(),
          )
        )
    }
    return EncodedPlanMutationState(
      targetIdsJson = SavedViewCodec.encodeStringArray(state.targetIds),
      stateJson = SavedViewCodec.encodeStringObject(records),
    )
  }

  fun decode(scopeId: String, stateJson: String): WorkingScheduleMutationState? =
    runCatching {
        requireValidKey(scopeId, "working-schedule scope")
        val records = SavedViewCodec.decodeStringObject(stateJson)
        require(records.isNotEmpty() && records.size <= MAX_COMPONENTS) {
          "Working-schedule journal is empty or too large"
        }
        val scheduleRecords = records.filterKeys { it.startsWith("$SCHEDULE|") }
        val windowRecords = records.filterKeys { it.startsWith("$WINDOW|") }
        val assignmentRecords = records.filterKeys { it.startsWith("$ASSIGNMENT|") }
        require(scheduleRecords.size + windowRecords.size + assignmentRecords.size == records.size) {
          "Unknown working-schedule journal record"
        }
        require(scheduleRecords.isNotEmpty()) { "Working-schedule journal needs a schedule" }

        val schedules = linkedMapOf<String, WorkSchedule?>()
        val windows = linkedMapOf<String, MutableList<WorkScheduleWindow>>()
        val assignments = linkedMapOf<String, MutableList<PlanItemSchedule>>()
        scheduleRecords.toList().sortedBy { it.first }.forEach { (recordKey, record) ->
          val id = recordId(recordKey)
          require(id !in schedules) { "Duplicate working schedule" }
          val decoded = decodeSchedule(id, record)
          schedules[id] = decoded.schedule
          windows[id] = mutableListOf()
          if (decoded.tracksAssignments) assignments[id] = mutableListOf()
        }
        windowRecords.toList().sortedBy { it.first }.forEach { (recordKey, record) ->
          val window = decodeWindow(recordId(recordKey), record)
          requireNotNull(windows[window.scheduleId]) {
            "Working window belongs to an untracked schedule"
          }.add(window)
        }
        assignmentRecords.toList().sortedBy { it.first }.forEach { (recordKey, record) ->
          val assignment = decodeAssignment(recordId(recordKey), record)
          requireNotNull(assignments[assignment.workScheduleId]) {
            "Assignment belongs to an untracked schedule scope"
          }.add(assignment)
        }
        WorkingScheduleMutationState(
            scopeId = scopeId,
            schedules = schedules,
            windows = windows.mapValues { (_, rows) -> rows.sortedBy(WorkScheduleWindow::id) },
            assignmentScopes =
              assignments.mapValues { (_, rows) -> rows.sortedBy(PlanItemSchedule::planItemId) },
          )
          .also(::validate)
      }
      .getOrNull()

  private data class DecodedSchedule(
    val schedule: WorkSchedule?,
    val tracksAssignments: Boolean,
  )

  private fun decodeSchedule(id: String, record: String): DecodedSchedule {
    val fields = Fields.decode(record, SCHEDULE_FIELDS)
    val present = fields[0].requiredBoolean("schedule presence")
    val tracksAssignments = fields[1].requiredBoolean("assignment tracking")
    if (!present) {
      require(fields.drop(2).all { it == null }) { "Absent schedule contains row data" }
      return DecodedSchedule(null, tracksAssignments)
    }
    return DecodedSchedule(
      schedule =
        WorkSchedule(
          id = id,
          name = fields[2].required("schedule name"),
          nameKey = fields[3].required("schedule name key"),
          timeZoneId = fields[4].required("schedule time zone"),
          isDefault = fields[5].requiredBoolean("default schedule"),
          minimumChunkMinutes = fields[6].requiredInt("minimum chunk"),
          maximumChunkMinutes = fields[7].requiredInt("maximum chunk"),
          bufferMinutes = fields[8].requiredInt("buffer"),
          rank = fields[9].requiredLong("schedule rank"),
          archivedAt = fields[10].nullableLong("schedule archive time"),
          createdAt = fields[11].requiredLong("schedule creation time"),
          updatedAt = fields[12].requiredLong("schedule update time"),
        ),
      tracksAssignments = tracksAssignments,
    )
  }

  private fun decodeWindow(id: String, record: String): WorkScheduleWindow {
    val fields = Fields.decode(record, WINDOW_FIELDS)
    return WorkScheduleWindow(
      id = id,
      scheduleId = fields[0].required("window schedule ID"),
      kind = fields[1].required("window kind"),
      dayOfWeek = fields[2].nullableInt("window weekday"),
      localDate = fields[3],
      startMinute = fields[4].requiredInt("window start"),
      endMinute = fields[5].requiredInt("window end"),
      isClosed = fields[6].requiredBoolean("closed window"),
      rank = fields[7].requiredLong("window rank"),
      createdAt = fields[8].requiredLong("window creation time"),
      updatedAt = fields[9].requiredLong("window update time"),
    )
  }

  private fun decodeAssignment(id: String, record: String): PlanItemSchedule {
    val fields = Fields.decode(record, ASSIGNMENT_FIELDS)
    return PlanItemSchedule(
      planItemId = id,
      workScheduleId = fields[0].required("assigned schedule ID"),
      createdAt = fields[1].requiredLong("assignment creation time"),
      updatedAt = fields[2].requiredLong("assignment update time"),
    )
  }

  private fun validate(state: WorkingScheduleMutationState) {
    requireValidKey(state.scopeId, "working-schedule scope")
    require(state.schedules.isNotEmpty() && state.schedules.size <= MAX_SCHEDULES) {
      "Working-schedule journal has an invalid schedule count"
    }
    require(state.windows.keys == state.schedules.keys) {
      "Every tracked schedule needs an exact window scope"
    }
    require(state.assignmentScopes.keys.all { it in state.schedules }) {
      "Assignment scope references an untracked schedule"
    }
    val windowIds = mutableSetOf<String>()
    val assignmentIds = mutableSetOf<String>()
    state.schedules.forEach { (id, schedule) ->
      requireValidKey(id, "working schedule ID")
      require(schedule == null || schedule.id == id) {
        "Working-schedule journal key does not match its row"
      }
      val scheduleWindows = state.windows.getValue(id)
      require(scheduleWindows.size <= MAX_WINDOWS_PER_SCHEDULE) {
        "Working schedule has too many windows"
      }
      scheduleWindows.forEach { window ->
        requireValidKey(window.id, "working-window ID")
        require(window.scheduleId == id && windowIds.add(window.id)) {
          "Working-window identity is invalid"
        }
      }
      val scheduleAssignments = state.assignmentScopes[id].orEmpty()
      require(scheduleAssignments.size <= MAX_ASSIGNMENTS_PER_SCHEDULE) {
        "Working schedule has too many assignments"
      }
      scheduleAssignments.forEach { assignment ->
        requireValidKey(assignment.planItemId, "assigned task ID")
        require(assignment.workScheduleId == id && assignmentIds.add(assignment.planItemId)) {
          "Working-schedule assignment identity is invalid"
        }
        require(assignment.updatedAt >= assignment.createdAt) {
          "Working-schedule assignment timestamps are invalid"
        }
      }
      if (schedule == null) {
        require(scheduleWindows.isEmpty() && scheduleAssignments.isEmpty()) {
          "Absent schedule retains dependent rows"
        }
      } else {
        require(schedule.name.isNotBlank() && schedule.name.length <= MAX_NAME_LENGTH) {
          "Invalid working-schedule name"
        }
        require(schedule.name.none(Char::isISOControl)) {
          "Working-schedule name contains a control character"
        }
        require(nameKey(schedule.name) == schedule.nameKey) {
          "Working-schedule name key is not canonical"
        }
        require(schedule.archivedAt == null || !schedule.isDefault) {
          "An archived working schedule cannot remain default"
        }
        require(schedule.updatedAt >= schedule.createdAt) {
          "Working-schedule timestamps are invalid"
        }
        WorkingCalendarMapper.fromEntities(schedule, scheduleWindows)
      }
    }
    require(
      state.schedules.size + windowIds.size + assignmentIds.size <= MAX_COMPONENTS
    ) {
      "Working-schedule journal is too large"
    }
  }

  private fun key(kind: Char, id: String): String = "$kind|$id"

  private fun recordId(key: String): String {
    require(key.length >= 3 && key[1] == '|') { "Invalid working-schedule record key" }
    return key.substring(2).also { requireValidKey(it, "working-schedule record ID") }
  }

  private fun requireValidKey(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_KEY_LENGTH && value.none(Char::isISOControl)) {
      "Invalid $label"
    }
  }

  private fun String?.required(label: String): String =
    requireNotNull(this).also { require(it.isNotEmpty()) { "$label cannot be empty" } }

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

  private object Fields {
    fun encode(values: List<String?>): String =
      buildString {
        append("v1|")
        append(values.size)
        append('|')
        values.forEach { value ->
          require(value == null || value.length <= MAX_FIELD_LENGTH) {
            "Working-schedule journal field is too large"
          }
          if (value == null) append("-1:")
          else {
            append(value.length)
            append(':')
            append(value)
          }
        }
        require(length <= MAX_RECORD_LENGTH) { "Working-schedule journal record is too large" }
      }

    fun decode(encoded: String, expectedCount: Int): List<String?> {
      require(encoded.length <= MAX_RECORD_LENGTH && encoded.startsWith("v1|")) {
        "Unsupported working-schedule journal record"
      }
      var cursor = 3
      val countEnd = encoded.indexOf('|', cursor)
      require(countEnd > cursor && encoded.substring(cursor, countEnd).toIntOrNull() == expectedCount) {
        "Wrong working-schedule journal field count"
      }
      cursor = countEnd + 1
      val result = ArrayList<String?>(expectedCount)
      repeat(expectedCount) {
        val lengthEnd = encoded.indexOf(':', cursor)
        require(lengthEnd > cursor) { "Missing working-schedule journal field length" }
        val length = encoded.substring(cursor, lengthEnd).toIntOrNull()
          ?: error("Invalid working-schedule journal field length")
        cursor = lengthEnd + 1
        if (length == -1) result += null
        else {
          require(length in 0..MAX_FIELD_LENGTH && cursor + length <= encoded.length) {
            "Invalid working-schedule journal field boundary"
          }
          result += encoded.substring(cursor, cursor + length)
          cursor += length
        }
      }
      require(cursor == encoded.length) { "Trailing working-schedule journal content" }
      return result
    }
  }

  private const val SCHEDULE = 's'
  private const val WINDOW = 'w'
  private const val ASSIGNMENT = 'a'
  private const val SCHEDULE_FIELDS = 13
  private const val WINDOW_FIELDS = 10
  private const val ASSIGNMENT_FIELDS = 3
  private const val MAX_SCHEDULES = 16
  private const val MAX_WINDOWS_PER_SCHEDULE = 1_000
  private const val MAX_ASSIGNMENTS_PER_SCHEDULE = 5_000
  private const val MAX_COMPONENTS = 10_000
  private const val MAX_NAME_LENGTH = 80
  private const val MAX_KEY_LENGTH = 1_024
  private const val MAX_FIELD_LENGTH = 512_000
  private const val MAX_RECORD_LENGTH = 1_048_576
}
