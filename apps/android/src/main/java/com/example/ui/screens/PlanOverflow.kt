package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.TimeFormatter
import com.example.ui.components.RowIconButton
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space
import com.example.ui.theme.UiDensity

/**
 * One menu for everything the Plan can do that is not the work itself.
 *
 * The Plan used to spend four bands on this before a single task appeared: a row of saved-view,
 * health and history controls, then a board summary, then three presentation chips. On a small phone
 * that was the whole screen. They are all still here, one tap away and grouped by what a person came
 * to do, and the screen underneath is now the tasks.
 */
@Composable
internal fun PlanOverflowMenu(
  onViewOptions: () -> Unit,
  onHealth: () -> Unit,
  onTools: () -> Unit,
  onHistory: () -> Unit,
  onExport: (Boolean) -> Unit,
  onExportPdf: () -> Unit,
  onExportPng: () -> Unit,
  onPrint: () -> Unit = {},
) {
  var open by remember { mutableStateOf(false) }
  Box {
    RowIconButton(
      icon = Icons.Default.MoreVert,
      contentDescription = "Plan options",
      onClick = { open = true },
      modifier = Modifier.testTag("plan_overflow"),
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(
        text = { Text("View options…") },
        onClick = {
          open = false
          onViewOptions()
        },
        modifier = Modifier.testTag("plan_menu_view_options"),
      )
      DropdownMenuItem(
        text = { Text("Plan health…") },
        onClick = {
          open = false
          onHealth()
        },
        modifier = Modifier.testTag("plan_menu_health"),
      )
      DropdownMenuItem(
        text = { Text("Plan tools…") },
        onClick = {
          open = false
          onTools()
        },
        modifier = Modifier.testTag("plan_menu_tools"),
      )
      DropdownMenuItem(
        text = { Text("History…") },
        onClick = {
          open = false
          onHistory()
        },
        modifier = Modifier.testTag("plan_menu_history"),
      )
      HorizontalDivider()
      DropdownMenuItem(
        text = { Text("Export tasks (CSV)") },
        onClick = {
          open = false
          onExport(false)
        },
        modifier = Modifier.testTag("plan_export_csv"),
      )
      DropdownMenuItem(
        text = { Text("Export schedule (calendar)") },
        onClick = {
          open = false
          onExport(true)
        },
        modifier = Modifier.testTag("plan_export_ics"),
      )
      DropdownMenuItem(
        text = { Text("Export plan (PDF)") },
        onClick = {
          open = false
          onExportPdf()
        },
        modifier = Modifier.testTag("plan_export_pdf"),
      )
      DropdownMenuItem(
        text = { Text("Export plan (image)") },
        onClick = {
          open = false
          onExportPng()
        },
        modifier = Modifier.testTag("plan_export_png"),
      )
      DropdownMenuItem(
        text = { Text("Print plan") },
        onClick = {
          open = false
          onPrint()
        },
        modifier = Modifier.testTag("plan_print"),
      )
    }
  }
}

/**
 * How the work is shown, in one place.
 *
 * Order, banding, what is hidden and which saved view is applied are all the same kind of decision —
 * they change the presentation and never the work — so they are answered together rather than spread
 * across a chip row, a menu and a rail.
 */
@Composable
internal fun PlanViewOptionsDialog(
  visible: Boolean,
  sort: OutlineSort,
  grouping: OutlineGrouping,
  hideCompleted: Boolean,
  query: String,
  density: UiDensity,
  collapsedCount: Int,
  savedViews: List<com.example.data.repository.SavedPlanView>,
  activeViewId: String?,
  /** The Board's lanes, when the Board is the surface being shown. Empty elsewhere. */
  lanes: List<com.example.data.model.PlanColumn> = emptyList(),
  visibleLaneIds: Set<String> = emptySet(),
  onToggleLane: (String) -> Unit = {},
  onShowAllLanes: () -> Unit = {},
  onSort: (OutlineSort) -> Unit,
  onGrouping: (OutlineGrouping) -> Unit,
  onHideCompleted: (Boolean) -> Unit,
  onQueryChange: (String) -> Unit,
  onDensity: (UiDensity) -> Unit,
  onExpandAll: () -> Unit,
  onApplyView: (com.example.data.repository.SavedPlanView) -> Unit,
  onSaveView: () -> Unit,
  onManageViews: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (!visible) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("View options") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier =
          Modifier.heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState())
            .testTag("plan_view_options"),
      ) {
        OptionHeading("Order")
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(Space.xs),
          verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
          OutlineSort.entries.forEach { option ->
            FilterChip(
              selected = option == sort,
              onClick = { onSort(option) },
              label = { Text(option.label) },
              modifier =
                Modifier.heightIn(min = MinimumTouchTarget).testTag("outline_sort_${option.key}"),
            )
          }
        }

        OptionHeading("Group by")
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(Space.xs),
          verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
          OutlineGrouping.entries.forEach { option ->
            FilterChip(
              selected = option == grouping,
              onClick = { onGrouping(option) },
              label = { Text(option.label) },
              modifier =
                Modifier.heightIn(min = MinimumTouchTarget)
                  .testTag("outline_grouping_${option.key}"),
            )
          }
        }

        OptionHeading("Search")
        OutlinedTextField(
          value = query,
          onValueChange = onQueryChange,
          label = { Text("Filter by title or note") },
          singleLine = true,
          trailingIcon = {
            if (query.isNotEmpty()) {
              IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Default.Close, contentDescription = "Clear search")
              }
            }
          },
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(min = MinimumTouchTarget)
              .testTag("outline_query"),
        )

        OptionHeading("Density")
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(Space.xs),
          verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
          UiDensity.entries.forEach { option ->
            FilterChip(
              selected = option == density,
              onClick = { onDensity(option) },
              label = { Text(option.label) },
              modifier =
                Modifier.heightIn(min = MinimumTouchTarget)
                  .testTag("outline_density_${option.key}"),
            )
          }
        }

        OptionHeading("Show")
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(Space.xs),
          verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
          FilterChip(
            selected = hideCompleted,
            onClick = { onHideCompleted(!hideCompleted) },
            label = { Text("Hide done") },
            modifier =
              Modifier.heightIn(min = MinimumTouchTarget).testTag("outline_hide_completed"),
          )
          if (collapsedCount > 0) {
            FilterChip(
              selected = false,
              onClick = onExpandAll,
              label = { Text("Expand all ($collapsedCount)") },
              modifier =
                Modifier.heightIn(min = MinimumTouchTarget).testTag("outline_expand_all"),
            )
          }
        }

        if (lanes.isNotEmpty()) {
          OptionHeading("Lanes")
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
          ) {
            lanes.forEach { lane ->
              // An empty set means every lane, so a preset only exists once one is switched off.
              val shown = visibleLaneIds.isEmpty() || lane.id in visibleLaneIds
              FilterChip(
                selected = shown,
                onClick = { onToggleLane(lane.id) },
                label = { Text(lane.name) },
                modifier =
                  Modifier.heightIn(min = MinimumTouchTarget)
                    .testTag("board_column_toggle_${lane.id}"),
              )
            }
            if (visibleLaneIds.isNotEmpty()) {
              FilterChip(
                selected = false,
                onClick = onShowAllLanes,
                label = { Text("Show every lane") },
                modifier =
                  Modifier.heightIn(min = MinimumTouchTarget).testTag("board_columns_all"),
              )
            }
          }
        }

        if (savedViews.isNotEmpty()) {
          OptionHeading("Saved views")
          savedViews.forEach { saved ->
            TextButton(
              onClick = { onApplyView(saved) },
              modifier =
                Modifier.fillMaxWidth()
                  .heightIn(min = MinimumTouchTarget)
                  .testTag("plan_view_apply_${saved.view.id}"),
            ) {
              Text(
                text = if (saved.view.id == activeViewId) "${saved.view.name} · applied" else saved.view.name,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }
        }
        TextButton(
          onClick = onSaveView,
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("save_plan_view"),
        ) {
          Text("Save this view…")
        }
        TextButton(
          onClick = onManageViews,
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_manage_views"),
        ) {
          Text("Manage saved views…")
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_view_options_close"),
      ) {
        Text("Done")
      }
    },
    modifier = Modifier.testTag("plan_view_options_dialog"),
  )
}

/**
 * What can be done *to* a saved view, as opposed to which one is applied.
 *
 * Applying lives in View options because that is a presentation choice; renaming, duplicating,
 * pinning and deleting are housekeeping, and mixing the two made one menu that did both badly.
 */
@Composable
internal fun ManageSavedViewsDialog(
  visible: Boolean,
  views: List<com.example.data.repository.SavedPlanView>,
  activeViewId: String?,
  onUpdate: () -> Unit,
  onRename: (com.example.data.repository.SavedPlanView) -> Unit,
  onDuplicate: (com.example.data.repository.SavedPlanView) -> Unit,
  onPin: (com.example.data.repository.SavedPlanView) -> Unit,
  onDelete: (com.example.data.repository.SavedPlanView) -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (!visible) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Saved views") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier =
          Modifier.heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState())
            .testTag("plan_manage_views_body"),
      ) {
        if (views.isEmpty()) {
          Text(
            "No saved views yet. Set the order and filters you want, then save them from View options.",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        views.forEach { saved ->
          Column {
            Text(
              text = if (saved.view.id == activeViewId) "${saved.view.name} · applied" else saved.view.name,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
            )
            if (saved.view.pinned) {
              Text(
                "Opens this board",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
              TextButton(
                onClick = { onRename(saved) },
                modifier =
                  Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_view_rename"),
              ) {
                Text("Rename")
              }
              TextButton(
                onClick = { onDuplicate(saved) },
                modifier =
                  Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_view_duplicate"),
              ) {
                Text("Duplicate")
              }
              TextButton(
                onClick = { onPin(saved) },
                modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_view_pin"),
              ) {
                Text(if (saved.view.pinned) "Unpin" else "Pin")
              }
              TextButton(
                onClick = { onDelete(saved) },
                modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_view_delete"),
              ) {
                Text("Delete")
              }
            }
          }
        }
        HorizontalDivider()
        TextButton(
          onClick = onUpdate,
          enabled = activeViewId != null,
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_view_update"),
        ) {
          Text("Update the applied view to what is on screen")
        }
        TextButton(
          onClick = onReset,
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_view_reset"),
        ) {
          Text("Reset to defaults")
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_manage_views_close"),
      ) {
        Text("Done")
      }
    },
    modifier = Modifier.testTag("plan_manage_views_dialog"),
  )
}

/** Everything that reasons about the plan rather than changing how it is displayed. */
@Composable
internal fun PlanToolsDialog(
  visible: Boolean,
  onProposePlan: () -> Unit,
  onCompareScenarios: () -> Unit,
  onWeeklyReview: () -> Unit,
  onBaselines: () -> Unit,
  onPortfolio: () -> Unit,
  onFocusTimer: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (!visible) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Plan tools") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier.testTag("plan_tools"),
      ) {
        ToolRow("Plan my week", "Fill unscheduled effort into your working hours.", "plan_auto_plan") {
          onDismiss()
          onProposePlan()
        }
        ToolRow(
          "Compare approaches",
          "The same week ordered three ways, before anything is applied.",
          "plan_scenarios_open",
        ) {
          onDismiss()
          onCompareScenarios()
        }
        ToolRow("Weekly review", "What the week planned, finished and carried over.", "plan_weekly_review") {
          onDismiss()
          onWeeklyReview()
        }
        ToolRow("Baselines", "Record where the schedule is, and see what moved.", "plan_baselines_open") {
          onDismiss()
          onBaselines()
        }
        ToolRow("Across every plan", "Open work and unscheduled effort on every board.", "plan_portfolio_open") {
          onDismiss()
          onPortfolio()
        }
        ToolRow("Focus timer", "A countdown anchored to one plan block.", "plan_focus_timer") {
          onDismiss()
          onFocusTimer()
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_tools_close"),
      ) {
        Text("Close")
      }
    },
    modifier = Modifier.testTag("plan_tools_dialog"),
  )
}

@Composable
private fun ToolRow(title: String, blurb: String, tag: String, onClick: () -> Unit) {
  TextButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget).testTag(tag),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      Text(
        blurb,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun OptionHeading(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = Space.xs).semantics { heading() },
  )
}

/** One plan block that can anchor a focus session: what it is, and when it sits. */
internal data class FocusCandidate(
  val blockId: String,
  val itemTitle: String,
  val startAtMs: Long,
  val endAtMs: Long,
)

/**
 * A focus timer anchored to a plan block. The dialog lists the board's blocks that still have
 * time left; choosing one arms a single alarm at the session's end and the receiver turns it
 * into a notification with Done and Defer actions. One session at a time.
 */
@Composable
internal fun FocusTimerDialog(
  visible: Boolean,
  candidates: List<FocusCandidate>,
  formatter: TimeFormatter,
  onStart: (FocusCandidate) -> Unit,
  onCancelActive: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (!visible) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Focus timer") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier =
          Modifier.heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .testTag("plan_focus_dialog"),
      ) {
        if (candidates.isEmpty()) {
          Text(
            "No plan blocks remain in the next day. Apply a plan first, or add a block.",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        candidates.forEach { candidate ->
          TextButton(
            onClick = { onStart(candidate) },
            modifier =
              Modifier.fillMaxWidth()
                .heightIn(min = MinimumTouchTarget)
                .testTag("plan_focus_start_${candidate.blockId}"),
          ) {
            Column(modifier = Modifier.fillMaxWidth()) {
              Text(
                candidate.itemTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                "${formatter.time(candidate.startAtMs)}–${formatter.time(candidate.endAtMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        HorizontalDivider()
        TextButton(
          onClick = onCancelActive,
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_focus_cancel"),
        ) {
          Text("Cancel the running timer")
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_focus_close"),
      ) {
        Text("Done")
      }
    },
    modifier = Modifier.testTag("plan_focus_dialog_root"),
  )
}
