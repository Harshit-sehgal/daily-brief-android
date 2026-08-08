package com.example.ui

import android.Manifest
import android.os.Build
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.rememberTimeFormatter
import com.example.data.api.DeviceCalendarSync
import com.example.data.model.BriefingEvent
import com.example.ui.components.EventEditorSheet
import com.example.ui.screens.BoardScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TodayScreen
import com.example.ui.screens.WeekScreen
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.EventDraft

private enum class Destination(
  val label: String,
  val icon: ImageVector,
  val hasCreateAction: Boolean = false,
) {
  Today("Today", Icons.Default.Home, hasCreateAction = true),
  Week("Week", Icons.Default.DateRange),
  Board("Board", Icons.AutoMirrored.Filled.List, hasCreateAction = true),
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
  val windowWidth = LocalWindowWidth.current
  val formatter = rememberTimeFormatter()
  val snackbarHostState = remember { SnackbarHostState() }
  val layoutDirection = LocalLayoutDirection.current

  var destinationName by rememberSaveable { mutableStateOf(Destination.Today.name) }
  val destination = remember(destinationName) { Destination.valueOf(destinationName) }
  var editing by remember { mutableStateOf<EventDraft?>(null) }

  val boards by viewModel.boards.collectAsStateWithLifecycle()
  val columns by viewModel.boardColumns.collectAsStateWithLifecycle()

  val calendarPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) viewModel.onCalendarPermissionGranted()
    }
  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

  val requestCalendarPermission: () -> Unit = {
    if (DeviceCalendarSync.hasPermission(context)) viewModel.onCalendarPermissionGranted()
    else calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
  }
  val requestNotificationPermission: () -> Unit = {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  LaunchedEffect(Unit) { viewModel.ensureAlarmsScheduled() }
  LaunchedEffect(Unit) {
    viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
  }

  BackHandler(enabled = destination != Destination.Today) {
    destinationName = Destination.Today.name
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    bottomBar = {
      if (!windowWidth.usesSideNav) {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
          Destination.entries.forEach { item ->
            NavigationBarItem(
              selected = destination == item,
              onClick = { destinationName = item.name },
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
          onClick = { editing = viewModel.newDraftFor() },
          modifier = Modifier.testTag("add_event"),
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
              onClick = { destinationName = item.name },
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
        val openEditor: (BriefingEvent) -> Unit = { editing = viewModel.draftFrom(it) }
        Box(modifier = Modifier.fillMaxSize()) {
          when (current) {
            Destination.Today ->
              TodayScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = openEditor,
                onRequestCalendarPermission = requestCalendarPermission,
                onSync = { viewModel.sync() },
              )
            Destination.Week ->
              WeekScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = openEditor,
                onOpenDay = { day ->
                  viewModel.selectDay(day)
                  destinationName = Destination.Today.name
                },
              )
            Destination.Board ->
              BoardScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = screenPadding,
                onEditEvent = openEditor,
                onAddToColumn = { column ->
                  editing = viewModel.newDraftFor().copy(column = column)
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
              )
          }
        }
      }
    }
  }

  val draft = editing
  if (draft != null) {
    EventEditorSheet(
      initial = draft,
      boards = boards,
      columns = columns,
      formatter = formatter,
      onDismiss = { editing = null },
      onSave = {
        viewModel.saveEvent(it)
        editing = null
      },
      onDelete =
        draft.id?.let { id ->
          {
            viewModel.deleteEventById(id)
            editing = null
          }
        },
    )
  }
}
