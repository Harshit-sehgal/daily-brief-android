package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.prefs.SettingKeys
import com.example.ui.components.AppTimeDialog
import com.example.ui.components.SectionLabel
import com.example.ui.NotificationFeature
import com.example.ui.theme.Accents
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import com.example.ui.components.RowIconButton
import com.example.ui.theme.UiDensity
import com.example.ui.viewmodel.TodaySection
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.HomeCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  isDarkTheme: Boolean,
  onRequestCalendarPermission: () -> Unit,
  onRequestNotificationPermission: (NotificationFeature) -> Unit,
  onOpenNotificationSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val windowWidth = LocalWindowWidth.current
  val gutter = windowWidth.gutter

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = windowWidth.readableMaxWidth).fillMaxSize().imePadding(),
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
      item {
        Text(
          text = "Settings",
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.padding(horizontal = gutter),
        )
      }

      item { Group("Connections", gutter) { CalendarSection(viewModel, onRequestCalendarPermission) } }
      item { Group("", gutter) { NotionSection(viewModel) } }
      item { Group("Brief", gutter) { AiSection(viewModel) } }
      item {
        Group("Notifications", gutter) {
          NotificationSection(
            viewModel,
            formatter,
            onRequestNotificationPermission,
            onOpenNotificationSettings,
          )
        }
      }
      item { Group("Appearance", gutter) { AppearanceSection(viewModel, isDarkTheme) } }
      item { Group("Workspace", gutter) { WorkspaceSection(viewModel) } }
      item { Group("Data", gutter) { DataSection(viewModel, formatter) } }
    }
  }
}

/**
 * One settings group, headed the same way sections are headed everywhere else.
 *
 * No card: the rule under the label is what separates groups, so a long settings
 * page reads as one continuous list rather than a stack of floating panels.
 */
@Composable
private fun Group(label: String, gutter: Dp, content: @Composable () -> Unit) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme

  Column(modifier = Modifier.padding(start = gutter, end = gutter)) {
    if (label.isNotEmpty()) {
      Spacer(Modifier.height(d.sectionGap))
      Row(
        modifier = Modifier.fillMaxWidth().height(d.sectionHeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = label.uppercase(),
          fontSize = d.label,
          fontWeight = FontWeight.Medium,
          letterSpacing = 0.8.sp,
          color = scheme.onSurfaceVariant,
        )
      }
      Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
    }
    content()
  }
}

/** The contents of a group. Flat, because the group heading already frames it. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xs),
    content = content,
  )
}

@Composable
private fun SettingRow(
  title: String,
  subtitle: String? = null,
  trailing: @Composable (() -> Unit)? = null,
) {
  val d = LocalDensityTokens.current
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = d.rowHeight),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        fontSize = d.title,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (subtitle != null) {
        Text(
          text = subtitle,
          fontSize = d.secondary,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (trailing != null) {
      Spacer(Modifier.width(Space.md))
      trailing()
    }
  }
}

@Composable
private fun ToggleSettingRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String,
) {
  val d = LocalDensityTokens.current
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = d.rowHeight)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(vertical = Space.xs),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        fontSize = d.title,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = subtitle,
        fontSize = d.secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.width(Space.md))
    Switch(
      checked = checked,
      onCheckedChange = null,
      modifier = Modifier.testTag(testTag),
    )
  }
}

@Composable
private fun CalendarSection(viewModel: BriefingViewModel, onRequestPermission: () -> Unit) {
  val writeBack by viewModel.calendarWriteBack.collectAsStateWithLifecycle()
  val enabled by viewModel.deviceCalendarEnabled.collectAsStateWithLifecycle()
  val needsPermission by viewModel.needsCalendarPermission.collectAsStateWithLifecycle()

  Panel {
    ToggleSettingRow(
      title = "Device calendar",
      subtitle = "Pull events from the calendars already on this phone",
      checked = enabled,
      onCheckedChange = viewModel::setDeviceCalendarEnabled,
      testTag = "calendar_switch",
    )
    AnimatedVisibility(visible = enabled && needsPermission) {
      Column {
        Spacer(Modifier.height(Space.sm))
        TextButton(onClick = onRequestPermission, contentPadding = PaddingValues(0.dp)) {
          Text("Grant calendar access")
        }
      }
    }
    AnimatedVisibility(visible = enabled) {
      Column {
        Spacer(Modifier.height(Space.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(Space.md))
        ToggleSettingRow(
          title = "Write edits back",
          subtitle =
            "Changes you make here update the calendar entry too. Repeating and " +
              "all-day events stay local.",
          checked = writeBack,
          onCheckedChange = { wanted ->
            if (wanted) onRequestPermission()
            viewModel.setCalendarWriteBack(wanted)
          },
          testTag = "write_back_switch",
        )
      }
    }
  }
}

@Composable
private fun NotionSection(viewModel: BriefingViewModel) {
  val storedToken by viewModel.notionToken.collectAsStateWithLifecycle()
  val storedDatabaseId by viewModel.notionDatabaseId.collectAsStateWithLifecycle()
  val enabled by viewModel.notionEnabled.collectAsStateWithLifecycle()
  val draft by viewModel.notionCredentialDraft.collectAsStateWithLifecycle()

  val token = draft?.token ?: storedToken
  val databaseId = draft?.databaseId ?: storedDatabaseId

  val dirty = token != storedToken || databaseId != storedDatabaseId

  Panel {
    ToggleSettingRow(
      title = "Notion",
      subtitle = "Read dated items from one database",
      checked = enabled,
      onCheckedChange = { viewModel.saveNotionSettings(token, databaseId, it) },
      testTag = "notion_switch",
    )
    AnimatedVisibility(visible = enabled) {
      Column {
        Spacer(Modifier.height(Space.md))
        OutlinedTextField(
          value = token,
          onValueChange = { viewModel.updateNotionCredentialDraft(it, databaseId) },
          label = { Text("Integration token") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth().testTag("notion_token"),
        )
        Spacer(Modifier.height(Space.sm))
        OutlinedTextField(
          value = databaseId,
          onValueChange = { viewModel.updateNotionCredentialDraft(token, it) },
          label = { Text("Database or data source ID") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("notion_db"),
        )
        Spacer(Modifier.height(Space.sm))
        Button(
          onClick = { viewModel.saveNotionSettings(token, databaseId, true) },
          enabled = dirty,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Save and sync")
        }
      }
    }
  }
}

@Composable
private fun AiSection(viewModel: BriefingViewModel) {
  val activeKey by viewModel.activeGeminiKeyName.collectAsStateWithLifecycle()
  val keys by viewModel.geminiKeys.collectAsStateWithLifecycle()
  val storedModel by viewModel.geminiModel.collectAsStateWithLifecycle()
  val credentialDraft by viewModel.geminiCredentialDraft.collectAsStateWithLifecycle()

  var model by rememberSaveable { mutableStateOf(storedModel) }
  var showAdd by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(storedModel) { model = storedModel }

  Panel {
    Text("Gemini key", fontSize = LocalDensityTokens.current.title, fontWeight = FontWeight.Medium)
    Text(
      text = "Add your own Gemini key. It is encrypted on this device and never backed up.",
      fontSize = LocalDensityTokens.current.secondary,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.md))

    keys.forEach { (name, value) ->
      KeyRow(
        name = name,
        detail = maskKey(value),
        selected = activeKey == name,
        onSelect = { viewModel.selectGeminiKey(name) },
        onDelete = { viewModel.deleteGeminiKey(name) },
      )
      Spacer(Modifier.height(Space.sm))
    }

    if (keys.isEmpty()) {
      Text(
        text = "No key added — briefs use the on-device fallback.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Spacer(Modifier.height(Space.sm))
    if (!showAdd) {
      TextButton(onClick = { showAdd = true }, contentPadding = PaddingValues(0.dp)) {
        Text("Add your own key")
      }
    } else {
      OutlinedTextField(
        value = credentialDraft.name,
        onValueChange = {
          viewModel.updateGeminiCredentialDraft(it, credentialDraft.key)
        },
        label = { Text("Label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("key_name"),
      )
      Spacer(Modifier.height(Space.sm))
      OutlinedTextField(
        value = credentialDraft.key,
        onValueChange = {
          viewModel.updateGeminiCredentialDraft(credentialDraft.name, it)
        },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("key_value"),
      )
      Spacer(Modifier.height(Space.sm))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Button(
          onClick = {
            viewModel.addGeminiKey(credentialDraft.name, credentialDraft.key)
            showAdd = false
          },
          enabled = credentialDraft.name.isNotBlank() && credentialDraft.key.isNotBlank(),
          modifier = Modifier.testTag("key_save"),
        ) {
          Text("Save key")
        }
        TextButton(onClick = { showAdd = false }) { Text("Cancel") }
      }
    }

    Spacer(Modifier.height(Space.md))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(Space.md))

    OutlinedTextField(
      value = model,
      onValueChange = { model = it },
      label = { Text("Model (optional)") },
      placeholder = { Text("gemini-3.6-flash") },
      supportingText = { Text("Leave empty to use the default, with automatic fallbacks.") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("model_field"),
    )
    AnimatedVisibility(visible = model != storedModel) {
      Column {
        Spacer(Modifier.height(Space.sm))
        Button(onClick = { viewModel.setGeminiModel(model) }, modifier = Modifier.fillMaxWidth()) {
          Text("Save model")
        }
      }
    }
  }
}

private fun maskKey(value: String): String =
  if (value.length > 8) "${value.take(4)}…${value.takeLast(4)}" else "••••••••"

@Composable
private fun KeyRow(
  name: String,
  detail: String,
  selected: Boolean,
  onSelect: () -> Unit,
  onDelete: (() -> Unit)?,
) {
  Surface(
    onClick = onSelect,
    shape = MaterialTheme.shapes.medium,
    color =
      if (selected) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier =
      Modifier.fillMaxWidth().semantics {
        this.selected = selected
        contentDescription = "$name, $detail"
      },
  ) {
    Row(
      modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (selected) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = "In use",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Space.sm))
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(text = name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          text = detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (onDelete != null) {
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
          Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = "Remove $name",
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun NotificationSection(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  onRequestNotificationPermission: (NotificationFeature) -> Unit,
  onOpenNotificationSettings: () -> Unit,
) {
  val briefEnabled by viewModel.dailyBriefEnabled.collectAsStateWithLifecycle()
  val hour by viewModel.briefHour.collectAsStateWithLifecycle()
  val minute by viewModel.briefMinute.collectAsStateWithLifecycle()
  val remindersEnabled by viewModel.remindersEnabled.collectAsStateWithLifecycle()
  val notificationsAvailable by viewModel.notificationsAvailable.collectAsStateWithLifecycle()
  val dailyNotificationsAvailable by
    viewModel.dailyNotificationsAvailable.collectAsStateWithLifecycle()
  val reminderNotificationsAvailable by
    viewModel.reminderNotificationsAvailable.collectAsStateWithLifecycle()
  val permissionRequested by
    viewModel.notificationPermissionRequested.collectAsStateWithLifecycle()
  val lead by viewModel.reminderLeadMinutes.collectAsStateWithLifecycle()
  var showTimePicker by remember { mutableStateOf(false) }

  Panel {
    ToggleSettingRow(
      title = "Morning notification",
      subtitle =
        if (dailyNotificationsAvailable) "A one-line summary of the day ahead"
        else if (briefEnabled) "Enabled here, but blocked by Android"
        else "Off until notifications are allowed",
      checked = briefEnabled,
      onCheckedChange = { enabled ->
        if (enabled) onRequestNotificationPermission(NotificationFeature.DailyBrief)
        else viewModel.setDailyBriefEnabled(false)
      },
      testTag = "daily_brief_switch",
    )
    AnimatedVisibility(visible = briefEnabled && dailyNotificationsAvailable) {
      Column {
        Spacer(Modifier.height(Space.sm))
        TextButton(onClick = { showTimePicker = true }, contentPadding = PaddingValues(0.dp)) {
          Text("At ${formatTime(formatter, hour, minute)}")
        }
      }
    }

    Spacer(Modifier.height(Space.md))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(Space.md))

    ToggleSettingRow(
      title = "Event reminders",
      subtitle =
        if (reminderNotificationsAvailable) "A heads-up before something starts"
        else if (remindersEnabled) "Enabled here, but blocked by Android"
        else "Off until notifications are allowed",
      checked = remindersEnabled,
      onCheckedChange = { enabled ->
        if (enabled) onRequestNotificationPermission(NotificationFeature.EventReminders)
        else viewModel.setRemindersEnabled(false)
      },
      testTag = "reminders_switch",
    )
    AnimatedVisibility(visible = remindersEnabled && reminderNotificationsAvailable) {
      Column {
        Spacer(Modifier.height(Space.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
          listOf(5, 15, 30, 60).forEach { minutes ->
            FilterChip(
              selected = lead == minutes,
              onClick = { viewModel.setReminderLead(minutes) },
              label = { Text("${minutes}m") },
            )
          }
        }
      }
    }

    val blocked =
      (briefEnabled && !dailyNotificationsAvailable) ||
        (remindersEnabled && !reminderNotificationsAvailable) ||
        (!notificationsAvailable && permissionRequested)
    AnimatedVisibility(visible = blocked) {
      Column {
        Spacer(Modifier.height(Space.md))
        Text(
          text = "Android is currently blocking Daily Brief notifications.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onOpenNotificationSettings, contentPadding = PaddingValues(0.dp)) {
          Text("Open notification settings")
        }
      }
    }
  }

  if (showTimePicker) {
    AppTimeDialog(
      title = "Notify me at",
      initialHour = hour,
      initialMinute = minute,
      is24Hour = formatter.is24Hour,
      onDismiss = { showTimePicker = false },
      onConfirm = { newHour, newMinute ->
        viewModel.setBriefTime(newHour, newMinute)
        showTimePicker = false
      },
    )
  }
}

private fun formatTime(formatter: TimeFormatter, hour: Int, minute: Int): String {
  val base = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
  return formatter.time(ScheduleAnalysis.withTimeOfDay(base, hour, minute))
}

@Composable
private fun AppearanceSection(viewModel: BriefingViewModel, isDarkTheme: Boolean) {
  val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
  val accent by viewModel.themeAccent.collectAsStateWithLifecycle()
  val storedName by viewModel.profileName.collectAsStateWithLifecycle()
  var name by rememberSaveable { mutableStateOf(storedName) }
  LaunchedEffect(storedName) { name = storedName }

  Panel {
    Text("Theme", fontSize = LocalDensityTokens.current.title, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(Space.sm))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
      listOf(
          SettingKeys.THEME_MODE_SYSTEM to "System",
          SettingKeys.THEME_MODE_LIGHT to "Light",
          SettingKeys.THEME_MODE_DARK to "Dark",
        )
        .forEach { (key, label) ->
          FilterChip(
            selected = themeMode == key,
            onClick = { viewModel.setThemeMode(key) },
            label = { Text(label) },
          )
        }
    }

    Spacer(Modifier.height(Space.lg))
    Text("Accent", fontSize = LocalDensityTokens.current.title, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(Space.sm))
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
      Accents.all.forEach { option ->
        val selected = option.key == accent
        Surface(
          onClick = { viewModel.setThemeAccent(option.key) },
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surface,
          modifier =
            Modifier.size(48.dp)
              .semantics {
                contentDescription = "${option.label} accent"
                this.selected = selected
              }
              .testTag("accent_" + option.key),
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
              shape = CircleShape,
              color = option.swatch(isDarkTheme),
              modifier = Modifier.size(34.dp),
            ) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (selected) {
                  Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = option.onSwatch(isDarkTheme),
                    modifier = Modifier.size(18.dp),
                  )
                }
              }
            }
          }
        }
      }
    }

    Spacer(Modifier.height(Space.lg))
    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = { Text("Your name") },
      supportingText = { Text("Used in the greeting. Leave empty to skip it.") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("profile_name"),
    )
    AnimatedVisibility(visible = name != storedName) {
      Column {
        Spacer(Modifier.height(Space.sm))
        Button(onClick = { viewModel.setProfileName(name) }, modifier = Modifier.fillMaxWidth()) {
          Text("Save name")
        }
      }
    }
  }
}

@Composable
private fun DataSection(viewModel: BriefingViewModel, formatter: TimeFormatter) {
  val lastSync by viewModel.lastSyncAt.collectAsStateWithLifecycle()
  val syncing by viewModel.isSyncing.collectAsStateWithLifecycle()
  val syncProblem by viewModel.syncProblem.collectAsStateWithLifecycle()

  Panel {
    SettingRow(
      title = "Sync now",
      subtitle =
        if (lastSync == 0L) "Never synced"
        else "Last synced ${formatter.relativeDay(lastSync)} at ${formatter.time(lastSync)}",
      trailing = {
        TextButton(onClick = { viewModel.sync() }, enabled = !syncing) {
          Text(if (syncing) "Syncing…" else "Sync")
        }
      },
    )

    AnimatedVisibility(visible = syncProblem != null) {
      Column {
        Spacer(Modifier.height(Space.sm))
        Text(
          text = syncProblem.orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    Spacer(Modifier.height(Space.md))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(Space.md))

    SettingRow(
      title = "Sample day",
      subtitle = "Adds four example events to the selected day so you can see the app working",
    )
    Spacer(Modifier.height(Space.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
      Button(onClick = viewModel::loadSampleDay, modifier = Modifier.weight(1f)) { Text("Add") }
      TextButton(onClick = viewModel::clearSampleData, modifier = Modifier.weight(1f)) {
        Text("Remove")
      }
    }
  }
}

/**
 * Layout controls: how tightly the workspace packs, which Today sections appear
 * and in what order, and how far the Week view looks ahead.
 */
@Composable
private fun WorkspaceSection(viewModel: BriefingViewModel) {
  val homeCards by viewModel.homeCards.collectAsStateWithLifecycle()
  val home by viewModel.homeDestination.collectAsStateWithLifecycle()
  val density by viewModel.uiDensity.collectAsStateWithLifecycle()
  val sections by viewModel.todaySections.collectAsStateWithLifecycle()
  val weekSpan by viewModel.weekSpanDays.collectAsStateWithLifecycle()

  Panel {
    Text("Home page", fontSize = LocalDensityTokens.current.title, fontWeight = FontWeight.Medium)
    Text(
      text = "The screen the app opens on.",
      fontSize = LocalDensityTokens.current.secondary,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.sm))
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
      listOf("Home", "Today", "Week", "Board").forEach { option ->
        FilterChip(
          selected = home == option,
          onClick = { viewModel.setHomeDestination(option) },
          label = { Text(option) },
          modifier = Modifier.testTag("home_" + option),
        )
      }
    }

    Spacer(Modifier.height(Space.lg))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(Space.lg))

    Text("Home blocks", fontSize = LocalDensityTokens.current.title, fontWeight = FontWeight.Medium)
    Text(
      text = "What the landing page shows, and in what order.",
      fontSize = LocalDensityTokens.current.secondary,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.sm))
    HomeCard.entries.forEach { card ->
      val shown = homeCards.contains(card)
      val index = homeCards.indexOf(card)
      SettingRow(
        title = card.label,
        subtitle = card.blurb,
        trailing = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            RowIconButton(
              icon = Icons.Default.KeyboardArrowUp,
              contentDescription = "Move ${'$'}{card.label} up",
              onClick = { viewModel.moveHomeCard(card, -1) },
              enabled = shown && index > 0,
            )
            RowIconButton(
              icon = Icons.Default.KeyboardArrowDown,
              contentDescription = "Move ${'$'}{card.label} down",
              onClick = { viewModel.moveHomeCard(card, 1) },
              enabled = shown && index >= 0 && index < homeCards.lastIndex,
            )
            Spacer(Modifier.width(Space.sm))
            Switch(
              checked = shown,
              onCheckedChange = { viewModel.setHomeCardVisible(card, it) },
              modifier = Modifier.testTag("home_card_" + card.key),
            )
          }
        },
      )
    }

    Spacer(Modifier.height(Space.lg))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(Space.lg))

    Text("Density", fontSize = LocalDensityTokens.current.title, fontWeight = FontWeight.Medium)
    Text(
      text = "Row heights, spacing and type size across the whole app.",
      fontSize = LocalDensityTokens.current.secondary,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
      UiDensity.entries.forEach { option ->
        FilterChip(
          selected = density == option,
          onClick = { viewModel.setDensity(option) },
          label = { Text(option.label) },
          modifier = Modifier.testTag("density_" + option.key),
        )
      }
    }

    Spacer(Modifier.height(Space.lg))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(Space.lg))

    Text("Today sections", fontSize = LocalDensityTokens.current.title, fontWeight = FontWeight.Medium)
    Text(
      text = "Turn sections off, or move them into the order you read in.",
      fontSize = LocalDensityTokens.current.secondary,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.sm))
    TodaySection.entries.forEach { section ->
      val visible = sections.contains(section)
      val position = sections.indexOf(section)
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(section.label, style = MaterialTheme.typography.bodyLarge)
          Text(
            text = section.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        RowIconButton(
          icon = Icons.Default.KeyboardArrowUp,
          contentDescription = "Move " + section.label + " up",
          enabled = visible && position > 0,
          onClick = { viewModel.moveSection(section, -1) },
        )
        RowIconButton(
          icon = Icons.Default.KeyboardArrowDown,
          contentDescription = "Move " + section.label + " down",
          enabled = visible && position >= 0 && position < sections.lastIndex,
          onClick = { viewModel.moveSection(section, 1) },
        )
        Switch(
          checked = visible,
          onCheckedChange = { viewModel.setSectionVisible(section, it) },
          modifier = Modifier.testTag("section_" + section.key),
        )
      }
    }

    Spacer(Modifier.height(Space.lg))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(Space.lg))

    SettingRow(title = "Week view spans", subtitle = weekSpan.toString() + " days")
    Spacer(Modifier.height(Space.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
      listOf(7, 14, 30).forEach { days ->
        FilterChip(
          selected = weekSpan == days,
          onClick = { viewModel.setWeekSpan(days) },
          label = { Text(days.toString() + "d") },
        )
      }
    }
  }
}
