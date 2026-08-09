package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.DayPulse
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.ui.components.PropertyChip
import com.example.ui.components.RowTag
import com.example.ui.components.WorkspaceRow
import com.example.ui.components.priorityState
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.HomeCard
import kotlinx.coroutines.delay

/**
 * The landing page: the day answered, not the day listed.
 *
 * Every other screen in the app shows you your events. This one is the only
 * screen that tries to answer "so what should I be doing" — what you are in,
 * what is next, how much is left — and then gets out of the way. Anything that
 * needs reading rather than glancing lives one tap deeper.
 *
 * Which blocks appear, and in what order, is the user's call.
 */
@Composable
fun HomeScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onNewEvent: () -> Unit,
  onOpenTimeline: () -> Unit,
  onOpenPalette: () -> Unit,
  onOpenAgenda: () -> Unit,
  onOpenBoard: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val events by viewModel.todayEvents.collectAsStateWithLifecycle()
  val cards by viewModel.homeCards.collectAsStateWithLifecycle()
  val name by viewModel.profileName.collectAsStateWithLifecycle()
  val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
  val boardEvents by viewModel.activeBoardEvents.collectAsStateWithLifecycle()
  val columns by viewModel.boardColumns.collectAsStateWithLifecycle()

  val window = LocalWindowWidth.current
  val gutter = window.gutter
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme

  // A landing page that shows a stale countdown is worse than one that shows none.
  val nowMs by
    produceState(initialValue = System.currentTimeMillis()) {
      while (true) {
        value = System.currentTimeMillis()
        delay(30_000L)
      }
    }

  val today = remember(nowMs) { ScheduleAnalysis.startOfDay(nowMs) }
  val pulse = remember(events, nowMs, today) { DayPulse.of(events, nowMs, today) }
  val conflicts = remember(events) { ScheduleAnalysis.findConflicts(events) }

  PullToRefreshBox(
    isRefreshing = isSyncing,
    onRefresh = { viewModel.sync() },
    modifier = modifier.fillMaxSize(),
  ) {
   Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = window.readableMaxWidth).fillMaxSize(),
      contentPadding = contentPadding,
    ) {
      item(key = "greeting") {
        Column(modifier = Modifier.padding(start = gutter, end = gutter, bottom = Space.sm)) {
          Text(
            text = greeting(nowMs, name),
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
          )
          Text(
            text = formatter.fullDay(today),
            fontSize = d.secondary,
            color = scheme.onSurfaceVariant,
          )
        }
      }

      cards.forEach { card ->
        when (card) {
          HomeCard.Focus ->
            item(key = "focus") {
              FocusBlock(
                pulse = pulse,
                formatter = formatter,
                onClick = { event -> onEditEvent(event) },
                onNewEvent = onNewEvent,
                modifier = Modifier.padding(start = gutter, end = gutter, top = Space.sm),
              )
            }

          HomeCard.Progress ->
            item(key = "progress") {
              ProgressBlock(
                pulse = pulse,
                modifier = Modifier.padding(start = gutter, end = gutter, top = d.sectionGap),
              )
            }

          HomeCard.Actions ->
            item(key = "actions") {
              ActionsBlock(
                isSyncing = isSyncing,
                onNewEvent = onNewEvent,
                onOpenTimeline = onOpenTimeline,
                onOpenPalette = onOpenPalette,
                onSync = { viewModel.sync() },
                modifier = Modifier.padding(start = gutter, end = gutter, top = d.sectionGap),
              )
            }

          HomeCard.UpNext ->
            item(key = "upnext") {
              UpNextBlock(
                pulse = pulse,
                formatter = formatter,
                onEditEvent = onEditEvent,
                onOpenAgenda = onOpenAgenda,
                modifier = Modifier.padding(start = gutter, end = gutter, top = d.sectionGap),
              )
            }

          HomeCard.Attention ->
            if (conflicts.isNotEmpty()) {
              item(key = "attention") {
                AttentionBlock(
                  conflicts = conflicts.size,
                  onOpen = onOpenAgenda,
                  modifier = Modifier.padding(start = gutter, end = gutter, top = d.sectionGap),
                )
              }
            }

          HomeCard.Board ->
            item(key = "board") {
              BoardBlock(
                columns = columns,
                events = boardEvents,
                onOpen = onOpenBoard,
                modifier = Modifier.padding(start = gutter, end = gutter, top = d.sectionGap),
              )
            }
        }
      }

      item(key = "tail") { Spacer(Modifier.height(Space.xxl)) }
    }
   }
  }
}

/* ---------------------------------------------------------------- blocks -- */

/** The hero: one sentence about right now, sized so it reads from arm's length. */
@Composable
private fun FocusBlock(
  pulse: DayPulse.Snapshot,
  formatter: TimeFormatter,
  onClick: (BriefingEvent) -> Unit,
  onNewEvent: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current

  val current = pulse.current
  val next = pulse.next

  val (label, tone) =
    when {
      current != null -> "NOW" to status.deadline
      next != null && pulse.freeMinutes != null ->
        "FREE FOR ${DayPulse.humanDuration(pulse.freeMinutes)}" to scheme.primary
      pulse.isDone -> "DONE FOR THE DAY" to status.positive
      else -> "NOTHING SCHEDULED" to scheme.onSurfaceVariant
    }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = tone.copy(alpha = 0.10f),
    modifier = modifier.fillMaxWidth().testTag("home_focus"),
  ) {
    Column(modifier = Modifier.padding(Space.lg)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).background(tone, CircleShape))
        Spacer(Modifier.width(Space.sm))
        Text(
          text = label,
          fontSize = d.label,
          fontWeight = FontWeight.Medium,
          letterSpacing = 0.9.sp,
          color = tone,
        )
      }

      Spacer(Modifier.height(Space.sm))

      when {
        current != null -> {
          Text(
            text = current.title,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onClick(current) },
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text =
              buildString {
                pulse.minutesLeft?.let { append(DayPulse.humanDuration(it)).append(" left · ") }
                append("until ").append(formatter.time(current.endTime))
              },
            fontSize = d.body,
            color = scheme.onSurfaceVariant,
          )
          if (next != null) {
            Spacer(Modifier.height(Space.sm))
            Text(
              text = "Then ${next.title} at ${formatter.time(next.startTime)}",
              fontSize = d.secondary,
              color = scheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.clickable { onClick(next) },
            )
          }
        }

        next != null -> {
          Text(
            text = next.title,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onClick(next) },
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text =
              buildString {
                append("at ").append(formatter.time(next.startTime))
                pulse.minutesToNext?.let {
                  append(" · in ").append(DayPulse.humanDuration(it))
                }
              },
            fontSize = d.body,
            color = scheme.onSurfaceVariant,
          )
        }

        else -> {
          Text(
            text = if (pulse.isDone) "Everything is behind you." else "The day is yours.",
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
          )
          Spacer(Modifier.height(Space.sm))
          Text(
            text = "Add something",
            fontSize = d.body,
            fontWeight = FontWeight.Medium,
            color = scheme.primary,
            modifier = Modifier.clickable(onClick = onNewEvent),
          )
        }
      }
    }
  }
}

/** How far through the committed day you are, as a bar rather than a paragraph. */
@Composable
private fun ProgressBlock(pulse: DayPulse.Snapshot, modifier: Modifier = Modifier) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val fraction by animateFloatAsState(pulse.progress.coerceIn(0f, 1f), label = "day-progress")
  val remaining = (pulse.bookedMinutes * (1f - pulse.progress)).toInt().coerceAtLeast(0)

  Column(
    modifier =
      modifier.fillMaxWidth().semantics {
        contentDescription =
          "${pulse.finished} of ${pulse.total} done, " +
            "${DayPulse.humanDuration(remaining)} of booked time left"
      }
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "DAY",
        fontSize = d.label,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "${pulse.finished} of ${pulse.total} done",
        fontSize = d.label,
        color = scheme.onSurfaceVariant,
      )
    }

    Spacer(Modifier.height(Space.sm))

    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(6.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(scheme.surfaceContainerHigh)
    ) {
      Box(
        modifier =
          Modifier.fillMaxWidth(fraction)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(scheme.primary)
      )
    }

    Spacer(Modifier.height(Space.xs))

    Text(
      text =
        when {
          pulse.bookedMinutes == 0 -> "Nothing booked"
          remaining == 0 -> DayPulse.humanDuration(pulse.bookedMinutes) + " booked · all done"
          else ->
            DayPulse.humanDuration(pulse.bookedMinutes) +
              " booked · " +
              DayPulse.humanDuration(remaining) +
              " left"
        },
      fontSize = d.secondary,
      color = scheme.onSurfaceVariant,
    )
  }
}

/** The four things people actually open the app to do. */
@Composable
private fun ActionsBlock(
  isSyncing: Boolean,
  onNewEvent: () -> Unit,
  onOpenTimeline: () -> Unit,
  onOpenPalette: () -> Unit,
  onSync: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
    QuickAction(Icons.Default.Add, "New", onNewEvent, Modifier.weight(1f))
    QuickAction(Icons.Default.DateRange, "Timeline", onOpenTimeline, Modifier.weight(1f))
    QuickAction(Icons.Default.Search, "Find", onOpenPalette, Modifier.weight(1f))
    QuickAction(
      Icons.Default.Refresh,
      if (isSyncing) "Syncing" else "Sync",
      onSync,
      Modifier.weight(1f),
      enabled = !isSyncing,
    )
  }
}

@Composable
private fun QuickAction(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val tint = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.5f)

  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(10.dp),
    color = scheme.surfaceContainerLow,
    modifier = modifier.testTag("quick_" + label.lowercase()),
  ) {
    Column(
      modifier = Modifier.padding(vertical = Space.md),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(d.icon + 2.dp))
      Spacer(Modifier.height(Space.xs))
      Text(text = label, fontSize = d.label, color = tint, maxLines = 1)
    }
  }
}

/** The next few things — three at most, because this is a glance, not the agenda. */
@Composable
private fun UpNextBlock(
  pulse: DayPulse.Snapshot,
  formatter: TimeFormatter,
  onEditEvent: (BriefingEvent) -> Unit,
  onOpenAgenda: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current

  // What is running is already the hero; repeating it here wastes the space.
  val shown = remember(pulse) { pulse.upcoming.filter { it != pulse.current }.take(3) }
  val more = (pulse.upcoming.size - (if (pulse.current != null) 1 else 0) - shown.size)
    .coerceAtLeast(0)

  Column(modifier = modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "UP NEXT",
        fontSize = d.label,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      if (more > 0) {
        Text(
          text = "+$more more",
          fontSize = d.label,
          color = scheme.primary,
          modifier = Modifier.clickable(onClick = onOpenAgenda),
        )
      }
    }

    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))

    if (shown.isEmpty()) {
      Text(
        text = if (pulse.isClear) "Nothing on today" else "Nothing else today",
        fontSize = d.secondary,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Space.sm),
      )
    } else {
      shown.forEachIndexed { index, event ->
        val tone =
          when {
            event.isDeadline -> status.deadline
            event.isUrgent -> status.urgent
            else -> scheme.outline
          }
        WorkspaceRow(
          title = event.title,
          gutterText =
            if (ScheduleAnalysis.isAllDay(event)) "All day" else formatter.time(event.startTime),
          marker = tone,
          tags =
            buildList {
              if (event.isDeadline) add(RowTag("Deadline", status.deadline))
              if (event.isUrgent) add(RowTag("Urgent", status.urgent))
            },
          onClick = { onEditEvent(event) },
          showDivider = index != shown.lastIndex,
          stateDescription = priorityState(event.isDeadline, event.isUrgent),
        )
      }
    }
  }
}

/** Only ever rendered when there is something wrong, so it never becomes wallpaper. */
@Composable
private fun AttentionBlock(conflicts: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current

  Surface(
    onClick = onOpen,
    shape = RoundedCornerShape(10.dp),
    color = status.deadline.copy(alpha = 0.10f),
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(Space.md),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(7.dp).background(status.deadline, CircleShape))
      Spacer(Modifier.width(Space.sm))
      Text(
        text = if (conflicts == 1) "1 clash today" else "$conflicts clashes today",
        fontSize = d.title,
        fontWeight = FontWeight.Medium,
        color = scheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      Text(text = "Review", fontSize = d.label, color = status.deadline)
    }
  }
}

/** Where the work stands, as counts — the board itself is one tap away. */
@Composable
private fun BoardBlock(
  columns: List<String>,
  events: List<BriefingEvent>,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val counts = remember(columns, events) { columns.map { c -> c to events.count { it.kanbanStatus == c } } }

  Column(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
    Text(
      text = "BOARD",
      fontSize = d.label,
      fontWeight = FontWeight.Medium,
      letterSpacing = 0.8.sp,
      color = scheme.onSurfaceVariant,
    )
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
    Spacer(Modifier.height(Space.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
      counts.forEach { (name, count) -> PropertyChip("$name $count", scheme.onSurfaceVariant) }
    }
  }
}

/* ---------------------------------------------------------------- helpers -- */

private fun greeting(nowMs: Long, name: String): String {
  val hour = ScheduleAnalysis.hourOf(nowMs)
  val part =
    when {
      hour < 5 -> "Still up"
      hour < 12 -> "Good morning"
      hour < 17 -> "Good afternoon"
      hour < 22 -> "Good evening"
      else -> "Good night"
    }
  return if (name.isBlank()) part else "$part, $name"
}
