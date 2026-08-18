package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space

/**
 * Accessible, non-drag dependency management for a single Plan.
 *
 * Persistence and graph validation remain host-owned. Pass cycle or repository errors through
 * [errors]; this component keeps the relationship ledger usable while explaining those failures.
 */
@Composable
fun PlanDependencyManager(
  boardId: String,
  items: List<PlanItem>,
  dependencies: List<PlanDependency>,
  onAdd: (DependencyDraft) -> Unit,
  onUpdate: (PlanDependency, DependencyDraft) -> Unit,
  onDelete: (PlanDependency) -> Unit,
  modifier: Modifier = Modifier,
  errors: List<String> = emptyList(),
  isBusy: Boolean = false,
) {
  val options = remember(boardId, items) { DependencyUiProjector.taskOptions(boardId, items) }
  val rows =
    remember(boardId, items, dependencies) {
      DependencyUiProjector.rows(boardId, items, dependencies)
    }
  var editorTarget by rememberSaveable { mutableStateOf<String?>(null) }
  var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
  val editingDependency =
    editorTarget?.takeUnless { it == AddEditorTarget }?.let { id ->
      dependencies.firstOrNull { it.id == id }
    }
  val deleteTarget = deleteTargetId?.let { id -> dependencies.firstOrNull { it.id == id } }

  LaunchedEffect(editorTarget, editingDependency) {
    if (editorTarget != null && editorTarget != AddEditorTarget && editingDependency == null) {
      editorTarget = null
    }
  }
  LaunchedEffect(deleteTargetId, deleteTarget) {
    if (deleteTargetId != null && deleteTarget == null) deleteTargetId = null
  }

  Column(
    modifier = modifier.fillMaxWidth().testTag("dependency_manager"),
    verticalArrangement = Arrangement.spacedBy(Space.sm),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(Space.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Dependencies",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.semantics { heading() },
        )
        Text(
          text = "A title-first relationship ledger; no timeline dragging required.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedButton(
        onClick = { editorTarget = AddEditorTarget },
        enabled = !isBusy && options.size >= 2,
        modifier =
          Modifier.heightIn(min = MinimumTouchTarget)
            .semantics { contentDescription = "Add task dependency" }
            .testTag("dependency_add"),
      ) {
        Text("Add")
      }
    }

    if (errors.isNotEmpty()) {
      DependencyErrorPanel(errors)
    }

    when {
      options.size < 2 ->
        GuidancePanel(
          title = "Two active tasks are required",
          body = "Add or restore another task in this Plan before creating a relationship.",
          tag = "dependency_unavailable",
        )
      rows.isEmpty() ->
        GuidancePanel(
          title = "No dependencies yet",
          body = "Add one to define which task leads, which follows, and any lead or lag.",
          tag = "dependency_empty",
        )
      else ->
        rows.forEach { row ->
          DependencyRow(
            row = row,
            enabled = !isBusy,
            onEdit = { editorTarget = row.dependency.id },
            onDelete = { deleteTargetId = row.dependency.id },
          )
        }
    }
  }

  if (editorTarget != null && (editorTarget == AddEditorTarget || editingDependency != null)) {
    val targetKey = editorTarget.orEmpty()
    key(targetKey) {
      val initialDraft =
        editingDependency?.toDraft()
          ?: DependencyDraft(
            predecessorId = options.firstOrNull()?.item?.id.orEmpty(),
            successorId = options.drop(1).firstOrNull()?.item?.id.orEmpty(),
            type = PlanDependencyType.FINISH_TO_START,
            lagMinutes = 0,
          )
      DependencyEditorDialog(
        initialDraft = initialDraft,
        editingDependency = editingDependency,
        options = options,
        dependencies = dependencies,
        isBusy = isBusy,
        onDismiss = { editorTarget = null },
        onConfirm = { draft ->
          if (editingDependency == null) onAdd(draft) else onUpdate(editingDependency, draft)
          editorTarget = null
        },
      )
    }
  }

  if (deleteTarget != null) {
    val row = rows.firstOrNull { it.dependency.id == deleteTarget.id }
    AlertDialog(
      onDismissRequest = { if (!isBusy) deleteTargetId = null },
      title = { Text("Delete dependency?") },
      text = {
        Text(
          if (row == null) {
            "This relationship will be removed. The two tasks will stay in the Plan."
          } else {
            "Remove ${row.predecessorTitle} → ${row.successorTitle} " +
              "(${row.typeCode}, ${row.offsetLabel})? The two tasks will stay in the Plan."
          }
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onDelete(deleteTarget)
            deleteTargetId = null
          },
          enabled = !isBusy,
          modifier =
            Modifier.heightIn(min = MinimumTouchTarget).testTag("dependency_delete_confirm"),
        ) {
          Text("Delete")
        }
      },
      dismissButton = {
        TextButton(
          onClick = { deleteTargetId = null },
          enabled = !isBusy,
          modifier = Modifier.heightIn(min = MinimumTouchTarget),
        ) {
          Text("Cancel")
        }
      },
      modifier = Modifier.testTag("dependency_delete_dialog"),
    )
  }
}

@Composable
private fun DependencyErrorPanel(errors: List<String>) {
  val distinctErrors = errors.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
  if (distinctErrors.isEmpty()) return
  val hasCycle = distinctErrors.any { it.contains("cycle", ignoreCase = true) }
  Surface(
    modifier =
      Modifier.fillMaxWidth()
        .semantics { liveRegion = LiveRegionMode.Polite }
        .testTag("dependency_errors"),
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    shape = MaterialTheme.shapes.small,
  ) {
    Column(modifier = Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
      Text("Dependency check", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
      distinctErrors.forEach { error -> Text("• $error", style = MaterialTheme.typography.bodySmall) }
      Text(
        text =
          if (hasCycle) {
            "A cycle has no valid task order. Edit or delete one relationship in the loop."
          } else {
            "Fix these relationships before relying on calculated dates."
          },
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun GuidancePanel(title: String, body: String, tag: String) {
  Surface(
    modifier = Modifier.fillMaxWidth().testTag(tag),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = MaterialTheme.shapes.small,
  ) {
    Column(modifier = Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
      Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
      Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun DependencyRow(
  row: DependencyUiRow,
  enabled: Boolean,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().testTag("dependency_row_${row.dependency.id}"),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = MaterialTheme.shapes.small,
  ) {
    Column(modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
      Column(
        modifier =
          Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = row.accessibilityLabel
            stateDescription = if (row.issue == null) "Dependency ready" else "Dependency needs attention"
          },
        verticalArrangement = Arrangement.spacedBy(Space.xs),
      ) {
        Text(
          text = "${row.predecessorTitle} → ${row.successorTitle}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
          DependencyToken("${row.typeCode} · ${row.typeLabel}")
          DependencyToken(row.offsetLabel)
        }
        if (row.issue != null) {
          Text(
            row.issue,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = onEdit,
          enabled = enabled,
          modifier =
            Modifier.heightIn(min = MinimumTouchTarget)
              .semantics {
                contentDescription =
                  "Edit dependency from ${row.predecessorTitle} to ${row.successorTitle}"
              }
              .testTag("dependency_edit_${row.dependency.id}"),
        ) {
          Text("Edit")
        }
        TextButton(
          onClick = onDelete,
          enabled = enabled,
          modifier =
            Modifier.heightIn(min = MinimumTouchTarget)
              .semantics {
                contentDescription =
                  "Delete dependency from ${row.predecessorTitle} to ${row.successorTitle}"
              }
              .testTag("dependency_delete_${row.dependency.id}"),
        ) {
          Text("Delete")
        }
      }
    }
  }
}

@Composable
private fun DependencyToken(text: String) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    shape = MaterialTheme.shapes.extraSmall,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
    )
  }
}

@Composable
private fun DependencyEditorDialog(
  initialDraft: DependencyDraft,
  editingDependency: PlanDependency?,
  options: List<DependencyTaskOption>,
  dependencies: List<PlanDependency>,
  isBusy: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (DependencyDraft) -> Unit,
) {
  var predecessorId by rememberSaveable { mutableStateOf(initialDraft.predecessorId) }
  var successorId by rememberSaveable { mutableStateOf(initialDraft.successorId) }
  var type by rememberSaveable { mutableStateOf(initialDraft.type) }
  var lagText by rememberSaveable { mutableStateOf(initialDraft.lagMinutes.toString()) }
  val parsedLag = lagText.trim().toIntOrNull()
  val draft =
    parsedLag?.let {
      DependencyDraft(
        predecessorId = predecessorId,
        successorId = successorId,
        type = type,
        lagMinutes = it,
      )
    }
  val validationError =
    when {
      parsedLag == null -> "Enter lead or lag as signed whole minutes, such as -30, 0, or +45."
      draft == null -> "Enter a valid relationship."
      else ->
        DependencyUiProjector.draftError(
          draft = draft,
          options = options,
          dependencies = dependencies,
          editingDependencyId = editingDependency?.id,
        )
    }

  AlertDialog(
    onDismissRequest = { if (!isBusy) onDismiss() },
    title = { Text(if (editingDependency == null) "Add dependency" else "Edit dependency") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.md),
      ) {
        DependencyChoiceField(
          label = "Predecessor",
          selectedId = predecessorId,
          options = options.map { it.item.id to it.label },
          enabled = !isBusy && editingDependency == null,
          tag = "dependency_predecessor",
          onSelect = { predecessorId = it },
        )
        DependencyChoiceField(
          label = "Successor",
          selectedId = successorId,
          options = options.map { it.item.id to it.label },
          enabled = !isBusy && editingDependency == null,
          tag = "dependency_successor",
          onSelect = { successorId = it },
        )
        if (editingDependency != null) {
          Text(
            "To connect different tasks, delete this relationship and add a new one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        DependencyChoiceField(
          label = "Relationship type",
          selectedId = type,
          options =
            DependencyUiProjector.Types.map { dependencyType ->
              dependencyType to
                "${DependencyUiProjector.typeCode(dependencyType)} · " +
                  DependencyUiProjector.typeLabel(dependencyType)
            },
          enabled = !isBusy,
          tag = "dependency_type",
          onSelect = { type = it },
        )
        OutlinedTextField(
          value = lagText,
          onValueChange = { lagText = it },
          enabled = !isBusy,
          label = { Text("Lead / lag (minutes)") },
          supportingText = { Text("Negative = lead; positive = lag; zero = none.") },
          singleLine = true,
          isError = parsedLag == null,
          keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(min = MinimumTouchTarget)
              .testTag("dependency_lag"),
        )
        Text(
          "FS is the usual sequence. SS, FF, and SF coordinate task starts or finishes. " +
            "Cycles are checked when the host saves.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (validationError != null) {
          Text(
            validationError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier =
              Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("dependency_editor_error"),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { draft?.let(onConfirm) },
        enabled = !isBusy && validationError == null && draft != null,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("dependency_editor_confirm"),
      ) {
        Text(if (editingDependency == null) "Add dependency" else "Save changes")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        enabled = !isBusy,
        modifier = Modifier.heightIn(min = MinimumTouchTarget),
      ) {
        Text("Cancel")
      }
    },
    modifier = Modifier.widthIn(min = 280.dp).testTag("dependency_editor"),
  )
}

@Composable
private fun DependencyChoiceField(
  label: String,
  selectedId: String,
  options: List<Pair<String, String>>,
  enabled: Boolean,
  tag: String,
  onSelect: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val selectedLabel = options.firstOrNull { it.first == selectedId }?.second ?: "Choose a task"
  Box(modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(
      onClick = { expanded = true },
      enabled = enabled,
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(min = MinimumTouchTarget)
          .semantics { contentDescription = "$label: $selectedLabel" }
          .testTag(tag),
    ) {
      Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { (id, optionLabel) ->
        DropdownMenuItem(
          text = { Text(optionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis) },
          onClick = {
            expanded = false
            onSelect(id)
          },
          modifier = Modifier.heightIn(min = MinimumTouchTarget),
        )
      }
    }
  }
}

private fun PlanDependency.toDraft(): DependencyDraft =
  DependencyDraft(
    predecessorId = predecessorId,
    successorId = successorId,
    type = type,
    lagMinutes = lagMinutes,
  )

private const val AddEditorTarget = "__add_dependency__"
