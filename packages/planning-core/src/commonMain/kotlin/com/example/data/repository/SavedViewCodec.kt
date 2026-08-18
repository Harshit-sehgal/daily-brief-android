package com.example.data.repository

import com.example.data.model.PlanSurface
import com.example.data.model.SavedView

/**
 * Typed, bounded state for a named Plan view.
 *
 * Room keeps the independently queryable surface/range fields while the collection fields use
 * small JSON values. This codec is deliberately dependency-free and strict: malformed or future
 * data is rejected as a whole instead of being half-applied to the current screen.
 */
data class SavedPlanViewState(
  val surface: String,
  val filters: Map<String, String> = emptyMap(),
  val grouping: String = GROUP_NONE,
  val sort: List<String> = emptyList(),
  val columns: List<String> = emptyList(),
  val rangeDays: Int? = null,
  val zoom: String? = null,
  val collapsedItemIds: Set<String> = emptySet(),
) {
  companion object {
    const val GROUP_NONE = "none"

    /** The one filter the Outline offered at launch; stored as "true"/"false". */
    const val FILTER_HIDE_COMPLETED = "hideCompleted"

    /** A free-text outline filter; stored as the query string. */
    const val FILTER_TEXT = "text"
    val AllowedGroupings = setOf(GROUP_NONE, "status", "priority", "owner")
    val AllowedZooms = setOf("compact", "comfortable", "expanded")
    val AllowedRanges = setOf(7, 30, 90)
  }
}

data class EncodedSavedPlanView(
  val filtersJson: String,
  val grouping: String,
  val sortJson: String,
  val columnsJson: String,
  val rangeDays: Int?,
  val zoom: String?,
  val collapsedIdsJson: String,
)

object SavedViewCodec {
  fun encode(state: SavedPlanViewState): EncodedSavedPlanView {
    validate(state)
    return EncodedSavedPlanView(
      filtersJson = encodeStringObject(state.filters),
      grouping = state.grouping,
      sortJson = encodeStringArray(state.sort),
      columnsJson = encodeStringArray(state.columns),
      rangeDays = state.rangeDays,
      zoom = state.zoom,
      collapsedIdsJson = encodeStringArray(state.collapsedItemIds.sorted()),
    )
  }

  /** Returns null for malformed, unsupported, or unreasonably large persisted state. */
  fun decode(view: SavedView): SavedPlanViewState? =
    runCatching {
        SavedPlanViewState(
            surface = view.surface,
            filters = decodeStringObject(view.filtersJson),
            grouping = view.grouping,
            sort = decodeStringArray(view.sortJson),
            columns = decodeStringArray(view.columnsJson),
            rangeDays = view.rangeDays,
            zoom = view.zoom,
            collapsedItemIds = decodeStringArray(view.collapsedIdsJson).toSet(),
          )
          .also(::validate)
      }
      .getOrNull()

  private fun validate(state: SavedPlanViewState) {
    require(state.surface in setOf(PlanSurface.OUTLINE, PlanSurface.BOARD, PlanSurface.GANTT)) {
      "Unknown Plan surface"
    }
    require(state.grouping in SavedPlanViewState.AllowedGroupings) { "Unknown view grouping" }
    require(state.rangeDays == null || state.rangeDays in SavedPlanViewState.AllowedRanges) {
      "Unsupported Gantt range"
    }
    require(state.zoom == null || state.zoom in SavedPlanViewState.AllowedZooms) {
      "Unsupported view zoom"
    }
    require(state.filters.size <= MAX_FILTERS) { "Too many saved filters" }
    require(state.sort.size <= MAX_SORTS) { "Too many saved sorts" }
    require(state.columns.size <= MAX_COLUMNS) { "Too many saved columns" }
    require(state.collapsedItemIds.size <= MAX_COLLAPSED_IDS) { "Too many collapsed tasks" }
    state.filters.forEach { (key, value) ->
      requireToken(key, "filter name", MAX_KEY_LENGTH)
      requireToken(value, "filter value", MAX_VALUE_LENGTH)
    }
    state.sort.forEach { requireToken(it, "sort", MAX_KEY_LENGTH) }
    state.columns.forEach { requireToken(it, "column", MAX_ID_LENGTH) }
    state.collapsedItemIds.forEach { requireToken(it, "task id", MAX_ID_LENGTH) }
  }

  private fun requireToken(value: String, label: String, maximumLength: Int) {
    require(value.isNotBlank()) { "$label cannot be blank" }
    require(value.length <= maximumLength) { "$label is too long" }
    require(value.none(Char::isISOControl)) { "$label contains a control character" }
  }

  /** Shared by the mutation codec across the repository layer. */
  fun encodeStringArray(values: Collection<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { quote(it) }

  fun encodeStringObject(values: Map<String, String>): String =
    values.toList().sortedBy { it.first }.joinToString(prefix = "{", postfix = "}") { (key, value) ->
      "${quote(key)}:${quote(value)}"
    }

  fun decodeStringArray(value: String): List<String> = JsonStrings(value).readArray()

  fun decodeStringObject(value: String): Map<String, String> = JsonStrings(value).readObject()

  private fun quote(value: String): String =
    buildString(value.length + 2) {
      append('"')
      value.forEach { character ->
        when (character) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          '\b' -> append("\\b")
          '\u000c' -> append("\\f")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else ->
            if (character.code < 0x20) append("\\u" + character.code.toString(16).padStart(4, '0'))
            else append(character)
        }
      }
      append('"')
    }

  /** Strict parser for only the JSON shapes this schema owns: string arrays and maps. */
  private class JsonStrings(private val input: String) {
    private var cursor = 0

    fun readArray(): List<String> {
      require(input.length <= MAX_JSON_LENGTH) { "Saved JSON is too large" }
      skipWhitespace()
      expect('[')
      skipWhitespace()
      val values = mutableListOf<String>()
      if (peek() != ']') {
        while (true) {
          values += readString()
          skipWhitespace()
          if (peek() != ',') break
          cursor++
          skipWhitespace()
        }
      }
      expect(']')
      finish()
      return values
    }

    fun readObject(): Map<String, String> {
      require(input.length <= MAX_JSON_LENGTH) { "Saved JSON is too large" }
      skipWhitespace()
      expect('{')
      skipWhitespace()
      val values = linkedMapOf<String, String>()
      if (peek() != '}') {
        while (true) {
          val key = readString()
          require(key !in values) { "Duplicate JSON key" }
          skipWhitespace()
          expect(':')
          skipWhitespace()
          values[key] = readString()
          skipWhitespace()
          if (peek() != ',') break
          cursor++
          skipWhitespace()
        }
      }
      expect('}')
      finish()
      return values
    }

    private fun readString(): String {
      expect('"')
      val value = StringBuilder()
      while (cursor < input.length) {
        val character = input[cursor++]
        when {
          character == '"' -> return value.toString()
          character == '\\' -> value.append(readEscape())
          character.code < 0x20 -> error("Unescaped JSON control character")
          else -> value.append(character)
        }
      }
      error("Unterminated JSON string")
    }

    private fun readEscape(): Char {
      require(cursor < input.length) { "Unterminated JSON escape" }
      return when (val escape = input[cursor++]) {
        '"', '\\', '/' -> escape
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
          require(cursor + 4 <= input.length) { "Short Unicode escape" }
          val digits = input.substring(cursor, cursor + 4)
          require(digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Invalid Unicode escape"
          }
          cursor += 4
          digits.toInt(16).toChar()
        }
        else -> error("Invalid JSON escape")
      }
    }

    private fun finish() {
      skipWhitespace()
      require(cursor == input.length) { "Trailing JSON content" }
    }

    private fun expect(character: Char) {
      require(peek() == character) { "Expected $character" }
      cursor++
    }

    private fun peek(): Char? = input.getOrNull(cursor)

    private fun skipWhitespace() {
      while (peek() in setOf(' ', '\t', '\n', '\r')) cursor++
    }
  }

  private const val MAX_FILTERS = 32
  private const val MAX_SORTS = 8
  private const val MAX_COLUMNS = 64
  private const val MAX_COLLAPSED_IDS = 2_000
  private const val MAX_KEY_LENGTH = 64
  private const val MAX_VALUE_LENGTH = 256
  private const val MAX_ID_LENGTH = 256
  // Catalog Undo uses the same strict string-object parser for a bounded aggregate of changed
  // Room rows. Saved-view collections remain independently capped above, so accepting a larger
  // encoded object here does not broaden their semantic limits.
  private const val MAX_JSON_LENGTH = 8_388_608
}
