package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.ui.components.AppCard
import com.example.ui.components.EmptyState
import com.example.ui.components.Tag
import com.example.ui.components.WeekItem
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.BriefingViewModel

/**
 * Seven days from the selected one. Lays out as a single column on a phone and
 * flows into two or three on anything wider, without a second layout to maintain.
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
  val windowWidth = LocalWindowWidth.current
  val gutter = windowWidth.gutter

  val days =
    remember(selectedDay, events) {
      val grouped = ScheduleAnalysis.groupByDay(events).toMap()
      (0 until 7).map { offset ->
        val day = ScheduleAnalysis.startOfDayOffset(selectedDay, offset)
        day to (grouped[day].orEmpty())
      }
    }
  val total = remember(events) { events.size }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 320.dp),
      modifier = Modifier.fillMaxSize().widthIn(max = windowWidth.readableMaxWidth),
      contentPadding = contentPadding,
      horizontalArrangement = Arrangement.spacedBy(Space.md),
      verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
      item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
        Column(modifier = Modifier.padding(horizontal = gutter)) {
          Text(
            text = "Next 7 days",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text =
              "${formatter.mediumDay(selectedDay)} – " +
                formatter.mediumDay(ScheduleAnalysis.startOfDayOffset(selectedDay, 6)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (total == 0) {
        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
          EmptyState(
            title = "A clear week",
            message = "Nothing scheduled between now and next week.",
          )
        }
      } else {
        items(days, key = { it.first }) { (day, dayEvents) ->
          DayCard(
            day = day,
            events = dayEvents,
            formatter = formatter,
            onEditEvent = onEditEvent,
            onOpenDay = onOpenDay,
            modifier = Modifier.padding(horizontal = gutter),
          )
        }
      }
    }
  }
}

@Composable
private fun DayCard(
  day: Long,
  events: List<BriefingEvent>,
  formatter: TimeFormatter,
  onEditEvent: (BriefingEvent) -> Unit,
  onOpenDay: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val status = LocalStatusColors.current
  val conflicts = remember(events) { ScheduleAnalysis.findConflicts(events).size }

  AppCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(Space.md)) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .clip(MaterialTheme.shapes.medium)
          .clickable { onOpenDay(day) }
          .padding(horizontal = Space.sm, vertical = Space.xs),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = formatter.relativeDay(day),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      if (conflicts > 0) {
        Tag(text = "$conflicts overlap", tone = status.deadline)
        Spacer(Modifier.width(Space.xs))
      }
      Text(
        text = if (events.isEmpty()) "—" else events.size.toString(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = "Open this day",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }

    Spacer(Modifier.height(Space.xs))

    if (events.isEmpty()) {
      Text(
        text = "Nothing scheduled",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.sm),
      )
    } else {
      events.forEach { event ->
        WeekItem(event = event, formatter = formatter, onClick = { onEditEvent(event) })
      }
    }
  }
}
