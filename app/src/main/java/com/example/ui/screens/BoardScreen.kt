package com.example.ui.screens

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Check
import com.example.ui.theme.InlineIconSize
import com.example.ui.theme.Radius
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.TimeFormatter
import com.example.data.model.PlanBoard
import com.example.data.model.PlanColumn
import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority
import com.example.ui.components.PropertyChip
import com.example.ui.components.RowIconButton
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.PlanBatchMovePolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plan-owned workflow Board. Calendar events deliberately do not enter these lanes: the Schedule
 * action opens the shared time spine where fixed commitments are rendered as a separate type.
 */
@Composable
fun BoardScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditTask: (PlanItem) -> Unit,
  onAddTask: (String?) -> Unit,
  onAddSubtask: (PlanItem) -> Unit,
  onOpenGantt: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val boards by viewModel.planBoards.collectAsStateWithLifecycle()
  val activeBoardId by viewModel.activePlanBoardId.collectAsStateWithLifecycle()
  val allColumns by viewModel.planColumns.collectAsStateWithLifecycle()
  val visibleColumnIds by viewModel.visibleBoardColumnIds.collectAsStateWithLifecycle()
  // An empty preset means every lane. A preset hides lanes from view; it never deletes them, and
  // the tasks inside a hidden lane are still counted and still moveable from the Outline.
  val columns =
    remember(allColumns, visibleColumnIds) {
      if (visibleColumnIds.isEmpty()) allColumns
      else allColumns.filter { it.id in visibleColumnIds }
    }
  val tasks by viewModel.planItems.collectAsStateWithLifecycle()
  val activeBoard = boards.firstOrNull { it.id == activeBoardId }
  val lanes =
    remember(activeBoardId, columns, tasks) {
      activeBoardId?.let { PlanBoardProjector.project(it, columns, tasks) }.orEmpty()
    }
  val destinations =
    remember(columns) { listOf(null to "Inbox") + columns.map { it.id to it.name } }
  val gutter = LocalWindowWidth.current.gutter
  val density = LocalDensity.current
  val laneListState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val laneBounds = remember { mutableStateMapOf<String, Rect>() }

  var prompt by rememberSaveable { mutableStateOf<Prompt?>(null) }
  var promptBusy by rememberSaveable { mutableStateOf(false) }
  var confirmDeleteBoard by rememberSaveable { mutableStateOf(false) }
  var confirmDeleteColumnId by rememberSaveable { mutableStateOf<String?>(null) }
  var selectionBoardId by rememberSaveable { mutableStateOf<String?>(null) }
  var selectedItemIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
  var pendingMove by remember { mutableStateOf<BoardMoveRequest?>(null) }
  var dragSession by remember { mutableStateOf<BoardDragSession?>(null) }
  var laneViewport by remember { mutableStateOf<Rect?>(null) }
  var autoScrollJob by remember { mutableStateOf<Job?>(null) }
  var batchMoveBusy by rememberSaveable { mutableStateOf(false) }
  val selectionMode = activeBoardId != null && selectionBoardId == activeBoardId
  val availableItemIds = remember(tasks) { tasks.mapTo(linkedSetOf(), PlanItem::id) }
  val selectedIds = remember(selectedItemIds, availableItemIds) {
    selectedItemIds.filterTo(linkedSetOf()) { it in availableItemIds }
  }
  val laneTargets =
    remember(lanes) {
      lanes
        .filter { it.kind != PlanBoardLaneKind.NEEDS_ROUTING }
        .associate { lane ->
          lane.key to BoardLaneDropTarget(columnId = lane.column?.id, label = lane.title)
        }
    }
  val draggedHierarchyIds =
    remember(tasks, dragSession?.requestedItemIds) {
      dragSession?.let { session ->
        PlanBatchMovePolicy.scope(tasks, session.requestedItemIds).hierarchyItemIds
      }.orEmpty()
    }

  fun targetAt(position: Offset): BoardLaneDropTarget? =
    laneTargets.entries.firstNotNullOfOrNull { (laneKey, target) ->
      laneBounds[laneKey]?.takeIf { bounds ->
        position.x in bounds.left..bounds.right && position.y in bounds.top..bounds.bottom
      }?.let { target }
    }

  fun autoScrollDelta(position: Offset): Float {
    val viewport = laneViewport ?: return 0f
    val edgeWidth = with(density) { 56.dp.toPx() }
    val maxStep = with(density) { 36.dp.toPx() }
    return BoardLaneAutoScrollPolicy.delta(
      pointerX = position.x,
      pointerY = position.y,
      viewportLeft = viewport.left,
      viewportTop = viewport.top,
      viewportRight = viewport.right,
      viewportBottom = viewport.bottom,
      edgeWidth = edgeWidth,
      maxStep = maxStep,
      canScrollBackward = laneListState.canScrollBackward,
      canScrollForward = laneListState.canScrollForward,
    )
  }

  fun startAutoScrollIfNeeded() {
    if (autoScrollJob?.isActive == true) return
    val position = dragSession?.pointerPosition ?: return
    if (autoScrollDelta(position) == 0f) return
    autoScrollJob =
      scope.launch {
        while (true) {
          val active = dragSession ?: break
          val delta = autoScrollDelta(active.pointerPosition)
          if (delta == 0f) break
          laneListState.scrollBy(delta)
          delay(16L)
          dragSession =
            dragSession?.let { current ->
              current.copy(target = targetAt(current.pointerPosition))
            }
        }
      }
  }

  fun updateDrag(position: Offset) {
    val session = dragSession ?: return
    dragSession = session.copy(target = targetAt(position), pointerPosition = position)
    if (autoScrollDelta(position) == 0f) {
      autoScrollJob?.cancel()
    } else {
      startAutoScrollIfNeeded()
    }
  }

  LaunchedEffect(activeBoardId, availableItemIds) {
    if (activeBoardId != null && selectionBoardId != null && selectionBoardId != activeBoardId) {
      selectionBoardId = null
      selectedItemIds = emptyList()
      pendingMove = null
    }
    if (dragSession?.liftedItemId?.let { it !in availableItemIds } == true) {
      autoScrollJob?.cancel()
      dragSession = null
    }
  }

  LaunchedEffect(laneTargets.keys) { laneBounds.keys.retainAll(laneTargets.keys) }

  // Leaving a selection is what Back means here; leaving the Board is not.
  BackHandler(enabled = selectionMode) {
    selectionBoardId = null
    selectedItemIds = emptyList()
    pendingMove = null
    dragSession = null
  }

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(contentPadding)
        .testTag("screen_plan_board")
  ) {
    PlanBoardHeader(
      boards = boards,
      activeBoard = activeBoard,
      taskCount = tasks.size,
      onSelectBoard = viewModel::setActivePlanBoard,
      onOpenGantt = onOpenGantt,
      allColumns = allColumns,
      visibleColumnIds = visibleColumnIds,
      onToggleColumn = viewModel::toggleBoardColumnVisible,
      onShowAllColumns = viewModel::showAllBoardColumns,
      selectionMode = selectionMode,
      onToggleSelectionMode = {
        if (selectionMode) {
          selectionBoardId = null
          selectedItemIds = emptyList()
          pendingMove = null
          dragSession = null
        } else {
          selectionBoardId = activeBoardId
        }
      },
      onNewBoard = { prompt = Prompt.NewBoard },
      onRenameBoard = { activeBoard?.let { prompt = Prompt.RenameBoard(it.name) } },
      onDeleteBoard = { confirmDeleteBoard = true },
      onAddColumn = { prompt = Prompt.NewColumn },
      modifier = Modifier.padding(horizontal = gutter),
    )
    if (selectionMode) {
      BoardSelectionBar(
        selectedCount = selectedIds.size,
        totalCount = tasks.size,
        destinations = destinations,
        busy = batchMoveBusy,
        onSelectAll = { selectedItemIds = tasks.map(PlanItem::id) },
        onClear = { selectedItemIds = emptyList() },
        onMoveTo = { id, label ->
          pendingMove =
            BoardMoveRequest(
              requestedItemIds = selectedIds,
              destination = BoardLaneDropTarget(id, label),
            )
        },
        onDone = {
          selectionBoardId = null
          selectedItemIds = emptyList()
          pendingMove = null
          dragSession = null
        },
        modifier = Modifier.padding(horizontal = gutter),
      )
    }
    dragSession?.let { session ->
      BoardDragStatus(
        liftedTitle = tasks.firstOrNull { it.id == session.liftedItemId }?.title ?: "Task",
        includedCount = draggedHierarchyIds.size,
        destination = session.target?.label,
        modifier = Modifier.padding(horizontal = gutter, vertical = Space.xs),
      )
    }
    Spacer(Modifier.height(Space.sm))

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val laneWidth = if (maxWidth < 520.dp) (maxWidth - gutter - Space.xl) else 320.dp
      LazyRow(
        state = laneListState,
        modifier =
          Modifier.fillMaxSize()
            .onGloballyPositioned { laneViewport = it.boundsInWindow() }
            .testTag("plan_board_lanes"),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
      ) {
        items(lanes, key = { it.key }) { lane ->
          val workflowIndex =
            when (lane.kind) {
              PlanBoardLaneKind.INBOX -> 0
              PlanBoardLaneKind.NEEDS_ROUTING -> -1
              PlanBoardLaneKind.COLUMN -> columns.indexOfFirst { it.id == lane.column?.id } + 1
            }
          val leftDestination =
            when {
              lane.kind == PlanBoardLaneKind.NEEDS_ROUTING -> destinations.lastOrNull()?.first
              workflowIndex > 0 -> destinations[workflowIndex - 1].first
              else -> null
            }
          val hasLeftDestination = lane.kind == PlanBoardLaneKind.NEEDS_ROUTING || workflowIndex > 0
          val rightDestination =
            destinations.getOrNull(workflowIndex + 1)?.first.takeIf { workflowIndex >= 0 }
          val hasRightDestination = workflowIndex >= 0 && workflowIndex + 1 < destinations.size

          PlanBoardColumn(
            lane = lane,
            formatter = formatter,
            destinations = destinations,
            hasLeftDestination = hasLeftDestination,
            leftDestination = leftDestination,
            hasRightDestination = hasRightDestination,
            rightDestination = rightDestination,
            onEditTask = onEditTask,
            onToggleComplete = viewModel::togglePlanItemComplete,
            onStageMove = { itemIds, destinationId, destinationLabel ->
              pendingMove =
                BoardMoveRequest(
                  requestedItemIds = itemIds.toCollection(linkedSetOf()),
                  destination = BoardLaneDropTarget(destinationId, destinationLabel),
                )
            },
            onAddTask = { onAddTask(lane.column?.id) },
            onAddSubtask = onAddSubtask,
            selectionMode = selectionMode,
            selectedItemIds = selectedIds,
            onToggleSelected = { itemId ->
              selectedItemIds =
                if (itemId in selectedIds) selectedItemIds - itemId
                else (selectedItemIds + itemId).distinct()
            },
            draggedHierarchyItemIds = draggedHierarchyIds,
            liftedItemId = dragSession?.liftedItemId,
            dropTarget = dragSession?.target == laneTargets[lane.key],
            onLaneBounds = { bounds -> laneBounds[lane.key] = bounds },
            onLaneDisposed = { laneBounds.remove(lane.key) },
            onDragStart = { itemId, position ->
              autoScrollJob?.cancel()
              val requestedIds =
                if (selectionMode && itemId in selectedIds && selectedIds.isNotEmpty()) {
                  selectedIds
                } else {
                  setOf(itemId)
                }
              dragSession =
                BoardDragSession(
                  liftedItemId = itemId,
                  requestedItemIds = requestedIds,
                  target = targetAt(position),
                  pointerPosition = position,
                )
              startAutoScrollIfNeeded()
            },
            onDrag = ::updateDrag,
            onDragCancel = {
              autoScrollJob?.cancel()
              dragSession = null
            },
            onDragEnd = {
              autoScrollJob?.cancel()
              val completedDrag = dragSession
              dragSession = null
              completedDrag?.target?.let { destination ->
                pendingMove =
                  BoardMoveRequest(
                    requestedItemIds = completedDrag.requestedItemIds,
                    destination = destination,
                  )
              }
            },
            onRename = { lane.column?.let { prompt = Prompt.RenameColumn(it.name) } },
            onDelete = { lane.column?.let { confirmDeleteColumnId = it.id } },
            canDelete = lane.column != null && columns.size > 1,
            modifier = Modifier.width(laneWidth).fillMaxHeight(),
          )
        }
      }
    }
  }

  val activePrompt = prompt
  if (activePrompt != null) {
    TextPromptDialog(
      title = activePrompt.title,
      initialValue = activePrompt.initialValue,
      confirmLabel = activePrompt.confirmLabel,
      busy = promptBusy,
      onDismiss = { if (!promptBusy) prompt = null },
      onConfirm = { value ->
        promptBusy = true
        val finish: (Boolean) -> Unit = { success ->
          promptBusy = false
          if (success) prompt = null
        }
        when (activePrompt) {
          Prompt.NewBoard -> viewModel.addBoard(value, finish)
          is Prompt.RenameBoard -> viewModel.renameBoard(activePrompt.currentName, value, finish)
          Prompt.NewColumn -> viewModel.addColumn(value, finish)
          is Prompt.RenameColumn -> viewModel.renameColumn(activePrompt.currentName, value, finish)
        }
      },
    )
  }

  if (confirmDeleteBoard && activeBoard != null) {
    AlertDialog(
      onDismissRequest = { confirmDeleteBoard = false },
      title = { Text("Delete \"${activeBoard.name}\"?") },
      text = {
        Text(
          "Calendar labels move to the first remaining Plan. Plan tasks must be moved or deleted first."
        )
      },
      confirmButton = {
        TextButton(
          enabled = !activeBoard.isDefault,
          onClick = {
            viewModel.deleteBoard(activeBoard.name)
            confirmDeleteBoard = false
          },
        ) {
          Text("Delete Plan", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmDeleteBoard = false }) { Text("Cancel") } },
    )
  }

  val columnToDelete = columns.firstOrNull { it.id == confirmDeleteColumnId }
  if (columnToDelete != null) {
    val fallback = columns.firstOrNull { it.id != columnToDelete.id }
    AlertDialog(
      onDismissRequest = { confirmDeleteColumnId = null },
      title = { Text("Delete \"${columnToDelete.name}\"?") },
      text = {
        Text(
          fallback?.let {
            "Tasks and calendar labels in this column move to \"${it.name}\". Nothing is deleted."
          } ?: "A Plan needs at least one workflow column."
        )
      },
      confirmButton = {
        TextButton(
          enabled = fallback != null,
          onClick = {
            viewModel.deleteColumn(columnToDelete.name)
            confirmDeleteColumnId = null
          },
        ) {
          Text("Delete column", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmDeleteColumnId = null }) { Text("Cancel") } },
    )
  }

  val moveDestination = pendingMove
  if (moveDestination != null) {
    val preview =
      remember(tasks, moveDestination.requestedItemIds, moveDestination.destination.columnId) {
        PlanBatchMovePolicy.plan(
          tasks,
          moveDestination.requestedItemIds,
          moveDestination.destination.columnId,
        )
      }
    val currentDestination =
      destinations.firstOrNull { it.first == moveDestination.destination.columnId }
    val destinationLabel = currentDestination?.second ?: moveDestination.destination.label
    val destinationAvailable = currentDestination != null
    val includedTitles =
      preview.includedHierarchyItemIds.mapNotNull { id ->
        tasks.firstOrNull { it.id == id }?.title
      }
    AlertDialog(
      onDismissRequest = { if (!batchMoveBusy) pendingMove = null },
      modifier = Modifier.testTag("board_move_preview"),
      title = { Text("Review move to $destinationLabel") },
      text = {
        Column {
          Text(
            "${moveDestination.requestedItemIds.size} selected · " +
              "${preview.hierarchyItemIds.size} included in the hierarchy-aware move."
          )
          if (includedTitles.isNotEmpty()) {
            Text(
              "Included descendants and linked tasks: ${includedTitles.take(4).joinToString()}" +
                if (includedTitles.size > 4) " and ${includedTitles.size - 4} more" else "",
              modifier = Modifier.padding(top = Space.sm),
            )
          }
          val warning =
            when {
              !destinationAvailable -> "That destination is no longer available."
              preview.validationMessage != null -> preview.validationMessage
              preview.isNoOp -> "Every selected hierarchy is already in $destinationLabel."
              else -> null
            }
          if (warning != null) {
            Text(
              warning,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(top = Space.sm),
            )
          } else {
            Text(
              "Apply saves one atomic Plan History step. If any task or destination changed, " +
                "nothing moves.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = Space.sm),
            )
          }
        }
      },
      confirmButton = {
        TextButton(
          enabled = destinationAvailable && preview.canApply && !batchMoveBusy,
          onClick = {
            batchMoveBusy = true
            viewModel.movePlanItemsAtomically(
              moveDestination.requestedItemIds,
              moveDestination.destination.columnId,
            ) { outcome ->
              batchMoveBusy = false
              pendingMove = null
              if (outcome.completed) selectedItemIds = emptyList()
            }
          },
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("board_batch_move_confirm"),
        ) {
          Text(if (batchMoveBusy) "Applying…" else "Apply")
        }
      },
      dismissButton = {
        TextButton(
          enabled = !batchMoveBusy,
          onClick = { pendingMove = null },
          modifier =
            Modifier.heightIn(min = MinimumTouchTarget).testTag("board_batch_move_cancel"),
        ) {
          Text("Cancel")
        }
      },
    )
  }
}

private data class BoardLaneDropTarget(val columnId: String?, val label: String)

private data class BoardMoveRequest(
  val requestedItemIds: Set<String>,
  val destination: BoardLaneDropTarget,
)

private data class BoardDragSession(
  val liftedItemId: String,
  val requestedItemIds: Set<String>,
  val target: BoardLaneDropTarget?,
  val pointerPosition: Offset,
)

@Composable
private fun BoardDragStatus(
  liftedTitle: String,
  includedCount: Int,
  destination: String?,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(Radius.block),
    color = MaterialTheme.colorScheme.primaryContainer,
    modifier = modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget).testTag("board_drag_status"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "LIFTED",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Text(
        " · $liftedTitle" +
          if (includedCount > 1) " + ${includedCount - 1} linked" else "",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        destination?.let { "Release over $it to review" } ?: "Drag across lanes",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 2,
      )
    }
  }
}

private sealed interface Prompt : java.io.Serializable {
  val title: String
  val initialValue: String
  val confirmLabel: String

  data object NewBoard : Prompt {
    override val title = "New Plan"
    override val initialValue = ""
    override val confirmLabel = "Create"
  }

  data class RenameBoard(val currentName: String) : Prompt {
    override val title = "Rename Plan"
    override val initialValue = currentName
    override val confirmLabel = "Rename"
  }

  data object NewColumn : Prompt {
    override val title = "New workflow column"
    override val initialValue = ""
    override val confirmLabel = "Create"
  }

  data class RenameColumn(val currentName: String) : Prompt {
    override val title = "Rename column"
    override val initialValue = currentName
    override val confirmLabel = "Rename"
  }
}

@Composable
private fun PlanBoardHeader(
  boards: List<PlanBoard>,
  activeBoard: PlanBoard?,
  taskCount: Int,
  onSelectBoard: (String) -> Unit,
  onOpenGantt: () -> Unit,
  allColumns: List<PlanColumn>,
  visibleColumnIds: Set<String>,
  onToggleColumn: (String) -> Unit,
  onShowAllColumns: () -> Unit,
  selectionMode: Boolean,
  onToggleSelectionMode: () -> Unit,
  onNewBoard: () -> Unit,
  onRenameBoard: () -> Unit,
  onDeleteBoard: () -> Unit,
  onAddColumn: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var boardMenu by remember { mutableStateOf(false) }
  var overflowMenu by remember { mutableStateOf(false) }
  var columnMenu by remember { mutableStateOf(false) }

  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.weight(1f)) {
      Surface(
        onClick = { if (boards.size > 1) boardMenu = true },
        enabled = boards.size > 1,
        shape = RoundedCornerShape(Radius.control),
        color = Color.Transparent,
        modifier = Modifier.testTag("board_selector"),
      ) {
        Row(
          modifier = Modifier.padding(vertical = Space.xs, horizontal = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
              text = activeBoard?.name ?: "Preparing Plan…",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              // One line, like every other summary under a title. Wrapped, it orphaned a word onto
              // a third line and pushed the lanes further down a screen that has none to spare.
              text = "$taskCount ${if (taskCount == 1) "task" else "tasks"} · flexible work",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (boards.size > 1) {
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose a Plan")
          }
        }
      }
      DropdownMenu(expanded = boardMenu, onDismissRequest = { boardMenu = false }) {
        boards.forEach { board ->
          DropdownMenuItem(
            text = { Text(board.name) },
            onClick = {
              boardMenu = false
              onSelectBoard(board.id)
            },
          )
        }
      }
    }
    TextButton(
      onClick = onToggleSelectionMode,
      enabled = activeBoard != null,
      modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("board_select"),
    ) {
      Text(if (selectionMode) "Done" else "Select")
    }
    // Which lanes are shown is a view option, and those all live in the Plan's View options now
    // rather than behind a second overflow menu on the surface itself.
  }
}

@Composable
private fun BoardSelectionBar(
  selectedCount: Int,
  totalCount: Int,
  destinations: List<Pair<String?, String>>,
  busy: Boolean,
  onSelectAll: () -> Unit,
  onClear: () -> Unit,
  onMoveTo: (String?, String) -> Unit,
  onDone: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var moveMenu by remember { mutableStateOf(false) }
  Surface(
    shape = RoundedCornerShape(Radius.block),
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = modifier.fillMaxWidth().testTag("board_selection_bar"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "$selectedCount selected",
        fontWeight = FontWeight.SemiBold,
        modifier =
          Modifier.padding(start = Space.sm, end = Space.xs).semantics {
            stateDescription = "$selectedCount of $totalCount tasks selected"
          },
      )
      LazyRow(
        contentPadding = PaddingValues(end = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f).heightIn(min = MinimumTouchTarget),
      ) {
        item("select_all") {
          TextButton(
            onClick = onSelectAll,
            enabled = !busy && totalCount > 0 && selectedCount < totalCount,
            modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("board_select_all"),
          ) {
            Text("Select all")
          }
        }
        item("clear") {
          TextButton(
            onClick = onClear,
            enabled = !busy && selectedCount > 0,
            modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("board_clear_selection"),
          ) {
            Text("Clear")
          }
        }
        item("move") {
          Box {
            TextButton(
              onClick = { moveMenu = true },
              enabled = !busy && selectedCount > 0,
              modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("board_batch_move"),
            ) {
              Text("Move to…")
            }
            DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
              destinations.forEach { (id, label) ->
                DropdownMenuItem(
                  text = { Text(label) },
                  modifier = Modifier.testTag("board_move_destination_${id ?: "inbox"}"),
                  onClick = {
                    moveMenu = false
                    onMoveTo(id, label)
                  },
                )
              }
            }
          }
        }
        item("done") {
          TextButton(
            onClick = onDone,
            enabled = !busy,
            modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("board_selection_done"),
          ) {
            Text("Done")
          }
        }
      }
    }
  }
}

@Composable
private fun PlanBoardColumn(
  lane: PlanBoardLane,
  formatter: TimeFormatter,
  destinations: List<Pair<String?, String>>,
  hasLeftDestination: Boolean,
  leftDestination: String?,
  hasRightDestination: Boolean,
  rightDestination: String?,
  onEditTask: (PlanItem) -> Unit,
  onToggleComplete: (PlanItem) -> Unit,
  onStageMove: (Collection<String>, String?, String) -> Unit,
  onAddTask: () -> Unit,
  onAddSubtask: (PlanItem) -> Unit,
  selectionMode: Boolean,
  selectedItemIds: Set<String>,
  onToggleSelected: (String) -> Unit,
  draggedHierarchyItemIds: Set<String>,
  liftedItemId: String?,
  dropTarget: Boolean,
  onLaneBounds: (Rect) -> Unit,
  onLaneDisposed: () -> Unit,
  onDragStart: (String, Offset) -> Unit,
  onDrag: (Offset) -> Unit,
  onDragCancel: () -> Unit,
  onDragEnd: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  canDelete: Boolean,
  modifier: Modifier = Modifier,
) {
  var menu by remember { mutableStateOf(false) }
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme
  DisposableEffect(lane.key) { onDispose(onLaneDisposed) }

  Column(
    modifier =
      modifier
        .onGloballyPositioned { onLaneBounds(it.boundsInWindow()) }
        .then(
          if (dropTarget) {
            Modifier.border(
                width = 2.dp,
                color = colors.primary,
                shape = RoundedCornerShape(Radius.block),
              )
              .background(colors.primaryContainer.copy(alpha = 0.18f), RoundedCornerShape(Radius.block))
          } else {
            Modifier
          }
        )
        .semantics { if (dropTarget) stateDescription = "Move preview destination ${lane.title}" }
        .testTag("plan_board_lane_${lane.key}")
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().height(d.sectionHeaderHeight),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = lane.title.uppercase(),
        fontSize = d.label,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { heading() },
      )
      Spacer(Modifier.width(Space.sm))
      Text(lane.rows.size.toString(), fontSize = d.label, color = colors.onSurfaceVariant)
      Spacer(Modifier.weight(1f))
      if (lane.kind != PlanBoardLaneKind.NEEDS_ROUTING) {
        RowIconButton(
          icon = Icons.Default.Add,
          contentDescription = "Add task to ${lane.title}",
          onClick = onAddTask,
          modifier = Modifier.testTag("plan_board_add"),
        )
      }
      if (lane.column != null) {
        Box {
          RowIconButton(
            icon = Icons.Default.MoreVert,
            contentDescription = "${lane.title} column options",
            onClick = { menu = true },
          )
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
              text = { Text("Rename") },
              onClick = {
                menu = false
                onRename()
              },
            )
            DropdownMenuItem(
              text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
              enabled = canDelete,
              onClick = {
                menu = false
                onDelete()
              },
            )
          }
        }
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
    Spacer(Modifier.height(Space.xs))

    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
      if (lane.rows.isEmpty()) {
        item("empty") {
          Text(
            text =
              if (lane.kind == PlanBoardLaneKind.INBOX) "Captured tasks wait here until you route them."
              else "No tasks in ${lane.title}.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Space.lg),
          )
        }
      }
      items(lane.rows, key = { it.item.id }) { row ->
        PlanBoardCard(
          row = row,
          formatter = formatter,
          destinations = destinations,
          hasLeftDestination = hasLeftDestination,
          leftDestination = leftDestination,
          hasRightDestination = hasRightDestination,
          rightDestination = rightDestination,
          onEdit = { onEditTask(row.item) },
          onToggleComplete = { onToggleComplete(row.item) },
          onStageMove = { destination, label ->
            val request =
              if (selectionMode && row.item.id in selectedItemIds) selectedItemIds
              else setOf(row.item.id)
            onStageMove(request, destination, label)
          },
          onAddSubtask = { onAddSubtask(row.item) },
          selectionMode = selectionMode,
          selected = row.item.id in selectedItemIds,
          onToggleSelected = { onToggleSelected(row.item.id) },
          dragSelected = row.item.id in draggedHierarchyItemIds,
          lifted = row.item.id == liftedItemId,
          onDragStart = { position -> onDragStart(row.item.id, position) },
          onDrag = onDrag,
          onDragCancel = onDragCancel,
          onDragEnd = onDragEnd,
        )
      }
      item("bottom") { Spacer(Modifier.height(Space.lg)) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanBoardCard(
  row: OutlineRow,
  formatter: TimeFormatter,
  destinations: List<Pair<String?, String>>,
  hasLeftDestination: Boolean,
  leftDestination: String?,
  hasRightDestination: Boolean,
  rightDestination: String?,
  onEdit: () -> Unit,
  onToggleComplete: () -> Unit,
  onStageMove: (String?, String) -> Unit,
  onAddSubtask: () -> Unit,
  selectionMode: Boolean,
  selected: Boolean,
  onToggleSelected: () -> Unit,
  dragSelected: Boolean,
  lifted: Boolean,
  onDragStart: (Offset) -> Unit,
  onDrag: (Offset) -> Unit,
  onDragCancel: () -> Unit,
  onDragEnd: () -> Unit,
) {
  val item = row.item
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme
  val status = LocalStatusColors.current
  var menu by remember { mutableStateOf(false) }
  var cardTopLeft by remember(item.id) { mutableStateOf(Offset.Zero) }
  val leftDestinationLabel =
    destinations.firstOrNull { it.first == leftDestination }?.second ?: "previous lane"
  val rightDestinationLabel =
    destinations.firstOrNull { it.first == rightDestination }?.second ?: "next lane"
  val accessibilityMoveActions =
    if (selectionMode) {
      emptyList()
    } else {
      destinations
        .filterNot { (id, _) -> id == item.columnId }
        .map { (id, label) ->
          CustomAccessibilityAction("Review move to $label") {
            onStageMove(id, label)
            true
          }
        }
    }
  val tone =
    when (item.priority) {
      PlanPriority.URGENT -> status.urgent
      PlanPriority.HIGH -> status.deadline
      else -> if (item.columnId == null) colors.outline else colors.primary
    }
  val state =
      buildList {
        if (selectionMode) add(if (selected) "Selected" else "Not selected")
        if (dragSelected) add(if (lifted) "Lifted for move" else "Included in lifted hierarchy")
        add(if (item.progress == 100) "Completed" else "${item.progress} percent complete")
        add("${item.priority} priority")
        add("Hierarchy level ${row.depth + 1}")
        if (row.hasChildren) add("Moving workflow also moves this task group")
        if (row.isOrphan) add("Parent unavailable")
        if (item.isMilestone) add("Milestone")
      }
      .joinToString(", ")

  Surface(
    onClick = if (selectionMode) onToggleSelected else onEdit,
    shape = RoundedCornerShape(Radius.block),
    color =
      when {
        lifted -> colors.primaryContainer
        dragSelected || (selectionMode && selected) -> colors.secondaryContainer
        else -> colors.surfaceContainerLow
      },
    tonalElevation = if (lifted) 6.dp else 0.dp,
    shadowElevation = if (lifted) 5.dp else 0.dp,
    modifier =
      Modifier.fillMaxWidth()
        .onGloballyPositioned { cardTopLeft = it.boundsInWindow().topLeft }
        .graphicsLayer {
          scaleX = if (lifted) 1.02f else 1f
          scaleY = if (lifted) 1.02f else 1f
        }
        .then(
          if (lifted) Modifier.border(2.dp, colors.primary, RoundedCornerShape(Radius.block))
          else Modifier
        )
        .pointerInput(item.id, selectionMode, selected) {
          detectDragGesturesAfterLongPress(
            onDragStart = { localPosition -> onDragStart(cardTopLeft + localPosition) },
            onDrag = { change, _ ->
              onDrag(cardTopLeft + change.position)
              change.consume()
            },
            onDragCancel = onDragCancel,
            onDragEnd = onDragEnd,
          )
        }
        .onPreviewKeyEvent { event ->
          // Alt+Arrow has to be claimed on the way down as well as acted on when released.
          // Letting the key-down fall through hands it to focus traversal, which moves focus off
          // the card — and then the key-up this was waiting for is delivered somewhere else, so
          // the shortcut silently does nothing but jump the focus ring.
          val destination =
            when {
              selectionMode || !event.isAltPressed -> null
              event.key == Key.DirectionLeft && hasLeftDestination ->
                leftDestination to leftDestinationLabel
              event.key == Key.DirectionRight && hasRightDestination ->
                rightDestination to rightDestinationLabel
              else -> null
            }
          when {
            destination == null -> false
            event.type == KeyEventType.KeyDown -> true
            event.type == KeyEventType.KeyUp -> {
              onStageMove(destination.first, destination.second)
              true
            }
            else -> false
          }
        }
        .focusable()
        .semantics {
          stateDescription = state
          customActions = accessibilityMoveActions
        }
        .testTag("plan_board_item"),
  ) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      Box(
        modifier =
          Modifier.width(3.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = Radius.block, bottomStart = Radius.block))
            .background(tone)
      )
      Spacer(Modifier.width((row.depth * 12).dp))
      Checkbox(
        checked = if (selectionMode) selected else item.progress == 100,
        onCheckedChange = {
          if (selectionMode) onToggleSelected() else onToggleComplete()
        },
        enabled = true,
        modifier =
          Modifier.testTag(if (selectionMode) "board_select_${item.id}" else "board_complete_${item.id}")
            .semantics {
              contentDescription =
                if (selectionMode) {
                  if (selected) "Deselect ${item.title}" else "Select ${item.title}"
                } else if (item.progress == 100) {
                  "Mark ${item.title} incomplete"
                } else {
                  "Mark ${item.title} complete"
                }
            },
      )
      Column(modifier = Modifier.weight(1f).padding(top = Space.sm, bottom = Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (item.isMilestone) {
            Box(
              Modifier.padding(end = Space.xs)
                .size(9.dp)
                .rotate(45f)
                .background(colors.primary, RoundedCornerShape(Radius.mark))
            )
          }
          Text(
            text = item.title,
            fontSize = d.title,
            fontWeight = if (row.hasChildren) FontWeight.SemiBold else FontWeight.Medium,
            color = colors.onSurface.copy(alpha = if (item.progress == 100) 0.58f else 1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (!selectionMode && row.depth == 0) {
            PlanBoardItemMenu(
              item = item,
              hasChildren = row.hasChildren,
              destinations = destinations,
              expanded = menu,
              onExpandedChange = { menu = it },
              onEdit = onEdit,
              onAddSubtask = onAddSubtask,
              onStageMove = onStageMove,
            )
          }
        }
        if (item.progress in 1..99) {
          Spacer(Modifier.height(Space.xs))
          LinearProgressIndicator(
            progress = { item.progress / 100f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = status.positive,
            trackColor = colors.surfaceContainerHighest,
          )
        }
        val chips = boardMetadata(item, formatter)
        if (chips.isNotEmpty()) {
          Spacer(Modifier.height(Space.xs))
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
          ) {
            chips.forEach { (label, chipTone) -> PropertyChip(label, chipTone) }
          }
        }
        if (!selectionMode && row.depth == 0) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (hasLeftDestination) {
              RowIconButton(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                "Move ${item.title}${if (row.hasChildren) " task group" else ""} left",
                { onStageMove(leftDestination, leftDestinationLabel) },
                modifier = Modifier.testTag("plan_board_move_left"),
              )
            }
            if (hasRightDestination) {
              RowIconButton(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                "Move ${item.title}${if (row.hasChildren) " task group" else ""} right",
                { onStageMove(rightDestination, rightDestinationLabel) },
                modifier = Modifier.testTag("plan_board_move_right"),
              )
            }
          }
        }
      }
      if (!selectionMode && row.depth > 0) {
        RowIconButton(
          Icons.Default.Edit,
          "Edit ${item.title}; move its hierarchy from the root task",
          onEdit,
        )
      }
    }
  }
}

@Composable
private fun PlanBoardItemMenu(
  item: PlanItem,
  hasChildren: Boolean,
  destinations: List<Pair<String?, String>>,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  onEdit: () -> Unit,
  onAddSubtask: () -> Unit,
  onStageMove: (String?, String) -> Unit,
) {
  Box {
    RowIconButton(
      Icons.Default.MoreVert,
      "More actions for ${item.title}",
      { onExpandedChange(true) },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
      DropdownMenuItem(
        text = { Text("Edit details") },
        onClick = {
          onExpandedChange(false)
          onEdit()
        },
      )
      DropdownMenuItem(
        text = { Text("Add subtask") },
        onClick = {
          onExpandedChange(false)
          onAddSubtask()
        },
      )
      destinations
        .filterNot { (id, _) -> id == item.columnId }
        .forEach { (id, label) ->
          DropdownMenuItem(
            text = {
              Text("Move${if (hasChildren) " task group" else ""} to $label")
            },
            onClick = {
              onExpandedChange(false)
              onStageMove(id, label)
            },
          )
        }
    }
  }
}

@Composable
private fun boardMetadata(item: PlanItem, formatter: TimeFormatter): List<Pair<String, Color>> {
  val colors = MaterialTheme.colorScheme
  val status = LocalStatusColors.current
  return buildList {
    item.effortMinutes?.let { minutes ->
      val effort =
        when {
          minutes < 60 -> "${minutes}m"
          minutes % 60 == 0 -> "${minutes / 60}h"
          else -> "${minutes / 60}h ${minutes % 60}m"
        }
      add(effort to colors.onSurfaceVariant)
    }
    item.dueAt?.let { add("Due ${formatter.relativeDay(it)}" to status.deadline) }
    if (item.isMilestone) add("Milestone" to colors.primary)
    if (item.progress in 1..99) add("${item.progress}%" to status.positive)
    when (item.priority) {
      PlanPriority.URGENT -> add("Urgent" to status.urgent)
      PlanPriority.HIGH -> add("High" to status.deadline)
    }
    item.owner?.takeIf(String::isNotBlank)?.let { add(it to colors.onSurfaceVariant) }
  }
}

@Composable
private fun TextPromptDialog(
  title: String,
  initialValue: String,
  confirmLabel: String,
  busy: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        enabled = !busy,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("prompt_input"),
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(value) }, enabled = !busy && value.isNotBlank()) {
        Text(if (busy) "Saving…" else confirmLabel)
      }
    },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
  )
}
