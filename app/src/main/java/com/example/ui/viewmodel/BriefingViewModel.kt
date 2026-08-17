package com.example.ui.viewmodel

import com.example.ui.screens.OutlineGrouping
import com.example.core.AutoPlanResult
import com.example.core.AutoPlan
import com.example.core.IsoDates
import com.example.core.ExportScope
import com.example.core.PlanExport
import com.example.data.repository.PlanMutationType
import com.example.core.WeeklyReviewResult
import com.example.core.WeeklyReview
import com.example.ui.screens.OutlineSort
import com.example.data.prefs.UndoWindowPolicy
import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.core.ScheduleAnalysis
import com.example.core.MultiSchedulePlanHealth
import com.example.core.PlanHealthResult
import com.example.core.CriticalPathEngine
import com.example.core.CriticalPathResult
import com.example.core.WorkingInterval
import com.example.data.model.PlanBaseline
import com.example.core.PortfolioRollupResult
import com.example.core.PortfolioRollup
import com.example.core.PlanScenarios
import com.example.core.PlanScenario
import com.example.core.BaselineVariance
import com.example.core.BaselineComparison
import com.example.core.WorkingCalendarSpec
import com.example.data.api.WriteBack
import com.example.data.database.PlanItemWithBlocks
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.model.PlanBoard
import com.example.data.model.PlanBlock
import com.example.data.model.PlanColumn
import com.example.data.model.PlanDependency
import com.example.data.model.PlanItem
import com.example.data.model.PlanItemSchedule
import com.example.data.model.PlanMutation
import com.example.data.model.PlanSurface
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.BoardDeleteOutcome
import com.example.data.repository.EventDeleteOutcome
import com.example.data.repository.PlanBlockInput
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanHealthSchedulePolicy
import com.example.data.repository.PlanRepository
import com.example.data.repository.PlanUndoStatus
import com.example.data.repository.PersistedWorkingCalendar
import com.example.data.repository.SavedPlanView
import com.example.data.repository.SavedPlanViewState
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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

/** Which system owns an event and therefore which actions are safe in Daily Brief. */
enum class EventOwnership(
  val sourceFieldsEditable: Boolean,
  val deletableFromEditor: Boolean,
  val movableOnTimeline: Boolean,
  val deletionUndoable: Boolean,
) {
  APP_OWNED(
    sourceFieldsEditable = true,
    deletableFromEditor = true,
    movableOnTimeline = true,
    deletionUndoable = true,
  ),
  DEVICE_CALENDAR(
    sourceFieldsEditable = true,
    deletableFromEditor = true,
    movableOnTimeline = false,
    deletionUndoable = false,
  ),
  READ_ONLY_SOURCE(
    sourceFieldsEditable = false,
    deletableFromEditor = false,
    movableOnTimeline = false,
    deletionUndoable = false,
  );

  companion object {
    /** Unknown integrations fail closed until their write-back contract is explicit. */
    fun forSource(source: String): EventOwnership =
      when (source) {
        EventSource.MANUAL, EventSource.SAMPLE -> APP_OWNED
        in EventSource.DEVICE_WRITABLE -> DEVICE_CALENDAR
        else -> READ_ONLY_SOURCE
      }
  }
}

/** A single, complete description of an event the user is creating or editing. */
data class EventDraft(
  val id: String? = null,
  val title: String = "",
  val description: String = "",
  val startMs: Long,
  val endMs: Long,
  val source: String = EventSource.MANUAL,
  val ownership: EventOwnership = EventOwnership.forSource(source),
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

    /** Agenda-first by default; every analytical section remains opt-in. */
    val Defaults = listOf(Agenda)
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
  Actions("actions", "Quick actions", "Timeline, search and sync"),
  UpNext("upnext", "Up next", "The next few things"),
  Attention("attention", "Needs attention", "Overlapping events"),
  Health("health", "Plan health", "Capacity, estimates and deadline risk"),
  Board("board", "Board", "Where your work stands");

  companion object {
    fun byKey(key: String?) = entries.firstOrNull { it.key == key }

    /**
     * What Home opens with: what you are in, what is next, and anything clashing.
     *
     * Plan Health, the Board preview and quick actions are all real, and all of them are answers to
     * questions nobody asked on opening the app. They stay one tap away under Edit rather than
     * standing on the first screen every morning.
     */
    val Defaults = listOf(Focus, UpNext, Attention)
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
  /** Null means the Undo expects the row to remain deleted; non-null is an exact stale guard. */
  val undoExpectedEvent: BriefingEvent? = null,
  val undoPlanMutationId: String? = null,
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

data class PlanHealthUiState(
  val result: PlanHealthResult? = null,
  val rangeStart: Long = 0L,
  val rangeEnd: Long = 0L,
  val generatedAt: Long = 0L,
  val unavailableReason: String? = "Preparing Plan Health…",
)

data class CriticalPathUiState(
  val result: CriticalPathResult? = null,
  val unavailableReason: String? = "Preparing critical path…",
)

/** A baseline, and how far today's plan has moved from it. */
data class BaselineComparisonUiState(
  val baselineId: String,
  val name: String,
  val capturedAt: Long,
  val comparison: BaselineComparison,
)

data class ActiveWorkingCalendarsState(
  val calendars: List<PersistedWorkingCalendar> = emptyList(),
  val loaded: Boolean = false,
)

data class PlanItemScheduleAssignmentsState(
  val boardId: String? = null,
  val assignments: List<PlanItemSchedule> = emptyList(),
  val loaded: Boolean = false,
)

private data class PlanHealthScheduleInputs(
  val defaultCalendar: PersistedWorkingCalendar?,
  val activeCalendars: ActiveWorkingCalendarsState,
  val assignments: PlanItemScheduleAssignmentsState,
)

private data class PlanHealthInputs(
  val board: PlanBoard?,
  val items: List<PlanItem>,
  val relationBoardIds: Set<String>,
  val blocks: List<com.example.data.model.PlanBlock>,
  val commitments: List<BriefingEvent>,
  val schedules: PlanHealthScheduleInputs,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BriefingViewModel(application: Application, private val savedStateHandle: SavedStateHandle) :
  AndroidViewModel(application) {
  private val repository = BriefingRepository(application)
  private val planRepository = PlanRepository(application)
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

  /**
   * Editor mutations live in the ViewModel so rotation cannot strand a restored
   * sheet in a locally saved "busy" state. A signed completion token is positive
   * on success and negative on failure; the UI consumes each absolute token once.
   */
  val eventEditorBusy: StateFlow<Boolean> =
    savedStateHandle.getStateFlow(EVENT_EDITOR_BUSY_KEY, false)
  val taskEditorBusy: StateFlow<Boolean> =
    savedStateHandle.getStateFlow(TASK_EDITOR_BUSY_KEY, false)
  val eventEditorCompletion: StateFlow<Long> =
    savedStateHandle.getStateFlow(EVENT_EDITOR_COMPLETION_KEY, 0L)
  val taskEditorCompletion: StateFlow<Long> =
    savedStateHandle.getStateFlow(TASK_EDITOR_COMPLETION_KEY, 0L)

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

  /** One foreground schedule lane keeps sync, edits, alarms and cache refreshes ordered. */
  private val scheduleActionMutex = Mutex()
  private val workspacePreferencesMutex = Mutex()
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
        repository.eventsInRange(today, ScheduleAnalysis.startOfDayOffset(today, 1))
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

  /** Which screen the app opens on; null means Room has not emitted yet. */
  val homeDestination: StateFlow<String?> =
    repository
      .settingFlow(SettingKeys.HOME_DESTINATION)
      .map { WorkspacePreferencePolicy.homeDestination(it?.value) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  fun setHomeDestination(name: String) {
    viewModelScope.launch {
      repository.writeSetting(
        SettingKeys.HOME_DESTINATION,
        WorkspacePreferencePolicy.homeDestination(name),
      )
    }
  }

  val calendarView: StateFlow<String> =
    combine(
        repository.settingFlow(SettingKeys.CALENDAR_VIEW),
        repository.settingFlow(SettingKeys.HOME_DESTINATION),
      ) { storedCalendarView, storedHomeDestination ->
        WorkspacePreferencePolicy.calendarView(
          storedCalendarView?.value,
          storedHomeDestination?.value,
        )
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_CALENDAR_VIEW)

  fun setCalendarView(name: String) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.CALENDAR_VIEW, WorkspacePreferencePolicy.calendarView(name))
    }
  }

  val planView: StateFlow<String> =
    repository
      .settingFlow(SettingKeys.PLAN_VIEW)
      .map { WorkspacePreferencePolicy.planView(it?.value) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_PLAN_VIEW)

  fun setPlanView(name: String) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.PLAN_VIEW, WorkspacePreferencePolicy.planView(name))
      repository.writeSetting(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID, "")
    }
  }

  // ---- App-owned planning ------------------------------------------------

  val planBoards: StateFlow<List<PlanBoard>> =
    planRepository
      .observeBoards()
      .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  val activePlanBoardId: StateFlow<String?> =
    combine(planRepository.observeActiveBoardId(), planBoards) { stored, available ->
        stored?.takeIf { id -> available.any { it.id == id } }
          ?: available.firstOrNull { it.isDefault }?.id
          ?: available.firstOrNull()?.id
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  val activePlanBoard: StateFlow<PlanBoard?> =
    combine(activePlanBoardId, planBoards) { activeId, available ->
        available.firstOrNull { it.id == activeId }
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  val planColumns: StateFlow<List<PlanColumn>> =
    activePlanBoardId
      .flatMapLatest { boardId ->
        if (boardId == null) flowOf(emptyList()) else planRepository.observeColumns(boardId)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  val planItems: StateFlow<List<PlanItem>> =
    activePlanBoardId
      .flatMapLatest { boardId ->
        if (boardId == null) flowOf(emptyList()) else planRepository.observeItems(boardId)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  val planGanttItems: StateFlow<List<PlanItemWithBlocks>> =
    activePlanBoardId
      .flatMapLatest { boardId ->
        if (boardId == null) flowOf(emptyList()) else planRepository.observeGanttItems(boardId)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  val planDependencies: StateFlow<List<PlanDependency>> =
    activePlanBoardId
      .flatMapLatest { boardId ->
        if (boardId == null) flowOf(emptyList()) else planRepository.observeDependencies(boardId)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  val activeWorkingCalendarsState: StateFlow<ActiveWorkingCalendarsState> =
    planRepository
      .observeActiveWorkingCalendars()
      .map { calendars -> ActiveWorkingCalendarsState(calendars = calendars, loaded = true) }
      .onStart { emit(ActiveWorkingCalendarsState()) }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT),
        ActiveWorkingCalendarsState(),
      )

  val activeWorkingCalendars: StateFlow<List<PersistedWorkingCalendar>> =
    activeWorkingCalendarsState
      .map { state -> state.calendars }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  val planItemScheduleAssignmentsState: StateFlow<PlanItemScheduleAssignmentsState> =
    activePlanBoardId
      .flatMapLatest { boardId ->
        if (boardId == null) {
          flowOf(PlanItemScheduleAssignmentsState(boardId = null, loaded = true))
        } else {
          planRepository
            .observeItemScheduleAssignments(boardId)
            .map { assignments ->
              PlanItemScheduleAssignmentsState(
                boardId = boardId,
                assignments = assignments,
                loaded = true,
              )
            }
            .onStart {
              emit(PlanItemScheduleAssignmentsState(boardId = boardId, loaded = false))
            }
        }
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT),
        PlanItemScheduleAssignmentsState(),
      )

  val planItemScheduleAssignments: StateFlow<List<PlanItemSchedule>> =
    planItemScheduleAssignmentsState
      .map { state -> state.assignments }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  private val _planItemScheduleAssignmentsInFlight = MutableStateFlow<Set<String>>(emptySet())
  val planItemScheduleAssignmentsInFlight: StateFlow<Set<String>> =
    _planItemScheduleAssignmentsInFlight.asStateFlow()

  val criticalPath: StateFlow<CriticalPathUiState> =
    combine(activePlanBoard, planItems, planGanttItems, planDependencies) {
        board,
        items,
        relations,
        dependencies ->
        if (board == null) {
          CriticalPathUiState(unavailableReason = "Choose a Plan board first")
        } else if (
          items.any { it.boardId != board.id } ||
            relations.any { it.item.boardId != board.id } ||
            dependencies.any { it.boardId != board.id }
        ) {
          CriticalPathUiState(unavailableReason = "Critical path is refreshing…")
        } else {
          val result =
            CriticalPathEngine.analyze(
              items = items,
              blocks = relations.flatMap(PlanItemWithBlocks::blocks),
              dependencies = dependencies,
            )
          if (result.isComplete) CriticalPathUiState(result = result, unavailableReason = null)
          else {
            CriticalPathUiState(
              result = result,
              unavailableReason =
                buildList {
                    addAll(result.errors.take(2).map { it.explanation })
                    addAll(result.incompleteItems.take(2).map { it.explanation })
                  }
                  .joinToString(" ")
                  .ifBlank { "Critical path needs complete task durations and dependencies" },
            )
          }
        }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), CriticalPathUiState())

  val planCommitments: StateFlow<List<BriefingEvent>> =
    activePlanBoard
      .flatMapLatest { board ->
        if (board == null) flowOf(emptyList()) else repository.eventsForBoard(board.name)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  val ganttRangeDays: StateFlow<Int> =
    repository
      .settingFlow(SettingKeys.GANTT_RANGE_DAYS)
      .map { setting -> setting?.value?.toIntOrNull()?.takeIf { it in setOf(7, 30, 90) } ?: 30 }
      .stateIn(viewModelScope, SharingStarted.Eagerly, 30)

  /**
   * How the Plan Outline is presented. Persisted so a folded branch survives a cold start, and
   * captured by saved views; none of it changes a task.
   */
  val outlineSort: StateFlow<OutlineSort> =
    repository
      .settingFlow(SettingKeys.PLAN_OUTLINE_SORT)
      .map { OutlineSort.byKey(it?.value) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, OutlineSort.MANUAL)

  val outlineHideCompleted: StateFlow<Boolean> =
    repository
      .settingFlow(SettingKeys.PLAN_OUTLINE_HIDE_COMPLETED)
      .map { it?.value == "true" }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val outlineGrouping: StateFlow<OutlineGrouping> =
    repository
      .settingFlow(SettingKeys.PLAN_OUTLINE_GROUPING)
      .map { OutlineGrouping.byKey(it?.value) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, OutlineGrouping.SECTION)

  /** Empty means every lane; a preset is a filter, never a deletion. */
  val visibleBoardColumnIds: StateFlow<Set<String>> =
    repository
      .settingFlow(SettingKeys.PLAN_BOARD_COLUMNS)
      .map { setting -> SettingKeys.decodeList(setting?.value)?.toSet().orEmpty() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

  fun setOutlineGrouping(grouping: OutlineGrouping) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.PLAN_OUTLINE_GROUPING, grouping.key)
      clearActiveSavedView()
    }
  }

  fun toggleBoardColumnVisible(columnId: String) {
    viewModelScope.launch {
      val all = planColumns.value.map { it.id }
      val current = visibleBoardColumnIds.value.ifEmpty { all.toSet() }
      val next = if (columnId in current) current - columnId else current + columnId
      // Hiding the last lane would leave a Board with nothing on it; that is a bug, not a preset.
      if (next.isEmpty()) return@launch
      repository.writeSetting(
        SettingKeys.PLAN_BOARD_COLUMNS,
        SettingKeys.encodeList(if (next.toSet() == all.toSet()) emptyList() else next.sorted()),
      )
      clearActiveSavedView()
    }
  }

  fun showAllBoardColumns() {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.PLAN_BOARD_COLUMNS, SettingKeys.encodeList(emptyList()))
      clearActiveSavedView()
    }
  }

  val outlineCollapsedIds: StateFlow<Set<String>> =
    repository
      .settingFlow(SettingKeys.PLAN_OUTLINE_COLLAPSED)
      .map { setting -> SettingKeys.decodeList(setting?.value)?.toSet().orEmpty() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

  fun setOutlineSort(sort: OutlineSort) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.PLAN_OUTLINE_SORT, sort.key)
      clearActiveSavedView()
    }
  }

  fun setOutlineHideCompleted(hide: Boolean) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.PLAN_OUTLINE_HIDE_COMPLETED, hide.toString())
      clearActiveSavedView()
    }
  }

  fun toggleOutlineCollapsed(itemId: String) {
    viewModelScope.launch {
      val current = outlineCollapsedIds.value
      val next = if (itemId in current) current - itemId else current + itemId
      repository.writeSetting(
        SettingKeys.PLAN_OUTLINE_COLLAPSED,
        SettingKeys.encodeList(next.sorted()),
      )
      clearActiveSavedView()
    }
  }

  fun expandAllOutlineTasks() {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.PLAN_OUTLINE_COLLAPSED, SettingKeys.encodeList(emptyList()))
      clearActiveSavedView()
    }
  }

  /** Changing the view by hand means the named view is no longer what is on screen. */
  private suspend fun clearActiveSavedView() {
    if (activeSavedPlanViewId.value != null) {
      repository.writeSetting(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID, "")
    }
  }

  private val _autoPlan = MutableStateFlow<AutoPlanResult?>(null)

  /** The most recent proposal. Null means nothing has been proposed, not that nothing fits. */
  val autoPlan: StateFlow<AutoPlanResult?> = _autoPlan.asStateFlow()

  /** Baselines for the active board, newest first. */
  val planBaselines: StateFlow<List<PlanBaseline>> =
    activePlanBoardId
      .flatMapLatest { boardId ->
        if (boardId == null) flowOf(emptyList()) else planRepository.observeBaselines(boardId)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  private val _baselineComparison = MutableStateFlow<BaselineComparisonUiState?>(null)

  /** The comparison currently on screen. Null means none has been asked for. */
  val baselineComparison: StateFlow<BaselineComparisonUiState?> = _baselineComparison.asStateFlow()

  fun captureBaseline(name: String, onDone: (Boolean) -> Unit = {}) {
    val boardId = activePlanBoardId.value
    if (boardId == null) {
      message("Choose a plan board before taking a baseline")
      onDone(false)
      return
    }
    viewModelScope.launch {
      try {
        val baseline = planRepository.captureBaseline(boardId, name)
        message("Baseline \"${baseline.name}\" saved")
        onDone(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That baseline could not be saved")
        onDone(false)
      }
    }
  }

  /** Reads a baseline and measures today's plan against it; nothing is written. */
  fun compareBaseline(baselineId: String) {
    val boardId = activePlanBoardId.value ?: return
    viewModelScope.launch {
      try {
        val baseline = planBaselines.value.firstOrNull { it.id == baselineId }
        val snapshot = planRepository.readBaseline(baselineId)
        if (baseline == null || snapshot == null) {
          message("That baseline could not be read")
          return@launch
        }
        val relations = planGanttItems.value
        _baselineComparison.value =
          BaselineComparisonUiState(
            baselineId = baselineId,
            name = baseline.name,
            capturedAt = baseline.capturedAt,
            comparison =
              BaselineVariance.compare(
                baselineItems = snapshot.first,
                baselineBlocks = snapshot.second,
                currentItems = relations.map { it.item },
                currentBlocks = relations.flatMap { it.blocks },
              ),
          )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That baseline could not be read")
      }
    }
  }

  fun dismissBaselineComparison() {
    _baselineComparison.value = null
  }

  /** Puts the schedule back to a baseline as one undoable command. */
  fun restoreBaseline(baselineId: String) {
    val boardId = activePlanBoardId.value ?: return
    viewModelScope.launch {
      try {
        val result = planRepository.restoreBaselineWithUndo(baselineId, boardId)
        _baselineComparison.value = null
        planMutationMessage(
          "Restored ${result.value} block${if (result.value == 1) "" else "s"}",
          result.mutationId,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That baseline could not be restored")
      }
    }
  }

  fun deleteBaseline(baselineId: String) {
    val boardId = activePlanBoardId.value ?: return
    viewModelScope.launch {
      if (planRepository.deleteBaseline(baselineId, boardId)) {
        if (_baselineComparison.value?.baselineId == baselineId) _baselineComparison.value = null
        message("Baseline deleted")
      } else {
        message("That baseline was already gone")
      }
    }
  }

  private val _portfolio = MutableStateFlow<PortfolioRollupResult?>(null)

  /** Every board at once. Null means the rollup is not open. */
  val portfolio: StateFlow<PortfolioRollupResult?> = _portfolio.asStateFlow()

  /**
   * Totals across every live board.
   *
   * Read once on demand rather than kept live: a rollup is something a person opens to think with,
   * and observing every board's tasks continuously would cost far more than it tells anyone.
   */
  fun openPortfolio() {
    viewModelScope.launch {
      try {
        val boards = planBoards.value.filter { it.archivedAt == null }
        val itemsByBoard = mutableMapOf<String, List<PlanItem>>()
        val blocksByItem = mutableMapOf<String, List<PlanBlock>>()
        boards.forEach { board ->
          val items = planRepository.itemsForBoardOnce(board.id)
          itemsByBoard[board.id] = items
          planRepository.blocksForBoardOnce(board.id).groupBy(PlanBlock::planItemId).forEach {
            (itemId, blocks) ->
            blocksByItem[itemId] = blocks
          }
        }
        _portfolio.value =
          PortfolioRollup.summarise(
            boards = boards.map { it.id to it.name },
            itemsByBoard = itemsByBoard,
            blocksByItem = blocksByItem,
            nowMs = System.currentTimeMillis(),
          )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "Those plans could not be totalled")
      }
    }
  }

  fun dismissPortfolio() {
    _portfolio.value = null
  }

  private val _planScenarios = MutableStateFlow<List<PlanScenario>>(emptyList())

  /** Alternative orderings of the same week. Empty means none have been asked for. */
  val planScenarios: StateFlow<List<PlanScenario>> = _planScenarios.asStateFlow()

  /** Runs the planner three defensible ways without writing anything. */
  fun comparePlanScenarios(days: Int = 7) {
    val boardId = activePlanBoardId.value
    if (boardId == null) {
      message("Choose a plan board before comparing approaches")
      return
    }
    viewModelScope.launch {
      try {
        val calendar = planRepository.observeDefaultWorkingCalendar().first()
        if (calendar == null) {
          message("Set up a working schedule before comparing approaches")
          return@launch
        }
        val relations = planGanttItems.value
        val now = System.currentTimeMillis()
        val start = ScheduleAnalysis.startOfDay(now)
        val end = ScheduleAnalysis.startOfDayOffset(start, days)
        val commitments = ScheduleAnalysis.fixedCommitments(repository.eventsInRangeOnce(start, end))
        _planScenarios.value =
          PlanScenarios.compare(
            items = relations.map { it.item },
            blocks = relations.flatMap { it.blocks },
            fixedCommitments = commitments,
            dependencies = planDependencies.value,
            schedule = calendar.spec,
            rangeStartMs = start,
            rangeEndMs = end,
            nowMs = now,
          )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "Those approaches could not be compared")
      }
    }
  }

  fun dismissPlanScenarios() {
    _planScenarios.value = emptyList()
  }

  /** Hands the chosen scenario to the ordinary proposal review rather than writing it outright. */
  fun choosePlanScenario(key: String) {
    val scenario = _planScenarios.value.firstOrNull { it.key == key } ?: return
    _planScenarios.value = emptyList()
    _autoPlan.value = scenario.result
  }

  /**
   * Proposes blocks for unscheduled effort without writing anything.
   *
   * The engine is pure and the result is shown before it is applied, because a planner that moves
   * a person's week while they are not looking is not a feature, it is a surprise.
   */
  fun proposePlan(days: Int = 7) {
    val boardId = activePlanBoardId.value
    if (boardId == null) {
      message("Choose a plan board before planning it")
      return
    }
    viewModelScope.launch {
      try {
        val calendar = planRepository.observeDefaultWorkingCalendar().first()
        if (calendar == null) {
          message("Set up a working schedule before planning")
          return@launch
        }
        val relations = planGanttItems.value
        val now = System.currentTimeMillis()
        val start = ScheduleAnalysis.startOfDay(now)
        val end = ScheduleAnalysis.startOfDayOffset(start, days)
        val commitments = ScheduleAnalysis.fixedCommitments(repository.eventsInRangeOnce(start, end))
        _autoPlan.value =
          AutoPlan.propose(
            items = relations.map { it.item },
            blocks = relations.flatMap { it.blocks },
            fixedCommitments = commitments,
            dependencies = planDependencies.value,
            schedule = calendar.spec,
            rangeStartMs = start,
            rangeEndMs = end,
            nowMs = now,
          )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That plan could not be proposed")
      }
    }
  }

  fun dismissPlanProposal() {
    _autoPlan.value = null
  }

  /** Writes the whole proposal as one command, or none of it. */
  fun applyPlanProposal() {
    val proposal = _autoPlan.value ?: return
    val boardId = activePlanBoardId.value ?: return
    if (proposal.proposals.isEmpty()) return
    viewModelScope.launch {
      try {
        val result = planRepository.applyPlanProposals(proposal.proposals, boardId)
        _autoPlan.value = null
        planMutationMessage(
          "Scheduled ${result.value.size} block${if (result.value.size == 1) "" else "s"}",
          result.mutationId,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That plan could not be applied")
      }
    }
  }

  /**
   * A plan as plain text, ready to hand to something that is not this app.
   *
   * The export states its own scope on the first line, because a file cannot answer questions about
   * what it left out and a person reading it months later will not remember.
   */
  fun exportActivePlan(asCalendar: Boolean, onReady: (String, String) -> Unit) {
    val board = activePlanBoard.value
    if (board == null) {
      message("Choose a plan board before exporting it")
      return
    }
    viewModelScope.launch {
      try {
        val relations = planGanttItems.value
        val scope =
          ExportScope(
            boardName = board.name,
            generatedAtMs = System.currentTimeMillis(),
            rangeStartMs = null,
            rangeEndMs = null,
            includesUnscheduled = !asCalendar,
          )
        val items = relations.map { it.item }
        val blocks = relations.flatMap { it.blocks }
        val body =
          if (asCalendar) {
            PlanExport.toIcs(items, blocks, scope, IsoDates::icsUtc)
          } else {
            PlanExport.toCsv(items, blocks, scope, IsoDates::isoUtc)
          }
        onReady(body, if (asCalendar) "text/calendar" else "text/csv")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That plan could not be exported")
      }
    }
  }

  /**
   * Last week, as it actually went. Read from the same journal that powers Undo, so the review
   * cannot claim a smoother week than the record supports.
   */
  val weeklyReview: StateFlow<WeeklyReviewResult?> =
    combine(planGanttItems, activePlanBoardId) { relations, boardId -> relations to boardId }
      .flatMapLatest { (relations, boardId) ->
        if (boardId == null) {
          flowOf(null)
        } else {
          planRepository.observeMutationHistory(boardId, 200).map { history ->
            val now = System.currentTimeMillis()
            val start = ScheduleAnalysis.startOfDayOffset(ScheduleAnalysis.startOfDay(now), -6)
            val end = ScheduleAnalysis.startOfDayOffset(start, 7)
            val inWeek = history.filter { it.createdAt in start until end }
            WeeklyReview.summarise(
              items = relations.map { it.item },
              blocks = relations.flatMap { it.blocks },
              rescheduleCount = inWeek.count { it.mutationType == PlanMutationType.BLOCK_EDIT },
              createdTaskCount = inWeek.count { it.mutationType == PlanMutationType.ITEM_CREATE },
              rangeStartMs = start,
              rangeEndMs = end,
            )
          }
        }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

  /** How long a change stays undoable; see [UndoWindowPolicy]. */
  val undoWindowSeconds: StateFlow<Int> =
    repository
      .settingFlow(SettingKeys.UNDO_WINDOW_SECONDS)
      .map { setting -> UndoWindowPolicy.seconds(setting?.value) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, UndoWindowPolicy.DEFAULT_SECONDS)

  fun setUndoWindowSeconds(seconds: Int) {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.UNDO_WINDOW_SECONDS, UndoWindowPolicy.store(seconds))
    }
  }

  fun setGanttRangeDays(days: Int) {
    require(days in setOf(7, 30, 90)) { "Unsupported Gantt range" }
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.GANTT_RANGE_DAYS, days.toString())
      repository.writeSetting(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID, "")
    }
  }

  val showLegacyPlanDisclosure: StateFlow<Boolean> =
    combine(
        repository.settingFlow(SettingKeys.PLAN_LEGACY_CATALOG_IMPORTED),
        repository.settingFlow(SettingKeys.PLAN_IMPORT_DISCLOSURE_ACKNOWLEDGED),
      ) { imported, acknowledged ->
        imported?.value?.toBooleanStrictOrNull() == true &&
          acknowledged?.value?.toBooleanStrictOrNull() != true
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  fun acknowledgeLegacyPlanDisclosure() {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.PLAN_IMPORT_DISCLOSURE_ACKNOWLEDGED, "true")
    }
  }

  val savedPlanViews: StateFlow<List<SavedPlanView>> =
    activePlanBoardId
      .flatMapLatest { boardId ->
        if (boardId == null) flowOf(emptyList()) else planRepository.observeSavedViews(boardId)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  val activeSavedPlanViewId: StateFlow<String?> =
    combine(
        repository.settingFlow(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID),
        savedPlanViews,
      ) { stored, views ->
        stored?.value?.takeIf { id -> views.any { it.view.id == id } }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

  val planMutationHistory: StateFlow<List<PlanMutation>> =
    activePlanBoardId
      .flatMapLatest { boardId ->
        if (boardId == null) flowOf(emptyList())
        else planRepository.observeMutationHistory(boardId, limit = 30)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

  /** Everything on screen that a view is allowed to remember: presentation, never task data. */
  private fun currentPlanViewState(): SavedPlanViewState {
    val surface =
      when (WorkspacePreferencePolicy.planView(planView.value)) {
        "Board" -> PlanSurface.BOARD
        "Gantt" -> PlanSurface.GANTT
        else -> PlanSurface.OUTLINE
      }
    return SavedPlanViewState(
      surface = surface,
      filters =
        if (outlineHideCompleted.value) {
          mapOf(SavedPlanViewState.FILTER_HIDE_COMPLETED to "true")
        } else {
          emptyMap()
        },
      grouping = outlineGrouping.value.key,
      sort = listOf(outlineSort.value.key),
      columns = visibleBoardColumnIds.value.sorted(),
      rangeDays = if (surface == PlanSurface.GANTT) ganttRangeDays.value else null,
      collapsedItemIds = outlineCollapsedIds.value,
    )
  }

  /** Points the active view at what is on screen now, without renaming it. */
  fun updateActivePlanView(onComplete: (Boolean) -> Unit = {}) {
    val boardId = activePlanBoardId.value
    val viewId = activeSavedPlanViewId.value
    if (boardId == null || viewId == null) {
      message("Apply a saved view before updating it")
      onComplete(false)
      return
    }
    runPlanViewAction(onComplete) {
      val updated = planRepository.updateSavedView(viewId, boardId, currentPlanViewState())
      message("Updated \"${updated.view.name}\"")
    }
  }

  fun renamePlanView(viewId: String, name: String, onComplete: (Boolean) -> Unit = {}) {
    val boardId = activePlanBoardId.value ?: return onComplete(false)
    runPlanViewAction(onComplete) {
      val renamed = planRepository.renameSavedView(viewId, boardId, name)
      message("Renamed to \"${renamed.view.name}\"")
    }
  }

  fun duplicatePlanView(viewId: String, onComplete: (Boolean) -> Unit = {}) {
    val boardId = activePlanBoardId.value ?: return onComplete(false)
    runPlanViewAction(onComplete) {
      val copy = planRepository.duplicateSavedView(viewId, boardId)
      message("Copied to \"${copy.view.name}\"")
    }
  }

  fun setPlanViewPinned(viewId: String, pinned: Boolean, onComplete: (Boolean) -> Unit = {}) {
    val boardId = activePlanBoardId.value ?: return onComplete(false)
    runPlanViewAction(onComplete) {
      val view = planRepository.setSavedViewPinned(viewId, boardId, pinned)
      message(if (pinned) "\"${view.view.name}\" opens this board" else "Unpinned \"${view.view.name}\"")
    }
  }

  /** Returns the Plan surface to its own defaults and stops following the named view. */
  fun resetPlanView() {
    viewModelScope.launch {
      repository.writeSetting(SettingKeys.PLAN_OUTLINE_SORT, OutlineSort.MANUAL.key)
      repository.writeSetting(SettingKeys.PLAN_OUTLINE_HIDE_COMPLETED, false.toString())
      repository.writeSetting(SettingKeys.PLAN_OUTLINE_COLLAPSED, SettingKeys.encodeList(emptyList()))
      repository.writeSetting(SettingKeys.PLAN_OUTLINE_GROUPING, OutlineGrouping.SECTION.key)
      repository.writeSetting(SettingKeys.PLAN_BOARD_COLUMNS, SettingKeys.encodeList(emptyList()))
      repository.writeSetting(SettingKeys.ACTIVE_SAVED_PLAN_VIEW_ID, "")
      message("View reset")
    }
  }

  private fun runPlanViewAction(onComplete: (Boolean) -> Unit, block: suspend () -> Unit) {
    viewModelScope.launch {
      try {
        block()
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That view could not be changed")
        onComplete(false)
      }
    }
  }

  fun saveCurrentPlanView(name: String, onComplete: (Boolean) -> Unit = {}) {
    val boardId = activePlanBoardId.value
    if (boardId == null) {
      message("Choose a plan board before saving a view")
      onComplete(false)
      return
    }
    viewModelScope.launch {
      try {
        val saved =
          planRepository.saveSavedView(
            boardId = boardId,
            name = name,
            state = currentPlanViewState(),
          )
        planRepository.activateSavedView(saved.view.id, boardId)
        message("Saved view \"${saved.view.name}\"")
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "The view could not be saved")
        onComplete(false)
      }
    }
  }

  /** The board a pinned view belongs to has already opened with it. */
  private var pinnedViewAppliedFor: String? = null

  /**
   * Applies a board's pinned view when that board becomes active — once per switch, so a person who
   * then resets or edits the view is not fought by their own pin until they come back.
   */
  private fun observePinnedPlanViews() {
    viewModelScope.launch {
      combine(activePlanBoardId, savedPlanViews) { boardId, views -> boardId to views }
        .collect { (boardId, views) ->
          if (boardId == null) {
            pinnedViewAppliedFor = null
            return@collect
          }
          if (pinnedViewAppliedFor == boardId) return@collect
          val pinned = views.firstOrNull { it.view.pinned } ?: return@collect
          pinnedViewAppliedFor = boardId
          runCatching { planRepository.activateSavedView(pinned.view.id, boardId) }
        }
    }
  }

  fun applySavedPlanView(saved: SavedPlanView) {
    val boardId = activePlanBoardId.value ?: return
    viewModelScope.launch {
      try {
        planRepository.activateSavedView(saved.view.id, boardId)
        message("Applied \"${saved.view.name}\"")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That saved view could not be applied")
      }
    }
  }

  fun deleteSavedPlanView(saved: SavedPlanView) {
    val boardId = activePlanBoardId.value ?: return
    viewModelScope.launch {
      if (planRepository.deleteSavedView(saved.view.id, boardId)) {
        message("Deleted saved view \"${saved.view.name}\"")
      } else {
        message("That saved view was already removed")
      }
    }
  }

  private val _workingCalendarProblem = MutableStateFlow<String?>(null)
  val workingCalendarProblem: StateFlow<String?> = _workingCalendarProblem.asStateFlow()

  private val _workingCalendarSaving = MutableStateFlow(false)
  val workingCalendarSaving: StateFlow<Boolean> = _workingCalendarSaving.asStateFlow()

  val workingCalendar: StateFlow<PersistedWorkingCalendar?> =
    planRepository
      .observeDefaultWorkingCalendar()
      .catch { error ->
        _workingCalendarProblem.value =
          error.message ?: "The saved working week could not be read safely"
        emit(null)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

  private val planHealthScheduleInputs =
    combine(
      workingCalendar,
      activeWorkingCalendarsState,
      planItemScheduleAssignmentsState,
    ) { defaultCalendar, activeCalendars, assignments ->
      PlanHealthScheduleInputs(defaultCalendar, activeCalendars, assignments)
    }

  private val planHealthInputs =
    combine(
      activePlanBoard,
      planItems,
      planGanttItems,
      planCommitments,
      planHealthScheduleInputs,
    ) { board, items, relations, commitments, schedules ->
      PlanHealthInputs(
        board = board,
        items = items,
        relationBoardIds = relations.mapTo(mutableSetOf()) { it.item.boardId },
        blocks = relations.flatMap(PlanItemWithBlocks::blocks),
        commitments = commitments,
        schedules = schedules,
      )
    }

  val planHealth: StateFlow<PlanHealthUiState> =
    combine(planHealthInputs, planDependencies, _dayTick) { inputs, dependencies, day ->
        val board = inputs.board
          ?: return@combine PlanHealthUiState(unavailableReason = "Choose a Plan board first")
        val scheduleInputs = inputs.schedules
        if (
          !scheduleInputs.activeCalendars.loaded ||
            !scheduleInputs.assignments.loaded ||
            scheduleInputs.assignments.boardId != board.id
        ) {
          return@combine PlanHealthUiState(unavailableReason = "Plan Health is refreshing working schedules…")
        }
        val calendar = scheduleInputs.defaultCalendar
          ?: return@combine PlanHealthUiState(unavailableReason = "Set a working week in Settings")
        if (
          inputs.items.any { it.boardId != board.id } ||
            inputs.relationBoardIds.any { it != board.id } ||
            dependencies.any { it.boardId != board.id } ||
            inputs.commitments.any { it.kanbanBoard != board.name }
        ) {
          return@combine PlanHealthUiState(unavailableReason = "Plan Health is refreshing…")
        }
        val resolvedSchedules =
          PlanHealthSchedulePolicy.resolve(
            items = inputs.items,
            mappings = scheduleInputs.assignments.assignments,
            calendars = scheduleInputs.activeCalendars.calendars,
            defaultScheduleId = calendar.schedule.id,
          )
        if (!resolvedSchedules.isUsable) {
          return@combine PlanHealthUiState(
            unavailableReason =
              resolvedSchedules.problem
                ?: "Plan Health cannot resolve the active working schedules.",
          )
        }
        val rangeStart = ScheduleAnalysis.startOfDay(day)
        val rangeEnd = ScheduleAnalysis.startOfDayOffset(rangeStart, PLAN_HEALTH_DAYS)
        val now = System.currentTimeMillis()
        runCatching {
            MultiSchedulePlanHealth.evaluate(
              schedules = resolvedSchedules.specsByScheduleId,
              scheduleIdByItemId = resolvedSchedules.scheduleIdByItemId,
              rangeStart = rangeStart,
              rangeEnd = rangeEnd,
              now = now,
              items = inputs.items,
              blocks = inputs.blocks,
              fixedCommitments =
                ScheduleAnalysis.fixedCommitments(inputs.commitments)
                  .filter { it.endAt > rangeStart && it.startAt < rangeEnd },
              dependencies = dependencies,
            )
          }
          .fold(
            onSuccess = { result ->
              PlanHealthUiState(
                result = result,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                generatedAt = now,
                unavailableReason = null,
              )
            },
            onFailure = { error ->
              PlanHealthUiState(
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                generatedAt = now,
                unavailableReason = error.message ?: "Plan Health could not be calculated safely",
              )
            },
          )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), PlanHealthUiState())

  fun saveWorkingCalendar(spec: WorkingCalendarSpec, onComplete: (Boolean) -> Unit = {}) {
    if (_workingCalendarSaving.value) return
    viewModelScope.launch {
      _workingCalendarSaving.value = true
      _workingCalendarProblem.value = null
      try {
        val result = planRepository.saveDefaultWorkingCalendarWithUndo(spec)
        planMutationMessage("Default working schedule saved", result.mutationId)
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _workingCalendarProblem.value = e.message ?: "The working week could not be saved"
        onComplete(false)
      } finally {
        _workingCalendarSaving.value = false
      }
    }
  }

  fun createWorkingSchedule(
    name: String,
    spec: WorkingCalendarSpec,
    onComplete: (PersistedWorkingCalendar?) -> Unit = {},
  ) {
    if (_workingCalendarSaving.value) return
    viewModelScope.launch {
      _workingCalendarSaving.value = true
      _workingCalendarProblem.value = null
      try {
        val result = planRepository.createWorkingScheduleWithUndo(name, spec)
        planMutationMessage("Created ${result.value.schedule.name}", result.mutationId)
        onComplete(result.value)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _workingCalendarProblem.value = e.message ?: "The working schedule could not be created"
        onComplete(null)
      } finally {
        _workingCalendarSaving.value = false
      }
    }
  }

  fun updateWorkingSchedule(
    id: String,
    name: String,
    spec: WorkingCalendarSpec,
    onComplete: (PersistedWorkingCalendar?) -> Unit = {},
  ) {
    if (_workingCalendarSaving.value) return
    viewModelScope.launch {
      _workingCalendarSaving.value = true
      _workingCalendarProblem.value = null
      try {
        val result = planRepository.updateWorkingScheduleWithUndo(id, name, spec)
        planMutationMessage("Saved ${result.value.schedule.name}", result.mutationId)
        onComplete(result.value)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _workingCalendarProblem.value = e.message ?: "The working schedule could not be saved"
        onComplete(null)
      } finally {
        _workingCalendarSaving.value = false
      }
    }
  }

  fun makeWorkingScheduleDefault(
    id: String,
    onComplete: (Boolean) -> Unit = {},
  ) {
    if (_workingCalendarSaving.value) return
    viewModelScope.launch {
      _workingCalendarSaving.value = true
      _workingCalendarProblem.value = null
      try {
        val result = planRepository.makeWorkingScheduleDefaultWithUndo(id)
        planMutationMessage("${result.value.schedule.name} is now the default", result.mutationId)
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _workingCalendarProblem.value =
          e.message ?: "The default working schedule could not be changed"
        onComplete(false)
      } finally {
        _workingCalendarSaving.value = false
      }
    }
  }

  fun archiveWorkingSchedule(
    id: String,
    replacementScheduleId: String?,
    onComplete: (Boolean) -> Unit = {},
  ) {
    if (_workingCalendarSaving.value) return
    viewModelScope.launch {
      _workingCalendarSaving.value = true
      _workingCalendarProblem.value = null
      try {
        val result =
          planRepository.archiveWorkingScheduleWithUndo(id, replacementScheduleId)
        planMutationMessage("Archived ${result.value.archivedSchedule.name}", result.mutationId)
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _workingCalendarProblem.value = e.message ?: "The working schedule could not be archived"
        onComplete(false)
      } finally {
        _workingCalendarSaving.value = false
      }
    }
  }

  fun setActivePlanBoard(boardId: String) {
    viewModelScope.launch {
      if (!planRepository.setActiveBoard(boardId)) message("That plan board is unavailable")
    }
  }

  fun newPlanItemDraft(columnId: String? = null, parentId: String? = null): PlanItemDraft =
    PlanItemDraft(
      boardId =
        activePlanBoardId.value
          ?: planBoards.value.firstOrNull { it.isDefault }?.id
          ?: PlanRepository.DEFAULT_BOARD_ID,
      columnId = columnId,
      parentId = parentId,
    )

  fun planItemDraftFrom(item: PlanItem): PlanItemDraft = item.asDraft()

  fun savePlanItem(draft: PlanItemDraft, onComplete: (Boolean) -> Unit = {}) {
    if (draft.title.isBlank()) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      try {
        val saved = planRepository.saveItemWithUndo(
          PlanItemInput(
            id = draft.id,
            boardId = draft.boardId,
            columnId = draft.columnId,
            parentId = draft.parentId,
            title = draft.title,
            notes = draft.notes,
            startConstraint = draft.startConstraint,
            dueAt = draft.dueAt,
            effortMinutes = draft.effortMinutes,
            progress = draft.progress,
            priority = draft.priority,
            owner = draft.owner,
            schedulingMode = draft.schedulingMode,
            locked = draft.locked,
            isMilestone = draft.isMilestone,
          )
        )
        val destination =
          if (draft.columnId == null) {
            "Plan Inbox"
          } else {
            planColumns.value.firstOrNull { it.id == draft.columnId }?.name ?: "Plan"
          }
        planMutationMessage(
          if (draft.id == null) "Added to $destination" else "Task updated",
          saved.mutationId,
        )
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "The task could not be saved")
        onComplete(false)
      }
    }
  }

  /** Schedule assignment is an immediate, separately journaled editor command. */
  fun assignPlanItemWorkingSchedule(itemId: String, workScheduleId: String?) {
    if (itemId in _planItemScheduleAssignmentsInFlight.value) return
    _planItemScheduleAssignmentsInFlight.update { it + itemId }
    viewModelScope.launch {
      try {
        val result = planRepository.assignItemWorkingScheduleWithUndo(itemId, workScheduleId)
        if (result.mutationId != null) {
          val text =
            if (workScheduleId == null) {
              "Task now inherits the default working schedule"
            } else {
              val name =
                activeWorkingCalendarsState.value.calendars
                  .firstOrNull { it.schedule.id == workScheduleId }
                  ?.schedule
                  ?.name
                  ?: "selected schedule"
              "Task assigned to $name"
            }
          planMutationMessage(text, result.mutationId)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "The working schedule could not be assigned")
      } finally {
        _planItemScheduleAssignmentsInFlight.update { it - itemId }
      }
    }
  }

  fun deletePlanItem(id: String, onComplete: (Boolean) -> Unit = {}) {
    viewModelScope.launch {
      try {
        val result = planRepository.deleteItemWithUndo(id)
        val deleted = result.value
        if (deleted == null) message("That task was already removed")
        else planMutationMessage("Task removed", result.mutationId)
        onComplete(deleted != null)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message("The task could not be deleted")
        onComplete(false)
      }
    }
  }

  fun savePlanItemFromEditor(draft: PlanItemDraft) {
    beginEditorOperation(
      busyKey = TASK_EDITOR_BUSY_KEY,
      sequenceKey = TASK_EDITOR_SEQUENCE_KEY,
      completionKey = TASK_EDITOR_COMPLETION_KEY,
    ) { complete -> savePlanItem(draft, onComplete = complete) }
  }

  fun deletePlanItemFromEditor(id: String) {
    beginEditorOperation(
      busyKey = TASK_EDITOR_BUSY_KEY,
      sequenceKey = TASK_EDITOR_SEQUENCE_KEY,
      completionKey = TASK_EDITOR_COMPLETION_KEY,
    ) { complete -> deletePlanItem(id, onComplete = complete) }
  }

  fun addPlanBlock(
    item: PlanItem,
    startAt: Long,
    endAt: Long,
    locked: Boolean,
    onComplete: (Boolean) -> Unit = {},
  ) {
    if (endAt <= startAt || item.isMilestone) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      try {
        val saved =
          planRepository.saveBlockWithUndo(
            PlanBlockInput(
              planItemId = item.id,
              startAt = startAt,
              endAt = endAt,
              locked = locked,
            )
          )
        planMutationMessage("Scheduled \"${item.title}\"", saved.mutationId)
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That task could not be scheduled")
        onComplete(false)
      }
    }
  }

  /** Gantt-only edit command; repository validation and the exact BLOCK_EDIT journal stay atomic. */
  fun updateGanttPlanBlock(
    item: PlanItem,
    block: PlanBlock,
    startAt: Long,
    endAt: Long,
    locked: Boolean,
    onComplete: (Boolean) -> Unit = {},
  ) {
    if (block.planItemId != item.id || endAt <= startAt || item.isMilestone) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      try {
        val saved =
          planRepository.saveBlockWithUndo(
            PlanBlockInput(
              id = block.id,
              planItemId = item.id,
              startAt = startAt,
              endAt = endAt,
              position = block.position,
              locked = locked,
              linkedEventId = block.linkedEventId,
            )
          )
        planMutationMessage("Updated schedule for \"${item.title}\"", saved.mutationId)
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That Plan block could not be updated")
        onComplete(false)
      }
    }
  }

  /** Gantt-only removal command; Undo restores the repository's exact persisted block snapshot. */
  fun deleteGanttPlanBlock(
    item: PlanItem,
    block: PlanBlock,
    onComplete: (Boolean) -> Unit = {},
  ) {
    if (block.planItemId != item.id) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      try {
        val deleted = planRepository.deleteBlockWithUndo(block.id)
        if (deleted.value == null) {
          message("That Plan block was already removed")
          onComplete(false)
        } else {
          planMutationMessage("Removed schedule for \"${item.title}\"", deleted.mutationId)
          onComplete(true)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "That Plan block could not be removed")
        onComplete(false)
      }
    }
  }

  fun togglePlanItemComplete(item: PlanItem) {
    viewModelScope.launch {
      try {
        val result =
          planRepository.setItemProgressWithUndo(
            item.id,
            if (item.progress == 100) 0 else 100,
          )
        planMutationMessage(
          if (result.value.progress == 100) "Completed \"${item.title}\""
          else "Reopened \"${item.title}\"",
          result.mutationId,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "The task could not be updated")
      }
    }
  }

  fun movePlanItem(item: PlanItem, columnId: String?) {
    viewModelScope.launch {
      try {
        val result = planRepository.moveItemToColumnWithUndo(item.id, columnId)
        val destination =
          columnId?.let { id -> planColumns.value.firstOrNull { it.id == id }?.name } ?: "Inbox"
        if (result.value.movedCount > 0) {
          planMutationMessage(
            if (result.value.movedCount > 1) {
              "Moved ${result.value.movedCount} linked tasks to $destination"
            } else {
              "Moved \"${item.title}\" to $destination"
            },
            result.mutationId,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "The task could not be moved")
      }
    }
  }

  /** Applies the complete fresh selection as one repository transaction and one Undo snapshot. */
  fun movePlanItemsAtomically(
    selectedItemIds: Collection<String>,
    columnId: String?,
    onComplete: (PlanBatchMoveOutcome) -> Unit = {},
  ) {
    viewModelScope.launch {
      val requested = selectedItemIds.filter(String::isNotBlank).distinct()
      val boardId = activePlanBoardId.value
      if (requested.isEmpty() || boardId == null) {
        val outcome =
          PlanBatchMoveOutcome(
            requestedSelectionCount = requested.size,
            completedSelectionCount = 0,
            movedTaskCount = 0,
            completedStepCount = 0,
            unavailableSelectionCount = requested.size,
          )
        message("No available Plan tasks were selected")
        onComplete(outcome)
        return@launch
      }

      val destination =
        columnId?.let { id -> planColumns.value.firstOrNull { it.id == id }?.name }
          ?: if (columnId == null) "Inbox" else "that workflow column"
      try {
        val result =
          planRepository.moveItemsToColumnAtomicallyWithUndo(
            itemIds = requested,
            targetColumnId = columnId,
            expectedBoardId = boardId,
          )
        val outcome =
          PlanBatchMoveOutcome(
            requestedSelectionCount = requested.size,
            completedSelectionCount = requested.size,
            movedTaskCount = result.value.movedCount,
            completedStepCount = if (result.mutationId == null) 0 else 1,
            unavailableSelectionCount = 0,
          )
        if (result.mutationId == null) {
          message("The selected tasks are already in $destination")
        } else {
          planMutationMessage(
            "Moved ${requested.size} selected ${if (requested.size == 1) "task" else "tasks"} " +
              "to $destination in one saved step" +
              if (result.value.movedCount > requested.size) {
                " (${result.value.movedCount} linked tasks affected)"
              } else "",
            result.mutationId,
          )
        }
        onComplete(outcome)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val reason = e.message ?: "The selected tasks could not be moved"
        message(reason)
        onComplete(
          PlanBatchMoveOutcome(
            requestedSelectionCount = requested.size,
            completedSelectionCount = 0,
            movedTaskCount = 0,
            completedStepCount = 0,
            unavailableSelectionCount = 0,
            failureMessage = reason,
          )
        )
      }
    }
  }

  fun addPlanDependency(
    predecessorId: String,
    successorId: String,
    type: String,
    lagMinutes: Int,
    onComplete: (Boolean) -> Unit = {},
  ) {
    viewModelScope.launch {
      try {
        val result =
          planRepository.addDependencyWithUndo(
            predecessorId = predecessorId,
            successorId = successorId,
            type = type,
            lagMinutes = lagMinutes,
          )
        planMutationMessage("Dependency added", result.mutationId)
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "The dependency could not be added")
        onComplete(false)
      }
    }
  }

  fun updatePlanDependency(
    dependency: PlanDependency,
    type: String,
    lagMinutes: Int,
    onComplete: (Boolean) -> Unit = {},
  ) {
    viewModelScope.launch {
      try {
        val result = planRepository.updateDependency(dependency.id, type, lagMinutes)
        planMutationMessage("Dependency updated", result.mutationId)
        onComplete(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "The dependency could not be updated")
        onComplete(false)
      }
    }
  }

  fun deletePlanDependency(
    dependency: PlanDependency,
    onComplete: (Boolean) -> Unit = {},
  ) {
    viewModelScope.launch {
      try {
        val result = planRepository.deleteDependency(dependency.id)
        if (result.value == null) message("That dependency was already removed")
        else planMutationMessage("Dependency removed", result.mutationId)
        onComplete(result.value != null)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message(e.message ?: "The dependency could not be removed")
        onComplete(false)
      }
    }
  }

  fun undoPlanMutation(mutationId: String) {
    viewModelScope.launch {
      try {
        val result = planRepository.undoMutation(mutationId)
        message(
          when (result.status) {
            PlanUndoStatus.UNDONE -> "Undone: ${result.summary.orEmpty()}"
            PlanUndoStatus.EXPIRED -> "Undo expired; the current plan was not changed"
            PlanUndoStatus.STALE -> "Plan changed since then, so Undo was not applied"
            PlanUndoStatus.UNSUPPORTED -> "That saved change cannot be safely undone"
            PlanUndoStatus.UNAVAILABLE -> "That change was already undone or is unavailable"
          }
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message("Undo could not be applied; the current plan was not changed")
      }
    }
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
      .map { WorkspacePreferencePolicy.collapsedTodaySections(it?.value) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptySet())

  /** Which Today sections are shown, in the order the user arranged them. */
  val todaySections: StateFlow<List<TodaySection>> =
    repository
      .settingFlow(SettingKeys.TODAY_SECTIONS)
      .map { setting -> WorkspacePreferencePolicy.todaySections(setting?.value) }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT),
        TodaySection.Defaults,
      )

  /** Which landing-page blocks are shown, in the order the user arranged them. */
  val homeCards: StateFlow<List<HomeCard>> =
    repository
      .settingFlow(SettingKeys.HOME_CARDS)
      .map { setting -> WorkspacePreferencePolicy.homeCards(setting?.value) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), HomeCard.Defaults)

  fun setHomeCardVisible(card: HomeCard, visible: Boolean) {
    viewModelScope.launch {
      workspacePreferencesMutex.withLock {
        val next =
          WorkspacePreferencePolicy.setHomeCardVisible(
            repository.readSetting(SettingKeys.HOME_CARDS),
            card,
            visible,
          )
        repository.writeSetting(SettingKeys.HOME_CARDS, SettingKeys.encodeList(next.map { it.key }))
      }
    }
  }

  fun moveHomeCard(card: HomeCard, delta: Int) {
    viewModelScope.launch {
      workspacePreferencesMutex.withLock {
        val next =
          WorkspacePreferencePolicy.moveHomeCard(
            repository.readSetting(SettingKeys.HOME_CARDS),
            card,
            delta,
          )
        repository.writeSetting(SettingKeys.HOME_CARDS, SettingKeys.encodeList(next.map { it.key }))
      }
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
    val section = TodaySection.byKey(key) ?: return
    viewModelScope.launch {
      workspacePreferencesMutex.withLock {
        val next =
          WorkspacePreferencePolicy.toggleTodaySection(
            repository.readSetting(SettingKeys.COLLAPSED_SECTIONS),
            section,
          )
        repository.writeSetting(SettingKeys.COLLAPSED_SECTIONS, SettingKeys.encodeList(next.toList()))
      }
    }
  }

  fun setSectionVisible(section: TodaySection, visible: Boolean) {
    viewModelScope.launch {
      workspacePreferencesMutex.withLock {
        val next =
          WorkspacePreferencePolicy.setTodaySectionVisible(
            repository.readSetting(SettingKeys.TODAY_SECTIONS),
            section,
            visible,
          )
        repository.writeSetting(SettingKeys.TODAY_SECTIONS, SettingKeys.encodeList(next.map { it.key }))
      }
    }
  }

  fun moveSection(section: TodaySection, delta: Int) {
    viewModelScope.launch {
      workspacePreferencesMutex.withLock {
        val next =
          WorkspacePreferencePolicy.moveTodaySection(
            repository.readSetting(SettingKeys.TODAY_SECTIONS),
            section,
            delta,
          )
        repository.writeSetting(
          SettingKeys.TODAY_SECTIONS,
          SettingKeys.encodeList(next.map { it.key }),
        )
      }
    }
  }

  /** Makes a section visible and expanded as one Room transaction. */
  fun revealTodaySection(section: TodaySection, onRevealed: () -> Unit = {}) {
    viewModelScope.launch {
      workspacePreferencesMutex.withLock {
        val visible =
          WorkspacePreferencePolicy.revealTodaySection(
            repository.readSetting(SettingKeys.TODAY_SECTIONS),
            section,
          )
        val collapsed =
          WorkspacePreferencePolicy.collapsedTodaySections(
            repository.readSetting(SettingKeys.COLLAPSED_SECTIONS)
          ) - section.key
        repository.writeSettingsAtomically(
          mapOf(
            SettingKeys.TODAY_SECTIONS to SettingKeys.encodeList(visible.map { it.key }),
            SettingKeys.COLLAPSED_SECTIONS to SettingKeys.encodeList(collapsed.toList()),
          )
        )
        onRevealed()
      }
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
    // A process-death restore cannot resume the old coroutine. Clear only the
    // transient running flags; the last completion token remains replayable.
    savedStateHandle[EVENT_EDITOR_BUSY_KEY] = false
    savedStateHandle[TASK_EDITOR_BUSY_KEY] = false
    observePinnedPlanViews()
    viewModelScope.launch {
      try {
        planRepository.ensureCatalog()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message("Plan could not be prepared; calendar data is unchanged")
      }
    }
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
      scheduleActionMutex.withLock {
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
    val newEndMs =
      runCatching { Math.addExact(newStartMs, length) }.getOrElse {
        message("That time is outside the supported calendar range")
        return
      }
    moveEventToRange(event, newStartMs, newEndMs)
  }

  /** Exact local overlap list shown by the Timeline's pre-commit move preview. */
  suspend fun timelineMoveConflicts(
    movingEventId: String,
    proposedStartMs: Long,
    proposedEndMs: Long,
  ): List<BriefingEvent> {
    if (proposedEndMs <= proposedStartMs) return emptyList()
    return repository
      .eventsInRangeOnce(proposedStartMs, proposedEndMs)
      .asSequence()
      .filter { it.id != movingEventId }
      .filter { it.startTime < proposedEndMs && it.endTime > proposedStartMs }
      .sortedWith(compareBy<BriefingEvent> { it.startTime }.thenBy { it.id })
      .toList()
  }

  /** Moves to an exact previewed range; callers must not invent a partial or invalid interval. */
  fun moveEventToRange(event: BriefingEvent, newStartMs: Long, newEndMs: Long) {
    val ownership = EventOwnership.forSource(event.source)
    if (!ownership.movableOnTimeline) {
      val guidance =
        if (ownership.sourceFieldsEditable) "open it to edit its time"
        else "reschedule it in its source app"
      message("${event.source} events stay fixed in Timeline; $guidance")
      return
    }
    if (newEndMs <= newStartMs) {
      message("The moved event must end after it starts")
      return
    }
    if (newStartMs == event.startTime && newEndMs == event.endTime) return
    viewModelScope.launch {
      scheduleActionMutex.withLock {
        try {
          val expectedStored =
            repository.moveAppOwnedEventIfUnchanged(event, newStartMs, newEndMs)
          if (expectedStored == null) {
            message("That move preview is no longer current — nothing was changed")
            return@withLock
          }
          try {
            rescheduleReminderFor(expectedStored)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            message("Event moved, but reminders could not be refreshed")
          }
          _messages.emit(
            UiMessage(
              text = "Moved \"${event.title}\"",
              actionLabel = "Undo",
              undoEvent = event,
              undoExpectedEvent = expectedStored,
            )
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          message("Couldn't move the event — nothing was changed")
        }
      }
    }
  }

  /** Moves an event by local calendar days, preserving wall time or its all-day span. */
  fun moveEventByDays(event: BriefingEvent, days: Int) {
    val (start, end) = ScheduleAnalysis.moveEventByDays(event, days)
    saveEvent(
      draft = draftFrom(event).copy(startMs = start, endMs = end),
      followMovedEvent = false,
    )
  }

  fun newDraftFor(dayMs: Long = _selectedDay.value): EventDraft {
    // Anchored to the day the user is looking at, not to "now" — creating an
    // event while browsing next Tuesday used to land it on today.
    val start = ScheduleAnalysis.suggestedEventStart(dayMs)
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
      source = event.source,
      ownership = EventOwnership.forSource(event.source),
      isAllDay = event.isAllDay,
      isUrgent = event.isUrgent,
      isDeadline = event.isDeadline,
      board = event.kanbanBoard,
      column = event.kanbanStatus,
    )

  fun saveEvent(
    draft: EventDraft,
    followMovedEvent: Boolean = true,
    onComplete: (Boolean) -> Unit = {},
  ) {
    val title = draft.title.trim()
    if (title.isEmpty()) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      scheduleActionMutex.withLock {
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

          reportWriteBack(repository.saveEventAndWriteBack(event), verb = "Saved")
          try {
            rescheduleReminderFor(event)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            message("Event saved, but reminders could not be refreshed")
          }
          if (followMovedEvent && !ScheduleAnalysis.isSameDay(event.startTime, _selectedDay.value)) {
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
      // The undo offer is emitted after the lock is released. Emitting is a
      // suspending call, and a full message buffer would otherwise hold the
      // schedule lane closed until the user got around to reading a snackbar.
      var undo: UiMessage? = null
      scheduleActionMutex.withLock {
        try {
          val event = repository.eventById(id)
          val result =
            event?.let { repository.deleteEventAndProvider(it) }
              ?: EventDeleteOutcome(
                writeBack = WriteBack.NotApplicable,
                deleted = true,
              )
          if (event == null) repository.deleteEvent(id)
          reportWriteBack(result.writeBack, verb = "Removed")
          if (!result.deleted) {
            onComplete(false)
            return@withLock
          }
          AlarmScheduler.cancelEventReminder(appContext, id)
          if (event != null) {
            if (event.source in EventSource.SYNCED) {
              message("Deleted \"${event.title}\" from its calendar")
            } else {
              undo =
                UiMessage(
                  text = "Deleted \"${event.title}\"",
                  actionLabel = "Undo",
                  undoEvent = event,
                )
            }
          }
          onComplete(true)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          message("Couldn't delete the event — nothing was changed")
          onComplete(false)
        }
      }
      undo?.let { _messages.emit(it) }
    }
  }

  fun saveEventFromEditor(draft: EventDraft) {
    beginEditorOperation(
      busyKey = EVENT_EDITOR_BUSY_KEY,
      sequenceKey = EVENT_EDITOR_SEQUENCE_KEY,
      completionKey = EVENT_EDITOR_COMPLETION_KEY,
    ) { complete -> saveEvent(draft, onComplete = complete) }
  }

  fun deleteEventFromEditor(id: String) {
    beginEditorOperation(
      busyKey = EVENT_EDITOR_BUSY_KEY,
      sequenceKey = EVENT_EDITOR_SEQUENCE_KEY,
      completionKey = EVENT_EDITOR_COMPLETION_KEY,
    ) { complete -> deleteEventById(id, onComplete = complete) }
  }

  fun restoreDeletedEvent(event: BriefingEvent) = restoreEventSnapshot(event, expectedCurrent = null)

  /** Restores only the exact state offered by the snackbar; a newer edit always wins. */
  fun restoreEventSnapshot(event: BriefingEvent, expectedCurrent: BriefingEvent?) {
    viewModelScope.launch {
      scheduleActionMutex.withLock {
        if (!repository.restoreEventSnapshotIfUnchanged(event, expectedCurrent)) {
          message("That event changed since then, so Undo was not applied")
          return@withLock
        }
        try {
          rescheduleReminderFor(event)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          message("Event restored, but reminders could not be refreshed")
        }
        message(
          if (expectedCurrent == null) "Restored \"${event.title}\""
          else "Moved \"${event.title}\" back"
        )
      }
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
    viewModelScope.launch {
      if (!repository.selectBoard(name)) message("That board is unavailable")
    }
  }

  fun addBoard(rawName: String, onComplete: (Boolean) -> Unit = {}) {
    val name = rawName.trim()
    if (name.isEmpty()) {
      onComplete(false)
      return
    }
    viewModelScope.launch {
      val result = repository.createBoardWithUndo(name)
      val success = result.value
      if (!success) {
        message("A board called \"$name\" already exists")
      } else {
        planMutationMessage("Created board \"$name\"", result.mutationId)
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
      val result = repository.renameBoardWithUndo(oldName, newName)
      val success = result.value
      if (!success) {
        message("A board called \"$newName\" already exists")
      } else {
        planMutationMessage("Renamed board to \"$newName\"", result.mutationId)
      }
      onComplete(success)
    }
  }

  fun deleteBoard(name: String) {
    if (name == SettingKeys.DEFAULT_BOARD) return
    viewModelScope.launch {
      val result = repository.deleteBoardWithOutcomeAndUndo(name)
      when (val outcome = result.value) {
        is BoardDeleteOutcome.Deleted ->
          planMutationMessage(
            "Moved calendar labels from \"$name\" to \"${outcome.fallbackBoard}\"",
            result.mutationId,
          )
        BoardDeleteOutcome.ContainsPlanItems ->
          message("Move or delete this board's Plan tasks before deleting the board")
        BoardDeleteOutcome.Invalid -> message("That board could not be deleted")
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
      val result = repository.createColumnWithUndo(name)
      val success = result.value
      if (!success) {
        message("That column already exists")
      } else {
        planMutationMessage("Created column \"$name\"", result.mutationId)
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
      val result = repository.renameColumnWithUndo(oldName, newName)
      val success = result.value
      if (!success) {
        message("That column already exists")
      } else {
        planMutationMessage("Renamed column to \"$newName\"", result.mutationId)
      }
      onComplete(success)
    }
  }

  fun deleteColumn(name: String) {
    viewModelScope.launch {
      val result = repository.deleteColumnWithUndo(name)
      if (!result.value) {
        message("A board needs at least one column")
      } else {
        planMutationMessage("Deleted column \"$name\"", result.mutationId)
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

  private fun planMutationMessage(text: String, mutationId: String?) {
    _messages.tryEmit(
      UiMessage(
        text = text,
        actionLabel = mutationId?.let { "Undo" },
        undoPlanMutationId = mutationId,
      )
    )
  }

  private fun beginEditorOperation(
    busyKey: String,
    sequenceKey: String,
    completionKey: String,
    start: ((Boolean) -> Unit) -> Unit,
  ) {
    if (savedStateHandle.get<Boolean>(busyKey) == true) return
    val sequence = (savedStateHandle.get<Long>(sequenceKey) ?: 0L) + 1L
    savedStateHandle[sequenceKey] = sequence
    savedStateHandle[busyKey] = true
    start { success ->
      savedStateHandle[busyKey] = false
      savedStateHandle[completionKey] = if (success) sequence else -sequence
    }
  }

  companion object {
    private const val STOP_TIMEOUT = 5_000L
    private const val DAY_TICK_MS = 60_000L
    private const val AUTO_SYNC_INTERVAL_MS = 30 * 60 * 1000L
    private const val SYNC_DAYS_BACK = 7
    private const val SYNC_DAYS_FORWARD = 30
    private const val SELECTED_DAY_KEY = "selected_day"
    private const val EVENT_EDITOR_BUSY_KEY = "event_editor_busy"
    private const val EVENT_EDITOR_SEQUENCE_KEY = "event_editor_sequence"
    private const val EVENT_EDITOR_COMPLETION_KEY = "event_editor_completion"
    private const val TASK_EDITOR_BUSY_KEY = "task_editor_busy"
    private const val TASK_EDITOR_SEQUENCE_KEY = "task_editor_sequence"
    private const val TASK_EDITOR_COMPLETION_KEY = "task_editor_completion"
    private const val DEFAULT_CALENDAR_VIEW = "Agenda"
    private const val DEFAULT_PLAN_VIEW = "Outline"
    private const val PLAN_HEALTH_DAYS = 7
  }
}
