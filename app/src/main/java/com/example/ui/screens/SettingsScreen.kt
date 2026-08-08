package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.ui.components.AppCard
import com.example.ui.components.AppTimeDialog
import com.example.ui.components.SectionLabel
import com.example.ui.theme.Accents
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.BriefingViewModel

@Composable
fun SettingsScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  isDarkTheme: Boolean,
  onRequestCalendarPermission: () -> Unit,
  onRequestNotificationPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val windowWidth = LocalWindowWidth.current
  val gutter = windowWidth.gutter

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = windowWidth.readableMaxWidth).imePadding(),
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
          NotificationSection(viewModel, formatter, onRequestNotificationPermission)
        }
      }
      item { Group("Appearance", gutter) { AppearanceSection(viewModel, isDarkTheme) } }
      item { Group("Data", gutter) { DataSection(viewModel, formatter) } }
    }
  }
}

@Composable
private fun Group(label: String, gutter: Dp, content: @Composable () -> Unit) {
  Column(modifier = Modifier.padding(horizontal = gutter)) {
    if (label.isNotEmpty()) SectionLabel(label, modifier = Modifier.padding(top = Space.sm))
    content()
  }
}

@Composable
private fun SettingRow(
  title: String,
  subtitle: String? = null,
  trailing: @Composable (() -> Unit)? = null,
) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.titleMedium)
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
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
private fun CalendarSection(viewModel: BriefingViewModel, onRequestPermission: () -> Unit) {
  val enabled by viewModel.deviceCalendarEnabled.collectAsStateWithLifecycle()
  val needsPermission by viewModel.needsCalendarPermission.collectAsStateWithLifecycle()

  AppCard(modifier = Modifier.fillMaxWidth()) {
    SettingRow(
      title = "Device calendar",
      subtitle = "Pull events from the calendars already on this phone",
      trailing = {
        Switch(
          checked = enabled,
          onCheckedChange = viewModel::setDeviceCalendarEnabled,
          modifier = Modifier.testTag("calendar_switch"),
        )
      },
    )
    AnimatedVisibility(visible = enabled && needsPermission) {
      Column {
        Spacer(Modifier.height(Space.sm))
        TextButton(onClick = onRequestPermission, contentPadding = PaddingValues(0.dp)) {
          Text("Grant calendar access")
        }
      }
    }
  }
}

@Composable
private fun NotionSection(viewModel: BriefingViewModel) {
  val storedToken by viewModel.notionToken.collectAsStateWithLifecycle()
  val storedDatabaseId by viewModel.notionDatabaseId.collectAsStateWithLifecycle()
  val enabled by viewModel.notionEnabled.collectAsStateWithLifecycle()

  var token by remember { mutableStateOf(storedToken) }
  var databaseId by remember { mutableStateOf(storedDatabaseId) }
  LaunchedEffect(storedToken) { token = storedToken }
  LaunchedEffect(storedDatabaseId) { databaseId = storedDatabaseId }

  val dirty = token != storedToken || databaseId != storedDatabaseId

  AppCard(modifier = Modifier.fillMaxWidth()) {
    SettingRow(
      title = "Notion",
      subtitle = "Read dated items from one database",
      trailing = {
        Switch(
          checked = enabled,
          onCheckedChange = { viewModel.saveNotionSettings(token, databaseId, it) },
          modifier = Modifier.testTag("notion_switch"),
        )
      },
    )
    AnimatedVisibility(visible = enabled) {
      Column {
        Spacer(Modifier.height(Space.md))
        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          label = { Text("Integration token") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth().testTag("notion_token"),
        )
        Spacer(Modifier.height(Space.sm))
        OutlinedTextField(
          value = databaseId,
          onValueChange = { databaseId = it },
          label = { Text("Database ID") },
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

  var keyName by remember { mutableStateOf("") }
  var keyValue by remember { mutableStateOf("") }
  var model by remember { mutableStateOf(storedModel) }
  var showAdd by remember { mutableStateOf(false) }
  LaunchedEffect(storedModel) { model = storedModel }

  AppCard(modifier = Modifier.fillMaxWidth()) {
    Text("Gemini key", style = MaterialTheme.typography.titleMedium)
    Text(
      text = "The brief is written by Gemini. The built-in key ships with the app.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.md))

    KeyRow(
      name = SettingKeys.DEFAULT_GEMINI_KEY_NAME,
      detail = "Bundled with the app",
      selected = BriefingRepository.isBuiltInKeyName(activeKey),
      onSelect = { viewModel.selectGeminiKey(SettingKeys.DEFAULT_GEMINI_KEY_NAME) },
      onDelete = null,
    )
    keys.forEach { (name, value) ->
      Spacer(Modifier.height(Space.sm))
      KeyRow(
        name = name,
        detail = maskKey(value),
        selected = activeKey == name,
        onSelect = { viewModel.selectGeminiKey(name) },
        onDelete = { viewModel.deleteGeminiKey(name) },
      )
    }

    Spacer(Modifier.height(Space.sm))
    if (!showAdd) {
      TextButton(onClick = { showAdd = true }, contentPadding = PaddingValues(0.dp)) {
        Text("Add your own key")
      }
    } else {
      OutlinedTextField(
        value = keyName,
        onValueChange = { keyName = it },
        label = { Text("Label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("key_name"),
      )
      Spacer(Modifier.height(Space.sm))
      OutlinedTextField(
        value = keyValue,
        onValueChange = { keyValue = it },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("key_value"),
      )
      Spacer(Modifier.height(Space.sm))
      Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Button(
          onClick = {
            viewModel.addGeminiKey(keyName, keyValue)
            keyName = ""
            keyValue = ""
            showAdd = false
          },
          enabled = keyName.isNotBlank() && keyValue.isNotBlank(),
          modifier = Modifier.weight(1f).testTag("key_save"),
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
      placeholder = { Text("gemini-2.5-flash") },
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
    modifier = Modifier.fillMaxWidth(),
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
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
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
  onRequestNotificationPermission: () -> Unit,
) {
  val briefEnabled by viewModel.dailyBriefEnabled.collectAsStateWithLifecycle()
  val hour by viewModel.briefHour.collectAsStateWithLifecycle()
  val minute by viewModel.briefMinute.collectAsStateWithLifecycle()
  val remindersEnabled by viewModel.remindersEnabled.collectAsStateWithLifecycle()
  val lead by viewModel.reminderLeadMinutes.collectAsStateWithLifecycle()
  var showTimePicker by remember { mutableStateOf(false) }

  AppCard(modifier = Modifier.fillMaxWidth()) {
    SettingRow(
      title = "Morning notification",
      subtitle = "A one-line summary of the day ahead",
      trailing = {
        Switch(
          checked = briefEnabled,
          onCheckedChange = {
            // Ask for the permission at the moment it becomes necessary, not at launch.
            if (it) onRequestNotificationPermission()
            viewModel.setDailyBriefEnabled(it)
          },
          modifier = Modifier.testTag("daily_brief_switch"),
        )
      },
    )
    AnimatedVisibility(visible = briefEnabled) {
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

    SettingRow(
      title = "Event reminders",
      subtitle = "A heads-up before something starts",
      trailing = {
        Switch(
          checked = remindersEnabled,
          onCheckedChange = {
            if (it) onRequestNotificationPermission()
            viewModel.setRemindersEnabled(it)
          },
          modifier = Modifier.testTag("reminders_switch"),
        )
      },
    )
    AnimatedVisibility(visible = remindersEnabled) {
      Column {
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
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
  var name by remember { mutableStateOf(storedName) }
  LaunchedEffect(storedName) { name = storedName }

  AppCard(modifier = Modifier.fillMaxWidth()) {
    Text("Theme", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(Space.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
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
    Text("Accent", style = MaterialTheme.typography.titleMedium)
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
          color = option.swatch(isDarkTheme),
          modifier = Modifier.size(34.dp).testTag("accent_" + option.key),
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (selected) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = option.label + " selected",
                tint = option.onSwatch(isDarkTheme),
                modifier = Modifier.size(18.dp),
              )
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

  AppCard(modifier = Modifier.fillMaxWidth()) {
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
