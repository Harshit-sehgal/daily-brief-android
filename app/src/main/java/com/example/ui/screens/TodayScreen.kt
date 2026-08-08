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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.core.greetingFor
import com.example.data.model.BriefingEvent
import com.example.ui.components.AppCard
import com.example.ui.components.DateStrip
import com.example.ui.components.EmptyState
import com.example.ui.components.AgendaItem
import com.example.ui.components.InlineNotice
import com.example.ui.components.MarkdownText
import com.example.ui.components.SectionLabel
import com.example.ui.components.StatTile
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.delay

@Composable
fun TodayScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onRequestCalendarPermission: () -> Unit,
  onSync: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()
  val events by viewModel.selectedDayEvents.collectAsStateWithLifecycle()
  val stripDays by viewModel.stripDays.collectAsStateWithLifecycle()
  val daysWithEvents by viewModel.daysWithEvents.collectAsStateWithLifecycle()
  val brief by viewModel.brief.collectAsStateWithLifecycle()
  val profileName by viewModel.profileName.collectAsStateWithLifecycle()
  val needsPermission by viewModel.needsCalendarPermission.collectAsStateWithLifecycle()
  val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()

  val windowWidth = LocalWindowWidth.current
  val gutter = windowWidth.gutter
  val status = LocalStatusColors.current

  // Ticks once a minute so "now" markers stay honest without a busy loop.
  val nowMs by
    produceState(initialValue = System.currentTimeMillis()) {
      while (true) {
        value = System.currentTimeMillis()
        delay(60_000L)
      }
    }

  val stats = remember(events) { ScheduleAnalysis.statsFor(events) }
  val conflicts = remember(events) { ScheduleAnalysis.findConflicts(events) }
  val sorted = remember(events) { events.sortedWith(compareBy({ it.startTime }, { it.title })) }
  val todayStart = remember(nowMs) { ScheduleAnalysis.startOfDay(nowMs) }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = windowWidth.readableMaxWidth),
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
      item(key = "header") {
        val isToday = selectedDay == todayStart
        val greeting = remember(nowMs) { greetingFor(nowMs) }
        DayHeader(
          // A greeting only makes sense on the day you are actually in.
          title =
            when {
              !isToday -> formatter.relativeDay(selectedDay, nowMs)
              profileName.isBlank() -> greeting
              else -> "$greeting, $profileName"
            },
          dateLabel = formatter.fullDay(selectedDay),
          isToday = isToday,
          isSyncing = isSyncing,
          onPrevious = { viewModel.shiftDay(-1) },
          onNext = { viewModel.shiftDay(1) },
          onToday = { viewModel.selectToday() },
          onSync = onSync,
          modifier = Modifier.padding(horizontal = gutter),
        )
      }

      item(key = "strip") {
        DateStrip(
          days = stripDays,
          selectedDay = selectedDay,
          todayStart = todayStart,
          daysWithEvents = daysWithEvents,
          formatter = formatter,
          onSelect = viewModel::selectDay,
          contentPadding = PaddingValues(horizontal = gutter),
        )
      }

      if (needsPermission) {
        item(key = "permission") {
          InlineNotice(
            text = "Calendar access is off, so nothing is being pulled in.",
            tone = status.urgent,
            icon = Icons.Default.Warning,
            actionLabel = "Allow",
            onAction = onRequestCalendarPermission,
            modifier = Modifier.padding(horizontal = gutter),
          )
        }
      }

      item(key = "stats") {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
          horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
          StatTile(
            label = if (stats.total == 1) "Event" else "Events",
            value = stats.total.toString(),
            tone = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
          StatTile(
            label = "Booked",
            value = bookedLabel(stats.bookedMinutes),
            tone = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
          StatTile(
            label = if (stats.urgent == 1) "Priority" else "Priorities",
            value = stats.urgent.toString(),
            tone = if (stats.urgent > 0) status.urgent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
        }
      }

      if (conflicts.isNotEmpty()) {
        item(key = "conflicts") {
          ConflictCard(
            conflicts = conflicts,
            formatter = formatter,
            modifier = Modifier.padding(horizontal = gutter),
          )
        }
      }

      item(key = "brief") {
        BriefCard(
          markdown = brief.markdown,
          isLoading = brief.isLoading,
          isStale = brief.isStale,
          note = brief.note,
          isFallback = brief.isFallback,
          hasEvents = events.isNotEmpty(),
          onGenerate = viewModel::generateBrief,
          modifier = Modifier.padding(horizontal = gutter),
        )
      }

      item(key = "agenda_label") {
        SectionLabel("Agenda", modifier = Modifier.padding(horizontal = gutter, vertical = Space.xs))
      }

      if (sorted.isEmpty()) {
        item(key = "agenda_empty") {
          EmptyState(
            title = "Nothing scheduled",
            message = "Add something, or connect a calendar in Settings.",
          )
        }
      } else {
        items(sorted, key = { it.id }) { event ->
          AgendaItem(
            event = event,
            formatter = formatter,
            nowMs = nowMs,
            onClick = { onEditEvent(event) },
            modifier = Modifier.padding(horizontal = gutter),
          )
        }
      }
    }
  }
}

private fun bookedLabel(minutes: Int): String {
  if (minutes <= 0) return "0h"
  val hours = minutes / 60
  val mins = minutes % 60
  return when {
    hours == 0 -> "${mins}m"
    mins == 0 -> "${hours}h"
    else -> "${hours}h" + mins.toString().padStart(2, '0')
  }
}

@Composable
private fun DayHeader(
  title: String,
  dateLabel: String,
  isToday: Boolean,
  isSyncing: Boolean,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onToday: () -> Unit,
  onSync: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (isSyncing) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
      } else {
        IconButton(onClick = onSync, modifier = Modifier.testTag("sync")) {
          Icon(imageVector = Icons.Default.Refresh, contentDescription = "Sync now")
        }
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = dateLabel,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      AnimatedVisibility(visible = !isToday) {
        TextButton(onClick = onToday) { Text("Today") }
      }
      IconButton(onClick = onPrevious) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
          contentDescription = "Previous day",
        )
      }
      IconButton(onClick = onNext) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = "Next day",
        )
      }
    }
  }
}

@Composable
private fun ConflictCard(
  conflicts: List<ScheduleAnalysis.Conflict>,
  formatter: TimeFormatter,
  modifier: Modifier = Modifier,
) {
  val status = LocalStatusColors.current
  AppCard(modifier = modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        tint = status.deadline,
        modifier = Modifier.size(16.dp),
      )
      Spacer(Modifier.width(Space.sm))
      Text(
        text =
          if (conflicts.size == 1) "1 overlap" else "${conflicts.size} overlaps",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Spacer(Modifier.height(Space.sm))
    conflicts.take(3).forEach { conflict ->
      Text(
        text =
          "${formatter.time(conflict.second.startTime)}  ${conflict.first.title} · ${conflict.second.title}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 2.dp),
      )
    }
    if (conflicts.size > 3) {
      Text(
        text = "and ${conflicts.size - 3} more",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun BriefCard(
  markdown: String,
  isLoading: Boolean,
  isStale: Boolean,
  note: String?,
  isFallback: Boolean,
  hasEvents: Boolean,
  onGenerate: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val status = LocalStatusColors.current
  AppCard(modifier = modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "Brief",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
      } else if (markdown.isNotBlank()) {
        IconButton(onClick = onGenerate, modifier = Modifier.size(32.dp)) {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Rewrite the brief",
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }

    Spacer(Modifier.height(Space.sm))

    when {
      markdown.isNotBlank() -> MarkdownText(markdown)
      isLoading ->
        Text(
          text = "Writing…",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      else ->
        Column {
          Text(
            text =
              if (hasEvents) "Get a short read on today's schedule."
              else "Nothing to summarise yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (hasEvents) {
            Spacer(Modifier.height(Space.sm))
            TextButton(onClick = onGenerate, contentPadding = PaddingValues(0.dp)) {
              Text("Write the brief")
            }
          }
        }
    }

    if (isStale && markdown.isNotBlank()) {
      Spacer(Modifier.height(Space.md))
      InlineNotice(
        text = "The schedule changed after this was written.",
        tone = status.urgent,
        actionLabel = "Rewrite",
        onAction = onGenerate,
      )
    }

    if (isFallback && note != null) {
      Spacer(Modifier.height(Space.md))
      InlineNotice(text = note, tone = status.deadline)
    }
  }
}
