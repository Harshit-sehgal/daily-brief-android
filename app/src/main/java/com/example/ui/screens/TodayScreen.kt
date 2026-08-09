package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.core.greetingFor
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.ui.components.DateStrip
import com.example.ui.components.InlineAddRow
import com.example.ui.components.MarkdownText
import com.example.ui.components.PropertyChip
import com.example.ui.components.RowIconButton
import com.example.ui.components.RowTag
import com.example.ui.components.SectionToggle
import com.example.ui.components.ViewSwitcher
import com.example.ui.components.WorkspaceRow
import com.example.ui.components.priorityState
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.AgendaGrouping
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.TodaySection
import kotlinx.coroutines.delay

/**
 * The day as a workspace.
 *
 * Sections fold away and can be reordered or hidden, content sits on the page
 * ground rather than in a card each, and every measurement comes from the density
 * tokens — so the same screen serves a glance and a full working day.
 */
@Composable
fun TodayScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onNewEvent: () -> Unit,
  onOpenPalette: () -> Unit,
  onOpenTimeline: () -> Unit,
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
  val sections by viewModel.todaySections.collectAsStateWithLifecycle()
  val collapsed by viewModel.collapsedSections.collectAsStateWithLifecycle()
  val grouping by viewModel.agendaGrouping.collectAsStateWithLifecycle()

  val window = LocalWindowWidth.current
  val gutter = window.gutter
  val d = LocalDensityTokens.current
  val status = LocalStatusColors.current
  val scheme = MaterialTheme.colorScheme

  val nowMs by
    produceState(initialValue = System.currentTimeMillis()) {
      while (true) {
        value = System.currentTimeMillis()
        delay(60_000L)
      }
    }

  val stats = remember(events) { ScheduleAnalysis.statsFor(events) }
  val conflicts = remember(events) { ScheduleAnalysis.findConflicts(events) }
  val todayStart = remember(nowMs) { ScheduleAnalysis.startOfDay(nowMs) }
  val isToday = selectedDay == todayStart
  val groups = remember(events, grouping) { groupAgenda(events, grouping) }

  PullToRefreshBox(
    isRefreshing = isSyncing,
    onRefresh = onSync,
    modifier = modifier.fillMaxSize(),
  ) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.widthIn(max = window.readableMaxWidth).fillMaxSize(),
        contentPadding = contentPadding,
      ) {
        item(key = "header") {
          DayHeader(
            dateLabel = formatter.relativeDay(selectedDay, nowMs),
            fullDate = formatter.fullDay(selectedDay),
            greeting =
              if (isToday) {
                val greeting = remember(nowMs) { greetingFor(nowMs) }
                if (profileName.isBlank()) greeting else "$greeting, $profileName"
              } else null,
            isToday = isToday,
            isSyncing = isSyncing,
            onPrevious = { viewModel.shiftDay(-1) },
            onNext = { viewModel.shiftDay(1) },
            onToday = viewModel::selectToday,
            onSync = onSync,
            onSearch = onOpenPalette,
            onTimeline = onOpenTimeline,
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
            modifier = Modifier.padding(bottom = Space.sm),
          )
        }

        if (needsPermission) {
          item(key = "permission") {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Calendar access is off",
                fontSize = d.secondary,
                color = status.urgent,
                modifier = Modifier.weight(1f),
              )
              TextButton(onClick = onRequestCalendarPermission) {
                Text("Allow", fontSize = d.secondary)
              }
            }
          }
        }

        val liveSections =
          sections.filter { section ->
            when (section) {
              // A "no conflicts" header is noise; the section earns its space or goes.
              TodaySection.Conflicts -> conflicts.isNotEmpty()
              // Keep the brief reachable whenever there is something to summarise.
              TodaySection.Brief -> brief.markdown.isNotBlank() || brief.isLoading || events.isNotEmpty()
              TodaySection.Summary,
              TodaySection.Agenda -> true
            }
          }

        items(liveSections.size, key = { liveSections[it].key }) { index ->
          val section = liveSections[index]
          val expanded = !collapsed.contains(section.key)
          Column(
            modifier = Modifier.padding(start = gutter, end = gutter, top = d.sectionGap / 2)
          ) {
            when (section) {
              TodaySection.Summary ->
                SectionToggle(
                  title = "Summary",
                  expanded = expanded,
                  onToggle = { viewModel.toggleSection(section.key) },
                ) {
                  StatStrip(
                    events = stats.total,
                    booked = bookedLabel(stats.bookedMinutes),
                    priorities = stats.urgent,
                  )
                }

              TodaySection.Conflicts ->
                SectionToggle(
                  title = "Conflicts",
                  expanded = expanded,
                  count = conflicts.size,
                  onToggle = { viewModel.toggleSection(section.key) },
                ) {
                  run {
                    val shown = conflicts.take(6)
                    shown.forEachIndexed { i, conflict ->
                      WorkspaceRow(
                        title = "${conflict.first.title}  ↔  ${conflict.second.title}",
                        gutterText = formatter.time(conflict.second.startTime),
                        marker = status.deadline,
                        subtitle = formatter.duration(0L, conflict.overlapMs) + " overlap",
                        onClick = { onEditEvent(conflict.second) },
                        showDivider = i != shown.lastIndex,
                      )
                    }
                  }
                }

              TodaySection.Brief ->
                SectionToggle(
                  title = "Brief",
                  expanded = expanded,
                  onToggle = { viewModel.toggleSection(section.key) },
                  action = {
                    if (brief.isLoading) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.6.dp,
                      )
                    } else {
                      RowIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "Rewrite the brief",
                        onClick = viewModel::generateBrief,
                      )
                    }
                  },
                ) {
                  BriefBody(
                    markdown = brief.markdown,
                    isLoading = brief.isLoading,
                    isStale = brief.isStale,
                    isFallback = brief.isFallback,
                    note = brief.note,
                    hasEvents = events.isNotEmpty(),
                    onGenerate = viewModel::generateBrief,
                  )
                }

              TodaySection.Agenda ->
                SectionToggle(
                  title = "Agenda",
                  expanded = expanded,
                  count = events.size,
                  onToggle = { viewModel.toggleSection(section.key) },
                  action = {
                    RowIconButton(
                      icon = Icons.Default.DateRange,
                      contentDescription = "Open the day timeline",
                      onClick = onOpenTimeline,
                    )
                    ViewSwitcher(
                      options = AgendaGrouping.entries.map { it.label },
                      selectedIndex = AgendaGrouping.entries.indexOf(grouping),
                      onSelect = { viewModel.setAgendaGrouping(AgendaGrouping.entries[it]) },
                    )
                  },
                ) {
                  if (events.isEmpty()) {
                    Text(
                      text = "Nothing scheduled yet.",
                      fontSize = d.secondary,
                      color = scheme.onSurfaceVariant,
                      modifier = Modifier.padding(vertical = Space.xs),
                    )
                  }
                  groups.forEach { (label, groupEvents) ->
                    if (label != null) {
                      Text(
                        text = label,
                        fontSize = d.label,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Space.sm, bottom = 2.dp),
                      )
                    }
                    groupEvents.forEachIndexed { i, event ->
                      AgendaLine(
                        event = event,
                        formatter = formatter,
                        nowMs = nowMs,
                        onClick = { onEditEvent(event) },
                        onPushToTomorrow = {
                          viewModel.moveEventTo(
                            event,
                            event.startTime + ScheduleAnalysis.DAY_MS,
                          )
                        },
                        onDelete = { viewModel.deleteEventById(event.id) },
                        isLast = i == groupEvents.lastIndex,
                      )
                    }
                  }
                  InlineAddRow(label = "New event", onClick = onNewEvent)
                }
            }
          }
        }
      }
    }
  }
}

/** Three numbers on one line — the tiles' information at a third of the height. */
@Composable
private fun StatStrip(events: Int, booked: String, priorities: Int) {
  val d = LocalDensityTokens.current
  val status = LocalStatusColors.current
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
    horizontalArrangement = Arrangement.spacedBy(Space.xl),
  ) {
    listOf(
        Triple(events.toString(), if (events == 1) "event" else "events", scheme.onSurface),
        Triple(booked, "booked", scheme.onSurface),
        Triple(
          priorities.toString(),
          if (priorities == 1) "priority" else "priorities",
          if (priorities > 0) status.urgent else scheme.onSurface,
        ),
      )
      .forEach { (value, label, tone) ->
        Row(verticalAlignment = Alignment.Bottom) {
          Text(text = value, fontSize = d.title, fontWeight = FontWeight.SemiBold, color = tone)
          Spacer(Modifier.width(4.dp))
          Text(text = label, fontSize = d.secondary, color = scheme.onSurfaceVariant)
        }
      }
  }
}

@Composable
private fun AgendaLine(
  event: BriefingEvent,
  formatter: TimeFormatter,
  nowMs: Long,
  onClick: () -> Unit,
  onPushToTomorrow: () -> Unit,
  onDelete: () -> Unit,
  isLast: Boolean,
) {
  val status = LocalStatusColors.current
  val scheme = MaterialTheme.colorScheme
  val allDay = ScheduleAnalysis.isAllDay(event)
  val running = nowMs in event.startTime until event.endTime
  val tone =
    when {
      event.isDeadline -> status.deadline
      event.isUrgent -> status.urgent
      running -> scheme.primary
      else -> scheme.outline
    }
  val tags = buildList {
    if (running) add(RowTag("Now", scheme.primary))
    if (event.isDeadline) add(RowTag("Deadline", status.deadline))
    if (event.isUrgent) add(RowTag("Urgent", status.urgent))
    if (event.source != EventSource.MANUAL) add(RowTag(event.source, scheme.onSurfaceVariant))
  }

  // Push right to defer, pull left to delete — the two things you actually do to
  // a row you are looking at. Deferring snaps back because the row is not leaving
  // the app, only the day; deleting lets the row go and offers an undo.
  val dismiss =
    rememberSwipeToDismissBoxState(
      // Most of the row, not a flick: deleting by accident is not recoverable
      // enough to be cheap, even with an undo.
      positionalThreshold = { width -> width * 0.55f },
      confirmValueChange = { value ->
        when (value) {
          SwipeToDismissBoxValue.StartToEnd -> {
            onPushToTomorrow()
            false
          }
          SwipeToDismissBoxValue.EndToStart -> {
            onDelete()
            true
          }
          SwipeToDismissBoxValue.Settled -> false
        }
      }
    )

  SwipeToDismissBox(
    state = dismiss,
    enableDismissFromStartToEnd = !allDay,
    // dismissDirection, not targetValue: the label has to appear as soon as the
    // row starts moving, not only once the action is already committed.
    backgroundContent = { SwipeBackdrop(dismiss.dismissDirection) },
  ) {
    WorkspaceRow(
      modifier = Modifier.testTag("agenda_item"),
      title = event.title,
      gutterText = if (allDay) "All day" else formatter.time(event.startTime),
      gutterSubtext = if (allDay) null else formatter.duration(event.startTime, event.endTime),
      marker = tone,
      subtitle = event.description?.takeIf { it.isNotBlank() } ?: event.location,
      tags = tags,
      onClick = onClick,
      showDivider = !isLast,
      stateDescription =
        listOfNotNull(
            if (running) "In progress" else null,
            priorityState(event.isDeadline, event.isUrgent),
          )
          .joinToString(", ")
          .ifEmpty { null },
    )
  }
}

/** What shows behind a row while it is being swiped: the action, named. */
@Composable
private fun SwipeBackdrop(direction: SwipeToDismissBoxValue) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current

  // Settled has to paint nothing at all: tinting it would leave every row of a
  // resting list sitting on a wash of colour.
  val action =
    when (direction) {
      SwipeToDismissBoxValue.StartToEnd -> Triple(status.urgent, "Tomorrow", Alignment.CenterStart)
      SwipeToDismissBoxValue.EndToStart -> Triple(status.deadline, "Delete", Alignment.CenterEnd)
      SwipeToDismissBoxValue.Settled -> null
    } ?: return

  val (tone, label, alignment) = action

  Box(
    modifier =
      Modifier.fillMaxSize().background(tone.copy(alpha = 0.16f)).padding(horizontal = Space.lg),
    contentAlignment = alignment,
  ) {
    Text(text = label, fontSize = d.label, fontWeight = FontWeight.Medium, color = tone)
  }
}

@Composable
private fun BriefBody(
  markdown: String,
  isLoading: Boolean,
  isStale: Boolean,
  isFallback: Boolean,
  note: String?,
  hasEvents: Boolean,
  onGenerate: () -> Unit,
) {
  val d = LocalDensityTokens.current
  val status = LocalStatusColors.current
  val scheme = MaterialTheme.colorScheme
  Column(modifier = Modifier.padding(vertical = Space.xs)) {
    when {
      markdown.isNotBlank() -> MarkdownText(markdown)
      isLoading -> Text("Writing…", fontSize = d.secondary, color = scheme.onSurfaceVariant)
      else ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = if (hasEvents) "No brief yet." else "Nothing to summarise.",
            fontSize = d.secondary,
            color = scheme.onSurfaceVariant,
          )
          if (hasEvents) {
            TextButton(onClick = onGenerate) { Text("Write it", fontSize = d.secondary) }
          }
        }
    }
    if (isStale && markdown.isNotBlank()) {
      Spacer(Modifier.height(Space.xs))
      Row(verticalAlignment = Alignment.CenterVertically) {
        PropertyChip("Out of date", status.urgent)
        TextButton(onClick = onGenerate) { Text("Rewrite", fontSize = d.secondary) }
      }
    }
    if (isFallback && note != null) {
      Spacer(Modifier.height(Space.xs))
      Text(text = note, fontSize = d.secondary, color = status.deadline)
    }
  }
}

@Composable
private fun DayHeader(
  dateLabel: String,
  fullDate: String,
  greeting: String?,
  isToday: Boolean,
  isSyncing: Boolean,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onToday: () -> Unit,
  onSync: () -> Unit,
  onSearch: () -> Unit,
  onTimeline: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  Column(modifier = modifier.fillMaxWidth().padding(top = Space.xs, bottom = Space.xs)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = dateLabel,
          fontSize = 21.sp,
          fontWeight = FontWeight.SemiBold,
          color = scheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = greeting ?: fullDate,
          fontSize = d.secondary,
          color = scheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      RowIconButton(Icons.Default.DateRange, "Open the day timeline", onTimeline)
      RowIconButton(Icons.Default.Search, "Search and commands", onSearch)
      if (isSyncing) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.6.dp)
        }
      } else {
        RowIconButton(Icons.Default.Refresh, "Sync now", onSync)
      }
      RowIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day", onPrevious)
      RowIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day", onNext)
    }
    if (!isToday) {
      TextButton(onClick = onToday, contentPadding = PaddingValues(0.dp)) {
        Text("Back to today", fontSize = d.secondary)
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

/** Splits the agenda by the active grouping. A null label means "no header". */
private fun groupAgenda(
  events: List<BriefingEvent>,
  grouping: AgendaGrouping,
): List<Pair<String?, List<BriefingEvent>>> {
  val sorted = events.sortedWith(compareBy({ it.startTime }, { it.title }))
  if (sorted.isEmpty()) return emptyList()
  return when (grouping) {
    AgendaGrouping.Time -> listOf(null to sorted)
    AgendaGrouping.Source -> sorted.groupBy { it.source }.toSortedMap().map { it.key to it.value }
    AgendaGrouping.Priority -> {
      val deadlines = sorted.filter { it.isDeadline }
      val urgent = sorted.filter { it.isUrgent && !it.isDeadline }
      val rest = sorted.filterNot { it.isDeadline || it.isUrgent }
      buildList {
        if (deadlines.isNotEmpty()) add("Deadlines" to deadlines)
        if (urgent.isNotEmpty()) add("Urgent" to urgent)
        if (rest.isNotEmpty()) add("Everything else" to rest)
      }
    }
  }
}
