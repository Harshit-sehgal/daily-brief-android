package com.example.data.repository

import com.example.data.prefs.UndoWindowPolicy
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
import com.example.data.database.LegacyPlanCatalogBuilder
import com.example.data.model.BriefingEvent
import com.example.data.model.DailyBriefing
import com.example.data.model.EventSource
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem
import com.example.data.model.PlanMutation
import com.example.data.model.PlanMutationOrigin
import com.example.data.model.PlanMutationStatus
import com.example.data.model.SystemSetting
import com.example.data.prefs.SettingKeys
import com.example.data.security.SecretStore
import java.util.Date
import java.util.UUID
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

/** Result of a fail-closed delete attempt against the event's real source. */
data class EventDeleteOutcome(val writeBack: WriteBack, val deleted: Boolean)

/** Detailed board deletion result so Plan tasks can block destructive legacy actions. */
sealed interface BoardDeleteOutcome {
  data class Deleted(val fallbackBoard: String) : BoardDeleteOutcome

  data object ContainsPlanItems : BoardDeleteOutcome

  data object Invalid : BoardDeleteOutcome
}

private data class ReconcileResult(
  val writtenCount: Int = 0,
  val removedEvents: List<BriefingEvent> = emptyList(),
)

private data class PlanCatalogRows(
  val boards: Map<String, PlanBoard>,
  val columns: Map<String, PlanColumn>,
)

class BriefingRepository(private val context: Context) {
  private val database = AppDatabase.getDatabase(context)
  private val eventDao = database.eventDao()
  private val briefingDao = database.briefingDao()
  private val settingDao = database.settingDao()
  private val planDao = database.planDao()
  private val secretStore = SecretStore(context)

  // ---- Events -------------------------------------------------------------

  fun eventsInRange(startInclusive: Long, endExclusive: Long): Flow<List<BriefingEvent>> =
    eventDao.getEventsInRange(startInclusive, endExclusive)

  fun eventsForBoard(board: String): Flow<List<BriefingEvent>> = eventDao.getEventsForBoard(board)

  suspend fun upsertEvent(event: BriefingEvent, markUserEdited: Boolean = true) =
    withContext(Dispatchers.IO) {
      SCHEDULE_MUTEX.withLock {
        eventDao.insertEvents(listOf(event.copy(userEdited = event.userEdited || markUserEdited)))
      }
    }

  suspend fun deleteEvent(id: String) =
    withContext(Dispatchers.IO) { SCHEDULE_MUTEX.withLock { eventDao.deleteEventById(id) } }

  /** Exact compare-and-restore used by event Undo; sync or a newer edit always wins. */
  suspend fun restoreEventSnapshotIfUnchanged(
    before: BriefingEvent,
    expectedCurrent: BriefingEvent?,
  ): Boolean =
    withContext(Dispatchers.IO) {
      SCHEDULE_MUTEX.withLock {
        val current = eventDao.getEventById(before.id)
        val unchanged =
          if (expectedCurrent == null) current == null else current == expectedCurrent
        if (!unchanged) return@withLock false
        eventDao.insertEvents(listOf(before))
        true
      }
    }

  /**
   * Commits a previewed Timeline move only when the exact event used to build the preview is
   * still current. Timeline manipulation is deliberately limited to app-owned rows, so this
   * transaction never performs a provider write or overwrites a provider refresh.
   *
   * The returned row is the exact stored snapshot callers must use as the expected state for
   * Undo. `null` means the source is not app-owned or the preview became stale; neither case
   * writes anything.
   */
  suspend fun moveAppOwnedEventIfUnchanged(
    before: BriefingEvent,
    newStartMs: Long,
    newEndMs: Long,
  ): BriefingEvent? =
    withContext(Dispatchers.IO) {
      SCHEDULE_MUTEX.withLock {
        if (before.source != EventSource.MANUAL && before.source != EventSource.SAMPLE) {
          return@withLock null
        }
        if (newEndMs <= newStartMs) return@withLock null
        val current = eventDao.getEventById(before.id)
        if (current != before) return@withLock null
        val moved = current.copy(startTime = newStartMs, endTime = newEndMs, userEdited = true)
        eventDao.insertEvents(listOf(moved))
        moved
      }
    }

  /**
   * Saves locally and completes any provider write-back in the same schedule
   * lane as sync. A fetched old timestamp can therefore never commit after this
   * edit and silently undo it.
   */
  suspend fun saveEventAndWriteBack(event: BriefingEvent): WriteBack =
    withContext(Dispatchers.IO) {
      SCHEDULE_MUTEX.withLock {
        eventDao.insertEvents(listOf(event.copy(userEdited = true)))
        when {
          event.source == EventSource.NOTION ->
            WriteBack.Skipped(
              "Notion stays read-only here — local labels were saved; source times refresh on sync"
            )
          event.source !in EventSource.DEVICE_WRITABLE -> WriteBack.NotApplicable
          !readBoolean(SettingKeys.CALENDAR_WRITE_BACK, true) ->
            WriteBack.Skipped("Calendar write-back is off — saved here only")
          else -> DeviceCalendarSync.writeBack(context, event)
        }
      }
    }

  /** Deletes only when the real source accepts it; read-only providers fail closed. */
  suspend fun deleteEventAndProvider(event: BriefingEvent): EventDeleteOutcome =
    withContext(Dispatchers.IO) {
      SCHEDULE_MUTEX.withLock {
        val writeBack =
          when {
            event.source == EventSource.NOTION ->
              WriteBack.Skipped("Notion events are read-only here — delete it in Notion")
            event.source !in EventSource.DEVICE_WRITABLE -> WriteBack.NotApplicable
            !readBoolean(SettingKeys.CALENDAR_WRITE_BACK, true) ->
              WriteBack.Skipped(
                "Calendar write-back is off — delete it in your calendar app"
              )
            else -> DeviceCalendarSync.deleteFromProvider(context, event.id)
          }
        val mayDelete =
          writeBack == WriteBack.Success ||
            (writeBack == WriteBack.NotApplicable && event.source !in EventSource.SYNCED)
        if (mayDelete) eventDao.deleteEventById(event.id)
        EventDeleteOutcome(writeBack = writeBack, deleted = mayDelete)
      }
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
      SCHEDULE_MUTEX.withLock {
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
    val merged =
      SyncMergePolicy.merge(SyncMergePolicy.scopedToSources(incoming, sources), existing)
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
    val merged =
      SyncMergePolicy.merge(SyncMergePolicy.scopedToSources(scopedIncoming, sources), existing)
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
    val merged =
      SyncMergePolicy.merge(SyncMergePolicy.scopedToSources(incoming, sources), existing)
    if (merged.isNotEmpty()) eventDao.insertEvents(merged)
    return ReconcileResult(writtenCount = merged.size)
  }

  suspend fun loadSampleDay(dayStartMs: Long) =
    withContext(Dispatchers.IO) {
      SCHEDULE_MUTEX.withLock {
        eventDao.insertEvents(DeviceCalendarSync.sampleDay(ScheduleAnalysis.startOfDay(dayStartMs)))
      }
    }

  suspend fun clearSampleData() =
    withContext(Dispatchers.IO) {
      SCHEDULE_MUTEX.withLock {
        eventDao.clearEventsBySources(listOf(EventSource.SAMPLE))
      }
    }

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

  /** Commits related, non-secret preferences as one observable Room transaction. */
  suspend fun writeSettingsAtomically(settings: Map<String, String>) =
    withContext(Dispatchers.IO) {
      require(settings.keys.none { it in SECRET_SETTING_KEYS }) {
        "Secret settings must be written through the encrypted store"
      }
      database.withTransaction {
        settings.forEach { (key, value) -> settingDao.insertSetting(SystemSetting(key, value)) }
      }
    }

  // ---- Atomic board settings --------------------------------------------

  /** Keeps the name-based legacy selection and stable Plan selection in one commit. */
  suspend fun selectBoard(name: String): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val boards = storedBoards()
        if (name !in boards) return@withTransaction false
        val planBoard = ensurePlanBoard(name, storedColumns(name))
        writeActiveBoardSelection(name, planBoard.id)
        true
      }
    }

  suspend fun createBoard(name: String): Boolean = createBoardWithUndo(name).value

  suspend fun createBoardWithUndo(name: String): JournaledPlanResult<Boolean> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val boards = storedBoards()
        if (name.isBlank() || boards.any { it.equals(name, ignoreCase = true) }) {
          return@withTransaction JournaledPlanResult(false, null)
        }
        val scopeId = newCatalogScopeId()
        val settingKeys = catalogSettingKeys(SettingKeys.columnsForBoard(name))
        val beforeCatalog = readPlanCatalogRows()
        val beforeSettings = readSettings(settingKeys)
        val columns = SettingKeys.DEFAULT_COLUMNS
        // A Plan-only board with this exact name can safely become visible to the legacy board.
        // Reuse its identity; never create a second entity or overwrite its tasks.
        val planBoard = ensurePlanBoard(name, columns)
        settingDao.insertSetting(
          SystemSetting(SettingKeys.BOARDS, SettingKeys.encodeList(boards + name))
        )
        settingDao.insertSetting(
          SystemSetting(
            SettingKeys.columnsForBoard(name),
            SettingKeys.encodeList(columns),
          )
        )
        writeActiveBoardSelection(name, planBoard.id)
        val (before, after) =
          catalogMutationStates(
            scopeId = scopeId,
            beforeCatalog = beforeCatalog,
            afterCatalog = readPlanCatalogRows(),
            beforeSettings = beforeSettings,
            afterSettings = readSettings(settingKeys),
          )
        val mutationId =
          insertCatalogMutation(
            mutationType = PlanMutationType.BOARD_CREATE,
            summary = "Created board \"$name\"",
            before = before,
            after = after,
          )
        JournaledPlanResult(true, mutationId)
      }
    }

  suspend fun renameBoard(oldName: String, newName: String): Boolean =
    renameBoardWithUndo(oldName, newName).value

  suspend fun renameBoardWithUndo(
    oldName: String,
    newName: String,
  ): JournaledPlanResult<Boolean> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val boards = storedBoards()
        if (newName.isBlank() ||
          oldName !in boards ||
          oldName == SettingKeys.DEFAULT_BOARD ||
          boards.any { it.equals(newName, ignoreCase = true) }
        ) {
          return@withTransaction JournaledPlanResult(false, null)
        }
        val columns = storedColumns(oldName)
        val existingPlanBoard = planDao.getBoardByName(oldName)
        val planNameOwner = planDao.getBoardByName(newName)
        if (planNameOwner != null && planNameOwner.id != existingPlanBoard?.id) {
          return@withTransaction JournaledPlanResult(false, null)
        }
        val scopeId = newCatalogScopeId()
        val settingKeys =
          catalogSettingKeys(
            SettingKeys.columnsForBoard(oldName),
            SettingKeys.columnsForBoard(newName),
          )
        val beforeCatalog = readPlanCatalogRows()
        val beforeSettings = readSettings(settingKeys)
        val beforeEvents = eventDao.getEventsForBoardSync(oldName).associateBy(BriefingEvent::id)
        val planBoard = ensurePlanBoard(oldName, columns)
        val now = System.currentTimeMillis()
        planDao.updateBoard(
          planBoard.copy(
            name = newName,
            nameKey = availableBoardNameKey(newName, excludingBoardId = planBoard.id),
            updatedAt = now,
          )
        )
        settingDao.insertSetting(
          SystemSetting(
            SettingKeys.BOARDS,
            SettingKeys.encodeList(boards.map { if (it == oldName) newName else it }),
          )
        )
        settingDao.insertSetting(
          SystemSetting(SettingKeys.columnsForBoard(newName), SettingKeys.encodeList(columns))
        )
        settingDao.deleteSetting(SettingKeys.columnsForBoard(oldName))
        eventDao.moveEventsToBoard(oldName, newName)
        val activeName = storedActiveBoard(boards).let { if (it == oldName) newName else it }
        val activePlanBoard =
          if (activeName == newName) planBoard else ensurePlanBoard(activeName, storedColumns(activeName))
        writeActiveBoardSelection(activeName, activePlanBoard.id)
        val afterEvents = readEvents(beforeEvents.keys)
        val (before, after) =
          catalogMutationStates(
            scopeId = scopeId,
            beforeCatalog = beforeCatalog,
            afterCatalog = readPlanCatalogRows(),
            beforeEvents = beforeEvents,
            afterEvents = afterEvents,
            beforeSettings = beforeSettings,
            afterSettings = readSettings(settingKeys),
          )
        val mutationId =
          insertCatalogMutation(
            mutationType = PlanMutationType.BOARD_EDIT,
            summary = "Renamed board \"$oldName\" to \"$newName\"",
            before = before,
            after = after,
            now = now,
          )
        JournaledPlanResult(true, mutationId)
      }
    }

  suspend fun deleteBoardWithOutcome(name: String): BoardDeleteOutcome =
    deleteBoardWithOutcomeAndUndo(name).value

  suspend fun deleteBoardWithOutcomeAndUndo(
    name: String,
  ): JournaledPlanResult<BoardDeleteOutcome> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val boards = storedBoards()
        if (name == SettingKeys.DEFAULT_BOARD || name !in boards) {
          return@withTransaction JournaledPlanResult(BoardDeleteOutcome.Invalid, null)
        }
        val existingPlanBoard = planDao.getBoardByName(name)
        if (existingPlanBoard != null && planDao.countItemsForBoard(existingPlanBoard.id) > 0) {
          return@withTransaction JournaledPlanResult(BoardDeleteOutcome.ContainsPlanItems, null)
        }
        val remaining = boards.filterNot { it == name }.ifEmpty { listOf(SettingKeys.DEFAULT_BOARD) }
        val fallback = remaining.first()
        val fallbackColumns = storedColumns(fallback)
        val scopeId = newCatalogScopeId()
        val settingKeys = catalogSettingKeys(SettingKeys.columnsForBoard(name))
        val beforeCatalog = readPlanCatalogRows()
        val beforeSettings = readSettings(settingKeys)
        val beforeEvents = eventDao.getEventsForBoardSync(name).associateBy(BriefingEvent::id)
        val planBoard = ensurePlanBoard(name, storedColumns(name))
        val fallbackPlanBoard = ensurePlanBoard(fallback, fallbackColumns)
        settingDao.insertSetting(
          SystemSetting(SettingKeys.BOARDS, SettingKeys.encodeList(remaining))
        )
        settingDao.deleteSetting(SettingKeys.columnsForBoard(name))
        eventDao.moveEventsToBoardWithColumnFallback(
          oldBoard = name,
          newBoard = fallback,
          validStatuses = fallbackColumns,
          fallbackStatus = fallbackColumns.first(),
        )
        val now = System.currentTimeMillis()
        planDao.updateBoard(planBoard.copy(archivedAt = now, updatedAt = now))
        writeActiveBoardSelection(fallback, fallbackPlanBoard.id)
        val (before, after) =
          catalogMutationStates(
            scopeId = scopeId,
            beforeCatalog = beforeCatalog,
            afterCatalog = readPlanCatalogRows(),
            beforeEvents = beforeEvents,
            afterEvents = readEvents(beforeEvents.keys),
            beforeSettings = beforeSettings,
            afterSettings = readSettings(settingKeys),
          )
        val mutationId =
          insertCatalogMutation(
            mutationType = PlanMutationType.BOARD_DELETE,
            summary = "Deleted board \"$name\"",
            before = before,
            after = after,
            now = now,
          )
        JournaledPlanResult(BoardDeleteOutcome.Deleted(fallback), mutationId)
      }
    }

  /** Backwards-compatible shape for callers that only need success and fallback. */
  suspend fun deleteBoard(name: String): String? =
    when (val outcome = deleteBoardWithOutcome(name)) {
      is BoardDeleteOutcome.Deleted -> outcome.fallbackBoard
      BoardDeleteOutcome.ContainsPlanItems,
      BoardDeleteOutcome.Invalid -> null
    }

  suspend fun createColumn(name: String): Boolean = createColumnWithUndo(name).value

  suspend fun createColumnWithUndo(name: String): JournaledPlanResult<Boolean> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val board = storedActiveBoard(storedBoards())
        val columns = storedColumns(board)
        if (name.isBlank() || columns.any { it.equals(name, ignoreCase = true) }) {
          return@withTransaction JournaledPlanResult(false, null)
        }
        val scopeId = newCatalogScopeId()
        val settingKeys = catalogSettingKeys(SettingKeys.columnsForBoard(board))
        val beforeCatalog = readPlanCatalogRows()
        val beforeSettings = readSettings(settingKeys)
        val planBoard = ensurePlanBoard(board, columns)
        // Reuse an exact Plan-only column instead of splitting one workflow label into two IDs.
        ensurePlanColumn(planBoard.id, name)
        settingDao.insertSetting(
          SystemSetting(SettingKeys.columnsForBoard(board), SettingKeys.encodeList(columns + name))
        )
        writeActiveBoardSelection(board, planBoard.id)
        val (before, after) =
          catalogMutationStates(
            scopeId = scopeId,
            beforeCatalog = beforeCatalog,
            afterCatalog = readPlanCatalogRows(),
            beforeSettings = beforeSettings,
            afterSettings = readSettings(settingKeys),
          )
        val mutationId =
          insertCatalogMutation(
            mutationType = PlanMutationType.COLUMN_CREATE,
            summary = "Created column \"$name\"",
            before = before,
            after = after,
          )
        JournaledPlanResult(true, mutationId)
      }
    }

  suspend fun renameColumn(oldName: String, newName: String): Boolean =
    renameColumnWithUndo(oldName, newName).value

  suspend fun renameColumnWithUndo(
    oldName: String,
    newName: String,
  ): JournaledPlanResult<Boolean> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val board = storedActiveBoard(storedBoards())
        val columns = storedColumns(board)
        if (newName.isBlank() ||
          oldName !in columns ||
          columns.any { it.equals(newName, ignoreCase = true) }
        ) {
          return@withTransaction JournaledPlanResult(false, null)
        }
        val existingPlanBoard = planDao.getBoardByName(board)
        val existingPlanColumn = existingPlanBoard?.let { planDao.getColumnByName(it.id, oldName) }
        val planNameOwner = existingPlanBoard?.let { planDao.getColumnByName(it.id, newName) }
        if (planNameOwner != null && planNameOwner.id != existingPlanColumn?.id) {
          return@withTransaction JournaledPlanResult(false, null)
        }
        val scopeId = newCatalogScopeId()
        val settingKeys = catalogSettingKeys(SettingKeys.columnsForBoard(board))
        val beforeCatalog = readPlanCatalogRows()
        val beforeSettings = readSettings(settingKeys)
        val beforeEvents =
          eventDao.getEventsForBoardColumnSync(board, oldName).associateBy(BriefingEvent::id)
        val planBoard = ensurePlanBoard(board, columns)
        val planColumn = ensurePlanColumn(planBoard.id, oldName)
        val now = System.currentTimeMillis()
        planDao.updateColumn(
          planColumn.copy(
            name = newName,
            nameKey =
              availableColumnNameKey(
                boardId = planBoard.id,
                name = newName,
                excludingColumnId = planColumn.id,
              ),
            updatedAt = now,
          )
        )
        settingDao.insertSetting(
          SystemSetting(
            SettingKeys.columnsForBoard(board),
            SettingKeys.encodeList(columns.map { if (it == oldName) newName else it }),
          )
        )
        eventDao.moveEventsToColumn(board, oldName, newName)
        writeActiveBoardSelection(board, planBoard.id)
        val (before, after) =
          catalogMutationStates(
            scopeId = scopeId,
            beforeCatalog = beforeCatalog,
            afterCatalog = readPlanCatalogRows(),
            beforeEvents = beforeEvents,
            afterEvents = readEvents(beforeEvents.keys),
            beforeSettings = beforeSettings,
            afterSettings = readSettings(settingKeys),
          )
        val mutationId =
          insertCatalogMutation(
            mutationType = PlanMutationType.COLUMN_EDIT,
            summary = "Renamed column \"$oldName\" to \"$newName\"",
            before = before,
            after = after,
            now = now,
          )
        JournaledPlanResult(true, mutationId)
      }
    }

  suspend fun deleteColumn(name: String): Boolean = deleteColumnWithUndo(name).value

  suspend fun deleteColumnWithUndo(name: String): JournaledPlanResult<Boolean> =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val board = storedActiveBoard(storedBoards())
        val columns = storedColumns(board)
        val remaining = columns.filterNot { it == name }
        if (name !in columns || remaining.isEmpty()) {
          return@withTransaction JournaledPlanResult(false, null)
        }
        val scopeId = newCatalogScopeId()
        val settingKeys = catalogSettingKeys(SettingKeys.columnsForBoard(board))
        val beforeCatalog = readPlanCatalogRows()
        val beforeSettings = readSettings(settingKeys)
        val beforeEvents =
          eventDao.getEventsForBoardColumnSync(board, name).associateBy(BriefingEvent::id)
        val existingPlanBoard = planDao.getBoardByName(board)
        val beforeItems =
          existingPlanBoard
            ?.let { planDao.getAllItems(it.id) }
            .orEmpty()
            .associateBy(PlanItem::id)
        val planBoard = ensurePlanBoard(board, columns)
        val planColumn = ensurePlanColumn(planBoard.id, name)
        val fallbackColumn = ensurePlanColumn(planBoard.id, remaining.first())
        val now = System.currentTimeMillis()
        planDao.moveItemsToColumn(
          boardId = planBoard.id,
          deletedColumnId = planColumn.id,
          fallbackColumnId = fallbackColumn.id,
          updatedAt = now,
        )
        planDao.deleteColumn(planColumn)
        settingDao.insertSetting(
          SystemSetting(SettingKeys.columnsForBoard(board), SettingKeys.encodeList(remaining))
        )
        eventDao.moveEventsToColumn(board, name, remaining.first())
        writeActiveBoardSelection(board, planBoard.id)
        val afterItems = planDao.getAllItems(planBoard.id).associateBy(PlanItem::id)
        val (before, after) =
          catalogMutationStates(
            scopeId = scopeId,
            beforeCatalog = beforeCatalog,
            afterCatalog = readPlanCatalogRows(),
            beforeItems = beforeItems,
            afterItems = afterItems,
            beforeEvents = beforeEvents,
            afterEvents = readEvents(beforeEvents.keys),
            beforeSettings = beforeSettings,
            afterSettings = readSettings(settingKeys),
          )
        val mutationId =
          insertCatalogMutation(
            mutationType = PlanMutationType.COLUMN_DELETE,
            summary = "Deleted column \"$name\"",
            before = before,
            after = after,
            now = now,
          )
        JournaledPlanResult(true, mutationId)
      }
    }

  private suspend fun readPlanCatalogRows(): PlanCatalogRows {
    val boards = planDao.getAllBoards()
    return PlanCatalogRows(
      boards = boards.associateBy(PlanBoard::id),
      columns =
        boards
          .flatMap { board -> planDao.getAllColumns(board.id) }
          .associateBy(PlanColumn::id),
    )
  }

  private suspend fun readSettings(keys: Set<String>): Map<String, String?> =
    keys.sorted().associateWith { key -> settingDao.getSettingSync(key)?.value }

  private suspend fun readEvents(ids: Set<String>): Map<String, BriefingEvent> =
    if (ids.isEmpty()) emptyMap()
    else eventDao.getEventsByIds(ids.sorted()).associateBy(BriefingEvent::id)

  private fun catalogSettingKeys(vararg additional: String): Set<String> =
    setOf(
      SettingKeys.BOARDS,
      SettingKeys.ACTIVE_BOARD,
      SettingKeys.ACTIVE_PLAN_BOARD_ID,
      *additional,
    )

  private fun newCatalogScopeId(): String = "catalog_scope_${UUID.randomUUID()}"

  private fun catalogMutationStates(
    scopeId: String,
    beforeCatalog: PlanCatalogRows,
    afterCatalog: PlanCatalogRows,
    beforeItems: Map<String, PlanItem> = emptyMap(),
    afterItems: Map<String, PlanItem> = emptyMap(),
    beforeEvents: Map<String, BriefingEvent> = emptyMap(),
    afterEvents: Map<String, BriefingEvent> = emptyMap(),
    beforeSettings: Map<String, String?> = emptyMap(),
    afterSettings: Map<String, String?> = emptyMap(),
  ): Pair<PlanCatalogState, PlanCatalogState> {
    val (boardsBefore, boardsAfter) = changedSlots(beforeCatalog.boards, afterCatalog.boards)
    val (columnsBefore, columnsAfter) = changedSlots(beforeCatalog.columns, afterCatalog.columns)
    val (itemsBefore, itemsAfter) = changedSlots(beforeItems, afterItems)
    val (eventsBefore, eventsAfter) = changedSlots(beforeEvents, afterEvents)
    val (settingsBefore, settingsAfter) = changedSlots(beforeSettings, afterSettings)
    val before =
      PlanCatalogState(
        scopeId = scopeId,
        boards = boardsBefore,
        columns = columnsBefore,
        items = itemsBefore,
        events = eventsBefore,
        settings = settingsBefore,
      )
    val after =
      PlanCatalogState(
        scopeId = scopeId,
        boards = boardsAfter,
        columns = columnsAfter,
        items = itemsAfter,
        events = eventsAfter,
        settings = settingsAfter,
      )
    require(
      before.boards.isNotEmpty() ||
        before.columns.isNotEmpty() ||
        before.items.isNotEmpty() ||
        before.events.isNotEmpty() ||
        before.settings.isNotEmpty()
    ) {
      "Catalog command did not change persisted state"
    }
    return before to after
  }

  private fun <T> changedSlots(
    before: Map<String, T>,
    after: Map<String, T>,
  ): Pair<Map<String, T?>, Map<String, T?>> {
    val keys = (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
    return keys.associateWith { before[it] } to keys.associateWith { after[it] }
  }

  private suspend fun insertCatalogMutation(
    mutationType: String,
    summary: String,
    before: PlanCatalogState,
    after: PlanCatalogState,
    now: Long = System.currentTimeMillis(),
  ): String {
    require(mutationType in PlanMutationType.Catalog) { "Unknown catalog mutation type" }
    val encodedBefore = PlanMutationCodec.encode(before)
    val encodedAfter = PlanMutationCodec.encode(after)
    require(encodedBefore.targetIdsJson == encodedAfter.targetIdsJson) {
      "Catalog mutation scopes differ"
    }
    val cleanSummary = summary.trim()
    require(cleanSummary.isNotEmpty() && cleanSummary.length <= 240) {
      "Invalid catalog mutation summary"
    }
    val mutationId = "mutation_${UUID.randomUUID()}"
    planDao.insertPlanMutation(
      PlanMutation(
        id = mutationId,
        // Catalog commands may create/archive their subject board. Keeping this null prevents the
        // journal's own foreign key from becoming an extra side effect of exact Undo.
        boardId = null,
        mutationType = mutationType,
        targetType = PlanMutationTarget.CATALOG,
        targetIdsJson = encodedAfter.targetIdsJson,
        summary = cleanSummary,
        beforeJson = encodedBefore.stateJson,
        afterJson = encodedAfter.stateJson,
        status = PlanMutationStatus.APPLIED,
        origin = PlanMutationOrigin.USER,
        schemaVersion = PlanMutationCodec.SCHEMA_VERSION,
        createdAt = now,
        updatedAt = now,
        expiresAt = now + UndoWindowPolicy.windowMs(
          settingDao.getSettingSync(SettingKeys.UNDO_WINDOW_SECONDS)?.value
        ),
      )
    )
    return mutationId
  }

  private suspend fun writeActiveBoardSelection(boardName: String, planBoardId: String) {
    settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_BOARD, boardName))
    settingDao.insertSetting(SystemSetting(SettingKeys.ACTIVE_PLAN_BOARD_ID, planBoardId))
  }

  private suspend fun ensurePlanBoard(name: String, columns: List<String>): PlanBoard {
    val existing = planDao.getBoardByName(name)
    if (existing != null) {
      val active =
        if (existing.archivedAt == null) existing
        else existing.copy(archivedAt = null, updatedAt = System.currentTimeMillis())
      if (active !== existing) planDao.updateBoard(active)
      columns.forEach { ensurePlanColumn(active.id, it) }
      return active
    }
    return insertPlanBoard(name, columns)
  }

  private suspend fun insertPlanBoard(name: String, columns: List<String>): PlanBoard {
    val now = System.currentTimeMillis()
    val nameKey = availableBoardNameKey(name)
    val board =
      PlanBoard(
        id = availableBoardId(name, nameKey),
        name = name,
        nameKey = nameKey,
        rank = planDao.maxBoardRank() + PLAN_RANK_GAP,
        isDefault = name == SettingKeys.DEFAULT_BOARD,
        createdAt = now,
        updatedAt = now,
      )
    planDao.insertBoard(board)
    columns.forEach { insertPlanColumn(board.id, it) }
    return board
  }

  private suspend fun ensurePlanColumn(boardId: String, name: String): PlanColumn {
    val existing = planDao.getColumnByName(boardId, name)
    if (existing != null) {
      val active =
        if (existing.archivedAt == null) existing
        else existing.copy(archivedAt = null, updatedAt = System.currentTimeMillis())
      if (active !== existing) planDao.updateColumn(active)
      return active
    }
    return insertPlanColumn(boardId, name)
  }

  private suspend fun insertPlanColumn(boardId: String, name: String): PlanColumn {
    val now = System.currentTimeMillis()
    val nameKey = availableColumnNameKey(boardId, name)
    val column =
      PlanColumn(
        id = availableColumnId(boardId, name, nameKey),
        boardId = boardId,
        name = name,
        nameKey = nameKey,
        rank = planDao.maxColumnRank(boardId) + PLAN_RANK_GAP,
        createdAt = now,
        updatedAt = now,
      )
    planDao.insertColumn(column)
    return column
  }

  private suspend fun availableBoardNameKey(
    name: String,
    excludingBoardId: String? = null,
  ): String {
    val base = LegacyPlanCatalogBuilder.nameKey(name)
    var candidate = base
    var owner = planDao.getBoardByNameKey(candidate)
    if (owner == null || owner.id == excludingBoardId) return candidate
    candidate = "$base#legacy-${utf16Hex(name)}"
    owner = planDao.getBoardByNameKey(candidate)
    while (owner != null && owner.id != excludingBoardId) {
      candidate += "#"
      owner = planDao.getBoardByNameKey(candidate)
    }
    return candidate
  }

  private suspend fun availableColumnNameKey(
    boardId: String,
    name: String,
    excludingColumnId: String? = null,
  ): String {
    val base = LegacyPlanCatalogBuilder.nameKey(name)
    var candidate = base
    var owner = planDao.getColumnByNameKey(boardId, candidate)
    if (owner == null || owner.id == excludingColumnId) return candidate
    candidate = "$base#legacy-${utf16Hex(name)}"
    owner = planDao.getColumnByNameKey(boardId, candidate)
    while (owner != null && owner.id != excludingColumnId) {
      candidate += "#"
      owner = planDao.getColumnByNameKey(boardId, candidate)
    }
    return candidate
  }

  private suspend fun availableBoardId(name: String, nameKey: String): String {
    var salt = ""
    var candidate = LegacyPlanCatalogBuilder.stableId("legacy-board:$nameKey")
    while (planDao.getBoard(candidate) != null) {
      salt += "#"
      candidate =
        LegacyPlanCatalogBuilder.stableId(
          "legacy-board:$nameKey#bridge-${utf16Hex(name)}$salt"
        )
    }
    return candidate
  }

  private suspend fun availableColumnId(boardId: String, name: String, nameKey: String): String {
    var salt = ""
    var candidate = LegacyPlanCatalogBuilder.stableId("legacy-column:$boardId:$nameKey")
    while (planDao.getColumn(candidate) != null) {
      salt += "#"
      candidate =
        LegacyPlanCatalogBuilder.stableId(
          "legacy-column:$boardId:$nameKey#bridge-${utf16Hex(name)}$salt"
        )
    }
    return candidate
  }

  private fun utf16Hex(value: String): String =
    buildString(value.length * 4) {
      value.forEach { character -> append(character.code.toString(16).padStart(4, '0')) }
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
    /** Serializes every source reconciliation and direct event mutation process-wide. */
    private val SCHEDULE_MUTEX = Mutex()
    private val DEVICE_SOURCES =
      listOf(EventSource.GOOGLE, EventSource.SAMSUNG, EventSource.DEVICE)
    private const val BRIEF_RETENTION_MS = 60L * 24 * 60 * 60 * 1000
    private const val PLAN_RANK_GAP = 1_000_000L

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
