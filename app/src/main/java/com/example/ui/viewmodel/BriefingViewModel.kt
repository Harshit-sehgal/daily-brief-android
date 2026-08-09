package com.example.ui.viewmodel

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.core.ScheduleAnalysis
import com.example.data.api.WriteBack
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.receiver.AlarmScheduler
import com.example.ui.theme.Accents
import com.example.ui.theme.UiDensity
import com.example.receiver.BriefingAndReminderReceiver
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
  val isAllDay: Boolean = false,
  val isUrgent: Boolean = false,
  val isDeadline: Boolean = false,
  val board: String = SettingKeys.DEFAULT_BOARD,
  val column: String = SettingKeys.DEFAULT_COLUMNS.first(),
)

/** Sections of the Today workspace. Each can be hidden or folded away. */
enum class TodaySection(val key: String, val label: String, val blurb: String) {
  Summary("summary", "Summary", "Counts for the day"),
  Conflicts("conflicts", "Conflicts", "Overlapping events"),
  Brief("brief", "Brief", "The written summary"),
  Agenda("agenda", "Agenda", "Every event, in order");

  companion object {
    fun byKey(key: String?) = entries.firstOrNull { it.key == key }

    /** A deliberately quiet starting point; the brief is opt-in. */
    val Defaults = listOf(Summary, Conflicts, Agenda)
  }
}

/**
 * The blocks the landing page can show.
 *
 * The page is meant to be read in two seconds, so the default set is small and
 * everything else is opt-in — the point of a start page is what it leaves out.
 */
enum class HomeCard(val key: String, val label: String, val blurb: String) {
  Focus("focus", "Now", "What you are in, or what is next"),
  Progress("progress", "Day", "How much of the day is behind you"),
  Actions("actions", "Quick actions", "New event, timeline, search"),
  UpNext("upnext", "Up next", "The next few things"),
  Attention("attention", "Needs attention", "Clashes and deadlines"),
  Board("board", "Board", "Where your work stands");

  companion object {
    fun byKey(key: String?) = entries.firstOrNull { it.key == key }

    val Defaults = listOf(Focus, Progress, UpNext, Attention)
  }
}

/** How the agenda list is broken up. */
enum class AgendaGrouping(val key: String, val label: String) {
  Time("time", "Time"),
  Source("source", "Source"),
  Priority("priority", "Priority");

  companion object {
    fun byKey(key: String?) = entries.firstOrNull { it.key == key } ?: Time
  }
}

data class UiMessage(
  val text: String,
  val actionLabel: String? = null,
  val undoEvent: BriefingEvent? = null,
)

data class NotionCredentialDraft(val token: String, val databaseId: String)

data class GeminiCredentialDraft(val name: String = "", val key: String = "")

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
class BriefingViewModel(application: Application, savedStateHandle: SavedStateHandle) :
  AndroidViewModel(application) {
  private val repository = BriefingRepository(application)
  private val appContext = application.applicationContext

  // ---- Selection ----------------------------------------------------------

  private val _selectedDay =
    savedStateHandle.getMutableStateFlow(
      SELECTED_DAY_KEY,
      ScheduleAnalysis.startOfDay(System.currentTimeMillis()),
    )
  val selectedDay: StateFlow<Long> = _selectedDay.asStateFlow()

  private val _isSyncing = MutableStateFlow(false)
  val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

  private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
  val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

  private val _syncProblem = MutableStateFlow<String?>(null)
  val syncProblem: StateFlow<String?> = _syncProblem.asStateFlow()

  private val _notificationsAvailable =
    MutableStateFlow(NotificationManagerCompat.from(appContext).areNotificationsEnabled())
  val notificationsAvailable: StateFlow<Boolean> = _notificationsAvailable.asStateFlow()

  private val _dailyNotificationsAvailable =
    MutableStateFlow(
      BriefingAndReminderReceiver.canPostToChannel(
        appContext,
        BriefingAndReminderReceiver.CHANNEL_ID_BRIEF,
      )
    )
  val dailyNotificationsAvailable: StateFlow<Boolean> =
    _dailyNotificationsAvailable.asStateFlow()

  private val _reminderNotificationsAvailable =
    MutableStateFlow(
      BriefingAndReminderReceiver.canPostToChannel(
        appContext,
        BriefingAndReminderReceiver.CHANNEL_ID_REMINDER,
      )
    )
  val reminderNotificationsAvailable: StateFlow<Boolean> =
    _reminderNotificationsAvailable.asStateFlow()

  private val syncMutex = Mutex()
  private val eventMutationMutex = Mutex()
  private var briefJob: Job? = null

  private val _brief = MutableStateFlow(BriefUiState())
  val brief: StateFlow<BriefUiState> = _brief.asStateFlow()

  /** Set when a sync found the calendar switched on but the permission missing. */
  private val _needsCalendarPermission = MutableStateFlow(false)
  val needsCalendarPermission: StateFlow<Boolean> = _needsCalendarPermission.asStateFlow()

  private val _notionCredentialDraft = MutableStateFlow<NotionCredentialDraft?>(null)
  val notionCredentialDraft: StateFlow<NotionCredentialDraft?> =
    _notionCredentialDraft.asStateFlow()

  private val _geminiCredentialDraft = MutableStateFlow(GeminiCredentialDraft())
  val geminiCredentialDraft: StateFlow<GeminiCredentialDraft> =
    _geminiCredentialDraft.asStateFlow()

  // ---- Events -------------------------------------------------------------

  val selectedDayEvents: StateFlow<List<BriefingEvent>> =
    _selectedDay
      .flatMapLatest { day ->
        val bounds = ScheduleAnalysis.dayBounds(day)
        repository.eventsInRange(bounds.first, bounds.last + 1)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  /**
   * Today's midnight, re-emitted when the date rolls over.
   *
   * An app left open overnight should wake up on the new day rather than insisting
   * it is still yesterday.
   */
  private val _dayTick: StateFlow<Long> =
    flow {
        while (true) {
          emit(ScheduleAnalysis.startOfDay(System.currentTimeMillis()))
          delay(DAY_TICK_MS)
        }
      }
      .distinctUntilChanged()
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT),
        ScheduleAnalysis.startOfDay(System.currentTimeMillis()),
      )

  /**
   * Today's events, whatever day is selected elsewhere.
   *
   * The landing page is about now, so it must not follow the date strip — browsing
   * to next Tuesday should not change what your start page says you are doing.
   */
  val todayEvents: StateFlow<List<BriefingEvent>> =
    _dayTick
      .flatMapLatest { today ->
        repository.eventsInRange(today, today + ScheduleAnalysis.DAY_MS)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  /** How many days the Week view spans. Declared here because [weekEvents] reads it. */
  val weekSpanDays = intSetting(SettingKeys.WEEK_SPAN_DAYS, 7)

  val weekEvents: StateFlow<List<BriefingEvent>> =
    combine(_selectedDay, weekSpanDays) { day, span -> day to span }
      .flatMapLatest { (day, span) ->
        repository.eventsInRange(
          ScheduleAnalysis.startOfDay(day),
          ScheduleAnalysis.startOfDayOffset(day, span),
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
          repository
            .eventsInRange(days.first(), ScheduleAnalysis.startOfDayOffset(days.last(), 1))
            .map { events ->
              days
                .filter { day ->
                  val nextDay = ScheduleAnalysis.startOfDayOffset(day, 1)
                  events.any { event -> event.startTime < nextDay && event.endTime > day }
                }
                .toSet()
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
  val themeAccent = stringSetting(SettingKeys.THEME_ACCENT, Accents.Default.key, eager = true)
  val themeMode = stringSetting(SettingKeys.THEME_MODE, SettingKeys.THEME_MODE_SYSTEM, eager = true)

  // ---- Workspace layout ---------------------------------------------------

  /** Which screen the app opens on. */
  val homeDestination = stringSetting(SettingKeys.HOME_DESTINATION, "Home", eager = true)

  fun setHomeDestination(name: String) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.HOME_DESTINATION, name) }
  }

  val uiDensity: StateFlow<UiDensity> =
    repository
      .settingFlow(SettingKeys.UI_DENSITY)
      .map { UiDensity.byKey(it?.value) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, UiDensity.Default)

  /** Folded sections, by key. Folding is a view preference, so it persists. */
  val collapsedSections: StateFlow<Set<String>> =
    repository
      .settingFlow(SettingKeys.COLLAPSED_SECTIONS)
      .map { SettingKeys.decodeList(it?.value)?.toSet() ?: emptySet() }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptySet())

  /** Which Today sections are shown, in the order the user arranged them. */
  val todaySections: StateFlow<List<TodaySection>> =
    repository
      .settingFlow(SettingKeys.TODAY_SECTIONS)
      .map { setting ->
        SettingKeys.decodeList(setting?.value)?.mapNotNull(TodaySection::byKey)?.ifEmpty { null }
          ?: TodaySection.Defaults
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT),
        TodaySection.Defaults,
      )

  /** Which landing-page blocks are shown, in the order the user arranged them. */
  val homeCards: StateFlow<List<HomeCard>> =
    repository
      .settingFlow(SettingKeys.HOME_CARDS)
      .map { setting ->
        SettingKeys.decodeList(setting?.value)?.mapNotNull(HomeCard::byKey)?.ifEmpty { null }
          ?: HomeCard.Defaults
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), HomeCard.Defaults)

  fun setHomeCardVisible(card: HomeCard, visible: Boolean) {
    viewModelScope.launch {
      val current =
        SettingKeys.decodeList(repository.readSetting(SettingKeys.HOME_CARDS))
          ?.mapNotNull(HomeCard::byKey)
          ?: HomeCard.Defaults
      val next = if (visible) (current + card).distinct() else current.filterNot { it == card }
      repository.writeSetting(SettingKeys.HOME_CARDS, SettingKeys.encodeList(next.map { it.key }))
    }
  }

  fun moveHomeCard(card: HomeCard, delta: Int) {
    viewModelScope.launch {
      val current =
        (SettingKeys.decodeList(repository.readSetting(SettingKeys.HOME_CARDS))
            ?.mapNotNull(HomeCard::byKey)
            ?: HomeCard.Defaults)
          .toMutableList()
      val from = current.indexOf(card)
      val to = from + delta
      if (from < 0 || to !in current.indices) return@launch
      current.removeAt(from)
      current.add(to, card)
      repository.writeSetting(SettingKeys.HOME_CARDS, SettingKeys.encodeList(current.map { it.key }))
    }
  }

  val agendaGrouping: StateFlow<AgendaGrouping> =
    repository
      .settingFlow(SettingKeys.AGENDA_GROUPING)
      .map { AgendaGrouping.byKey(it?.value) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), AgendaGrouping.Time)

  fun setDensity(density: UiDensity) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.UI_DENSITY, density.key) }
  }

  fun toggleSection(key: String) {
    viewModelScope.launch {
      val current =
        SettingKeys.decodeList(repository.readSetting(SettingKeys.COLLAPSED_SECTIONS))
          ?.toMutableSet() ?: mutableSetOf()
      if (!current.add(key)) current.remove(key)
      repository.writeSetting(SettingKeys.COLLAPSED_SECTIONS, SettingKeys.encodeList(current.toList()))
    }
  }

  fun setSectionVisible(section: TodaySection, visible: Boolean) {
    viewModelScope.launch {
      val current =
        SettingKeys.decodeList(repository.readSetting(SettingKeys.TODAY_SECTIONS))
          ?.mapNotNull(TodaySection::byKey)
          ?: TodaySection.Defaults
      val next =
        if (visible) (current + section).distinct() else current.filterNot { it == section }
      // A workspace with nothing in it is a dead end; keep at least the agenda.
      val safe = next.ifEmpty { listOf(TodaySection.Agenda) }
      repository.writeSetting(SettingKeys.TODAY_SECTIONS, SettingKeys.encodeList(safe.map { it.key }))
    }
  }

  fun moveSection(section: TodaySection, delta: Int) {
    viewModelScope.launch {
      val current =
        (SettingKeys.decodeList(repository.readSetting(SettingKeys.TODAY_SECTIONS))
            ?.mapNotNull(TodaySection::byKey)
            ?: TodaySection.Defaults)
          .toMutableList()
      val from = current.indexOf(section)
      val to = from + delta
      if (from < 0 || to !in current.indices) return@launch
      current.removeAt(from)
      current.add(to, section)
      repository.writeSetting(
        SettingKeys.TODAY_SECTIONS,
        SettingKeys.encodeList(current.map { it.key }),
      )
    }
  }

  fun setAgendaGrouping(grouping: AgendaGrouping) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.AGENDA_GROUPING, grouping.key) }
  }

  fun setWeekSpan(days: Int) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.WEEK_SPAN_DAYS, days.coerceIn(3, 60).toString())
    }
  }

  val notionToken = stringSetting(SettingKeys.NOTION_TOKEN, "")
  val notionDatabaseId = stringSetting(SettingKeys.NOTION_DB_ID, "")
  val notionEnabled = boolSetting(SettingKeys.NOTION_ENABLED, false)
  val deviceCalendarEnabled = boolSetting(SettingKeys.DEVICE_CALENDAR_ENABLED, true)
  val calendarWriteBack = boolSetting(SettingKeys.CALENDAR_WRITE_BACK, true)

  fun setCalendarWriteBack(enabled: Boolean) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.CALENDAR_WRITE_BACK, enabled.toString())
    }
  }

  val dailyBriefEnabled = boolSetting(SettingKeys.DAILY_BRIEF_ENABLED, false)
  val briefHour = intSetting(SettingKeys.BRIEF_HOUR, 8)
  val briefMinute = intSetting(SettingKeys.BRIEF_MINUTE, 0)
  val remindersEnabled = boolSetting(SettingKeys.REMINDERS_ENABLED, false)
  val reminderLeadMinutes = intSetting(SettingKeys.REMINDER_LEAD_MINUTES, 30)
  val notificationPermissionRequested =
    boolSetting(SettingKeys.NOTIFICATION_PERMISSION_REQUESTED, false)

  val geminiModel = stringSetting(SettingKeys.GEMINI_MODEL, "")
  val activeGeminiKeyName = stringSetting(SettingKeys.ACTIVE_GEMINI_KEY, "")
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

  val boardColumnsByBoard: StateFlow<Map<String, List<String>>> =
    boards
      .flatMapLatest { names ->
        if (names.isEmpty()) {
          flowOf(emptyMap())
        } else {
          combine(
            names.map { board ->
              repository.settingFlow(SettingKeys.columnsForBoard(board)).map { setting ->
                board to (SettingKeys.decodeList(setting?.value) ?: SettingKeys.DEFAULT_COLUMNS)
              }
            }
          ) { pairs -> pairs.toMap() }
        }
      }
      .stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        mapOf(SettingKeys.DEFAULT_BOARD to SettingKeys.DEFAULT_COLUMNS),
      )

  private val _boardScope = MutableStateFlow(BoardScope.Week)
  val boardScope: StateFlow<BoardScope> = _boardScope.asStateFlow()

  val boardEvents: StateFlow<List<BriefingEvent>> =
    combine(activeBoard, _boardScope, _selectedDay) { board, scope, day -> Triple(board, scope, day) }
      .flatMapLatest { (board, scope, day) ->
        repository.eventsForBoard(board).map { events -> events.filter { inScope(it, scope, day) } }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  /**
   * Everything on the active board, unscoped by date.
   *
   * The landing page must not inherit a filter the user set on the board screen,
   * or it reports zero work outstanding when the board is simply looking at a
   * different week.
   */
  val activeBoardEvents: StateFlow<List<BriefingEvent>> =
    activeBoard
      .flatMapLatest { board -> repository.eventsForBoard(board) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  private fun inScope(event: BriefingEvent, scope: BoardScope, day: Long): Boolean =
    when (scope) {
      BoardScope.All -> true
      BoardScope.Day -> {
        val bounds = ScheduleAnalysis.dayBounds(day)
        event.startTime < bounds.last + 1 && event.endTime > bounds.first
      }
      BoardScope.Week -> {
        val start = ScheduleAnalysis.startOfDay(day)
        val end = ScheduleAnalysis.startOfDayOffset(day, 7)
        event.startTime < end && event.endTime > start
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
    viewModelScope.launch {
      _selectedDay.collectLatest { day ->
        briefJob?.cancel()
        loadCachedBrief(day)
      }
    }
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
    viewModelScope.launch {
      syncMutex.withLock {
        _isSyncing.value = true
        try {
          val now = System.currentTimeMillis()
          val outcome =
            repository.syncSchedules(
              ScheduleAnalysis.startOfDayOffset(now, -SYNC_DAYS_BACK),
              ScheduleAnalysis.startOfDayOffset(now, SYNC_DAYS_FORWARD),
            )
          _needsCalendarPermission.value = outcome.calendarPermissionMissing

          AlarmScheduler.cancelEventReminders(appContext, outcome.removedEvents)
          refreshAllReminders()
          loadCachedBrief(_selectedDay.value)

          _syncProblem.value =
            when {
              outcome.warnings.isNotEmpty() -> outcome.warnings.joinToString(" · ")
              outcome.calendarPermissionMissing ->
                "Device-calendar updates are paused until calendar access is granted"
              !outcome.completed -> "Sync was incomplete; saved source data was preserved"
              else -> null
            }

          when {
            outcome.warnings.isNotEmpty() -> message(outcome.warnings.first())
            outcome.calendarPermissionMissing ->
              message("Calendar access is off — grant it to pull in your events")
            !outcome.completed -> message("Sync was incomplete; saved source data was preserved")
            announce -> message("Schedule up to date")
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          _syncProblem.value = "Sync failed. Your saved schedule was kept; try again."
          message(_syncProblem.value!!)
        } finally {
          _isSyncing.value = false
        }
      }
    }
  }

  // ---- Brief --------------------------------------------------------------

  private suspend fun loadCachedBrief(day: Long) {
    val bounds = ScheduleAnalysis.dayBounds(day)
    val events = repository.eventsInRangeOnce(bounds.first, bounds.last + 1)
    val cached = repository.cachedBriefing(day, events)
    if (_selectedDay.value != day) return
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
    if (briefJob?.isActive == true) return
    val day = _selectedDay.value
    briefJob = viewModelScope.launch {
      _brief.update { it.copy(isLoading = true) }
      try {
        val bounds = ScheduleAnalysis.dayBounds(day)
        val events = repository.eventsInRangeOnce(bounds.first, bounds.last + 1)
        val snapshot = repository.briefingFor(day, events, forceRefresh = true)
        if (_selectedDay.value == day) {
          // The schedule can change while Gemini is answering. Re-read it
          // before publishing so an old response is never labelled fresh.
          val currentEvents = repository.eventsInRangeOnce(bounds.first, bounds.last + 1)
          val currentSignature = ScheduleAnalysis.signature(currentEvents)
          _brief.value =
            BriefUiState(
              markdown = snapshot.markdown,
              generatedAt = snapshot.generatedAt,
              signature = snapshot.signature,
              isFallback = snapshot.isFallback,
              note = snapshot.note,
              isStale = snapshot.signature != currentSignature,
            )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (_selectedDay.value == day) {
          _brief.update { it.copy(isLoading = false) }
          message("Couldn't write the brief — your schedule is unchanged")
        }
      } finally {
        if (_selectedDay.value == day) _brief.update { it.copy(isLoading = false) }
      }
    }
  }

  // ---- Event editing ------------------------------------------------------

  /** A draft starting at an exact time — used when tapping a slot on the timeline. */
  fun newDraftAt(startMs: Long, durationMinutes: Int = 60): EventDraft =
    EventDraft(
      startMs = startMs,
      endMs = startMs + durationMinutes * 60_000L,
      board = activeBoard.value,
      column = boardColumns.value.firstOrNull() ?: SettingKeys.DEFAULT_COLUMNS.first(),
    )

  /** Moves an existing event to a new start, keeping its length. */
  fun moveEventTo(event: BriefingEvent, newStartMs: Long) {
    val length = (event.endTime - event.startTime).coerceAtLeast(60_000L)
    saveEvent(draftFrom(event).copy(startMs = newStartMs, endMs = newStartMs + length))
  }

  fun newDraftFor(dayMs: Long = _selectedDay.value): EventDraft {
    // Anchored to the day the user is looking at, not to "now" — creating an
    // event while browsing next Tuesday used to land it on today.
    val now = System.currentTimeMillis()
    val start =
      if (ScheduleAnalysis.isSameDay(dayMs, now)) roundUpToQuarterHour(now)
      else ScheduleAnalysis.withTimeOfDay(dayMs, 9, 0)
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
      isAllDay = event.isAllDay,
      isUrgent = event.isUrgent,
      isDeadline = event.isDeadline,
      board = event.kanbanBoard,
      column = event.kanbanStatus,
    )

  fun saveEvent(draft: EventDraft, onComplete: (Boolean) -> Unit = {}) {
    val title = draft.title.trim()
    if (title.isEmpty()) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      eventMutationMutex.withLock {
        try {
          val existing = draft.id?.let { id -> repository.eventById(id) }
          val validBoards = persistedBoards()
          val fallbackBoard =
            persistedActiveBoard().takeIf { it in validBoards }
              ?: validBoards.firstOrNull()
              ?: SettingKeys.DEFAULT_BOARD
          val board = draft.board.takeIf { it in validBoards } ?: fallbackBoard
          val validColumns = persistedColumns(board)
          val column = draft.column.takeIf { it in validColumns } ?: validColumns.first()
          if (board != draft.board || column != draft.column) {
            message("That board changed while you were editing; the event was moved safely")
          }
          val end =
            if (draft.endMs > draft.startMs) draft.endMs
            else if (draft.isAllDay) ScheduleAnalysis.startOfDayOffset(draft.startMs, 1)
            else draft.startMs + 30 * 60 * 1000L
          val event =
            existing?.copy(
              title = title,
              description = draft.description.trim().takeIf { it.isNotEmpty() },
              startTime = draft.startMs,
              endTime = end,
              isAllDay = draft.isAllDay,
              isUrgent = draft.isUrgent,
              isDeadline = draft.isDeadline,
              kanbanStatus = column,
              kanbanBoard = board,
            )
              ?: BriefingEvent(
                id = "manual_${UUID.randomUUID()}",
                title = title,
                startTime = draft.startMs,
                endTime = end,
                isAllDay = draft.isAllDay,
                source = EventSource.MANUAL,
                description = draft.description.trim().takeIf { it.isNotEmpty() },
                isDeadline = draft.isDeadline,
                isUrgent = draft.isUrgent,
                kanbanStatus = column,
                kanbanBoard = board,
                userEdited = true,
              )

          repository.upsertEvent(event)
          reportWriteBack(repository.writeEventBack(event), verb = "Saved")
          try {
            rescheduleReminderFor(event)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            message("Event saved, but reminders could not be refreshed")
          }
          if (!ScheduleAnalysis.isSameDay(event.startTime, _selectedDay.value)) {
            selectDay(event.startTime)
          }
          onComplete(true)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          message("Couldn't save the event — your draft is still open")
          onComplete(false)
        }
      }
    }
  }

  fun deleteEventById(id: String, onComplete: (Boolean) -> Unit = {}) {
    viewModelScope.launch {
      eventMutationMutex.withLock {
        try {
          val event = repository.eventById(id)
          val calendarResult =
            event?.let { repository.deleteEventFromCalendar(it) } ?: WriteBack.NotApplicable
          repository.deleteEvent(id)
          reportWriteBack(calendarResult, verb = "Removed")
          AlarmScheduler.cancelEventReminder(appContext, id)
          if (event != null) {
            _messages.emit(
              UiMessage(
                text = "Deleted \"${event.title}\"",
                actionLabel = "Undo",
                undoEvent = event,
              )
            )
          }
          onComplete(true)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          message("Couldn't delete the event — nothing was changed")
          onComplete(false)
        }
      }
    }
  }

  fun restoreDeletedEvent(event: BriefingEvent) {
    viewModelScope.launch {
      repository.upsertEvent(event, markUserEdited = false)
      rescheduleReminderFor(event)
      message("Restored \"${event.title}\"")
    }
  }

  fun moveEvent(event: BriefingEvent, column: String) {
    viewModelScope.launch {
      repository.upsertEvent(event.copy(kanbanStatus = column), markUserEdited = false)
    }
  }

  // ---- Boards -------------------------------------------------------------

  /**
   * Tells the user whether an edit reached their calendar. Silence would leave
   * them guessing whether a change they made in here actually landed there.
   */
  private suspend fun reportWriteBack(result: WriteBack, verb: String) {
    when (result) {
      is WriteBack.Skipped -> message(result.reason)
      is WriteBack.Failed -> message("$verb here, but ${result.reason.replaceFirstChar { it.lowercase() }}")
      WriteBack.Success -> message("$verb, and updated in your calendar")
      WriteBack.NotApplicable -> Unit
    }
  }

  fun setBoardScope(scope: BoardScope) {
    _boardScope.value = scope
  }

  fun selectBoard(name: String) {
    viewModelScope.launch { repository.writeSetting(SettingKeys.ACTIVE_BOARD, name) }
  }

  fun addBoard(rawName: String, onComplete: (Boolean) -> Unit = {}) {
    val name = rawName.trim()
    if (name.isEmpty()) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      val success = repository.createBoard(name)
      if (!success) {
        message("A board called \"$name\" already exists")
      }
      onComplete(success)
    }
  }

  fun renameBoard(
    oldName: String,
    rawNewName: String,
    onComplete: (Boolean) -> Unit = {},
  ) {
    val newName = rawNewName.trim()
    if (newName.isEmpty() || newName == oldName || oldName == SettingKeys.DEFAULT_BOARD) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      val success = repository.renameBoard(oldName, newName)
      if (!success) {
        message("A board called \"$newName\" already exists")
      }
      onComplete(success)
    }
  }

  fun deleteBoard(name: String) {
    if (name == SettingKeys.DEFAULT_BOARD) return
    viewModelScope.launch {
      repository.deleteBoard(name)?.let { fallback ->
        message("Moved everything from \"$name\" to \"$fallback\"")
      }
    }
  }

  fun addColumn(rawName: String, onComplete: (Boolean) -> Unit = {}) {
    val name = rawName.trim()
    if (name.isEmpty()) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      val success = repository.createColumn(name)
      if (!success) {
        message("That column already exists")
      }
      onComplete(success)
    }
  }

  fun renameColumn(
    oldName: String,
    rawNewName: String,
    onComplete: (Boolean) -> Unit = {},
  ) {
    val newName = rawNewName.trim()
    if (newName.isEmpty() || newName == oldName) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      val success = repository.renameColumn(oldName, newName)
      if (!success) {
        message("That column already exists")
      }
      onComplete(success)
    }
  }

  fun deleteColumn(name: String) {
    viewModelScope.launch {
      if (!repository.deleteColumn(name)) {
        message("A board needs at least one column")
      }
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

  fun updateNotionCredentialDraft(token: String, databaseId: String) {
    _notionCredentialDraft.value = NotionCredentialDraft(token, databaseId)
  }

  fun updateGeminiCredentialDraft(name: String, key: String) {
    _geminiCredentialDraft.value = GeminiCredentialDraft(name, key)
  }

  fun setDeviceCalendarEnabled(enabled: Boolean) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.DEVICE_CALENDAR_ENABLED, enabled.toString())
      if (!enabled) _needsCalendarPermission.value = false
      sync(announce = false)
    }
  }

  fun setDailyBriefEnabled(enabled: Boolean) {
    if (enabled && !_dailyNotificationsAvailable.value) {
      message("Allow notifications before turning on the morning brief")
      return
    }
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
        repository.readSetting(SettingKeys.DAILY_BRIEF_ENABLED)?.toBooleanStrictOrNull() ?: false
      if (enabled && _dailyNotificationsAvailable.value) {
        AlarmScheduler.scheduleDailyBrief(appContext, hour, minute)
      }
    }
  }

  fun setRemindersEnabled(enabled: Boolean) {
    if (enabled && !_reminderNotificationsAvailable.value) {
      message("Allow notifications before turning on event reminders")
      return
    }
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
      _geminiCredentialDraft.value = GeminiCredentialDraft()
      message("Using \"$name\"")
    }
  }

  fun deleteGeminiKey(name: String) {
    viewModelScope.launch {
      val updated = repository.geminiKeys().filterNot { it.first == name }
      repository.writeSetting(SettingKeys.GEMINI_KEYS, BriefingRepository.encodeGeminiKeys(updated))
      if (repository.readSetting(SettingKeys.ACTIVE_GEMINI_KEY) == name) {
        repository.deleteSetting(SettingKeys.ACTIVE_GEMINI_KEY)
      }
    }
  }

  fun loadSampleDay() {
    viewModelScope.launch {
      repository.loadSampleDay(_selectedDay.value)
      refreshAllReminders()
      message("Added a sample day")
    }
  }

  fun clearSampleData() {
    viewModelScope.launch {
      repository.clearSampleData()
      refreshAllReminders()
      message("Sample data removed")
    }
  }

  /**
   * Lays down the daily-brief alarm on launch. Toggling the setting is not the
   * only way it can be missing — a fresh install has never scheduled one.
   */
  fun ensureAlarmsScheduled() {
    viewModelScope.launch {
      val enabled =
        repository.readSetting(SettingKeys.DAILY_BRIEF_ENABLED)?.toBooleanStrictOrNull() ?: false
      if (enabled && _dailyNotificationsAvailable.value) {
        AlarmScheduler.scheduleDailyBrief(
          appContext,
          repository.readSetting(SettingKeys.BRIEF_HOUR)?.toIntOrNull() ?: 8,
          repository.readSetting(SettingKeys.BRIEF_MINUTE)?.toIntOrNull() ?: 0,
        )
      } else {
        AlarmScheduler.cancelDailyBrief(appContext)
      }
      refreshAllReminders()
    }
  }

  /** Keeps feature state honest when permission changes in or outside the app. */
  fun onNotificationPermissionChecked(available: Boolean) {
    val dailyAvailable =
      available &&
        BriefingAndReminderReceiver.canPostToChannel(
          appContext,
          BriefingAndReminderReceiver.CHANNEL_ID_BRIEF,
        )
    val reminderAvailable =
      available &&
        BriefingAndReminderReceiver.canPostToChannel(
          appContext,
          BriefingAndReminderReceiver.CHANNEL_ID_REMINDER,
        )
    val changed =
      _notificationsAvailable.value != available ||
        _dailyNotificationsAvailable.value != dailyAvailable ||
        _reminderNotificationsAvailable.value != reminderAvailable
    _notificationsAvailable.value = available
    _dailyNotificationsAvailable.value = dailyAvailable
    _reminderNotificationsAvailable.value = reminderAvailable
    if (!changed) return
    ensureAlarmsScheduled()
  }

  fun onNotificationPermissionDenied() {
    onNotificationPermissionChecked(false)
    markNotificationPermissionRequested()
    message("Notifications remain off. You can allow them in system settings.")
  }

  fun onNotificationChannelBlocked() {
    markNotificationPermissionRequested()
    message("That notification channel is blocked in Android settings")
  }

  fun markNotificationPermissionRequested() {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.NOTIFICATION_PERMISSION_REQUESTED, true.toString())
    }
  }

  /**
   * Called on every resume. Granting the permission from system settings does not
   * come back through the in-app launcher, so without this the banner would stay
   * up until the next manual sync.
   */
  fun onCalendarPermissionChecked(granted: Boolean) {
    viewModelScope.launch {
      val enabled =
        repository.readSetting(SettingKeys.DEVICE_CALENDAR_ENABLED)?.toBooleanStrictOrNull() ?: true
      val missing = enabled && !granted
      val wasMissing = _needsCalendarPermission.value
      _needsCalendarPermission.value = missing
      if (wasMissing && !missing) sync(announce = false)
    }
  }

  fun onCalendarPermissionGranted() {
    _needsCalendarPermission.value = false
    setDeviceCalendarEnabled(true)
  }

  // ---- Reminders ----------------------------------------------------------

  private suspend fun rescheduleReminderFor(event: BriefingEvent) {
    AlarmScheduler.cancelEventReminder(appContext, event.id)
    // Rebuild the complete reminder set. replaceEventReminders intentionally
    // clears its durable ledger first, so passing only the edited event here
    // would silently cancel every other upcoming reminder.
    refreshAllReminders()
  }

  private suspend fun refreshAllReminders(forceEnabled: Boolean? = null) {
    val now = System.currentTimeMillis()
    val upcoming =
      repository.eventsInRangeOnce(now, ScheduleAnalysis.startOfDayOffset(now, 15))
    val enabled =
      (forceEnabled
        ?: (repository.readSetting(SettingKeys.REMINDERS_ENABLED)?.toBooleanStrictOrNull()
          ?: false)) && _reminderNotificationsAvailable.value
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

  private fun message(text: String) {
    _messages.tryEmit(UiMessage(text))
  }

  private fun roundUpToQuarterHour(timeMs: Long): Long {
    val quarter = 15 * 60 * 1000L
    return ((timeMs + quarter - 1) / quarter) * quarter
  }

  companion object {
    private const val STOP_TIMEOUT = 5_000L
    private const val DAY_TICK_MS = 60_000L
    private const val AUTO_SYNC_INTERVAL_MS = 30 * 60 * 1000L
    private const val SYNC_DAYS_BACK = 7
    private const val SYNC_DAYS_FORWARD = 30
    private const val STRIP_BACK = -7
    private const val STRIP_FORWARD = 21
    private const val SELECTED_DAY_KEY = "selected_day"
  }
}
