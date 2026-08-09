package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.core.TimelineLayout
import com.example.data.model.BriefingEvent
import com.example.ui.components.DaySwipeBox
import com.example.ui.components.PropertyChip
import com.example.ui.components.RowIconButton
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.Space
import com.example.ui.viewmodel.BriefingViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val MINUTES_PER_DAY = 24 * 60
private val GutterWidth = 52.dp

/** Right margin shared by the hour rules, the blocks and the now-line. */
private val EndInset = 12.dp

/**
 * The day as a time grid, the way a calendar shows it.
 *
 * A list tells you what is on; a grid tells you where the gaps are — which is the
 * question people actually open a schedule to answer. Blocks are positioned and
 * sized by real time, overlaps sit side by side, and a line marks now.
 *
 * Interactions follow the calendar conventions people already know: tap a block
 * to edit, tap empty space to create there, long-press then drag to move.
 */
@Composable
fun DayTimelineScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  onEditEvent: (BriefingEvent) -> Unit,
  onCreateAt: (Long) -> Unit,
  onDismiss: () -> Unit,
) {
  val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()
  val events by viewModel.selectedDayEvents.collectAsStateWithLifecycle()
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current

  val nowMs by
    produceState(initialValue = System.currentTimeMillis()) {
      while (true) {
        value = System.currentTimeMillis()
        delay(30_000L)
      }
    }

  val slots = remember(events, selectedDay) { TimelineLayout.layout(events, selectedDay) }
  val allDay = remember(events) { TimelineLayout.allDay(events) }
  val nowMinute = TimelineLayout.nowMinute(nowMs, selectedDay)
  val scroll = rememberScrollState()
  val density = LocalDensity.current
  val hourPx = with(density) { d.hourHeight.toPx() }

  // Open where the day actually is: on now, or on the first event, or the morning.
  LaunchedEffect(selectedDay) {
    val focusMinute = nowMinute ?: slots.minOfOrNull { it.startMinute } ?: (8 * 60)
    val target = (focusMinute / 60f) * hourPx - hourPx
    scroll.scrollTo(target.roundToInt().coerceAtLeast(0))
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
      Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- header ----
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.sm),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = formatter.relativeDay(selectedDay, nowMs),
              fontSize = 19.sp,
              fontWeight = FontWeight.SemiBold,
              color = scheme.onSurface,
            )
            Text(
              text = formatter.fullDay(selectedDay),
              fontSize = d.secondary,
              color = scheme.onSurfaceVariant,
            )
          }
          RowIconButton(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            "Previous day",
            { viewModel.shiftDay(-1) },
          )
          RowIconButton(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            "Next day",
            { viewModel.shiftDay(1) },
          )
          RowIconButton(Icons.Default.Add, "New event", { onCreateAt(defaultSlot(selectedDay, nowMs)) })
          RowIconButton(Icons.Default.Close, "Close timeline", onDismiss)
        }

        if (allDay.isNotEmpty()) {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .padding(start = GutterWidth, end = Space.lg, top = Space.xs, bottom = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
          ) {
            allDay.take(3).forEach { event ->
              Box(modifier = Modifier.weight(1f, fill = false)) {
                PropertyChip(event.title, scheme.primary)
              }
            }
          }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))

        // ---- grid ----
        DaySwipeBox(
          onPrevious = { viewModel.shiftDay(-1) },
          onNext = { viewModel.shiftDay(1) },
        ) {
        BoxWithConstraints(
          modifier = Modifier.fillMaxSize().verticalScroll(scroll).navigationBarsPadding()
        ) {
          val laneAreaWidth = maxWidth - GutterWidth - EndInset
          val gridHeight = d.hourHeight * 24

          Box(
            modifier =
              Modifier.fillMaxWidth()
                .height(gridHeight)
                .pointerInput(selectedDay, hourPx) {
                  detectTapGestures { offset ->
                    val minute = ((offset.y / hourPx) * 60f).toInt()
                    val snapped = TimelineLayout.snapMinute(minute, 30, 60)
                    onCreateAt(
                      ScheduleAnalysis.withTimeOfDay(selectedDay, snapped / 60, snapped % 60)
                    )
                  }
                }
                .drawBehind {
                  // Hour rules, with the half-hour a touch fainter to give rhythm
                  // without turning the page into graph paper.
                  val gutter = GutterWidth.toPx()
                  val right = size.width - EndInset.toPx()
                  for (hour in 0..24) {
                    val y = hour * hourPx
                    drawLine(
                      color = scheme.outlineVariant,
                      start = Offset(gutter, y),
                      end = Offset(right, y),
                      strokeWidth = 1f,
                    )
                    if (hour < 24) {
                      drawLine(
                        color = scheme.outlineVariant.copy(alpha = 0.45f),
                        start = Offset(gutter, y + hourPx / 2f),
                        end = Offset(right, y + hourPx / 2f),
                        strokeWidth = 1f,
                      )
                    }
                  }
                }
                .testTag("timeline_grid")
          ) {
            // hour labels
            (0..23).forEach { hour ->
              Text(
                text = hourLabel(hour, formatter.is24Hour),
                fontSize = d.label,
                color = scheme.onSurfaceVariant,
                modifier =
                  Modifier.padding(end = Space.sm)
                    .width(GutterWidth)
                    .offsetY(d.hourHeight * hour - 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
              )
            }

            slots.forEach { slot ->
              TimelineBlock(
                slot = slot,
                formatter = formatter,
                hourHeight = d.hourHeight,
                laneAreaWidth = laneAreaWidth,
                onClick = { onEditEvent(slot.event) },
                onMoved = { minutes ->
                  viewModel.moveEventTo(
                    slot.event,
                    ScheduleAnalysis.withTimeOfDay(selectedDay, minutes / 60, minutes % 60),
                  )
                },
              )
            }

            if (nowMinute != null) {
              NowLine(
                minute = nowMinute,
                hourHeight = d.hourHeight,
                color = status.deadline,
              )
            }
          }
        }
        }
      }
    }
  }
}

@Composable
private fun TimelineBlock(
  slot: TimelineLayout.Slot,
  formatter: TimeFormatter,
  hourHeight: Dp,
  laneAreaWidth: Dp,
  onClick: () -> Unit,
  onMoved: (Int) -> Unit,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current
  val density = LocalDensity.current
  val hourPx = with(density) { hourHeight.toPx() }

  val tone =
    when {
      slot.event.isDeadline -> status.deadline
      slot.event.isUrgent -> status.urgent
      else -> scheme.primary
    }

  val laneWidth = laneAreaWidth / slot.lanes
  val heightDp = hourHeight * ((slot.endMinute - slot.startMinute) / 60f)
  val baseTop = hourHeight * (slot.startMinute / 60f)

  var dragMinutes by remember(slot.event.id, slot.startMinute) { mutableIntStateOf(0) }
  var dragging by remember(slot.event.id) { mutableStateOf(false) }
  var accumulated by remember(slot.event.id) { mutableFloatStateOf(0f) }

  val topDp = baseTop + hourHeight * (dragMinutes / 60f)
  val duration = slot.endMinute - slot.startMinute

  Box(
    modifier =
      Modifier.offsetXY(GutterWidth + laneWidth * slot.lane, topDp)
        .width(laneWidth)
        .height(heightDp)
        .padding(end = 3.dp, top = 1.dp, bottom = 1.dp)
        .clip(RoundedCornerShape(7.dp))
        .background(tone.copy(alpha = if (dragging) 0.34f else 0.18f))
        .drawBehind {
          drawRoundRect(
            color = tone,
            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
          )
        }
        .pointerInput(slot.event.id) { detectTapGestures { onClick() } }
        .pointerInput(slot.event.id, hourPx) {
          // Long-press first so a drag never fights the timeline's own scrolling.
          detectDragGesturesAfterLongPress(
            onDragStart = {
              dragging = true
              accumulated = 0f
            },
            onDrag = { change, delta ->
              accumulated += delta.y
              dragMinutes = ((accumulated / hourPx) * 60f).roundToInt()
              change.consume()
            },
            onDragCancel = {
              dragging = false
              dragMinutes = 0
            },
            onDragEnd = {
              dragging = false
              val target =
                TimelineLayout.snapMinute(slot.startMinute + dragMinutes, 15, duration)
              dragMinutes = 0
              if (target != slot.startMinute) onMoved(target)
            },
          )
        }
        .testTag("timeline_block"),
  ) {
    // A 15-minute block is barely taller than one line of text, so it gets a
    // single centred line rather than a title clipped through the middle.
    if (heightDp < 32.dp) {
      Row(
        modifier = Modifier.fillMaxSize().padding(start = 9.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = slot.event.title,
          fontSize = d.label,
          fontWeight = FontWeight.Medium,
          color = scheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    } else {
      Column(modifier = Modifier.padding(start = 9.dp, end = 6.dp, top = 3.dp)) {
        Text(
          text = slot.event.title,
          fontSize = d.secondary,
          fontWeight = FontWeight.SemiBold,
          color = scheme.onSurface,
          maxLines = if (heightDp > 52.dp) 2 else 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (heightDp > 40.dp) {
          Text(
            text = formatter.time(slot.event.startTime),
            fontSize = d.label,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
private fun NowLine(minute: Int, hourHeight: Dp, color: Color) {
  val y = hourHeight * (minute / 60f)
  Box(modifier = Modifier.offsetXY(0.dp, y - 4.dp).fillMaxWidth().height(8.dp)) {
    Box(
      modifier =
        Modifier.padding(start = GutterWidth - 4.dp)
          .size(8.dp)
          .background(color, CircleShape)
          .align(Alignment.CenterStart)
    )
    Box(
      modifier =
        Modifier.padding(start = GutterWidth + 2.dp, end = EndInset)
          .fillMaxWidth()
          .height(1.5.dp)
          .background(color)
          .align(Alignment.CenterStart)
    )
  }
}

/** Absolute placement helpers; the grid positions everything by time, not by flow. */
private fun Modifier.offsetXY(x: Dp, y: Dp) = this.offset(x = x, y = y)

private fun Modifier.offsetY(y: Dp) = this.offset(y = y)

private fun hourLabel(hour: Int, is24Hour: Boolean): String =
  when {
    is24Hour -> "%02d:00".format(hour)
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
  }

/** Where the "+" in the header should drop a new event. */
private fun defaultSlot(dayStart: Long, nowMs: Long): Long {
  val minute = TimelineLayout.nowMinute(nowMs, dayStart) ?: (9 * 60)
  val snapped = TimelineLayout.snapMinute(minute, 15, 60)
  return ScheduleAnalysis.withTimeOfDay(dayStart, snapped / 60, snapped % 60)
}
