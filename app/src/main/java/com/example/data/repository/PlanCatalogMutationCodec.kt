package com.example.data.repository

import com.example.data.model.BriefingEvent
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem

/**
 * Exact, bounded database slots touched by one legacy-catalog command.
 *
 * A null value is deliberate: it records that the row or setting did not exist. Keeping the same
 * slot keys on both sides lets Undo remove rows created by the command without treating an absent
 * row as an absent snapshot. The command scope is the journal target; individual database IDs stay
 * inside this aggregate because a board rename can atomically affect several entity kinds.
 */
internal data class PlanCatalogState(
  val scopeId: String,
  val boards: Map<String, PlanBoard?> = emptyMap(),
  val columns: Map<String, PlanColumn?> = emptyMap(),
  val items: Map<String, PlanItem?> = emptyMap(),
  val events: Map<String, BriefingEvent?> = emptyMap(),
  val settings: Map<String, String?> = emptyMap(),
) : PlanMutationState {
  override val targetIds: List<String> = listOf(scopeId)
}

/** Strict aggregate codec for catalog mutations; arbitrary Unicode round-trips by character count. */
internal object PlanCatalogMutationCodec {
  fun encode(state: PlanCatalogState): EncodedPlanMutationState {
    validate(state)
    val records = linkedMapOf<String, String>()
    state.boards.forEach { (id, value) ->
      records[key(BOARD, id)] =
        Fields.encode(
          listOf(
            present(value),
            value?.name,
            value?.nameKey,
            value?.rank?.toString(),
            value?.isDefault?.toString(),
            value?.archivedAt?.toString(),
            value?.createdAt?.toString(),
            value?.updatedAt?.toString(),
          )
        )
    }
    state.columns.forEach { (id, value) ->
      records[key(COLUMN, id)] =
        Fields.encode(
          listOf(
            present(value),
            value?.boardId,
            value?.name,
            value?.nameKey,
            value?.rank?.toString(),
            value?.archivedAt?.toString(),
            value?.createdAt?.toString(),
            value?.updatedAt?.toString(),
          )
        )
    }
    state.items.forEach { (id, value) ->
      records[key(ITEM, id)] =
        Fields.encode(
          listOf(
            present(value),
            value?.boardId,
            value?.columnId,
            value?.parentId,
            value?.title,
            value?.notes,
            value?.rank?.toString(),
            value?.startConstraint?.toString(),
            value?.dueAt?.toString(),
            value?.effortMinutes?.toString(),
            value?.progress?.toString(),
            value?.priority,
            value?.owner,
            value?.schedulingMode,
            value?.locked?.toString(),
            value?.isMilestone?.toString(),
            value?.completedAt?.toString(),
            value?.archivedAt?.toString(),
            value?.createdAt?.toString(),
            value?.updatedAt?.toString(),
          )
        )
    }
    state.events.forEach { (id, value) ->
      records[key(EVENT, id)] =
        Fields.encode(
          listOf(
            present(value),
            value?.title,
            value?.startTime?.toString(),
            value?.endTime?.toString(),
            value?.source,
            value?.description,
            value?.isDeadline?.toString(),
            value?.isUrgent?.toString(),
            value?.isAllDay?.toString(),
            value?.location,
            value?.kanbanStatus,
            value?.kanbanBoard,
            value?.userEdited?.toString(),
          )
        )
    }
    state.settings.forEach { (settingKey, value) ->
      records[key(SETTING, settingKey)] = Fields.encode(listOf((value != null).toString(), value))
    }
    return EncodedPlanMutationState(
      targetIdsJson = SavedViewCodec.encodeStringArray(state.targetIds),
      stateJson = SavedViewCodec.encodeStringObject(records),
    )
  }

  fun decode(scopeId: String, stateJson: String): PlanCatalogState? =
    runCatching {
        requireValidKey(scopeId, "catalog scope")
        val boards = linkedMapOf<String, PlanBoard?>()
        val columns = linkedMapOf<String, PlanColumn?>()
        val items = linkedMapOf<String, PlanItem?>()
        val events = linkedMapOf<String, BriefingEvent?>()
        val settings = linkedMapOf<String, String?>()
        val records = SavedViewCodec.decodeStringObject(stateJson)
        require(records.isNotEmpty() && records.size <= MAX_COMPONENTS) {
          "Catalog journal is empty or too large"
        }
        records.forEach { (recordKey, record) ->
          require(recordKey.length >= 3 && recordKey[1] == '|') { "Invalid catalog record key" }
          val id = recordKey.substring(2)
          requireValidKey(id, "catalog record ID")
          when (recordKey[0]) {
            BOARD -> boards[id] = decodeBoard(id, record)
            COLUMN -> columns[id] = decodeColumn(id, record)
            ITEM -> items[id] = decodeItem(id, record)
            EVENT -> events[id] = decodeEvent(id, record)
            SETTING -> settings[id] = decodeSetting(record)
            else -> error("Unknown catalog record kind")
          }
        }
        PlanCatalogState(scopeId, boards, columns, items, events, settings).also(::validate)
      }
      .getOrNull()

  private fun decodeBoard(id: String, record: String): PlanBoard? {
    val fields = Fields.decode(record, BOARD_FIELDS)
    if (!fields.present()) return fields.requireAbsent()
    return PlanBoard(
      id = id,
      name = fields[1].required("board name"),
      nameKey = fields[2].required("board name key"),
      rank = fields[3].requiredLong("board rank"),
      isDefault = fields[4].requiredBoolean("default board"),
      archivedAt = fields[5].nullableLong("board archive time"),
      createdAt = fields[6].requiredLong("board creation time"),
      updatedAt = fields[7].requiredLong("board update time"),
    )
  }

  private fun decodeColumn(id: String, record: String): PlanColumn? {
    val fields = Fields.decode(record, COLUMN_FIELDS)
    if (!fields.present()) return fields.requireAbsent()
    return PlanColumn(
      id = id,
      boardId = fields[1].required("column board ID"),
      name = fields[2].required("column name"),
      nameKey = fields[3].required("column name key"),
      rank = fields[4].requiredLong("column rank"),
      archivedAt = fields[5].nullableLong("column archive time"),
      createdAt = fields[6].requiredLong("column creation time"),
      updatedAt = fields[7].requiredLong("column update time"),
    )
  }

  private fun decodeItem(id: String, record: String): PlanItem? {
    val fields = Fields.decode(record, ITEM_FIELDS)
    if (!fields.present()) return fields.requireAbsent()
    return PlanItem(
      id = id,
      boardId = fields[1].required("task board ID"),
      columnId = fields[2],
      parentId = fields[3],
      title = fields[4].required("task title"),
      notes = fields[5],
      rank = fields[6].requiredLong("task rank"),
      startConstraint = fields[7].nullableLong("task start constraint"),
      dueAt = fields[8].nullableLong("task due time"),
      effortMinutes = fields[9].nullableInt("task effort"),
      progress = fields[10].requiredInt("task progress"),
      priority = fields[11].required("task priority"),
      owner = fields[12],
      schedulingMode = fields[13].required("task scheduling mode"),
      locked = fields[14].requiredBoolean("task lock"),
      isMilestone = fields[15].requiredBoolean("task milestone"),
      completedAt = fields[16].nullableLong("task completion time"),
      archivedAt = fields[17].nullableLong("task archive time"),
      createdAt = fields[18].requiredLong("task creation time"),
      updatedAt = fields[19].requiredLong("task update time"),
    )
  }

  private fun decodeEvent(id: String, record: String): BriefingEvent? {
    val fields = Fields.decode(record, EVENT_FIELDS)
    if (!fields.present()) return fields.requireAbsent()
    return BriefingEvent(
      id = id,
      title = fields[1].required("event title"),
      startTime = fields[2].requiredLong("event start"),
      endTime = fields[3].requiredLong("event end"),
      source = fields[4].required("event source"),
      description = fields[5],
      isDeadline = fields[6].requiredBoolean("event deadline"),
      isUrgent = fields[7].requiredBoolean("event urgency"),
      isAllDay = fields[8].requiredBoolean("event all-day state"),
      location = fields[9],
      kanbanStatus = fields[10].required("event column"),
      kanbanBoard = fields[11].required("event board"),
      userEdited = fields[12].requiredBoolean("event edit state"),
    )
  }

  private fun decodeSetting(record: String): String? {
    val fields = Fields.decode(record, SETTING_FIELDS)
    return when (fields[0].requiredBoolean("setting presence")) {
      true -> requireNotNull(fields[1]) { "Present setting needs a value" }
      false -> fields.requireAbsent()
    }
  }

  private fun validate(state: PlanCatalogState) {
    requireValidKey(state.scopeId, "catalog scope")
    val total =
      state.boards.size + state.columns.size + state.items.size + state.events.size +
        state.settings.size
    require(total in 1..MAX_COMPONENTS) { "Catalog journal is empty or too large" }
    state.boards.forEach { (id, row) ->
      requireValidKey(id, "board ID")
      require(row == null || row.id == id) { "Board journal key does not match its row" }
    }
    state.columns.forEach { (id, row) ->
      requireValidKey(id, "column ID")
      require(row == null || row.id == id) { "Column journal key does not match its row" }
    }
    state.items.forEach { (id, row) ->
      requireValidKey(id, "task ID")
      require(row == null || row.id == id) { "Task journal key does not match its row" }
    }
    state.events.forEach { (id, row) ->
      requireValidKey(id, "event ID")
      require(row == null || row.id == id) { "Event journal key does not match its row" }
    }
    state.settings.keys.forEach { requireValidKey(it, "setting key") }
  }

  private fun present(value: Any?): String = (value != null).toString()

  private fun key(kind: Char, id: String): String = "$kind|$id"

  private fun requireValidKey(value: String, label: String) {
    require(value.isNotEmpty() && value.length <= MAX_KEY_LENGTH && value.none(Char::isISOControl)) {
      "Invalid $label"
    }
  }

  private fun List<String?>.present(): Boolean = this[0].requiredBoolean("row presence")

  private fun <T> List<String?>.requireAbsent(): T? {
    require(drop(1).all { it == null }) { "Absent catalog row contains data" }
    return null
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
          require(value == null || value.length <= MAX_FIELD_LENGTH) { "Catalog field is too large" }
          if (value == null) append("-1:")
          else {
            append(value.length)
            append(':')
            append(value)
          }
        }
        require(length <= MAX_RECORD_LENGTH) { "Catalog record is too large" }
      }

    fun decode(encoded: String, expectedCount: Int): List<String?> {
      require(encoded.length <= MAX_RECORD_LENGTH && encoded.startsWith("v1|")) {
        "Unsupported catalog record"
      }
      var cursor = 3
      val countEnd = encoded.indexOf('|', cursor)
      require(countEnd > cursor && encoded.substring(cursor, countEnd).toIntOrNull() == expectedCount) {
        "Wrong catalog field count"
      }
      cursor = countEnd + 1
      val result = ArrayList<String?>(expectedCount)
      repeat(expectedCount) {
        val lengthEnd = encoded.indexOf(':', cursor)
        require(lengthEnd > cursor) { "Missing catalog field length" }
        val length = encoded.substring(cursor, lengthEnd).toIntOrNull()
          ?: error("Invalid catalog field length")
        cursor = lengthEnd + 1
        if (length == -1) result += null
        else {
          require(length in 0..MAX_FIELD_LENGTH && cursor + length <= encoded.length) {
            "Invalid catalog field boundary"
          }
          result += encoded.substring(cursor, cursor + length)
          cursor += length
        }
      }
      require(cursor == encoded.length) { "Trailing catalog record content" }
      return result
    }
  }

  private const val BOARD = 'b'
  private const val COLUMN = 'c'
  private const val ITEM = 'i'
  private const val EVENT = 'e'
  private const val SETTING = 's'
  private const val BOARD_FIELDS = 8
  private const val COLUMN_FIELDS = 8
  private const val ITEM_FIELDS = 20
  private const val EVENT_FIELDS = 13
  private const val SETTING_FIELDS = 2
  private const val MAX_COMPONENTS = 5_000
  private const val MAX_KEY_LENGTH = 1_024
  private const val MAX_FIELD_LENGTH = 512_000
  private const val MAX_RECORD_LENGTH = 1_048_576
}
