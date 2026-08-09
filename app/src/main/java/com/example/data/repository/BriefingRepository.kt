package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.core.ScheduleAnalysis
import com.example.core.isoDate
import com.example.data.api.BriefOutcome
import com.example.data.api.DeviceCalendarSync
import com.example.data.api.WriteBack
import com.example.data.api.GeminiClient
import com.example.data.api.NotionClient
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.DailyBriefing
import com.example.data.model.EventSource
import com.example.data.model.SystemSetting
import com.example.data.prefs.SettingKeys
import com.example.data.security.SecretStore
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What a sync produced, including anything the user needs to act on. */
data class SyncOutcome(
  val eventCount: Int,
  val calendarPermissionMissing: Boolean = false,
  val warnings: List<String> = emptyList(),
  /** Exact old rows removed by reconciliation; callers must cancel their alarms. */
  val removedEvents: List<BriefingEvent> = emptyList(),
  /** False when any enabled provider failed or returned an incomplete snapshot. */
  val completed: Boolean = true,
) {
  val hasProblems: Boolean
    get() = !completed || calendarPermissionMissing || warnings.isNotEmpty()
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

private data class ReconcileResult(
  val writtenCount: Int = 0,
  val removedEvents: List<BriefingEvent> = emptyList(),
)

class BriefingRepository(private val context: Context) {
  private val database = AppDatabase.getDatabase(context)
  private val eventDao = database.eventDao()
  private val briefingDao = database.briefingDao()
  private val settingDao = database.settingDao()
  private val secretStore = SecretStore(context)

  // ---- Events -------------------------------------------------------------

  fun eventsInRange(startInclusive: Long, endExclusive: Long): Flow<List<BriefingEvent>> =
    eventDao.getEventsInRange(startInclusive, endExclusive)

  fun eventsForBoard(board: String): Flow<List<BriefingEvent>> = eventDao.getEventsForBoard(board)

  suspend fun upsertEvent(event: BriefingEvent, markUserEdited: Boolean = true) =
    withContext(Dispatchers.IO) {
      eventDao.insertEvents(listOf(event.copy(userEdited = event.userEdited || markUserEdited)))
    }

  suspend fun deleteEvent(id: String) = withContext(Dispatchers.IO) { eventDao.deleteEventById(id) }

  /**
   * Pushes a hand edit back to the calendar the event came from, when the user
   * has that switched on. Local storage is already updated by this point, so a
   * refusal here degrades to "saved locally" rather than losing the edit.
   */
  suspend fun writeEventBack(event: BriefingEvent): WriteBack =
    withContext(Dispatchers.IO) {
      if (!readBoolean(SettingKeys.CALENDAR_WRITE_BACK, true)) {
        return@withContext WriteBack.NotApplicable
      }
      DeviceCalendarSync.writeBack(context, event)
    }

  suspend fun deleteEventFromCalendar(event: BriefingEvent): WriteBack =
    withContext(Dispatchers.IO) {
      if (event.source !in EventSource.DEVICE_WRITABLE) return@withContext WriteBack.NotApplicable
      if (!readBoolean(SettingKeys.CALENDAR_WRITE_BACK, true)) {
        return@withContext WriteBack.NotApplicable
      }
      DeviceCalendarSync.deleteFromProvider(context, event.id)
    }

  suspend fun eventsInRangeOnce(startInclusive: Long, endExclusive: Long): List<BriefingEvent> =
    withContext(Dispatchers.IO) {
      eventDao.getEventsInRangeSync(startInclusive, endExclusive)
    }

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
    syncSchedulesInternal(startMs, endMs, includeNotion = true)

  /** Fast local-only refresh used by the morning receiver before summarising Room. */
  suspend fun refreshDeviceCalendar(startMs: Long, endMs: Long): SyncOutcome =
    syncSchedulesInternal(startMs, endMs, includeNotion = false)

  private suspend fun syncSchedulesInternal(
    startMs: Long,
    endMs: Long,
    includeNotion: Boolean,
  ): SyncOutcome =
    withContext(Dispatchers.IO) {
      SYNC_MUTEX.withLock {
      val warnings = mutableListOf<String>()
      val calendarEnabled = readBoolean(SettingKeys.DEVICE_CALENDAR_ENABLED, true)
      // Provider I/O deliberately happens before opening the Room transaction.
      val calendarFetch =
        if (calendarEnabled) DeviceCalendarSync.fetchDeviceCalendars(context, startMs, endMs)
        else null
      val permissionMissing = calendarEnabled && calendarFetch?.permissionGranted == false
      calendarFetch?.error?.let(warnings::add)

      val savedNotionEnabled = readBoolean(SettingKeys.NOTION_ENABLED, false)
      val notionEnabled = includeNotion && savedNotionEnabled
      val token = if (notionEnabled) readSetting(SettingKeys.NOTION_TOKEN).orEmpty() else ""
      val databaseId = if (notionEnabled) readSetting(SettingKeys.NOTION_DB_ID).orEmpty() else ""
      val notionConfigured = token.isNotBlank() && databaseId.isNotBlank()
      val notionFetch =
        if (notionEnabled && notionConfigured) NotionClient.fetchNotionEvents(token, databaseId)
        else null
      if (notionEnabled && !notionConfigured) {
        warnings += "Add your Notion token and database/data-source ID to sync it"
      }
      notionFetch?.warnings?.let(warnings::addAll)
      notionFetch?.error?.let(warnings::add)

      val calendarCompleted =
        !calendarEnabled || (calendarFetch?.let { it.permissionGranted && it.error == null } == true)
      val notionCompleted =
        !includeNotion || !savedNotionEnabled || (notionConfigured && notionFetch?.complete == true)
      val completed = calendarCompleted && notionCompleted

      val reconciliation =
        database.withTransaction {
          val pieces = mutableListOf<ReconcileResult>()

          when {
            !calendarEnabled -> pieces += replaceSourcesGlobally(DEVICE_SOURCES, emptyList())
            calendarCompleted ->
              pieces +=
                replaceSourcesInRange(
                  sources = DEVICE_SOURCES,
                  incoming = calendarFetch?.events.orEmpty(),
                  startInclusive = startMs,
                  endExclusive = endMs,
                )
          }

          when {
            !includeNotion -> Unit
            !savedNotionEnabled ->
              pieces += replaceSourcesGlobally(listOf(EventSource.NOTION), emptyList())
            notionFetch?.complete == true ->
              pieces +=
                replaceSourcesGlobally(listOf(EventSource.NOTION), notionFetch.events)
            !notionFetch?.events.isNullOrEmpty() ->
              // Incomplete pagination may refresh rows we did receive, but it
              // must never imply that absent rows were deleted upstream.
              pieces += upsertPartial(listOf(EventSource.NOTION), notionFetch.events)
          }

          if (completed && includeNotion) {
            settingDao.insertSetting(
              SystemSetting(SettingKeys.LAST_SYNC_AT, System.currentTimeMillis().toString())
            )
          }
          briefingDao.deleteBriefingsOlderThan(System.currentTimeMillis() - BRIEF_RETENTION_MS)

          ReconcileResult(
            writtenCount = pieces.sumOf { it.writtenCount },
            removedEvents = pieces.flatMap { it.removedEvents }.distinctBy { it.id },
          )
        }

      SyncOutcome(
        eventCount = reconciliation.writtenCount,
        calendarPermissionMissing = permissionMissing,
        warnings = warnings.distinct(),
        removedEvents = reconciliation.removedEvents,
        completed = completed,
      )
      }
    }

  private suspend fun replaceSourcesGlobally(
    sources: List<String>,
    incoming: List<BriefingEvent>,
  ): ReconcileResult {
    val existing = eventDao.getEventsBySources(sources)
    val merged = mergeWithLocalEdits(incomingForSources(incoming, sources), existing)
    val incomingIds = merged.mapTo(mutableSetOf()) { it.id }
    eventDao.clearEventsBySources(sources)
    if (merged.isNotEmpty()) eventDao.insertEvents(merged)
    return ReconcileResult(
      writtenCount = merged.size,
      removedEvents = existing.filterNot { it.id in incomingIds },
    )
  }

  private suspend fun replaceSourcesInRange(
    sources: List<String>,
    incoming: List<BriefingEvent>,
    startInclusive: Long,
    endExclusive: Long,
  ): ReconcileResult {
    val existing = eventDao.getEventsBySourcesInRange(sources, startInclusive, endExclusive)
    val scopedIncoming =
      incoming.filter { it.startTime < endExclusive && it.endTime > startInclusive }
    val merged = mergeWithLocalEdits(incomingForSources(scopedIncoming, sources), existing)
    val incomingIds = merged.mapTo(mutableSetOf()) { it.id }
    eventDao.clearEventsBySourcesInRange(sources, startInclusive, endExclusive)
    if (merged.isNotEmpty()) eventDao.insertEvents(merged)
    return ReconcileResult(
      writtenCount = merged.size,
      removedEvents = existing.filterNot { it.id in incomingIds },
    )
  }

  private suspend fun upsertPartial(
    sources: List<String>,
    incoming: List<BriefingEvent>,
  ): ReconcileResult {
    val existing = eventDao.getEventsBySources(sources)
    val merged = mergeWithLocalEdits(incomingForSources(incoming, sources), existing)
    if (merged.isNotEmpty()) eventDao.insertEvents(merged)
    return ReconcileResult(writtenCount = merged.size)
  }

  private fun incomingForSources(
    incoming: List<BriefingEvent>,
    sources: List<String>,
  ): List<BriefingEvent> =
    incoming.filter { it.source in sources }.associateBy { it.id }.values.toList()

  private fun mergeWithLocalEdits(
    incoming: List<BriefingEvent>,
    existingEvents: List<BriefingEvent>,
  ): List<BriefingEvent> {
    val existing = existingEvents.associateBy { it.id }
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

  fun settingFlow(key: String): Flow<SystemSetting?> =
    if (key in SECRET_SETTING_KEYS) {
      flow {
        migrateLegacySecret(key)
        emitAll(secretStore.flow(key).map { value -> value?.let { SystemSetting(key, it) } })
      }.flowOn(Dispatchers.IO)
    } else {
      settingDao.getSetting(key)
    }

  suspend fun readSetting(key: String): String? =
    withContext(Dispatchers.IO) {
      if (key in SECRET_SETTING_KEYS) {
        SECRET_MUTEX.withLock {
          migrateLegacySecretUnlocked(key)
          secretStore.read(key)
        }
      } else {
        settingDao.getSettingSync(key)?.value
      }
    }

  suspend fun writeSetting(key: String, value: String) =
    withContext(Dispatchers.IO) {
      if (key in SECRET_SETTING_KEYS) {
        SECRET_MUTEX.withLock {
          secretStore.write(key, value)
          settingDao.deleteSetting(key)
        }
      } else {
        settingDao.insertSetting(SystemSetting(key, value))
      }
    }

  suspend fun deleteSetting(key: String) =
    withContext(Dispatchers.IO) {
      if (key in SECRET_SETTING_KEYS) {
        SECRET_MUTEX.withLock {
          secretStore.delete(key)
          settingDao.deleteSetting(key)
        }
      } else {
        settingDao.deleteSetting(key)
      }
    }

  // ---- Atomic board settings --------------------------------------------

  suspend fun createBoard(name: String): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val boards = storedBoards()
        if (boards.any { it.equals(name, ignoreCase = true) }) return@withTransaction false
        settingDao.insertSetting(
          SystemSetting(SettingKeys.BOARDS, SettingKeys.encodeList(boards + name))
        )
        settingDao.insertSetting(
          SystemSetting(
            SettingKeys.columnsForBoard(name),
            SettingKeys.encodeList(SettingKeys.DEFAULT_COLUMNS),
          )
        )
        settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_BOARD, name))
        true
      }
    }

  suspend fun renameBoard(oldName: String, newName: String): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val boards = storedBoards()
        if (oldName !in boards ||
          oldName == SettingKeys.DEFAULT_BOARD ||
          boards.any { it.equals(newName, ignoreCase = true) }
        ) {
          return@withTransaction false
        }
        settingDao.insertSetting(
          SystemSetting(
            SettingKeys.BOARDS,
            SettingKeys.encodeList(boards.map { if (it == oldName) newName else it }),
          )
        )
        val columns =
          settingDao.getSettingSync(SettingKeys.columnsForBoard(oldName))?.value
            ?: SettingKeys.encodeList(SettingKeys.DEFAULT_COLUMNS)
        settingDao.insertSetting(SystemSetting(SettingKeys.columnsForBoard(newName), columns))
        settingDao.deleteSetting(SettingKeys.columnsForBoard(oldName))
        eventDao.moveEventsToBoard(oldName, newName)
        if (storedActiveBoard(boards) == oldName) {
          settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_BOARD, newName))
        }
        true
      }
    }

  /** Returns the fallback board on success, or null when deletion is invalid. */
  suspend fun deleteBoard(name: String): String? =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val boards = storedBoards()
        if (name == SettingKeys.DEFAULT_BOARD || name !in boards) return@withTransaction null
        val remaining = boards.filterNot { it == name }.ifEmpty { listOf(SettingKeys.DEFAULT_BOARD) }
        val fallback = remaining.first()
        settingDao.insertSetting(
          SystemSetting(SettingKeys.BOARDS, SettingKeys.encodeList(remaining))
        )
        settingDao.deleteSetting(SettingKeys.columnsForBoard(name))
        eventDao.moveEventsToBoard(name, fallback)
        settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_BOARD, fallback))
        fallback
      }
    }

  suspend fun createColumn(name: String): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val board = storedActiveBoard(storedBoards())
        val columns = storedColumns(board)
        if (columns.any { it.equals(name, ignoreCase = true) }) return@withTransaction false
        settingDao.insertSetting(
          SystemSetting(SettingKeys.columnsForBoard(board), SettingKeys.encodeList(columns + name))
        )
        true
      }
    }

  suspend fun renameColumn(oldName: String, newName: String): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val board = storedActiveBoard(storedBoards())
        val columns = storedColumns(board)
        if (oldName !in columns || columns.any { it.equals(newName, ignoreCase = true) }) {
          return@withTransaction false
        }
        settingDao.insertSetting(
          SystemSetting(
            SettingKeys.columnsForBoard(board),
            SettingKeys.encodeList(columns.map { if (it == oldName) newName else it }),
          )
        )
        eventDao.moveEventsToColumn(board, oldName, newName)
        true
      }
    }

  suspend fun deleteColumn(name: String): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val board = storedActiveBoard(storedBoards())
        val columns = storedColumns(board)
        val remaining = columns.filterNot { it == name }
        if (name !in columns || remaining.isEmpty()) return@withTransaction false
        settingDao.insertSetting(
          SystemSetting(SettingKeys.columnsForBoard(board), SettingKeys.encodeList(remaining))
        )
        eventDao.moveEventsToColumn(board, name, remaining.first())
        true
      }
    }

  private suspend fun storedBoards(): List<String> =
    SettingKeys.decodeList(settingDao.getSettingSync(SettingKeys.BOARDS)?.value)
      ?: listOf(SettingKeys.DEFAULT_BOARD)

  private suspend fun storedActiveBoard(boards: List<String>): String =
    settingDao
      .getSettingSync(SettingKeys.ACTIVE_BOARD)
      ?.value
      ?.takeIf { it in boards }
      ?: boards.firstOrNull()
      ?: SettingKeys.DEFAULT_BOARD

  private suspend fun storedColumns(board: String): List<String> =
    SettingKeys.decodeList(settingDao.getSettingSync(SettingKeys.columnsForBoard(board))?.value)
      ?: SettingKeys.DEFAULT_COLUMNS

  /** Moves plaintext values written by earlier builds into Android Keystore once. */
  private suspend fun migrateLegacySecret(key: String) =
    withContext(Dispatchers.IO) {
      SECRET_MUTEX.withLock { migrateLegacySecretUnlocked(key) }
    }

  private suspend fun migrateLegacySecretUnlocked(key: String) {
    val legacy = settingDao.getSettingSync(key)?.value
    if (secretStore.read(key) == null && !legacy.isNullOrBlank()) {
      secretStore.write(key, legacy)
    }
    if (legacy != null) settingDao.deleteSetting(key)
  }

  private suspend fun readBoolean(key: String, default: Boolean): Boolean =
    readSetting(key)?.toBooleanStrictOrNull() ?: default

  companion object {
    private const val TAG = "BriefingRepository"
    private val SECRET_SETTING_KEYS = setOf(SettingKeys.NOTION_TOKEN, SettingKeys.GEMINI_KEYS)
    private val SECRET_MUTEX = Mutex()
    private val SYNC_MUTEX = Mutex()
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
