package com.example.ui.components

import com.example.ui.viewmodel.EditorSaveGuard
import com.example.ui.viewmodel.EditorField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority
import com.example.data.model.WorkSchedule
import com.example.ui.theme.Space
import com.example.ui.viewmodel.PlanItemDraft

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorSheet(
  draft: PlanItemDraft,
  original: PlanItemDraft,
  hasOtherUnsavedChanges: Boolean = false,
  isBusy: Boolean,
  boards: List<PlanBoard>,
  columns: List<PlanColumn>,
  items: List<PlanItem>,
  workSchedules: List<WorkSchedule>,
  workScheduleDataLoaded: Boolean,
  assignedWorkScheduleId: String?,
  workScheduleAssignmentBusy: Boolean,
  formatter: TimeFormatter,
  onDraftChange: (PlanItemDraft) -> Unit,
  onSwitchToEvent: (() -> Unit)?,
  onDismiss: () -> Unit,
  onSave: (PlanItemDraft) -> Unit,
  onAssignWorkSchedule: (String?) -> Unit,
  onDelete: (() -> Unit)? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val focusRequester = remember { FocusRequester() }
  var showDuePicker by rememberSaveable { mutableStateOf(false) }
  var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
  var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
  val activeBoard = boards.firstOrNull { it.id == draft.boardId }
  val validColumns = columns.filter { it.boardId == draft.boardId }
  val validParents = remember(draft.id, draft.boardId, draft.columnId, items) {
    parentCandidates(draft, items)
  }
  val selectedParent = items.firstOrNull { it.id == draft.parentId && it.boardId == draft.boardId }
  val hasValidDestination = activeBoard != null && (draft.columnId == null || validColumns.any { it.id == draft.columnId })
  val hasValidParent = draft.parentId == null || selectedParent != null
  val blocker =
    EditorSaveGuard.forTask(
      title = draft.title,
      hasValidDestination = hasValidDestination,
      hasValidParent = hasValidParent,
      effortMinutes = draft.effortMinutes,
      isMilestone = draft.isMilestone,
    )
  val canChangeDestination = taskDestinationChangeAllowed(draft, original, items)
  val requestDismiss: () -> Unit = {
    if (!isBusy) {
      if (draft == original && !hasOtherUnsavedChanges) {
        onDismiss()
      } else {
        showDiscardConfirmation = true
      }
    }
  }
  LaunchedEffect(draft.id) {
    if (draft.id == null) focusRequester.requestFocus()
  }

  ModalBottomSheet(onDismissRequest = requestDismiss, sheetState = sheetState) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .imePadding()
          .navigationBarsPadding()
          .padding(horizontal = Space.xl)
          .padding(bottom = Space.xl)
          .testTag("task_editor"),
      verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
      if (onSwitchToEvent != null) {
        ViewSwitcher(
          options = listOf("Task", "Event"),
          selectedIndex = 0,
          onSelect = { if (it == 1) onSwitchToEvent() },
          modifier = Modifier.testTag("capture_kind"),
        )
      }

      Text(
        text = if (draft.id == null) "New task" else "Edit task",
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        text = "Flexible work stays separate from source-owned calendar commitments.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      OutlinedTextField(
        value = draft.title,
        onValueChange = { onDraftChange(draft.copy(title = it)) },
        enabled = !isBusy,
        label = { Text("Title") },
        singleLine = true,
        isError = blocker?.field == EditorField.TITLE,
        supportingText =
          blocker?.takeIf { it.field == EditorField.TITLE }?.let { { Text(it.message) } },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("task_title"),
      )

      OutlinedTextField(
        value = draft.notes,
        onValueChange = { onDraftChange(draft.copy(notes = it)) },
        enabled = !isBusy,
        label = { Text("Notes") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
      )

      TaskSectionLabel("Destination")
      Text(
        text = activeBoard?.name ?: "Preparing Plan…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TaskDestinationField(
        columnId = draft.columnId,
        columns = validColumns,
        onSelect = { onDraftChange(draft.copy(columnId = it)) },
        enabled = !isBusy && canChangeDestination,
      )
      if (!canChangeDestination) {
        Text(
          text = "This task belongs to a hierarchy. Use Board's group move controls to move the complete task group.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("task_destination_group_hint"),
        )
      }
      TaskParentField(
        parent = selectedParent,
        candidates = validParents,
        parentUnavailable = draft.parentId != null && selectedParent == null,
        onSelect = { parent ->
          onDraftChange(
            draft.copy(
              parentId = parent?.id,
              columnId = if (draft.id == null && parent != null) parent.columnId else draft.columnId,
            )
          )
        },
        enabled = !isBusy,
      )
      selectedParent?.let { parent ->
        Text(
          "Subtask of ${parent.title}. Workflow moves apply to the complete task group.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      TaskSectionLabel("Constraints")
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = draft.effortMinutes?.toString().orEmpty(),
          onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(5)
            onDraftChange(draft.copy(effortMinutes = digits.toIntOrNull()))
          },
          enabled = !isBusy && !draft.isMilestone,
          label = { Text("Effort (minutes)") },
          singleLine = true,
          isError = blocker?.field == EditorField.EFFORT || blocker?.field == EditorField.MILESTONE,
          supportingText =
            blocker
              ?.takeIf { it.field == EditorField.EFFORT || it.field == EditorField.MILESTONE }
              ?.let { { Text(it.message) } },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f).testTag("task_effort"),
        )
        OutlinedButton(
          onClick = { showDuePicker = true },
          enabled = !isBusy,
          modifier = Modifier.weight(1f).testTag("task_due"),
        ) {
          Text(
            text = draft.dueAt?.let { "Due ${formatter.relativeDay(it)}" } ?: "Add due date",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (draft.dueAt != null) {
        TextButton(
          onClick = { onDraftChange(draft.copy(dueAt = null)) },
          enabled = !isBusy,
          modifier = Modifier.align(Alignment.End),
        ) {
          Text("Clear due date")
        }
      }

      if (draft.id != null) {
        TaskSectionLabel("Working time")
        TaskWorkScheduleField(
          schedules = workSchedules,
          dataLoaded = workScheduleDataLoaded,
          assignedScheduleId = assignedWorkScheduleId,
          enabled = workScheduleDataLoaded && !isBusy && !workScheduleAssignmentBusy,
          onSelect = onAssignWorkSchedule,
        )
        Text(
          text =
            when {
              !workScheduleDataLoaded -> "Loading this task’s working schedule…"
              workScheduleAssignmentBusy -> "Assigning working schedule…"
              else ->
                "This choice applies immediately with its own Undo. Save controls the other task fields."
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("task_work_schedule_hint"),
        )
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
      ) {
        listOf(
            PlanPriority.LOW to "Low",
            PlanPriority.NORMAL to "Normal",
            PlanPriority.HIGH to "High",
            PlanPriority.URGENT to "Urgent",
          )
          .forEach { (value, label) ->
            FilterChip(
              selected = draft.priority == value,
              onClick = { onDraftChange(draft.copy(priority = value)) },
              enabled = !isBusy,
              label = { Text(label) },
            )
          }
        FilterChip(
          selected = draft.isMilestone,
          onClick = {
            onDraftChange(
              draft.copy(
                isMilestone = !draft.isMilestone,
                effortMinutes = if (!draft.isMilestone) null else draft.effortMinutes,
              )
            )
          },
          enabled = !isBusy,
          label = { Text("Milestone") },
          modifier = Modifier.testTag("task_milestone"),
        )
      }

      if (draft.id != null) {
        TaskSectionLabel("Progress")
        Row(verticalAlignment = Alignment.CenterVertically) {
          Slider(
            value = draft.progress.toFloat(),
            onValueChange = { value ->
              val stepped = ((value / 5).toInt() * 5).coerceIn(0, 100)
              onDraftChange(draft.copy(progress = stepped))
            },
            enabled = !isBusy,
            valueRange = 0f..100f,
            steps = 19,
            modifier = Modifier.weight(1f).testTag("task_progress"),
          )
          Spacer(Modifier.width(Space.sm))
          Text("${draft.progress}%", style = MaterialTheme.typography.labelLarge)
        }
      }

      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
      ) {
        if (onDelete != null) {
          TextButton(
            onClick = { showDeleteConfirmation = true },
            enabled = !isBusy,
            modifier = Modifier.testTag("task_delete"),
          ) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
          }
        }
        TextButton(onClick = requestDismiss, enabled = !isBusy) { Text("Cancel") }
        Button(
          onClick = { onSave(draft) },
          enabled = !isBusy && blocker == null,
          modifier = Modifier.testTag("task_save"),
        ) {
          Text(if (isBusy) "Saving…" else if (draft.id == null) "Add task" else "Save")
        }
      }
      SaveBlockerNotice(blocker, Modifier.testTag("task_save_blocker"))
    }
  }

  if (showDuePicker) {
    val initial = draft.dueAt ?: System.currentTimeMillis()
    val pickerState =
      rememberDatePickerState(initialSelectedDateMillis = ScheduleAnalysis.utcMillisFromLocalDay(initial))
    DatePickerDialog(
      onDismissRequest = { showDuePicker = false },
      confirmButton = {
        TextButton(
          enabled = !isBusy,
          onClick = {
            pickerState.selectedDateMillis?.let { selected ->
              onDraftChange(draft.copy(dueAt = ScheduleAnalysis.localDayFromUtcMillis(selected)))
            }
            showDuePicker = false
          },
        ) {
          Text("Set")
        }
      },
      dismissButton = { TextButton(onClick = { showDuePicker = false }) { Text("Cancel") } },
    ) {
      DatePicker(state = pickerState)
    }
  }

  if (showDiscardConfirmation) {
    AlertDialog(
      onDismissRequest = { showDiscardConfirmation = false },
      title = { Text("Discard unsaved changes?") },
      text = { Text("Your task edits have not been saved.") },
      confirmButton = {
        TextButton(
          onClick = {
            showDiscardConfirmation = false
            onDismiss()
          },
          enabled = !isBusy,
        ) {
          Text("Discard", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDiscardConfirmation = false }) { Text("Keep editing") }
      },
    )
  }

  if (showDeleteConfirmation && onDelete != null) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirmation = false },
      title = { Text("Delete ${draft.title.ifBlank { "this task" }}?") },
      text = { Text("Its plan blocks and dependencies will also be removed.") },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteConfirmation = false
            onDelete()
          },
          enabled = !isBusy,
          modifier = Modifier.testTag("task_delete_confirm"),
        ) {
          Text(if (isBusy) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirmation = false }, enabled = !isBusy) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun TaskWorkScheduleField(
  schedules: List<WorkSchedule>,
  dataLoaded: Boolean,
  assignedScheduleId: String?,
  enabled: Boolean,
  onSelect: (String?) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val label = taskWorkScheduleLabel(schedules, assignedScheduleId, dataLoaded)
  val options = taskWorkScheduleOptions(schedules, assignedScheduleId)
  Box(modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(
      onClick = { expanded = true },
      enabled = enabled,
      modifier =
        Modifier.fillMaxWidth()
          .semantics {
            contentDescription = "Working schedule, $label"
          }
          .testTag("task_work_schedule"),
    ) {
      Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      modifier = Modifier.testTag("task_work_schedule_menu"),
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = {
            Column {
              Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
              option.supportingText?.let { supporting ->
                Text(
                  supporting,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          },
          onClick = {
            expanded = false
            if (!option.selected) onSelect(option.scheduleId)
          },
          enabled = enabled && option.enabled,
          modifier =
            Modifier.semantics {
                selected = option.selected
                stateDescription = if (option.selected) "Selected" else "Not selected"
              }
              .testTag(option.testTag),
        )
      }
    }
  }
}

internal data class TaskWorkScheduleOption(
  val scheduleId: String?,
  val label: String,
  val supportingText: String? = null,
  val selected: Boolean,
  val enabled: Boolean,
  val testTag: String,
)

/** Pure option policy keeps loading, inheritance and explicit pinning honest in tests and UI. */
internal fun taskWorkScheduleOptions(
  schedules: List<WorkSchedule>,
  assignedScheduleId: String?,
): List<TaskWorkScheduleOption> {
  val default = schedules.singleOrNull { it.isDefault }
  val onlyDefault = schedules.size == 1 && default != null
  val inherited = assignedScheduleId == null
  val options =
    mutableListOf(
      TaskWorkScheduleOption(
        scheduleId = null,
        label = if (inherited) "Inherit default · Selected" else "Inherit default",
        supportingText = default?.name ?: "No valid default schedule",
        selected = inherited,
        enabled = default != null,
        testTag = "task_work_schedule_inherit",
      )
    )
  schedules
    .filterNot { schedule -> onlyDefault && assignedScheduleId == null && schedule.isDefault }
    .forEach { schedule ->
      val isSelected = assignedScheduleId == schedule.id
      val explicitLabel = if (schedule.isDefault) "${schedule.name} (explicit)" else schedule.name
      options +=
        TaskWorkScheduleOption(
          scheduleId = schedule.id,
          label = if (isSelected) "$explicitLabel · Selected" else explicitLabel,
          selected = isSelected,
          enabled = true,
          testTag = "task_work_schedule_${schedule.id}",
        )
    }
  return options
}

internal fun taskWorkScheduleLabel(
  schedules: List<WorkSchedule>,
  assignedScheduleId: String?,
  dataLoaded: Boolean,
): String {
  if (!dataLoaded) return "Loading working schedule…"
  val assigned = schedules.firstOrNull { it.id == assignedScheduleId }
  val default = schedules.singleOrNull { it.isDefault }
  return when {
    assignedScheduleId == null && default == null -> "Default schedule unavailable"
    assignedScheduleId == null -> "Inherit default"
    assigned == null -> "Assigned schedule unavailable"
    assigned.isDefault -> "${assigned.name} (explicit)"
    else -> assigned.name
  }
}

/** Hierarchy moves are group operations; the single-item editor must not split the group. */
internal fun taskDestinationChangeAllowed(
  draft: PlanItemDraft,
  original: PlanItemDraft,
  items: List<PlanItem>,
): Boolean {
  val itemId = draft.id ?: return true
  if (original.parentId != null || draft.parentId != null) return false
  return items.none { item -> item.archivedAt == null && item.parentId == itemId }
}

private fun parentCandidates(draft: PlanItemDraft, items: List<PlanItem>): List<PlanItem> {
  val active =
    items.filter {
      it.boardId == draft.boardId &&
        it.archivedAt == null &&
        it.id != draft.id &&
        it.columnId == draft.columnId
    }
  val byId = items.associateBy { it.id }
  fun isDescendant(candidate: PlanItem): Boolean {
    val itemId = draft.id ?: return false
    val visited = mutableSetOf<String>()
    var parentId = candidate.parentId
    while (parentId != null && visited.add(parentId)) {
      if (parentId == itemId) return true
      parentId = byId[parentId]?.parentId
    }
    return false
  }
  return active
    .filterNot(::isDescendant)
    .sortedWith(compareBy<PlanItem> { it.rank }.thenBy { it.createdAt }.thenBy { it.id })
}

@Composable
private fun TaskParentField(
  parent: PlanItem?,
  candidates: List<PlanItem>,
  parentUnavailable: Boolean,
  onSelect: (PlanItem?) -> Unit,
  enabled: Boolean,
) {
  var expanded by remember { mutableStateOf(false) }
  val label =
    when {
      parentUnavailable -> "Unavailable parent — choose one"
      parent != null -> parent.title
      else -> "No parent"
    }
  Box(modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(
      onClick = { expanded = true },
      enabled = enabled,
      modifier =
        Modifier.fillMaxWidth()
          .semantics { contentDescription = "Parent task: $label" }
          .testTag("task_parent"),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Parent task",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text("No parent") },
        onClick = {
          expanded = false
          onSelect(null)
        },
      )
      candidates.forEach { candidate ->
        DropdownMenuItem(
          text = { Text(candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          onClick = {
            expanded = false
            onSelect(candidate)
          },
        )
      }
    }
  }
}

@Composable
private fun TaskDestinationField(
  columnId: String?,
  columns: List<PlanColumn>,
  onSelect: (String?) -> Unit,
  enabled: Boolean,
) {
  var expanded by remember { mutableStateOf(false) }
  val label =
    when {
      columnId == null -> "Inbox"
      else -> columns.firstOrNull { it.id == columnId }?.name ?: "Unavailable destination — choose one"
    }
  Box(modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(
      onClick = { expanded = true },
      enabled = enabled,
      modifier =
        Modifier.fillMaxWidth()
          .semantics { contentDescription = "Destination: $label" }
          .testTag("task_destination"),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Workflow",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text("Inbox") },
        onClick = {
          expanded = false
          onSelect(null)
        },
      )
      columns.forEach { column ->
        DropdownMenuItem(
          text = { Text(column.name) },
          onClick = {
            expanded = false
            onSelect(column.id)
          },
        )
      }
    }
  }
}

@Composable
private fun TaskSectionLabel(text: String) {
  Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelSmall,
    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
    letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
