package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.TimeFormatter
import com.example.data.model.BriefingEvent
import com.example.data.prefs.SettingKeys
import com.example.ui.components.BoardCard
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

  var prompt by remember { mutableStateOf<Prompt?>(null) }
  var confirmDeleteBoard by remember { mutableStateOf(false) }

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

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter),
      horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
      BoardScope.entries.forEach { option ->
        FilterChip(
          selected = scope == option,
          onClick = { viewModel.setBoardScope(option) },
          label = { Text(option.label) },
        )
      }
    }

    Spacer(Modifier.height(Space.md))

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
          contentPadding = PaddingValues(horizontal = gutter, bottom = Space.lg),
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
              onDelete = { viewModel.deleteColumn(column) },
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
      onDismiss = { prompt = null },
      onConfirm = { value ->
        when (activePrompt) {
          Prompt.NewBoard -> viewModel.addBoard(value)
          is Prompt.RenameBoard -> viewModel.renameBoard(activePrompt.current, value)
          Prompt.NewColumn -> viewModel.addColumn(value)
          is Prompt.RenameColumn -> viewModel.renameColumn(activePrompt.current, value)
        }
        prompt = null
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
}

private sealed interface Prompt {
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag("board_selector"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = activeBoard,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "Choose a board",
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
      IconButton(onClick = { overflowMenu = true }, modifier = Modifier.testTag("board_menu")) {
        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Board options")
      }
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
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var menu by remember { mutableStateOf(false) }

  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(Space.md)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = name,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = events.size.toString(),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
          IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
            Icon(
              imageVector = Icons.Default.MoreVert,
              contentDescription = "Column options",
              modifier = Modifier.size(18.dp),
            )
          }
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
              onClick = {
                menu = false
                onDelete()
              },
            )
          }
        }
      }

      Spacer(Modifier.height(Space.sm))

      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
      ) {
        items(events, key = { it.id }) { event ->
          BoardCard(
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
              text = "Nothing here yet",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = Space.md),
            )
          }
        }
      }

      TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().testTag("add_card_$name")) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Space.xs))
        Text("Add")
      }
    }
  }
}

@Composable
private fun TextPromptDialog(
  title: String,
  initialValue: String,
  confirmLabel: String,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var value by remember { mutableStateOf(initialValue) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("prompt_input"),
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
        Text(confirmLabel)
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
