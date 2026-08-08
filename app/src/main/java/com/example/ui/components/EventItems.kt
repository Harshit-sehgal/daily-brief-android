package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.Space

/** Colour that stands for an event's priority. Neutral unless it means something. */
@Composable
fun eventTone(event: BriefingEvent, isNow: Boolean = false): Color {
  val status = LocalStatusColors.current
  return when {
    event.isDeadline -> status.deadline
    event.isUrgent -> status.urgent
    isNow -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
  }
}

private fun isRunning(event: BriefingEvent, nowMs: Long) =
  nowMs in event.startTime until event.endTime

/**
 * A row in the day's agenda: time on the left, everything else on the right.
 * The whole row opens the editor, so there are no per-row icon buttons to crowd it.
 */
@Composable
fun AgendaItem(
  event: BriefingEvent,
  formatter: TimeFormatter,
  nowMs: Long,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val running = isRunning(event, nowMs)
  val tone = eventTone(event, running)
  val allDay = ScheduleAnalysis.isAllDay(event)

  AppCard(
    modifier = modifier.fillMaxWidth().testTag("agenda_item"),
    onClick = onClick,
    contentPadding = PaddingValues(Space.md),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.width(58.dp)) {
        Text(
          text = if (allDay) "All day" else formatter.time(event.startTime),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        if (!allDay) {
          Text(
            text = formatter.duration(event.startTime, event.endTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Box(
        modifier = Modifier.width(3.dp).height(36.dp).background(tone, RoundedCornerShape(2.dp))
      )
      Spacer(Modifier.width(Space.md))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = event.title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val subtitle = event.description?.takeIf { it.isNotBlank() } ?: event.location
        if (!subtitle.isNullOrBlank()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        val flags = flagsFor(event, running)
        if (flags.isNotEmpty()) {
          Spacer(Modifier.height(Space.xs))
          Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            flags.forEach { (label, colour) -> Tag(text = label, tone = colour) }
          }
        }
      }
    }
  }
}

/** One line per event, for the week list where density matters more than detail. */
@Composable
fun WeekItem(
  event: BriefingEvent,
  formatter: TimeFormatter,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val tone = eventTone(event)
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.medium)
        .clickable(onClick = onClick)
        .padding(vertical = Space.sm, horizontal = Space.sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text =
        if (ScheduleAnalysis.isAllDay(event)) "All day" else formatter.time(event.startTime),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(58.dp),
    )
    Dot(tone = tone, size = 6.dp)
    Spacer(Modifier.width(Space.md))
    Text(
      text = event.title,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

/** A card on the board. Tap to edit; the arrows move it between columns. */
@Composable
fun BoardCard(
  event: BriefingEvent,
  formatter: TimeFormatter,
  canMoveLeft: Boolean,
  canMoveRight: Boolean,
  onClick: () -> Unit,
  onMoveLeft: () -> Unit,
  onMoveRight: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val tone = eventTone(event)
  AppCard(
    modifier = modifier.fillMaxWidth().testTag("board_card"),
    onClick = onClick,
    outlined = true,
    contentPadding = PaddingValues(start = Space.md, end = Space.xs, top = Space.md, bottom = Space.xs),
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Dot(tone = tone, size = 6.dp, modifier = Modifier.padding(top = 6.dp, end = Space.sm))
      Text(
        text = event.title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(end = Space.sm),
      )
    }
    Spacer(Modifier.height(Space.xs))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "${formatter.mediumDay(event.startTime)} · ${formatter.time(event.startTime)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = onMoveLeft, enabled = canMoveLeft, modifier = Modifier.size(32.dp)) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
          contentDescription = "Move to previous column",
          modifier = Modifier.size(18.dp),
        )
      }
      IconButton(onClick = onMoveRight, enabled = canMoveRight, modifier = Modifier.size(32.dp)) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = "Move to next column",
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
private fun flagsFor(event: BriefingEvent, running: Boolean): List<Pair<String, Color>> {
  val status = LocalStatusColors.current
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  return buildList {
    if (running) add("Now" to MaterialTheme.colorScheme.primary)
    if (event.isDeadline) add("Deadline" to status.deadline)
    if (event.isUrgent) add("Urgent" to status.urgent)
    if (event.source != EventSource.MANUAL) add(event.source to muted)
  }
}
