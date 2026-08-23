package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.rememberTimeFormatter
import com.example.data.api.DeviceCalendarSync
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.model.PlanItem
import com.example.receiver.BriefingAndReminderReceiver
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import com.example.core.ScheduleAnalysis
import com.example.ui.theme.UiDensity
import com.example.ui.components.CommandPalette
import com.example.ui.components.EventEditorSheet
import com.example.ui.components.PaletteItem
import com.example.ui.components.PlanPaletteProjector
import com.example.ui.components.TaskEditorSheet
import com.example.ui.screens.CalendarScreen
import com.example.ui.screens.CalendarView
import com.example.ui.screens.GanttScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PlanScreen
import com.example.ui.screens.PlanView
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.EventDraft
import com.example.ui.viewmodel.EventOwnership
import com.example.ui.viewmodel.PlanItemDraft
import com.example.ui.viewmodel.TodaySection
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

enum class NotificationFeature {
  DailyBrief,
  EventReminders,
}

private val NullableEventDraftSaver =
  Saver<EventDraft?, Bundle>(
    save = { draft ->
      Bundle().apply {
        putBoolean("present", draft != null)
        if (draft != null) {
          putString("id", draft.id)
          putString("title", draft.title)
          putString("description", draft.description)
          putLong("start", draft.startMs)
          putLong("end", draft.endMs)
          putString("source", draft.source)
          putString("ownership", draft.ownership.name)
          putBoolean("all_day", draft.isAllDay)
          putBoolean("urgent", draft.isUrgent)
          putBoolean("deadline", draft.isDeadline)
          putString("board", draft.board)
          putString("column", draft.column)
        }
      }
    },
    restore = { bundle ->
      if (!bundle.getBoolean("present")) null
      else {
        val id = bundle.getString("id")
        // A restored legacy create-draft is app-owned. An existing row whose old
        // bundle did not carry its provider must fail closed until it is reopened.
        val source = bundle.getString("source") ?: if (id == null) EventSource.MANUAL else ""
        val expectedOwnership = EventOwnership.forSource(source)
        val ownership =
          bundle.getString("ownership")
            ?.let { stored -> runCatching { EventOwnership.valueOf(stored) }.getOrNull() }
            ?.takeIf { it == expectedOwnership }
            ?: expectedOwnership
        EventDraft(
          id = id,
          title = bundle.getString("title").orEmpty(),
          description = bundle.getString("description").orEmpty(),
          startMs = bundle.getLong("start"),
          endMs = bundle.getLong("end"),
          source = source,
          ownership = ownership,
          isAllDay = bundle.getBoolean("all_day"),
          isUrgent = bundle.getBoolean("urgent"),
          isDeadline = bundle.getBoolean("deadline"),
          board = bundle.getString("board").orEmpty(),
          column = bundle.getString("column").orEmpty(),
        )
      }
    },
  )

private val NullablePlanItemDraftSaver =
  Saver<PlanItemDraft?, Bundle>(
    save = { draft ->
      Bundle().apply {
        putBoolean("present", draft != null)
        if (draft != null) {
          putString("id", draft.id)
          putString("board_id", draft.boardId)
          putString("column_id", draft.columnId)
          putString("parent_id", draft.parentId)
          putString("title", draft.title)
          putString("notes", draft.notes)
          putLong("start_constraint", draft.startConstraint ?: Long.MIN_VALUE)
          putLong("due_at", draft.dueAt ?: Long.MIN_VALUE)
          putInt("effort", draft.effortMinutes ?: Int.MIN_VALUE)
          putInt("progress", draft.progress)
          putString("priority", draft.priority)
          putString("owner", draft.owner)
          putString("scheduling_mode", draft.schedulingMode)
          putBoolean("locked", draft.locked)
          putBoolean("milestone", draft.isMilestone)
        }
      }
    },
    restore = { bundle ->
      if (!bundle.getBoolean("present")) null
      else
        PlanItemDraft(
          id = bundle.getString("id"),
          boardId = bundle.getString("board_id").orEmpty(),
          columnId = bundle.getString("column_id"),
          parentId = bundle.getString("parent_id"),
          title = bundle.getString("title").orEmpty(),
          notes = bundle.getString("notes").orEmpty(),
          startConstraint = bundle.getLong("start_constraint").takeUnless { it == Long.MIN_VALUE },
          dueAt = bundle.getLong("due_at").takeUnless { it == Long.MIN_VALUE },
          effortMinutes = bundle.getInt("effort").takeUnless { it == Int.MIN_VALUE },
          progress = bundle.getInt("progress"),
          priority = bundle.getString("priority").orEmpty(),
          owner = bundle.getString("owner").orEmpty(),
          schedulingMode = bundle.getString("scheduling_mode").orEmpty(),
          locked = bundle.getBoolean("locked"),
          isMilestone = bundle.getBoolean("milestone"),
        )
    },
  )

/** Preserve type-specific quick-capture fields while sharing the two common text fields. */
internal fun resumeTaskCapture(task: PlanItemDraft, event: EventDraft): PlanItemDraft =
  task.copy(title = event.title, notes = event.description)

internal fun resumeEventCapture(event: EventDraft, task: PlanItemDraft): EventDraft =
  event.copy(title = task.title, description = task.notes)

private enum class Destination(
  val label: String,
  val icon: ImageVector,
  val hasCreateAction: Boolean = false,
  val isPrimary: Boolean = true,
  /**
   * A page that takes the whole window: no bottom bar, no rail, no create button.
   *
   * The schedule map is the one screen here that is a *canvas*. Inside the Plan it sits under a
   * root header, a tab row and a board line, and what is left on a phone is a few rows of chart —
   * you can read it, but you cannot work in it. Opened as its own page it gets the room the day
   * timeline already has, which is what makes dragging a bar a reasonable thing to do.
   */
  val immersive: Boolean = false,
) {
  Home("Home", Icons.Default.Home, hasCreateAction = true),
  Calendar("Calendar", Icons.Default.DateRange, hasCreateAction = true),
  Plan("Plan", Icons.Default.CheckCircle, hasCreateAction = true),
  Settings("Settings", Icons.Default.Settings, isPrimary = false),
  ScheduleMap("Schedule map", Icons.Default.DateRange, isPrimary = false, immersive = true),
}

/**
 * Application shell: three primary destinations, a bottom bar on phones and a
 * side rail once the window is wide enough for one. Settings is deliberately a
 * secondary route from Home and search, and every screen shares one editor.
 */
@Composable
fun DailyBriefApp(
  viewModel: BriefingViewModel,
  isDarkTheme: Boolean,
  initialDestination: String? = null,
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val containerWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
  val windowWidth = LocalWindowWidth.current
  val formatter = rememberTimeFormatter()
  val snackbarHostState = remember { SnackbarHostState() }
  val layoutDirection = LocalLayoutDirection.current
  val stateHolder = rememberSaveableStateHolder()

  var destinationName by rememberSaveable { mutableStateOf(Destination.Home.name) }
  var paletteOpen by rememberSaveable { mutableStateOf(false) }
  var homeApplied by rememberSaveable { mutableStateOf(false) }
  var userNavigated by rememberSaveable { mutableStateOf(false) }
  var launchRootName by rememberSaveable { mutableStateOf(Destination.Home.name) }
  val home by viewModel.homeDestination.collectAsStateWithLifecycle()

  // A null value means Room has not emitted yet. Apply the stored root once,
  // unless the person already chose somewhere to go while it was loading.
  LaunchedEffect(home) {
    val storedName = home?.takeIf { it.isNotBlank() }
    if (storedName != null) {
      val stored =
        Destination.entries.firstOrNull { it.name == storedName && it.isPrimary }
          ?: Destination.Home
      launchRootName = stored.name
      if (!homeApplied && !userNavigated) destinationName = stored.name
      homeApplied = true
    }
  }
  // A launch shortcut names its destination; honor it once, over the stored root.
  LaunchedEffect(initialDestination) {
    if (initialDestination != null && !homeApplied && !userNavigated) {
      destinationName = initialDestination
      userNavigated = true
    }
  }
  var backDestinationName by rememberSaveable { mutableStateOf<String?>(null) }
  val destination = remember(destinationName) { Destination.valueOf(destinationName) }
  val launchRoot = remember(launchRootName) { Destination.valueOf(launchRootName) }
  var editing by rememberSaveable(stateSaver = NullableEventDraftSaver) {
    mutableStateOf<EventDraft?>(null)
  }
  var editorOriginal by rememberSaveable(stateSaver = NullableEventDraftSaver) {
    mutableStateOf<EventDraft?>(null)
  }
  val editorBusy by viewModel.eventEditorBusy.collectAsStateWithLifecycle()
  val eventEditorCompletion by viewModel.eventEditorCompletion.collectAsStateWithLifecycle()
  // The ViewModel completion token is durable; this acknowledgement deliberately
  // is not. After recreation the new composition must reconcile a restored sheet
  // with an operation that may have completed while the old activity was saving state.
  var handledEventEditorCompletion by remember { mutableLongStateOf(0L) }
  var editingTask by rememberSaveable(stateSaver = NullablePlanItemDraftSaver) {
    mutableStateOf<PlanItemDraft?>(null)
  }
  var taskEditorOriginal by rememberSaveable(stateSaver = NullablePlanItemDraftSaver) {
    mutableStateOf<PlanItemDraft?>(null)
  }
  val taskEditorBusy by viewModel.taskEditorBusy.collectAsStateWithLifecycle()
  val taskEditorCompletion by viewModel.taskEditorCompletion.collectAsStateWithLifecycle()
  var handledTaskEditorCompletion by remember { mutableLongStateOf(0L) }
  // Quick capture keeps one full draft per type. Switching Task ↔ Event only
  // synchronizes their shared title/notes; type-specific fields survive a round trip.
  var parkedEventCaptureDraft by rememberSaveable(stateSaver = NullableEventDraftSaver) {
    mutableStateOf<EventDraft?>(null)
  }
  var parkedTaskCaptureDraft by rememberSaveable(stateSaver = NullablePlanItemDraftSaver) {
    mutableStateOf<PlanItemDraft?>(null)
  }
  var pendingNotificationFeature by rememberSaveable {
    mutableStateOf<NotificationFeature?>(null)
  }

  val boards by viewModel.boards.collectAsStateWithLifecycle()
  val columnsByBoard by viewModel.boardColumnsByBoard.collectAsStateWithLifecycle()
  val planBoards by viewModel.planBoards.collectAsStateWithLifecycle()
  val planColumns by viewModel.planColumns.collectAsStateWithLifecycle()
  val planItems by viewModel.planItems.collectAsStateWithLifecycle()
  val activeWorkingCalendarsState by
    viewModel.activeWorkingCalendarsState.collectAsStateWithLifecycle()
  val planItemScheduleAssignmentsState by
    viewModel.planItemScheduleAssignmentsState.collectAsStateWithLifecycle()
  val planItemScheduleAssignmentsInFlight by
    viewModel.planItemScheduleAssignmentsInFlight.collectAsStateWithLifecycle()

  val openEditor: (EventDraft) -> Unit = { draft ->
    parkedEventCaptureDraft = null
    parkedTaskCaptureDraft = null
    taskEditorOriginal = null
    editorOriginal = draft
    editing = draft
  }
  val editEvent: (BriefingEvent) -> Unit = { openEditor(viewModel.draftFrom(it)) }
  val openTaskEditor: (PlanItemDraft) -> Unit = { draft ->
    parkedEventCaptureDraft = null
    parkedTaskCaptureDraft = null
    editorOriginal = null
    taskEditorOriginal = draft
    editingTask = draft
  }
  val editTask: (PlanItem) -> Unit = { openTaskEditor(viewModel.planItemDraftFrom(it)) }

  LaunchedEffect(eventEditorCompletion) {
    val token = abs(eventEditorCompletion)
    if (token == 0L || token <= handledEventEditorCompletion) return@LaunchedEffect
    handledEventEditorCompletion = token
    if (eventEditorCompletion > 0L) {
      editing = null
      editingTask = null
      editorOriginal = null
      taskEditorOriginal = null
      parkedEventCaptureDraft = null
      parkedTaskCaptureDraft = null
    }
  }
  LaunchedEffect(taskEditorCompletion) {
    val token = abs(taskEditorCompletion)
    if (token == 0L || token <= handledTaskEditorCompletion) return@LaunchedEffect
    handledTaskEditorCompletion = token
    if (taskEditorCompletion > 0L) {
      val createdFromPlan = editingTask?.id == null && destination == Destination.Plan
      editing = null
      editingTask = null
      editorOriginal = null
      taskEditorOriginal = null
      parkedEventCaptureDraft = null
      parkedTaskCaptureDraft = null
      if (createdFromPlan) viewModel.setPlanView(PlanView.Outline.label)
    }
  }

  val enableNotificationFeature: (NotificationFeature) -> Unit = { feature ->
    when (feature) {
      NotificationFeature.DailyBrief -> viewModel.setDailyBriefEnabled(true)
      NotificationFeature.EventReminders -> viewModel.setRemindersEnabled(true)
    }
  }

  // Write access is requested alongside read so an edit made here can reach the
  // calendar it came from; the app still works fully if only read is granted.
  val calendarPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      results ->
      val granted = results[Manifest.permission.READ_CALENDAR] == true
      if (granted) viewModel.onCalendarPermissionGranted()
    }
  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      val appAvailable = granted && notificationsAreAvailable(context)
      viewModel.onNotificationPermissionChecked(appAvailable)
      pendingNotificationFeature?.let { feature ->
        if (appAvailable && notificationFeatureAvailable(context, feature)) {
          enableNotificationFeature(feature)
        } else if (appAvailable) {
          viewModel.onNotificationChannelBlocked()
        } else {
          viewModel.onNotificationPermissionDenied()
        }
      }
      pendingNotificationFeature = null
    }

  val requestCalendarPermission: () -> Unit = {
    if (DeviceCalendarSync.hasPermission(context)) viewModel.onCalendarPermissionGranted()
    else
      calendarPermissionLauncher.launch(
        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
      )
  }
  val openNotificationSettings: () -> Unit = {
    val intent =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
          putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
      } else {
        Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          "package:${context.packageName}".toUri(),
        )
      }
    context.startActivity(intent)
  }
  val requestNotificationPermission: (NotificationFeature) -> Unit = { feature ->
    when {
      notificationFeatureAvailable(context, feature) -> enableNotificationFeature(feature)
      !notificationsAreAvailable(context) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
        viewModel.markNotificationPermissionRequested()
        pendingNotificationFeature = feature
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
      notificationsAreAvailable(context) -> {
        viewModel.onNotificationChannelBlocked()
        openNotificationSettings()
      }
      else -> viewModel.onNotificationPermissionDenied()
    }
  }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    viewModel.onCalendarPermissionChecked(DeviceCalendarSync.hasPermission(context))
    viewModel.onNotificationPermissionChecked(notificationsAreAvailable(context))
  }
  LaunchedEffect(Unit) { viewModel.ensureAlarmsScheduled() }
  LaunchedEffect(Unit) {
    viewModel.messages.collect { message ->
      val result =
        if (message.undoPlanMutationId != null || message.undoEvent != null) {
          withTimeoutOrNull(PLAN_UNDO_SNACKBAR_MS) {
            snackbarHostState.showSnackbar(
              message = message.text,
              actionLabel = message.actionLabel,
              withDismissAction = true,
              duration = SnackbarDuration.Indefinite,
            )
          } ?: SnackbarResult.Dismissed
        } else {
          snackbarHostState.showSnackbar(
            message = message.text,
            actionLabel = message.actionLabel,
            withDismissAction = message.actionLabel != null,
          )
        }
      if (result == SnackbarResult.ActionPerformed) {
        message.undoEvent?.let { event ->
          viewModel.restoreEventSnapshot(event, message.undoExpectedEvent)
        }
        message.undoPlanMutationId?.let(viewModel::undoPlanMutation)
      }
    }
  }

  BackHandler(
    enabled =
      editing == null && editingTask == null &&
        (backDestinationName != null || destination != launchRoot)
  ) {
    destinationName = backDestinationName ?: launchRoot.name
    backDestinationName = null
  }

  val fabEndPadding =
    if (windowWidth.usesSideNav) {
      val availableWidth = containerWidth - 80.dp
      ((availableWidth - windowWidth.readableMaxWidth) / 2).coerceAtLeast(0.dp) + windowWidth.gutter
    } else {
      0.dp
    }
  val newDraftForCurrentRoot: () -> EventDraft = {
    // Home always describes today. Calendar and Plan intentionally preserve the
    // date the person is working with, but Home must not inherit a future date
    // left behind after browsing Calendar.
    if (destination == Destination.Home) viewModel.newDraftFor(System.currentTimeMillis())
    else viewModel.newDraftFor()
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    bottomBar = {
      if (!windowWidth.usesSideNav && !destination.immersive) {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
          Destination.entries.filter { it.isPrimary }.forEach { item ->
            NavigationBarItem(
              selected = destination == item,
              onClick = {
                userNavigated = true
                destinationName = item.name
                backDestinationName = null
              },
              icon = { Icon(item.icon, contentDescription = null) },
              label = { Text(item.label) },
              modifier = Modifier.testTag("tab_${item.name}"),
            )
          }
        }
      }
    },
    floatingActionButton = {
      if (destination.hasCreateAction) {
        val isPlan = destination == Destination.Plan
        androidx.compose.material3.ExtendedFloatingActionButton(
          onClick = {
            if (isPlan) openTaskEditor(viewModel.newPlanItemDraft())
            else openEditor(newDraftForCurrentRoot())
          },
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          icon = {
            Icon(
              Icons.Default.Add,
              contentDescription = null,
            )
          },
          text = {
            androidx.compose.animation.AnimatedContent(
              targetState = if (isPlan) "New task" else "New event",
              label = "fab_label",
            ) { label -> Text(label) }
          },
          expanded = true,
          modifier =
            Modifier.padding(end = fabEndPadding)
              .testTag(if (isPlan) "add_task" else "add_event"),
        )
      }
    },
  ) { innerPadding ->
    // Screens scroll under the bars, so they take the insets as content padding
    // rather than being clipped by them. The FAB gets room at the bottom.
    val screenPadding =
      PaddingValues(
        top = innerPadding.calculateTopPadding() + Space.sm,
        // Extended FAB is wider but not taller; 80 dp leaves the same 16 dp gap above the
        // bottom bar as the old 88 dp did for the square FAB, without the dead band that
        // made the area near the plus feel like a layout breakpoint. Keep inset handling
        // explicit so immersive ScheduleMap (no bar) takes its own padding.
        bottom =
          innerPadding.calculateBottomPadding() +
            (if (destination.hasCreateAction) 80.dp else Space.lg),
      )

    Row(
      modifier =
        Modifier.fillMaxSize()
          .padding(
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
          )
    ) {
      if (windowWidth.usesSideNav && !destination.immersive) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
          Destination.entries.filter { it.isPrimary }.forEach { item ->
            NavigationRailItem(
              selected = destination == item,
              onClick = {
                userNavigated = true
                destinationName = item.name
                backDestinationName = null
              },
              icon = { Icon(item.icon, contentDescription = null) },
              label = { Text(item.label) },
              modifier = Modifier.testTag("tab_${item.name}"),
            )
          }
        }
      }

      AnimatedContent(
        targetState = destination,
        transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
        label = "destination",
        modifier = Modifier.weight(1f).fillMaxHeight(),
      ) { current ->
        stateHolder.SaveableStateProvider(current.name) {
          Box(modifier = Modifier.fillMaxSize()) {
            when (current) {
            Destination.Home ->
              HomeScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = editEvent,
                onOpenTimeline = {
                  viewModel.selectToday()
                  viewModel.setCalendarView(CalendarView.Timeline.label)
                  userNavigated = true
                  backDestinationName = Destination.Home.name
                  destinationName = Destination.Calendar.name
                },
                onOpenPalette = { paletteOpen = true },
                onOpenAgenda = {
                  viewModel.selectToday()
                  viewModel.setCalendarView(CalendarView.Agenda.label)
                  userNavigated = true
                  backDestinationName = Destination.Home.name
                  destinationName = Destination.Calendar.name
                },
                onReviewConflicts = {
                  viewModel.revealTodaySection(TodaySection.Conflicts) {
                    // Commit visibility and expansion before Calendar renders the section.
                    viewModel.selectToday()
                    viewModel.setCalendarView(CalendarView.Agenda.label)
                    userNavigated = true
                    backDestinationName = Destination.Home.name
                    destinationName = Destination.Calendar.name
                  }
                },
                onOpenBoard = {
                  viewModel.setPlanView(PlanView.Board.label)
                  userNavigated = true
                  backDestinationName = Destination.Home.name
                  destinationName = Destination.Plan.name
                },
                onOpenSettings = {
                  userNavigated = true
                  backDestinationName = Destination.Home.name
                  destinationName = Destination.Settings.name
                },
              )
            Destination.Calendar ->
              CalendarScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = editEvent,
                onCreateAt = { startMs -> openEditor(viewModel.newDraftAt(startMs)) },
                onOpenPalette = { paletteOpen = true },
                onOpenSettings = {
                  userNavigated = true
                  backDestinationName = Destination.Calendar.name
                  destinationName = Destination.Settings.name
                },
                onRequestCalendarPermission = requestCalendarPermission,
                onSync = { viewModel.sync() },
              )
            Destination.Plan ->
              PlanScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = editEvent,
                onEditTask = editTask,
                onAddTask = { columnId ->
                  openTaskEditor(viewModel.newPlanItemDraft(columnId = columnId))
                },
                onAddSubtask = { parent ->
                  openTaskEditor(
                    viewModel.newPlanItemDraft(
                      columnId = parent.columnId,
                      parentId = parent.id,
                    )
                  )
                },
                onOpenPalette = { paletteOpen = true },
                onOpenSettings = {
                  userNavigated = true
                  backDestinationName = Destination.Plan.name
                  destinationName = Destination.Settings.name
                },
                onOpenScheduleMap = {
                  userNavigated = true
                  backDestinationName = Destination.Plan.name
                  destinationName = Destination.ScheduleMap.name
                },
              )
            Destination.ScheduleMap ->
              GanttScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = editEvent,
                onEditTask = editTask,
                focused = true,
                onExitFocus = {
                  userNavigated = true
                  destinationName = Destination.Plan.name
                  backDestinationName = null
                },
              )

            Destination.Settings ->
              SettingsScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                isDarkTheme = isDarkTheme,
                onRequestCalendarPermission = requestCalendarPermission,
                onRequestNotificationPermission = requestNotificationPermission,
                onOpenNotificationSettings = openNotificationSettings,
              )
            }
          }
        }
      }
    }
  }

  if (paletteOpen) {
    val items =
      rememberPaletteItems(
        viewModel = viewModel,
        formatter = formatter,
        onGoToDay = { day ->
          viewModel.selectDay(day)
          viewModel.setCalendarView(CalendarView.Agenda.label)
          userNavigated = true
          backDestinationName = null
          destinationName = Destination.Calendar.name
        },
        onOpenEvent = editEvent,
        onOpenTask = editTask,
        onNewEvent = { openEditor(newDraftForCurrentRoot()) },
        onNewTask = { openTaskEditor(viewModel.newPlanItemDraft()) },
        onOpenTimeline = {
          viewModel.setCalendarView(CalendarView.Timeline.label)
          userNavigated = true
          backDestinationName = null
          destinationName = Destination.Calendar.name
        },
        onGo = { target ->
          userNavigated = true
          // A secondary route opened from the palette should return to the root
          // where the palette was invoked, just like the visible Settings button.
          backDestinationName =
            if (!target.isPrimary && destination.isPrimary) destination.name else null
          destinationName = target.name
        },
      )
    CommandPalette(items = items, onDismiss = { paletteOpen = false })
  }

  val draft = editing
  if (draft != null) {
    EventEditorSheet(
      draft = draft,
      original = editorOriginal ?: draft,
      hasOtherUnsavedChanges =
        parkedTaskCaptureDraft != null && parkedTaskCaptureDraft != taskEditorOriginal,
      isBusy = editorBusy,
      boards = boards,
      columnsByBoard = columnsByBoard,
      formatter = formatter,
      onDraftChange = { editing = it },
      onDismiss = {
        if (!editorBusy) {
          editing = null
          editingTask = null
          editorOriginal = null
          taskEditorOriginal = null
          parkedEventCaptureDraft = null
          parkedTaskCaptureDraft = null
        }
      },
      onSave = {
        if (!editorBusy) viewModel.saveEventFromEditor(it)
      },
      onDelete =
        draft.id?.let { id ->
          {
            if (!editorBusy) viewModel.deleteEventFromEditor(id)
          }
        },
      onSwitchToTask =
        if (draft.id == null && !editorBusy) {
          {
            parkedEventCaptureDraft = draft
            val baseTask = parkedTaskCaptureDraft ?: viewModel.newPlanItemDraft()
            val task =
              resumeTaskCapture(
                task = baseTask,
                event = draft,
              )
            editing = null
            if (taskEditorOriginal == null) taskEditorOriginal = baseTask
            editingTask = task
          }
        } else {
          null
        },
    )
  }

  val taskDraft = editingTask
  if (taskDraft != null) {
    val workScheduleDataLoaded =
      activeWorkingCalendarsState.loaded &&
        planItemScheduleAssignmentsState.loaded &&
        planItemScheduleAssignmentsState.boardId == taskDraft.boardId
    TaskEditorSheet(
      draft = taskDraft,
      original = taskEditorOriginal ?: taskDraft,
      hasOtherUnsavedChanges =
        parkedEventCaptureDraft != null && parkedEventCaptureDraft != editorOriginal,
      isBusy = taskEditorBusy,
      boards = planBoards,
      columns = planColumns,
      items = planItems,
      workSchedules = activeWorkingCalendarsState.calendars.map { it.schedule },
      workScheduleDataLoaded = workScheduleDataLoaded,
      assignedWorkScheduleId =
        if (!workScheduleDataLoaded) null
        else {
          taskDraft.id?.let { itemId ->
            planItemScheduleAssignmentsState.assignments
              .firstOrNull { it.planItemId == itemId }
              ?.workScheduleId
          }
        },
      workScheduleAssignmentBusy =
        taskDraft.id?.let { it in planItemScheduleAssignmentsInFlight } == true,
      formatter = formatter,
      onDraftChange = { editingTask = it },
      onSwitchToEvent =
        if (taskDraft.id == null && !taskEditorBusy) {
          {
            parkedTaskCaptureDraft = taskDraft
            val baseEvent = parkedEventCaptureDraft ?: newDraftForCurrentRoot()
            val event =
              resumeEventCapture(
                event = baseEvent,
                task = taskDraft,
              )
            editingTask = null
            if (editorOriginal == null) editorOriginal = baseEvent
            editing = event
          }
        } else {
          null
        },
      onDismiss = {
        if (!taskEditorBusy) {
          editingTask = null
          editing = null
          taskEditorOriginal = null
          editorOriginal = null
          parkedEventCaptureDraft = null
          parkedTaskCaptureDraft = null
        }
      },
      onSave = {
        if (!taskEditorBusy) viewModel.savePlanItemFromEditor(it)
      },
      onAssignWorkSchedule = { scheduleId ->
        taskDraft.id?.let { itemId ->
          viewModel.assignPlanItemWorkingSchedule(itemId, scheduleId)
        }
      },
      onDelete =
        taskDraft.id?.let { id ->
          {
            if (!taskEditorBusy) viewModel.deletePlanItemFromEditor(id)
          }
        },
    )
  }
}

/**
 * How long the message itself waits, not how long Undo lasts. The durable window is the
 * `Undo window` setting, honoured by Plan History; a snackbar that sat for an hour would be a
 * different bug.
 */
private const val PLAN_UNDO_SNACKBAR_MS = 30_000L

private fun notificationsAreAvailable(context: android.content.Context): Boolean {
  if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
  return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
}

private fun notificationFeatureAvailable(
  context: android.content.Context,
  feature: NotificationFeature,
): Boolean {
  if (!notificationsAreAvailable(context)) return false
  val channelId =
    when (feature) {
      NotificationFeature.DailyBrief -> BriefingAndReminderReceiver.CHANNEL_ID_BRIEF
      NotificationFeature.EventReminders -> BriefingAndReminderReceiver.CHANNEL_ID_REMINDER
    }
  return BriefingAndReminderReceiver.canPostToChannel(context, channelId)
}

/**
 * Everything the quick switcher can reach: nearby days, calendar events, active
 * Plan work, stable Plan boards, and commands that would otherwise be buried.
 */
@Composable
private fun rememberPaletteItems(
  viewModel: BriefingViewModel,
  formatter: com.example.core.TimeFormatter,
  onGoToDay: (Long) -> Unit,
  onOpenEvent: (BriefingEvent) -> Unit,
  onOpenTask: (PlanItem) -> Unit,
  onNewEvent: () -> Unit,
  onNewTask: () -> Unit,
  onOpenTimeline: () -> Unit,
  onGo: (Destination) -> Unit,
): List<PaletteItem> {
  val events by viewModel.weekEvents.collectAsStateWithLifecycle()
  val planBoards by viewModel.planBoards.collectAsStateWithLifecycle()
  val activePlanBoardId by viewModel.activePlanBoardId.collectAsStateWithLifecycle()
  val planColumns by viewModel.planColumns.collectAsStateWithLifecycle()
  val planItems by viewModel.planItems.collectAsStateWithLifecycle()
  val density by viewModel.uiDensity.collectAsStateWithLifecycle()
  val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()

  return remember(
    events,
    planBoards,
    activePlanBoardId,
    planColumns,
    planItems,
    density,
    selectedDay,
    formatter,
  ) {
    val planTargets =
      PlanPaletteProjector.project(activePlanBoardId, planBoards, planColumns, planItems)
    buildList {
      // Commands first: they are what people reach the switcher for most.
      add(
        PaletteItem("cmd_new", "New event", "Command", Icons.Default.Add, action = onNewEvent)
      )
      add(
        PaletteItem(
          "cmd_new_task",
          "New task",
          "Command",
          Icons.Default.CheckCircle,
          subtitle = "Capture flexible work in Plan Inbox",
          action = onNewTask,
        )
      )
      add(
        PaletteItem(
          "cmd_sync",
          "Sync now",
          "Command",
          Icons.Default.Refresh,
          action = { viewModel.sync() },
        )
      )
      add(
        PaletteItem(
          "cmd_brief",
          "Write the brief",
          "Command",
          Icons.Default.Edit,
          subtitle = "Summarise the selected day",
          action = {
            viewModel.revealTodaySection(TodaySection.Brief)
            viewModel.generateBrief()
            viewModel.setCalendarView(CalendarView.Agenda.label)
            onGo(Destination.Calendar)
          },
        )
      )
      add(
        PaletteItem(
          "cmd_timeline",
          "Open day timeline",
          "Command",
          Icons.Default.DateRange,
          subtitle = "See the day as a time grid",
          action = onOpenTimeline,
        )
      )
      Destination.entries.forEach { destination ->
        add(
          PaletteItem(
            "go_${destination.name}",
            "Go to ${destination.label}",
            "Command",
            destination.icon,
            action = { onGo(destination) },
          )
        )
      }
      UiDensity.entries
        .filterNot { it == density }
        .forEach { option ->
          add(
            PaletteItem(
              "density_${option.key}",
              "Density: ${option.label}",
              "Command",
              Icons.Default.Settings,
              subtitle = option.blurb,
              action = { viewModel.setDensity(option) },
            )
          )
        }

      // Days around the current selection.
      val today = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
      (-3..14).forEach { offset ->
        val day = ScheduleAnalysis.startOfDayOffset(today, offset)
        if (day != selectedDay) {
          add(
            PaletteItem(
              "day_$day",
              formatter.relativeDay(day),
              "Jump to",
              Icons.Default.DateRange,
              subtitle = formatter.fullDay(day),
              action = { onGoToDay(day) },
            )
          )
        }
      }

      planBoards.forEach { board ->
        add(
          PaletteItem(
            id = "plan_board_${board.id}",
            title = board.name,
            group = "Plan board",
            icon = Icons.AutoMirrored.Filled.List,
            subtitle = if (board.id == activePlanBoardId) "Active Plan board" else "Open Plan Board",
            searchTerms = board.id,
            action = {
              viewModel.setActivePlanBoard(board.id)
              viewModel.setPlanView(PlanView.Board.label)
              onGo(Destination.Plan)
            },
          )
        )
      }

      planTargets.forEach { target ->
        add(
          PaletteItem(
            id = target.paletteId,
            title = target.item.title,
            group = target.group,
            icon = Icons.Default.CheckCircle,
            subtitle = target.subtitle,
            trailing =
              if (target.item.isMilestone) "Milestone"
              else target.item.progress.takeIf { it > 0 }?.let { "$it%" },
            searchTerms = target.searchTerms,
            action = {
              viewModel.setActivePlanBoard(target.item.boardId)
              onOpenTask(target.item)
            },
          )
        )
      }

      events.sortedBy { it.startTime }.take(60).forEach { event ->
        add(
          PaletteItem(
            "event_${event.id}",
            event.title,
            "Event",
            Icons.Default.DateRange,
            subtitle = "${formatter.mediumDay(event.startTime)} · ${formatter.time(event.startTime)}",
            searchTerms = event.id,
            action = { onOpenEvent(event) },
          )
        )
      }
    }
  }
}
