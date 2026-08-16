package com.example.ui.components

import com.example.ui.viewmodel.EditorSaveGuard
import com.example.ui.viewmodel.EditorField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.ui.theme.Space
import com.example.ui.viewmodel.EventDraft
import com.example.ui.viewmodel.EventDraftEdits
import com.example.ui.viewmodel.EventOwnership

/**
 * The one place an event is created or changed, wherever the user starts from.
 * Previously three near-identical dialogs each supported a different subset of
 * the fields; this sheet is the whole model, once.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditorSheet(
  draft: EventDraft,
  original: EventDraft,
  hasOtherUnsavedChanges: Boolean = false,
  isBusy: Boolean,
  boards: List<String>,
  columnsByBoard: Map<String, List<String>>,
  formatter: TimeFormatter,
  onDraftChange: (EventDraft) -> Unit,
  onDismiss: () -> Unit,
  onSave: (EventDraft) -> Unit,
  onDelete: (() -> Unit)? = null,
  /** Present only during creation; editing an existing event cannot change ownership type. */
  onSwitchToTask: (() -> Unit)? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val focusRequester = remember { FocusRequester() }
  var showDatePicker by rememberSaveable { mutableStateOf(false) }
  var showStartPicker by rememberSaveable { mutableStateOf(false) }
  var showEndPicker by rememberSaveable { mutableStateOf(false) }
  var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
  var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
  // The times "All day" replaced, so switching it back off returns them rather
  // than dropping the user on a 9am default they never chose.
  var timedStartMs by rememberSaveable { mutableLongStateOf(if (draft.isAllDay) 0L else draft.startMs) }
  var timedEndMs by rememberSaveable { mutableLongStateOf(if (draft.isAllDay) 0L else draft.endMs) }
  LaunchedEffect(draft.isAllDay, draft.startMs, draft.endMs) {
    if (!draft.isAllDay) {
      timedStartMs = draft.startMs
      timedEndMs = draft.endMs
    }
  }
  val currentColumns =
    columnsByBoard[draft.board].orEmpty().ifEmpty { listOf(draft.column).filter { it.isNotBlank() } }
  val hasValidDestination = draft.board in boards && draft.column in currentColumns
  val blocker = EditorSaveGuard.forEvent(draft.title, hasValidDestination)
  val sourceFieldsEnabled = !isBusy && draft.ownership.sourceFieldsEditable
  val deleteAction = onDelete.takeIf { draft.ownership.deletableFromEditor }
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
          .testTag("event_editor"),
      verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
      if (onSwitchToTask != null) {
        ViewSwitcher(
          options = listOf("Task", "Event"),
          selectedIndex = 1,
          onSelect = { if (it == 0) onSwitchToTask() },
          modifier = Modifier.testTag("capture_kind"),
        )
      }

      Text(
        text = if (draft.id == null) "New event" else "Edit event",
        style = MaterialTheme.typography.titleLarge,
      )

      if (draft.id != null) {
        Column(
          modifier = Modifier.fillMaxWidth().testTag("event_ownership"),
          verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
          PropertyChip(
            text = eventOwnershipLabel(draft),
            tone = MaterialTheme.colorScheme.secondary,
          )
          Text(
            text = eventOwnershipExplanation(draft),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      OutlinedTextField(
        value = draft.title,
        onValueChange = { onDraftChange(draft.copy(title = it)) },
        enabled = sourceFieldsEnabled,
        label = { Text("Title") },
        singleLine = true,
        isError = blocker?.field == EditorField.TITLE,
        supportingText =
          blocker?.takeIf { it.field == EditorField.TITLE }?.let { { Text(it.message) } },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("editor_title"),
      )

      OutlinedTextField(
        value = draft.description,
        onValueChange = { onDraftChange(draft.copy(description = it)) },
        enabled = sourceFieldsEnabled,
        label = { Text("Notes") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().testTag("editor_notes"),
      )

      SectionLabel("When")
      OutlinedButton(
        onClick = { showDatePicker = true },
        enabled = sourceFieldsEnabled,
        colors = fieldButtonColors(),
        modifier = Modifier.fillMaxWidth().testTag("editor_date"),
      ) {
        Text(formatter.fullDay(draft.startMs))
      }

      AnimatedVisibility(visible = !draft.isAllDay) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        OutlinedButton(
          onClick = { showStartPicker = true },
          enabled = sourceFieldsEnabled,
          colors = fieldButtonColors(),
          modifier = Modifier.weight(1f).testTag("editor_start"),
        ) {
          Text("Starts ${formatter.time(draft.startMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(
          onClick = { showEndPicker = true },
          enabled = sourceFieldsEnabled,
          colors = fieldButtonColors(),
          modifier = Modifier.weight(1f).testTag("editor_end"),
        ) {
          Text(
            if (ScheduleAnalysis.isSameDay(draft.startMs, draft.endMs)) {
              "Ends ${formatter.time(draft.endMs)}"
            } else {
              "Ends ${formatter.mediumDay(draft.endMs)} · ${formatter.time(draft.endMs)}"
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        }
      }

      FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        FilterChip(
          selected = draft.isAllDay,
          onClick = {
            onDraftChange(
              if (draft.isAllDay) {
                EventDraftEdits.asTimed(draft, timedStartMs.takeIf { it > 0 }, timedEndMs.takeIf { it > 0 })
              } else {
                EventDraftEdits.asAllDay(draft)
              }
            )
          },
          enabled = sourceFieldsEnabled,
          label = { Text("All day") },
          modifier = Modifier.testTag("editor_all_day"),
        )
        FilterChip(
          selected = draft.isUrgent,
          onClick = { onDraftChange(draft.copy(isUrgent = !draft.isUrgent)) },
          enabled = !isBusy,
          label = { Text("Urgent") },
          modifier = Modifier.testTag("editor_urgent"),
        )
        FilterChip(
          selected = draft.isDeadline,
          onClick = { onDraftChange(draft.copy(isDeadline = !draft.isDeadline)) },
          enabled = !isBusy,
          label = { Text("Deadline") },
          modifier = Modifier.testTag("editor_deadline"),
        )
      }

      SectionLabel("Board")
      Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        DropdownField(
          label = "Board",
          value = draft.board,
          options = boards,
          onSelect = { board ->
            val columns = columnsByBoard[board].orEmpty().ifEmpty { listOf("To Do") }
            onDraftChange(
              draft.copy(
                board = board,
                column = draft.column.takeIf { it in columns } ?: columns.first(),
              )
            )
          },
          enabled = !isBusy,
          modifier = Modifier.fillMaxWidth(),
        )
        DropdownField(
          label = "Column",
          value = draft.column,
          options = currentColumns,
          onSelect = { onDraftChange(draft.copy(column = it)) },
          enabled = !isBusy,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      Spacer(Modifier.height(Space.xs))
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
      ) {
        if (deleteAction != null) {
          TextButton(
            onClick = { showDeleteConfirmation = true },
            enabled = !isBusy,
            modifier = Modifier.testTag("editor_delete"),
          ) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
          }
        }
        TextButton(onClick = requestDismiss, enabled = !isBusy) { Text("Cancel") }
        Button(
          onClick = { onSave(draft) },
          enabled = !isBusy && blocker == null,
          modifier = Modifier.testTag("editor_save"),
        ) {
          Text(if (isBusy) "Saving…" else "Save")
        }
      }
      SaveBlockerNotice(blocker, Modifier.testTag("editor_save_blocker"))
    }
  }

  if (showDatePicker) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = ScheduleAnalysis.utcMillisFromLocalDay(draft.startMs)
      )
    DatePickerDialog(
      onDismissRequest = { showDatePicker = false },
      confirmButton = {
        TextButton(
          enabled = !isBusy,
          onClick = {
            pickerState.selectedDateMillis?.let {
              onDraftChange(EventDraftEdits.movedToDay(draft, it))
            }
            showDatePicker = false
          }
        ) {
          Text("Set")
        }
      },
      dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
    ) {
      DatePicker(state = pickerState)
    }
  }

  if (showStartPicker) {
    AppTimeDialog(
      title = "Start time",
      initialMs = draft.startMs,
      is24Hour = formatter.is24Hour,
      onDismiss = { showStartPicker = false },
      onConfirm = { hour, minute ->
        onDraftChange(EventDraftEdits.withStart(draft, hour, minute))
        showStartPicker = false
      },
    )
  }

  if (showEndPicker) {
    AppTimeDialog(
      title = "End time",
      initialMs = draft.endMs,
      is24Hour = formatter.is24Hour,
      onDismiss = { showEndPicker = false },
      onConfirm = { hour, minute ->
        onDraftChange(EventDraftEdits.withEnd(draft, hour, minute))
        showEndPicker = false
      },
    )
  }

  if (showDiscardConfirmation) {
    AlertDialog(
      onDismissRequest = { showDiscardConfirmation = false },
      title = { Text("Discard unsaved changes?") },
      text = { Text("Your edits to this event have not been saved.") },
      confirmButton = {
        TextButton(
          enabled = !isBusy,
          onClick = {
            showDiscardConfirmation = false
            onDismiss()
          }
        ) {
          Text("Discard", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDiscardConfirmation = false }) { Text("Keep editing") }
      },
    )
  }

  if (showDeleteConfirmation && deleteAction != null) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirmation = false },
      title = { Text("Delete ${draft.title.ifBlank { "this event" }}?") },
      text = { Text(eventDeleteExplanation(draft)) },
      confirmButton = {
        TextButton(
          enabled = !isBusy,
          onClick = {
            showDeleteConfirmation = false
            deleteAction()
          },
          modifier = Modifier.testTag("editor_delete_confirm"),
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

private fun eventOwnershipLabel(draft: EventDraft): String {
  val source = draft.source.ifBlank { "External source" }
  return when (draft.ownership) {
    EventOwnership.APP_OWNED -> "Daily Brief · app-owned"
    EventOwnership.DEVICE_CALENDAR -> "$source · calendar-owned"
    EventOwnership.READ_ONLY_SOURCE -> "$source · read-only"
  }
}

private fun eventOwnershipExplanation(draft: EventDraft): String {
  val source = draft.source.ifBlank { "its source app" }
  return when (draft.ownership) {
    EventOwnership.APP_OWNED ->
      "This event belongs to Daily Brief. You can edit it here, and a deletion can be undone."
    EventOwnership.DEVICE_CALENDAR ->
      "This event belongs to $source. Edits are sent back when calendar access and write-back are available; a successful deletion cannot be undone here."
    EventOwnership.READ_ONLY_SOURCE ->
      "Title, notes, date, and time are controlled by $source. You can still change urgency, deadline, and Plan labels; delete or reschedule it in $source."
  }
}

private fun eventDeleteExplanation(draft: EventDraft): String =
  when (draft.ownership) {
    EventOwnership.APP_OWNED ->
      "This event will be removed from Daily Brief. You can undo it from the message shown after deletion."
    EventOwnership.DEVICE_CALENDAR -> {
      val source = draft.source.ifBlank { "the source calendar" }
      "Daily Brief will ask $source to delete this event. If the source accepts it, the deletion cannot be undone here."
    }
    EventOwnership.READ_ONLY_SOURCE ->
      "Delete this event in ${draft.source.ifBlank { "its source app" }}."
  }

/** Outlined buttons standing in for form fields read better in body colour. */
@Composable
private fun fieldButtonColors() =
  ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

@Composable
private fun DropdownField(
  label: String,
  value: String,
  options: List<String>,
  onSelect: (String) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier = modifier) {
    OutlinedButton(
      onClick = { expanded = true },
      enabled = enabled,
      colors = fieldButtonColors(),
      modifier =
        Modifier.fillMaxWidth().semantics { contentDescription = "$label: $value" },
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(option) },
          onClick = {
            onSelect(option)
            expanded = false
          },
        )
      }
    }
  }
}
