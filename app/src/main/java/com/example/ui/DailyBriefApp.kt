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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.receiver.BriefingAndReminderReceiver
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import com.example.core.ScheduleAnalysis
import com.example.ui.theme.UiDensity
import com.example.ui.components.CommandPalette
import com.example.ui.components.EventEditorSheet
import com.example.ui.components.PaletteItem
import com.example.ui.screens.BoardScreen
import com.example.ui.screens.DayTimelineScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TodayScreen
import com.example.ui.screens.WeekScreen
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.EventDraft

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
      else
        EventDraft(
          id = bundle.getString("id"),
          title = bundle.getString("title").orEmpty(),
          description = bundle.getString("description").orEmpty(),
          startMs = bundle.getLong("start"),
          endMs = bundle.getLong("end"),
          isAllDay = bundle.getBoolean("all_day"),
          isUrgent = bundle.getBoolean("urgent"),
          isDeadline = bundle.getBoolean("deadline"),
          board = bundle.getString("board").orEmpty(),
          column = bundle.getString("column").orEmpty(),
        )
    },
  )

private enum class Destination(
  val label: String,
  val icon: ImageVector,
  val hasCreateAction: Boolean = false,
) {
  Home("Home", Icons.Default.Home, hasCreateAction = true),
  Today("Today", Icons.AutoMirrored.Filled.List, hasCreateAction = true),
  Week("Week", Icons.Default.DateRange),
  Board("Board", Icons.Default.CheckCircle),
  Settings("Settings", Icons.Default.Settings),
}

/**
 * Application shell: four destinations, a bottom bar on phones and a side rail
 * once the window is wide enough for one, and a single event editor shared by
 * every screen.
 */
@Composable
fun DailyBriefApp(viewModel: BriefingViewModel, isDarkTheme: Boolean) {
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
  var timelineOpen by rememberSaveable { mutableStateOf(false) }
  var homeApplied by rememberSaveable { mutableStateOf(false) }
  val home by viewModel.homeDestination.collectAsStateWithLifecycle()

  // The stored home may load a beat after first composition, so apply it once
  // rather than assuming it is ready — and never after the user has navigated.
  LaunchedEffect(home) {
    if (!homeApplied) {
      Destination.entries.firstOrNull { it.name == home }?.let { destinationName = it.name }
      homeApplied = true
    }
  }
  var backDestinationName by rememberSaveable { mutableStateOf<String?>(null) }
  val destination = remember(destinationName) { Destination.valueOf(destinationName) }
  var editing by rememberSaveable(stateSaver = NullableEventDraftSaver) {
    mutableStateOf<EventDraft?>(null)
  }
  var editorOriginal by rememberSaveable(stateSaver = NullableEventDraftSaver) {
    mutableStateOf<EventDraft?>(null)
  }
  var editorBusy by rememberSaveable { mutableStateOf(false) }
  var pendingNotificationFeature by rememberSaveable {
    mutableStateOf<NotificationFeature?>(null)
  }

  val boards by viewModel.boards.collectAsStateWithLifecycle()
  val columnsByBoard by viewModel.boardColumnsByBoard.collectAsStateWithLifecycle()

  val openEditor: (EventDraft) -> Unit = { draft ->
    editorBusy = false
    editorOriginal = draft
    editing = draft
  }
  val editEvent: (BriefingEvent) -> Unit = { openEditor(viewModel.draftFrom(it)) }

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
        snackbarHostState.showSnackbar(
          message = message.text,
          actionLabel = message.actionLabel,
          withDismissAction = message.actionLabel != null,
        )
      if (result == SnackbarResult.ActionPerformed) {
        message.undoEvent?.let(viewModel::restoreDeletedEvent)
      }
    }
  }

  BackHandler(
    enabled = editing == null && (destination != Destination.Today || backDestinationName != null)
  ) {
    destinationName = backDestinationName ?: Destination.Home.name
    backDestinationName = null
  }

  val fabEndPadding =
    if (windowWidth.usesSideNav) {
      val availableWidth = containerWidth - 80.dp
      ((availableWidth - windowWidth.readableMaxWidth) / 2).coerceAtLeast(0.dp) + windowWidth.gutter
    } else {
      0.dp
    }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    bottomBar = {
      if (!windowWidth.usesSideNav) {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
          Destination.entries.forEach { item ->
            NavigationBarItem(
              selected = destination == item,
              onClick = {
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
        FloatingActionButton(
          onClick = { openEditor(viewModel.newDraftFor()) },
          // The default container is primaryContainer, which in this palette is a very
          // pale tint — too weak for the one primary action on the screen.
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.padding(end = fabEndPadding).testTag("add_event"),
        ) {
          Icon(Icons.Default.Add, contentDescription = "New event")
        }
      }
    },
  ) { innerPadding ->
    // Screens scroll under the bars, so they take the insets as content padding
    // rather than being clipped by them. The FAB gets room at the bottom.
    val screenPadding =
      PaddingValues(
        top = innerPadding.calculateTopPadding() + Space.sm,
        // Only leave room for a floating button on the screens that show one.
        bottom =
          innerPadding.calculateBottomPadding() +
            (if (destination.hasCreateAction) 88.dp else Space.lg),
      )

    Row(
      modifier =
        Modifier.fillMaxSize()
          .padding(
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
          )
    ) {
      if (windowWidth.usesSideNav) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
          Destination.entries.forEach { item ->
            NavigationRailItem(
              selected = destination == item,
              onClick = {
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
                onNewEvent = { openEditor(viewModel.newDraftFor()) },
                onOpenTimeline = { timelineOpen = true },
                onOpenPalette = { paletteOpen = true },
                onOpenAgenda = {
                  viewModel.selectToday()
                  destinationName = Destination.Today.name
                },
                onOpenBoard = { destinationName = Destination.Board.name },
              )
            Destination.Today ->
              TodayScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = editEvent,
                onNewEvent = { editing = viewModel.newDraftFor() },
                onOpenPalette = { paletteOpen = true },
                onOpenTimeline = { timelineOpen = true },
                onRequestCalendarPermission = requestCalendarPermission,
                onSync = { viewModel.sync() },
              )
            Destination.Week ->
              WeekScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = editEvent,
                onOpenDay = { day ->
                  viewModel.selectDay(day)
                  backDestinationName = Destination.Week.name
                  destinationName = Destination.Today.name
                },
              )
            Destination.Board ->
              BoardScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = editEvent,
                onAddToColumn = { column ->
                  openEditor(viewModel.newDraftFor().copy(column = column))
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
          destinationName = Destination.Today.name
        },
        onOpenEvent = editEvent,
        onNewEvent = { editing = viewModel.newDraftFor() },
        onOpenTimeline = {
          destinationName = Destination.Today.name
          timelineOpen = true
        },
        onGo = { destinationName = it.name },
      )
    CommandPalette(items = items, onDismiss = { paletteOpen = false })
  }

  if (timelineOpen) {
    DayTimelineScreen(
      viewModel = viewModel,
      formatter = formatter,
      onEditEvent = editEvent,
      onCreateAt = { startMs -> openEditor(viewModel.newDraftAt(startMs)) },
      onDismiss = { timelineOpen = false },
    )
  }

  val draft = editing
  if (draft != null) {
    EventEditorSheet(
      draft = draft,
      original = editorOriginal ?: draft,
      isBusy = editorBusy,
      boards = boards,
      columnsByBoard = columnsByBoard,
      formatter = formatter,
      onDraftChange = { editing = it },
      onDismiss = {
        if (!editorBusy) {
          editing = null
          editorOriginal = null
        }
      },
      onSave = {
        if (!editorBusy) {
          editorBusy = true
          viewModel.saveEvent(it) { success ->
            editorBusy = false
            if (success) {
              editing = null
              editorOriginal = null
            }
          }
        }
      },
      onDelete =
        draft.id?.let { id ->
          {
            if (!editorBusy) {
              editorBusy = true
              viewModel.deleteEventById(id) { success ->
                editorBusy = false
                if (success) {
                  editing = null
                  editorOriginal = null
                }
              }
            }
          }
        },
    )
  }
}

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
 * Everything the quick switcher can reach: the days around now, the events in
 * view, every board, and the commands that would otherwise be buried in menus.
 */
@Composable
private fun rememberPaletteItems(
  viewModel: BriefingViewModel,
  formatter: com.example.core.TimeFormatter,
  onGoToDay: (Long) -> Unit,
  onOpenEvent: (BriefingEvent) -> Unit,
  onNewEvent: () -> Unit,
  onOpenTimeline: () -> Unit,
  onGo: (Destination) -> Unit,
): List<PaletteItem> {
  val events by viewModel.weekEvents.collectAsStateWithLifecycle()
  val boards by viewModel.boards.collectAsStateWithLifecycle()
  val density by viewModel.uiDensity.collectAsStateWithLifecycle()
  val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()

  return remember(events, boards, density, selectedDay, formatter) {
    buildList {
      // Commands first: they are what people reach the switcher for most.
      add(
        PaletteItem("cmd_new", "New event", "Command", Icons.Default.Add, action = onNewEvent)
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
          action = { viewModel.generateBrief() },
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

      boards.forEach { board ->
        add(
          PaletteItem(
            "board_$board",
            board,
            "Board",
            Icons.AutoMirrored.Filled.List,
            action = {
              viewModel.selectBoard(board)
              onGo(Destination.Board)
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
            action = { onOpenEvent(event) },
          )
        )
      }
    }
  }
}
