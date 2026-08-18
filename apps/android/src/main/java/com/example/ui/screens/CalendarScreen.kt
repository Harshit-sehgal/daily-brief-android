package com.example.ui.screens

import com.example.ui.theme.Radius
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.ui.components.WorkspaceRootHeader
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.WindowWidth
import com.example.ui.theme.gutter
import com.example.ui.viewmodel.BriefingViewModel

enum class CalendarView(val label: String) {
  Agenda("Agenda"),
  Timeline("Timeline"),
  Week("Week");

  companion object {
    fun fromStored(value: String?): CalendarView =
      entries.firstOrNull { it.label == value } ?: Agenda
  }
}

/** Relative widths for the fixed Agenda-left, Timeline-right day desk. */
internal data class CalendarPaneWeights(
  val agenda: Float,
  val timeline: Float,
)

/**
 * Keeps both day representations useful while giving the selected one a modest working advantage.
 * Their positions never change, so selecting a view changes emphasis rather than spatial meaning.
 */
internal fun calendarPaneWeights(
  width: WindowWidth,
  primary: CalendarView,
): CalendarPaneWeights {
  val emphasis =
    when (width) {
      WindowWidth.Expanded -> 0.10f
      WindowWidth.Large -> 0.16f
      WindowWidth.ExtraLarge -> 0.20f
      WindowWidth.Compact,
      WindowWidth.Medium -> 0f
    }
  return when (primary) {
    CalendarView.Agenda -> CalendarPaneWeights(1f + emphasis, 1f - emphasis)
    CalendarView.Timeline -> CalendarPaneWeights(1f - emphasis, 1f + emphasis)
    CalendarView.Week -> CalendarPaneWeights(1f, 1f)
  }
}

/** Agenda, time grid and week are representations of one preserved date context. */
@Composable
fun CalendarScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onCreateAt: (Long) -> Unit,
  onOpenPalette: () -> Unit,
  onOpenSettings: () -> Unit,
  onRequestCalendarPermission: () -> Unit,
  onSync: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val storedView by viewModel.calendarView.collectAsStateWithLifecycle()
  val view = CalendarView.fromStored(storedView)
  val window = LocalWindowWidth.current
  val gutter = window.gutter
  val childPadding =
    PaddingValues(
      top = Space.sm,
      bottom = contentPadding.calculateBottomPadding(),
    )

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(top = contentPadding.calculateTopPadding())
        .testTag("screen_calendar")
  ) {
    WorkspaceRootHeader(
      title = "Calendar",
      views = CalendarView.entries.map { it.label },
      selectedView = CalendarView.entries.indexOf(view),
      onSelectView = { viewModel.setCalendarView(CalendarView.entries[it].label) },
      onSearch = onOpenPalette,
      onSettings = onOpenSettings,
      tagPrefix = "calendar",
      modifier = Modifier.padding(horizontal = gutter),
    )

    Box(modifier = Modifier.weight(1f)) {
      if (window.supportsMultiPane && view != CalendarView.Week) {
        WideDayCalendar(
          primary = view,
          window = window,
          viewModel = viewModel,
          formatter = formatter,
          contentPadding = contentPadding,
          onEditEvent = onEditEvent,
          onCreateAt = onCreateAt,
          onOpenPalette = onOpenPalette,
          onRequestCalendarPermission = onRequestCalendarPermission,
          onSync = onSync,
          onMakePrimary = { viewModel.setCalendarView(it.label) },
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        AnimatedContent(
          targetState = view,
          transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
          label = "calendar_view",
          modifier = Modifier.fillMaxSize(),
        ) { current ->
          when (current) {
            CalendarView.Agenda ->
              TodayScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = childPadding,
                onEditEvent = onEditEvent,
                onOpenPalette = onOpenPalette,
                onOpenTimeline = { viewModel.setCalendarView(CalendarView.Timeline.label) },
                onRequestCalendarPermission = onRequestCalendarPermission,
                onSync = onSync,
              )
            CalendarView.Timeline ->
              DayTimelineContent(
                viewModel = viewModel,
                formatter = formatter,
                onEditEvent = onEditEvent,
                onCreateAt = onCreateAt,
                contentPadding = childPadding,
              )
            CalendarView.Week ->
              WeekScreen(
                viewModel = viewModel,
                formatter = formatter,
                contentPadding = childPadding,
                onEditEvent = onEditEvent,
                onOpenDay = { day ->
                  viewModel.selectDay(day)
                  viewModel.setCalendarView(CalendarView.Agenda.label)
                },
              )
          }
        }
      }
    }
  }
}

/**
 * A stable two-pane day desk: Agenda is always left, Timeline is always right. Both retain their
 * complete controls and share the selected date; the stored Calendar view only sets priority.
 */
@Composable
private fun WideDayCalendar(
  primary: CalendarView,
  window: WindowWidth,
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onCreateAt: (Long) -> Unit,
  onOpenPalette: () -> Unit,
  onRequestCalendarPermission: () -> Unit,
  onSync: () -> Unit,
  onMakePrimary: (CalendarView) -> Unit,
  modifier: Modifier = Modifier,
) {
  val weights = calendarPaneWeights(window, primary)
  val panePadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())

  Row(
    modifier =
      modifier.padding(
        start = window.gutter,
        top = Space.sm,
        end = window.gutter,
      ),
  ) {
    CalendarDayPane(
      title = CalendarView.Agenda.label,
      isPrimary = primary == CalendarView.Agenda,
      onMakePrimary = { onMakePrimary(CalendarView.Agenda) },
      modifier =
        Modifier.weight(weights.agenda).fillMaxHeight().testTag("calendar_agenda_pane"),
    ) {
      TodayScreen(
        viewModel = viewModel,
        formatter = formatter,
        contentPadding = panePadding,
        onEditEvent = onEditEvent,
        onOpenPalette = onOpenPalette,
        onOpenTimeline = { onMakePrimary(CalendarView.Timeline) },
        onRequestCalendarPermission = onRequestCalendarPermission,
        onSync = onSync,
      )
    }

    Spacer(
      Modifier.padding(horizontal = Space.sm)
        .fillMaxHeight()
        .width(1.dp)
        .background(MaterialTheme.colorScheme.outlineVariant),
    )

    CalendarDayPane(
      title = CalendarView.Timeline.label,
      isPrimary = primary == CalendarView.Timeline,
      onMakePrimary = { onMakePrimary(CalendarView.Timeline) },
      modifier =
        Modifier.weight(weights.timeline).fillMaxHeight().testTag("calendar_timeline_pane"),
    ) {
      DayTimelineContent(
        viewModel = viewModel,
        formatter = formatter,
        onEditEvent = onEditEvent,
        onCreateAt = onCreateAt,
        contentPadding = panePadding,
      )
    }
  }
}

@Composable
private fun CalendarDayPane(
  title: String,
  isPrimary: Boolean,
  onMakePrimary: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    modifier = modifier.semantics { selected = isPrimary },
    shape = RoundedCornerShape(Radius.container),
    color = if (isPrimary) colors.surfaceContainerLow else colors.surfaceContainerLowest,
    border =
      BorderStroke(
        width = if (isPrimary) 2.dp else 1.dp,
        color = if (isPrimary) colors.primary else colors.outlineVariant,
      ),
  ) {
    Column(Modifier.fillMaxSize()) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = Space.md, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Medium,
          color = if (isPrimary) colors.primary else colors.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        if (isPrimary) {
          Text(
            text = "Primary",
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            modifier = Modifier.padding(horizontal = Space.sm),
          )
        } else {
          TextButton(onClick = onMakePrimary) { Text("Make primary") }
        }
      }
      HorizontalDivider(color = colors.outlineVariant)
      Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
  }
}
