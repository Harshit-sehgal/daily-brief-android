package com.example.data.prefs

/** Every key stored in the settings table, in one place so nothing drifts. */
object SettingKeys {
  const val NOTION_TOKEN = "notion_token"
  const val NOTION_DB_ID = "notion_db_id"
  const val NOTION_ENABLED = "notion_enabled"

  const val DEVICE_CALENDAR_ENABLED = "device_cal_enabled"

  const val BRIEF_HOUR = "brief_hour"
  const val BRIEF_MINUTE = "brief_minute"
  const val DAILY_BRIEF_ENABLED = "daily_brief_enabled"
  const val REMINDERS_ENABLED = "alert_30m_enabled"
  const val REMINDER_LEAD_MINUTES = "reminder_lead_minutes"

  const val PROFILE_NAME = "profile_name"
  const val THEME_ACCENT = "theme_accent"
  const val THEME_MODE = "theme_mode"

  const val ACTIVE_BOARD = "active_kanban_board"
  const val BOARDS = "kanban_boards"

  const val ACTIVE_GEMINI_KEY = "active_gemini_key_name"
  const val GEMINI_KEYS = "gemini_api_keys_list"
  const val GEMINI_MODEL = "gemini_model"

  const val LAST_SYNC_AT = "last_sync_at"

  fun columnsForBoard(board: String) = "kanban_columns_$board"

  const val DEFAULT_BOARD = "Default"
  val DEFAULT_COLUMNS = listOf("To Do", "In Progress", "Done")
  const val DEFAULT_GEMINI_KEY_NAME = "Built-in key"

  /**
   * Board and column lists are written newline-separated; commas are still
   * accepted when reading so lists saved by earlier versions keep working.
   */
  fun encodeList(values: List<String>): String = values.joinToString("\n")

  fun decodeList(raw: String?): List<String>? {
    if (raw == null) return null
    val parts = raw.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return parts.ifEmpty { null }
  }

  /** Light / dark / follow the system. */
  const val THEME_MODE_SYSTEM = "system"
  const val THEME_MODE_LIGHT = "light"
  const val THEME_MODE_DARK = "dark"
}
