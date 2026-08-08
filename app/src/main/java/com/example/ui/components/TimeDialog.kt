package com.example.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import com.example.core.ScheduleAnalysis

/**
 * Clock entry, shared by the event editor and the daily-brief time setting.
 * Uses the numeric input rather than the dial so it stays inside a dialog on
 * small screens and works with a keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTimeDialog(
  title: String,
  initialHour: Int,
  initialMinute: Int,
  is24Hour: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (Int, Int) -> Unit,
) {
  val state =
    rememberTimePickerState(
      initialHour = initialHour.coerceIn(0, 23),
      initialMinute = initialMinute.coerceIn(0, 59),
      is24Hour = is24Hour,
    )
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { TimeInput(state = state) },
    confirmButton = {
      TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("Set") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** Convenience overload for callers that hold a timestamp rather than fields. */
@Composable
fun AppTimeDialog(
  title: String,
  initialMs: Long,
  is24Hour: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (Int, Int) -> Unit,
) =
  AppTimeDialog(
    title = title,
    initialHour = ScheduleAnalysis.hourOf(initialMs),
    initialMinute = ScheduleAnalysis.minuteOf(initialMs),
    is24Hour = is24Hour,
    onDismiss = onDismiss,
    onConfirm = onConfirm,
  )
