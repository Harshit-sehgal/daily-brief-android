# Planner API contract

**Status: FROZEN at v1 by `:planning-contract` (WP-12).** Every type below exists as a wire
DTO in `packages/planning-contract/src/main/kotlin/com/example/contract/`, with golden byte-identical
files in `packages/planning-contract/src/test/resources/golden/` and tolerance rules in
`ApiVersionTest`. Changing a field here without changing the module and its goldens is a
breaking change; bump `PlannerApi.VERSION` and this status block instead.

Derived from the real engine signatures (`AutoPlan.propose`, `WorkingCalendar.propose`,
`CriticalPathEngine.analyze`, `PlanHealth.evaluate`, `MultiSchedulePlanHealth.evaluate`,
`ScheduleAnalysis.findConflicts`). The server exposes these as its planning API; the app's
`BriefingViewModel` already speaks this shape, so the client contract is the engine contract.

## PlanningRequest

The wire form of `AutoPlan.propose`'s inputs:

| Field | Type | Source (engine) | Notes |
| --- | --- | --- | --- |
| `workspaceId` | string | — | tenant scope |
| `rangeStartMs` / `rangeEndMs` | int64 | `AutoPlan.propose` | end must be > start; otherwise a refusal, never a plan |
| `nowMs` | int64 | `AutoPlan.propose` | the past is never planned into |
| `items` | Task[] | `PlanItem` | archived/milestone excluded by the engine |
| `blocks` | ScheduledBlock[] | `PlanBlock` | existing blocks are worked around, never over |
| `fixedCommitments` | Interval[] | `WorkingInterval` | busy time incl. buffers |
| `dependencies` | TaskDependency[] | `PlanDependency` | type ∈ FS/SS/FF/SF, signed lagMinutes |
| `scheduleIdByTaskId` | map | `WorkingCalendarSpec` per item | multi-schedule demand assignment |
| `schedules` | WorkSchedule[] | `WorkingCalendarSpec` | validated before use (`WorkingCalendar.validate`) |
| `preferredOrder` | string[] | `AutoPlan.propose` | scenario ordering; empty = engine order (soonest due, then priority, then plan order) |

## PlanningResult

| Field | Type | Source | Notes |
| --- | --- | --- | --- |
| `proposals` | PlanProposal[] | `PlanProposal` | blocks *it would* create, each with `reason` |
| `unplaced` | UnplacedTask[] | `UnplacedTask` | refused and named, never silently dropped (no effort stated, locked, unsupported link type) |
| `explanation` | string | `AutoPlanResult.explanation` | includes "Nothing is saved until you apply it" |
| `health` | PlanHealth | `PlanHealth.evaluate` | post-commit assessment, same response |

**Contract:** the result is a proposal, never a decision — the API writes nothing. Applying is
a separate `applyProposal` call that goes through the journal (one `AuditEntry`, compare-and-set
undo). A proposal may never overlap a `fixedCommitment` or its buffer, may never overlap an
existing `block`, and every block starts and ends on a whole minute.

## PlanProposal

```
itemId: string
startAt: int64   -- always inside a working interval of the item's schedule
endAt: int64     -- endAt > startAt; chunked at minimum/maximumChunkMinutes
reason: string   -- the sentence that justifies the slot ("working time", "after …", …)
```

## PlanConflict

The wire form of `ScheduleAnalysis.findConflicts` (`Conflict(first, second)`):

| Field | Type | Notes |
| --- | --- | --- |
| `first` / `second` | ExternalEvent | `BriefingEvent` shape incl. `isAllDay` |
| `overlapMs` | int64 | `min(end) - max(start)`, computed, not stored |

**Contract:** an all-day event is never a conflict and never a booked-minute consumer
(`ScheduleAnalysis.isAllDay` is source-of-truth state, never inferred from duration).

## PlanHealth

The wire form of `PlanHealthResult` / `MultiSchedulePlanHealth.evaluate`:

| Field | Type | Notes |
| --- | --- | --- |
| `assessment` | enum | OK / OVERCOMMITTED / … (`PlanHealthAssessment`) |
| `workingMinutes` / `capacityAfterCommitmentsMinutes` | int64 | merged time line, never summed per schedule |
| `overloadMinutes`, `missingEstimateCount`, `unscheduledDemandMinutes` | int64 | a total that cannot be complete must say so — unknown effort is reported, never summed as zero |
| `demands` | PlanTaskDemand[] | per task: remaining effort, scheduled, unscheduled |
| `risks` | PlanRisk[] | code, itemId, explanation, action |
| `repairs` | PlanRepairCandidate[] | what would have to move |
| `warnings` / `explanation` | string[] / string | |

**Contract:** overlapping schedules share one human capacity — the max-flow network
(`MultiSchedulePlanHealth`) must be reproduced exactly; the same hour is one hour of capacity,
not one per schedule. Existing blocks occupy the shared time line for every schedule and are
never moved by an assessment.

## Determinism

The engine is deterministic by contract (pinned by `EngineContractTest`): the same request
twice produces the same plan, `preferredOrder` is honoured without mutating the tasks, and
orderings only ever change *what is compared*, never the plan being compared. This makes
results cacheable by request hash and lets scenario comparison run server-side.

## Deadline turn (V1 mandate)

`AutoPlan.propose` today uses `dueAt` only for ordering and a reason string ("ahead of its due
date") — it is *not* a constraint (engine defect 1, characterised in `EngineContractTest`).
V1 adds a `deadlinePolicy` enum to the request — `SOFT` (today's behaviour, disclosed) or
`HARD` (a proposal that ends after `dueAt` is refused with an `UnplacedTask` naming what would
have to move). `WorkingCalendar.propose` already clamps to `dueAt`; the planner API elevates
that clamp to a first-class response.