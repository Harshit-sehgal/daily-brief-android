package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.core.TimeFormatter
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.Space

/**
 * The horizontal day picker.
 *
 * Scrolls itself to whatever is selected, so jumping to a date from anywhere in
 * the app leaves the strip in a sensible place instead of stranded at day one.
 */
@Composable
fun DateStrip(
  days: List<Long>,
  selectedDay: Long,
  todayStart: Long,
  daysWithEvents: Set<Long>,
  formatter: TimeFormatter,
  onSelect: (Long) -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = Space.lg),
) {
  val listState = rememberLazyListState()
  val selectedIndex = days.indexOf(selectedDay)

  LaunchedEffect(selectedIndex) {
    if (selectedIndex >= 0) {
      listState.animateScrollToItem(index = (selectedIndex - 2).coerceAtLeast(0))
    }
  }

  LazyRow(
    state = listState,
    modifier = modifier.testTag("date_strip"),
    contentPadding = contentPadding,
    horizontalArrangement = Arrangement.spacedBy(Space.sm),
  ) {
    itemsIndexed(days, key = { _, day -> day }) { _, day ->
      DayCell(
        dayStart = day,
        isSelected = day == selectedDay,
        isToday = day == todayStart,
        hasEvents = daysWithEvents.contains(day),
        formatter = formatter,
        onClick = { onSelect(day) },
      )
    }
  }
}

@Composable
private fun DayCell(
  dayStart: Long,
  isSelected: Boolean,
  isToday: Boolean,
  hasEvents: Boolean,
  formatter: TimeFormatter,
  onClick: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val cell = LocalDensityTokens.current.dayCell
  val background by
    animateColorAsState(
      targetValue = if (isSelected) scheme.primary else scheme.surfaceContainer,
      label = "day_cell_background",
    )
  val content by
    animateColorAsState(
      targetValue =
        when {
          isSelected -> scheme.onPrimary
          isToday -> scheme.primary
          else -> scheme.onSurface
        },
      label = "day_cell_content",
    )
  val accessibilityState =
    buildList {
        if (isToday) add("Today")
        add(if (hasEvents) "Has events" else "No events")
      }
      .joinToString(", ")

  Column(
    modifier =
      Modifier.width(cell)
        .heightIn(min = 48.dp)
        .clip(MaterialTheme.shapes.medium)
        .background(background)
        .selectable(selected = isSelected, onClick = onClick, role = Role.Tab)
        .padding(vertical = Space.xs)
        .semantics {
          contentDescription = formatter.fullDay(dayStart)
          stateDescription = accessibilityState
        },
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = formatter.weekday(dayStart),
      style = MaterialTheme.typography.labelSmall,
      color = if (isSelected) content else scheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(2.dp))
    Text(
      text = formatter.dayOfMonth(dayStart),
      style = MaterialTheme.typography.titleMedium,
      color = content,
    )
    Spacer(Modifier.height(4.dp))
    Box(
      modifier =
        Modifier.size(4.dp)
          .clip(MaterialTheme.shapes.extraSmall)
          .background(
            if (hasEvents) {
              if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant
            } else Color.Transparent
          )
          .clearAndSetSemantics {}
    )
  }
}
