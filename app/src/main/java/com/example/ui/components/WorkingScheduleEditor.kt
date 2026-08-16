package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.WorkingCalendar
import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingDateOverride
import com.example.core.WorkingDayWindow
import com.example.core.WorkingWeekWindow
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space
import java.io.Serializable
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class WorkingScheduleWindowEdit(
  val startText: String,
  val endText: String,
) : Serializable

data class WorkingScheduleDayEdit(
  val dayOfWeek: Int,
  val isOpen: Boolean,
  /** Retained while closed so reopening a day during the same edit does not discard its times. */
  val windows: List<WorkingScheduleWindowEdit>,
) : Serializable

data class WorkingScheduleDateOverrideEdit(
  val localDate: String,
  val isOpen: Boolean,
  /** Retained while closed so custom hours can be restored without re-entry. */
  val windows: List<WorkingScheduleWindowEdit>,
) : Serializable

/** Controlled editor state. The host can keep this in saveable state or a ViewModel. */
data class WorkingScheduleEditorState(
  val zoneId: String,
  val days: List<WorkingScheduleDayEdit>,
  val minimumChunkMinutes: String,
  val maximumChunkMinutes: String,
  val bufferMinutes: String,
  val dateOverrides: List<WorkingScheduleDateOverrideEdit> = emptyList(),
) : Serializable

data class WorkingScheduleValidation(
  val spec: WorkingCalendarSpec?,
  val fieldErrors: Map<String, String>,
) {
  val isValid: Boolean
    get() = spec != null && fieldErrors.isEmpty()

  val summary: String?
    get() =
      when {
        isValid -> null
        fieldErrors[SummaryField] != null -> fieldErrors[SummaryField]
        else -> "Fix the highlighted schedule fields before saving."
      }

  fun errorFor(field: String): String? = fieldErrors[field]
}

/** Pure edit and validation policy shared by Compose and focused JVM tests. */
object WorkingScheduleEditPolicy {
  fun fromSpec(spec: WorkingCalendarSpec): WorkingScheduleEditorState {
    WorkingCalendar.validate(spec)
    val windowsByDay = spec.weeklyWindows.groupBy { it.dayOfWeek }
    return WorkingScheduleEditorState(
      zoneId = spec.zoneId,
      days =
        DayOrder.map { dayOfWeek ->
          val windows =
            windowsByDay[dayOfWeek]
              .orEmpty()
              .sortedBy { it.startMinute }
              .map {
                WorkingScheduleWindowEdit(
                  startText = formatMinute(it.startMinute),
                  endText = formatMinute(it.endMinute),
                )
              }
          WorkingScheduleDayEdit(
            dayOfWeek = dayOfWeek,
            isOpen = windows.isNotEmpty(),
            windows = windows.ifEmpty { listOf(DefaultWindow) },
          )
        },
      minimumChunkMinutes = spec.minimumChunkMinutes.toString(),
      maximumChunkMinutes = spec.maximumChunkMinutes.toString(),
      bufferMinutes = spec.bufferMinutes.toString(),
      dateOverrides =
        spec.overrides.map { override ->
          val windows =
            override.windows
              .sortedBy { it.startMinute }
              .map {
                WorkingScheduleWindowEdit(
                  startText = formatMinute(it.startMinute),
                  endText = formatMinute(it.endMinute),
                )
              }
          WorkingScheduleDateOverrideEdit(
            localDate = override.localDate,
            isOpen = windows.isNotEmpty(),
            windows = windows.ifEmpty { listOf(DefaultWindow) },
          )
        },
    )
  }

  fun validate(state: WorkingScheduleEditorState): WorkingScheduleValidation {
    val errors = linkedMapOf<String, String>()
    if (state.zoneId !in AvailableZoneIds) {
      errors[ZoneField] = "Choose a valid IANA time zone."
    }
    val minimum =
      parseBoundedInt(
        value = state.minimumChunkMinutes,
        range = 1..MinutesPerDay,
        field = MinimumField,
        message = "Enter 1 to 1440 minutes.",
        errors = errors,
      )
    val maximum =
      parseBoundedInt(
        value = state.maximumChunkMinutes,
        range = 1..MinutesPerDay,
        field = MaximumField,
        message = "Enter 1 to 1440 minutes.",
        errors = errors,
      )
    val buffer =
      parseBoundedInt(
        value = state.bufferMinutes,
        range = 0 until MinutesPerDay,
        field = BufferField,
        message = "Enter 0 to 1439 minutes.",
        errors = errors,
      )
    if (minimum != null && maximum != null && maximum < minimum) {
      errors[MaximumField] = "Maximum chunk must be at least the minimum chunk."
    }

    val structurallyComplete =
      state.days.size == DayOrder.size &&
        state.days.map { it.dayOfWeek }.toSet() == DayOrder.toSet()
    if (!structurallyComplete) {
      errors[DaysField] = "The working week must contain Monday through Sunday exactly once."
    }

    val weekly = mutableListOf<WorkingWeekWindow>()
    DayOrder.forEach { dayOfWeek ->
      val day = state.days.singleOrNull { it.dayOfWeek == dayOfWeek } ?: return@forEach
      if (!day.isOpen) return@forEach
      if (day.windows.isEmpty()) {
        errors[dayField(dayOfWeek)] = "Add a time window or mark this day closed."
        return@forEach
      }
      val parsedWindows = mutableListOf<WorkingDayWindow>()
      day.windows.forEachIndexed { index, window ->
        val start = parseTime(window.startText, allowEndOfDay = false)
        val end = parseTime(window.endText, allowEndOfDay = true)
        if (start == null) {
          errors[startField(dayOfWeek, index)] = "Use HH:MM from 00:00 to 23:59."
        }
        if (end == null || end == 0) {
          errors[endField(dayOfWeek, index)] = "Use HH:MM from 00:01 to 24:00."
        }
        if (start != null && end != null && end > 0) {
          if (end <= start) {
            errors[endField(dayOfWeek, index)] = "End time must be after start time."
          } else {
            parsedWindows += WorkingDayWindow(start, end)
          }
        }
      }
      if (parsedWindows.size == day.windows.size) {
        parsedWindows.sortedBy { it.startMinute }.zipWithNext().forEach { (left, right) ->
          if (left.endMinute > right.startMinute) {
            errors[dayField(dayOfWeek)] = "Working windows cannot overlap."
          }
        }
        parsedWindows.forEach { window ->
          weekly += WorkingWeekWindow(dayOfWeek, window.startMinute, window.endMinute)
        }
      }
    }

    val overrides = mutableListOf<WorkingDateOverride>()
    state.dateOverrides.forEachIndexed { overrideIndex, override ->
      val dateIsValid = isExactLocalDate(override.localDate)
      if (!dateIsValid) {
        errors[overrideDateField(overrideIndex)] = "Use an exact date in YYYY-MM-DD format."
      }

      val parsedWindows = mutableListOf<WorkingDayWindow>()
      if (override.isOpen) {
        if (override.windows.isEmpty()) {
          errors[overrideField(overrideIndex)] =
            "Add a time window or mark this exception closed."
        }
        override.windows.forEachIndexed { windowIndex, window ->
          val start = parseTime(window.startText, allowEndOfDay = false)
          val end = parseTime(window.endText, allowEndOfDay = true)
          if (start == null) {
            errors[overrideStartField(overrideIndex, windowIndex)] =
              "Use HH:MM from 00:00 to 23:59."
          }
          if (end == null || end == 0) {
            errors[overrideEndField(overrideIndex, windowIndex)] =
              "Use HH:MM from 00:01 to 24:00."
          }
          if (start != null && end != null && end > 0) {
            if (end <= start) {
              errors[overrideEndField(overrideIndex, windowIndex)] =
                "End time must be after start time."
            } else {
              parsedWindows += WorkingDayWindow(start, end)
            }
          }
        }
        if (parsedWindows.size == override.windows.size) {
          parsedWindows.sortedBy { it.startMinute }.zipWithNext().forEach { (left, right) ->
            if (left.endMinute > right.startMinute) {
              errors[overrideField(overrideIndex)] = "Working windows cannot overlap."
            }
          }
        }
      }

      if (
        dateIsValid &&
          (!override.isOpen || parsedWindows.size == override.windows.size) &&
          errors[overrideField(overrideIndex)] == null
      ) {
        overrides +=
          WorkingDateOverride(
            localDate = override.localDate,
            windows =
              if (override.isOpen) parsedWindows.sortedBy { it.startMinute }
              else emptyList(),
          )
      }
    }
    state.dateOverrides
      .withIndex()
      .groupBy { it.value.localDate }
      .values
      .filter { it.size > 1 }
      .flatten()
      .forEach { indexed ->
        errors[overrideDateField(indexed.index)] = "Each date can have only one exception."
      }

    if (errors.isNotEmpty()) return WorkingScheduleValidation(null, errors)
    val spec =
      WorkingCalendarSpec(
        zoneId = state.zoneId,
        weeklyWindows = weekly,
        overrides = overrides,
        minimumChunkMinutes = requireNotNull(minimum),
        maximumChunkMinutes = requireNotNull(maximum),
        bufferMinutes = requireNotNull(buffer),
      )
    val validationError = runCatching { WorkingCalendar.validate(spec) }.exceptionOrNull()
    if (validationError != null) {
      errors[SummaryField] = validationError.message ?: "This working schedule is invalid."
      return WorkingScheduleValidation(null, errors)
    }
    return WorkingScheduleValidation(spec, emptyMap())
  }

  fun setDayOpen(
    state: WorkingScheduleEditorState,
    dayOfWeek: Int,
    isOpen: Boolean,
  ): WorkingScheduleEditorState =
    updateDay(state, dayOfWeek) { day ->
      day.copy(
        isOpen = isOpen,
        windows = if (isOpen && day.windows.isEmpty()) listOf(DefaultWindow) else day.windows,
      )
    }

  fun updateWindow(
    state: WorkingScheduleEditorState,
    dayOfWeek: Int,
    index: Int,
    startText: String? = null,
    endText: String? = null,
  ): WorkingScheduleEditorState =
    updateDay(state, dayOfWeek) { day ->
      require(index in day.windows.indices) { "Unknown working window" }
      day.copy(
        windows =
          day.windows.mapIndexed { current, window ->
            if (current != index) window
            else
              window.copy(
                startText = startText ?: window.startText,
                endText = endText ?: window.endText,
              )
          }
      )
    }

  fun addWindow(
    state: WorkingScheduleEditorState,
    dayOfWeek: Int,
  ): WorkingScheduleEditorState =
    updateDay(state, dayOfWeek) { day ->
      val latestEnd =
        day.windows.mapNotNull { parseTime(it.endText, allowEndOfDay = true) }.maxOrNull()
      val suggested =
        if (latestEnd != null && latestEnd <= MinutesPerDay - 60) {
          WorkingScheduleWindowEdit(formatMinute(latestEnd), formatMinute(latestEnd + 60))
        } else {
          DefaultWindow
        }
      day.copy(isOpen = true, windows = day.windows + suggested)
    }

  fun removeWindow(
    state: WorkingScheduleEditorState,
    dayOfWeek: Int,
    index: Int,
  ): WorkingScheduleEditorState =
    updateDay(state, dayOfWeek) { day ->
      require(index in day.windows.indices) { "Unknown working window" }
      day.copy(windows = day.windows.filterIndexed { current, _ -> current != index })
    }

  fun addDateOverride(state: WorkingScheduleEditorState): WorkingScheduleEditorState =
    state.copy(
      dateOverrides =
        state.dateOverrides +
          WorkingScheduleDateOverrideEdit(
            localDate = "",
            isOpen = false,
            windows = listOf(DefaultWindow),
          )
    )

  fun updateDateOverride(
    state: WorkingScheduleEditorState,
    index: Int,
    localDate: String,
  ): WorkingScheduleEditorState =
    updateDateOverride(state, index) { it.copy(localDate = localDate) }

  fun setDateOverrideOpen(
    state: WorkingScheduleEditorState,
    index: Int,
    isOpen: Boolean,
  ): WorkingScheduleEditorState =
    updateDateOverride(state, index) { override ->
      override.copy(
        isOpen = isOpen,
        windows =
          if (isOpen && override.windows.isEmpty()) listOf(DefaultWindow)
          else override.windows,
      )
    }

  fun updateDateOverrideWindow(
    state: WorkingScheduleEditorState,
    overrideIndex: Int,
    windowIndex: Int,
    startText: String? = null,
    endText: String? = null,
  ): WorkingScheduleEditorState =
    updateDateOverride(state, overrideIndex) { override ->
      require(windowIndex in override.windows.indices) { "Unknown exception window" }
      override.copy(
        windows =
          override.windows.mapIndexed { current, window ->
            if (current != windowIndex) window
            else
              window.copy(
                startText = startText ?: window.startText,
                endText = endText ?: window.endText,
              )
          }
      )
    }

  fun addDateOverrideWindow(
    state: WorkingScheduleEditorState,
    overrideIndex: Int,
  ): WorkingScheduleEditorState =
    updateDateOverride(state, overrideIndex) { override ->
      val latestEnd =
        override.windows.mapNotNull { parseTime(it.endText, allowEndOfDay = true) }.maxOrNull()
      val suggested =
        if (latestEnd != null && latestEnd <= MinutesPerDay - 60) {
          WorkingScheduleWindowEdit(formatMinute(latestEnd), formatMinute(latestEnd + 60))
        } else {
          DefaultWindow
        }
      override.copy(isOpen = true, windows = override.windows + suggested)
    }

  fun removeDateOverrideWindow(
    state: WorkingScheduleEditorState,
    overrideIndex: Int,
    windowIndex: Int,
  ): WorkingScheduleEditorState =
    updateDateOverride(state, overrideIndex) { override ->
      require(windowIndex in override.windows.indices) { "Unknown exception window" }
      override.copy(
        windows = override.windows.filterIndexed { current, _ -> current != windowIndex }
      )
    }

  fun removeDateOverride(
    state: WorkingScheduleEditorState,
    index: Int,
  ): WorkingScheduleEditorState {
    require(index in state.dateOverrides.indices) { "Unknown date exception" }
    return state.copy(
      dateOverrides = state.dateOverrides.filterIndexed { current, _ -> current != index }
    )
  }

  private fun updateDay(
    state: WorkingScheduleEditorState,
    dayOfWeek: Int,
    update: (WorkingScheduleDayEdit) -> WorkingScheduleDayEdit,
  ): WorkingScheduleEditorState {
    require(dayOfWeek in DayOrder) { "Unknown weekday" }
    var found = false
    val days =
      state.days.map { day ->
        if (day.dayOfWeek != dayOfWeek) day
        else {
          require(!found) { "Weekday appears more than once" }
          found = true
          update(day)
        }
      }
    require(found) { "Weekday is missing" }
    return state.copy(days = days)
  }

  private fun updateDateOverride(
    state: WorkingScheduleEditorState,
    index: Int,
    update: (WorkingScheduleDateOverrideEdit) -> WorkingScheduleDateOverrideEdit,
  ): WorkingScheduleEditorState {
    require(index in state.dateOverrides.indices) { "Unknown date exception" }
    return state.copy(
      dateOverrides =
        state.dateOverrides.mapIndexed { current, override ->
          if (current == index) update(override) else override
        }
    )
  }

  private fun parseBoundedInt(
    value: String,
    range: IntRange,
    field: String,
    message: String,
    errors: MutableMap<String, String>,
  ): Int? {
    val parsed = value.toIntOrNull()
    if (parsed == null || parsed !in range) errors[field] = message
    return parsed?.takeIf { it in range }
  }
}

/**
 * A controlled Settings surface for the persisted working-calendar contract.
 *
 * [onStateChange] owns every draft edit. [onSave] is called only with a fully validated spec.
 * A host-provided time-zone picker can update [WorkingScheduleEditorState.zoneId].
 */
@Composable
fun WorkingScheduleEditor(
  state: WorkingScheduleEditorState,
  onStateChange: (WorkingScheduleEditorState) -> Unit,
  onSave: (WorkingCalendarSpec) -> Unit,
  modifier: Modifier = Modifier,
  isSaving: Boolean = false,
  saveError: String? = null,
  onRequestTimeZoneChange: (() -> Unit)? = null,
  isSaveAllowed: Boolean = true,
  title: String = "Working week",
  description: String =
    "Plan uses these hours for flexible work. Calendar commitments still stay fixed.",
  saveLabel: String = "Save working week",
) {
  val validation = remember(state) { WorkingScheduleEditPolicy.validate(state) }
  var pendingDateOverrideRemoval by remember { mutableStateOf<Int?>(null) }
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme

  Column(
    modifier = modifier.fillMaxWidth().testTag("working_schedule_editor"),
    verticalArrangement = Arrangement.spacedBy(Space.lg),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        text = description,
        fontSize = d.secondary,
        color = colors.onSurfaceVariant,
      )
    }

    TimeZoneSection(
      zoneId = state.zoneId,
      error = validation.errorFor(ZoneField),
      enabled = !isSaving,
      onRequestChange = onRequestTimeZoneChange,
    )

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
      Text(
        text = "Planning limits",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
      )
      NumberField(
        label = "Minimum chunk",
        supportingText = "Shortest block Plan should normally create, in minutes.",
        value = state.minimumChunkMinutes,
        error = validation.errorFor(MinimumField),
        enabled = !isSaving,
        onValueChange = { onStateChange(state.copy(minimumChunkMinutes = it)) },
        testTag = "work_schedule_minimum",
      )
      NumberField(
        label = "Maximum chunk",
        supportingText = "Longest block Plan may create before splitting work.",
        value = state.maximumChunkMinutes,
        error = validation.errorFor(MaximumField),
        enabled = !isSaving,
        onValueChange = { onStateChange(state.copy(maximumChunkMinutes = it)) },
        testTag = "work_schedule_maximum",
      )
      NumberField(
        label = "Commitment buffer",
        supportingText = "Minutes protected before and after fixed commitments.",
        value = state.bufferMinutes,
        error = validation.errorFor(BufferField),
        enabled = !isSaving,
        onValueChange = { onStateChange(state.copy(bufferMinutes = it)) },
        testTag = "work_schedule_buffer",
      )
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
      Text(
        text = "Weekly availability",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        text = "Use 24-hour times. Split a day when you want to protect a break.",
        fontSize = d.secondary,
        color = colors.onSurfaceVariant,
      )
      validation.errorFor(DaysField)?.let { InlineError(it) }
      state.days.forEach { day ->
        DayEditor(
          day = day,
          validation = validation,
          enabled = !isSaving,
          onOpenChange = {
            onStateChange(WorkingScheduleEditPolicy.setDayOpen(state, day.dayOfWeek, it))
          },
          onWindowChange = { index, start, end ->
            onStateChange(
              WorkingScheduleEditPolicy.updateWindow(
                state = state,
                dayOfWeek = day.dayOfWeek,
                index = index,
                startText = start,
                endText = end,
              )
            )
          },
          onAddWindow = {
            onStateChange(WorkingScheduleEditPolicy.addWindow(state, day.dayOfWeek))
          },
          onRemoveWindow = { index ->
            onStateChange(
              WorkingScheduleEditPolicy.removeWindow(state, day.dayOfWeek, index)
            )
          },
        )
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
      Text(
        text = "Date exceptions",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
      )
      Text(
        text =
          "Close one local date or replace its weekly hours. " +
            "Dates use the ${state.zoneId} wall clock.",
        fontSize = d.secondary,
        color = colors.onSurfaceVariant,
      )
      state.dateOverrides.forEachIndexed { index, override ->
        DateOverrideEditor(
          index = index,
          override = override,
          validation = validation,
          enabled = !isSaving,
          removalPending = pendingDateOverrideRemoval == index,
          onDateChange = {
            onStateChange(WorkingScheduleEditPolicy.updateDateOverride(state, index, it))
          },
          onOpenChange = {
            onStateChange(WorkingScheduleEditPolicy.setDateOverrideOpen(state, index, it))
          },
          onWindowChange = { windowIndex, start, end ->
            onStateChange(
              WorkingScheduleEditPolicy.updateDateOverrideWindow(
                state = state,
                overrideIndex = index,
                windowIndex = windowIndex,
                startText = start,
                endText = end,
              )
            )
          },
          onAddWindow = {
            onStateChange(WorkingScheduleEditPolicy.addDateOverrideWindow(state, index))
          },
          onRemoveWindow = { windowIndex ->
            onStateChange(
              WorkingScheduleEditPolicy.removeDateOverrideWindow(
                state = state,
                overrideIndex = index,
                windowIndex = windowIndex,
              )
            )
          },
          onRequestRemoval = { pendingDateOverrideRemoval = index },
          onCancelRemoval = { pendingDateOverrideRemoval = null },
          onConfirmRemoval = {
            onStateChange(WorkingScheduleEditPolicy.removeDateOverride(state, index))
            pendingDateOverrideRemoval = null
          },
        )
      }
      TextButton(
        onClick = {
          onStateChange(WorkingScheduleEditPolicy.addDateOverride(state))
          pendingDateOverrideRemoval = null
        },
        enabled = !isSaving,
        modifier =
          Modifier.heightIn(min = MinimumTouchTarget)
            .semantics { contentDescription = "Add working-date exception" }
            .testTag("add_date_override"),
      ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(Space.xs))
        Text("Add exception")
      }
    }

    validation.summary?.let { InlineError(it, announce = true) }
    saveError?.takeIf { it.isNotBlank() }?.let { InlineError(it, announce = true) }

    Button(
      onClick = { validation.spec?.let(onSave) },
      enabled = validation.isValid && isSaveAllowed && !isSaving,
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(min = MinimumTouchTarget)
          .testTag("save_working_schedule"),
    ) {
      Text(if (isSaving) "Saving…" else saveLabel)
    }
  }
}

@Composable
private fun TimeZoneSection(
  zoneId: String,
  error: String?,
  enabled: Boolean,
  onRequestChange: (() -> Unit)?,
) {
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme
  Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
    Text(
      text = "Time zone",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.semantics { heading() },
    )
    Surface(
      color = colors.surfaceContainerLow,
      shape = MaterialTheme.shapes.small,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget).padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = zoneId.ifBlank { "No time zone selected" },
            fontFamily = FontFamily.Monospace,
            fontSize = d.body,
            color = colors.onSurface,
          )
          Text(
            text = "Working hours stay anchored to this local wall clock.",
            fontSize = d.secondary,
            color = colors.onSurfaceVariant,
          )
        }
        if (onRequestChange != null) {
          Spacer(Modifier.width(Space.sm))
          TextButton(
            onClick = onRequestChange,
            enabled = enabled,
            modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("change_work_zone"),
          ) {
            Text("Change")
          }
        }
      }
    }
    if (error != null) InlineError(error)
  }
}

@Composable
private fun NumberField(
  label: String,
  supportingText: String,
  value: String,
  error: String?,
  enabled: Boolean,
  onValueChange: (String) -> Unit,
  testTag: String,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    label = { Text(label) },
    supportingText = { Text(error ?: supportingText) },
    isError = error != null,
    singleLine = true,
    keyboardOptions =
      KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    modifier = Modifier.fillMaxWidth().testTag(testTag),
  )
}

@Composable
private fun DayEditor(
  day: WorkingScheduleDayEdit,
  validation: WorkingScheduleValidation,
  enabled: Boolean,
  onOpenChange: (Boolean) -> Unit,
  onWindowChange: (index: Int, start: String?, end: String?) -> Unit,
  onAddWindow: () -> Unit,
  onRemoveWindow: (Int) -> Unit,
) {
  val label = dayLabel(day.dayOfWeek)
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme
  Surface(
    color = colors.surfaceContainerLow,
    shape = MaterialTheme.shapes.small,
    modifier = Modifier.fillMaxWidth().testTag("work_day_${day.dayOfWeek}"),
  ) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      Box(
        modifier =
          Modifier.width(3.dp)
            .fillMaxHeight()
            .background(if (day.isOpen) colors.primary else colors.outlineVariant)
      )
      Column(
        modifier = Modifier.weight(1f).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = d.title, fontWeight = FontWeight.SemiBold)
            Text(
              if (day.isOpen) "Available" else "Closed",
              fontSize = d.secondary,
              color = colors.onSurfaceVariant,
            )
          }
          Switch(
            checked = day.isOpen,
            onCheckedChange = onOpenChange,
            enabled = enabled,
            modifier =
              Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
                .semantics {
                  contentDescription = "$label working day"
                  stateDescription = if (day.isOpen) "Open" else "Closed"
                }
                .testTag("work_day_${day.dayOfWeek}_toggle"),
          )
        }

        if (day.isOpen) {
          HorizontalDivider(color = colors.outlineVariant)
          day.windows.forEachIndexed { index, window ->
            WindowEditor(
              dayLabel = label,
              index = index,
              window = window,
              startError = validation.errorFor(startField(day.dayOfWeek, index)),
              endError = validation.errorFor(endField(day.dayOfWeek, index)),
              enabled = enabled,
              onStartChange = { onWindowChange(index, it, null) },
              onEndChange = { onWindowChange(index, null, it) },
              onRemove = { onRemoveWindow(index) },
            )
          }
          validation.errorFor(dayField(day.dayOfWeek))?.let { InlineError(it) }
          TextButton(
            onClick = onAddWindow,
            enabled = enabled,
            modifier = Modifier.heightIn(min = MinimumTouchTarget),
          ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(Space.xs))
            Text("Add window")
          }
        }
      }
    }
  }
}

@Composable
private fun DateOverrideEditor(
  index: Int,
  override: WorkingScheduleDateOverrideEdit,
  validation: WorkingScheduleValidation,
  enabled: Boolean,
  removalPending: Boolean,
  onDateChange: (String) -> Unit,
  onOpenChange: (Boolean) -> Unit,
  onWindowChange: (index: Int, start: String?, end: String?) -> Unit,
  onAddWindow: () -> Unit,
  onRemoveWindow: (Int) -> Unit,
  onRequestRemoval: () -> Unit,
  onCancelRemoval: () -> Unit,
  onConfirmRemoval: () -> Unit,
) {
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme
  val displayDate = override.localDate.ifBlank { "new date exception" }
  Surface(
    color = colors.surfaceContainerLow,
    shape = MaterialTheme.shapes.small,
    modifier = Modifier.fillMaxWidth().testTag("date_override_$index"),
  ) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      Box(
        modifier =
          Modifier.width(3.dp)
            .fillMaxHeight()
            .background(if (override.isOpen) colors.primary else colors.secondary)
      )
      Column(
        modifier = Modifier.weight(1f).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = override.localDate.ifBlank { "New exception" },
              fontSize = d.title,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = if (override.isOpen) "Custom working hours" else "Closed all day",
              fontSize = d.secondary,
              color = colors.onSurfaceVariant,
            )
          }
          IconButton(
            onClick = onRequestRemoval,
            enabled = enabled && !removalPending,
            modifier =
              Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
                .testTag("request_remove_date_override_$index"),
          ) {
            Icon(
              Icons.Default.Delete,
              contentDescription = "Remove exception for $displayDate",
            )
          }
        }

        OutlinedTextField(
          value = override.localDate,
          onValueChange = onDateChange,
          enabled = enabled,
          label = { Text("Local date") },
          placeholder = { Text("YYYY-MM-DD") },
          supportingText =
            validation.errorFor(overrideDateField(index))?.let { error ->
              { Text(error) }
            },
          isError = validation.errorFor(overrideDateField(index)) != null,
          singleLine = true,
          keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
          modifier =
            Modifier.fillMaxWidth()
              .semantics {
                contentDescription = "Exception ${index + 1} local date, YYYY-MM-DD"
              }
              .testTag("date_override_date_$index"),
        )

        Row(
          modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Availability", fontSize = d.body, fontWeight = FontWeight.Medium)
            Text(
              text = if (override.isOpen) "Use the hours below" else "Protect the whole date",
              fontSize = d.secondary,
              color = colors.onSurfaceVariant,
            )
          }
          Switch(
            checked = override.isOpen,
            onCheckedChange = onOpenChange,
            enabled = enabled,
            modifier =
              Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
                .semantics {
                  contentDescription = "Availability for $displayDate"
                  stateDescription =
                    if (override.isOpen) "Custom working hours" else "Closed all day"
                }
                .testTag("date_override_open_$index"),
          )
        }

        if (override.isOpen) {
          HorizontalDivider(color = colors.outlineVariant)
          override.windows.forEachIndexed { windowIndex, window ->
            WindowEditor(
              dayLabel = "Exception for $displayDate",
              index = windowIndex,
              window = window,
              startError =
                validation.errorFor(overrideStartField(index, windowIndex)),
              endError = validation.errorFor(overrideEndField(index, windowIndex)),
              enabled = enabled,
              onStartChange = { onWindowChange(windowIndex, it, null) },
              onEndChange = { onWindowChange(windowIndex, null, it) },
              onRemove = { onRemoveWindow(windowIndex) },
            )
          }
          validation.errorFor(overrideField(index))?.let { InlineError(it) }
          TextButton(
            onClick = onAddWindow,
            enabled = enabled,
            modifier = Modifier.heightIn(min = MinimumTouchTarget),
          ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(Space.xs))
            Text("Add window")
          }
        }

        if (removalPending) {
          Surface(
            color = colors.errorContainer,
            contentColor = colors.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier =
              Modifier.fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("confirm_remove_date_override_$index"),
          ) {
            Column(
              modifier = Modifier.padding(Space.md),
              verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
              Text(
                text = "Remove the exception for $displayDate?",
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = "Its saved closed date or custom hours will be removed when you save.",
                fontSize = d.secondary,
              )
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
              ) {
                TextButton(
                  onClick = onCancelRemoval,
                  enabled = enabled,
                  modifier = Modifier.weight(1f).heightIn(min = MinimumTouchTarget),
                ) {
                  Text("Keep")
                }
                Button(
                  onClick = onConfirmRemoval,
                  enabled = enabled,
                  modifier =
                    Modifier.weight(1f)
                      .heightIn(min = MinimumTouchTarget)
                      .semantics {
                        contentDescription = "Confirm removal for $displayDate"
                      },
                ) {
                  Text("Remove")
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WindowEditor(
  dayLabel: String,
  index: Int,
  window: WorkingScheduleWindowEdit,
  startError: String?,
  endError: String?,
  enabled: Boolean,
  onStartChange: (String) -> Unit,
  onEndChange: (String) -> Unit,
  onRemove: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
    Text(
      text = "Window ${index + 1}",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(Space.sm),
      verticalAlignment = Alignment.Top,
    ) {
      OutlinedTextField(
        value = window.startText,
        onValueChange = onStartChange,
        enabled = enabled,
        label = { Text("Start") },
        supportingText = startError?.let { { Text(it) } },
        isError = startError != null,
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        modifier =
          Modifier.weight(1f)
            .semantics { contentDescription = "$dayLabel window ${index + 1} start time" }
            .testTag("work_window_start"),
      )
      OutlinedTextField(
        value = window.endText,
        onValueChange = onEndChange,
        enabled = enabled,
        label = { Text("End") },
        supportingText = endError?.let { { Text(it) } },
        isError = endError != null,
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        modifier =
          Modifier.weight(1f)
            .semantics { contentDescription = "$dayLabel window ${index + 1} end time" }
            .testTag("work_window_end"),
      )
      IconButton(
        onClick = onRemove,
        enabled = enabled,
        modifier =
          Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
            .testTag("remove_work_window"),
      ) {
        Icon(
          Icons.Default.Delete,
          contentDescription = "Remove $dayLabel window ${index + 1}",
        )
      }
    }
  }
}

@Composable
private fun InlineError(text: String, announce: Boolean = false) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.error,
    modifier =
      if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite }
      else Modifier,
  )
}

private fun parseTime(value: String, allowEndOfDay: Boolean): Int? {
  if (!TimePattern.matches(value)) return null
  val hour = value.substring(0, 2).toInt()
  val minute = value.substring(3, 5).toInt()
  if (hour == 24 && (!allowEndOfDay || minute != 0)) return null
  if (hour > 23 && hour != 24) return null
  return hour * 60 + minute
}

private fun formatMinute(minute: Int): String {
  require(minute in 0..MinutesPerDay) { "Minute must stay inside a local day" }
  return "%02d:%02d".format(minute / 60, minute % 60)
}

private fun isExactLocalDate(value: String): Boolean {
  if (!DatePattern.matches(value)) return false
  val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
  val position = ParsePosition(0)
  return parser.parse(value, position) != null && position.index == value.length
}

private fun dayLabel(dayOfWeek: Int): String =
  when (dayOfWeek) {
    Calendar.MONDAY -> "Monday"
    Calendar.TUESDAY -> "Tuesday"
    Calendar.WEDNESDAY -> "Wednesday"
    Calendar.THURSDAY -> "Thursday"
    Calendar.FRIDAY -> "Friday"
    Calendar.SATURDAY -> "Saturday"
    Calendar.SUNDAY -> "Sunday"
    else -> error("Unknown weekday")
  }

private fun dayField(dayOfWeek: Int) = "day:$dayOfWeek"

private fun startField(dayOfWeek: Int, index: Int) = "day:$dayOfWeek:window:$index:start"

private fun endField(dayOfWeek: Int, index: Int) = "day:$dayOfWeek:window:$index:end"

private fun overrideField(index: Int) = "override:$index"

private fun overrideDateField(index: Int) = "override:$index:date"

private fun overrideStartField(overrideIndex: Int, windowIndex: Int) =
  "override:$overrideIndex:window:$windowIndex:start"

private fun overrideEndField(overrideIndex: Int, windowIndex: Int) =
  "override:$overrideIndex:window:$windowIndex:end"

private val DayOrder =
  listOf(
    Calendar.MONDAY,
    Calendar.TUESDAY,
    Calendar.WEDNESDAY,
    Calendar.THURSDAY,
    Calendar.FRIDAY,
    Calendar.SATURDAY,
    Calendar.SUNDAY,
  )
private val DefaultWindow = WorkingScheduleWindowEdit("09:00", "17:00")
private val AvailableZoneIds by lazy { TimeZone.getAvailableIDs().toSet() }
private val TimePattern = Regex("[0-2][0-9]:[0-5][0-9]")
private val DatePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private const val MinutesPerDay = 24 * 60
private const val ZoneField = "zone"
private const val MinimumField = "minimum"
private const val MaximumField = "maximum"
private const val BufferField = "buffer"
private const val DaysField = "days"
private const val SummaryField = "summary"
