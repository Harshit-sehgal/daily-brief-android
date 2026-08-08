package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.core.ScheduleAnalysis
import com.example.core.isoDate
import com.example.data.api.BriefOutcome
import com.example.data.api.DeviceCalendarSync
import com.example.data.api.GeminiClient
import com.example.data.api.NotionClient
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.DailyBriefing
import com.example.data.model.EventSource
import com.example.data.model.SystemSetting
import com.example.data.prefs.SettingKeys
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What a sync produced, including anything the user needs to act on. */
data class SyncOutcome(
  val eventCount: Int,
  val calendarPermissionMissing: Boolean = false,
  val warnings: List<String> = emptyList(),
) {
  val hasProblems: Boolean
    get() = calendarPermissionMissing || warnings.isNotEmpty()
}

/** A brief plus the provenance the UI needs to be honest about it. */
data class BriefSnapshot(
  val markdown: String,
  val generatedAt: Long,
  /** Signature of the schedule this text describes; used to detect staleness. */
  val signature: String = "",
  val isFallback: Boolean = false,
  val note: String? = null,
  val fromCache: Boolean = false,
)

class BriefingRepository(private val context: Context) {
  private val database = AppDatabase.getDatabase(context)
  private val eventDao = database.eventDao()
  private val briefingDao = database.briefingDao()
  private val settingDao = database.settingDao()

  // ---- Events -------------------------------------------------------------

  fun eventsInRange(start: Long, end: Long): Flow<List<BriefingEvent>> =
    eventDao.getEventsInRange(start, end)

  fun eventsForBoard(board: String): Flow<List<BriefingEvent>> = eventDao.getEventsForBoard(board)

  suspend fun upsertEvent(event: BriefingEvent, markUserEdited: Boolean = true) =
    withContext(Dispatchers.IO) {
      eventDao.insertEvents(listOf(event.copy(userEdited = event.userEdited || markUserEdited)))
    }

  suspend fun deleteEvent(id: String) = withContext(Dispatchers.IO) { eventDao.deleteEventById(id) }

  suspend fun moveEventsBetweenBoards(oldBoard: String, newBoard: String) =
    withContext(Dispatchers.IO) { eventDao.moveEventsToBoard(oldBoard, newBoard) }

  suspend fun moveEventsBetweenColumns(board: String, oldColumn: String, newColumn: String) =
    withContext(Dispatchers.IO) { eventDao.moveEventsToColumn(board, oldColumn, newColumn) }

  suspend fun eventsInRangeOnce(start: Long, end: Long): List<BriefingEvent> =
    withContext(Dispatchers.IO) { eventDao.getEventsInRangeSync(start, end) }

  suspend fun eventById(id: String): BriefingEvent? =
    withContext(Dispatchers.IO) { eventDao.getEventById(id) }

  // ---- Sync ---------------------------------------------------------------

  /**
   * Refreshes the connected sources.
   *
   * A source's stored events are only cleared when that source actually
   * answered. A failed Notion call or a revoked calendar permission therefore
   * leaves the last known schedule in place instead of blanking the app, and
   * user-owned fields (board, column, hand edits) survive the round trip.
   */
  suspend fun syncSchedules(startMs: Long, endMs: Long): SyncOutcome =
    withContext(Dispatchers.IO) {
      val warnings = mutableListOf<String>()
      val incoming = mutableListOf<BriefingEvent>()
      val replaceable = mutableListOf<String>()
      var permissionMissing = false

      val calendarEnabled = readBoolean(SettingKeys.DEVICE_CALENDAR_ENABLED, true)
      if (calendarEnabled) {
        val fetch = DeviceCalendarSync.fetchDeviceCalendars(context, startMs, endMs)
        if (!fetch.permissionGranted) {
          permissionMissing = true
        } else if (fetch.error == null) {
          replaceable += DEVICE_SOURCES
          incoming += fetch.events
        } else {
          warnings += fetch.error
        }
      } else {
        replaceable += DEVICE_SOURCES
      }

      val notionEnabled = readBoolean(SettingKeys.NOTION_ENABLED, false)
      val token = readSetting(SettingKeys.NOTION_TOKEN).orEmpty()
      val databaseId = readSetting(SettingKeys.NOTION_DB_ID).orEmpty()
      if (notionEnabled && token.isNotBlank() && databaseId.isNotBlank()) {
        val fetch = NotionClient.fetchNotionEvents(token, databaseId)
        if (fetch.error == null) {
          replaceable += EventSource.NOTION
          incoming += fetch.events
        } else {
          warnings += fetch.error
          // Partial pages are still better than nothing.
          if (fetch.events.isNotEmpty()) {
            replaceable += EventSource.NOTION
            incoming += fetch.events
          }
        }
      } else {
        if (notionEnabled) warnings += "Add your Notion token and database ID to sync it"
        replaceable += EventSource.NOTION
      }

      val merged = mergeWithLocalEdits(incoming)
      if (replaceable.isNotEmpty()) eventDao.clearEventsBySources(replaceable)
      if (merged.isNotEmpty()) eventDao.insertEvents(merged)

      writeSetting(SettingKeys.LAST_SYNC_AT, System.currentTimeMillis().toString())
      briefingDao.deleteBriefingsOlderThan(System.currentTimeMillis() - BRIEF_RETENTION_MS)

      SyncOutcome(
        eventCount = merged.size,
        calendarPermissionMissing = permissionMissing,
        warnings = warnings.distinct(),
      )
    }

  private suspend fun mergeWithLocalEdits(incoming: List<BriefingEvent>): List<BriefingEvent> {
    if (incoming.isEmpty()) return emptyList()
    val existing = eventDao.getEventsBySources(EventSource.SYNCED).associateBy { it.id }
    return incoming.map { fresh ->
      val prior = existing[fresh.id] ?: return@map fresh
      if (prior.userEdited) {
        // Times and location still track the source of truth; wording and flags
        // belong to whoever last edited them by hand.
        fresh.copy(
          title = prior.title,
          description = prior.description,
          isUrgent = prior.isUrgent,
          isDeadline = prior.isDeadline,
          kanbanStatus = prior.kanbanStatus,
          kanbanBoard = prior.kanbanBoard,
          userEdited = true,
        )
      } else {
        fresh.copy(kanbanStatus = prior.kanbanStatus, kanbanBoard = prior.kanbanBoard)
      }
    }
  }

  suspend fun loadSampleDay(dayStartMs: Long) =
    withContext(Dispatchers.IO) {
      eventDao.insertEvents(DeviceCalendarSync.sampleDay(ScheduleAnalysis.startOfDay(dayStartMs)))
    }

  suspend fun clearSampleData() =
    withContext(Dispatchers.IO) { eventDao.clearEventsBySources(listOf(EventSource.SAMPLE)) }

  // ---- Briefing -----------------------------------------------------------

  /**
   * Returns the cached brief when it was written for exactly these events,
   * otherwise generates a fresh one. Fallback text is never cached so the next
   * attempt can still reach the model.
   */
  suspend fun cachedBriefing(dayMs: Long, events: List<BriefingEvent>): BriefSnapshot? =
    withContext(Dispatchers.IO) {
      val cached = briefingDao.getBriefingForDate(isoDate(dayMs)) ?: return@withContext null
      if (cached.briefText.isBlank() || cached.signature != ScheduleAnalysis.signature(events)) {
        return@withContext null
      }
      BriefSnapshot(
        markdown = cached.briefText,
        generatedAt = cached.createdAt,
        signature = cached.signature,
        fromCache = true,
      )
    }

  suspend fun briefingFor(
    dayMs: Long,
    events: List<BriefingEvent>,
    forceRefresh: Boolean = false,
  ): BriefSnapshot =
    withContext(Dispatchers.IO) {
      val dateKey = isoDate(dayMs)
      val signature = ScheduleAnalysis.signature(events)

      if (!forceRefresh) {
        cachedBriefing(dayMs, events)?.let {
          return@withContext it
        }
      }

      val outcome =
        GeminiClient.generateDailyBrief(
          events = events,
          targetDate = Date(dayMs),
          apiKeyOverride = activeGeminiKey(),
          modelOverride = readSetting(SettingKeys.GEMINI_MODEL),
        )

      when (outcome) {
        is BriefOutcome.Generated -> {
          briefingDao.insertBriefing(
            DailyBriefing(dateString = dateKey, briefText = outcome.markdown, signature = signature)
          )
          BriefSnapshot(
            markdown = outcome.markdown,
            generatedAt = System.currentTimeMillis(),
            signature = signature,
          )
        }
        // Fallback text is deliberately not cached, so the next attempt can
        // still reach the model once connectivity or the key is fixed.
        is BriefOutcome.LocalFallback ->
          BriefSnapshot(
            markdown = outcome.markdown,
            generatedAt = System.currentTimeMillis(),
            signature = signature,
            isFallback = true,
            note = outcome.reason,
          )
      }
    }

  private suspend fun activeGeminiKey(): String? {
    val activeName = readSetting(SettingKeys.ACTIVE_GEMINI_KEY) ?: return null
    if (isBuiltInKeyName(activeName)) return null
    return geminiKeys().firstOrNull { it.first == activeName }?.second
  }

  suspend fun geminiKeys(): List<Pair<String, String>> =
    parseGeminiKeys(readSetting(SettingKeys.GEMINI_KEYS))

  // ---- Settings -----------------------------------------------------------

  fun settingFlow(key: String): Flow<SystemSetting?> = settingDao.getSetting(key)

  suspend fun readSetting(key: String): String? =
    withContext(Dispatchers.IO) { settingDao.getSettingSync(key)?.value }

  suspend fun writeSetting(key: String, value: String) =
    withContext(Dispatchers.IO) { settingDao.insertSetting(SystemSetting(key, value)) }

  suspend fun deleteSetting(key: String) =
    withContext(Dispatchers.IO) { settingDao.deleteSetting(key) }

  private suspend fun readBoolean(key: String, default: Boolean): Boolean =
    readSetting(key)?.toBooleanStrictOrNull() ?: default

  companion object {
    private const val TAG = "BriefingRepository"
    private val DEVICE_SOURCES =
      listOf(EventSource.GOOGLE, EventSource.SAMSUNG, EventSource.DEVICE)
    private const val BRIEF_RETENTION_MS = 60L * 24 * 60 * 60 * 1000

    /** Older builds stored a different label for the built-in key. */
    fun isBuiltInKeyName(name: String): Boolean =
      name == SettingKeys.DEFAULT_GEMINI_KEY_NAME || name == "Default System Key" || name.isBlank()

    fun parseGeminiKeys(raw: String?): List<Pair<String, String>> {
      if (raw.isNullOrBlank()) return emptyList()
      return try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
          val item = array.optJSONObject(index) ?: return@mapNotNull null
          val name = item.optString("name")
          val key = item.optString("key")
          if (name.isBlank() || key.isBlank()) null else name to key
        }
      } catch (e: Exception) {
        Log.e(TAG, "Could not parse stored Gemini keys", e)
        emptyList()
      }
    }

    fun encodeGeminiKeys(keys: List<Pair<String, String>>): String {
      val array = JSONArray()
      keys.forEach { (name, key) ->
        array.put(JSONObject().put("name", name).put("key", key))
      }
      return array.toString()
    }
  }
}
