package com.example.data.prefs

/** Every key stored in the settings table, in one place so nothing drifts. */
object SettingKeys {
  const val NOTION_TOKEN = "notion_token"
  const val NOTION_DB_ID = "notion_db_id"
  const val NOTION_ENABLED = "notion_enabled"

  const val DEVICE_CALENDAR_ENABLED = "device_cal_enabled"
  const val CALENDAR_WRITE_BACK = "calendar_write_back"

  const val BRIEF_HOUR = "brief_hour"
  const val BRIEF_MINUTE = "brief_minute"
  const val DAILY_BRIEF_ENABLED = "daily_brief_enabled"
  const val REMINDERS_ENABLED = "alert_30m_enabled"
  const val REMINDER_LEAD_MINUTES = "reminder_lead_minutes"
  const val NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

  const val PROFILE_NAME = "profile_name"
  const val THEME_ACCENT = "theme_accent"
  const val THEME_MODE = "theme_mode"

  const val ACTIVE_BOARD = "active_kanban_board"
  const val BOARDS = "kanban_boards"
  /** Stable planning-board identity; legacy event-board names remain untouched during cutover. */
  const val ACTIVE_PLAN_BOARD_ID = "active_plan_board_id"

  const val ACTIVE_GEMINI_KEY = "active_gemini_key_name"
  const val GEMINI_KEYS = "gemini_api_keys_list"
  const val GEMINI_MODEL = "gemini_model"

  const val LAST_SYNC_AT = "last_sync_at"

  /** Workspace layout preferences. */
  const val HOME_DESTINATION = "home_destination"
  const val CALENDAR_VIEW = "calendar_view"
  const val PLAN_VIEW = "plan_view"
  const val ACTIVE_SAVED_PLAN_VIEW_ID = "active_saved_plan_view_id"
  const val GANTT_RANGE_DAYS = "gantt_range_days"
  const val PLAN_IMPORT_DISCLOSURE_ACKNOWLEDGED = "plan_import_disclosure_acknowledged"
  const val PLAN_LEGACY_CATALOG_IMPORTED = "plan_legacy_catalog_imported"
  const val UI_DENSITY = "ui_density"
  const val COLLAPSED_SECTIONS = "collapsed_sections"
  const val TODAY_SECTIONS = "today_sections"
  const val HOME_CARDS = "home_cards"
  const val AGENDA_GROUPING = "agenda_grouping"
  const val WEEK_SPAN_DAYS = "week_span_days"

  /** Last timestamp acknowledged by the change digest for a specific Plan board. */
  fun planChangeDigestReviewedAt(boardId: String) = "plan_change_digest_reviewed_at_$boardId"

  /** How long a change stays undoable; see [UndoWindowPolicy]. */
  const val UNDO_WINDOW_SECONDS = "undo_window_seconds"

  /** Plan Outline presentation. Saved views read and write these; they never touch task data. */
  const val PLAN_OUTLINE_SORT = "plan_outline_sort"
  const val PLAN_OUTLINE_HIDE_COMPLETED = "plan_outline_hide_completed"
  const val PLAN_OUTLINE_COLLAPSED = "plan_outline_collapsed"
  const val PLAN_OUTLINE_GROUPING = "plan_outline_grouping"
  const val PLAN_OUTLINE_QUERY = "plan_outline_query"

  /** Which Board lanes are shown. Empty means every lane, which is the honest default. */
  const val PLAN_BOARD_COLUMNS = "plan_board_columns"

  fun columnsForBoard(board: String) = "kanban_columns_$board"

  const val DEFAULT_BOARD = "Default"
  val DEFAULT_COLUMNS = listOf("To Do", "In Progress", "Done")
  const val DEFAULT_GEMINI_KEY_NAME = "Built-in key"

  /**
   * Length-prefixed v2 encoding keeps commas, newlines and arbitrary Unicode in
   * names. Legacy comma/newline strings remain readable for upgrades.
   */
  fun encodeList(values: List<String>): String =
    buildString {
      append(LIST_V2_PREFIX)
      append(values.size)
      append('|')
      values.forEach { value ->
        append(value.length)
        append(':')
        append(value)
      }
    }

  fun decodeList(raw: String?): List<String>? {
    if (raw == null) return null
    val parts =
      if (raw.startsWith(LIST_V2_PREFIX)) decodeV2List(raw) ?: return null
      else raw.split("\n", ",").map { it.trim() }
    return parts.filter { it.isNotEmpty() }.distinct().ifEmpty { null }
  }

  private fun decodeV2List(raw: String): List<String>? {
    var cursor = LIST_V2_PREFIX.length
    val countEnd = raw.indexOf('|', cursor).takeIf { it >= cursor } ?: return null
    val count =
      raw.substring(cursor, countEnd).toIntOrNull()?.takeIf { it in 0..raw.length }
        ?: return null
    cursor = countEnd + 1

    val values = ArrayList<String>(count.coerceAtMost(1_024))
    repeat(count) {
      val lengthEnd = raw.indexOf(':', cursor).takeIf { it >= cursor } ?: return null
      val length =
        raw.substring(cursor, lengthEnd).toIntOrNull()?.takeIf { it >= 0 } ?: return null
      val valueStart = lengthEnd + 1
      val valueEnd = valueStart.toLong() + length.toLong()
      if (valueEnd > raw.length || valueEnd > Int.MAX_VALUE) return null
      values += raw.substring(valueStart, valueEnd.toInt())
      cursor = valueEnd.toInt()
    }
    if (cursor != raw.length) return null
    return values.filter { it.isNotEmpty() }.distinct().ifEmpty { null }
  }

  /** Light / dark / follow the system. */
  const val THEME_MODE_SYSTEM = "system"
  const val THEME_MODE_LIGHT = "light"
  const val THEME_MODE_DARK = "dark"

  private const val LIST_V2_PREFIX = "dailybrief:list:v2|"
}
