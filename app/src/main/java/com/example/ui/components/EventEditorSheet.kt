package com.example.ui.components

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.ui.theme.Space
import com.example.ui.viewmodel.EventDraft

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
  isBusy: Boolean,
  boards: List<String>,
  columnsByBoard: Map<String, List<String>>,
  formatter: TimeFormatter,
  onDraftChange: (EventDraft) -> Unit,
  onDismiss: () -> Unit,
  onSave: (EventDraft) -> Unit,
  onDelete: (() -> Unit)? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showDatePicker by rememberSaveable { mutableStateOf(false) }
  var showStartPicker by rememberSaveable { mutableStateOf(false) }
  var showEndPicker by rememberSaveable { mutableStateOf(false) }
  var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
  var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
  val currentColumns =
    columnsByBoard[draft.board].orEmpty().ifEmpty { listOf(draft.column).filter { it.isNotBlank() } }
  val hasValidDestination = draft.board in boards && draft.column in currentColumns
  val requestDismiss: () -> Unit = {
    if (!isBusy) {
      if (draft == original) onDismiss() else showDiscardConfirmation = true
    }
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
      Text(
        text = if (draft.id == null) "New event" else "Edit event",
        style = MaterialTheme.typography.titleLarge,
      )

      OutlinedTextField(
        value = draft.title,
        onValueChange = { onDraftChange(draft.copy(title = it)) },
        enabled = !isBusy,
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("editor_title"),
      )

      OutlinedTextField(
        value = draft.description,
        onValueChange = { onDraftChange(draft.copy(description = it)) },
        enabled = !isBusy,
        label = { Text("Notes") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
      )

      SectionLabel("When")
      OutlinedButton(
        onClick = { showDatePicker = true },
        enabled = !isBusy,
        colors = fieldButtonColors(),
        modifier = Modifier.fillMaxWidth().testTag("editor_date"),
      ) {
        Text(formatter.fullDay(draft.startMs))
      }

      AnimatedVisibility(visible = !draft.isAllDay) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        OutlinedButton(
          onClick = { showStartPicker = true },
          enabled = !isBusy,
          colors = fieldButtonColors(),
          modifier = Modifier.weight(1f),
        ) {
          Text("Starts ${formatter.time(draft.startMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(
          onClick = { showEndPicker = true },
          enabled = !isBusy,
          colors = fieldButtonColors(),
          modifier = Modifier.weight(1f),
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
          onClick = { onDraftChange(draft.withAllDay(!draft.isAllDay)) },
          enabled = !isBusy,
          label = { Text("All day") },
        )
        FilterChip(
          selected = draft.isUrgent,
          onClick = { onDraftChange(draft.copy(isUrgent = !draft.isUrgent)) },
          enabled = !isBusy,
          label = { Text("Urgent") },
        )
        FilterChip(
          selected = draft.isDeadline,
          onClick = { onDraftChange(draft.copy(isDeadline = !draft.isDeadline)) },
          enabled = !isBusy,
          label = { Text("Deadline") },
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
        if (onDelete != null) {
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
          enabled = !isBusy && draft.title.isNotBlank() && hasValidDestination,
          modifier = Modifier.testTag("editor_save"),
        ) {
          Text(if (isBusy) "Saving…" else "Save")
        }
      }
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
            pickerState.selectedDateMillis?.let { onDraftChange(draft.movedToDay(it)) }
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
        onDraftChange(draft.withStart(hour, minute))
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
        onDraftChange(draft.withEnd(hour, minute))
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

  if (showDeleteConfirmation && onDelete != null) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirmation = false },
      title = { Text("Delete ${draft.title.ifBlank { "this event" }}?") },
      text = { Text("You can undo this from the message shown after deletion.") },
      confirmButton = {
        TextButton(
          enabled = !isBusy,
          onClick = {
            showDeleteConfirmation = false
            onDelete()
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

/** Moves the whole event to another day, preserving its clock times and length. */
private fun EventDraft.movedToDay(utcDayMs: Long): EventDraft {
  val newDay = ScheduleAnalysis.localDayFromUtcMillis(utcDayMs)
  if (isAllDay) {
    return copy(
      startMs = newDay,
      endMs = ScheduleAnalysis.startOfDayOffset(newDay, 1),
    )
  }
  val length = (endMs - startMs).coerceAtLeast(0L)
  val start =
    ScheduleAnalysis.withTimeOfDay(
      newDay,
      ScheduleAnalysis.hourOf(startMs),
      ScheduleAnalysis.minuteOf(startMs),
    )
  return copy(startMs = start, endMs = start + length)
}

private fun EventDraft.withAllDay(allDay: Boolean): EventDraft {
  val day = ScheduleAnalysis.startOfDay(startMs)
  return if (allDay) {
    copy(
      startMs = day,
      endMs = ScheduleAnalysis.startOfDayOffset(day, 1),
      isAllDay = true,
    )
  } else {
    val start = ScheduleAnalysis.withTimeOfDay(day, 9, 0)
    copy(startMs = start, endMs = ScheduleAnalysis.withTimeOfDay(day, 10, 0), isAllDay = false)
  }
}

/** Keeps the event's length when the start moves, so end never lands before start. */
private fun EventDraft.withStart(hour: Int, minute: Int): EventDraft {
  val length = (endMs - startMs).coerceAtLeast(15 * 60_000L)
  val start = ScheduleAnalysis.withTimeOfDay(startMs, hour, minute)
  return copy(startMs = start, endMs = start + length)
}

private fun EventDraft.withEnd(hour: Int, minute: Int): EventDraft {
  var end = ScheduleAnalysis.withTimeOfDay(startMs, hour, minute)
  // An end earlier than the start reads as "runs past midnight".
  if (end <= startMs) {
    end =
      ScheduleAnalysis.withTimeOfDay(
        ScheduleAnalysis.startOfDayOffset(startMs, 1),
        hour,
        minute,
      )
  }
  return copy(endMs = end)
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
