package com.example.ui.screens

import com.example.ui.theme.Radius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.example.ui.components.HourLabelSamples
import com.example.ui.components.PropertyChip
import com.example.ui.components.RowIconButton
import com.example.ui.components.gutterWidthFor
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.EventOwnership
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Designed width of the hour-label column; grows with the user's text size. */
private val GutterWidth = 52.dp

/** Right margin shared by the hour rules, the blocks and the now-line. */
private val EndInset = 12.dp

private sealed interface TimelineConflictPreview {
  data object Loading : TimelineConflictPreview

  data class Loaded(val events: List<BriefingEvent>) : TimelineConflictPreview

  data object Unavailable : TimelineConflictPreview
}

/** Full-screen dialog wrapper retained for callers that open the timeline modally. */
@Composable
fun DayTimelineScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  onEditEvent: (BriefingEvent) -> Unit,
  onCreateAt: (Long) -> Unit,
  onDismiss: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
      DayTimelineContent(
        viewModel = viewModel,
        formatter = formatter,
        onEditEvent = onEditEvent,
        onCreateAt = onCreateAt,
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        onClose = onDismiss,
      )
    }
  }
}

/**
 * The reusable day time-grid body.
 *
 * A list tells you what is on; a grid tells you where the gaps are — which is the
 * question people actually open a schedule to answer. Blocks are positioned and
 * sized by real time, overlaps sit side by side, and a line marks now.
 *
 * Interactions follow the calendar conventions people already know: tap a block
 * to edit, tap empty space to create there, long-press then drag to move. Set
 * [showHeader] to false when the parent supplies its own date controls. A close
 * action is shown only when [onClose] is provided.
 */
@Composable
fun DayTimelineContent(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  onEditEvent: (BriefingEvent) -> Unit,
  onCreateAt: (Long) -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  showHeader: Boolean = true,
  onClose: (() -> Unit)? = null,
) {
  val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()
  val events by viewModel.selectedDayEvents.collectAsStateWithLifecycle()
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current
  var pendingMoveEventId by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingMoveStart by rememberSaveable { mutableStateOf<Long?>(null) }
  var pendingMoveEnd by rememberSaveable { mutableStateOf<Long?>(null) }
  var pendingExpectedRevision by rememberSaveable { mutableStateOf<String?>(null) }
  var dateMoveEventId by rememberSaveable { mutableStateOf<String?>(null) }

  fun requestMovePreview(event: BriefingEvent, startAt: Long, endAt: Long) {
    pendingMoveEventId = event.id
    pendingMoveStart = startAt
    pendingMoveEnd = endAt
    pendingExpectedRevision = timelineEventRevision(event)
  }

  fun dismissMovePreview() {
    pendingMoveEventId = null
    pendingMoveStart = null
    pendingMoveEnd = null
    pendingExpectedRevision = null
  }

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
  // Every measurement on this screen aligns to the hour column, so it is resolved
  // once here rather than each place reaching for the raw design constant.
  val gutterWidth = gutterWidthFor(GutterWidth, d.label, HourLabelSamples, FontWeight.Normal)

  // Open where the day actually is: on now, or on the first event, or the morning.
  LaunchedEffect(selectedDay) {
    val focusMinute = nowMinute ?: slots.minOfOrNull { it.startMinute } ?: (8 * 60)
    val target = (focusMinute / 60f) * hourPx - hourPx
    scroll.scrollTo(target.roundToInt().coerceAtLeast(0))
  }

  Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
    if (showHeader) {
      // ---- header ----
      Row(
        modifier =
          Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.sm),
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
        RowIconButton(
          Icons.Default.Add,
          "New event",
          { onCreateAt(defaultSlot(selectedDay, nowMs)) },
        )
        if (onClose != null) {
          RowIconButton(Icons.Default.Close, "Close timeline", onClose)
        }
      }
    }

    if (allDay.isNotEmpty()) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = gutterWidth, end = Space.lg, top = Space.xs, bottom = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
      ) {
        allDay.forEach { event ->
          Box(
            modifier =
              Modifier.heightIn(min = MinimumTouchTarget)
                .clip(RoundedCornerShape(Radius.control))
                .clickable(
                  onClickLabel = "Edit all-day event",
                  role = Role.Button,
                  onClick = { onEditEvent(event) },
                )
                .semantics { contentDescription = "${event.title}, all-day event" }
                .padding(horizontal = Space.xs),
            contentAlignment = Alignment.Center,
          ) {
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
      BoxWithConstraints(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
        val laneAreaWidth = maxWidth - gutterWidth - EndInset
        val gridHeight = d.hourHeight * 24

        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(gridHeight)
              .pointerInput(selectedDay, hourPx, gutterWidth) {
                detectTapGestures { offset ->
                  val laneStart = gutterWidth.toPx()
                  val laneEnd = size.width.toFloat() - EndInset.toPx()
                  // The hour labels and trailing breathing room are navigation chrome,
                  // not empty appointment slots.
                  if (!isTimelineLaneTap(offset.x, laneStart, laneEnd)) {
                    return@detectTapGestures
                  }
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
                val gutter = gutterWidth.toPx()
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
                  .width(gutterWidth)
                  .offsetY(d.hourHeight * hour - 6.dp),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
          }

          slots.forEach { slot ->
            TimelineBlock(
              slot = slot,
              formatter = formatter,
              hourHeight = d.hourHeight,
              gutterWidth = gutterWidth,
              laneAreaWidth = laneAreaWidth,
              onClick = { onEditEvent(slot.event) },
              onMoved = { minutes ->
                val startAt =
                  ScheduleAnalysis.withTimeOfDay(
                    selectedDay,
                    minutes / 60,
                    minutes % 60,
                  )
                val duration = (slot.event.endTime - slot.event.startTime).coerceAtLeast(60_000L)
                val endAt = runCatching { Math.addExact(startAt, duration) }.getOrNull()
                if (endAt != null) requestMovePreview(slot.event, startAt, endAt)
              },
              onRequestDateMove = {
                dateMoveEventId = slot.event.id
              },
            )
          }

          if (nowMinute != null) {
            NowLine(
              minute = nowMinute,
              hourHeight = d.hourHeight,
              gutterWidth = gutterWidth,
              color = status.deadline,
            )
          }
        }
      }
    }
  }

  val pendingEvent = events.firstOrNull { it.id == pendingMoveEventId }
  val proposedStart = pendingMoveStart
  val proposedEnd = pendingMoveEnd
  if (pendingEvent != null && proposedStart != null && proposedEnd != null) {
    val stale = timelineEventRevision(pendingEvent) != pendingExpectedRevision
    val conflictPreview by
      produceState<TimelineConflictPreview>(
        initialValue = TimelineConflictPreview.Loading,
        key1 = pendingEvent.id,
        key2 = proposedStart,
        key3 = proposedEnd,
      ) {
        value =
          try {
            TimelineConflictPreview.Loaded(
              viewModel.timelineMoveConflicts(
                movingEventId = pendingEvent.id,
                proposedStartMs = proposedStart,
                proposedEndMs = proposedEnd,
              )
            )
          } catch (error: CancellationException) {
            throw error
          } catch (_: Exception) {
            TimelineConflictPreview.Unavailable
          }
      }
    AlertDialog(
      onDismissRequest = ::dismissMovePreview,
      title = { Text("Move ${pendingEvent.title}?") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
          Text(
            "Current · ${formatter.fullDay(pendingEvent.startTime)} · " +
              formatter.range(pendingEvent.startTime, pendingEvent.endTime)
          )
          Text(
            "Proposed · ${formatter.fullDay(proposedStart)} · " +
              formatter.range(proposedStart, proposedEnd)
          )
          val conflicts = (conflictPreview as? TimelineConflictPreview.Loaded)?.events
          Text(
            when (conflictPreview) {
              TimelineConflictPreview.Loading -> "Conflicts · checking…"
              TimelineConflictPreview.Unavailable ->
                "Conflicts · unavailable; review the target date before applying"
              is TimelineConflictPreview.Loaded ->
                if (conflicts.isNullOrEmpty()) {
                  "Conflicts · none"
                } else {
                  val named = conflicts.take(3).joinToString { it.title }
                  val remainder = conflicts.size - 3
                  "Conflicts · ${conflicts.size}: $named" +
                  if (remainder > 0) ", and $remainder more" else ""
                }
            },
            color =
              when {
                conflictPreview == TimelineConflictPreview.Unavailable ->
                  MaterialTheme.colorScheme.error
                conflicts.isNullOrEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
              },
          )
          Text(
            if (stale) {
              "This event changed after the preview. Close this dialog and preview it again."
            } else {
              "Only this Daily Brief event moves. Its duration is preserved and Undo restores " +
                "the exact previous event."
            },
            color =
              if (stale) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.moveEventToRange(pendingEvent, proposedStart, proposedEnd)
            dismissMovePreview()
          },
          enabled = !stale && conflictPreview is TimelineConflictPreview.Loaded,
          modifier = Modifier.testTag("timeline_move_apply"),
        ) {
          Text("Apply move")
        }
      },
      dismissButton = {
        TextButton(
          onClick = ::dismissMovePreview,
          modifier = Modifier.testTag("timeline_move_cancel"),
        ) {
          Text("Cancel")
        }
      },
    )
  }

  val dateMoveEvent = events.firstOrNull { it.id == dateMoveEventId }
  if (dateMoveEvent != null) {
    val picker =
      rememberDatePickerState(
        initialSelectedDateMillis =
          ScheduleAnalysis.utcMillisFromLocalDay(dateMoveEvent.startTime),
      )
    DatePickerDialog(
      onDismissRequest = { dateMoveEventId = null },
      confirmButton = {
        TextButton(
          onClick = {
            picker.selectedDateMillis?.let { selectedUtcDay ->
              val localDay = ScheduleAnalysis.localDayFromUtcMillis(selectedUtcDay)
              val (startAt, endAt) = ScheduleAnalysis.moveEventToDay(dateMoveEvent, localDay)
              requestMovePreview(dateMoveEvent, startAt, endAt)
            }
            dateMoveEventId = null
          },
          enabled = picker.selectedDateMillis != null,
          modifier = Modifier.testTag("timeline_move_date_apply"),
        ) {
          Text("Preview date")
        }
      },
      dismissButton = {
        TextButton(onClick = { dateMoveEventId = null }) { Text("Cancel") }
      },
    ) {
      DatePicker(state = picker)
    }
  }
}

@Composable
private fun TimelineBlock(
  slot: TimelineLayout.Slot,
  formatter: TimeFormatter,
  hourHeight: Dp,
  gutterWidth: Dp,
  laneAreaWidth: Dp,
  onClick: () -> Unit,
  onMoved: (Int) -> Unit,
  onRequestDateMove: () -> Unit,
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
  val gridHeight = hourHeight * 24
  val touchHeight = maxOf(heightDp, MinimumTouchTarget)
  val touchTop =
    (topDp - (touchHeight - heightDp) / 2f)
      .coerceIn(0.dp, (gridHeight - touchHeight).coerceAtLeast(0.dp))
  val visualTop = topDp - touchTop
  val priority =
    when {
      slot.event.isDeadline && slot.event.isUrgent -> "Deadline, urgent"
      slot.event.isDeadline -> "Deadline"
      slot.event.isUrgent -> "Urgent"
      else -> null
    }
  val ownership = EventOwnership.forSource(slot.event.source)
  val earlierAccessibilityTarget =
    if (ownership.movableOnTimeline) {
      timelineAccessibilityMoveTarget(
        startMinute = slot.startMinute,
        durationMinutes = duration,
        deltaMinutes = -TIMELINE_ACCESSIBILITY_STEP_MINUTES,
      )
    } else {
      null
    }
  val laterAccessibilityTarget =
    if (ownership.movableOnTimeline) {
      timelineAccessibilityMoveTarget(
        startMinute = slot.startMinute,
        durationMinutes = duration,
        deltaMinutes = TIMELINE_ACCESSIBILITY_STEP_MINUTES,
      )
    } else {
      null
    }
  val accessibilityMoveActions =
    listOfNotNull(
      earlierAccessibilityTarget?.let { target ->
        CustomAccessibilityAction(label = "Move 15 minutes earlier") {
          onMoved(target)
          true
        }
      },
      laterAccessibilityTarget?.let { target ->
        CustomAccessibilityAction(label = "Move 15 minutes later") {
          onMoved(target)
          true
        }
      },
      if (ownership.movableOnTimeline) {
        CustomAccessibilityAction(label = "Move to another date") {
          onRequestDateMove()
          true
        }
      } else {
        null
      },
    )
  val movementState =
    when (ownership) {
      EventOwnership.APP_OWNED ->
        "Daily Brief event; use move actions, Alt plus arrow keys, long press and drag, or open to edit time"
      EventOwnership.DEVICE_CALENDAR ->
        "Fixed ${slot.event.source} event in Timeline; open it to edit its time"
      EventOwnership.READ_ONLY_SOURCE ->
        "Fixed ${slot.event.source} event; reschedule it in its source app"
    }
  val blockState = listOfNotNull(priority, movementState).joinToString(", ")

  Box(
    modifier =
      Modifier.offsetXY(gutterWidth + laneWidth * slot.lane, touchTop)
        .width(laneWidth)
        .height(touchHeight)
        .clickable(onClickLabel = "Edit event", role = Role.Button, onClick = onClick)
        .semantics {
          contentDescription =
            "${slot.event.title}, ${formatter.range(slot.event.startTime, slot.event.endTime)}"
          stateDescription = blockState
          if (accessibilityMoveActions.isNotEmpty()) {
            customActions = accessibilityMoveActions
          }
        }
        .then(
          if (ownership.movableOnTimeline) {
            Modifier
              .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isAltPressed) {
                  false
                } else {
                  when (event.key) {
                    Key.DirectionUp ->
                      earlierAccessibilityTarget?.let {
                        onMoved(it)
                        true
                      } ?: false
                    Key.DirectionDown ->
                      laterAccessibilityTarget?.let {
                        onMoved(it)
                        true
                      } ?: false
                    else -> false
                  }
                }
              }
              .focusable()
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
          } else {
            Modifier
          }
        )
        .testTag("timeline_block"),
  ) {
    Box(
      modifier =
        Modifier.offset(y = visualTop)
          .fillMaxWidth()
          .height(heightDp)
          .padding(end = 3.dp, top = 1.dp, bottom = 1.dp)
          .clip(RoundedCornerShape(Radius.control))
          .background(tone.copy(alpha = if (dragging) 0.34f else 0.18f))
          .drawBehind {
            drawRoundRect(
              color = tone,
              size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
              cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
          }
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
            modifier = Modifier.weight(1f),
          )
          if (!ownership.movableOnTimeline) {
            Text(
              text = "FIXED",
              fontSize = d.label,
              fontWeight = FontWeight.SemiBold,
              color = tone,
              maxLines = 1,
            )
          }
        }
      } else {
        Column(modifier = Modifier.padding(start = 9.dp, end = 6.dp, top = 3.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = slot.event.title,
              fontSize = d.secondary,
              fontWeight = FontWeight.SemiBold,
              color = scheme.onSurface,
              maxLines = if (heightDp > 52.dp) 2 else 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            if (!ownership.movableOnTimeline) {
              Text(
                text = "FIXED",
                fontSize = d.label,
                fontWeight = FontWeight.SemiBold,
                color = tone,
                maxLines = 1,
              )
            }
          }
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
}

@Composable
private fun NowLine(minute: Int, hourHeight: Dp, gutterWidth: Dp, color: Color) {
  val y = hourHeight * (minute / 60f)
  Box(modifier = Modifier.offsetXY(0.dp, y - 4.dp).fillMaxWidth().height(8.dp)) {
    Box(
      modifier =
        Modifier.padding(start = gutterWidth - 4.dp)
          .size(8.dp)
          .background(color, CircleShape)
          .align(Alignment.CenterStart)
    )
    Box(
      modifier =
        Modifier.padding(start = gutterWidth + 2.dp, end = EndInset)
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

/** Only the appointment lane creates an event; its surrounding chrome is inert. */
internal fun isTimelineLaneTap(x: Float, laneStart: Float, laneEnd: Float): Boolean =
  x in laneStart..laneEnd

private const val TIMELINE_ACCESSIBILITY_STEP_MINUTES = 15

/**
 * Returns an exact one-step accessibility move when both the current and target positions fit.
 *
 * Dragging snaps to the quarter-hour grid. Accessibility stepping instead preserves an event's
 * existing minute offset (for example, 09:07 becomes 09:22) so the announced 15-minute action is
 * exact. Both paths still use [TimelineLayout.snapMinute] for day-bound validation and commit
 * through the same ownership-checked `onMoved` callback.
 */
internal fun timelineAccessibilityMoveTarget(
  startMinute: Int,
  durationMinutes: Int,
  deltaMinutes: Int,
): Int? {
  if (
    deltaMinutes != -TIMELINE_ACCESSIBILITY_STEP_MINUTES &&
      deltaMinutes != TIMELINE_ACCESSIBILITY_STEP_MINUTES
  ) {
    return null
  }

  val current = TimelineLayout.snapMinute(startMinute, step = 1, durationMinutes = durationMinutes)
  if (current != startMinute) return null

  val requested = startMinute + deltaMinutes
  val target = TimelineLayout.snapMinute(requested, step = 1, durationMinutes = durationMinutes)
  return target.takeIf { it == requested }
}

/** Saveable, unambiguous revision token for the exact event used to build a move preview. */
internal fun timelineEventRevision(event: BriefingEvent): String =
  buildString {
    appendRevisionField(event.id)
    appendRevisionField(event.title)
    appendRevisionField(event.startTime)
    appendRevisionField(event.endTime)
    appendRevisionField(event.source)
    appendRevisionField(event.description)
    appendRevisionField(event.isDeadline)
    appendRevisionField(event.isUrgent)
    appendRevisionField(event.isAllDay)
    appendRevisionField(event.location)
    appendRevisionField(event.kanbanStatus)
    appendRevisionField(event.kanbanBoard)
    appendRevisionField(event.userEdited)
  }

private fun StringBuilder.appendRevisionField(value: Any?) {
  val encoded = value?.toString()
  if (encoded == null) {
    append("-;")
  } else {
    append(encoded.length).append(':').append(encoded).append(';')
  }
}

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
