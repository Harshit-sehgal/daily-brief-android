package com.example.ui.screens

import com.example.ui.theme.Radius
import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.ui.components.EmptyState
import com.example.ui.components.DaySwipeBox
import com.example.ui.components.MarkdownText
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
import com.example.ui.theme.rootTitleSize
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.AgendaGrouping
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.EventOwnership
import com.example.ui.viewmodel.TodaySection
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
  onOpenPalette: () -> Unit,
  onOpenTimeline: () -> Unit,
  onRequestCalendarPermission: () -> Unit,
  onSync: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()
  val events by viewModel.selectedDayEvents.collectAsStateWithLifecycle()
  val needsCalendarPermission by viewModel.needsCalendarPermission.collectAsStateWithLifecycle()
  val brief by viewModel.brief.collectAsStateWithLifecycle()
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
  val openDatePicker = {
    val current = Calendar.getInstance().apply { timeInMillis = selectedDay }
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
          val chosen =
            Calendar.getInstance().apply {
              clear()
              set(year, month, dayOfMonth, 12, 0, 0)
            }
          viewModel.selectDay(ScheduleAnalysis.startOfDay(chosen.timeInMillis))
        },
        current.get(Calendar.YEAR),
        current.get(Calendar.MONTH),
        current.get(Calendar.DAY_OF_MONTH),
      )
      .show()
  }

  PullToRefreshBox(
    isRefreshing = isSyncing,
    onRefresh = onSync,
    modifier = modifier.fillMaxSize().testTag("screen_today"),
  ) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.widthIn(max = window.readableMaxWidth).fillMaxSize(),
        contentPadding = contentPadding,
      ) {
        item(key = "header") {
          DaySwipeBox(
            onPrevious = { viewModel.shiftDay(-1) },
            onNext = { viewModel.shiftDay(1) },
            modifier = Modifier.padding(horizontal = gutter),
            fillViewport = false,
          ) {
            DayHeader(
              dateLabel = formatter.relativeDay(selectedDay, nowMs),
              fullDate = formatter.fullDay(selectedDay),
              isToday = isToday,
              isSyncing = isSyncing,
              onPrevious = { viewModel.shiftDay(-1) },
              onNext = { viewModel.shiftDay(1) },
              onChooseDate = openDatePicker,
              onToday = viewModel::selectToday,
              onSync = onSync,
              onSearch = onOpenPalette,
              onTimeline = onOpenTimeline,
            )
          }
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
                        showDivider = i != shown.lastIndex || conflicts.size > shown.size,
                      )
                    }
                    val remaining = conflicts.size - shown.size
                    if (remaining > 0) {
                      Text(
                        "$remaining more ${if (remaining == 1) "conflict" else "conflicts"} in this day",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Space.sm),
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
                    AgendaGroupingMenu(
                      selected = grouping,
                      onSelect = viewModel::setAgendaGrouping,
                    )
                  },
                ) {
                  if (events.isEmpty()) {
                    // A day with nothing on it is either a free day or an app with no calendar yet,
                    // and those want opposite things said to them.
                    if (!needsCalendarPermission) {
                      EmptyState(
                        headline = "Nothing scheduled.",
                        supporting = "The day is yours. Add something with the + button.",
                        tag = "agenda_empty",
                      )
                    } else {
                      EmptyState(
                        headline = "No calendar connected yet.",
                        supporting =
                          "Connect the calendar already on this phone and your day appears here.",
                        actionLabel = "Connect a calendar",
                        onAction = onRequestCalendarPermission,
                        tag = "agenda_empty",
                      )
                    }
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
                      // Keyed by event: without this the swipe state is positional,
                      // so removing one row leaves the row beneath it swiped away.
                      key(event.id) {
                        AgendaLine(
                          event = event,
                          formatter = formatter,
                          nowMs = nowMs,
                          onClick = { onEditEvent(event) },
                          onPushToTomorrow = { viewModel.moveEventByDays(event, 1) },
                          onDelete = { onOutcome ->
                            viewModel.deleteEventById(event.id, onOutcome)
                          },
                          isLast = i == groupEvents.lastIndex,
                        )
                      }
                    }
                  }
                }
            }
          }
        }
      }
    }
  }
}

/** One grouping control instead of three permanent tabs in the section header. */
@Composable
private fun AgendaGroupingMenu(
  selected: AgendaGrouping,
  onSelect: (AgendaGrouping) -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { expanded = true },
      modifier =
        Modifier.heightIn(min = 48.dp)
          .testTag("agenda_grouping")
          .semantics { stateDescription = "Grouped by ${selected.label}" },
    ) {
      Text(selected.label)
      Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      AgendaGrouping.entries.forEach { option ->
        DropdownMenuItem(
          text = { Text(option.label) },
          onClick = {
            onSelect(option)
            expanded = false
          },
          modifier =
            Modifier.semantics {
              if (option == selected) stateDescription = "Selected"
            },
        )
      }
    }
  }
}

/** Three compact numbers that wrap cleanly when the screen or type scale is large. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatStrip(events: Int, booked: String, priorities: Int) {
  val d = LocalDensityTokens.current
  val status = LocalStatusColors.current
  val scheme = MaterialTheme.colorScheme
  FlowRow(
    modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
    horizontalArrangement = Arrangement.spacedBy(Space.xl),
    verticalArrangement = Arrangement.spacedBy(Space.xs),
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
  /** Requests deletion and reports back whether the row actually went away. */
  onDelete: (onOutcome: (Boolean) -> Unit) -> Unit,
  isLast: Boolean,
) {
  val scope = rememberCoroutineScope()
  val status = LocalStatusColors.current
  val scheme = MaterialTheme.colorScheme
  val allDay = ScheduleAnalysis.isAllDay(event)
  val actions = agendaRowActionPolicy(event.source, allDay)
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
    // The source is worth a chip only when it changes what you can do here. Repeating
    // "Device Calendar" down every row of a normal day says nothing and takes the width the title
    // needs; a row you cannot edit still says so, which is the case that matters.
    if (EventOwnership.forSource(event.source) == EventOwnership.READ_ONLY_SOURCE) {
      add(RowTag(event.source, scheme.onSurfaceVariant))
    }
  }
  val accessibilityActions =
    buildList {
      if (actions.canMoveToTomorrow) {
        add(
          CustomAccessibilityAction("Move to tomorrow") {
            onPushToTomorrow()
            true
          }
        )
      }
      actions.deleteLabel?.let { label ->
        add(
          CustomAccessibilityAction(label) {
            onDelete {}
            true
          }
        )
      }
    }

  // Gestures remain shortcuts, never the only route: the trailing Actions control
  // exposes the same capability-filtered commands without requiring a swipe.
  val dismiss =
    rememberSwipeToDismissBoxState(
      // Most of the row, not a flick: deleting by accident is not recoverable
      // enough to be cheap, even with an undo.
      positionalThreshold = { width -> width * 0.55f },
    )

  // React only after the row has settled at an action anchor. This avoids the
  // deprecated confirm/veto callback and keeps the gesture animation honest.
  LaunchedEffect(dismiss.settledValue) {
    when (dismiss.settledValue) {
      SwipeToDismissBoxValue.StartToEnd -> {
        onPushToTomorrow()
        dismiss.reset()
      }
      // A read-only source can refuse the delete; put the row back when it does,
      // rather than leaving an event that still exists swiped off the screen.
      SwipeToDismissBoxValue.EndToStart ->
        onDelete { removed -> if (!removed) scope.launch { dismiss.reset() } }
      SwipeToDismissBoxValue.Settled -> Unit
    }
  }

  SwipeToDismissBox(
    state = dismiss,
    enableDismissFromStartToEnd = actions.canMoveToTomorrow,
    enableDismissFromEndToStart = actions.deleteLabel != null,
    // dismissDirection, not targetValue: the label has to appear as soon as the
    // row starts moving, not only once the action is already committed.
    backgroundContent = { SwipeBackdrop(dismiss.dismissDirection) },
  ) {
    WorkspaceRow(
      modifier =
        Modifier.testTag("agenda_item").semantics { customActions = accessibilityActions },
      title = event.title,
      gutterText = if (allDay) "All day" else formatter.time(event.startTime),
      gutterSubtext = if (allDay) null else formatter.duration(event.startTime, event.endTime),
      marker = tone,
      subtitle = event.description?.takeIf { it.isNotBlank() } ?: event.location,
      tags = tags,
      trailing = {
        AgendaRowActionMenu(
          eventId = event.id,
          eventTitle = event.title,
          actions = actions,
          onOpenDetails = onClick,
          onMoveToTomorrow = onPushToTomorrow,
          onDelete = { onDelete {} },
        )
      },
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

/**
 * Visible counterparts for every safe row gesture.
 *
 * "Open details" keeps the menu useful for read-only rows while the policy omits
 * commands Daily Brief cannot perform for their source.
 */
@Composable
private fun AgendaRowActionMenu(
  eventId: String,
  eventTitle: String,
  actions: AgendaRowActionPolicy,
  onOpenDetails: () -> Unit,
  onMoveToTomorrow: () -> Unit,
  onDelete: () -> Unit,
) {
  var expanded by rememberSaveable(eventId) { mutableStateOf(false) }

  Box {
    // An icon, not a labelled button. The word "Actions" was repeated down the whole day, competing
    // with the titles it belonged to and taking the width they needed; the same menu opens either
    // way, and the description still names the event it acts on.
    RowIconButton(
      icon = Icons.Default.MoreVert,
      contentDescription = "Actions for $eventTitle",
      onClick = { expanded = true },
      modifier = Modifier.testTag("agenda_actions_$eventId"),
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text("Open details") },
        onClick = {
          expanded = false
          onOpenDetails()
        },
        modifier = Modifier.heightIn(min = 48.dp),
      )
      if (actions.canMoveToTomorrow) {
        DropdownMenuItem(
          text = { Text("Move to tomorrow") },
          onClick = {
            expanded = false
            onMoveToTomorrow()
          },
          modifier = Modifier.heightIn(min = 48.dp),
        )
      }
      actions.deleteLabel?.let { label ->
        DropdownMenuItem(
          text = { Text(label) },
          onClick = {
            expanded = false
            onDelete()
          },
          modifier = Modifier.heightIn(min = 48.dp),
        )
      }
    }
  }
}

internal data class AgendaRowActionPolicy(
  val canMoveToTomorrow: Boolean,
  val deleteLabel: String?,
)

/** Unknown sources fail closed, matching the editor's ownership contract. */
internal fun agendaRowActionPolicy(source: String, isAllDay: Boolean): AgendaRowActionPolicy {
  val ownership = EventOwnership.forSource(source)
  return AgendaRowActionPolicy(
    canMoveToTomorrow = !isAllDay && ownership == EventOwnership.APP_OWNED,
    deleteLabel =
      when (ownership) {
        EventOwnership.APP_OWNED -> "Delete event"
        EventOwnership.DEVICE_CALENDAR -> "Delete from calendar"
        EventOwnership.READ_ONLY_SOURCE -> null
      },
  )
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
  isToday: Boolean,
  isSyncing: Boolean,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onChooseDate: () -> Unit,
  onToday: () -> Unit,
  onSync: () -> Unit,
  onSearch: () -> Unit,
  onTimeline: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  var menuOpen by rememberSaveable { mutableStateOf(false) }

  Row(
    modifier = modifier.fillMaxWidth().padding(top = Space.xs, bottom = Space.xs),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RowIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day", onPrevious)
    Column(
      modifier =
        Modifier.weight(1f)
          .heightIn(min = 48.dp)
          .clip(RoundedCornerShape(Radius.block))
          .clickable(role = Role.Button, onClickLabel = "Choose date", onClick = onChooseDate)
          // The long form still reaches a screen reader, where reading it costs no height.
          .semantics { contentDescription = fullDate }
          .padding(horizontal = Space.sm),
      verticalArrangement = Arrangement.Center,
    ) {
      // One line, not two. "Today" over "Wednesday, August 12" was two sizes of the same fact, and
      // the pair cost more height than two agenda rows. The short label already identifies the day —
      // anything that is not today or tomorrow reads as its own date — and the full form is one tap
      // away in the picker this line opens. Joining them with a dot only truncated at 360 dp.
      Text(
        text = dateLabel,
        fontSize = rootTitleSize(),
        fontWeight = FontWeight.SemiBold,
        color = scheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    RowIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day", onNext)
    Box(
      modifier =
        if (isSyncing) Modifier.semantics { stateDescription = "Sync in progress" }
        else Modifier
    ) {
      RowIconButton(Icons.Default.MoreVert, "More day actions", { menuOpen = true })
      if (isSyncing) {
        CircularProgressIndicator(
          modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(12.dp),
          strokeWidth = 1.6.dp,
        )
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (!isToday) {
          DropdownMenuItem(
            text = { Text("Go to today") },
            onClick = {
              menuOpen = false
              onToday()
            },
          )
        }
        DropdownMenuItem(
          text = { Text("Open timeline") },
          leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
          onClick = {
            menuOpen = false
            onTimeline()
          },
        )
        DropdownMenuItem(
          text = { Text("Find or run a command") },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          onClick = {
            menuOpen = false
            onSearch()
          },
        )
        DropdownMenuItem(
          text = { Text(if (isSyncing) "Syncing…" else "Sync now") },
          leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
          enabled = !isSyncing,
          onClick = {
            menuOpen = false
            onSync()
          },
        )
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
