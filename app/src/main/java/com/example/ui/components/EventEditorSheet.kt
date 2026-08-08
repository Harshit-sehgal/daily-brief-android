package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorSheet(
  initial: EventDraft,
  boards: List<String>,
  columns: List<String>,
  formatter: TimeFormatter,
  onDismiss: () -> Unit,
  onSave: (EventDraft) -> Unit,
  onDelete: (() -> Unit)? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var draft by remember(initial) { mutableStateOf(initial) }
  var showDatePicker by remember { mutableStateOf(false) }
  var showStartPicker by remember { mutableStateOf(false) }
  var showEndPicker by remember { mutableStateOf(false) }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
        onValueChange = { draft = draft.copy(title = it) },
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("editor_title"),
      )

      OutlinedTextField(
        value = draft.description,
        onValueChange = { draft = draft.copy(description = it) },
        label = { Text("Notes") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
      )

      SectionLabel("When")
      OutlinedButton(
        onClick = { showDatePicker = true },
        modifier = Modifier.fillMaxWidth().testTag("editor_date"),
      ) {
        Text(formatter.fullDay(draft.startMs))
      }

      Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        OutlinedButton(onClick = { showStartPicker = true }, modifier = Modifier.weight(1f)) {
          Text("Starts ${formatter.time(draft.startMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = { showEndPicker = true }, modifier = Modifier.weight(1f)) {
          Text("Ends ${formatter.time(draft.endMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        FilterChip(
          selected = draft.isUrgent,
          onClick = { draft = draft.copy(isUrgent = !draft.isUrgent) },
          label = { Text("Urgent") },
        )
        FilterChip(
          selected = draft.isDeadline,
          onClick = { draft = draft.copy(isDeadline = !draft.isDeadline) },
          label = { Text("Deadline") },
        )
      }

      SectionLabel("Board")
      Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        DropdownField(
          value = draft.board,
          options = boards,
          onSelect = { draft = draft.copy(board = it) },
          modifier = Modifier.weight(1f),
        )
        DropdownField(
          value = draft.column,
          options = columns,
          onSelect = { draft = draft.copy(column = it) },
          modifier = Modifier.weight(1f),
        )
      }

      Spacer(Modifier.height(Space.xs))
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (onDelete != null) {
          TextButton(onClick = onDelete, modifier = Modifier.testTag("editor_delete")) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
          }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Spacer(Modifier.width(Space.sm))
        Button(
          onClick = { onSave(draft) },
          enabled = draft.title.isNotBlank(),
          modifier = Modifier.testTag("editor_save"),
        ) {
          Text("Save")
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
          onClick = {
            pickerState.selectedDateMillis?.let { draft = draft.movedToDay(it) }
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
        draft = draft.withStart(hour, minute)
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
        draft = draft.withEnd(hour, minute)
        showEndPicker = false
      },
    )
  }
}

/** Moves the whole event to another day, preserving its clock times and length. */
private fun EventDraft.movedToDay(utcDayMs: Long): EventDraft {
  val newDay = ScheduleAnalysis.localDayFromUtcMillis(utcDayMs)
  val length = (endMs - startMs).coerceAtLeast(0L)
  val start =
    ScheduleAnalysis.withTimeOfDay(
      newDay,
      ScheduleAnalysis.hourOf(startMs),
      ScheduleAnalysis.minuteOf(startMs),
    )
  return copy(startMs = start, endMs = start + length)
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
  if (end <= startMs) end += ScheduleAnalysis.DAY_MS
  return copy(endMs = end)
}

@Composable
private fun DropdownField(
  value: String,
  options: List<String>,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier = modifier) {
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
      Text(text = value, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
