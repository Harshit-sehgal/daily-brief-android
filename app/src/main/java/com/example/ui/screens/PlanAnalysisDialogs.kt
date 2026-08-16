package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.PlanScenario
import com.example.core.PlanScenarios
import com.example.core.PortfolioRollupResult
import com.example.core.TimeFormatter
import com.example.data.model.PlanBaseline
import com.example.data.model.PlanItem
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space
import com.example.ui.viewmodel.BaselineComparisonUiState

/** Dialog bodies never grow past this; everything past it scrolls rather than pushing the buttons off. */
private val DialogBodyMaxHeight = 460.dp

/**
 * Baselines: take one, or pick one to measure against.
 *
 * Restoring is deliberately not offered here. It belongs in the comparison, after the person has
 * seen what restoring would move — an irreversible-looking command with no preview is exactly the
 * kind of thing people learn to avoid using at all.
 */
@Composable
internal fun BaselinesDialog(
  visible: Boolean,
  baselines: List<PlanBaseline>,
  name: String,
  formatter: TimeFormatter,
  onNameChange: (String) -> Unit,
  onCapture: () -> Unit,
  onCompare: (PlanBaseline) -> Unit,
  onDelete: (PlanBaseline) -> Unit,
  onDismiss: () -> Unit,
) {
  if (!visible) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Baselines") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier =
          Modifier.heightIn(max = DialogBodyMaxHeight)
            .verticalScroll(rememberScrollState())
            .testTag("baselines"),
      ) {
        Text(
          "A baseline records where the work sits today, so later you can see what moved.",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
          value = name,
          onValueChange = onNameChange,
          singleLine = true,
          label = { Text("Name this baseline") },
          modifier = Modifier.fillMaxWidth().testTag("baseline_name"),
        )
        TextButton(
          onClick = onCapture,
          enabled = name.isNotBlank(),
          modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("baseline_capture"),
        ) {
          Text("Take a baseline")
        }
        if (baselines.isNotEmpty()) HorizontalDivider()
        baselines.forEach { baseline ->
          Column(modifier = Modifier.testTag("baseline_row_${baseline.id}")) {
            Text(
              baseline.name,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              "Taken ${formatter.mediumDay(baseline.capturedAt)}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
              TextButton(
                onClick = { onCompare(baseline) },
                modifier =
                  Modifier.heightIn(min = MinimumTouchTarget)
                    .testTag("baseline_compare_${baseline.id}"),
              ) {
                Text("Compare")
              }
              TextButton(
                onClick = { onDelete(baseline) },
                modifier =
                  Modifier.heightIn(min = MinimumTouchTarget)
                    .testTag("baseline_delete_${baseline.id}"),
              ) {
                Text("Delete")
              }
            }
          }
        }
        if (baselines.isEmpty()) {
          Text(
            "No baselines yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("baselines_close"),
      ) {
        Text("Close")
      }
    },
    modifier = Modifier.testTag("baselines_dialog"),
  )
}

/**
 * Deleting a baseline is the one destructive act here that no journal can take back.
 *
 * Blocks, tasks and restores are all undoable commands, so they can be offered directly. A baseline
 * is not plan state — it is the only record of where the plan used to be — and it sits one tap from
 * "Compare", so it asks first and names what is being lost, exactly as deleting a saved view does.
 */
@Composable
internal fun DeleteBaselineDialog(
  baseline: PlanBaseline?,
  formatter: TimeFormatter,
  onDismiss: () -> Unit,
  onDelete: (PlanBaseline) -> Unit,
) {
  if (baseline == null) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Delete this baseline?") },
    text = {
      Text(
        "This throws away the record of where the schedule was on " +
          "${formatter.mediumDay(baseline.capturedAt)}, and it cannot be undone. The plan itself is " +
          "unchanged."
      )
    },
    confirmButton = {
      TextButton(
        onClick = { onDelete(baseline) },
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("confirm_delete_baseline"),
      ) {
        Text("Delete")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("cancel_delete_baseline"),
      ) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("delete_baseline_dialog"),
  )
}

/**
 * What has moved since the baseline, and the offer to put it back.
 *
 * Movement in both directions is listed, along with work that appeared afterwards and work that has
 * lost its schedule, because a variance report that only shows slippage teaches people to distrust
 * it. Restore says how many blocks it will rewrite before it does anything, and lands in History as
 * one undoable entry.
 */
@Composable
internal fun BaselineComparisonDialog(
  state: BaselineComparisonUiState?,
  items: List<PlanItem>,
  formatter: TimeFormatter,
  onRestore: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (state == null) return
  val titles = items.associate { it.id to it.title }
  val comparison = state.comparison
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(state.name) },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier =
          Modifier.heightIn(max = DialogBodyMaxHeight)
            .verticalScroll(rememberScrollState())
            .testTag("baseline_comparison"),
      ) {
        Text(
          "Taken ${formatter.mediumDay(state.capturedAt)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(comparison.summary, style = MaterialTheme.typography.bodyMedium)
        comparison.rows.forEach { row ->
          Column {
            Text(
              titles[row.itemId] ?: row.title,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              describeVariance(row.driftMinutes, row.addedSinceBaseline, row.removedSinceBaseline),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onRestore,
        modifier =
          Modifier.heightIn(min = MinimumTouchTarget).testTag("baseline_restore"),
      ) {
        Text("Restore this schedule")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("baseline_comparison_close"),
      ) {
        Text("Close")
      }
    },
    modifier = Modifier.testTag("baseline_comparison_dialog"),
  )
}

/** Plain words for a signed number of minutes; "on time" is only claimed when it is true. */
internal fun describeVariance(driftMinutes: Long?, added: Boolean, removed: Boolean): String =
  when {
    added -> "Scheduled after the baseline was taken"
    removed -> "No longer scheduled"
    driftMinutes == null || driftMinutes == 0L -> "Unmoved"
    else -> {
      val magnitude = kotlin.math.abs(driftMinutes)
      val amount =
        when {
          magnitude >= 1_440L -> countOf(magnitude / 1_440L, "day")
          magnitude >= 60L -> countOf(magnitude / 60L, "hour")
          else -> countOf(magnitude, "minute")
        }
      if (driftMinutes > 0L) "$amount later than the baseline" else "$amount earlier than the baseline"
    }
  }

/** One place for the plural, so a one-minute drift cannot read "1 minutes". */
private fun countOf(count: Long, noun: String): String =
  "$count $noun${if (count == 1L) "" else "s"}"

/**
 * Every board's open work in one place.
 *
 * The note above the rows says whether the totals are exact or a floor. A rollup that quietly treats
 * unestimated work as zero effort makes an over-committed portfolio look comfortable, which is the
 * one thing a rollup must never do.
 */
@Composable
internal fun PortfolioDialog(
  result: PortfolioRollupResult?,
  onDismiss: () -> Unit,
) {
  if (result == null) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Across every plan") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier =
          Modifier.heightIn(max = DialogBodyMaxHeight)
            .verticalScroll(rememberScrollState())
            .testTag("portfolio"),
      ) {
        // The reason to open a rollup at all is the total; per-board rows are the breakdown of it.
        if (result.rows.isNotEmpty()) {
          Text(
            buildString {
              append("${result.rows.size} plan${if (result.rows.size == 1) "" else "s"}")
              append(" · ${result.totalOpenTasks} open")
              if (result.totalUnscheduledMinutes > 0) {
                append(" · ${formatMinutes(result.totalUnscheduledMinutes)} not on the calendar yet")
              }
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("portfolio_total"),
          )
        }
        Text(result.note, style = MaterialTheme.typography.bodyMedium)
        result.rows.forEach { row ->
          Column(modifier = Modifier.testTag("portfolio_row_${row.boardId}")) {
            Text(
              row.boardName,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              buildString {
                append("${row.openTaskCount} open")
                append(", ${row.doneTaskCount} done")
                if (row.unscheduledMinutes > 0) {
                  append(", ${formatMinutes(row.unscheduledMinutes)} not on the calendar yet")
                }
                if (row.overdueTaskCount > 0) append(", ${row.overdueTaskCount} past due")
                if (!row.isComplete) append(" · ${row.unestimatedTaskCount} without an estimate")
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("portfolio_close"),
      ) {
        Text("Close")
      }
    },
    modifier = Modifier.testTag("portfolio_dialog"),
  )
}

/**
 * The same week planned three defensible ways.
 *
 * Choosing one does not write it: it hands that ordering to the ordinary proposal review, where the
 * blocks and the reasons are visible and Apply is still a deliberate act.
 */
@Composable
internal fun PlanScenarioDialog(
  scenarios: List<PlanScenario>,
  formatter: TimeFormatter,
  onChoose: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  if (scenarios.isEmpty()) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Compare approaches") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier =
          Modifier.heightIn(max = DialogBodyMaxHeight)
            .verticalScroll(rememberScrollState())
            .testTag("plan_scenarios"),
      ) {
        Text(
          PlanScenarios.describeSpread(scenarios),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.testTag("plan_scenarios_spread"),
        )
        scenarios.forEach { scenario ->
          Column(modifier = Modifier.testTag("scenario_${scenario.key}")) {
            Text(
              scenario.name,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              scenario.rationale,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              buildString {
                append("${scenario.placedTaskCount} placed")
                if (scenario.unplacedTaskCount > 0) {
                  append(", ${scenario.unplacedTaskCount} left over")
                }
                scenario.lastEndMs?.let { append(" · finishes ${formatter.mediumDay(it)}") }
              },
              style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
              onClick = { onChoose(scenario.key) },
              enabled = scenario.placedTaskCount > 0,
              modifier =
                Modifier.heightIn(min = MinimumTouchTarget)
                  .testTag("scenario_choose_${scenario.key}"),
            ) {
              Text("Review this one")
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_scenarios_close"),
      ) {
        Text("Close")
      }
    },
    modifier = Modifier.testTag("plan_scenarios_dialog"),
  )
}

/** Minutes as a person would say them, never as a bare number of minutes past an hour. */
internal fun formatMinutes(minutes: Int): String {
  if (minutes < 60) return "${minutes}m"
  val hours = minutes / 60
  val rest = minutes % 60
  return if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
}
