package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.ui.components.PropertyChip
import com.example.ui.components.RowIconButton
import com.example.ui.components.RowTag
import com.example.ui.components.SectionToggle
import com.example.ui.components.WorkspaceRow
import com.example.ui.components.priorityState
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.BriefingViewModel

/**
 * The days ahead, one foldable section each.
 *
 * A day with nothing on it collapses to a single quiet line rather than an empty
 * card, so a light week reads as a short page instead of a wall of placeholders.
 */
@Composable
fun WeekScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onOpenDay: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()
  val events by viewModel.weekEvents.collectAsStateWithLifecycle()
  val span by viewModel.weekSpanDays.collectAsStateWithLifecycle()
  val collapsed by viewModel.collapsedSections.collectAsStateWithLifecycle()

  val window = LocalWindowWidth.current
  val gutter = window.gutter
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current

  val days =
    remember(selectedDay, events, span) {
      val grouped = ScheduleAnalysis.groupByDay(events).toMap()
      (0 until span).map { offset ->
        val day = ScheduleAnalysis.startOfDayOffset(selectedDay, offset)
        day to grouped[day].orEmpty()
      }
    }
  val total = remember(events) { events.size }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = window.readableMaxWidth).fillMaxSize(),
      contentPadding = contentPadding,
    ) {
      item(key = "header") {
        Column(modifier = Modifier.padding(start = gutter, end = gutter, bottom = Space.xs)) {
          Text(
            text = "Next $span days",
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
          )
          Text(
            text =
              formatter.mediumDay(selectedDay) +
                " – " +
                formatter.mediumDay(ScheduleAnalysis.startOfDayOffset(selectedDay, span - 1)) +
                " · $total ${if (total == 1) "event" else "events"}",
            fontSize = d.secondary,
            color = scheme.onSurfaceVariant,
          )
        }
      }

      items(days.size, key = { days[it].first }) { index ->
        val day = days[index].first
        val dayEvents = days[index].second
        val sectionKey = "week_$day"
        val conflicts = remember(dayEvents) { ScheduleAnalysis.findConflicts(dayEvents).size }

        Column(modifier = Modifier.padding(start = gutter, end = gutter, top = d.sectionGap / 2)) {
          if (dayEvents.isEmpty()) {
            // One line, no header, no toggle — an empty day should cost one row.
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = formatter.relativeDay(day),
                fontSize = d.body,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
              )
              Text(text = "Free", fontSize = d.label, color = scheme.onSurfaceVariant)
              Spacer(Modifier.width(Space.xs))
              RowIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open " + formatter.relativeDay(day),
                onClick = { onOpenDay(day) },
              )
            }
          } else {
            SectionToggle(
              title = formatter.relativeDay(day),
              expanded = !collapsed.contains(sectionKey),
              count = dayEvents.size,
              onToggle = { viewModel.toggleSection(sectionKey) },
              action = {
                if (conflicts > 0) {
                  PropertyChip(
                    if (conflicts == 1) "1 clash" else "$conflicts clashes",
                    status.deadline,
                  )
                  Spacer(Modifier.width(Space.xs))
                }
                RowIconButton(
                  icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                  contentDescription = "Open " + formatter.relativeDay(day),
                  onClick = { onOpenDay(day) },
                )
              },
            ) {
              dayEvents.forEachIndexed { i, event ->
                WeekLine(
                  event = event,
                  formatter = formatter,
                  onClick = { onEditEvent(event) },
                  isLast = i == dayEvents.lastIndex,
                )
              }
            }
          }
        }
      }

      item(key = "tail") { Spacer(Modifier.height(Space.xl)) }
    }
  }
}

@Composable
private fun WeekLine(
  event: BriefingEvent,
  formatter: TimeFormatter,
  onClick: () -> Unit,
  isLast: Boolean,
) {
  val status = LocalStatusColors.current
  val scheme = MaterialTheme.colorScheme
  val allDay = ScheduleAnalysis.isAllDay(event)
  val tone =
    when {
      event.isDeadline -> status.deadline
      event.isUrgent -> status.urgent
      else -> scheme.outline
    }
  val tags = buildList {
    if (event.isDeadline) add(RowTag("Deadline", status.deadline))
    if (event.isUrgent) add(RowTag("Urgent", status.urgent))
    if (event.source != EventSource.MANUAL) add(RowTag(event.source, scheme.onSurfaceVariant))
  }

  WorkspaceRow(
    title = event.title,
    gutterText = if (allDay) "All day" else formatter.time(event.startTime),
    gutterSubtext = if (allDay) null else formatter.duration(event.startTime, event.endTime),
    marker = tone,
    tags = tags,
    onClick = onClick,
    showDivider = !isLast,
    stateDescription = priorityState(event.isDeadline, event.isUrgent),
  )
}
