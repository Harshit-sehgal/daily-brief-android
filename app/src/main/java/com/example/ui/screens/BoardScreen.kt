package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.TimeFormatter
import com.example.core.ScheduleAnalysis
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.prefs.SettingKeys
import com.example.ui.components.InlineAddRow
import com.example.ui.components.PropertyChip
import com.example.ui.components.RowIconButton
import com.example.ui.components.ViewSwitcher
import com.example.ui.components.priorityState
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.Space
import com.example.ui.theme.gutter
import com.example.ui.viewmodel.BoardScope
import com.example.ui.viewmodel.BriefingViewModel

/**
 * The board, as its own destination rather than a tab inside the day view — a
 * column of work is not a property of a single date.
 *
 * Columns fill the height they are given, so there is no manual height control
 * to get wrong, and they size to the window so the next one always peeks.
 */
@Composable
fun BoardScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onAddToColumn: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val boards by viewModel.boards.collectAsStateWithLifecycle()
  val activeBoard by viewModel.activeBoard.collectAsStateWithLifecycle()
  val columns by viewModel.boardColumns.collectAsStateWithLifecycle()
  val events by viewModel.boardEvents.collectAsStateWithLifecycle()
  val scope by viewModel.boardScope.collectAsStateWithLifecycle()

  val gutter = LocalWindowWidth.current.gutter
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme

  var prompt by rememberSaveable { mutableStateOf<Prompt?>(null) }
  var promptBusy by rememberSaveable { mutableStateOf(false) }
  var confirmDeleteBoard by rememberSaveable { mutableStateOf(false) }
  var confirmDeleteColumn by rememberSaveable { mutableStateOf<String?>(null) }

  Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
    BoardHeader(
      boards = boards,
      activeBoard = activeBoard,
      onSelectBoard = viewModel::selectBoard,
      onNewBoard = { prompt = Prompt.NewBoard },
      onRenameBoard = { prompt = Prompt.RenameBoard(activeBoard) },
      onDeleteBoard = { confirmDeleteBoard = true },
      onAddColumn = { prompt = Prompt.NewColumn },
      modifier = Modifier.padding(horizontal = gutter),
    )

    ViewSwitcher(
      options = BoardScope.entries.map { it.label },
      selectedIndex = BoardScope.entries.indexOf(scope),
      onSelect = { viewModel.setBoardScope(BoardScope.entries[it]) },
      modifier = Modifier.padding(start = gutter, end = gutter),
    )

    Spacer(Modifier.height(d.sectionGap / 2))

    if (columns.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TextButton(onClick = { prompt = Prompt.NewColumn }) { Text("Add a column to get started") }
      }
    } else {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Leave a sliver of the next column visible so the board reads as scrollable.
        val columnWidth =
          if (maxWidth < 420.dp) (maxWidth - gutter - Space.xl) else 320.dp

        LazyRow(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.lg),
          horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
          items(columns, key = { it }) { column ->
            val columnEvents =
              remember(events, column) { events.filter { it.kanbanStatus == column } }
            val index = columns.indexOf(column)
            BoardColumn(
              name = column,
              events = columnEvents,
              formatter = formatter,
              canMoveLeft = index > 0,
              canMoveRight = index < columns.size - 1,
              onMoveLeft = { event -> viewModel.moveEvent(event, columns[index - 1]) },
              onMoveRight = { event -> viewModel.moveEvent(event, columns[index + 1]) },
              onEditEvent = onEditEvent,
              onAdd = { onAddToColumn(column) },
              onRename = { prompt = Prompt.RenameColumn(column) },
              canDelete = columns.size > 1,
              onDelete = { confirmDeleteColumn = column },
              modifier = Modifier.width(columnWidth).fillMaxHeight(),
            )
          }
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
          is Prompt.RenameBoard -> viewModel.renameBoard(activePrompt.current, value, finish)
          Prompt.NewColumn -> viewModel.addColumn(value, finish)
          is Prompt.RenameColumn -> viewModel.renameColumn(activePrompt.current, value, finish)
        }
      },
    )
  }

  if (confirmDeleteBoard) {
    AlertDialog(
      onDismissRequest = { confirmDeleteBoard = false },
      title = { Text("Delete \"$activeBoard\"?") },
      text = { Text("Everything on it moves to the first remaining board. Nothing is deleted.") },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deleteBoard(activeBoard)
            confirmDeleteBoard = false
          }
        ) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmDeleteBoard = false }) { Text("Cancel") } },
    )
  }

  val columnToDelete = confirmDeleteColumn
  if (columnToDelete != null) {
    val fallbackColumn = columns.firstOrNull { it != columnToDelete }
    AlertDialog(
      onDismissRequest = { confirmDeleteColumn = null },
      title = { Text("Delete \"$columnToDelete\"?") },
      text = {
        Text(
          if (fallbackColumn == null) "A board needs at least one column."
          else "Cards in this column move to \"$fallbackColumn\". Nothing is deleted."
        )
      },
      confirmButton = {
        TextButton(
          enabled = fallbackColumn != null,
          onClick = {
            viewModel.deleteColumn(columnToDelete)
            confirmDeleteColumn = null
          },
        ) {
          Text("Delete column", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmDeleteColumn = null }) { Text("Cancel") } },
    )
  }
}

private sealed interface Prompt : java.io.Serializable {
  val title: String
  val initialValue: String
  val confirmLabel: String

  data object NewBoard : Prompt {
    override val title = "New board"
    override val initialValue = ""
    override val confirmLabel = "Create"
  }

  data class RenameBoard(val current: String) : Prompt {
    override val title = "Rename board"
    override val initialValue = current
    override val confirmLabel = "Rename"
  }

  data object NewColumn : Prompt {
    override val title = "New column"
    override val initialValue = ""
    override val confirmLabel = "Create"
  }

  data class RenameColumn(val current: String) : Prompt {
    override val title = "Rename column"
    override val initialValue = current
    override val confirmLabel = "Rename"
  }
}

@Composable
private fun BoardHeader(
  boards: List<String>,
  activeBoard: String,
  onSelectBoard: (String) -> Unit,
  onNewBoard: () -> Unit,
  onRenameBoard: () -> Unit,
  onDeleteBoard: () -> Unit,
  onAddColumn: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var boardMenu by remember { mutableStateOf(false) }
  var overflowMenu by remember { mutableStateOf(false) }
  val isDefaultBoard = activeBoard == SettingKeys.DEFAULT_BOARD

  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = Space.sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.weight(1f)) {
      Surface(
        onClick = { boardMenu = true },
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        modifier = Modifier.testTag("board_selector"),
      ) {
        Row(
          modifier = Modifier.padding(end = Space.xs, top = Space.xs, bottom = Space.xs),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = activeBoard,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "Choose a board",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
        }
      }
      DropdownMenu(expanded = boardMenu, onDismissRequest = { boardMenu = false }) {
        boards.forEach { board ->
          DropdownMenuItem(
            text = { Text(board) },
            onClick = {
              onSelectBoard(board)
              boardMenu = false
            },
          )
        }
      }
    }

    Box {
      RowIconButton(
        icon = Icons.Default.MoreVert,
        contentDescription = "Board options",
        onClick = { overflowMenu = true },
        modifier = Modifier.testTag("board_menu"),
      )
      DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
        DropdownMenuItem(
          text = { Text("Add column") },
          onClick = {
            overflowMenu = false
            onAddColumn()
          },
        )
        DropdownMenuItem(
          text = { Text("New board") },
          onClick = {
            overflowMenu = false
            onNewBoard()
          },
        )
        DropdownMenuItem(
          text = { Text("Rename board") },
          enabled = !isDefaultBoard,
          onClick = {
            overflowMenu = false
            onRenameBoard()
          },
        )
        DropdownMenuItem(
          text = { Text("Delete board", color = MaterialTheme.colorScheme.error) },
          enabled = !isDefaultBoard,
          onClick = {
            overflowMenu = false
            onDeleteBoard()
          },
        )
      }
    }
  }
}

@Composable
private fun BoardColumn(
  name: String,
  events: List<BriefingEvent>,
  formatter: TimeFormatter,
  canMoveLeft: Boolean,
  canMoveRight: Boolean,
  onMoveLeft: (BriefingEvent) -> Unit,
  onMoveRight: (BriefingEvent) -> Unit,
  onEditEvent: (BriefingEvent) -> Unit,
  onAdd: () -> Unit,
  onRename: () -> Unit,
  canDelete: Boolean,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var menu by remember { mutableStateOf(false) }

  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme

  Column(modifier = modifier) {
    Column(modifier = Modifier.fillMaxSize()) {
      // The column head is a label, not a card title — it stays out of the way.
      Row(
        modifier = Modifier.fillMaxWidth().height(d.sectionHeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = name.uppercase(),
          fontSize = d.label,
          fontWeight = FontWeight.Medium,
          letterSpacing = 0.8.sp,
          color = scheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Space.sm))
        Text(
          text = events.size.toString(),
          fontSize = d.label,
          color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
          modifier = Modifier.weight(1f),
        )
        Box {
          RowIconButton(
            icon = Icons.Default.MoreVert,
            contentDescription = "Column options",
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
              text = {
                Text(
                  "Delete",
                  color =
                    if (canDelete) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
              },
              enabled = canDelete,
              onClick = {
                menu = false
                onDelete()
              },
            )
          }
        }
      }

      Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
      Spacer(Modifier.height(Space.xs))

      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
      ) {
        items(events, key = { it.id }) { event ->
          BoardTile(
            event = event,
            formatter = formatter,
            canMoveLeft = canMoveLeft,
            canMoveRight = canMoveRight,
            onClick = { onEditEvent(event) },
            onMoveLeft = { onMoveLeft(event) },
            onMoveRight = { onMoveRight(event) },
          )
        }
        if (events.isEmpty()) {
          item {
            Text(
              text = "Nothing here",
              fontSize = d.secondary,
              color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
              modifier = Modifier.padding(vertical = Space.sm),
            )
          }
        }
        item {
          InlineAddRow(
            label = "New card",
            onClick = onAdd,
            modifier = Modifier.testTag("add_card_$name"),
          )
        }
      }
    }
  }
}

/**
 * One card on the board.
 *
 * Deliberately quieter than the old elevated card: a tinted panel with a marker
 * stripe, so a column of ten reads as a list you can scan rather than ten
 * competing surfaces. The move arrows only appear when there is somewhere to go.
 */
@Composable
private fun BoardTile(
  event: BriefingEvent,
  formatter: TimeFormatter,
  canMoveLeft: Boolean,
  canMoveRight: Boolean,
  onClick: () -> Unit,
  onMoveLeft: () -> Unit,
  onMoveRight: () -> Unit,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val status = LocalStatusColors.current
  val tone =
    when {
      event.isDeadline -> status.deadline
      event.isUrgent -> status.urgent
      else -> scheme.outline
    }

  val state = priorityState(event.isDeadline, event.isUrgent)

  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(8.dp),
    color = scheme.surfaceContainerLow,
    modifier =
      Modifier.fillMaxWidth().semantics {
        if (state != null) stateDescription = state
      },
  ) {
    // Intrinsic height, or the stripe measures against an infinite constraint and
    // collapses to nothing.
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      Box(
        modifier =
          Modifier.width(3.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
            .background(tone)
      )
      Column(modifier = Modifier.weight(1f).padding(Space.sm)) {
        Text(
          text = event.title,
          fontSize = d.title,
          fontWeight = FontWeight.Medium,
          color = scheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text =
            if (ScheduleAnalysis.isAllDay(event)) "All day"
            else formatter.time(event.startTime) + " · " + formatter.duration(event.startTime, event.endTime),
          fontSize = d.label,
          color = scheme.onSurfaceVariant,
        )
        val chips = buildList {
          if (event.isDeadline) add("Deadline" to status.deadline)
          if (event.isUrgent) add("Urgent" to status.urgent)
          if (event.source != EventSource.MANUAL) add(event.source to scheme.onSurfaceVariant)
        }
        if (chips.isNotEmpty() || canMoveLeft || canMoveRight) {
          Spacer(Modifier.height(Space.xs))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
          ) {
            chips.forEach { (label, chipTone) -> PropertyChip(label, chipTone) }
            Spacer(Modifier.weight(1f))
            if (canMoveLeft) {
              RowIconButton(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                "Move \"${'$'}{event.title}\" left",
                onMoveLeft,
              )
            }
            if (canMoveRight) {
              RowIconButton(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                "Move \"${'$'}{event.title}\" right",
                onMoveRight,
              )
            }
          }
        }
      }
    }
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
