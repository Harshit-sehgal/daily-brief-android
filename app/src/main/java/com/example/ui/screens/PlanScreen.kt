package com.example.ui.screens

import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.theme.InlineIconSize
import com.example.ui.theme.Radius
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.remember
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.TimeFormatter
import com.example.core.DayPulse
import com.example.core.PlanHealthAssessment
import com.example.data.model.BriefingEvent
import com.example.data.model.PlanItem
import com.example.data.model.PlanMutation
import com.example.data.model.PlanMutationStatus
import com.example.ui.components.WorkspaceRootHeader
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space
import com.example.ui.theme.WindowWidth
import com.example.ui.theme.gutter
import com.example.ui.theme.windowWidthFor
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.PlanHealthUiState
import kotlinx.coroutines.delay

enum class PlanView(val label: String) {
  Outline("Outline"),
  Board("Board"),
  Gantt("Gantt");

  companion object {
    fun fromStored(value: String?): PlanView = entries.firstOrNull { it.label == value } ?: Outline
  }
}

/** Board and schedule map are representations of the same active planning context. */
@Composable
fun PlanScreen(
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  contentPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onEditTask: (PlanItem) -> Unit,
  onAddTask: (String?) -> Unit,
  onAddSubtask: (PlanItem) -> Unit,
  onOpenPalette: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenScheduleMap: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val storedView by viewModel.planView.collectAsStateWithLifecycle()
  val savedViews by viewModel.savedPlanViews.collectAsStateWithLifecycle()
  val weeklyReview by viewModel.weeklyReview.collectAsStateWithLifecycle()
  val autoPlan by viewModel.autoPlan.collectAsStateWithLifecycle()
  val planItems by viewModel.planItems.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val activeSavedViewId by viewModel.activeSavedPlanViewId.collectAsStateWithLifecycle()
  val planHealth by viewModel.planHealth.collectAsStateWithLifecycle()
  val outlineSort by viewModel.outlineSort.collectAsStateWithLifecycle()
  val outlineGrouping by viewModel.outlineGrouping.collectAsStateWithLifecycle()
  val outlineHideCompleted by viewModel.outlineHideCompleted.collectAsStateWithLifecycle()
  val outlineCollapsed by viewModel.outlineCollapsedIds.collectAsStateWithLifecycle()
  val planColumns by viewModel.planColumns.collectAsStateWithLifecycle()
  val visibleColumnIds by viewModel.visibleBoardColumnIds.collectAsStateWithLifecycle()
  val showImportDisclosure by viewModel.showLegacyPlanDisclosure.collectAsStateWithLifecycle()
  val mutationHistory by viewModel.planMutationHistory.collectAsStateWithLifecycle()
  val baselines by viewModel.planBaselines.collectAsStateWithLifecycle()
  val baselineComparison by viewModel.baselineComparison.collectAsStateWithLifecycle()
  val portfolio by viewModel.portfolio.collectAsStateWithLifecycle()
  val portfolioTimeline by viewModel.portfolioTimeline.collectAsStateWithLifecycle()
  val scenarios by viewModel.planScenarios.collectAsStateWithLifecycle()
  val view = PlanView.fromStored(storedView)
  val windowWidth = LocalWindowWidth.current
  val gutter = windowWidth.gutter
  var showSaveView by rememberSaveable { mutableStateOf(false) }
  var savedViewName by rememberSaveable { mutableStateOf("") }
  var renamingViewId by rememberSaveable { mutableStateOf<String?>(null) }
  var showWeeklyReview by rememberSaveable { mutableStateOf(false) }
  var renameText by rememberSaveable { mutableStateOf("") }
  var pendingDeleteViewId by rememberSaveable { mutableStateOf<String?>(null) }
  var showHistory by rememberSaveable { mutableStateOf(false) }
  var showHealthDetails by rememberSaveable { mutableStateOf(false) }
  var showBaselines by rememberSaveable { mutableStateOf(false) }
  var showViewOptions by rememberSaveable { mutableStateOf(false) }
  var showTools by rememberSaveable { mutableStateOf(false) }
  var showManageViews by rememberSaveable { mutableStateOf(false) }
  var baselineName by rememberSaveable { mutableStateOf("") }
  var pendingDeleteBaselineId by rememberSaveable { mutableStateOf<String?>(null) }
  val selectedSavedView = savedViews.firstOrNull { it.view.id == activeSavedViewId }
  val pdfLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
      if (uri != null) {
        viewModel.exportActivePlanPdf { bytes ->
          context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }
      }
    }
  fun exportPlan(asCalendar: Boolean) {
    viewModel.exportActivePlan(asCalendar) { body, mime ->
      val send =
        Intent(Intent.ACTION_SEND).apply {
          type = mime
          putExtra(Intent.EXTRA_TITLE, "Daily Brief plan export")
          putExtra(Intent.EXTRA_TEXT, body)
        }
      context.startActivity(Intent.createChooser(send, "Export plan"))
    }
  }

  fun exportPlanPdf() {
    pdfLauncher.launch("daily-brief-plan.pdf")
  }

  val childPadding =
    PaddingValues(
      top = Space.sm,
      bottom = contentPadding.calculateBottomPadding(),
    )

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(top = contentPadding.calculateTopPadding())
        .testTag("screen_plan")
  ) {
    WorkspaceRootHeader(
      title = "Plan",
      views = PlanView.entries.map { it.label },
      selectedView = PlanView.entries.indexOf(view),
      onSelectView = { viewModel.setPlanView(PlanView.entries[it].label) },
      onSearch = onOpenPalette,
      onSettings = onOpenSettings,
      tagPrefix = "plan",
      overflow = {
        PlanOverflowMenu(
          onViewOptions = { showViewOptions = true },
          onHealth = { showHealthDetails = true },
          onTools = { showTools = true },
          onHistory = { showHistory = true },
          onExport = ::exportPlan,
          onExportPdf = ::exportPlanPdf,
        )
      },
      modifier = Modifier.padding(horizontal = gutter),
    )

    if (showImportDisclosure) {
      LegacyPlanDisclosure(
        onAcknowledge = viewModel::acknowledgeLegacyPlanDisclosure,
        modifier = Modifier.padding(horizontal = gutter, vertical = Space.xs),
      )
    }

    PlanAdaptiveWorkspace(
      windowWidth = windowWidth,
      // Nothing stands above the work on a phone any more: the header's menu holds the saved
      // views, health, tools and history that used to cost four bands before the first task.
      compactControls = {},
      ledger = {
        PlanLedgerRail(
          views = savedViews,
          activeViewId = activeSavedViewId,
          selectedView = selectedSavedView,
          history = mutationHistory,
          health = planHealth,
          formatter = formatter,
          onApply = viewModel::applySavedPlanView,
          onSave = { showSaveView = true },
          onShowHealth = { showHealthDetails = true },
          onShowHistory = { showHistory = true },
          onDelete = { pendingDeleteViewId = it.view.id },
          onUpdate = viewModel::updateActivePlanView,
          onRename = { saved ->
            renameText = saved.view.name
            renamingViewId = saved.view.id
          },
          onDuplicate = { saved -> viewModel.duplicatePlanView(saved.view.id) },
          onPin = { saved -> viewModel.setPlanViewPinned(saved.view.id, !saved.view.pinned) },
          onReset = viewModel::resetPlanView,
          onWeeklyReview = { showWeeklyReview = true },
          onProposePlan = { viewModel.proposePlan() },
          onCompareScenarios = { viewModel.comparePlanScenarios() },
          onBaselines = { showBaselines = true },
          onPortfolio = { viewModel.openPortfolio() },
          onExport = { asCalendar ->
            viewModel.exportActivePlan(asCalendar) { body, mime ->
              val send =
                Intent(Intent.ACTION_SEND).apply {
                  type = mime
                  putExtra(Intent.EXTRA_TITLE, "Daily Brief plan export")
                  putExtra(Intent.EXTRA_TEXT, body)
                }
              context.startActivity(Intent.createChooser(send, "Export plan"))
            }
          },
          onUndo = viewModel::undoPlanMutation,
          contentBottomPadding = contentPadding.calculateBottomPadding(),
        )
      },
      workspace = {
        PlanWorkspace(
          view = view,
          viewModel = viewModel,
          formatter = formatter,
          childPadding = childPadding,
          onEditEvent = onEditEvent,
          onEditTask = onEditTask,
          onAddTask = onAddTask,
          onAddSubtask = onAddSubtask,
          onOpenScheduleMap = onOpenScheduleMap,
        )
      },
      modifier = Modifier.weight(1f),
    )
  }

  AutoPlanDialog(
    result = autoPlan,
    items = planItems,
    formatter = formatter,
    onApply = viewModel::applyPlanProposal,
    onDismiss = viewModel::dismissPlanProposal,
  )
  PlanViewOptionsDialog(
    visible = showViewOptions,
    sort = outlineSort,
    grouping = outlineGrouping,
    hideCompleted = outlineHideCompleted,
    collapsedCount = outlineCollapsed.size,
    savedViews = savedViews,
    activeViewId = activeSavedViewId,
    lanes = if (view == PlanView.Board) planColumns else emptyList(),
    visibleLaneIds = visibleColumnIds,
    onToggleLane = viewModel::toggleBoardColumnVisible,
    onShowAllLanes = viewModel::showAllBoardColumns,
    onSort = viewModel::setOutlineSort,
    onGrouping = viewModel::setOutlineGrouping,
    onHideCompleted = viewModel::setOutlineHideCompleted,
    onExpandAll = viewModel::expandAllOutlineTasks,
    // Choosing a view is the answer to the question the sheet asked, so the sheet is done.
    onApplyView = { saved ->
      showViewOptions = false
      viewModel.applySavedPlanView(saved)
    },
    onSaveView = {
      showViewOptions = false
      showSaveView = true
    },
    onManageViews = {
      showViewOptions = false
      showManageViews = true
    },
    onDismiss = { showViewOptions = false },
  )
  ManageSavedViewsDialog(
    visible = showManageViews,
    views = savedViews,
    activeViewId = activeSavedViewId,
    onUpdate = viewModel::updateActivePlanView,
    onRename = { saved ->
      renameText = saved.view.name
      renamingViewId = saved.view.id
    },
    onDuplicate = { saved -> viewModel.duplicatePlanView(saved.view.id) },
    onPin = { saved -> viewModel.setPlanViewPinned(saved.view.id, !saved.view.pinned) },
    onDelete = { saved -> pendingDeleteViewId = saved.view.id },
    onReset = viewModel::resetPlanView,
    onDismiss = { showManageViews = false },
  )
  PlanToolsDialog(
    visible = showTools,
    onProposePlan = { viewModel.proposePlan() },
    onCompareScenarios = { viewModel.comparePlanScenarios() },
    onWeeklyReview = { showWeeklyReview = true },
    onBaselines = { showBaselines = true },
    onPortfolio = { viewModel.openPortfolio() },
    onDismiss = { showTools = false },
  )
  BaselinesDialog(
    visible = showBaselines,
    baselines = baselines,
    name = baselineName,
    formatter = formatter,
    onNameChange = { baselineName = it },
    onCapture = {
      viewModel.captureBaseline(baselineName) { saved -> if (saved) baselineName = "" }
    },
    onCompare = { baseline ->
      showBaselines = false
      viewModel.compareBaseline(baseline.id)
    },
    onDelete = { baseline -> pendingDeleteBaselineId = baseline.id },
    onDismiss = { showBaselines = false },
  )
  DeleteBaselineDialog(
    baseline = baselines.firstOrNull { it.id == pendingDeleteBaselineId },
    formatter = formatter,
    onDismiss = { pendingDeleteBaselineId = null },
    onDelete = { baseline ->
      viewModel.deleteBaseline(baseline.id)
      pendingDeleteBaselineId = null
    },
  )
  BaselineComparisonDialog(
    state = baselineComparison,
    items = planItems,
    formatter = formatter,
    onRestore = { baselineComparison?.let { viewModel.restoreBaseline(it.baselineId) } },
    onDismiss = viewModel::dismissBaselineComparison,
  )
  PortfolioDialog(
    result = portfolio,
    timeline = portfolioTimeline,
    onDismiss = viewModel::dismissPortfolio,
  )
  PlanScenarioDialog(
    scenarios = scenarios,
    formatter = formatter,
    onChoose = viewModel::choosePlanScenario,
    onDismiss = viewModel::dismissPlanScenarios,
  )
  WeeklyReviewDialog(
    visible = showWeeklyReview,
    review = weeklyReview,
    formatter = formatter,
    onDismiss = { showWeeklyReview = false },
  )
  RenamePlanViewDialog(
    view = savedViews.firstOrNull { it.view.id == renamingViewId },
    name = renameText,
    onNameChange = { renameText = it },
    onDismiss = { renamingViewId = null },
    onRename = { saved ->
      viewModel.renamePlanView(saved.view.id, renameText) { success ->
        if (success) renamingViewId = null
      }
    },
  )
  SavePlanViewDialog(
    visible = showSaveView,
    name = savedViewName,
    onNameChange = { savedViewName = it },
    onDismiss = { showSaveView = false },
    onSave = {
      viewModel.saveCurrentPlanView(savedViewName) { success ->
        if (success) {
          showSaveView = false
          savedViewName = ""
        }
      }
    },
  )
  DeletePlanViewDialog(
    view = savedViews.firstOrNull { it.view.id == pendingDeleteViewId },
    onDismiss = { pendingDeleteViewId = null },
    onDelete = { saved ->
      viewModel.deleteSavedPlanView(saved)
      pendingDeleteViewId = null
    },
  )
  PlanHealthDialog(
    visible = showHealthDetails,
    state = planHealth,
    formatter = formatter,
    onDismiss = { showHealthDetails = false },
  )
  PlanHistoryDialog(
    visible = showHistory,
    history = mutationHistory,
    formatter = formatter,
    onDismiss = { showHistory = false },
    onUndo = { mutationId ->
      viewModel.undoPlanMutation(mutationId)
      showHistory = false
    },
  )
}

/**
 * Secondary planning controls become a ledger only when the outer window can support it. The
 * active workspace is still composed exactly once; its nested width class reflects the space left
 * after the rail so the existing Outline/Board/Gantt layouts do not receive a false wide signal.
 */
@Composable
internal fun PlanAdaptiveWorkspace(
  windowWidth: WindowWidth,
  compactControls: @Composable () -> Unit,
  ledger: @Composable () -> Unit,
  workspace: @Composable () -> Unit,
  modifier: Modifier = Modifier,
) {
  val ledgerWidth = planLedgerWidthFor(windowWidth)
  if (ledgerWidth == null) {
    Column(modifier = modifier.fillMaxSize().testTag("plan_stacked_workspace")) {
      compactControls()
      Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("plan_active_workspace")) {
        workspace()
      }
    }
  } else {
    Row(modifier = modifier.fillMaxSize().testTag("plan_wide_workspace")) {
      BoxWithConstraints(
        modifier = Modifier.weight(1f).fillMaxHeight().testTag("plan_active_workspace")
      ) {
        val nestedWidth = windowWidthFor(maxWidth)
        CompositionLocalProvider(LocalWindowWidth provides nestedWidth) { workspace() }
      }
      VerticalDivider(
        modifier = Modifier.fillMaxHeight().padding(vertical = Space.sm).testTag("plan_ledger_rule"),
        color = MaterialTheme.colorScheme.outlineVariant,
      )
      Box(
        modifier =
          Modifier.width(ledgerWidth).fillMaxHeight().testTag("plan_ledger_container"),
      ) {
        ledger()
      }
    }
  }
}

internal fun planLedgerWidthFor(width: WindowWidth): Dp? =
  when (width) {
    WindowWidth.Compact,
    WindowWidth.Medium -> null
    WindowWidth.Expanded -> 300.dp
    WindowWidth.Large -> 320.dp
    WindowWidth.ExtraLarge -> 336.dp
  }

@Composable
private fun PlanWorkspace(
  view: PlanView,
  viewModel: BriefingViewModel,
  formatter: TimeFormatter,
  childPadding: PaddingValues,
  onEditEvent: (BriefingEvent) -> Unit,
  onEditTask: (PlanItem) -> Unit,
  onAddTask: (String?) -> Unit,
  onAddSubtask: (PlanItem) -> Unit,
  onOpenScheduleMap: () -> Unit,
) {
  AnimatedContent(
    targetState = view,
    transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
    label = "plan_view",
    modifier = Modifier.fillMaxSize(),
  ) { current ->
    when (current) {
      PlanView.Outline ->
        PlanOutlineScreen(
          viewModel = viewModel,
          formatter = formatter,
          contentPadding = childPadding,
          onAddTask = onAddTask,
          onEditTask = onEditTask,
        )
      PlanView.Board ->
        BoardScreen(
          viewModel = viewModel,
          formatter = formatter,
          contentPadding = childPadding,
          onEditTask = onEditTask,
          onAddTask = onAddTask,
          onAddSubtask = onAddSubtask,
          onOpenGantt = { viewModel.setPlanView(PlanView.Gantt.label) },
        )
      PlanView.Gantt ->
        GanttScreen(
          viewModel = viewModel,
          formatter = formatter,
          contentPadding = childPadding,
          onEditEvent = onEditEvent,
          onEditTask = onEditTask,
          onEnterFocus = onOpenScheduleMap,
        )
    }
  }
}

@Composable
private fun LegacyPlanDisclosure(
  onAcknowledge: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(Radius.block),
    color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
    modifier = modifier.fillMaxWidth().testTag("legacy_plan_disclosure"),
  ) {
    Row(modifier = Modifier.padding(start = Space.md, top = Space.sm, bottom = Space.sm)) {
      Text(
        "Plan kept your existing board and column names for continuity. Calendar and Notion " +
          "items remain fixed commitments; they were not turned into movable Plan tasks.",
        modifier = Modifier.weight(1f),
      )
      TextButton(
        onClick = onAcknowledge,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("ack_plan_import"),
      ) {
        Text("Got it")
      }
    }
  }
}

@Composable
private fun PlanHealthSummary(
  state: PlanHealthUiState,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val copy = planHealthCopy(state)

  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(Radius.block),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = modifier.heightIn(min = MinimumTouchTarget).testTag("plan_health_summary"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // One line that ellipsises. Left to wrap, this chip grew to three lines on a 360 dp phone and
      // took more height than the workspace it was describing. The "HEALTH" prefix went with it:
      // "On track · 37h free" already says what it is, and the label was costing a third of the
      // chip's width to repeat the name of the thing it opens.
      Text(
        text = "${copy.label} · ${copy.summary}",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * One control for everything a saved view can be: which one is applied, and what can be done to it.
 *
 * The button carries the applied view's name, so the row answers "what am I looking at?" before it
 * offers to change it.
 */
@Composable
private fun PlanViewMenu(
  views: List<com.example.data.repository.SavedPlanView>,
  activeViewId: String?,
  selectedView: com.example.data.repository.SavedPlanView?,
  onApply: (com.example.data.repository.SavedPlanView) -> Unit,
  onSave: () -> Unit,
  onUpdate: () -> Unit,
  onRename: (com.example.data.repository.SavedPlanView) -> Unit,
  onDuplicate: (com.example.data.repository.SavedPlanView) -> Unit,
  onPin: (com.example.data.repository.SavedPlanView) -> Unit,
  onDelete: (com.example.data.repository.SavedPlanView) -> Unit,
  onReset: () -> Unit,
  onWeeklyReview: () -> Unit,
  onExport: (Boolean) -> Unit,
  onProposePlan: () -> Unit,
  onCompareScenarios: () -> Unit,
  onBaselines: () -> Unit,
  onPortfolio: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { open = true },
      modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_view_actions"),
    ) {
      Text(
        text = selectedView?.view?.name ?: "Views",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Icon(
        imageVector = Icons.Default.ArrowDropDown,
        contentDescription = null,
        modifier = Modifier.size(InlineIconSize),
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      views.forEach { saved ->
        DropdownMenuItem(
          text = {
            Text(if (saved.view.pinned) "${saved.view.name} · opens this board" else saved.view.name)
          },
          leadingIcon = {
            if (saved.view.id == activeViewId) {
              Icon(Icons.Default.Check, contentDescription = "Applied")
            }
          },
          onClick = {
            open = false
            onApply(saved)
          },
          modifier = Modifier.testTag("plan_view_apply_${saved.view.id}"),
        )
      }
      if (views.isNotEmpty()) HorizontalDivider()
      DropdownMenuItem(
        text = { Text("Save this view…") },
        onClick = {
          open = false
          onSave()
        },
        modifier = Modifier.testTag("save_plan_view"),
      )
      val view = selectedView
      DropdownMenuItem(
        text = { Text("Update to what is on screen") },
        enabled = view != null,
        onClick = {
          open = false
          onUpdate()
        },
        modifier = Modifier.testTag("plan_view_update"),
      )
      DropdownMenuItem(
        text = { Text("Rename…") },
        enabled = view != null,
        onClick = {
          open = false
          view?.let(onRename)
        },
        modifier = Modifier.testTag("plan_view_rename"),
      )
      DropdownMenuItem(
        text = { Text("Duplicate") },
        enabled = view != null,
        onClick = {
          open = false
          view?.let(onDuplicate)
        },
        modifier = Modifier.testTag("plan_view_duplicate"),
      )
      DropdownMenuItem(
        text = {
          Text(if (view?.view?.pinned == true) "Unpin from this board" else "Open this board with it")
        },
        enabled = view != null,
        onClick = {
          open = false
          view?.let(onPin)
        },
        modifier = Modifier.testTag("plan_view_pin"),
      )
      DropdownMenuItem(
        text = { Text("Delete…") },
        enabled = view != null,
        onClick = {
          open = false
          view?.let(onDelete)
        },
        modifier = Modifier.testTag("plan_view_delete"),
      )
      HorizontalDivider()
      DropdownMenuItem(
        text = { Text("Reset to defaults") },
        onClick = {
          open = false
          onReset()
        },
        modifier = Modifier.testTag("plan_view_reset"),
      )
      PlanToolsMenuItems(
        onClose = { open = false },
        onProposePlan = onProposePlan,
        onCompareScenarios = onCompareScenarios,
        onWeeklyReview = onWeeklyReview,
        onBaselines = onBaselines,
        onPortfolio = onPortfolio,
        onExport = onExport,
      )
    }
  }
}

/**
 * A persistent planning ledger for wide windows. It deliberately reads like an operational
 * margin—saved perspectives, current health, then recent changes—rather than a second workspace.
 */
@Composable
internal fun PlanLedgerRail(
  views: List<com.example.data.repository.SavedPlanView>,
  activeViewId: String?,
  selectedView: com.example.data.repository.SavedPlanView?,
  history: List<PlanMutation>,
  health: PlanHealthUiState,
  formatter: TimeFormatter,
  onApply: (com.example.data.repository.SavedPlanView) -> Unit,
  onSave: () -> Unit,
  onShowHealth: () -> Unit,
  onShowHistory: () -> Unit,
  onDelete: (com.example.data.repository.SavedPlanView) -> Unit,
  onUpdate: () -> Unit,
  onRename: (com.example.data.repository.SavedPlanView) -> Unit,
  onDuplicate: (com.example.data.repository.SavedPlanView) -> Unit,
  onPin: (com.example.data.repository.SavedPlanView) -> Unit,
  onReset: () -> Unit,
  onWeeklyReview: () -> Unit,
  onExport: (Boolean) -> Unit,
  onProposePlan: () -> Unit,
  onCompareScenarios: () -> Unit,
  onBaselines: () -> Unit,
  onPortfolio: () -> Unit,
  onUndo: (String) -> Unit,
  contentBottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(
          start = Space.lg,
          top = Space.md,
          end = Space.lg,
          bottom = contentBottomPadding + Space.lg,
        )
        .testTag("plan_ledger"),
  ) {
    Text(
      "PLANNING LEDGER",
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.9.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.semantics { heading() },
    )
    Text(
      "Views, feasibility, and the latest recoverable changes.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = Space.xs, bottom = Space.lg),
    )

    PlanLedgerSectionTitle("Saved views")
    if (views.isEmpty()) {
      Text(
        "No saved views yet. Save the active surface when it becomes a useful working lens.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.sm),
      )
    } else {
      views.forEach { saved ->
        FilterChip(
          selected = saved.view.id == activeViewId,
          onClick = { onApply(saved) },
          label = { Text(if (saved.view.pinned) "${saved.view.name} · opens this board" else saved.view.name) },
          modifier = Modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget),
        )
      }
    }
    PlanViewActionsMenu(
      selectedView = selectedView,
      onUpdate = onUpdate,
      onRename = onRename,
      onDuplicate = onDuplicate,
      onPin = onPin,
      onDelete = onDelete,
      onReset = onReset,
      onWeeklyReview = onWeeklyReview,
      onExport = onExport,
      onProposePlan = onProposePlan,
      onCompareScenarios = onCompareScenarios,
      onBaselines = onBaselines,
      onPortfolio = onPortfolio,
    )
    TextButton(
      onClick = onSave,
      modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("save_plan_view"),
    ) {
      Text("Save current view")
    }
    if (selectedView != null) {
      TextButton(
        onClick = { onDelete(selectedView) },
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("delete_plan_view"),
      ) {
        Text("Delete selected view")
      }
    }

    HorizontalDivider(
      modifier = Modifier.padding(vertical = Space.lg),
      color = MaterialTheme.colorScheme.outlineVariant,
    )
    PlanLedgerSectionTitle("Plan Health")
    PlanHealthLedgerSummary(
      state = health,
      onClick = onShowHealth,
      modifier = Modifier.padding(top = Space.sm),
    )

    HorizontalDivider(
      modifier = Modifier.padding(vertical = Space.lg),
      color = MaterialTheme.colorScheme.outlineVariant,
    )
    PlanLedgerSectionTitle("Recent history")
    if (history.isEmpty()) {
      Text(
        "No journaled changes on this Plan yet.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.sm),
      )
    } else {
      val now = rememberPlanHistoryNow(history)
      history.take(3).forEachIndexed { index, mutation ->
        if (index > 0) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        PlanHistoryEntry(
          mutation = mutation,
          formatter = formatter,
          now = now,
          onUndo = onUndo,
        )
      }
    }
    TextButton(
      onClick = onShowHistory,
      modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_history"),
    ) {
      Text(if (history.size > 3) "View all ${history.size} changes" else "Open history")
    }
  }
}

@Composable
private fun PlanLedgerSectionTitle(title: String) {
  Text(
    title,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.semantics { heading() },
  )
}

@Composable
private fun PlanHealthLedgerSummary(
  state: PlanHealthUiState,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val copy = planHealthCopy(state)
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(Radius.block),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier =
      modifier.fillMaxWidth().heightIn(min = MinimumTouchTarget).testTag("plan_health_summary"),
  ) {
    Column(modifier = Modifier.padding(Space.md)) {
      Text(copy.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
      Text(
        copy.summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.xs),
      )
      Text(
        "Open 7-day details",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Space.sm),
      )
    }
  }
}

private data class PlanHealthCopy(val label: String, val summary: String)

private fun planHealthCopy(state: PlanHealthUiState): PlanHealthCopy {
  val result = state.result
  val label =
    when (result?.assessment) {
      PlanHealthAssessment.ON_TRACK -> "On track"
      PlanHealthAssessment.AT_RISK -> "At risk"
      PlanHealthAssessment.OVERCOMMITTED -> "Overcommitted"
      PlanHealthAssessment.INCOMPLETE_DATA -> "Incomplete data"
      null -> "Unavailable"
    }
  val summary =
    result?.let { health ->
      when (health.assessment) {
        PlanHealthAssessment.ON_TRACK ->
          "${DayPulse.humanDuration(health.freeAfterPlannedMinutes)} free"
        PlanHealthAssessment.OVERCOMMITTED ->
          "${DayPulse.humanDuration(health.overloadMinutes)} over capacity"
        PlanHealthAssessment.AT_RISK -> "${health.risks.size + health.warnings.size} risks"
        PlanHealthAssessment.INCOMPLETE_DATA ->
          "${health.missingEstimateCount} missing estimates"
      }
    } ?: state.unavailableReason.orEmpty()
  return PlanHealthCopy(label, summary)
}

@Composable
private fun PlanHistoryEntry(
  mutation: PlanMutation,
  formatter: TimeFormatter,
  now: Long,
  onUndo: (String) -> Unit,
) {
  val undoable = mutation.isUndoable(now)
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .padding(vertical = Space.sm)
        .testTag("plan_history_entry_${mutation.id}"),
  ) {
    Text(mutation.summary, style = MaterialTheme.typography.bodyMedium)
    Text(
      "${formatter.mediumDay(mutation.createdAt)} · ${formatter.time(mutation.createdAt)} · " +
        mutation.statusLabel(now),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = Space.xs),
    )
    if (undoable) {
      TextButton(
        onClick = { onUndo(mutation.id) },
        modifier =
          Modifier.heightIn(min = MinimumTouchTarget)
            .testTag("plan_history_undo_${mutation.id}"),
      ) {
        Text("Undo")
      }
    }
  }
}

private fun PlanMutation.isUndoable(now: Long): Boolean =
  status == PlanMutationStatus.APPLIED && (expiresAt?.let { it > now } ?: true)

private fun PlanMutation.statusLabel(now: Long): String =
  when {
    isUndoable(now) -> "Undo available"
    status == PlanMutationStatus.UNDONE -> "Undone"
    else -> "Expired"
  }

/** Keeps time-sensitive Undo controls honest without running a permanent screen-level ticker. */
@Composable
private fun rememberPlanHistoryNow(history: List<PlanMutation>): Long {
  val now by
    produceState(initialValue = System.currentTimeMillis(), key1 = history) {
      while (true) {
        val current = System.currentTimeMillis()
        value = current
        val nextExpiry =
          history
            .asSequence()
            .filter { it.status == PlanMutationStatus.APPLIED }
            .mapNotNull(PlanMutation::expiresAt)
            .filter { it > current }
            .minOrNull() ?: break
        delay((nextExpiry - current).coerceIn(1L, 1_000L))
      }
    }
  return now
}

@Composable
private fun SavePlanViewDialog(
  visible: Boolean,
  name: String,
  onNameChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onSave: () -> Unit,
) {
  if (!visible) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Save this Plan view") },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("View name") },
        supportingText = { Text("Saves this surface and its Gantt range.") },
        singleLine = true,
        modifier = Modifier.testTag("saved_view_name"),
      )
    },
    confirmButton = {
      TextButton(
        onClick = onSave,
        enabled = name.isNotBlank() && name.trim().length <= 80,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("confirm_save_plan_view"),
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinimumTouchTarget)) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun DeletePlanViewDialog(
  view: com.example.data.repository.SavedPlanView?,
  onDismiss: () -> Unit,
  onDelete: (com.example.data.repository.SavedPlanView) -> Unit,
) {
  if (view == null) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Delete saved view?") },
    text = { Text("This removes only \"${view.view.name}\". Plan tasks are unchanged.") },
    confirmButton = {
      TextButton(
        onClick = { onDelete(view) },
        modifier =
          Modifier.heightIn(min = MinimumTouchTarget).testTag("confirm_delete_plan_view"),
      ) {
        Text("Delete")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinimumTouchTarget)) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun PlanHealthDialog(
  visible: Boolean,
  state: PlanHealthUiState,
  formatter: TimeFormatter,
  onDismiss: () -> Unit,
) {
  if (!visible) return
  val result = state.result
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Plan Health · next 7 days") },
    text = {
      Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        if (result == null) {
          Text(state.unavailableReason ?: "Plan Health is unavailable.")
        } else {
          Text(result.explanation)
          Text(
            "${formatter.mediumDay(state.rangeStart)} – ${formatter.mediumDay(state.rangeEnd - 1)}",
            modifier = Modifier.padding(top = Space.sm),
          )
          Text(
            "Capacity after commitments: ${result.capacityAfterCommitmentsMinutes} min\n" +
              "Scheduled Plan work: ${result.scheduledPlanMinutes} min\n" +
              "Unscheduled demand: ${result.unscheduledDemandMinutes} min\n" +
              "Free after planned work: ${result.freeAfterPlannedMinutes} min",
            modifier = Modifier.padding(top = Space.md),
          )
          if (result.warnings.isNotEmpty()) {
            Text("Warnings", modifier = Modifier.padding(top = Space.md))
            result.warnings.forEach { Text("• $it") }
          }
          if (result.risks.isNotEmpty()) {
            Text("Risks", modifier = Modifier.padding(top = Space.md))
            result.risks.forEach { Text("• ${it.explanation}") }
          }
          if (result.repairs.isNotEmpty()) {
            Text("Suggested next steps", modifier = Modifier.padding(top = Space.md))
            result.repairs.forEach { repair ->
              Text("• ${repair.action}: ${repair.explanation}")
            }
          }
          Text(
            "Uses active-board tasks, Plan blocks, fixed calendar commitments assigned to this " +
              "Plan, dependencies, and the task-resolved working schedules. Overlapping " +
              "schedules share one human timeline; unresolved schedules fail closed instead of " +
              "inventing capacity. Missing estimates are never treated as zero.",
            modifier = Modifier.padding(top = Space.md),
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinimumTouchTarget)) {
        Text("Close")
      }
    },
  )
}

@Composable
private fun PlanHistoryDialog(
  visible: Boolean,
  history: List<PlanMutation>,
  formatter: TimeFormatter,
  onDismiss: () -> Unit,
  onUndo: (String) -> Unit,
) {
  if (!visible) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Recent Plan changes") },
    text = {
      Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        if (history.isEmpty()) {
          Text("No journaled changes on this Plan yet.")
        } else {
          val now = rememberPlanHistoryNow(history)
          history.forEach { mutation ->
            PlanHistoryEntry(
              mutation = mutation,
              formatter = formatter,
              now = now,
              onUndo = onUndo,
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinimumTouchTarget)) {
        Text("Close")
      }
    },
  )
}

/**
 * What can be done to the view that is currently applied.
 *
 * One menu rather than a row of icons: these are low-frequency commands, and a labelled list says
 * what each one does to a *view* — never to the work the view is showing.
 */
@Composable
private fun PlanViewActionsMenu(
  selectedView: com.example.data.repository.SavedPlanView?,
  onUpdate: () -> Unit,
  onRename: (com.example.data.repository.SavedPlanView) -> Unit,
  onDuplicate: (com.example.data.repository.SavedPlanView) -> Unit,
  onPin: (com.example.data.repository.SavedPlanView) -> Unit,
  onDelete: (com.example.data.repository.SavedPlanView) -> Unit,
  onReset: () -> Unit,
  onWeeklyReview: () -> Unit,
  onExport: (Boolean) -> Unit,
  onProposePlan: () -> Unit,
  onCompareScenarios: () -> Unit,
  onBaselines: () -> Unit,
  onPortfolio: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { open = true },
      modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("plan_view_actions"),
    ) {
      Text("View actions")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      val view = selectedView
      DropdownMenuItem(
        text = { Text("Update to what is on screen") },
        enabled = view != null,
        onClick = {
          open = false
          onUpdate()
        },
        modifier = Modifier.testTag("plan_view_update"),
      )
      DropdownMenuItem(
        text = { Text("Rename…") },
        enabled = view != null,
        onClick = {
          open = false
          view?.let(onRename)
        },
        modifier = Modifier.testTag("plan_view_rename"),
      )
      DropdownMenuItem(
        text = { Text("Duplicate") },
        enabled = view != null,
        onClick = {
          open = false
          view?.let(onDuplicate)
        },
        modifier = Modifier.testTag("plan_view_duplicate"),
      )
      DropdownMenuItem(
        text = { Text(if (view?.view?.pinned == true) "Unpin from this board" else "Open this board with it") },
        enabled = view != null,
        onClick = {
          open = false
          view?.let(onPin)
        },
        modifier = Modifier.testTag("plan_view_pin"),
      )
      DropdownMenuItem(
        text = { Text("Delete…") },
        enabled = view != null,
        onClick = {
          open = false
          view?.let(onDelete)
        },
        modifier = Modifier.testTag("plan_view_delete"),
      )
      HorizontalDivider()
      DropdownMenuItem(
        text = { Text("Reset to defaults") },
        onClick = {
          open = false
          onReset()
        },
        modifier = Modifier.testTag("plan_view_reset"),
      )
      PlanToolsMenuItems(
        onClose = { open = false },
        onProposePlan = onProposePlan,
        onCompareScenarios = onCompareScenarios,
        onWeeklyReview = onWeeklyReview,
        onBaselines = onBaselines,
        onPortfolio = onPortfolio,
        onExport = onExport,
      )
    }
  }
}

/**
 * The commands that act on the plan itself rather than on a saved view.
 *
 * Both the compact menu and the wide rail's menu end with exactly this list, from one definition —
 * a command that exists in one window size and not the other is a bug people report as "it moved".
 * The two labelled groups exist because a flat list of nine is a list nobody reads.
 */
@Composable
private fun PlanToolsMenuItems(
  onClose: () -> Unit,
  onProposePlan: () -> Unit,
  onCompareScenarios: () -> Unit,
  onWeeklyReview: () -> Unit,
  onBaselines: () -> Unit,
  onPortfolio: () -> Unit,
  onExport: (Boolean) -> Unit,
) {
  PlanMenuSectionLabel("Plan tools")
  DropdownMenuItem(
    text = { Text("Plan my week…") },
    onClick = {
      onClose()
      onProposePlan()
    },
    modifier = Modifier.testTag("plan_auto_plan"),
  )
  DropdownMenuItem(
    text = { Text("Compare approaches…") },
    onClick = {
      onClose()
      onCompareScenarios()
    },
    modifier = Modifier.testTag("plan_scenarios_open"),
  )
  DropdownMenuItem(
    text = { Text("Weekly review…") },
    onClick = {
      onClose()
      onWeeklyReview()
    },
    modifier = Modifier.testTag("plan_weekly_review"),
  )
  DropdownMenuItem(
    text = { Text("Baselines…") },
    onClick = {
      onClose()
      onBaselines()
    },
    modifier = Modifier.testTag("plan_baselines_open"),
  )
  DropdownMenuItem(
    text = { Text("Across every plan…") },
    onClick = {
      onClose()
      onPortfolio()
    },
    modifier = Modifier.testTag("plan_portfolio_open"),
  )
  PlanMenuSectionLabel("Export")
  DropdownMenuItem(
    text = { Text("Tasks (CSV)") },
    onClick = {
      onClose()
      onExport(false)
    },
    modifier = Modifier.testTag("plan_export_csv"),
  )
  DropdownMenuItem(
    text = { Text("Schedule (calendar)") },
    onClick = {
      onClose()
      onExport(true)
    },
    modifier = Modifier.testTag("plan_export_ics"),
  )
}

/** A heading inside a menu: it names the group without pretending to be a command. */
@Composable
private fun PlanMenuSectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier =
      Modifier.padding(start = Space.md, end = Space.md, top = Space.sm, bottom = Space.xs)
        .semantics { heading() },
  )
}

/** Renaming a view changes its label, never what it shows. */
@Composable
private fun RenamePlanViewDialog(
  view: com.example.data.repository.SavedPlanView?,
  name: String,
  onNameChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onRename: (com.example.data.repository.SavedPlanView) -> Unit,
) {
  if (view == null) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Rename this view") },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("View name") },
        supportingText = { Text("The view keeps everything it shows.") },
        singleLine = true,
        modifier = Modifier.testTag("rename_view_name"),
      )
    },
    confirmButton = {
      TextButton(
        onClick = { onRename(view) },
        enabled = name.isNotBlank() && name.trim().length <= 80,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("confirm_rename_plan_view"),
      ) {
        Text("Rename")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinimumTouchTarget)) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("rename_view_dialog"),
  )
}


/**
 * The week as the journal recorded it.
 *
 * A mirror rather than a scoreboard: planned time, what finished, what carried over, how much the
 * plan moved, and what was captured. Where the data cannot support a number it says so instead of
 * rounding the week into something more flattering.
 */
@Composable
private fun WeeklyReviewDialog(
  visible: Boolean,
  review: com.example.core.WeeklyReviewResult?,
  formatter: TimeFormatter,
  onDismiss: () -> Unit,
) {
  if (!visible) return
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Weekly review") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.testTag("weekly_review"),
      ) {
        if (review == null || !review.isComplete) {
          Text(
            review?.unavailableReason ?: "Choose a plan board to review its week.",
            style = MaterialTheme.typography.bodyMedium,
          )
          return@Column
        }
        Text(
          "${formatter.mediumDay(review.rangeStartMs)} – ${formatter.mediumDay(review.rangeEndMs - 1)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        review.findings.forEach { finding ->
          Column {
            Text(
              "${finding.label}: ${finding.value}",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              finding.detail,
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
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("weekly_review_close"),
      ) {
        Text("Close")
      }
    },
    modifier = Modifier.testTag("weekly_review_dialog"),
  )
}


/**
 * What the planner would do, before it does any of it.
 *
 * Each proposal carries the reason it chose that slot, and everything the planner refused to place
 * is listed with its reason too — an auto-planner that quietly drops the awkward half of your work
 * is worse than none. Apply writes the whole set as one History entry, or nothing.
 */
@Composable
private fun AutoPlanDialog(
  result: com.example.core.AutoPlanResult?,
  items: List<PlanItem>,
  formatter: TimeFormatter,
  onApply: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (result == null) return
  val titles = items.associate { it.id to it.title }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Plan my week") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        modifier =
          Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).testTag("auto_plan"),
      ) {
        Text(result.explanation, style = MaterialTheme.typography.bodyMedium)
        result.proposals.forEach { proposal ->
          Column {
            Text(
              "${titles[proposal.itemId] ?: "Task"} · ${formatter.mediumDay(proposal.startAt)} " +
                "${formatter.time(proposal.startAt)}-${formatter.time(proposal.endAt)}",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              proposal.reason,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        if (result.unplaced.isNotEmpty()) {
          Text(
            "Not planned",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("auto_plan_unplaced"),
          )
          result.unplaced.forEach { unplaced ->
            Text(
              "${titles[unplaced.itemId] ?: "Task"} - ${unplaced.reason}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onApply,
        enabled = result.proposals.isNotEmpty(),
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("auto_plan_apply"),
      ) {
        Text("Apply")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("auto_plan_cancel"),
      ) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("auto_plan_dialog"),
  )
}
