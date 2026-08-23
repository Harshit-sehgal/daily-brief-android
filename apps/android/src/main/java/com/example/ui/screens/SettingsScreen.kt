package com.example.ui.screens

import com.example.ui.theme.InlineIconSize
import com.example.ui.theme.MinimumTouchTarget
import com.example.data.prefs.UndoWindowPolicy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.core.WorkingCalendarSpec
import com.example.data.backup.BackupPreview
import com.example.data.prefs.SettingKeys
import com.example.data.repository.PersistedWorkingCalendar
import com.example.ui.components.AppTimeDialog
import com.example.ui.components.WorkingScheduleEditPolicy
import com.example.ui.components.WorkingScheduleEditor
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
import com.example.ui.viewmodel.ActiveWorkingCalendarsState
import com.example.ui.viewmodel.BriefingViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.ui.viewmodel.HomeCard
import java.text.Normalizer
import java.util.Locale

private const val CATEGORY_CUSTOMIZE = "customize"
private const val CATEGORY_PLANNING = "planning"
private const val CATEGORY_CONNECTIONS = "connections"
private const val CATEGORY_BRIEF = "brief"
private const val CATEGORY_NOTIFICATIONS = "notifications"
private const val CATEGORY_DATA = "data"

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
  val stateHolder = rememberSaveableStateHolder()
  var expandedCategory by rememberSaveable { mutableStateOf<String?>(CATEGORY_CUSTOMIZE) }
  val toggleCategory: (String) -> Unit = { category ->
    expandedCategory = category.takeUnless { it == expandedCategory }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier =
        Modifier.widthIn(max = windowWidth.readableMaxWidth)
          .fillMaxSize()
          .imePadding()
          .testTag("settings_list"),
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
      item(key = "settings_header") {
        Text(
          text = "Settings",
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.padding(horizontal = gutter, vertical = Space.xs),
        )
      }

      item(key = CATEGORY_CUSTOMIZE) {
        SettingsDisclosure(
          title = "Customize",
          summary = "Appearance, start page and workspace layout",
          expanded = expandedCategory == CATEGORY_CUSTOMIZE,
          onToggle = { toggleCategory(CATEGORY_CUSTOMIZE) },
          gutter = gutter,
        ) {
          stateHolder.SaveableStateProvider(CATEGORY_CUSTOMIZE) {
            SettingsSubheading("Appearance")
            AppearanceSection(viewModel, isDarkTheme)
            SettingsSubsectionDivider()
            SettingsSubheading("Workspace")
            WorkspaceSection(viewModel)
          }
        }
      }

      item(key = CATEGORY_CONNECTIONS) {
        SettingsDisclosure(
          title = "Connections",
          summary = "Device calendar and Notion",
          expanded = expandedCategory == CATEGORY_CONNECTIONS,
          onToggle = { toggleCategory(CATEGORY_CONNECTIONS) },
          gutter = gutter,
        ) {
          stateHolder.SaveableStateProvider(CATEGORY_CONNECTIONS) {
            CalendarSection(viewModel, onRequestCalendarPermission)
            SettingsSubsectionDivider()
            NotionSection(viewModel)
          }
        }
      }

      item(key = CATEGORY_PLANNING) {
        SettingsDisclosure(
          title = "Planning",
          summary = "Working schedules, chunks and commitment buffer",
          expanded = expandedCategory == CATEGORY_PLANNING,
          onToggle = { toggleCategory(CATEGORY_PLANNING) },
          gutter = gutter,
        ) {
          stateHolder.SaveableStateProvider(CATEGORY_PLANNING) {
            PlanningSection(viewModel)
          }
        }
      }

      item(key = CATEGORY_BRIEF) {
        SettingsDisclosure(
          title = "Brief",
          summary = "Gemini key and writing model",
          expanded = expandedCategory == CATEGORY_BRIEF,
          onToggle = { toggleCategory(CATEGORY_BRIEF) },
          gutter = gutter,
        ) {
          stateHolder.SaveableStateProvider(CATEGORY_BRIEF) { AiSection(viewModel) }
        }
      }

      item(key = CATEGORY_NOTIFICATIONS) {
        SettingsDisclosure(
          title = "Notifications",
          summary = "Morning brief and event reminders",
          expanded = expandedCategory == CATEGORY_NOTIFICATIONS,
          onToggle = { toggleCategory(CATEGORY_NOTIFICATIONS) },
          gutter = gutter,
        ) {
          stateHolder.SaveableStateProvider(CATEGORY_NOTIFICATIONS) {
            NotificationSection(
              viewModel,
              formatter,
              onRequestNotificationPermission,
              onOpenNotificationSettings,
            )
          }
        }
      }

      item(key = CATEGORY_DATA) {
        SettingsDisclosure(
          title = "Data",
          summary = "Sync status and sample content",
          expanded = expandedCategory == CATEGORY_DATA,
          onToggle = { toggleCategory(CATEGORY_DATA) },
          gutter = gutter,
        ) {
          stateHolder.SaveableStateProvider(CATEGORY_DATA) { DataSection(viewModel, formatter, gutter) }
        }
      }
    }
  }
}

/**
 * One calm settings category. Only one category is open at a time, so entering
 * Settings starts with useful customization rather than a wall of connection forms.
 */
@Composable
private fun SettingsDisclosure(
  title: String,
  summary: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  gutter: Dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme

  Column(modifier = Modifier.padding(start = gutter, end = gutter)) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(min = 48.dp)
          .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
          .clickable(
            role = Role.Button,
            onClickLabel = if (expanded) "Collapse $title" else "Expand $title",
            onClick = onToggle,
          )
          .padding(vertical = Space.xs)
          .testTag("settings_category_" + title.lowercase()),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          fontSize = d.title,
          fontWeight = FontWeight.SemiBold,
          color = scheme.onSurface,
        )
        Text(text = summary, fontSize = d.secondary, color = scheme.onSurfaceVariant)
      }
      Icon(
        imageVector =
          if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = null,
        tint = scheme.onSurfaceVariant,
        modifier = Modifier.size(InlineIconSize),
      )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
    AnimatedVisibility(visible = expanded) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.sm),
        content = content,
      )
    }
  }
}

@Composable
private fun PlanningSection(viewModel: BriefingViewModel) {
  val calendars by viewModel.activeWorkingCalendarsState.collectAsStateWithLifecycle()
  val defaultCalendar by viewModel.workingCalendar.collectAsStateWithLifecycle()
  val saving by viewModel.workingCalendarSaving.collectAsStateWithLifecycle()
  val problem by viewModel.workingCalendarProblem.collectAsStateWithLifecycle()
  val undoWindowSeconds by viewModel.undoWindowSeconds.collectAsStateWithLifecycle()

  UndoWindowSetting(
    selectedSeconds = undoWindowSeconds,
    onSelect = viewModel::setUndoWindowSeconds,
  )
  Spacer(Modifier.height(Space.lg))
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  Spacer(Modifier.height(Space.lg))

  WorkingScheduleManager(
    calendarsState = calendars,
    defaultCalendar = defaultCalendar,
    isSaving = saving,
    problem = problem,
    onCreate = { name, spec, done -> viewModel.createWorkingSchedule(name, spec, done) },
    onUpdate = { id, name, spec, done ->
      viewModel.updateWorkingSchedule(id, name, spec, done)
    },
    onMakeDefault = { id, done -> viewModel.makeWorkingScheduleDefault(id, done) },
    onArchive = { id, replacementId, done ->
      viewModel.archiveWorkingSchedule(id, replacementId, done)
    },
  )
}

/**
 * How long a change stays undoable.
 *
 * Thirty seconds is not enough time for everyone to notice a mistake, reach Plan History and act,
 * so the window a person needs is the window they get.
 */
@Composable
internal fun UndoWindowSetting(selectedSeconds: Int, onSelect: (Int) -> Unit) {
  Text("Undo window", fontSize = LocalDensityTokens.current.title, fontWeight = FontWeight.Medium)
  Text(
    text =
      "How long Plan History keeps Undo available after a change. The message at the bottom of " +
        "the screen is always brief; this is the window behind it.",
    fontSize = LocalDensityTokens.current.secondary,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(Space.sm))
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(Space.sm),
    verticalArrangement = Arrangement.spacedBy(Space.xs),
    modifier = Modifier.fillMaxWidth().testTag("undo_window_choices"),
  ) {
    UndoWindowPolicy.Choices.forEach { seconds ->
      FilterChip(
        selected = selectedSeconds == seconds,
        onClick = { onSelect(seconds) },
        label = { Text(UndoWindowPolicy.label(seconds)) },
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("undo_window_$seconds"),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkingScheduleManager(
  calendarsState: ActiveWorkingCalendarsState,
  defaultCalendar: PersistedWorkingCalendar?,
  isSaving: Boolean,
  problem: String?,
  onCreate: (String, WorkingCalendarSpec, (PersistedWorkingCalendar?) -> Unit) -> Unit,
  onUpdate:
    (String, String, WorkingCalendarSpec, (PersistedWorkingCalendar?) -> Unit) -> Unit,
  onMakeDefault: (String, (Boolean) -> Unit) -> Unit,
  onArchive: (String, String, (Boolean) -> Unit) -> Unit,
) {
  val calendars = calendarsState.calendars
  val activeDefaults = calendars.filter { it.schedule.isDefault }
  val activeDefault =
    activeDefaults.singleOrNull()
      ?: calendars.firstOrNull { it.schedule.id == defaultCalendar?.schedule?.id }
  val defaultInvariantProblem =
    when {
      !calendarsState.loaded || calendars.isEmpty() || activeDefaults.size == 1 -> null
      activeDefaults.isEmpty() ->
        "No active schedule is marked Default. Choose a schedule and make it the default."
      else ->
        "More than one active schedule is marked Default. Choose the one that should remain default."
    }
  val stateHolder = rememberSaveableStateHolder()
  var selectedScheduleId by rememberSaveable { mutableStateOf<String?>(null) }
  var addDialogOpen by rememberSaveable { mutableStateOf(false) }
  var addName by rememberSaveable { mutableStateOf("") }
  var pendingDefaultId by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingArchiveId by rememberSaveable { mutableStateOf<String?>(null) }
  var archiveReplacementId by rememberSaveable { mutableStateOf<String?>(null) }

  val activeIds = calendars.map { it.schedule.id }
  LaunchedEffect(calendarsState.loaded, activeIds, activeDefault?.schedule?.id) {
    val currentSelection = selectedScheduleId
    if (
      calendarsState.loaded &&
        (currentSelection == null || currentSelection !in activeIds)
    ) {
      selectedScheduleId =
        activeDefault?.schedule?.id?.takeIf { it in activeIds } ?: activeIds.firstOrNull()
    }
    pendingDefaultId?.let { id -> if (id !in activeIds) pendingDefaultId = null }
    pendingArchiveId?.let { id ->
      if (id !in activeIds) {
        pendingArchiveId = null
        archiveReplacementId = null
      }
    }
  }

  val selected = calendars.firstOrNull { it.schedule.id == selectedScheduleId }
  val addSource = selected ?: activeDefault?.takeIf { it.schedule.id in activeIds }
  val archiveTarget = calendars.firstOrNull { it.schedule.id == pendingArchiveId }
  val replacementChoices =
    archiveTarget?.let { target -> calendars.filter { it.schedule.id != target.schedule.id } }
      .orEmpty()

  Panel {
    Text(
      text = "Working schedules",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.semantics { contentDescription = "Working schedules" },
    )
    Text(
      text =
        "Choose the hours a task can use. Tasks without an explicit choice inherit the one " +
          "marked Default.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.sm))

    when {
      !calendarsState.loaded ->
        WorkingScheduleStatus(
          text = problem ?: "Loading working schedules…",
          isError = problem != null,
        )
      calendars.isEmpty() ->
        WorkingScheduleStatus(
          text =
            problem
              ?: "No active working schedule is available. Restore the default before planning.",
          isError = true,
        )
      else -> {
        defaultInvariantProblem?.let {
          WorkingScheduleStatus(text = it, isError = true)
          Spacer(Modifier.height(Space.sm))
        }
        Column(
          modifier = Modifier.fillMaxWidth().testTag("working_schedule_list"),
          verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
          calendars.forEach { calendar ->
            WorkingScheduleChoice(
              calendar = calendar,
              selected = calendar.schedule.id == selectedScheduleId,
              enabled = !isSaving,
              stateLabel =
                when {
                  calendar.schedule.isDefault && calendar.schedule.id == selectedScheduleId ->
                    "Default · Selected"
                  calendar.schedule.isDefault -> "Default"
                  calendar.schedule.id == selectedScheduleId -> "Selected"
                  else -> "Named schedule"
                },
              testTag = "work_schedule_choice_${calendar.schedule.id}",
              onClick = { selectedScheduleId = calendar.schedule.id },
            )
          }
        }
        Spacer(Modifier.height(Space.sm))

        OutlinedButton(
          onClick = {
            addName = ""
            addDialogOpen = true
          },
          enabled = !isSaving && addSource != null,
          modifier =
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_work_schedule"),
        ) {
          Text("Add schedule")
        }

        selected?.let { calendar ->
          Spacer(Modifier.height(Space.md))
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Spacer(Modifier.height(Space.md))
          val revisionKey = "${calendar.schedule.id}:${calendar.schedule.updatedAt}"
          stateHolder.SaveableStateProvider(revisionKey) {
            NamedWorkingScheduleEditor(
              calendar = calendar,
              calendars = calendars,
              isSaving = isSaving,
              problem = problem,
              onUpdate = onUpdate,
            )
          }

          Spacer(Modifier.height(Space.sm))
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
          ) {
            if (!calendar.schedule.isDefault) {
              TextButton(
                onClick = { pendingDefaultId = calendar.schedule.id },
                enabled = !isSaving,
                modifier =
                  Modifier.heightIn(min = 48.dp).testTag("make_work_schedule_default"),
              ) {
                Text("Make default")
              }
            }
            TextButton(
              onClick = {
                pendingArchiveId = calendar.schedule.id
                archiveReplacementId = null
              },
              enabled = !isSaving && calendars.any { it.schedule.id != calendar.schedule.id },
              modifier = Modifier.heightIn(min = 48.dp).testTag("archive_work_schedule"),
            ) {
              Text("Archive", color = MaterialTheme.colorScheme.error)
            }
          }
          if (calendars.size == 1) {
            Text(
              text = "Add another active schedule before archiving this default.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }

  if (addDialogOpen) {
    val nameProblem = workingScheduleNameProblem(addName, calendars)
    AlertDialog(
      onDismissRequest = { if (!isSaving) addDialogOpen = false },
      title = { Text("Add working schedule") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
          Text(
            text =
              "The new schedule starts with the selected schedule's hours. You can edit them " +
                "before using it for tasks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
            value = addName,
            onValueChange = { addName = it },
            label = { Text("Schedule name") },
            singleLine = true,
            isError = addName.isNotEmpty() && nameProblem != null,
            supportingText = {
              Text(nameProblem ?: "Use a distinct name of up to 80 characters.")
            },
            modifier = Modifier.fillMaxWidth().testTag("new_work_schedule_name"),
          )
          problem?.takeIf { it.isNotBlank() }?.let {
            WorkingScheduleStatus(text = it, isError = true)
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            addSource?.let { source ->
              onCreate(addName.trim(), source.spec) { created ->
                if (created != null) {
                  selectedScheduleId = created.schedule.id
                  addDialogOpen = false
                  addName = ""
                }
              }
            }
          },
          enabled = !isSaving && addSource != null && nameProblem == null,
          modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_add_work_schedule"),
        ) {
          Text(if (isSaving) "Creating…" else "Create")
        }
      },
      dismissButton = {
        TextButton(
          onClick = { addDialogOpen = false },
          enabled = !isSaving,
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("Cancel")
        }
      },
    )
  }

  calendars.firstOrNull { it.schedule.id == pendingDefaultId }?.let { target ->
    AlertDialog(
      onDismissRequest = { if (!isSaving) pendingDefaultId = null },
      title = { Text("Make ${target.schedule.name} default?") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
          Text(
            "Tasks set to inherit the default will immediately use ${target.schedule.name}. " +
              "Tasks explicitly assigned to another schedule stay there. This change has Undo."
          )
          problem?.takeIf { it.isNotBlank() }?.let {
            WorkingScheduleStatus(text = it, isError = true)
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            onMakeDefault(target.schedule.id) { success ->
              if (success) pendingDefaultId = null
            }
          },
          enabled = !isSaving,
          modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_make_work_schedule_default"),
        ) {
          Text(if (isSaving) "Changing…" else "Make default")
        }
      },
      dismissButton = {
        TextButton(
          onClick = { pendingDefaultId = null },
          enabled = !isSaving,
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("Cancel")
        }
      },
    )
  }

  archiveTarget?.let { target ->
    AlertDialog(
      onDismissRequest = {
        if (!isSaving) {
          pendingArchiveId = null
          archiveReplacementId = null
        }
      },
      title = { Text("Archive ${target.schedule.name}?") },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
          Text(
            "Choose an active replacement. Every task explicitly assigned to " +
              "${target.schedule.name} will move there. If this is the default, inherited tasks " +
              "will also use the replacement. History and schedule data remain available to Undo."
          )
          Text(
            text = "Replacement schedule",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
          )
          replacementChoices.forEach { replacement ->
            WorkingScheduleChoice(
              calendar = replacement,
              selected = replacement.schedule.id == archiveReplacementId,
              enabled = !isSaving,
              stateLabel =
                if (replacement.schedule.isDefault) "Default replacement" else "Replacement",
              testTag = "archive_replacement_${replacement.schedule.id}",
              onClick = { archiveReplacementId = replacement.schedule.id },
            )
          }
          problem?.takeIf { it.isNotBlank() }?.let {
            WorkingScheduleStatus(text = it, isError = true)
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            archiveReplacementId?.let { replacementId ->
              onArchive(target.schedule.id, replacementId) { success ->
                if (success) {
                  selectedScheduleId = replacementId
                  pendingArchiveId = null
                  archiveReplacementId = null
                }
              }
            }
          },
          enabled = !isSaving && archiveReplacementId != null,
          modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_archive_work_schedule"),
        ) {
          Text(if (isSaving) "Archiving…" else "Archive and reroute")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            pendingArchiveId = null
            archiveReplacementId = null
          },
          enabled = !isSaving,
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun NamedWorkingScheduleEditor(
  calendar: PersistedWorkingCalendar,
  calendars: List<PersistedWorkingCalendar>,
  isSaving: Boolean,
  problem: String?,
  onUpdate:
    (String, String, WorkingCalendarSpec, (PersistedWorkingCalendar?) -> Unit) -> Unit,
) {
  var nameDraft by rememberSaveable { mutableStateOf(calendar.schedule.name) }
  var editorState by rememberSaveable {
    mutableStateOf(WorkingScheduleEditPolicy.fromSpec(calendar.spec))
  }
  var zoneDialog by rememberSaveable { mutableStateOf(false) }
  var zoneDraft by rememberSaveable { mutableStateOf("") }
  val nameProblem =
    workingScheduleNameProblem(nameDraft, calendars, excludingId = calendar.schedule.id)

  Column(
    modifier = Modifier.fillMaxWidth().testTag("selected_work_schedule_editor"),
    verticalArrangement = Arrangement.spacedBy(Space.sm),
  ) {
    OutlinedTextField(
      value = nameDraft,
      onValueChange = { nameDraft = it },
      label = { Text("Schedule name") },
      singleLine = true,
      enabled = !isSaving,
      isError = nameProblem != null,
      supportingText = { Text(nameProblem ?: "Shown in task schedule choices.") },
      modifier = Modifier.fillMaxWidth().testTag("working_schedule_name"),
    )
    WorkingScheduleEditor(
      state = editorState,
      onStateChange = { editorState = it },
      onSave = { spec ->
        if (nameProblem == null) {
          onUpdate(calendar.schedule.id, nameDraft.trim(), spec) {}
        }
      },
      isSaving = isSaving,
      saveError = problem,
      onRequestTimeZoneChange = {
        zoneDraft = editorState.zoneId
        zoneDialog = true
      },
      isSaveAllowed = nameProblem == null,
      title = "Schedule availability",
      description =
        "These hours apply to assigned tasks. Inherited tasks use the schedule marked Default.",
      saveLabel = "Save schedule",
    )
  }

  if (zoneDialog) {
    AlertDialog(
      onDismissRequest = { zoneDialog = false },
      title = { Text("Working time zone") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
          Text(
            "Use an exact IANA ID. Working hours stay anchored to this zone when you travel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
            value = zoneDraft,
            onValueChange = { zoneDraft = it },
            label = { Text("Time zone ID") },
            supportingText = { Text("For example: Asia/Kolkata or Europe/London") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("work_zone_input"),
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            editorState = editorState.copy(zoneId = zoneDraft.trim())
            zoneDialog = false
          },
          enabled = zoneDraft.isNotBlank(),
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("Apply")
        }
      },
      dismissButton = {
        TextButton(
          onClick = { zoneDialog = false },
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun WorkingScheduleChoice(
  calendar: PersistedWorkingCalendar,
  selected: Boolean,
  enabled: Boolean,
  stateLabel: String,
  testTag: String,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
    shape = MaterialTheme.shapes.small,
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = 48.dp)
        .semantics {
          this.selected = selected
          stateDescription = stateLabel
        }
        .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .testTag(testTag),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = calendar.schedule.name,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
        )
        Text(
          text = stateLabel,
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
        )
      }
      if (selected) {
        Spacer(Modifier.width(Space.sm))
        Icon(Icons.Default.Check, contentDescription = null)
      }
    }
  }
}

@Composable
private fun WorkingScheduleStatus(text: String, isError: Boolean) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color =
      if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.semantics {
      if (isError) contentDescription = "Working schedule error: $text"
    },
  )
}

internal fun workingScheduleNameProblem(
  value: String,
  calendars: List<PersistedWorkingCalendar>,
  excludingId: String? = null,
): String? {
  val clean = value.trim()
  if (clean.isEmpty()) return "Enter a schedule name."
  if (clean.length > 80 || clean.any(Char::isISOControl)) {
    return "Use 80 characters or fewer without control characters."
  }
  val key = Normalizer.normalize(clean, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
  if (
    calendars.any { calendar ->
      calendar.schedule.id != excludingId && calendar.schedule.nameKey == key
    }
  ) {
    return "A working schedule with that name already exists."
  }
  return null
}

@Composable
private fun SettingsSubheading(title: String) {
  Text(
    text = title,
    fontSize = LocalDensityTokens.current.label,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = Space.xs),
  )
}

@Composable
private fun SettingsSubsectionDivider() {
  Spacer(Modifier.height(Space.md))
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  Spacer(Modifier.height(Space.md))
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
          modifier = Modifier.size(InlineIconSize),
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
            modifier = Modifier.size(InlineIconSize),
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
                    modifier = Modifier.size(InlineIconSize),
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
private fun DataSection(viewModel: BriefingViewModel, formatter: TimeFormatter, gutter: Dp) {
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

    Spacer(Modifier.height(Space.md))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(Space.md))

    BackupDisclosure(viewModel, gutter)
  }
}

/**
 * Encrypted backup and restore. Restore is a replacement, not a merge, so it asks before doing
 * anything and says afterwards what the journal lost with it.
 */
@Composable
private fun BackupDisclosure(viewModel: BriefingViewModel, gutter: Dp) {
  val context = LocalContext.current
  var pendingRestore by remember { mutableStateOf<ByteArray?>(null) }
  var pendingRestorePreview by remember { mutableStateOf<BackupPreview?>(null) }
  var expanded by rememberSaveable { mutableStateOf(false) }
  val exportLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
      if (uri != null) {
        viewModel.exportBackup { bytes ->
          context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }
      }
    }
  val openLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
    ) { uri ->
      if (uri != null) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null) {
          viewModel.previewBackup(bytes) { preview ->
            if (preview != null) {
              pendingRestore = bytes
              pendingRestorePreview = preview
            }
          }
        }
      }
    }

  SettingsDisclosure(
    title = "Backup",
    summary = "Encrypted export and restore of the plan workspace",
    expanded = expanded,
    onToggle = { expanded = !expanded },
    gutter = gutter,
  ) {
    Column {
      Text(
        text =
          "One encrypted file with your schedules, boards, tasks, blocks and saved views. " +
            "Restoring replaces what is here now.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(Space.sm))
      Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        OutlinedButton(
          onClick = { exportLauncher.launch("daily-brief-backup.dbb") },
          modifier = Modifier.weight(1f),
        ) { Text("Export") }
        OutlinedButton(
          onClick = { openLauncher.launch(arrayOf("application/octet-stream")) },
          modifier = Modifier.weight(1f),
        ) { Text("Restore") }
      }
    }
  }

  pendingRestore?.let { bytes ->
    val preview = pendingRestorePreview
    AlertDialog(
      onDismissRequest = {
        pendingRestore = null
        pendingRestorePreview = null
      },
      title = { Text("Review this restore") },
      text = {
        Column(
          verticalArrangement = Arrangement.spacedBy(Space.sm),
          modifier = Modifier.testTag("backup_restore_preview"),
        ) {
          Text(
            "This encrypted file will replace the current workspace. Nothing has been changed yet.",
          )
          if (preview != null) {
            Text("${preview.totalRows} rows will be restored.", fontWeight = FontWeight.SemiBold)
            BackupPreviewRow("Work schedules", preview.count("work_schedules"))
            BackupPreviewRow("Schedule windows", preview.count("work_schedule_windows"))
            BackupPreviewRow("Boards", preview.count("plan_boards"))
            BackupPreviewRow("Tasks", preview.count("plan_items"))
            BackupPreviewRow("Schedule blocks", preview.count("plan_blocks"))
            BackupPreviewRow("Dependencies", preview.count("plan_dependencies"))
            BackupPreviewRow("Saved views", preview.count("saved_views"))
            BackupPreviewRow("Baselines", preview.count("plan_baselines"))
            Text(
              "Device events are not in backups and return on the next sync. Undo history will be cleared.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            pendingRestore = null
            pendingRestorePreview = null
            viewModel.restoreBackup(bytes) {}
          },
          modifier = Modifier.testTag("backup_restore_confirm"),
        ) { Text("Restore") }
      },
      dismissButton = {
        TextButton(
          onClick = {
            pendingRestore = null
            pendingRestorePreview = null
          },
          modifier = Modifier.testTag("backup_restore_cancel"),
        ) { Text("Cancel") }
      },
      modifier = Modifier.testTag("backup_restore_preview_dialog"),
    )
  }
}

@Composable
private fun BackupPreviewRow(label: String, count: Int) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label)
    Text(count.toString(), fontWeight = FontWeight.SemiBold)
  }
}

/**
 * Layout controls: how tightly the workspace packs, which Agenda sections appear
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
      listOf("Home", "Calendar", "Plan").forEach { option ->
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
      text = "What the landing page shows, and in what order. Keep at least one block.",
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
              contentDescription = "Move ${card.label} up",
              onClick = { viewModel.moveHomeCard(card, -1) },
              enabled = shown && index > 0,
            )
            RowIconButton(
              icon = Icons.Default.KeyboardArrowDown,
              contentDescription = "Move ${card.label} down",
              onClick = { viewModel.moveHomeCard(card, 1) },
              enabled = shown && index >= 0 && index < homeCards.lastIndex,
            )
            Spacer(Modifier.width(Space.sm))
            Switch(
              checked = shown,
              onCheckedChange = { viewModel.setHomeCardVisible(card, it) },
              enabled = !shown || homeCards.size > 1,
              modifier =
                Modifier.semantics { contentDescription = "Show ${card.label} on Home" }
                  .testTag("home_card_" + card.key),
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
      text = "Row height, spacing and supporting text across the workspace.",
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

    Text("Agenda sections", fontSize = LocalDensityTokens.current.title, fontWeight = FontWeight.Medium)
    Text(
      text = "Turn sections off, or move them into the order you read in. Keep at least one.",
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
          enabled = !visible || sections.size > 1,
          modifier =
            Modifier.semantics { contentDescription = "Show ${section.label} in Agenda" }
              .testTag("section_" + section.key),
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
