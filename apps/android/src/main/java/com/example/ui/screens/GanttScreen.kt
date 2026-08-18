package com.example.ui.screens

import androidx.compose.ui.draw.clip
import com.example.core.GanttOverview
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.gestures.detectTapGestures
import com.example.core.GanttZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import com.example.ui.theme.Radius
import androidx.compose.ui.geometry.Offset
import com.example.data.model.PlanDependency
import com.example.core.GanttSlackBand
import com.example.core.GanttConnectorPlan
import com.example.core.GanttConnector
import com.example.core.GanttRowGeometry
import com.example.core.GanttDependencyPaths
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.GanttLayout
import com.example.core.GanttBlockDraft
import com.example.core.GanttBlockEditPolicy
import com.example.core.GanttDirectManipulationPolicy
import com.example.core.GanttDirectManipulationTargets
import com.example.core.GanttDragTarget
import com.example.core.GanttWorkingBands
import com.example.core.PlanGanttLayout
import com.example.core.PlanBlockPreview
import com.example.core.PlanBlockPreviewResult
import com.example.core.ScheduleAnalysis
import com.example.core.TimeFormatter
import com.example.core.WorkingInterval
import com.example.core.WorkingCalendarSpec
import com.example.data.model.BriefingEvent
import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority
import com.example.data.repository.PlanItemScheduleResolver
import com.example.data.repository.PlanItemScheduleResolution
import com.example.ui.components.AppTimeDialog
import com.example.ui.components.PlanDependencyManager
import com.example.ui.components.RowIconButton
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space
import com.example.ui.theme.rootTitleSize
import com.example.ui.theme.isShortWindow
import com.example.ui.theme.gutter
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.delay

private sealed interface GanttDisplayRow {
  data class Group(val label: String) : GanttDisplayRow

  data class PlanTask(val layout: PlanGanttLayout.Row, val outline: OutlineRow) : GanttDisplayRow

  data class FixedCommitment(val item: GanttLayout.Item) : GanttDisplayRow
}

private data class NonWorkingBand(val startPosition: Double, val endPosition: Double)

private enum class BlockTimeField {
  START,
  END,
}

/**
 * A shared time spine for app-owned Plan blocks and read-only calendar commitments. Solid bars can
 * be planned here; outlined bars remain visibly fixed and always open the event editor.
 */

/**
 * Restores an in-progress Gantt edit across process death. Replaces the java.io.Serializable
 * seam GanttBlockDraft used to carry, so the engine's common half stays platform-free.
 */
private fun GanttBlockDraft.toBundle(): Bundle =
  Bundle().apply {
    putString("item_id", itemId)
    putString("block_id", blockId)
    putLong("start", startAt)
    putLong("end", endAt)
    putBoolean("locked", locked)
  }

private fun Bundle.toGanttBlockDraft(): GanttBlockDraft =
  GanttBlockDraft(
    itemId = getString("item_id").orEmpty(),
    blockId = getString("block_id"),
    startAt = getLong("start"),
    endAt = getLong("end"),
    locked = getBoolean("locked"),
  )

private val GanttBlockDraftSaver =
  Saver<GanttBlockDraft, Bundle>(
    save = { it.toBundle() },
    restore = { it.toGanttBlockDraft() },
  )

private val NullableGanttBlockDraftSaver =
  Saver<GanttBlockDraft?, Bundle>(
    save = { draft ->
      if (draft == null) Bundle().apply { putBoolean("present", false) }
      else draft.toBundle().apply { putBoolean("present", true) }
    },
    restore = { bundle ->
      if (bundle.getBoolean("present")) bundle.toGanttBlockDraft() else null
    },
  )

@Composable
fun GanttScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onEditTask: (PlanItem) -> Unit,
  modifier: Modifier = Modifier,
  /** True when this is its own page rather than a tab inside the Plan. */
  focused: Boolean = false,
  onExitFocus: (() -> Unit)? = null,
  onEnterFocus: (() -> Unit)? = null,
) {
  val activeBoard by viewModel.activePlanBoard.collectAsStateWithLifecycle()
  val columns by viewModel.planColumns.collectAsStateWithLifecycle()
  val taskRelations by viewModel.planGanttItems.collectAsStateWithLifecycle()
  val commitments by viewModel.planCommitments.collectAsStateWithLifecycle()
  val selectedDay by viewModel.selectedDay.collectAsStateWithLifecycle()
  val workingCalendar by viewModel.workingCalendar.collectAsStateWithLifecycle()
  val activeWorkingCalendarsState by
    viewModel.activeWorkingCalendarsState.collectAsStateWithLifecycle()
  val itemScheduleAssignmentsState by
    viewModel.planItemScheduleAssignmentsState.collectAsStateWithLifecycle()
  val dependencies by viewModel.planDependencies.collectAsStateWithLifecycle()
  val criticalPath by viewModel.criticalPath.collectAsStateWithLifecycle()
  val gutter = LocalWindowWidth.current.gutter
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme

  val rangeDays by viewModel.ganttRangeDays.collectAsStateWithLifecycle()
  var scheduleDraft by rememberSaveable(stateSaver = NullableGanttBlockDraftSaver) { mutableStateOf<GanttBlockDraft?>(null) }
  var moveDraft by rememberSaveable(stateSaver = NullableGanttBlockDraftSaver) { mutableStateOf<GanttBlockDraft?>(null) }
  var moveSaving by rememberSaveable { mutableStateOf(false) }
  var dragOrigin by remember { mutableStateOf<GanttBlockDraft?>(null) }
  var dragPixels by remember { mutableFloatStateOf(0f) }
  var showDependencies by rememberSaveable { mutableStateOf(false) }
  var showLegend by rememberSaveable { mutableStateOf(false) }
  var mapMenu by remember { mutableStateOf(false) }
  val range =
    remember(selectedDay, rangeDays) {
      GanttLayout.VisibleRange(
        startInclusiveMs = ScheduleAnalysis.startOfDay(selectedDay),
        endExclusiveMs = ScheduleAnalysis.startOfDayOffset(selectedDay, rangeDays),
      )
    }
  val planItems = remember(taskRelations) { taskRelations.map { it.item } }
  val planBlocks = remember(taskRelations) { taskRelations.flatMap { it.blocks } }
  val displayedPlanBlocks =
    remember(planBlocks, moveDraft) {
      val preview = moveDraft
      if (preview?.blockId == null) {
        planBlocks
      } else {
        planBlocks.map { block ->
          if (block.id == preview.blockId) {
            block.copy(startAt = preview.startAt, endAt = preview.endAt)
          } else {
            block
          }
        }
      }
    }
  val planLayout =
    remember(planItems, displayedPlanBlocks, range) {
      PlanGanttLayout.layout(planItems, displayedPlanBlocks, range)
    }
  val fixedLayout = remember(commitments, range) { GanttLayout.layout(commitments, range) }
  val ticks =
    remember(range, rangeDays) {
      GanttLayout.dayTicks(
        range = range,
        minimumStepDays =
          when (rangeDays) {
            7 -> 1
            30 -> 2
            else -> 7
          },
        maximumTicks = 18,
      )
    }
  val nowMs by
    produceState(initialValue = System.currentTimeMillis()) {
      while (true) {
        value = System.currentTimeMillis()
        delay(NowRefreshIntervalMs)
      }
    }
  val today = remember(range, nowMs) { GanttLayout.today(range, nowMs = nowMs) }
  val rows =
    remember(activeBoard, columns, planItems, planLayout, fixedLayout) {
      buildList {
        if (fixedLayout.items.isNotEmpty()) {
          add(GanttDisplayRow.Group("Fixed calendar commitments"))
          fixedLayout.items.forEach { add(GanttDisplayRow.FixedCommitment(it)) }
        }
        val board = activeBoard
        if (board != null) {
          val geometryById = planLayout.rows.associateBy { it.item.id }
          PlanBoardProjector.project(board.id, columns, planItems).forEach { lane ->
            val visibleRows =
              lane.rows.mapNotNull { outline ->
                geometryById[outline.item.id]?.let { GanttDisplayRow.PlanTask(it, outline) }
              }
            if (visibleRows.isNotEmpty()) {
              add(GanttDisplayRow.Group(lane.title))
              addAll(visibleRows)
            }
          }
        }
      }
    }
  val criticalTaskIds =
    remember(criticalPath) {
      criticalPath.result
        ?.takeIf { it.isComplete }
        ?.taskTimings
        .orEmpty()
        .filter { it.isCritical == true }
        .mapTo(mutableSetOf()) { it.itemId }
    }
  val defaultNonWorkingBands =
    remember(range, workingCalendar?.spec) {
      workingCalendar?.spec?.let { nonWorkingBands(range, it) }.orEmpty()
    }
  val scheduleBandsById =
    remember(range, activeWorkingCalendarsState.calendars) {
      activeWorkingCalendarsState.calendars.associate { calendar ->
        calendar.schedule.id to nonWorkingBands(range, calendar.spec)
      }
    }
  val taskNonWorkingBands =
    remember(
      planItems,
      activeBoard?.id,
      activeWorkingCalendarsState,
      itemScheduleAssignmentsState,
      scheduleBandsById,
    ) {
      val boardId = activeBoard?.id
      if (!activeWorkingCalendarsState.loaded ||
        !itemScheduleAssignmentsState.loaded ||
        itemScheduleAssignmentsState.boardId != boardId
      ) {
        emptyMap()
      } else {
        planItems.associate { item ->
          val resolution =
            PlanItemScheduleResolver.resolve(
              itemId = item.id,
              mappings = itemScheduleAssignmentsState.assignments,
              calendars = activeWorkingCalendarsState.calendars,
            )
          item.id to
            resolution.calendar?.schedule?.id?.let(scheduleBandsById::get).orEmpty()
        }
      }
    }
  val dependencyErrors =
    remember(criticalPath.result) {
      criticalPath.result
        ?.errors
        .orEmpty()
        .filter { issue -> issue.dependencyId != null || issue.code.name.contains("DEPENDENCY") }
        .map { it.explanation }
    }
  val moveBlock = moveDraft?.blockId?.let { id -> planBlocks.firstOrNull { it.id == id } }
  val moveItem = moveBlock?.let { block -> planItems.firstOrNull { it.id == block.planItemId } }
  val moveScheduleDataLoaded =
    moveItem != null &&
      activeWorkingCalendarsState.loaded &&
      itemScheduleAssignmentsState.loaded &&
      itemScheduleAssignmentsState.boardId == moveItem.boardId
  val moveScheduleResolution =
    remember(
      moveItem?.id,
      moveScheduleDataLoaded,
      itemScheduleAssignmentsState,
      activeWorkingCalendarsState,
    ) {
      when {
        moveItem == null -> null
        !moveScheduleDataLoaded ->
          PlanItemScheduleResolution(
            calendar = null,
            inheritsDefault = false,
            problem = "Working schedules are still loading. Try direct movement again in a moment.",
          )
        else ->
          PlanItemScheduleResolver.resolve(
            itemId = moveItem.id,
            mappings = itemScheduleAssignmentsState.assignments,
            calendars = activeWorkingCalendarsState.calendars,
          )
      }
    }
  val movePreview =
    remember(
      moveItem,
      moveDraft,
      planItems,
      planBlocks,
      commitments,
      dependencies,
      moveScheduleResolution,
    ) {
      val item = moveItem
      val draft = moveDraft
      val schedule = moveScheduleResolution?.calendar?.spec
      if (item == null || draft?.blockId == null || schedule == null) {
        null
      } else {
        runCatching {
            PlanBlockPreview.evaluate(
              item = item,
              blockId = draft.blockId,
              proposedStart = draft.startAt,
              proposedEnd = draft.endAt,
              items = planItems,
              blocks = planBlocks,
              fixedCommitments =
                commitments.map { event -> WorkingInterval(event.startTime, event.endTime) },
              dependencies = dependencies,
              workSchedule = schedule,
            )
          }
          .getOrNull()
      }
    }
  var connectorPlan by remember { mutableStateOf(GanttConnectorPlan(emptyList(), 0, null)) }
  val slackMinutesByItemId =
    remember(criticalPath.result) {
      criticalPath.result
        ?.taskTimings
        .orEmpty()
        .mapNotNull { timing -> timing.totalSlackMinutes?.let { timing.itemId to it } }
        .toMap()
    }

  val moving = moveDraft != null

  // Back is the gesture people reach for to leave a mode. Without this it leaves the Plan root
  // instead and takes the unsaved preview with it, which is the one thing move mode promises
  // never to happen. Cancelling here writes nothing, exactly like the Cancel button.
  BackHandler(enabled = moving) {
    moveDraft = null
    moveSaving = false
    dragOrigin = null
    dragPixels = 0f
  }

  val moveHasChanges =
    moveBlock != null &&
      moveDraft != null &&
      (moveDraft?.startAt != moveBlock.startAt || moveDraft?.endAt != moveBlock.endAt)

  LaunchedEffect(moveDraft?.blockId, moveBlock?.id, moveItem?.id, moveBlock?.locked, moveItem?.locked) {
    if (
      moveDraft != null &&
        (moveBlock == null || moveItem == null || moveBlock.locked || moveItem.locked)
    ) {
      moveDraft = null
      moveSaving = false
      dragOrigin = null
      dragPixels = 0f
    }
  }

  // Back leaves the page rather than the app, the same rule every transient mode here follows.
  BackHandler(enabled = focused && !moving) { onExitFocus?.invoke() }

  Column(
    modifier =
      modifier
        .fillMaxSize()
        // As a page it owns the whole window, so it takes the status-bar inset itself. As a tab the
        // Plan has already done that, and taking it twice would leave a band of nothing.
        .padding(
          top = if (focused) contentPadding.calculateTopPadding() else 0.dp,
          bottom = contentPadding.calculateBottomPadding(),
        )
        .testTag("screen_gantt")
  ) {
    if (focused) {
      // Its own page, so it carries its own way back and its own title. Everything else the tab
      // version stacks above the canvas — the board line, the section heading, the strip — is the
      // Plan's furniture, and a page does not need to repeat it.
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RowIconButton(
          icon = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back to Plan",
          onClick = { onExitFocus?.invoke() },
          modifier = Modifier.testTag("gantt_exit_focus"),
        )
        Column(modifier = Modifier.weight(1f).padding(start = Space.xs)) {
          Text(
            text = "Schedule map",
            fontSize = rootTitleSize(),
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          activeBoard?.name?.let { board ->
            Text(
              text = board,
              fontSize = d.secondary,
              color = colors.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Box {
          RowIconButton(
            icon = Icons.Default.MoreVert,
            contentDescription = "Schedule map options",
            onClick = { mapMenu = true },
            modifier = Modifier.testTag("gantt_map_menu"),
          )
          GanttMapMenu(
            expanded = mapMenu,
            dependencyCount = dependencies.size,
            boardReady = activeBoard != null,
            legendShown = showLegend,
            onDismiss = { mapMenu = false },
            onDependencies = { showDependencies = true },
            onToggleLegend = { showLegend = !showLegend },
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Space.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        // The view switcher directly above already says "Gantt", so on a short screen this title is
        // the app describing itself twice while the canvas it names has one row left.
        if (!isShortWindow() && !focused) {
          Text(
            text = "Schedule map",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
          )
        }
        // The legend used to stand permanently above the canvas: five lines of prose that a person
        // reads once and then scrolls past forever, on the one screen that most needs its height.
        // It is a labelled disclosure now — still there, still exact, no longer rent-free.
        // A short screen spends its height on the canvas instead. The board being planned is named
        // in the Plan controls above and in the range line below, so this is the line to drop.
        if (!moving && !isShortWindow() && !focused) {
          Text(
            text = activeBoard?.name ?: "Plan",
            fontSize = d.secondary,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (!focused) {
        GanttRangeNav(rangeDays, selectedDay, viewModel)
      }
    }

    Row(
      modifier =
        Modifier.fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = gutter),
      horizontalArrangement = Arrangement.spacedBy(Space.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      listOf(7, 30, 90).forEach { days ->
        FilterChip(
          selected = rangeDays == days,
          onClick = { viewModel.setGanttRangeDays(days) },
          label = { Text("${days}d") },
          modifier = Modifier.testTag("gantt_range_$days"),
        )
      }
      // The zoom chips are navigation and stay on the canvas. Dependencies and the legend are
      // references — read once, then never again — so they moved behind the one menu this screen
      // already has rather than holding a place above the map forever.
      Spacer(modifier = Modifier.weight(1f))
      if (focused) {
        GanttRangeNav(rangeDays, selectedDay, viewModel)
      }
      if (!focused && onEnterFocus != null) {
        // The way into the full page, next to the zoom it belongs with. A chart you cannot see is
        // not a chart, and inside a tab this one gets a few rows on a phone.
        RowIconButton(
          icon = Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = "Open the schedule map full screen",
          onClick = onEnterFocus,
          modifier = Modifier.testTag("gantt_open_focus"),
        )
      }
      if (!focused) {
        Box {
          RowIconButton(
            icon = Icons.Default.MoreVert,
            contentDescription = "Schedule map options",
            onClick = { mapMenu = true },
            modifier = Modifier.testTag("gantt_map_menu"),
          )
          GanttMapMenu(
            expanded = mapMenu,
            dependencyCount = dependencies.size,
            boardReady = activeBoard != null,
            legendShown = showLegend,
            onDismiss = { mapMenu = false },
            onDependencies = { showDependencies = true },
            onToggleLegend = { showLegend = !showLegend },
          )
        }
      }
    }

    if (showLegend && !moving) {
      Text(
        text =
          "Solid bars are Plan blocks this app owns. Outlined bars are calendar commitments Plan " +
            "may not move. Shading marks non-working time inside each day — " +
            if (workingCalendar == null) {
              "it awaits the default working schedule, and each task's own rules are still checked per block."
            } else {
              "it uses the default “${workingCalendar?.schedule?.name}” for fixed rows and each task's resolved schedule for its own."
            },
        fontSize = d.secondary,
        color = colors.onSurfaceVariant,
        modifier =
          Modifier.padding(horizontal = gutter, vertical = Space.xs).testTag("gantt_legend"),
      )
    }

    // On a short screen this line is the difference between a canvas that shows a bar and one that
    // shows only its axis: the row beneath it needs 63 dp and the status takes about 30. The dates
    // it opens with are already written across the axis, so this is the line to spend.
    if (!moving && !isShortWindow() && !focused) {
      // One status line instead of two: the range being read, then what the critical path can
      // honestly say about it. Two standing lines for this cost the canvas more than they told.
      Text(
        text =
          "${formatter.mediumDay(range.startInclusiveMs)} – ${formatter.mediumDay(ScheduleAnalysis.startOfDayOffset(range.endExclusiveMs, -1))}" +
            " · " +
            (criticalPath.result?.takeIf { it.isComplete }?.let { result ->
              val duration = result.projectDurationMinutes ?: 0L
              "critical path ${criticalTaskIds.size} tasks, $duration min, all durations verified"
            } ?: "critical path unavailable"),
        fontSize = d.label,
        color = colors.onSurfaceVariant,
        // A status *line*. On a 360 dp phone this wrapped to two, and the canvas it describes had
        // about one row of height left to it.
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
          Modifier.padding(horizontal = gutter, vertical = Space.xs).testTag("gantt_status_line"),
      )
      // Drawn links are a subset by design. Saying so keeps the canvas from implying that the
      // dependencies it can draw are all the dependencies there are.
      connectorPlan.undrawnReason?.let { reason ->
        Text(
          text = "Dependency lines: $reason. The ledger lists every link.",
          fontSize = d.label,
          color = colors.onSurfaceVariant,
          modifier =
            Modifier.padding(horizontal = gutter, vertical = Space.xs)
              .testTag("gantt_link_disclosure"),
        )
      }
    }
    if (!moving) {
      val overview =
        remember(planBlocks, commitments, range) {
          val starts = planBlocks.map { it.startAt } + commitments.map { it.startTime }
          val ends = planBlocks.map { it.endAt } + commitments.map { it.endTime }
          GanttZoom.overview(
            planStartMs = starts.minOrNull(),
            planEndMs = ends.maxOrNull(),
            visibleStartMs = range.startInclusiveMs,
            visibleEndMs = range.endExclusiveMs,
          )
        }
      // A map of the map, which is worth its 48 dp only while the map itself has room. On a short
      // screen it was the difference between a canvas showing work and one showing its axis and a
      // sliver, so here the canvas wins and the strip stands down.
      overview?.takeIf { !isShortWindow() }?.let { strip ->
        GanttOverviewStrip(
          overview = strip,
          formatter = formatter,
          onJump = { fraction ->
            viewModel.selectDay(GanttZoom.dayForOverviewTap(strip, fraction, rangeDays))
          },
          onStep = { days ->
            viewModel.selectDay(ScheduleAnalysis.startOfDayOffset(selectedDay, days))
          },
          rangeDays = rangeDays,
          modifier = Modifier.padding(horizontal = gutter, vertical = Space.xs),
        )
      }
    }

    HorizontalDivider(color = colors.outlineVariant)

    if (rows.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize().padding(gutter), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text("No Plan work or fixed commitments here", color = colors.onSurface)
          Spacer(Modifier.height(Space.xs))
          Text(
            "Capture a task, choose another date, or widen the range.",
            color = colors.onSurfaceVariant,
            fontSize = d.secondary,
          )
        }
      }
    } else {
      // Deliberately no minimum height. A weighted child with a floor larger than the space left
      // does not push the chrome up — it overflows the column, and the canvas is placed below the
      // window where its bars measure zero by zero. The room comes from spending less above it.
      Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        GanttTable(
          focused = focused,
          rows = rows,
          ticks = ticks,
          today = today,
          range = range,
          rangeDays = rangeDays,
          formatter = formatter,
          onEditEvent = onEditEvent,
          onEditTask = onEditTask,
          onRevealAt = viewModel::selectDay,
          onSchedule = { item ->
            if (item.isMilestone) {
              onEditTask(item)
            } else {
              val startAt =
                item.startConstraint
                  ?: ScheduleAnalysis.withTimeOfDay(selectedDay, hour = 9, minute = 0)
              scheduleDraft =
                GanttBlockEditPolicy.replaceDurationMinutes(
                  GanttBlockDraft(
                    itemId = item.id,
                    startAt = startAt,
                    endAt = startAt + 60_000L,
                    locked = item.locked,
                  ),
                  item.effortMinutes ?: 60,
                )
            }
          },
          onEditBlock = { item, block ->
            moveDraft = null
            dragOrigin = null
            dragPixels = 0f
            scheduleDraft =
              GanttBlockDraft(
                itemId = item.id,
                blockId = block.id,
                startAt = block.startAt,
                endAt = block.endAt,
                locked = block.locked,
              )
          },
          onMoveBlock = { item, block ->
            val persisted = planBlocks.firstOrNull { it.id == block.id }
            if (
              persisted != null &&
                persisted.planItemId == item.id &&
                !persisted.locked &&
                !item.locked
            ) {
              scheduleDraft = null
              moveSaving = false
              dragOrigin = null
              dragPixels = 0f
              moveDraft =
                GanttBlockDraft(
                  itemId = item.id,
                  blockId = persisted.id,
                  startAt = persisted.startAt,
                  endAt = persisted.endAt,
                  locked = false,
                )
            }
          },
          selectedMoveBlockId = moveDraft?.blockId,
          onMoveDragStart = {
            dragOrigin = moveDraft
            dragPixels = 0f
          },
          onMoveDrag = { target, deltaPixels, canvasWidthPixels ->
            val origin = dragOrigin
            if (origin != null) {
              dragPixels += deltaPixels
              GanttDirectManipulationPolicy.preview(
                  origin = origin,
                  target = target,
                  deltaPixels = dragPixels,
                  canvasWidthPixels = canvasWidthPixels,
                  rangeStartInclusive = range.startInclusiveMs,
                  rangeEndExclusive = range.endExclusiveMs,
                )
                ?.let { moveDraft = it }
            }
          },
          onMoveDragCancel = {
            dragOrigin?.let { moveDraft = it }
            dragOrigin = null
            dragPixels = 0f
          },
          onMoveDragEnd = {
            dragOrigin = null
            dragPixels = 0f
          },
          onMoveAdjust = { target, minutes ->
            val current = moveDraft
            val adjusted =
              current?.let {
                GanttDirectManipulationPolicy.adjustByMinutes(
                  origin = current,
                  target = target,
                  deltaMinutes = minutes,
                  rangeStartInclusive = range.startInclusiveMs,
                  rangeEndExclusive = range.endExclusiveMs,
                )
              }
            if (adjusted != null && adjusted != current) {
              moveDraft = adjusted
              true
            } else {
              false
            }
          },
          criticalTaskIds = criticalTaskIds,
          dependencies = dependencies,
          slackMinutesByItemId = slackMinutesByItemId,
          rangeMinutes = (range.endExclusiveMs - range.startInclusiveMs) / 60_000L,
          onConnectorsPlanned = { connectorPlan = it },
          onZoom = { days, focus ->
            viewModel.selectDay(
              GanttZoom.focusPreservingStart(range.startInclusiveMs, rangeDays, focus, days)
            )
            viewModel.setGanttRangeDays(days)
          },
          defaultNonWorkingBands = defaultNonWorkingBands,
          taskNonWorkingBands = taskNonWorkingBands,
          modifier = Modifier.fillMaxSize(),
        )
      }
      // The panel takes its own space rather than floating over the canvas: an overlay would
      // cover the very bar being moved, and a covered bar cannot be dragged or even seen.
      moveDraft?.let { activeMoveDraft ->
        val activeMoveBlock = moveBlock
        if (activeMoveBlock != null) {
          val activeMoveItem = moveItem
          if (activeMoveItem != null) {
            GanttMoveModePanel(
              item = activeMoveItem,
              draft = activeMoveDraft,
              formatter = formatter,
              preview = movePreview,
              scheduleProblem = moveScheduleResolution?.problem,
              saving = moveSaving,
              hasChanges = moveHasChanges,
              onAdjust = { target, minutes ->
                val adjusted =
                  GanttDirectManipulationPolicy.adjustByMinutes(
                    origin = activeMoveDraft,
                    target = target,
                    deltaMinutes = minutes,
                    rangeStartInclusive = range.startInclusiveMs,
                    rangeEndExclusive = range.endExclusiveMs,
                  )
                if (adjusted != null && adjusted != activeMoveDraft) {
                  moveDraft = adjusted
                  true
                } else {
                  false
                }
              },
              onCancel = {
                moveDraft = null
                dragOrigin = null
                dragPixels = 0f
              },
              onApply = {
                if (!moveSaving && moveHasChanges && movePreview?.canCommit == true) {
                  moveSaving = true
                  viewModel.updateGanttPlanBlock(
                    item = activeMoveItem,
                    block = activeMoveBlock,
                    startAt = activeMoveDraft.startAt,
                    endAt = activeMoveDraft.endAt,
                    locked = activeMoveBlock.locked,
                  ) { success ->
                    moveSaving = false
                    if (success) {
                      moveDraft = null
                      dragOrigin = null
                      dragPixels = 0f
                    }
                  }
                }
              },
              modifier =
                Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Space.sm),
            )
          }
        }
      }
    }
  }

  val dependencyBoard = activeBoard
  if (showDependencies && dependencyBoard != null) {
    AlertDialog(
      onDismissRequest = { showDependencies = false },
      title = { Text("Plan dependencies") },
      text = {
        PlanDependencyManager(
          boardId = dependencyBoard.id,
          items = planItems,
          dependencies = dependencies,
          errors = dependencyErrors,
          onAdd = { draft ->
            viewModel.addPlanDependency(
              predecessorId = draft.predecessorId,
              successorId = draft.successorId,
              type = draft.type,
              lagMinutes = draft.lagMinutes,
            )
          },
          onUpdate = { dependency, draft ->
            viewModel.updatePlanDependency(
              dependency = dependency,
              type = draft.type,
              lagMinutes = draft.lagMinutes,
            )
          },
          onDelete = { dependency -> viewModel.deletePlanDependency(dependency) },
          modifier =
            Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        )
      },
      confirmButton = {
        TextButton(
          onClick = { showDependencies = false },
          modifier = Modifier.heightIn(min = MinimumTouchTarget),
        ) {
          Text("Close")
        }
      },
      modifier = Modifier.testTag("gantt_dependency_dialog"),
    )
  }

  val activeScheduleDraft = scheduleDraft
  val scheduleItem =
    activeScheduleDraft?.let { draft -> planItems.firstOrNull { it.id == draft.itemId } }
  val scheduleBlock =
    activeScheduleDraft?.blockId?.let { id -> planBlocks.firstOrNull { it.id == id } }
  if (
    activeScheduleDraft != null &&
      scheduleItem != null &&
      (activeScheduleDraft.blockId == null || scheduleBlock != null)
  ) {
    val scheduleDataLoaded =
      activeWorkingCalendarsState.loaded &&
        itemScheduleAssignmentsState.loaded &&
        itemScheduleAssignmentsState.boardId == scheduleItem.boardId
    val scheduleResolution =
      remember(
        scheduleItem.id,
        scheduleDataLoaded,
        itemScheduleAssignmentsState,
        activeWorkingCalendarsState,
      ) {
        if (!scheduleDataLoaded) {
          PlanItemScheduleResolution(
            calendar = null,
            inheritsDefault = false,
            problem = "Working schedules are still loading. Try this block again in a moment.",
          )
        } else {
          PlanItemScheduleResolver.resolve(
            itemId = scheduleItem.id,
            mappings = itemScheduleAssignmentsState.assignments,
            calendars = activeWorkingCalendarsState.calendars,
          )
        }
      }
    ScheduleBlockDialog(
      item = scheduleItem,
      initial = activeScheduleDraft,
      existingBlock = scheduleBlock,
      formatter = formatter,
      allItems = planItems,
      allBlocks = planBlocks,
      fixedCommitments =
        commitments.map { event -> WorkingInterval(event.startTime, event.endTime) },
      dependencies = dependencies,
      workSchedule = scheduleResolution.calendar?.spec,
      workScheduleLabel = scheduleResolution.calendar?.schedule?.name,
      workScheduleProblem = scheduleResolution.problem,
      onDismiss = { scheduleDraft = null },
      onMoveOnMap = { block ->
        scheduleDraft = null
        moveSaving = false
        dragOrigin = null
        dragPixels = 0f
        moveDraft =
          GanttBlockDraft(
            itemId = scheduleItem.id,
            blockId = block.id,
            startAt = block.startAt,
            endAt = block.endAt,
            locked = false,
          )
      },
      onConfirm = { startAt, endAt, locked, onComplete ->
        if (scheduleBlock == null) {
          viewModel.addPlanBlock(scheduleItem, startAt, endAt, locked, onComplete)
        } else {
          viewModel.updateGanttPlanBlock(
            item = scheduleItem,
            block = scheduleBlock,
            startAt = startAt,
            endAt = endAt,
            locked = locked,
            onComplete = onComplete,
          )
        }
      },
      onDelete = { onComplete ->
        val block = scheduleBlock
        if (block == null) onComplete(false)
        else viewModel.deleteGanttPlanBlock(scheduleItem, block, onComplete)
      },
    )
  }
}

@Composable
private fun GanttTable(
  rows: List<GanttDisplayRow>,
  ticks: List<GanttLayout.Tick>,
  today: GanttLayout.Today?,
  range: GanttLayout.VisibleRange,
  rangeDays: Int,
  formatter: TimeFormatter,
  onEditEvent: (BriefingEvent) -> Unit,
  onEditTask: (PlanItem) -> Unit,
  onRevealAt: (Long) -> Unit,
  onSchedule: (PlanItem) -> Unit,
  onEditBlock: (PlanItem, PlanBlock) -> Unit,
  onMoveBlock: (PlanItem, PlanBlock) -> Unit,
  selectedMoveBlockId: String?,
  onMoveDragStart: () -> Unit,
  onMoveDrag: (GanttDragTarget, Float, Float) -> Unit,
  onMoveDragCancel: () -> Unit,
  onMoveDragEnd: () -> Unit,
  onMoveAdjust: (GanttDragTarget, Int) -> Boolean,
  criticalTaskIds: Set<String>,
  dependencies: List<PlanDependency>,
  slackMinutesByItemId: Map<String, Long>,
  rangeMinutes: Long,
  onConnectorsPlanned: (GanttConnectorPlan) -> Unit,
  onZoom: (Int, Float) -> Unit,
  defaultNonWorkingBands: List<NonWorkingBand>,
  taskNonWorkingBands: Map<String, List<NonWorkingBand>>,
  modifier: Modifier = Modifier,
  focused: Boolean,
) {
  val horizontalScroll = rememberScrollState()
  val verticalScroll = rememberScrollState()
  val density = LocalDensity.current
  var timelineViewportWidthPixels by remember { mutableIntStateOf(0) }
  val window = LocalWindowWidth.current
  // A label that breaks "Migrate" into "Migr / ate" is not a label. On the full page the column can
  // afford the width that stops that; inside a tab the canvas cannot spare it.
  val labelWidth = if (!window.isCompact) 260.dp else if (focused) 190.dp else 158.dp
  val dayWidth =
    when (rangeDays) {
      7 -> 68.dp
      30 -> 34.dp
      else -> 18.dp
    }
  val canvasWidth = dayWidth * rangeDays
  var pinchScale by remember(rangeDays) { mutableFloatStateOf(1f) }
  val canvasWidthPixels = with(density) { canvasWidth.toPx() }
  val autoScrollEdgePixels = with(density) { DirectManipulationHandleSize.toPx() }
  val autoScrollMaximumStepPixels = with(density) { DirectManipulationAutoScrollStep.toPx() }

  LaunchedEffect(range.startInclusiveMs, rangeDays) { horizontalScroll.scrollTo(0) }

  // The date axis is pinned: it lives outside the vertical scroll so the rows move under it while
  // the axis keeps the day the user is reading. It shares the canvas's horizontal ScrollState, so
  // one gesture scrolls both in lockstep.
  Column(modifier = modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth()) {
      GanttLabelsHeader(modifier = Modifier.width(labelWidth))
      Box(
        modifier =
          Modifier.weight(1f)
            .horizontalScroll(horizontalScroll, enabled = selectedMoveBlockId == null)
      ) {
        GanttAxisRow(
          ticks = ticks,
          canvasWidth = canvasWidth,
          defaultNonWorkingBands = defaultNonWorkingBands,
          formatter = formatter,
        )
      }
    }
    Box(modifier = Modifier.fillMaxWidth().verticalScroll(verticalScroll)) {
      Row(modifier = Modifier.fillMaxWidth()) {
        GanttLabels(
          rows = rows,
          formatter = formatter,
          onEditEvent = onEditEvent,
          onEditTask = onEditTask,
          onSchedule = onSchedule,
          onRevealAt = onRevealAt,
          modifier = Modifier.width(labelWidth),
        )
        Box(
          modifier =
            Modifier.weight(1f)
              .onSizeChanged { timelineViewportWidthPixels = it.width }
              .pointerInput(rangeDays, selectedMoveBlockId) {
                // Pinch is an accelerator for the range buttons, so it lands on 7, 30 or 90 and the
                // buttons keep reporting the truth. Move mode owns the pointer instead.
                if (selectedMoveBlockId != null) return@pointerInput
                detectTransformGestures { centroid, _, zoom, _ ->
                  pinchScale *= zoom
                  val next = GanttZoom.rangeForScale(rangeDays, pinchScale)
                  if (next != rangeDays) {
                    val width = size.width.toFloat().takeIf { it > 0f } ?: return@detectTransformGestures
                    val focus = ((centroid.x + horizontalScroll.value) / width).coerceIn(0f, 1f)
                    onZoom(next, focus)
                    pinchScale = 1f
                  }
                }
              }
              .horizontalScroll(horizontalScroll, enabled = selectedMoveBlockId == null)
        ) {
        GanttCanvas(
          focused = focused,
          rows = rows,
          ticks = ticks,
          today = today,
          canvasWidth = canvasWidth,
          formatter = formatter,
          onEditEvent = onEditEvent,
          onEditTask = onEditTask,
          onEditBlock = onEditBlock,
          onMoveBlock = onMoveBlock,
          selectedMoveBlockId = selectedMoveBlockId,
          onMoveDragStart = onMoveDragStart,
          onMoveDrag = { target, deltaPixels, pointerContentX ->
            val autoScrollRequest =
              if (
                timelineViewportWidthPixels <= 0 || horizontalScroll.maxValue == Int.MAX_VALUE
              ) {
                0f
              } else {
                GanttDirectManipulationPolicy.boundedAutoScrollDelta(
                  pointerViewportX = pointerContentX - horizontalScroll.value,
                  viewportWidthPixels = timelineViewportWidthPixels.toFloat(),
                  currentScrollPixels = horizontalScroll.value.toFloat(),
                  maximumScrollPixels = horizontalScroll.maxValue.toFloat(),
                  edgeWidthPixels = autoScrollEdgePixels,
                  maximumStepPixels = autoScrollMaximumStepPixels,
                )
              }
            val consumedScroll = horizontalScroll.dispatchRawDelta(autoScrollRequest)
            onMoveDrag(target, deltaPixels + consumedScroll, canvasWidthPixels)
          },
          onMoveDragCancel = onMoveDragCancel,
          onMoveDragEnd = onMoveDragEnd,
          onMoveAdjust = onMoveAdjust,
          criticalTaskIds = criticalTaskIds,
          dependencies = dependencies,
          slackMinutesByItemId = slackMinutesByItemId,
          rangeMinutes = rangeMinutes,
          onConnectorsPlanned = onConnectorsPlanned,
          defaultNonWorkingBands = defaultNonWorkingBands,
          taskNonWorkingBands = taskNonWorkingBands,
          modifier = Modifier.width(canvasWidth),
        )
      }
    }
  }
  }
}

@Composable
private fun GanttLabels(
  rows: List<GanttDisplayRow>,
  formatter: TimeFormatter,
  onEditEvent: (BriefingEvent) -> Unit,
  onEditTask: (PlanItem) -> Unit,
  onSchedule: (PlanItem) -> Unit,
  onRevealAt: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme
  Column(modifier = modifier.background(colors.surface)) {
    rows.forEach { row ->
      when (row) {
        is GanttDisplayRow.Group -> {
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .height(GroupHeight)
                .background(colors.surfaceContainerHigh)
                .padding(horizontal = Space.sm)
                .semantics { heading() },
            contentAlignment = Alignment.CenterStart,
          ) {
            Text(
              row.label.uppercase(),
              fontSize = d.label,
              fontWeight = FontWeight.SemiBold,
              color = colors.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        is GanttDisplayRow.FixedCommitment -> {
          val event = row.item.event
          Column(
            modifier =
              Modifier.fillMaxWidth()
                .height(FixedRowHeight)
                .clickable(role = Role.Button, onClickLabel = "Edit fixed commitment") {
                  onEditEvent(event)
                }
                .padding(horizontal = Space.sm, vertical = Space.xs),
            verticalArrangement = Arrangement.Center,
          ) {
            Text(
              event.title,
              color = colors.onSurface,
              fontSize = d.body,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              "FIXED · ${formatter.mediumDay(event.startTime)}",
              color = colors.onSurfaceVariant,
              fontSize = d.label,
              maxLines = 1,
            )
          }
        }
        is GanttDisplayRow.PlanTask -> {
          val item = row.layout.item
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .height(displayRowHeight(row))
                .semantics {
                  stateDescription = planTaskStateDescription(row, formatter)
                }
                .padding(start = Space.sm + (row.outline.depth * 10).dp, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(
              modifier =
                Modifier.weight(1f)
                  .clickable(role = Role.Button, onClickLabel = "Edit Plan task") {
                    onEditTask(item)
                  }
                  .padding(vertical = Space.xs),
            ) {
              Text(
                item.title,
                color = colors.onSurface,
                fontSize = d.body,
                fontWeight = if (row.outline.hasChildren) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Wrap between words or ellipsise; never split a word across two lines.
                softWrap = true,
                style = LocalTextStyle.current.copy(lineBreak = LineBreak.Simple),
              )
              Text(
                planTaskSummary(row, formatter),
                color = colors.onSurfaceVariant,
                fontSize = d.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            row.layout.revealAt?.let { revealAt ->
              TextButton(
                onClick = { onRevealAt(revealAt) },
                modifier = Modifier.heightIn(min = MinimumTouchTarget),
              ) {
                Text("Reveal")
              }
            }
            RowIconButton(
              Icons.Default.Add,
              if (item.isMilestone) "Set milestone date for ${item.title}"
              else "Add schedule block for ${item.title}",
              { onSchedule(item) },
              modifier = Modifier.testTag("gantt_schedule_task"),
            )
          }
        }
      }
      HorizontalDivider(color = colors.outlineVariant)
    }
  }
}

/**
 * The pinned column header above the rows: the "WORK" label, hoisted out of the vertical scroll
 * together with the date axis so the reading column never scrolls away either.
 */
@Composable
private fun GanttLabelsHeader(modifier: Modifier = Modifier) {
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme
  Box(
    modifier = modifier.height(AxisHeight).background(colors.surface).padding(horizontal = Space.sm),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(
      "WORK",
      fontSize = d.label,
      fontWeight = FontWeight.SemiBold,
      color = colors.onSurfaceVariant,
    )
  }
}

/**
 * The pinned date axis: the day ticks and non-working bands, hoisted out of the vertical scroll.
 * It shares the canvas's horizontal scroll, so the dates stay over the days they name.
 */
@Composable
private fun GanttAxisRow(
  ticks: List<GanttLayout.Tick>,
  canvasWidth: Dp,
  defaultNonWorkingBands: List<NonWorkingBand>,
  formatter: TimeFormatter,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme
  Box(
    modifier =
      modifier
        .width(canvasWidth)
        .height(AxisHeight)
        .ganttNonWorkingBands(defaultNonWorkingBands, colors.onSurface)
        .drawBehind {
          ticks.forEach { tick ->
            val x = (tick.position * size.width).toFloat()
            drawLine(
              colors.outlineVariant,
              start = androidx.compose.ui.geometry.Offset(x, 0f),
              end = androidx.compose.ui.geometry.Offset(x, size.height),
            )
          }
        }
  ) {
    ticks.filter { it.position < 0.995 }.forEach { tick ->
      Text(
        formatter.mediumDay(tick.timeMs),
        fontSize = d.label,
        color = colors.onSurfaceVariant,
        modifier =
          Modifier.offset(x = normalizedOffset(tick.position, canvasWidth))
            .padding(start = 4.dp, top = Space.sm),
        maxLines = 1,
      )
    }
  }
}

/**
 * Links and slack, drawn as one overlay rather than per row.
 *
 * The overlay never takes pointer input: a dependency is edited in the ledger, where it can be
 * named and explained, not by aiming at a two-pixel line.
 */
@Composable
private fun GanttLinkOverlay(
  connectors: List<GanttConnector>,
  slackBands: List<GanttSlackBand>,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val linkColor = colors.onSurfaceVariant
  val criticalColor = colors.error
  val slackColor = colors.primary
  Canvas(modifier = modifier) {
    slackBands.forEach { band ->
      val left = (band.fromPosition * size.width).toFloat()
      val right = (band.toPosition * size.width).toFloat()
      drawRect(
        color = slackColor.copy(alpha = 0.12f),
        topLeft = Offset(left, 0f),
        size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(1f), size.height),
      )
    }
    connectors.forEach { connector ->
      val fromX = (connector.fromPosition * size.width).toFloat()
      val toX = (connector.toPosition * size.width).toFloat()
      val fromY = connector.fromDp.dp.toPx()
      val toY = connector.toDp.dp.toPx()
      val color = if (connector.critical) criticalColor else linkColor
      val alpha = if (connector.critical) 0.75f else 0.45f
      val elbow = if (connector.backwards) fromX + 8.dp.toPx() else (fromX + toX) / 2f
      listOf(
        Offset(fromX, fromY) to Offset(elbow, fromY),
        Offset(elbow, fromY) to Offset(elbow, toY),
        Offset(elbow, toY) to Offset(toX, toY),
      )
        .forEach { (start, end) ->
          drawLine(color = color, start = start, end = end, strokeWidth = 2f, alpha = alpha)
        }
    }
  }
}

@Composable
private fun GanttCanvas(
  rows: List<GanttDisplayRow>,
  ticks: List<GanttLayout.Tick>,
  today: GanttLayout.Today?,
  canvasWidth: Dp,
  formatter: TimeFormatter,
  onEditEvent: (BriefingEvent) -> Unit,
  onEditTask: (PlanItem) -> Unit,
  onEditBlock: (PlanItem, PlanBlock) -> Unit,
  onMoveBlock: (PlanItem, PlanBlock) -> Unit,
  selectedMoveBlockId: String?,
  onMoveDragStart: () -> Unit,
  onMoveDrag: (GanttDragTarget, Float, Float) -> Unit,
  onMoveDragCancel: () -> Unit,
  onMoveDragEnd: () -> Unit,
  onMoveAdjust: (GanttDragTarget, Int) -> Boolean,
  criticalTaskIds: Set<String>,
  dependencies: List<PlanDependency>,
  slackMinutesByItemId: Map<String, Long>,
  rangeMinutes: Long,
  onConnectorsPlanned: (GanttConnectorPlan) -> Unit,
  defaultNonWorkingBands: List<NonWorkingBand>,
  taskNonWorkingBands: Map<String, List<NonWorkingBand>>,
  modifier: Modifier = Modifier,
  focused: Boolean,
) {
  val colors = MaterialTheme.colorScheme
  // Rows are laid out in a column of known heights, so where each bar sits is arithmetic rather
  // than measurement. Doing it here keeps the link drawing in one place instead of per row.
  val rowGeometry =
    remember(rows) {
      var top = 0f
      rows.mapNotNull { row ->
        val height = displayRowHeight(row).value
        val geometry =
          (row as? GanttDisplayRow.PlanTask)?.let { task ->
            GanttRowGeometry(
              itemId = task.layout.item.id,
              topDp = top,
              heightDp = height,
              startPosition = task.layout.segments.minOfOrNull { it.startPosition },
              endPosition = task.layout.segments.maxOfOrNull { it.endPosition },
            )
          }
        top += height
        geometry
      }
    }
  val connectorPlan =
    remember(rowGeometry, dependencies, criticalTaskIds) {
      GanttDependencyPaths.connectors(
        rows = rowGeometry,
        dependencies = dependencies.map { Triple(it.id, it.predecessorId, it.successorId) },
        criticalItemIds = criticalTaskIds,
      )
    }
  val slackBands =
    remember(rowGeometry, slackMinutesByItemId, rangeMinutes) {
      GanttDependencyPaths.slackBands(rowGeometry, slackMinutesByItemId, rangeMinutes)
    }
  LaunchedEffect(connectorPlan) { onConnectorsPlanned(connectorPlan) }
  val totalHeight = remember(rows) { rows.fold(0.dp) { total, row -> total + displayRowHeight(row) } }
  Box(modifier = modifier) {
    GanttLinkOverlay(
      connectors = connectorPlan.connectors,
      slackBands = slackBands,
      modifier = Modifier.fillMaxWidth().height(totalHeight).testTag("gantt_link_overlay"),
    )
  Column {
    rows.forEach { row ->
      when (row) {
        is GanttDisplayRow.Group ->
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .height(GroupHeight)
                .background(colors.surfaceContainerHigh)
                .ganttGrid(ticks, today, colors.outlineVariant, colors.primary)
          )
        is GanttDisplayRow.FixedCommitment ->
          FixedCommitmentCanvasRow(
            item = row.item,
            ticks = ticks,
            today = today,
            canvasWidth = canvasWidth,
            formatter = formatter,
            onEditEvent = onEditEvent,
            nonWorkingBands = defaultNonWorkingBands,
          )
        is GanttDisplayRow.PlanTask ->
          PlanTaskCanvasRow(
            row = row,
            ticks = ticks,
            today = today,
            canvasWidth = canvasWidth,
            formatter = formatter,
            onEditTask = onEditTask,
            onEditBlock = onEditBlock,
            onMoveBlock = onMoveBlock,
            selectedMoveBlockId = selectedMoveBlockId,
            onMoveDragStart = onMoveDragStart,
            onMoveDrag = onMoveDrag,
            onMoveDragCancel = onMoveDragCancel,
            onMoveDragEnd = onMoveDragEnd,
            onMoveAdjust = onMoveAdjust,
            isCritical = row.layout.item.id in criticalTaskIds,
            nonWorkingBands =
              taskNonWorkingBands[row.layout.item.id] ?: defaultNonWorkingBands,
            focused = focused,
          )
      }
      HorizontalDivider(color = colors.outlineVariant)
    }
    }
  }
}

/** The map's references, in one definition so the page and the tab cannot drift apart. */
@Composable
private fun GanttMapMenu(
  expanded: Boolean,
  dependencyCount: Int,
  boardReady: Boolean,
  legendShown: Boolean,
  onDismiss: () -> Unit,
  onDependencies: () -> Unit,
  onToggleLegend: () -> Unit,
) {
  DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
    DropdownMenuItem(
      text = { Text("Dependencies ($dependencyCount)") },
      enabled = boardReady,
      onClick = {
        onDismiss()
        onDependencies()
      },
      modifier = Modifier.testTag("gantt_dependencies"),
    )
    DropdownMenuItem(
      text = { Text(if (legendShown) "Hide legend" else "What the bars mean") },
      onClick = {
        onDismiss()
        onToggleLegend()
      },
      modifier = Modifier.testTag("gantt_legend_toggle"),
    )
  }
}

/** Previous range, Today, next range — the same three controls wherever the map is shown. */
@Composable
private fun GanttRangeNav(rangeDays: Int, selectedDay: Long, viewModel: BriefingViewModel) {
  RowIconButton(
    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
    "Previous $rangeDays days",
    { viewModel.selectDay(ScheduleAnalysis.startOfDayOffset(selectedDay, -rangeDays)) },
    modifier = Modifier.testTag("gantt_previous_range"),
  )
  TextButton(
    onClick = viewModel::selectToday,
    modifier = Modifier.heightIn(min = MinimumTouchTarget),
  ) {
    Text("Today")
  }
  RowIconButton(
    Icons.AutoMirrored.Filled.KeyboardArrowRight,
    "Next $rangeDays days",
    { viewModel.selectDay(ScheduleAnalysis.startOfDayOffset(selectedDay, rangeDays)) },
    modifier = Modifier.testTag("gantt_next_range"),
  )
}

@Composable
private fun PlanTaskCanvasRow(
  row: GanttDisplayRow.PlanTask,
  ticks: List<GanttLayout.Tick>,
  today: GanttLayout.Today?,
  canvasWidth: Dp,
  formatter: TimeFormatter,
  onEditTask: (PlanItem) -> Unit,
  onEditBlock: (PlanItem, PlanBlock) -> Unit,
  onMoveBlock: (PlanItem, PlanBlock) -> Unit,
  selectedMoveBlockId: String?,
  onMoveDragStart: () -> Unit,
  onMoveDrag: (GanttDragTarget, Float, Float) -> Unit,
  onMoveDragCancel: () -> Unit,
  onMoveDragEnd: () -> Unit,
  onMoveAdjust: (GanttDragTarget, Int) -> Boolean,
  isCritical: Boolean,
  nonWorkingBands: List<NonWorkingBand>,
  /** Direct dragging is offered on the full page, where there is room to aim. */
  focused: Boolean,
) {
  val item = row.layout.item
  val density = LocalDensity.current
  val colors = MaterialTheme.colorScheme
  val status = LocalStatusColors.current
  val tone =
    when (item.priority) {
      PlanPriority.URGENT -> status.urgent
      PlanPriority.HIGH -> status.deadline
      else -> colors.primary
    }
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .height(displayRowHeight(row))
        .ganttNonWorkingBands(nonWorkingBands, colors.onSurface)
        .ganttGrid(ticks, today, colors.outlineVariant, colors.primary),
  ) {
    row.layout.milestonePosition?.let { position ->
      val touchStart =
        (normalizedOffset(position, canvasWidth) - MinimumTouchTarget / 2)
          .coerceIn(0.dp, (canvasWidth - MinimumTouchTarget).coerceAtLeast(0.dp))
      Box(
        modifier =
          Modifier.offset(x = touchStart, y = (displayRowHeight(row) - MinimumTouchTarget) / 2)
            .size(MinimumTouchTarget)
            .semantics {
              contentDescription =
                "${item.title}, Plan milestone on ${formatter.mediumDay(requireNotNull(item.dueAt))}"
            }
            .clickable(role = Role.Button, onClickLabel = "Edit Plan milestone") {
              onEditTask(item)
            }
            .testTag("plan_gantt_item"),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          Modifier.size(14.dp)
            .rotate(45f)
            .background(tone, RoundedCornerShape(Radius.mark))
        )
      }
    }
    val currentOnMoveDragStart by rememberUpdatedState(onMoveDragStart)
    val currentOnMoveDrag by rememberUpdatedState(onMoveDrag)
    val currentOnMoveDragCancel by rememberUpdatedState(onMoveDragCancel)
    val currentOnMoveDragEnd by rememberUpdatedState(onMoveDragEnd)
    val currentOnMoveAdjust by rememberUpdatedState(onMoveAdjust)
    row.layout.segments.forEach { segment ->
      key(segment.block.id) {
        val start = normalizedOffset(segment.startPosition, canvasWidth)
        val end = normalizedOffset(segment.endPosition, canvasWidth)
        val barWidth = (end - start).coerceAtLeast(MinimumLegibleBarWidth)
        val selected = segment.block.id == selectedMoveBlockId
        // In move mode the bar shares the canvas with two 48 dp handles drawn above it, so the
        // three regions are laid out as neighbours; otherwise the bar keeps its own tap target.
        val manipulation =
          if (selected) {
            GanttDirectManipulationTargets.compute(
              barStartDp = start.value,
              barEndDp = end.value,
              canvasWidthDp = canvasWidth.value,
              handleDp = DirectManipulationHandleSize.value,
              minimumMoveDp = MinimumTouchTarget.value,
            )
          } else {
            null
          }
        val touchStart =
          manipulation?.moveStartDp?.dp
            ?: start.coerceAtMost((canvasWidth - MinimumTouchTarget).coerceAtLeast(0.dp))
        val barOffset = start - touchStart
        val touchWidth =
          manipulation?.moveWidthDp?.dp
            ?: (barOffset + barWidth)
              .coerceAtLeast(MinimumTouchTarget)
              .coerceAtMost(canvasWidth - touchStart)
        val canMove = !item.locked && !segment.block.locked
        val enterMoveActions =
          if (canMove && !selected) {
            listOf(
              CustomAccessibilityAction("Move Plan block") {
                onMoveBlock(item, segment.block)
                true
              }
            )
          } else {
            emptyList()
          }
        val selectedMoveActions =
          if (selected) {
            listOf(
              CustomAccessibilityAction("Move 15 minutes earlier") {
                currentOnMoveAdjust(GanttDragTarget.BLOCK, -GanttBlockEditPolicy.STEP_MINUTES)
              },
              CustomAccessibilityAction("Move 15 minutes later") {
                currentOnMoveAdjust(GanttDragTarget.BLOCK, GanttBlockEditPolicy.STEP_MINUTES)
              },
            )
          } else {
            emptyList()
          }
        val barContentStartPixels = with(density) { touchStart.toPx() }
        val currentBarContentStartPixels by rememberUpdatedState(barContentStartPixels)
        Box(
          modifier =
            Modifier.offset(x = touchStart, y = 8.dp + PlanLanePitch * segment.lane)
              .width(touchWidth)
              .height(MinimumTouchTarget)
              .semantics {
                contentDescription = planSegmentDescription(row, segment, formatter, isCritical)
                stateDescription =
                  when {
                    selected ->
                      "Direct move preview. Drag the bar, then use Apply; pointer release does not save."
                    segment.block.locked || item.locked ->
                      "Locked. Open the block and explicitly unlock it before movement."
                    focused ->
                      "Flexible. Drag the bar to move it, or use Move Plan block; nothing is saved " +
                        "until you use Apply."
                    else -> "Flexible. Long press or use Move Plan block to enter direct movement."
                  }
                customActions = if (selected) selectedMoveActions else enterMoveActions
              }
              .then(
                // On its own page a bar is draggable outright: the drag itself picks the block up,
                // so there is no long press to discover first. Nothing is written by dragging —
                // release leaves the preview open and Apply is still the only thing that commits,
                // which is the rule every other move path here follows.
                if (selected || (focused && canMove)) {
                  // Deliberately not keyed on `selected`: picking the block up changes that flag,
                  // and re-keying would restart this pointer input and cancel the very gesture that
                  // did the picking up. The first drag would then only select, and the person would
                  // have to drag again — which is exactly what dragging is supposed to avoid.
                  Modifier.pointerInput(segment.block.id, GanttDragTarget.BLOCK, focused) {
                    detectHorizontalDragGestures(
                      onDragStart = {
                        if (!selected) onMoveBlock(item, segment.block)
                        currentOnMoveDragStart()
                      },
                      onHorizontalDrag = { change, delta ->
                        currentOnMoveDrag(
                          GanttDragTarget.BLOCK,
                          delta,
                          currentBarContentStartPixels + change.position.x,
                        )
                        change.consume()
                      },
                      onDragCancel = currentOnMoveDragCancel,
                      onDragEnd = currentOnMoveDragEnd,
                    )
                  }
                } else {
                  Modifier.combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Edit Plan block",
                    onClick = { onEditBlock(item, segment.block) },
                    onLongClickLabel = if (canMove) "Move Plan block" else null,
                    onLongClick =
                      if (canMove) {
                        { onMoveBlock(item, segment.block) }
                      } else {
                        null
                      },
                  )
                }
              )
              .testTag(if (selected) "gantt_move_bar" else "plan_gantt_item"),
          contentAlignment = Alignment.CenterStart,
        ) {
          Surface(
            color = tone,
            contentColor = readableBarContentColor(tone),
            border =
              when {
                selected -> BorderStroke(3.dp, colors.onSurface)
                isCritical -> BorderStroke(2.dp, colors.onSurface)
                else -> null
              },
            shape = RoundedCornerShape(Radius.control),
            // A little taller on the page, where the room exists: a bar you are about to drag with
            // a thumb should look like something you can put a thumb on.
            modifier =
              Modifier.offset(x = barOffset).width(barWidth).height(if (focused) 36.dp else 30.dp),
          ) {
            Box {
              if (item.progress > 0) {
                Box(
                  Modifier.fillMaxWidth(item.progress / 100f)
                    .height(30.dp)
                    .background(Color.Black.copy(alpha = 0.16f))
                )
              }
              Text(
                buildString {
                  if (isCritical) append("CRITICAL · ")
                  if (selected) append("PREVIEW · ")
                  if (segment.block.locked) append("LOCKED · ")
                  append(item.title)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.sm, vertical = 6.dp),
              )
            }
          }
        }
        if (selected && manipulation != null) {
          manipulation.startHandleStartDp?.let { handleStart ->
            GanttDragHandle(
              target = GanttDragTarget.START,
              boundary = start,
              touchStart = handleStart.dp,
              lane = segment.lane,
              tone = tone,
              contentDescription =
                "Start handle, ${formatter.mediumDay(segment.block.startAt)}, ${formatter.time(segment.block.startAt)}",
              onDragStart = currentOnMoveDragStart,
              onDrag = currentOnMoveDrag,
              onDragCancel = currentOnMoveDragCancel,
              onDragEnd = currentOnMoveDragEnd,
              onAdjust = currentOnMoveAdjust,
            )
          }
          manipulation.endHandleStartDp?.let { handleStart ->
            GanttDragHandle(
              target = GanttDragTarget.END,
              boundary = end,
              touchStart = handleStart.dp,
              lane = segment.lane,
              tone = tone,
              contentDescription =
                "End handle, ${formatter.mediumDay(segment.block.endAt)}, ${formatter.time(segment.block.endAt)}",
              onDragStart = currentOnMoveDragStart,
              onDrag = currentOnMoveDrag,
              onDragCancel = currentOnMoveDragCancel,
              onDragEnd = currentOnMoveDragEnd,
              onAdjust = currentOnMoveAdjust,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun GanttDragHandle(
  target: GanttDragTarget,
  boundary: Dp,
  touchStart: Dp,
  lane: Int,
  tone: Color,
  contentDescription: String,
  onDragStart: () -> Unit,
  onDrag: (GanttDragTarget, Float, Float) -> Unit,
  onDragCancel: () -> Unit,
  onDragEnd: () -> Unit,
  onAdjust: (GanttDragTarget, Int) -> Boolean,
) {
  require(target != GanttDragTarget.BLOCK) { "A boundary handle must target start or end" }
  val density = LocalDensity.current
  // The grip points at the real boundary, clamped inside its own box when the bar is too narrow
  // for the handle to sit exactly on it.
  val gripOffset =
    (boundary - touchStart - DirectManipulationGripWidth / 2)
      .coerceIn(0.dp, DirectManipulationHandleSize - DirectManipulationGripWidth)
  val contentStartPixels = with(density) { touchStart.toPx() }
  val currentContentStartPixels by rememberUpdatedState(contentStartPixels)
  val currentOnDragStart by rememberUpdatedState(onDragStart)
  val currentOnDrag by rememberUpdatedState(onDrag)
  val currentOnDragCancel by rememberUpdatedState(onDragCancel)
  val currentOnDragEnd by rememberUpdatedState(onDragEnd)
  val currentOnAdjust by rememberUpdatedState(onAdjust)
  val actions =
    if (target == GanttDragTarget.START) {
      listOf(
        CustomAccessibilityAction("Move start 15 minutes earlier") {
          currentOnAdjust(target, -GanttBlockEditPolicy.STEP_MINUTES)
        },
        CustomAccessibilityAction("Move start 15 minutes later") {
          currentOnAdjust(target, GanttBlockEditPolicy.STEP_MINUTES)
        },
      )
    } else {
      listOf(
        CustomAccessibilityAction("Shorten 15 minutes") {
          currentOnAdjust(target, -GanttBlockEditPolicy.STEP_MINUTES)
        },
        CustomAccessibilityAction("Extend 15 minutes") {
          currentOnAdjust(target, GanttBlockEditPolicy.STEP_MINUTES)
        },
      )
    }

  Box(
    modifier =
      Modifier.offset(x = touchStart, y = 8.dp + PlanLanePitch * lane)
        .size(DirectManipulationHandleSize)
        .zIndex(2f)
        .semantics {
          this.contentDescription = contentDescription
          stateDescription =
            "Preview handle. Drag horizontally or use 15 minute actions; Apply is required to save."
          customActions = actions
        }
        .pointerInput(target) {
          detectHorizontalDragGestures(
            onDragStart = { currentOnDragStart() },
            onHorizontalDrag = { change, delta ->
              currentOnDrag(target, delta, currentContentStartPixels + change.position.x)
              change.consume()
            },
            onDragCancel = currentOnDragCancel,
            onDragEnd = currentOnDragEnd,
          )
        }
        .testTag(
          if (target == GanttDragTarget.START) "gantt_move_start_handle"
          else "gantt_move_end_handle"
        )
  ) {
    Box(
      modifier =
        Modifier.offset(x = gripOffset, y = 6.dp)
          .width(DirectManipulationGripWidth)
          .height(36.dp)
          .background(tone, RoundedCornerShape(DirectManipulationGripWidth / 2))
          .border(1.dp, readableBarContentColor(tone), RoundedCornerShape(Radius.mark))
    )
  }
}

@Composable
private fun GanttMoveModePanel(
  item: PlanItem,
  draft: GanttBlockDraft,
  formatter: TimeFormatter,
  preview: PlanBlockPreviewResult?,
  scheduleProblem: String?,
  saving: Boolean,
  hasChanges: Boolean,
  onAdjust: (GanttDragTarget, Int) -> Boolean,
  onCancel: () -> Unit,
  onApply: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val focusRequester = remember(draft.itemId, draft.blockId) { FocusRequester() }
  val currentOnAdjust by rememberUpdatedState(onAdjust)
  val step = GanttBlockEditPolicy.STEP_MINUTES
  val accessibilityActions =
    listOf(
      CustomAccessibilityAction("Move 15 minutes earlier") {
        currentOnAdjust(GanttDragTarget.BLOCK, -step)
      },
      CustomAccessibilityAction("Move 15 minutes later") {
        currentOnAdjust(GanttDragTarget.BLOCK, step)
      },
      CustomAccessibilityAction("Move start 15 minutes earlier") {
        currentOnAdjust(GanttDragTarget.START, -step)
      },
      CustomAccessibilityAction("Move start 15 minutes later") {
        currentOnAdjust(GanttDragTarget.START, step)
      },
      CustomAccessibilityAction("Shorten 15 minutes") {
        currentOnAdjust(GanttDragTarget.END, -step)
      },
      CustomAccessibilityAction("Extend 15 minutes") {
        currentOnAdjust(GanttDragTarget.END, step)
      },
    )
  LaunchedEffect(draft.itemId, draft.blockId) { focusRequester.requestFocus() }

  Surface(
    color = colors.surfaceContainerHigh,
    contentColor = colors.onSurface,
    shape = RoundedCornerShape(Radius.block),
    border = BorderStroke(1.dp, colors.primary),
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
    modifier =
      modifier
        .fillMaxWidth()
        .focusRequester(focusRequester)
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) {
            false
          } else {
            if (event.key == Key.Escape) {
              // A keyboard user's Cancel, matching what Back does for touch.
              onCancel()
              return@onPreviewKeyEvent true
            }
            val minutes =
              when (event.key) {
                Key.DirectionLeft -> -step
                Key.DirectionRight -> step
                else -> null
              }
            if (minutes == null) {
              false
            } else {
              val target =
                when {
                  event.isShiftPressed -> GanttDragTarget.START
                  event.isAltPressed -> GanttDragTarget.END
                  else -> GanttDragTarget.BLOCK
                }
              currentOnAdjust(target, minutes)
            }
          }
        }
        .focusable()
        .semantics {
          contentDescription = "Direct movement for ${item.title}"
          stateDescription =
            "Preview only. Left and right move the block; Shift adjusts start; Alt adjusts end. Apply is required to save."
          customActions = accessibilityActions
        }
        .testTag("gantt_move_mode"),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
      verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
      Text(
        "Moving ${item.title}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "Start · ${formatter.mediumDay(draft.startAt)} · ${formatter.time(draft.startAt)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("gantt_move_start"),
      )
      Text(
        "End · ${formatter.mediumDay(draft.endAt)} · ${formatter.time(draft.endAt)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("gantt_move_end"),
      )
      Text(
        "Drag the bar to move it or a handle to resize, in 15-minute steps. Only Apply saves.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
      val blockingIssue = preview?.issues?.firstOrNull { it.blocking }
      val warningCount = preview?.issues?.count { !it.blocking } ?: 0
      val status =
        when {
          scheduleProblem != null -> scheduleProblem
          preview == null -> "Checking this preview against working time and fixed commitments…"
          blockingIssue != null -> "Cannot apply: ${blockingIssue.explanation}"
          !hasChanges -> "No preview change yet."
          warningCount > 0 -> "Ready to apply with $warningCount scheduling warning${if (warningCount == 1) "" else "s"}."
          else -> "Ready to apply. Working time, commitments, and dependencies are valid."
        }
      Text(
        text = status,
        style = MaterialTheme.typography.bodySmall,
        color = if (blockingIssue != null || scheduleProblem != null) colors.error else colors.primary,
        modifier =
          Modifier.semantics {
            // An increment applied by custom action, arrow key or handle changes the times and
            // this line, and moves no focus — so without a live region the one person who cannot
            // see the bar move is told nothing at all. The times ride along in the same
            // announcement rather than as three separate ones.
            liveRegion = LiveRegionMode.Polite
            contentDescription =
              "Start ${formatter.mediumDay(draft.startAt)} ${formatter.time(draft.startAt)}, " +
                "end ${formatter.mediumDay(draft.endAt)} ${formatter.time(draft.endAt)}. $status"
          }
            .testTag("gantt_move_status"),
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = onCancel,
          enabled = !saving,
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("gantt_move_cancel"),
        ) {
          Text("Cancel")
        }
        TextButton(
          onClick = onApply,
          enabled = !saving && hasChanges && preview?.canCommit == true,
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("gantt_move_apply"),
        ) {
          Text(if (saving) "Applying…" else "Apply")
        }
      }
    }
  }
}

@Composable
private fun FixedCommitmentCanvasRow(
  item: GanttLayout.Item,
  ticks: List<GanttLayout.Tick>,
  today: GanttLayout.Today?,
  canvasWidth: Dp,
  formatter: TimeFormatter,
  onEditEvent: (BriefingEvent) -> Unit,
  nonWorkingBands: List<NonWorkingBand>,
) {
  val colors = MaterialTheme.colorScheme
  val status = LocalStatusColors.current
  val tone =
    when {
      item.event.isDeadline -> status.deadline
      item.event.isUrgent -> status.urgent
      else -> colors.onSurfaceVariant
    }
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .height(FixedRowHeight)
        .ganttNonWorkingBands(nonWorkingBands, colors.onSurface)
        .ganttGrid(ticks, today, colors.outlineVariant, colors.primary),
    contentAlignment = Alignment.CenterStart,
  ) {
    if (item.kind == GanttLayout.ItemKind.MILESTONE) {
      val touchStart =
        (normalizedOffset(item.startPosition, canvasWidth) - MinimumTouchTarget / 2)
          .coerceIn(0.dp, (canvasWidth - MinimumTouchTarget).coerceAtLeast(0.dp))
      Box(
        modifier =
          Modifier.offset(x = touchStart)
            .size(MinimumTouchTarget)
            .semantics { contentDescription = fixedItemDescription(item, formatter) }
            .clickable(role = Role.Button, onClickLabel = "Edit fixed commitment") {
              onEditEvent(item.event)
            }
            .testTag("gantt_item"),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          Modifier.size(15.dp)
            .rotate(45f)
            .border(2.dp, tone, RoundedCornerShape(Radius.mark))
        )
      }
    } else {
      val start = normalizedOffset(item.startPosition, canvasWidth)
      val end = normalizedOffset(item.endPosition, canvasWidth)
      val barWidth = (end - start).coerceAtLeast(MinimumLegibleBarWidth)
      val touchStart = start.coerceAtMost((canvasWidth - MinimumTouchTarget).coerceAtLeast(0.dp))
      val barOffset = start - touchStart
      val touchWidth =
        (barOffset + barWidth)
          .coerceAtLeast(MinimumTouchTarget)
          .coerceAtMost(canvasWidth - touchStart)
      Box(
        modifier =
          Modifier.offset(x = touchStart)
            .width(touchWidth)
            .height(MinimumTouchTarget)
            .semantics { contentDescription = fixedItemDescription(item, formatter) }
            .clickable(role = Role.Button, onClickLabel = "Edit fixed commitment") {
              onEditEvent(item.event)
            }
            .testTag("gantt_item"),
        contentAlignment = Alignment.CenterStart,
      ) {
        Surface(
          color = colors.surface.copy(alpha = 0.94f),
          contentColor = tone,
          border = BorderStroke(1.dp, tone),
          shape = RoundedCornerShape(Radius.control),
          modifier = Modifier.offset(x = barOffset).width(barWidth).height(30.dp),
        ) {
          Text(
            "FIXED · ${item.event.title}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Space.sm, vertical = 6.dp),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleBlockDialog(
  item: PlanItem,
  initial: GanttBlockDraft,
  existingBlock: PlanBlock?,
  formatter: TimeFormatter,
  allItems: List<PlanItem>,
  allBlocks: List<PlanBlock>,
  fixedCommitments: List<WorkingInterval>,
  dependencies: List<com.example.data.model.PlanDependency>,
  workSchedule: com.example.core.WorkingCalendarSpec?,
  workScheduleLabel: String?,
  workScheduleProblem: String?,
  onDismiss: () -> Unit,
  onConfirm: (Long, Long, Boolean, (Boolean) -> Unit) -> Unit,
  onMoveOnMap: ((PlanBlock) -> Unit)? = null,
  onDelete: ((Boolean) -> Unit) -> Unit,
) {
  var draft by
    rememberSaveable(
      initial.itemId,
      initial.blockId,
      initial.startAt,
      initial.endAt,
      stateSaver = GanttBlockDraftSaver,
    ) {
      mutableStateOf(initial)
    }
  var durationText by
    rememberSaveable(initial.itemId, initial.blockId, initial.startAt, initial.endAt) {
      mutableStateOf(GanttBlockEditPolicy.exactDurationMinutes(initial)?.toString().orEmpty())
    }
  var dateField by rememberSaveable { mutableStateOf<BlockTimeField?>(null) }
  var timeField by rememberSaveable { mutableStateOf<BlockTimeField?>(null) }
  var confirmDelete by rememberSaveable { mutableStateOf(false) }
  var saving by rememberSaveable { mutableStateOf(false) }
  val duration = durationText.toIntOrNull()?.takeIf { it > 0 }
  val exactDuration = GanttBlockEditPolicy.exactDurationMinutes(draft)
  val durationInputValid = duration != null && duration == exactDuration
  val hasChanges =
    existingBlock == null ||
      draft.startAt != existingBlock.startAt ||
      draft.endAt != existingBlock.endAt ||
      draft.locked != existingBlock.locked
  val timeEditingEnabled = !saving && existingBlock?.locked != true
  val earlier = GanttBlockEditPolicy.moveByMinutes(draft, -GanttBlockEditPolicy.STEP_MINUTES)
  val later = GanttBlockEditPolicy.moveByMinutes(draft, GanttBlockEditPolicy.STEP_MINUTES)
  val shorter =
    GanttBlockEditPolicy.resizeEndByMinutes(draft, -GanttBlockEditPolicy.STEP_MINUTES)
  val longer = GanttBlockEditPolicy.resizeEndByMinutes(draft, GanttBlockEditPolicy.STEP_MINUTES)
  fun applyDraft(updated: GanttBlockDraft?) {
    if (updated == null) return
    draft = updated
    durationText = GanttBlockEditPolicy.exactDurationMinutes(updated)?.toString().orEmpty()
  }
  val preview =
    remember(
      item,
      draft.startAt,
      draft.endAt,
      durationInputValid,
      allItems,
      allBlocks,
      fixedCommitments,
      dependencies,
      workSchedule,
    ) {
      if (!durationInputValid || workSchedule == null) null
      else {
        runCatching {
            PlanBlockPreview.evaluate(
              item = item,
              blockId = initial.blockId,
              proposedStart = draft.startAt,
              proposedEnd = draft.endAt,
              items = allItems,
              blocks = allBlocks,
              fixedCommitments = fixedCommitments,
              dependencies = dependencies,
              workSchedule = workSchedule,
            )
          }
          .getOrNull()
      }
    }

  AlertDialog(
    onDismissRequest = { if (!saving) onDismiss() },
    title = { Text(if (existingBlock == null) "Schedule a block" else "Edit schedule block") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.md),
        modifier =
          Modifier.heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .testTag("gantt_block_editor"),
      ) {
        Text(
          item.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          if (existingBlock == null) {
            "This creates a flexible Plan block. It does not create or rewrite a calendar event."
          } else {
            "This changes only the app-owned Plan block. Fixed calendar commitments stay unchanged."
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "Working schedule: ${workScheduleLabel ?: "unavailable"}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("gantt_block_work_schedule"),
        )
        Text("Start", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
          OutlinedButton(
            onClick = { dateField = BlockTimeField.START },
            enabled = timeEditingEnabled,
            modifier =
              Modifier.weight(1f)
                .heightIn(min = MinimumTouchTarget)
                .testTag("gantt_block_start_date"),
          ) {
            Text(formatter.mediumDay(draft.startAt), maxLines = 1)
          }
          OutlinedButton(
            onClick = { timeField = BlockTimeField.START },
            enabled = timeEditingEnabled,
            modifier =
              Modifier.weight(1f)
                .heightIn(min = MinimumTouchTarget)
                .testTag("gantt_block_start_time"),
          ) {
            Text(formatter.time(draft.startAt))
          }
        }
        Text("End", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
          OutlinedButton(
            onClick = { dateField = BlockTimeField.END },
            enabled = timeEditingEnabled,
            modifier =
              Modifier.weight(1f)
                .heightIn(min = MinimumTouchTarget)
                .testTag("gantt_block_end_date"),
          ) {
            Text(formatter.mediumDay(draft.endAt), maxLines = 1)
          }
          OutlinedButton(
            onClick = { timeField = BlockTimeField.END },
            enabled = timeEditingEnabled,
            modifier =
              Modifier.weight(1f)
                .heightIn(min = MinimumTouchTarget)
                .testTag("gantt_block_end_time"),
          ) {
            Text(formatter.time(draft.endAt))
          }
        }
        OutlinedTextField(
          value = durationText,
          onValueChange = { value ->
            val filtered = value.filter(Char::isDigit).take(5)
            durationText = filtered
            filtered.toIntOrNull()?.let { minutes ->
              GanttBlockEditPolicy.replaceDurationMinutes(draft, minutes)?.let { draft = it }
            }
          },
          enabled = timeEditingEnabled,
          label = { Text("Duration (minutes)") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth().testTag("gantt_block_duration"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
          OutlinedButton(
            onClick = { applyDraft(earlier) },
            enabled = timeEditingEnabled && earlier != null,
            modifier =
              Modifier.weight(1f)
                .heightIn(min = MinimumTouchTarget)
                .testTag("gantt_block_move_earlier"),
          ) {
            Text("Earlier 15m")
          }
          OutlinedButton(
            onClick = { applyDraft(later) },
            enabled = timeEditingEnabled && later != null,
            modifier =
              Modifier.weight(1f)
                .heightIn(min = MinimumTouchTarget)
                .testTag("gantt_block_move_later"),
          ) {
            Text("Later 15m")
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
          OutlinedButton(
            onClick = { applyDraft(shorter) },
            enabled = timeEditingEnabled && shorter != null,
            modifier =
              Modifier.weight(1f)
                .heightIn(min = MinimumTouchTarget)
                .testTag("gantt_block_shorten"),
          ) {
            Text("Shorten 15m")
          }
          OutlinedButton(
            onClick = { applyDraft(longer) },
            enabled = timeEditingEnabled && longer != null,
            modifier =
              Modifier.weight(1f)
                .heightIn(min = MinimumTouchTarget)
                .testTag("gantt_block_extend"),
          ) {
            Text("Extend 15m")
          }
        }
        FilterChip(
          selected = draft.locked,
          onClick = { draft = draft.copy(locked = !draft.locked) },
          enabled = !saving,
          label = { Text(if (draft.locked) "Locked in place" else "Flexible") },
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("gantt_block_lock"),
        )
        if (existingBlock?.locked == true) {
          Text(
            "This saved block is locked. Turn off Locked in place and save before changing its time or deleting it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          "${formatter.mediumDay(draft.startAt)} · ${formatter.range(draft.startAt, draft.endAt)}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
          workSchedule == null ->
            Text(
              workScheduleProblem
                ?: "Set a valid working week in Settings before scheduling Plan work.",
              color = MaterialTheme.colorScheme.error,
            )
          !durationInputValid || preview == null ->
            Text(
              "Enter a valid duration to preview this change.",
              color = MaterialTheme.colorScheme.error,
            )
          existingBlock != null && !hasChanges ->
            Text(
              "No changes yet.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          preview.issues.isEmpty() ->
            Text(
              "Ready to schedule within working time and fixed commitments.",
              color = MaterialTheme.colorScheme.primary,
            )
          else ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
              preview.issues.forEach { issue ->
                Text(
                  "${if (issue.blocking) "Cannot schedule" else "Warning"}: ${issue.explanation}",
                  color =
                    if (issue.blocking) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
        }
        preview?.affectedSuccessorIds?.takeIf { it.isNotEmpty() }?.let { affected ->
          Text(
            "This change affects ${affected.size} downstream " +
              if (affected.size == 1) "task." else "tasks.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = !saving && hasChanges && durationInputValid && preview?.canCommit == true,
        onClick = {
          saving = true
          onConfirm(draft.startAt, draft.endAt, draft.locked) { success ->
            saving = false
            if (success) onDismiss()
          }
        },
        modifier = Modifier.testTag("gantt_block_save"),
      ) {
        Text(
          when {
            saving -> "Saving…"
            existingBlock == null -> "Add block"
            else -> "Save changes"
          }
        )
      }
    },
    dismissButton = {
      Row {
        if (existingBlock != null) {
          TextButton(
            onClick = { confirmDelete = true },
            enabled = !saving && !existingBlock.locked,
            modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("gantt_block_delete"),
          ) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
          }
        }
        if (existingBlock != null && onMoveOnMap != null) {
          // Direct movement was reachable only by long-press, which nothing on screen advertised.
          // This is the visible way in; the gesture stays as the accelerator.
          TextButton(
            onClick = { onMoveOnMap(existingBlock) },
            enabled = !saving && !existingBlock.locked && !item.locked,
            modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("gantt_block_move_on_map"),
          ) {
            Text("Move on map")
          }
        }
        TextButton(
          onClick = onDismiss,
          enabled = !saving,
          modifier = Modifier.heightIn(min = MinimumTouchTarget),
        ) {
          Text("Cancel")
        }
      }
    },
  )

  val activeDateField = dateField
  if (activeDateField != null) {
    val current = if (activeDateField == BlockTimeField.START) draft.startAt else draft.endAt
    val picker =
      rememberDatePickerState(
        initialSelectedDateMillis = ScheduleAnalysis.utcMillisFromLocalDay(current)
      )
    DatePickerDialog(
      onDismissRequest = { dateField = null },
      confirmButton = {
        TextButton(
          onClick = {
            picker.selectedDateMillis?.let { selected ->
              val localDay = ScheduleAnalysis.localDayFromUtcMillis(selected)
              val selectedAt =
                ScheduleAnalysis.withTimeOfDay(
                  localDay,
                  ScheduleAnalysis.hourOf(current),
                  ScheduleAnalysis.minuteOf(current),
                )
              applyDraft(
                if (activeDateField == BlockTimeField.START) {
                  GanttBlockEditPolicy.replaceStartPreservingDuration(draft, selectedAt)
                } else {
                  GanttBlockEditPolicy.replaceEnd(draft, selectedAt)
                }
              )
            }
            dateField = null
          }
        ) {
          Text("Set")
        }
      },
      dismissButton = {
        TextButton(onClick = { dateField = null }) { Text("Cancel") }
      },
    ) {
      DatePicker(state = picker)
    }
  }

  val activeTimeField = timeField
  if (activeTimeField != null) {
    val current = if (activeTimeField == BlockTimeField.START) draft.startAt else draft.endAt
    AppTimeDialog(
      title = if (activeTimeField == BlockTimeField.START) "Block start" else "Block end",
      initialMs = current,
      is24Hour = formatter.is24Hour,
      onDismiss = { timeField = null },
      onConfirm = { hour, minute ->
        val selectedAt = ScheduleAnalysis.withTimeOfDay(current, hour, minute)
        applyDraft(
          if (activeTimeField == BlockTimeField.START) {
            GanttBlockEditPolicy.replaceStartPreservingDuration(draft, selectedAt)
          } else {
            GanttBlockEditPolicy.replaceEnd(draft, selectedAt)
          }
        )
        timeField = null
      },
    )
  }

  if (confirmDelete && existingBlock != null) {
    AlertDialog(
      onDismissRequest = { if (!saving) confirmDelete = false },
      title = { Text("Delete this Plan block?") },
      text = { Text("The task remains in Plan. You can restore the exact block from Undo or History.") },
      confirmButton = {
        TextButton(
          onClick = {
            saving = true
            onDelete { success ->
              saving = false
              confirmDelete = false
              if (success) onDismiss()
            }
          },
          enabled = !saving && !existingBlock.locked,
          modifier = Modifier.testTag("gantt_block_delete_confirm"),
        ) {
          Text(if (saving) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = false }, enabled = !saving) { Text("Cancel") }
      },
      modifier = Modifier.testTag("gantt_block_delete_dialog"),
    )
  }
}

private fun displayRowHeight(row: GanttDisplayRow): Dp =
  when (row) {
    is GanttDisplayRow.Group -> GroupHeight
    is GanttDisplayRow.FixedCommitment -> FixedRowHeight
    is GanttDisplayRow.PlanTask ->
      (16.dp + PlanLanePitch * row.layout.laneCount).coerceAtLeast(PlanMinimumRowHeight)
  }

private fun planTaskSummary(row: GanttDisplayRow.PlanTask, formatter: TimeFormatter): String =
  when (row.layout.state) {
    PlanGanttLayout.RowState.SCHEDULED ->
      "${row.layout.segments.size} ${if (row.layout.segments.size == 1) "block" else "blocks"} · ${row.layout.item.progress}%"
    PlanGanttLayout.RowState.MILESTONE ->
      "MILESTONE · ${formatter.mediumDay(requireNotNull(row.layout.item.dueAt))}"
    PlanGanttLayout.RowState.UNSCHEDULED ->
      if (row.layout.item.isMilestone) "UNSCHEDULED · set milestone date"
      else "UNSCHEDULED · add a block"
    PlanGanttLayout.RowState.OUTSIDE_RANGE -> "OUTSIDE VISIBLE RANGE"
  }

private fun planTaskStateDescription(
  row: GanttDisplayRow.PlanTask,
  formatter: TimeFormatter,
): String =
  buildList {
      add("Plan task")
      add(planTaskSummary(row, formatter))
      add("${row.layout.item.priority} priority")
      if (row.outline.hasChildren) add("Has subtasks")
      if (row.outline.isOrphan) add("Parent unavailable")
    }
    .joinToString(", ")

private fun planSegmentDescription(
  row: GanttDisplayRow.PlanTask,
  segment: PlanGanttLayout.Segment,
  formatter: TimeFormatter,
  isCritical: Boolean,
): String =
  buildString {
    append(row.layout.item.title)
    append(", Plan block ")
    append(formatter.mediumDay(segment.block.startAt))
    append(", ")
    append(formatter.range(segment.block.startAt, segment.block.endAt))
    append(", ${row.layout.item.progress} percent complete")
    if (isCritical) append(", critical path")
    if (segment.block.locked) append(", locked") else append(", flexible")
    when {
      segment.clippedAtStart && segment.clippedAtEnd -> append(", continues beyond both visible edges")
      segment.clippedAtStart -> append(", starts before the visible range")
      segment.clippedAtEnd -> append(", continues after the visible range")
    }
  }

private fun fixedItemDescription(item: GanttLayout.Item, formatter: TimeFormatter): String =
  buildString {
    val event = item.event
    append(event.title)
    append(", fixed calendar commitment, ")
    when {
      item.kind == GanttLayout.ItemKind.MILESTONE -> {
        append("milestone on ")
        append(formatter.mediumDay(event.startTime))
      }
      event.isAllDay -> {
        append("all day on ")
        append(formatter.mediumDay(event.startTime))
      }
      else -> {
        append(formatter.mediumDay(event.startTime))
        append(", ")
        append(formatter.range(event.startTime, event.endTime))
      }
    }
    when {
      item.clippedAtStart && item.clippedAtEnd -> append(", continues beyond both visible edges")
      item.clippedAtStart -> append(", starts before the visible range")
      item.clippedAtEnd -> append(", continues after the visible range")
    }
  }

private fun Modifier.ganttGrid(
  ticks: List<GanttLayout.Tick>,
  today: GanttLayout.Today?,
  grid: Color,
  todayTone: Color,
): Modifier =
  drawBehind {
    if (today != null) {
      val left = (today.startPosition * size.width).toFloat()
      val right = (today.endPosition * size.width).toFloat()
      drawRect(
        color = todayTone.copy(alpha = 0.07f),
        topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
        size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(0f), size.height),
      )
      today.nowPosition?.let { position ->
        val x = (position * size.width).toFloat()
        drawLine(
          todayTone,
          androidx.compose.ui.geometry.Offset(x, 0f),
          androidx.compose.ui.geometry.Offset(x, size.height),
          strokeWidth = 2f,
        )
      }
    }
    ticks.forEach { tick ->
      val x = (tick.position * size.width).toFloat()
      drawLine(
        grid,
        androidx.compose.ui.geometry.Offset(x, 0f),
        androidx.compose.ui.geometry.Offset(x, size.height),
      )
    }
  }

private fun normalizedOffset(position: Double, width: Dp): Dp = width * position.toFloat()

private fun nonWorkingBands(
  range: GanttLayout.VisibleRange,
  spec: WorkingCalendarSpec,
): List<NonWorkingBand> =
  GanttWorkingBands
    .nonWorkingIntervals(spec, range.startInclusiveMs, range.endExclusiveMs)
    .map { interval ->
      NonWorkingBand(
        startPosition = range.positionOf(interval.startAt),
        endPosition = range.positionOf(interval.endAt),
      )
    }

private fun Modifier.ganttNonWorkingBands(
  bands: List<NonWorkingBand>,
  tone: Color,
): Modifier =
  drawBehind {
    bands.forEach { band ->
      val left = (band.startPosition * size.width).toFloat()
      val right = (band.endPosition * size.width).toFloat()
      drawRect(
        color = tone.copy(alpha = 0.045f),
        topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
        size =
          androidx.compose.ui.geometry.Size(
            (right - left).coerceAtLeast(0f),
            size.height,
          ),
      )
    }
  }

/** Black or white is always AA-readable when selected on the WCAG luminance crossover. */
private fun readableBarContentColor(background: Color): Color =
  if (background.luminance() > BlackWhiteLuminanceCrossover) Color.Black else Color.White

private val AxisHeight = 48.dp
private val GroupHeight = 34.dp
private val FixedRowHeight = 58.dp
private val PlanLanePitch = 48.dp
private val PlanMinimumRowHeight = 80.dp
/**
 * A one-hour block is under 3 dp wide at the seven-day zoom, and a 4 dp minimum was a hairline
 * nobody could read as work. This is the narrowest bar that still looks like a bar; the exact
 * times stay in the label, the description and the move panel.
 */
private val MinimumLegibleBarWidth = 10.dp

/** The overview strip is a glance, not a canvas; it earns very little height. */
private val OverviewStripHeight = 10.dp

private val DirectManipulationHandleSize = 48.dp
private val DirectManipulationGripWidth = 6.dp
private val DirectManipulationAutoScrollStep = 24.dp
private const val NowRefreshIntervalMs = 30_000L
private const val BlackWhiteLuminanceCrossover = 0.179f


/**
 * Where the visible range sits inside everything the plan covers.
 *
 * A schedule map shows a window; without an overview a person cannot tell whether the window is
 * near the start of the work, at its end, or somewhere in a gap. Tapping jumps, and the same jumps
 * are available as labelled accessibility actions and from the existing previous/next controls, so
 * the strip stays an accelerator rather than the only way to travel.
 */
@Composable
private fun GanttOverviewStrip(
  overview: GanttOverview,
  formatter: TimeFormatter,
  rangeDays: Int,
  onJump: (Float) -> Unit,
  onStep: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val plannedTone = colors.primary.copy(alpha = 0.22f)
  val viewportTone = colors.primary
  val description =
    "Plan overview, ${formatter.mediumDay(overview.startMs)} to ${formatter.mediumDay(overview.endMs)}. " +
      "The visible ${rangeDays}-day range is marked."
  val actions =
    listOf(
      CustomAccessibilityAction("Jump to the start of the plan") {
        onJump(0f)
        true
      },
      CustomAccessibilityAction("Jump to the middle of the plan") {
        onJump(0.5f)
        true
      },
      CustomAccessibilityAction("Jump to the end of the plan") {
        onJump(1f)
        true
      },
      CustomAccessibilityAction("Move back one range") {
        onStep(-rangeDays)
        true
      },
      CustomAccessibilityAction("Move forward one range") {
        onStep(rangeDays)
        true
      },
    )
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .height(MinimumTouchTarget)
        .semantics {
          contentDescription = description
          customActions = actions
        }
        .testTag("gantt_overview"),
    contentAlignment = Alignment.CenterStart,
  ) {
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(OverviewStripHeight)
          .clip(RoundedCornerShape(Radius.mark))
          .background(colors.surfaceContainerHigh)
          .pointerInput(overview) {
            detectTapGestures { position ->
              val width = size.width.toFloat().takeIf { it > 0f } ?: return@detectTapGestures
              onJump((position.x / width).coerceIn(0f, 1f))
            }
          }
          .drawBehind {
            // Where work actually is, then the window a person is looking through.
            val planLeft = (overview.planStart * size.width).toFloat()
            val planRight = (overview.planEnd * size.width).toFloat()
            drawRect(
              color = plannedTone,
              topLeft = Offset(planLeft, 0f),
              size = Size((planRight - planLeft).coerceAtLeast(1f), size.height),
            )
            val viewLeft = (overview.viewportStart * size.width).toFloat()
            val viewRight = (overview.viewportEnd * size.width).toFloat()
            drawRect(
              color = viewportTone,
              topLeft = Offset(viewLeft, 0f),
              size = Size((viewRight - viewLeft).coerceAtLeast(2f), size.height),
            )
          }
    )
  }
}
