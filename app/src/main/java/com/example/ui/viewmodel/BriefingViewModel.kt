package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.ScheduleAnalysis
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.receiver.AlarmScheduler
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How much of a board is shown at once. */
enum class BoardScope(val label: String) {
  Day("Selected day"),
  Week("Next 7 days"),
  All("Everything"),
}

/** A single, complete description of an event the user is creating or editing. */
data class EventDraft(
  val id: String? = null,
  val title: String = "",
  val description: String = "",
  val startMs: Long,
  val endMs: Long,
  val isUrgent: Boolean = false,
  val isDeadline: Boolean = false,
  val board: String = SettingKeys.DEFAULT_BOARD,
  val column: String = SettingKeys.DEFAULT_COLUMNS.first(),
)

data class BriefUiState(
  val markdown: String = "",
  val isLoading: Boolean = false,
  val isStale: Boolean = false,
  val isFallback: Boolean = false,
  val note: String? = null,
  val generatedAt: Long = 0L,
  val signature: String = "",
) {
  val hasContent: Boolean
    get() = markdown.isNotBlank()
}

@OptIn(ExperimentalCoroutinesApi::class)
class BriefingViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = BriefingRepository(application)
  private val appContext = application.applicationContext

  // ---- Selection ----------------------------------------------------------

  private val _selectedDay =
    MutableStateFlow(ScheduleAnalysis.startOfDay(System.currentTimeMillis()))
  val selectedDay: StateFlow<Long> = _selectedDay.asStateFlow()

  private val _isSyncing = MutableStateFlow(false)
  val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

  private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val messages: SharedFlow<String> = _messages.asSharedFlow()

  private val _brief = MutableStateFlow(BriefUiState())
  val brief: StateFlow<BriefUiState> = _brief.asStateFlow()

  /** Set when a sync found the calendar switched on but the permission missing. */
  private val _needsCalendarPermission = MutableStateFlow(false)
  val needsCalendarPermission: StateFlow<Boolean> = _needsCalendarPermission.asStateFlow()

  // ---- Events -------------------------------------------------------------

  val selectedDayEvents: StateFlow<List<BriefingEvent>> =
    _selectedDay
      .flatMapLatest { day ->
        val bounds = ScheduleAnalysis.dayBounds(day)
        repository.eventsInRange(bounds.first, bounds.last)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  val weekEvents: StateFlow<List<BriefingEvent>> =
    _selectedDay
      .flatMapLatest { day ->
        repository.eventsInRange(
          ScheduleAnalysis.startOfDay(day),
          ScheduleAnalysis.startOfDayOffset(day, 7) - 1,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  /** Days offered by the date strip: a week back, three weeks ahead of today. */
  val stripDays: StateFlow<List<Long>> =
    _selectedDay
      .map { day ->
        val today = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
        val aroundToday = (STRIP_BACK..STRIP_FORWARD).map {
          ScheduleAnalysis.startOfDayOffset(today, it)
        }
        // Jumping far out of range re-centres the strip rather than stranding it.
        if (aroundToday.contains(day)) aroundToday
        else (STRIP_BACK..STRIP_FORWARD).map { ScheduleAnalysis.startOfDayOffset(day, it) }
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT),
        (STRIP_BACK..STRIP_FORWARD).map {
          ScheduleAnalysis.startOfDayOffset(System.currentTimeMillis(), it)
        },
      )

  /** Which of [stripDays] have anything on them, for the dots under each date. */
  val daysWithEvents: StateFlow<Set<Long>> =
    stripDays
      .flatMapLatest { days ->
        if (days.isEmpty()) flowOf(emptySet())
        else
          repository.eventsInRange(days.first(), days.last() + ScheduleAnalysis.DAY_MS - 1).map {
            events ->
            events.map { ScheduleAnalysis.startOfDay(it.startTime) }.toSet()
          }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptySet())

  // ---- Settings -----------------------------------------------------------

  private fun stringSetting(key: String, default: String, eager: Boolean = false) =
    repository
      .settingFlow(key)
      .map { it?.value?.takeIf { value -> value.isNotEmpty() } ?: default }
      .stateIn(
        viewModelScope,
        if (eager) SharingStarted.Eagerly else SharingStarted.WhileSubscribed(STOP_TIMEOUT),
        default,
      )

  private fun boolSetting(key: String, default: Boolean) =
    repository
      .settingFlow(key)
      .map { it?.value?.toBooleanStrictOrNull() ?: default }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), default)

  private fun intSetting(key: String, default: Int) =
    repository
      .settingFlow(key)
      .map { it?.value?.toIntOrNull() ?: default }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), default)

  val profileName = stringSetting(SettingKeys.PROFILE_NAME, "")
  val themeAccent = stringSetting(SettingKeys.THEME_ACCENT, "purple", eager = true)
  val themeMode = stringSetting(SettingKeys.THEME_MODE, SettingKeys.THEME_MODE_SYSTEM, eager = true)

  val notionToken = stringSetting(SettingKeys.NOTION_TOKEN, "")
  val notionDatabaseId = stringSetting(SettingKeys.NOTION_DB_ID, "")
  val notionEnabled = boolSetting(SettingKeys.NOTION_ENABLED, false)
  val deviceCalendarEnabled = boolSetting(SettingKeys.DEVICE_CALENDAR_ENABLED, true)

  val dailyBriefEnabled = boolSetting(SettingKeys.DAILY_BRIEF_ENABLED, true)
  val briefHour = intSetting(SettingKeys.BRIEF_HOUR, 8)
  val briefMinute = intSetting(SettingKeys.BRIEF_MINUTE, 0)
  val remindersEnabled = boolSetting(SettingKeys.REMINDERS_ENABLED, true)
  val reminderLeadMinutes = intSetting(SettingKeys.REMINDER_LEAD_MINUTES, 30)

  val geminiModel = stringSetting(SettingKeys.GEMINI_MODEL, "")
  val activeGeminiKeyName =
    stringSetting(SettingKeys.ACTIVE_GEMINI_KEY, SettingKeys.DEFAULT_GEMINI_KEY_NAME)
  val geminiKeys: StateFlow<List<Pair<String, String>>> =
    repository
      .settingFlow(SettingKeys.GEMINI_KEYS)
      .map { BriefingRepository.parseGeminiKeys(it?.value) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  val lastSyncAt = repository
    .settingFlow(SettingKeys.LAST_SYNC_AT)
    .map { it?.value?.toLongOrNull() ?: 0L }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0L)

  // ---- Boards -------------------------------------------------------------

  val activeBoard = stringSetting(SettingKeys.ACTIVE_BOARD, SettingKeys.DEFAULT_BOARD, eager = true)

  val boards: StateFlow<List<String>> =
    repository
      .settingFlow(SettingKeys.BOARDS)
      .map { setting -> SettingKeys.decodeList(setting?.value) ?: listOf(SettingKeys.DEFAULT_BOARD) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(SettingKeys.DEFAULT_BOARD))

  val boardColumns: StateFlow<List<String>> =
    activeBoard
      .flatMapLatest { board ->
        repository.settingFlow(SettingKeys.columnsForBoard(board)).map { setting ->
          SettingKeys.decodeList(setting?.value) ?: SettingKeys.DEFAULT_COLUMNS
        }
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, SettingKeys.DEFAULT_COLUMNS)

  private val _boardScope = MutableStateFlow(BoardScope.Week)
  val boardScope: StateFlow<BoardScope> = _boardScope.asStateFlow()

  val boardEvents: StateFlow<List<BriefingEvent>> =
    combine(activeBoard, _boardScope, _selectedDay) { board, scope, day -> Triple(board, scope, day) }
      .flatMapLatest { (board, scope, day) ->
        repository.eventsForBoard(board).map { events -> events.filter { inScope(it, scope, day) } }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  private fun inScope(event: BriefingEvent, scope: BoardScope, day: Long): Boolean =
    when (scope) {
      BoardScope.All -> true
      BoardScope.Day -> {
        val bounds = ScheduleAnalysis.dayBounds(day)
        event.startTime <= bounds.last && event.endTime >= bounds.first
      }
      BoardScope.Week -> {
        val start = ScheduleAnalysis.startOfDay(day)
        val end = ScheduleAnalysis.startOfDayOffset(day, 7) - 1
        event.startTime <= end && event.endTime >= start
      }
    }

  init {
    // Keep the brief's staleness marker in step with the schedule it describes,
    // without silently spending API calls on every edit.
    viewModelScope.launch {
      selectedDayEvents.collect { events ->
        val signature = ScheduleAnalysis.signature(events)
        _brief.update { state ->
          if (!state.hasContent) state else state.copy(isStale = state.signature != signature)
        }
      }
    }
    viewModelScope.launch { _selectedDay.collect { day -> loadCachedBrief(day) } }
    viewModelScope.launch { autoSyncIfStale() }
  }

  // ---- Day selection ------------------------------------------------------

  fun selectDay(dayStartMs: Long) {
    _selectedDay.value = ScheduleAnalysis.startOfDay(dayStartMs)
  }

  fun selectToday() = selectDay(System.currentTimeMillis())

  fun shiftDay(days: Int) {
    _selectedDay.value = ScheduleAnalysis.startOfDayOffset(_selectedDay.value, days)
  }

  // ---- Sync ---------------------------------------------------------------

  /** Skips the network entirely if the last sync is recent, so launches stay cheap. */
  private suspend fun autoSyncIfStale() {
    val last = repository.readSetting(SettingKeys.LAST_SYNC_AT)?.toLongOrNull() ?: 0L
    if (System.currentTimeMillis() - last > AUTO_SYNC_INTERVAL_MS) {
      sync(announce = false)
    }
  }

  fun sync(announce: Boolean = true) {
    if (_isSyncing.value) return
    viewModelScope.launch {
      _isSyncing.value = true
      try {
        val now = System.currentTimeMillis()
        val outcome =
          repository.syncSchedules(
            ScheduleAnalysis.startOfDayOffset(now, -SYNC_DAYS_BACK),
            ScheduleAnalysis.startOfDayOffset(now, SYNC_DAYS_FORWARD),
          )
        _needsCalendarPermission.value = outcome.calendarPermissionMissing

        refreshAllReminders()
        loadCachedBrief(_selectedDay.value)

        when {
          outcome.warnings.isNotEmpty() -> _messages.tryEmit(outcome.warnings.first())
          outcome.calendarPermissionMissing ->
            _messages.tryEmit("Calendar access is off — grant it to pull in your events")
          announce -> _messages.tryEmit("Schedule up to date")
        }
      } finally {
        _isSyncing.value = false
      }
    }
  }

  // ---- Brief --------------------------------------------------------------

  private suspend fun loadCachedBrief(day: Long) {
    val bounds = ScheduleAnalysis.dayBounds(day)
    val events = repository.eventsInRangeOnce(bounds.first, bounds.last)
    val cached = repository.cachedBriefing(day, events)
    _brief.value =
      if (cached == null) BriefUiState()
      else
        BriefUiState(
          markdown = cached.markdown,
          generatedAt = cached.generatedAt,
          signature = cached.signature,
        )
  }

  fun generateBrief() {
    if (_brief.value.isLoading) return
    viewModelScope.launch {
      _brief.update { it.copy(isLoading = true) }
      val day = _selectedDay.value
      val bounds = ScheduleAnalysis.dayBounds(day)
      val events = repository.eventsInRangeOnce(bounds.first, bounds.last)
      val snapshot = repository.briefingFor(day, events, forceRefresh = true)
      _brief.value =
        BriefUiState(
          markdown = snapshot.markdown,
          generatedAt = snapshot.generatedAt,
          signature = snapshot.signature,
          isFallback = snapshot.isFallback,
          note = snapshot.note,
          isStale = false,
        )
    }
  }

  // ---- Event editing ------------------------------------------------------

  fun newDraftFor(dayMs: Long = _selectedDay.value): EventDraft {
    // Anchored to the day the user is looking at, not to "now" — creating an
    // event while browsing next Tuesday used to land it on today.
    val now = System.currentTimeMillis()
    val start =
      if (ScheduleAnalysis.isSameDay(dayMs, now)) roundUpToQuarterHour(now)
      else ScheduleAnalysis.startOfDay(dayMs) + 9 * 60 * 60 * 1000L
    return EventDraft(
      startMs = start,
      endMs = start + 60 * 60 * 1000L,
      board = activeBoard.value,
      column = boardColumns.value.firstOrNull() ?: SettingKeys.DEFAULT_COLUMNS.first(),
    )
  }

  fun draftFrom(event: BriefingEvent) =
    EventDraft(
      id = event.id,
      title = event.title,
      description = event.description.orEmpty(),
      startMs = event.startTime,
      endMs = event.endTime,
      isUrgent = event.isUrgent,
      isDeadline = event.isDeadline,
      board = event.kanbanBoard,
      column = event.kanbanStatus,
    )

  fun saveEvent(draft: EventDraft) {
    val title = draft.title.trim()
    if (title.isEmpty()) return
    viewModelScope.launch {
      val existing = draft.id?.let { id -> repository.eventById(id) }
      val end = if (draft.endMs > draft.startMs) draft.endMs else draft.startMs + 30 * 60 * 1000L
      val event =
        existing?.copy(
          title = title,
          description = draft.description.trim().takeIf { it.isNotEmpty() },
          startTime = draft.startMs,
          endTime = end,
          isUrgent = draft.isUrgent,
          isDeadline = draft.isDeadline,
          kanbanStatus = draft.column,
          kanbanBoard = draft.board,
        )
          ?: BriefingEvent(
            id = "manual_${UUID.randomUUID()}",
            title = title,
            startTime = draft.startMs,
            endTime = end,
            source = EventSource.MANUAL,
            description = draft.description.trim().takeIf { it.isNotEmpty() },
            isDeadline = draft.isDeadline,
            isUrgent = draft.isUrgent,
            kanbanStatus = draft.column,
            kanbanBoard = draft.board,
            userEdited = true,
          )

      repository.upsertEvent(event)
      rescheduleReminderFor(event)
      if (!ScheduleAnalysis.isSameDay(event.startTime, _selectedDay.value)) {
        selectDay(event.startTime)
      }
    }
  }

  fun deleteEventById(id: String) {
    viewModelScope.launch {
      repository.eventById(id)?.let { deleteEvent(it) }
        ?: run {
          repository.deleteEvent(id)
          AlarmScheduler.cancelEventReminder(appContext, id)
        }
    }
  }

  fun deleteEvent(event: BriefingEvent) {
    viewModelScope.launch {
      repository.deleteEvent(event.id)
      AlarmScheduler.cancelEventReminder(appContext, event.id)
      _messages.tryEmit("Deleted \"${event.title}\"")
    }
  }

  fun moveEvent(event: BriefingEvent, column: String) {
    viewModelScope.launch {
      repository.upsertEvent(event.copy(kanbanStatus = column), markUserEdited = false)
    }
  }

  // ---- Boards -------------------------------------------------------------

  fun setBoardScope(scope: BoardScope) {
    _boardScope.value = scope
  }

  fun selectBoard(name: String) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.ACTIVE_BOARD, name) }
  }

  fun addBoard(rawName: String) {
    val name = rawName.trim()
    if (name.isEmpty()) return
    viewModelScope.launch {
      val current = persistedBoards()
      if (current.any { it.equals(name, ignoreCase = true) }) {
        _messages.tryEmit("A board called \"$name\" already exists")
        return@launch
      }
      repository.writeSetting(SettingKeys.BOARDS, SettingKeys.encodeList(current + name))
      repository.writeSetting(
        SettingKeys.columnsForBoard(name),
        SettingKeys.encodeList(SettingKeys.DEFAULT_COLUMNS),
      )
      repository.writeSetting(SettingKeys.ACTIVE_BOARD, name)
    }
  }

  fun renameBoard(oldName: String, rawNewName: String) {
    val newName = rawNewName.trim()
    if (newName.isEmpty() || newName == oldName || oldName == SettingKeys.DEFAULT_BOARD) return
    viewModelScope.launch {
      val current = persistedBoards()
      if (current.any { it.equals(newName, ignoreCase = true) }) {
        _messages.tryEmit("A board called \"$newName\" already exists")
        return@launch
      }
      repository.writeSetting(
        SettingKeys.BOARDS,
        SettingKeys.encodeList(current.map { if (it == oldName) newName else it }),
      )
      val columns =
        repository.readSetting(SettingKeys.columnsForBoard(oldName))
          ?: SettingKeys.encodeList(SettingKeys.DEFAULT_COLUMNS)
      repository.writeSetting(SettingKeys.columnsForBoard(newName), columns)
      repository.deleteSetting(SettingKeys.columnsForBoard(oldName))
      // Applies to every event on the board, not just the visible day.
      repository.moveEventsBetweenBoards(oldName, newName)
      if (repository.readSetting(SettingKeys.ACTIVE_BOARD) == oldName) {
        repository.writeSetting(SettingKeys.ACTIVE_BOARD, newName)
      }
    }
  }

  fun deleteBoard(name: String) {
    if (name == SettingKeys.DEFAULT_BOARD) return
    viewModelScope.launch {
      val remaining =
        persistedBoards().filterNot { it == name }.ifEmpty { listOf(SettingKeys.DEFAULT_BOARD) }
      repository.writeSetting(SettingKeys.BOARDS, SettingKeys.encodeList(remaining))
      repository.deleteSetting(SettingKeys.columnsForBoard(name))
      val fallback = remaining.first()
      repository.moveEventsBetweenBoards(name, fallback)
      repository.writeSetting(SettingKeys.ACTIVE_BOARD, fallback)
      _messages.tryEmit("Moved everything from \"$name\" to \"$fallback\"")
    }
  }

  fun addColumn(rawName: String) {
    val name = rawName.trim()
    if (name.isEmpty()) return
    viewModelScope.launch {
      val board = persistedActiveBoard()
      val current = persistedColumns(board)
      if (current.any { it.equals(name, ignoreCase = true) }) {
        _messages.tryEmit("That column already exists")
        return@launch
      }
      repository.writeSetting(SettingKeys.columnsForBoard(board), SettingKeys.encodeList(current + name))
    }
  }

  fun renameColumn(oldName: String, rawNewName: String) {
    val newName = rawNewName.trim()
    if (newName.isEmpty() || newName == oldName) return
    viewModelScope.launch {
      val board = persistedActiveBoard()
      val current = persistedColumns(board)
      if (current.any { it.equals(newName, ignoreCase = true) }) {
        _messages.tryEmit("That column already exists")
        return@launch
      }
      repository.writeSetting(
        SettingKeys.columnsForBoard(board),
        SettingKeys.encodeList(current.map { if (it == oldName) newName else it }),
      )
      repository.moveEventsBetweenColumns(board, oldName, newName)
    }
  }

  fun deleteColumn(name: String) {
    viewModelScope.launch {
      val board = persistedActiveBoard()
      val remaining = persistedColumns(board).filterNot { it == name }
      if (remaining.isEmpty()) {
        _messages.tryEmit("A board needs at least one column")
        return@launch
      }
      repository.writeSetting(SettingKeys.columnsForBoard(board), SettingKeys.encodeList(remaining))
      repository.moveEventsBetweenColumns(board, name, remaining.first())
    }
  }

  // ---- Integrations & preferences ----------------------------------------

  fun saveNotionSettings(token: String, databaseId: String, enabled: Boolean) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.NOTION_TOKEN, token.trim())
      repository.writeSetting(SettingKeys.NOTION_DB_ID, databaseId.trim())
      repository.writeSetting(SettingKeys.NOTION_ENABLED, enabled.toString())
      sync(announce = false)
    }
  }

  fun setDeviceCalendarEnabled(enabled: Boolean) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.DEVICE_CALENDAR_ENABLED, enabled.toString())
      if (!enabled) _needsCalendarPermission.value = false
      sync(announce = false)
    }
  }

  fun setDailyBriefEnabled(enabled: Boolean) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.DAILY_BRIEF_ENABLED, enabled.toString())
      if (enabled) {
        AlarmScheduler.scheduleDailyBrief(
          appContext,
          repository.readSetting(SettingKeys.BRIEF_HOUR)?.toIntOrNull() ?: 8,
          repository.readSetting(SettingKeys.BRIEF_MINUTE)?.toIntOrNull() ?: 0,
        )
      } else {
        AlarmScheduler.cancelDailyBrief(appContext)
      }
    }
  }

  fun setBriefTime(hour: Int, minute: Int) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.BRIEF_HOUR, hour.coerceIn(0, 23).toString())
      repository.writeSetting(SettingKeys.BRIEF_MINUTE, minute.coerceIn(0, 59).toString())
      val enabled =
        repository.readSetting(SettingKeys.DAILY_BRIEF_ENABLED)?.toBooleanStrictOrNull() ?: true
      if (enabled) AlarmScheduler.scheduleDailyBrief(appContext, hour, minute)
    }
  }

  fun setRemindersEnabled(enabled: Boolean) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.REMINDERS_ENABLED, enabled.toString())
      refreshAllReminders(forceEnabled = enabled)
    }
  }

  fun setReminderLead(minutes: Int) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.REMINDER_LEAD_MINUTES, minutes.coerceIn(0, 240).toString())
      refreshAllReminders()
    }
  }

  fun setProfileName(name: String) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.PROFILE_NAME, name.trim()) }
  }

  fun setThemeAccent(key: String) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.THEME_ACCENT, key) }
  }

  fun setThemeMode(mode: String) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.THEME_MODE, mode) }
  }

  fun setGeminiModel(model: String) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.GEMINI_MODEL, model.trim()) }
  }

  fun selectGeminiKey(name: String) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.ACTIVE_GEMINI_KEY, name) }
  }

  fun addGeminiKey(rawName: String, rawKey: String) {
    val name = rawName.trim()
    val key = rawKey.trim()
    if (name.isEmpty() || key.isEmpty()) return
    viewModelScope.launch {
      val updated = repository.geminiKeys().filterNot { it.first == name } + (name to key)
      repository.writeSetting(SettingKeys.GEMINI_KEYS, BriefingRepository.encodeGeminiKeys(updated))
      repository.writeSetting(SettingKeys.ACTIVE_GEMINI_KEY, name)
      _messages.tryEmit("Using \"$name\"")
    }
  }

  fun deleteGeminiKey(name: String) {
    viewModelScope.launch {
      val updated = repository.geminiKeys().filterNot { it.first == name }
      repository.writeSetting(SettingKeys.GEMINI_KEYS, BriefingRepository.encodeGeminiKeys(updated))
      if (repository.readSetting(SettingKeys.ACTIVE_GEMINI_KEY) == name) {
        repository.writeSetting(
          SettingKeys.ACTIVE_GEMINI_KEY,
          SettingKeys.DEFAULT_GEMINI_KEY_NAME,
        )
      }
    }
  }

  fun loadSampleDay() {
    viewModelScope.launch {
      repository.loadSampleDay(_selectedDay.value)
      _messages.tryEmit("Added a sample day")
    }
  }

  fun clearSampleData() {
    viewModelScope.launch {
      repository.clearSampleData()
      _messages.tryEmit("Sample data removed")
    }
  }

  /**
   * Lays down the daily-brief alarm on launch. Toggling the setting is not the
   * only way it can be missing — a fresh install has never scheduled one.
   */
  fun ensureAlarmsScheduled() {
    viewModelScope.launch {
      val enabled =
        repository.readSetting(SettingKeys.DAILY_BRIEF_ENABLED)?.toBooleanStrictOrNull() ?: true
      if (enabled) {
        AlarmScheduler.scheduleDailyBrief(
          appContext,
          repository.readSetting(SettingKeys.BRIEF_HOUR)?.toIntOrNull() ?: 8,
          repository.readSetting(SettingKeys.BRIEF_MINUTE)?.toIntOrNull() ?: 0,
        )
      } else {
        AlarmScheduler.cancelDailyBrief(appContext)
      }
    }
  }

  fun onCalendarPermissionGranted() {
    _needsCalendarPermission.value = false
    setDeviceCalendarEnabled(true)
  }

  // ---- Reminders ----------------------------------------------------------

  private suspend fun rescheduleReminderFor(event: BriefingEvent) {
    AlarmScheduler.cancelEventReminder(appContext, event.id)
    val enabled =
      repository.readSetting(SettingKeys.REMINDERS_ENABLED)?.toBooleanStrictOrNull() ?: true
    if (!enabled) return
    AlarmScheduler.replaceEventReminders(
      context = appContext,
      previous = emptyList(),
      events = listOf(event),
      leadMinutes = repository.readSetting(SettingKeys.REMINDER_LEAD_MINUTES)?.toIntOrNull() ?: 30,
      enabled = true,
    )
  }

  private suspend fun refreshAllReminders(forceEnabled: Boolean? = null) {
    val now = System.currentTimeMillis()
    val upcoming = repository.eventsInRangeOnce(now, now + 14 * ScheduleAnalysis.DAY_MS)
    val enabled =
      forceEnabled
        ?: (repository.readSetting(SettingKeys.REMINDERS_ENABLED)?.toBooleanStrictOrNull() ?: true)
    AlarmScheduler.replaceEventReminders(
      context = appContext,
      previous = upcoming,
      events = upcoming,
      leadMinutes = repository.readSetting(SettingKeys.REMINDER_LEAD_MINUTES)?.toIntOrNull() ?: 30,
      enabled = enabled,
    )
  }

  private suspend fun persistedBoards(): List<String> =
    SettingKeys.decodeList(repository.readSetting(SettingKeys.BOARDS)) ?: listOf(SettingKeys.DEFAULT_BOARD)

  private suspend fun persistedActiveBoard(): String =
    repository.readSetting(SettingKeys.ACTIVE_BOARD)?.takeIf { it.isNotBlank() }
      ?: SettingKeys.DEFAULT_BOARD

  private suspend fun persistedColumns(board: String): List<String> =
    SettingKeys.decodeList(repository.readSetting(SettingKeys.columnsForBoard(board)))
      ?: SettingKeys.DEFAULT_COLUMNS

  private fun roundUpToQuarterHour(timeMs: Long): Long {
    val quarter = 15 * 60 * 1000L
    return ((timeMs + quarter - 1) / quarter) * quarter
  }

  companion object {
    private const val STOP_TIMEOUT = 5_000L
    private const val AUTO_SYNC_INTERVAL_MS = 30 * 60 * 1000L
    private const val SYNC_DAYS_BACK = 7
    private const val SYNC_DAYS_FORWARD = 30
    private const val STRIP_BACK = -7
    private const val STRIP_FORWARD = 21
  }
}
