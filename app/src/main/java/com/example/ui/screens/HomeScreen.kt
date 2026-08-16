package com.example.ui.screens

import com.example.ui.theme.Radius
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.example.ui.theme.rootTitleSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.DayPulse
import com.example.core.PlanHealthAssessment
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.ui.components.PropertyChip
import com.example.ui.components.RowIconButton
import com.example.ui.components.RowTag
import com.example.ui.components.WorkspaceRow
import com.example.ui.components.priorityState
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.theme.readableMaxWidth
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.HomeCard
import com.example.ui.viewmodel.PlanHealthUiState
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
  onOpenTimeline: () -> Unit,
  onOpenPalette: () -> Unit,
  onOpenAgenda: () -> Unit,
  onReviewConflicts: () -> Unit,
  onOpenBoard: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val events by viewModel.todayEvents.collectAsStateWithLifecycle()
  val cards by viewModel.homeCards.collectAsStateWithLifecycle()
  val name by viewModel.profileName.collectAsStateWithLifecycle()
  val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
  val activePlanBoard by viewModel.activePlanBoard.collectAsStateWithLifecycle()
  val planColumns by viewModel.planColumns.collectAsStateWithLifecycle()
  val planItems by viewModel.planItems.collectAsStateWithLifecycle()
  val planHealth by viewModel.planHealth.collectAsStateWithLifecycle()

  val window = LocalWindowWidth.current
  val gutter = window.gutter
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  var editingLayout by rememberSaveable { mutableStateOf(false) }

  // Back finishes editing rather than leaving Home still in edit mode on return.
  BackHandler(enabled = editingLayout) { editingLayout = false }

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
  val planSummary =
    remember(activePlanBoard, planColumns, planItems) {
      HomePlanSummaryProjector.project(activePlanBoard, planColumns, planItems)
    }

  PullToRefreshBox(
    isRefreshing = isSyncing,
    onRefresh = { viewModel.sync() },
    modifier = modifier.fillMaxSize().testTag("screen_home"),
  ) {
   Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = window.readableMaxWidth).fillMaxSize(),
      contentPadding = contentPadding,
    ) {
      item(key = "greeting") {
        // Home is a root like Calendar and Plan, so its header behaves like theirs: one line for the
        // greeting, one for the date, and the actions kept together. Left to wrap, a 25 sp greeting
        // and a full date ran to four lines on a small phone and the actions floated in the middle
        // of them.
        Row(
          modifier =
            Modifier.fillMaxWidth().padding(start = gutter, end = gutter, bottom = Space.sm),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = Space.sm)) {
            Text(
              text = greeting(nowMs, name),
              fontSize = rootTitleSize(),
              fontWeight = FontWeight.SemiBold,
              color = scheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${formatter.fullDay(today)} · At a glance",
              fontSize = d.secondary,
              color = scheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            RowIconButton(
              icon = Icons.Default.Search,
              contentDescription = "Search and commands",
              onClick = onOpenPalette,
              modifier = Modifier.testTag("global_search"),
            )
            TextButton(
              onClick = { editingLayout = !editingLayout },
              modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("home_edit_layout"),
            ) {
              Text(if (editingLayout) "Done" else "Edit")
            }
            RowIconButton(
              icon = Icons.Default.Settings,
              contentDescription = "Open settings",
              onClick = onOpenSettings,
              modifier = Modifier.testTag("open_settings"),
            )
          }
        }
      }

      if (editingLayout) {
        item(key = "layout_editor") {
          HomeLayoutEditor(
            visibleCards = cards,
            onMove = viewModel::moveHomeCard,
            onVisibilityChange = viewModel::setHomeCardVisible,
            modifier = Modifier.padding(horizontal = gutter, vertical = Space.sm),
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
                  onOpen = onReviewConflicts,
                  modifier = Modifier.padding(start = gutter, end = gutter, top = d.sectionGap),
                )
              }
            }

          HomeCard.Board ->
            item(key = "board") {
              BoardBlock(
                summary = planSummary,
                onOpen = onOpenBoard,
                modifier = Modifier.padding(start = gutter, end = gutter, top = d.sectionGap),
              )
            }

          HomeCard.Health ->
            item(key = "plan_health") {
              PlanHealthBlock(
                state = planHealth,
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

/** In-context Home customization; Settings keeps the same controls as a fallback. */
@Composable
private fun HomeLayoutEditor(
  visibleCards: List<HomeCard>,
  onMove: (HomeCard, Int) -> Unit,
  onVisibilityChange: (HomeCard, Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    shape = RoundedCornerShape(Radius.block),
    color = colors.surfaceContainerLow,
    modifier = modifier.fillMaxWidth().testTag("home_layout_editor"),
  ) {
    Column(modifier = Modifier.padding(Space.md)) {
      Text(
        "Arrange Home",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        "Show the answers you use most and move them into glance order.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Space.sm),
      )
      HomeCard.entries.forEach { card ->
        val shown = card in visibleCards
        val index = visibleCards.indexOf(card)
        Row(
          modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(card.label, style = MaterialTheme.typography.bodyMedium)
            Text(
              card.blurb,
              style = MaterialTheme.typography.bodySmall,
              color = colors.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          RowIconButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = "Move ${card.label} up",
            onClick = { onMove(card, -1) },
            enabled = shown && index > 0,
          )
          RowIconButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = "Move ${card.label} down",
            onClick = { onMove(card, 1) },
            enabled = shown && index in 0 until visibleCards.lastIndex,
          )
          Switch(
            checked = shown,
            onCheckedChange = { onVisibilityChange(card, it) },
            enabled = !shown || visibleCards.size > 1,
            modifier =
              Modifier.semantics { contentDescription = "Show ${card.label} on Home" }
                .testTag("home_inline_card_${card.key}"),
          )
        }
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
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current

  val current = pulse.current
  val next = pulse.next
  val focusEvent = current ?: next

  val (label, tone) =
    when {
      current != null -> "NOW" to status.deadline
      next != null && pulse.freeMinutes != null ->
        "FREE FOR ${DayPulse.humanDuration(pulse.freeMinutes)}" to scheme.primary
      pulse.isDone -> "DONE FOR THE DAY" to status.positive
      else -> "NOTHING SCHEDULED" to scheme.onSurfaceVariant
    }

  Surface(
    shape = RoundedCornerShape(Radius.block),
    color = tone.copy(alpha = 0.10f),
    modifier =
      modifier.fillMaxWidth().testTag("home_focus").then(
        if (focusEvent != null) {
          Modifier.clickable(role = Role.Button) { onClick(focusEvent) }
        } else {
          Modifier
        }
      ),
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
            text = "Use + to plan something",
            fontSize = d.body,
            color = scheme.onSurfaceVariant,
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
          .clip(RoundedCornerShape(Radius.mark))
          .background(scheme.surfaceContainerHigh)
    ) {
      Box(
        modifier =
          Modifier.fillMaxWidth(fraction)
            .height(6.dp)
            .clip(RoundedCornerShape(Radius.mark))
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

/** Optional utility shortcuts; creation stays on the global + action. */
@Composable
private fun ActionsBlock(
  isSyncing: Boolean,
  onOpenTimeline: () -> Unit,
  onOpenPalette: () -> Unit,
  onSync: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
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
    shape = RoundedCornerShape(Radius.block),
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
        TextButton(
          onClick = onOpenAgenda,
          contentPadding = PaddingValues(horizontal = Space.sm),
          modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text(text = "+$more more", fontSize = d.label)
        }
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
    shape = RoundedCornerShape(Radius.block),
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

/** A one-glance, fail-closed capacity signal; Plan contains the complete explanation. */
@Composable
private fun PlanHealthBlock(
  state: PlanHealthUiState,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current
  val result = state.result
  val tone =
    when (result?.assessment) {
      PlanHealthAssessment.ON_TRACK -> status.positive
      PlanHealthAssessment.AT_RISK -> status.urgent
      PlanHealthAssessment.OVERCOMMITTED -> status.deadline
      PlanHealthAssessment.INCOMPLETE_DATA,
      null -> scheme.onSurfaceVariant
    }
  val title =
    when (result?.assessment) {
      PlanHealthAssessment.ON_TRACK -> "On track"
      PlanHealthAssessment.AT_RISK -> "At risk"
      PlanHealthAssessment.OVERCOMMITTED -> "Overcommitted"
      PlanHealthAssessment.INCOMPLETE_DATA -> "More data needed"
      null -> "Plan Health unavailable"
    }
  val detail =
    result?.let { health ->
      when (health.assessment) {
        PlanHealthAssessment.ON_TRACK ->
          "${DayPulse.humanDuration(health.freeAfterPlannedMinutes)} free in the next 7 days"
        PlanHealthAssessment.OVERCOMMITTED ->
          "${DayPulse.humanDuration(health.overloadMinutes)} does not fit"
        PlanHealthAssessment.AT_RISK ->
          "${health.risks.size + health.warnings.size} risks need review"
        PlanHealthAssessment.INCOMPLETE_DATA ->
          "${health.missingEstimateCount} estimates missing; totals are partial"
      }
    } ?: state.unavailableReason.orEmpty()

  Surface(
    onClick = onOpen,
    shape = RoundedCornerShape(Radius.block),
    color = tone.copy(alpha = 0.10f),
    modifier = modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget).testTag("home_plan_health"),
  ) {
    Row(
      modifier = Modifier.padding(Space.md),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(7.dp).background(tone, CircleShape))
      Spacer(Modifier.width(Space.sm))
      Column(modifier = Modifier.weight(1f)) {
        Text(title, fontSize = d.title, fontWeight = FontWeight.Medium, color = scheme.onSurface)
        Text(detail, fontSize = d.secondary, color = scheme.onSurfaceVariant)
      }
      Text("Details", fontSize = d.label, color = tone)
    }
  }
}

/** Exact Plan Board lanes and first tasks — the full board is one tap away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardBlock(
  summary: HomePlanSummary?,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val remaining = (summary?.totalItems ?: 0) - (summary?.preview?.size ?: 0)

  Column(
    modifier =
      modifier.fillMaxWidth()
        .heightIn(min = MinimumTouchTarget)
        .clip(RoundedCornerShape(Radius.control))
        .clickable(role = Role.Button, onClick = onOpen)
        .semantics(mergeDescendants = true) {
          contentDescription =
            if (summary == null) {
              "Open Plan Board. Preparing the active board"
            } else {
              val laneCounts =
                summary.visibleLanes.joinToString { lane -> "${lane.title} ${lane.count}" }
              "Open ${summary.boardName} Plan Board. ${summary.totalItems} active tasks. $laneCounts"
            }
        }
        .testTag("home_board"),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "BOARD",
        fontSize = d.label,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = summary?.boardName ?: "Preparing Plan…",
        fontSize = d.label,
        color = scheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
    Spacer(Modifier.height(Space.sm))
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(Space.xs),
      verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
      summary?.visibleLanes?.forEach { lane ->
        PropertyChip("${lane.title} ${lane.count}", scheme.onSurfaceVariant)
      }
      if (summary == null) PropertyChip("Loading", scheme.onSurfaceVariant)
      summary?.hiddenLaneCount?.takeIf { it > 0 }?.let { hidden ->
        PropertyChip("+$hidden more", scheme.onSurfaceVariant)
      }
    }

    when {
      summary == null ->
        Text(
          text = "Plan is preparing the active board.",
          fontSize = d.secondary,
          color = scheme.onSurfaceVariant,
          modifier = Modifier.padding(top = Space.sm),
        )
      summary.totalItems == 0 ->
        Text(
          text = "No active tasks on this board.",
          fontSize = d.secondary,
          color = scheme.onSurfaceVariant,
          modifier = Modifier.padding(top = Space.sm),
        )
      else -> {
        Spacer(Modifier.height(Space.sm))
        summary.preview.forEach { preview ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = preview.item.title,
              fontSize = d.secondary,
              color = scheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.sm))
            PropertyChip(
              text =
                if (preview.item.isMilestone) "${preview.laneTitle} · milestone"
                else preview.laneTitle,
              tone = scheme.onSurfaceVariant,
            )
          }
        }
        if (remaining > 0) {
          Text(
            text = "+$remaining more on Board",
            fontSize = d.label,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.xs),
          )
        }
      }
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
