package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import com.example.ui.theme.InlineIconSize
import com.example.ui.theme.Radius
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import com.example.ui.components.RowIconButton
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.components.EmptyState
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.viewmodel.BriefingViewModel

/**
 * Flexible work as a dense planning ledger. The left rail communicates readiness:
 * a hollow marker is unplanned work, a filled marker is routed, and a diamond is a milestone.
 */
@Composable
fun PlanOutlineScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onAddTask: (String?) -> Unit,
  onEditTask: (PlanItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val boards by viewModel.planBoards.collectAsStateWithLifecycle()
  val activeBoardId by viewModel.activePlanBoardId.collectAsStateWithLifecycle()
  val columns by viewModel.planColumns.collectAsStateWithLifecycle()
  val tasks by viewModel.planItems.collectAsStateWithLifecycle()
  val sort by viewModel.outlineSort.collectAsStateWithLifecycle()
  val grouping by viewModel.outlineGrouping.collectAsStateWithLifecycle()
  val hideCompleted by viewModel.outlineHideCompleted.collectAsStateWithLifecycle()
  val query by viewModel.outlineQuery.collectAsStateWithLifecycle()
  val collapsedIds by viewModel.outlineCollapsedIds.collectAsStateWithLifecycle()
  val presentation =
    remember(sort, grouping, hideCompleted, query, collapsedIds) {
      OutlinePresentation(
        sort = sort,
        grouping = grouping,
        hideCompleted = hideCompleted,
        query = query,
        collapsedItemIds = collapsedIds,
      )
    }
  var selectedTaskIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
  val selectionMode = selectedTaskIds.isNotEmpty()
  val availableIds = remember(tasks) { tasks.mapTo(linkedSetOf(), PlanItem::id) }
  val selected = remember(selectedTaskIds, availableIds) {
    selectedTaskIds.filterTo(linkedSetOf()) { it in availableIds }
  }
  // Back leaves a selection before it leaves the screen, exactly as it does on Board.
  BackHandler(enabled = selectionMode) { selectedTaskIds = emptyList() }

  val width = LocalWindowWidth.current
  val gutter = width.gutter
  val inbox = remember(tasks) { tasks.filter { it.columnId == null } }
  val availableColumnIds = remember(columns) { columns.mapTo(mutableSetOf()) { it.id } }
  val needsRouting =
    remember(tasks, availableColumnIds) {
      tasks.filter { item -> item.columnId != null && item.columnId !in availableColumnIds }
    }

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(contentPadding)
        .padding(horizontal = gutter)
        .testTag("screen_plan_outline")
  ) {
    PlanCatalogHeader(
      boards = boards,
      activeBoardId = activeBoardId,
      taskCount = tasks.size,
      effortMinutes = tasks.mapNotNull { it.effortMinutes }.sum(),
      onSelectBoard = viewModel::setActivePlanBoard,
    )
    if (selectionMode) {
      OutlineSelectionBar(
        selectedCount = selected.size,
        columns = columns,
        onMove = { columnId ->
          viewModel.movePlanItemsAtomically(selected, columnId) { outcome ->
            if (outcome.movedTaskCount > 0) selectedTaskIds = emptyList()
          }
        },
        onClear = { selectedTaskIds = emptyList() },
      )
    }
    // Order, grouping and what is hidden live in the header's View options. They are decisions a
    // person makes occasionally and reads never, so they no longer cost a band above every task.

    if (!width.supportsMultiPane) {
      CompactOutline(
        inbox = inbox,
        needsRouting = needsRouting,
        columns = columns,
        tasks = tasks,
        presentation = presentation,
        onToggleCollapsed = viewModel::toggleOutlineCollapsed,
        selectedIds = selected,
        onToggleSelected = { id ->
          selectedTaskIds = if (id in selectedTaskIds) selectedTaskIds - id else selectedTaskIds + id
        },
        formatter = formatter,
        onAddTask = onAddTask,
        onEditTask = onEditTask,
        onToggleComplete = viewModel::togglePlanItemComplete,
        modifier = Modifier.weight(1f),
      )
    } else {
      Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(Space.xl),
      ) {
        OutlinePane(
          title = "Inbox",
          count = inbox.size,
          emptyText = "Captured tasks wait here until you route them.",
          rows = PlanOutlineProjector.project(inbox, presentation),
          onToggleCollapsed = viewModel::toggleOutlineCollapsed,
          selectedIds = selected,
          onToggleSelected = { id ->
            selectedTaskIds = if (id in selectedTaskIds) selectedTaskIds - id else selectedTaskIds + id
          },
          formatter = formatter,
          onAddTask = { onAddTask(null) },
          onEditTask = onEditTask,
          onToggleComplete = viewModel::togglePlanItemComplete,
          modifier = Modifier.width(340.dp).fillMaxHeight().testTag("plan_inbox"),
        )
        WorkflowPane(
          columns = columns,
          tasks = tasks,
          needsRouting = needsRouting,
          presentation = presentation,
          onToggleCollapsed = viewModel::toggleOutlineCollapsed,
          selectedIds = selected,
          onToggleSelected = { id ->
            selectedTaskIds = if (id in selectedTaskIds) selectedTaskIds - id else selectedTaskIds + id
          },
          formatter = formatter,
          onAddTask = onAddTask,
          onEditTask = onEditTask,
          onToggleComplete = viewModel::togglePlanItemComplete,
          modifier = Modifier.weight(1f).fillMaxHeight(),
        )
      }
    }
  }
}

@Composable
private fun PlanCatalogHeader(
  boards: List<PlanBoard>,
  activeBoardId: String?,
  taskCount: Int,
  effortMinutes: Int,
  onSelectBoard: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val active = boards.firstOrNull { it.id == activeBoardId }
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.weight(1f)) {
      Surface(
        onClick = { if (boards.size > 1) expanded = true },
        enabled = boards.size > 1,
        shape = RoundedCornerShape(Radius.control),
        color = Color.Transparent,
      ) {
        Column(modifier = Modifier.padding(vertical = Space.xs, horizontal = 2.dp)) {
          Text(
            text = active?.name ?: "Preparing Plan…",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = planSummary(taskCount, effortMinutes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        boards.forEach { board ->
          DropdownMenuItem(
            text = { Text(board.name) },
            onClick = {
              expanded = false
              onSelectBoard(board.id)
            },
          )
        }
      }
    }
  }
}

private fun planSummary(taskCount: Int, effortMinutes: Int): String {
  val effort =
    when {
      effortMinutes <= 0 -> "no effort estimated"
      effortMinutes < 60 -> "${effortMinutes}m estimated"
      effortMinutes % 60 == 0 -> "${effortMinutes / 60}h estimated"
      else -> "${effortMinutes / 60}h ${effortMinutes % 60}m estimated"
    }
  return "$taskCount ${if (taskCount == 1) "task" else "tasks"} · $effort"
}

@Composable
private fun CompactOutline(
  inbox: List<PlanItem>,
  needsRouting: List<PlanItem>,
  columns: List<PlanColumn>,
  tasks: List<PlanItem>,
  presentation: OutlinePresentation,
  onToggleCollapsed: (String) -> Unit,
  selectedIds: Set<String>,
  onToggleSelected: (String) -> Unit,
  formatter: TimeFormatter,
  onAddTask: (String?) -> Unit,
  onEditTask: (PlanItem) -> Unit,
  onToggleComplete: (PlanItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val grouped =
    if (presentation.grouping == OutlineGrouping.SECTION) {
      emptyList()
    } else {
      PlanOutlineProjector.group(
        PlanOutlineProjector.project(tasks, presentation),
        presentation.grouping,
        System.currentTimeMillis(),
      )
    }
  if (grouped.isNotEmpty()) {
    // Banding replaces sections rather than nesting inside them: one heading per row, always.
    LazyColumn(modifier = modifier.fillMaxWidth().testTag("outline_grouped")) {
      grouped.forEach { band ->
        item("band_${band.label}") {
          Spacer(Modifier.height(Space.md))
          PlanSectionHeader(band.label, band.rows.size, onAdd = null)
        }
        items(band.rows, key = { "${band.label}_${it.item.id}" }) { row ->
          PlanTaskRow(row, formatter, onEditTask, onToggleComplete, onToggleCollapsed, selectedIds, onToggleSelected)
        }
      }
      item("outline_bottom") { Spacer(Modifier.height(Space.xl)) }
    }
    return
  }
  LazyColumn(modifier = modifier.fillMaxWidth()) {
    item("inbox_header") {
      PlanSectionHeader("Inbox", inbox.size, onAdd = { onAddTask(null) })
    }
    val inboxRows = PlanOutlineProjector.project(inbox, presentation)
    if (inboxRows.isEmpty()) {
      item("inbox_empty") {
        EmptyState(
          headline = "No tasks yet.",
          supporting =
            "Capture what you have to do here first. Nothing needs a time until you want one.",
          actionLabel = "Add a task",
          onAction = { onAddTask(null) },
          tag = "outline_inbox_empty",
        )
      }
    } else {
      items(inboxRows, key = { "inbox_${it.item.id}" }) { row ->
        PlanTaskRow(row, formatter, onEditTask, onToggleComplete, onToggleCollapsed, selectedIds, onToggleSelected)
      }
    }

    columns.forEach { column ->
      val rows = PlanOutlineProjector.project(tasks.filter { it.columnId == column.id }, presentation)
      item("column_header_${column.id}") {
        Spacer(Modifier.height(Space.md))
        PlanSectionHeader(column.name, rows.size, onAdd = { onAddTask(column.id) })
      }
      if (rows.isEmpty()) {
        item("column_empty_${column.id}") { EmptyPlanText("No tasks in ${column.name}.") }
      } else {
        items(rows, key = { "${column.id}_${it.item.id}" }) { row ->
          PlanTaskRow(row, formatter, onEditTask, onToggleComplete, onToggleCollapsed, selectedIds, onToggleSelected)
        }
      }
    }
    if (needsRouting.isNotEmpty()) {
      val rows = PlanOutlineProjector.project(needsRouting, presentation)
      item("needs_routing_header") {
        Spacer(Modifier.height(Space.md))
        PlanSectionHeader("Needs routing", rows.size, onAdd = null)
      }
      items(rows, key = { "needs_routing_${it.item.id}" }) { row ->
        PlanTaskRow(row, formatter, onEditTask, onToggleComplete, onToggleCollapsed, selectedIds, onToggleSelected)
      }
    }
    item("outline_bottom") { Spacer(Modifier.height(Space.xl)) }
  }
}

@Composable
private fun OutlinePane(
  title: String,
  count: Int,
  emptyText: String,
  rows: List<OutlineRow>,
  onToggleCollapsed: (String) -> Unit,
  selectedIds: Set<String>,
  onToggleSelected: (String) -> Unit,
  formatter: TimeFormatter,
  onAddTask: () -> Unit,
  onEditTask: (PlanItem) -> Unit,
  onToggleComplete: (PlanItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(modifier = modifier) {
    item("header") { PlanSectionHeader(title, count, onAddTask) }
    if (rows.isEmpty()) item("empty") { EmptyPlanText(emptyText) }
    else {
      items(rows, key = { it.item.id }) { row ->
        PlanTaskRow(row, formatter, onEditTask, onToggleComplete, onToggleCollapsed, selectedIds, onToggleSelected)
      }
    }
  }
}

@Composable
private fun WorkflowPane(
  columns: List<PlanColumn>,
  tasks: List<PlanItem>,
  needsRouting: List<PlanItem>,
  presentation: OutlinePresentation,
  onToggleCollapsed: (String) -> Unit,
  selectedIds: Set<String>,
  onToggleSelected: (String) -> Unit,
  formatter: TimeFormatter,
  onAddTask: (String?) -> Unit,
  onEditTask: (PlanItem) -> Unit,
  onToggleComplete: (PlanItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(modifier = modifier) {
    if (columns.isEmpty()) {
      item("no_columns") { EmptyPlanText("Workflow columns are still being prepared.") }
    }
    columns.forEachIndexed { index, column ->
      val rows = PlanOutlineProjector.project(tasks.filter { it.columnId == column.id }, presentation)
      item("header_${column.id}") {
        if (index > 0) Spacer(Modifier.height(Space.md))
        PlanSectionHeader(column.name, rows.size, onAdd = { onAddTask(column.id) })
      }
      if (rows.isEmpty()) {
        item("empty_${column.id}") { EmptyPlanText("No tasks in ${column.name}.") }
      } else {
        items(rows, key = { "${column.id}_${it.item.id}" }) { row ->
          PlanTaskRow(row, formatter, onEditTask, onToggleComplete, onToggleCollapsed, selectedIds, onToggleSelected)
        }
      }
    }
    if (needsRouting.isNotEmpty()) {
      val rows = PlanOutlineProjector.project(needsRouting, presentation)
      item("needs_routing_header") {
        Spacer(Modifier.height(Space.md))
        PlanSectionHeader("Needs routing", rows.size, onAdd = null)
      }
      items(rows, key = { "needs_routing_${it.item.id}" }) { row ->
        PlanTaskRow(row, formatter, onEditTask, onToggleComplete, onToggleCollapsed, selectedIds, onToggleSelected)
      }
    }
  }
}

@Composable
private fun PlanSectionHeader(title: String, count: Int, onAdd: (() -> Unit)?) {
  val d = LocalDensityTokens.current
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title.uppercase(),
      fontSize = d.label,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.9.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.width(Space.sm))
    Text(text = count.toString(), fontSize = d.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.weight(1f))
    if (onAdd != null) {
      TextButton(
        onClick = onAdd,
        modifier =
          Modifier.heightIn(min = MinimumTouchTarget).semantics {
            contentDescription = "Add task to $title"
          },
      ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(InlineIconSize))
        Spacer(Modifier.width(Space.xs))
        Text("Task")
      }
    }
  }
  Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun EmptyPlanText(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(vertical = Space.lg),
  )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PlanTaskRow(
  row: OutlineRow,
  formatter: TimeFormatter,
  onEdit: (PlanItem) -> Unit,
  onToggleComplete: (PlanItem) -> Unit,
  onToggleCollapsed: (String) -> Unit,
  selectedIds: Set<String>,
  onToggleSelected: (String) -> Unit,
) {
  val item = row.item
  val isSelected = item.id in selectedIds
  val selectionActive = selectedIds.isNotEmpty()
  val d = LocalDensityTokens.current
  val colors = MaterialTheme.colorScheme
  val metadata = buildTaskMetadata(item, formatter)
  val state =
    buildList {
        add(if (item.progress == 100) "Completed" else "${item.progress} percent complete")
        add("${item.priority} priority")
        add("Hierarchy level ${row.depth + 1}")
        if (row.hasChildren) add("Has subtasks")
        if (item.isMilestone) add("Milestone")
        add(if (item.columnId == null) "In Plan Inbox" else "Routed to workflow")
        if (item.locked) add("Locked")
        item.owner?.takeIf(String::isNotBlank)?.let { add("Owned by $it") }
        item.dueAt?.let { add("Due ${formatter.relativeDay(it)}") }
        if (row.isOrphan) add("Parent unavailable")
        if (row.isCollapsed) add("${row.hiddenDescendants} subtasks hidden")
        if (isSelected) add("Selected")
      }
      .joinToString(", ")

  val selectAction =
    CustomAccessibilityAction(if (isSelected) "Clear selection" else "Select this task") {
      onToggleSelected(item.id)
      true
    }

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .height(IntrinsicSize.Min)
        .heightIn(min = MinimumTouchTarget)
        .combinedClickable(
          role = Role.Button,
          onClickLabel = if (selectionActive) "Toggle selection" else "Edit task",
          onClick = { if (selectionActive) onToggleSelected(item.id) else onEdit(item) },
          onLongClickLabel = "Select this task",
          onLongClick = { onToggleSelected(item.id) },
        )
        .semantics {
          stateDescription = state
          customActions = listOf(selectAction)
        }
        .testTag("plan_item"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (selectionActive) {
      Checkbox(
        checked = isSelected,
        onCheckedChange = { onToggleSelected(item.id) },
        modifier = Modifier.testTag("plan_item_select"),
      )
    }
    if (row.hasChildren) {
      // Hierarchy was indented but never foldable: a visible control, not only an indent.
      RowIconButton(
        icon =
          if (row.isCollapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight
          else Icons.Filled.KeyboardArrowDown,
        contentDescription =
          if (row.isCollapsed) "Expand ${item.title}" else "Collapse ${item.title}",
        onClick = { onToggleCollapsed(item.id) },
        modifier = Modifier.testTag("plan_item_fold"),
      )
    } else {
      Spacer(Modifier.width(MinimumTouchTarget))
    }
    Box(
      modifier =
        Modifier.width(18.dp)
          .fillMaxHeight()
          .padding(vertical = 5.dp),
      contentAlignment = Alignment.Center,
    ) {
      Box(Modifier.width(2.dp).fillMaxHeight().background(colors.outlineVariant))
      Box(
        modifier =
          Modifier.size(if (item.isMilestone) 9.dp else 8.dp)
            .rotate(if (item.isMilestone) 45f else 0f)
            .background(
              if (item.columnId == null) colors.surface else colors.primary,
              if (item.isMilestone) RoundedCornerShape(Radius.mark) else CircleShape,
            )
      )
      if (item.columnId == null && !item.isMilestone) {
        Box(Modifier.size(8.dp).background(colors.outline, CircleShape).padding(2.dp)) {
          Box(Modifier.fillMaxSize().background(colors.surface, CircleShape))
        }
      }
    }
    Spacer(Modifier.width((row.depth * 14).dp))
    Checkbox(
      checked = item.progress == 100,
      onCheckedChange = { onToggleComplete(item) },
      modifier =
        Modifier.semantics {
            contentDescription =
              if (item.progress == 100) {
                "Mark ${item.title} incomplete"
              } else {
                "Mark ${item.title} complete"
              }
          }
          .testTag("plan_item_complete"),
    )
    Column(modifier = Modifier.weight(1f).padding(vertical = d.rowPaddingV)) {
      Text(
        text = item.title,
        fontSize = d.title,
        fontWeight = if (row.hasChildren) FontWeight.SemiBold else FontWeight.Medium,
        color = colors.onSurface.copy(alpha = if (item.progress == 100) 0.58f else 1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (metadata.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
          metadata.take(3).forEach { (label, tone) -> PropertyChip(label, tone) }
        }
      }
    }
  }
  Box(
    Modifier.fillMaxWidth()
      .padding(start = 18.dp + (row.depth * 14).dp)
      .height(1.dp)
      .background(colors.outlineVariant)
  )
}

@Composable
private fun buildTaskMetadata(item: PlanItem, formatter: TimeFormatter): List<Pair<String, Color>> {
  val colors = MaterialTheme.colorScheme
  val status = LocalStatusColors.current
  return buildList {
    item.effortMinutes?.let { minutes ->
      add(
        (if (minutes < 60) "${minutes}m" else if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes / 60}h ${minutes % 60}m") to
          colors.onSurfaceVariant
      )
    }
    item.dueAt?.let { add("Due ${formatter.relativeDay(it)}" to status.deadline) }
    if (item.isMilestone) add("Milestone" to colors.primary)
    if (item.progress in 1..99) add("${item.progress}%" to status.positive)
    when (item.priority) {
      PlanPriority.URGENT -> add("Urgent" to status.urgent)
      PlanPriority.HIGH -> add("High" to status.deadline)
    }
  }
}

/**
 * What can be done to a set of selected tasks.
 *
 * The move runs through the same atomic command the Board uses, so a hierarchy travels whole and a
 * refusal writes nothing — selecting work in a different place must not mean weaker guarantees.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutlineSelectionBar(
  selectedCount: Int,
  columns: List<PlanColumn>,
  onMove: (String?) -> Unit,
  onClear: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier.fillMaxWidth().testTag("outline_selection_bar"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Space.sm),
  ) {
    Text(
      text = "$selectedCount selected",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.weight(1f),
    )
    Box {
      OutlinedButton(
        onClick = { open = true },
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("outline_move_selected"),
      ) {
        Text("Move to…")
      }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
          text = { Text("Inbox") },
          onClick = {
            open = false
            onMove(null)
          },
          modifier = Modifier.testTag("outline_move_inbox"),
        )
        columns.forEach { column ->
          DropdownMenuItem(
            text = { Text(column.name) },
            onClick = {
              open = false
              onMove(column.id)
            },
            modifier = Modifier.testTag("outline_move_${column.id}"),
          )
        }
      }
    }
    TextButton(
      onClick = onClear,
      modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("outline_selection_done"),
    ) {
      Text("Done")
    }
  }
}
