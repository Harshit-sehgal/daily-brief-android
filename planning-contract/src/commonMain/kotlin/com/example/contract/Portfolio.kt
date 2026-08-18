package com.example.contract

import kotlinx.serialization.Serializable

/**
 * Stage 4.4 wire — scenarios, baselines and the portfolio, all analysis.
 *
 * Scenarios are the same plan under different orderings (the app's `PlanScenarios`); the server
 * computes them from one request and writes nothing — choosing an ordering is the caller's
 * `/v1/plan` call. A baseline is a snapshot of the schedule at one moment; the variance endpoint
 * runs `BaselineVariance` against today, and the summary sentence is the engine's own. The
 * portfolio is the per-project rollup plus the week's applied blocks, which is everything the
 * Gantt folds in with.
 */

/** One ordering a caller wants compared, or omitted for the server's three default approaches. */
@Serializable
data class ScenarioOrderingWire(
  val key: String,
  val name: String,
  val rationale: String,
  val preferredOrder: List<String>,
)

@Serializable
data class ScenarioRequestWire(
  val v: Int,
  val plan: PlanningRequest,
  val orderings: List<ScenarioOrderingWire>? = null,
)

@Serializable
data class ScenarioResultWire(
  val key: String,
  val name: String,
  val rationale: String,
  /** The ordering that produced this result, echoed so "use this" is exact, not re-derived. */
  val preferredOrder: List<String>,
  val result: PlanningResult,
)

@Serializable
data class ScenarioResponseWire(
  val v: Int,
  val scenarios: List<ScenarioResultWire>,
  /** `PlanScenarios.describeSpread`'s sentence: what, if anything, the choice is between. */
  val spread: String,
)

/** A baseline taken at one moment: the id the caller keeps, and what it holds. */
@Serializable
data class BaselineSnapshotWire(
  val v: Int,
  val id: String,
  val workspaceId: String,
  val createdAt: Long,
  val taskCount: Int,
  val blockCount: Int,
)

/** One task's movement since the baseline; `driftMinutes` is positive when it slipped later. */
@Serializable
data class TaskVarianceWire(
  val itemId: String,
  val title: String,
  val baselineStartMs: Long? = null,
  val currentStartMs: Long? = null,
  val baselineMinutes: Int,
  val currentMinutes: Int,
  val driftMinutes: Long? = null,
  val addedSinceBaseline: Boolean = false,
  val removedSinceBaseline: Boolean = false,
)

/** `BaselineVariance.compare`'s wire form; rows are ordered by movement, not by id. */
@Serializable
data class BaselineComparisonWire(
  val v: Int,
  val summary: String,
  val rows: List<TaskVarianceWire>,
)

/** One applied block on the portfolio Gantt. */
@Serializable
data class PortfolioBlockWire(
  val itemId: String,
  val title: String,
  val startAt: Long,
  val endAt: Long,
)

/** One project's row: open work, what is scheduled, and where it sits this week. */
@Serializable
data class PortfolioRowWire(
  val projectId: String,
  val projectName: String,
  val openTasks: Int,
  val doneTasks: Int,
  val statedEffortMinutes: Int,
  val scheduledMinutes: Int,
  val overdueTasks: Int,
  val unestimatedTasks: Int,
  val weekBlocks: List<PortfolioBlockWire>,
)

/** The portfolio for one workspace: per-project rows, in project order, plus the honesty note. */
@Serializable
data class PortfolioResponseWire(
  val v: Int,
  val rows: List<PortfolioRowWire>,
  val note: String,
)