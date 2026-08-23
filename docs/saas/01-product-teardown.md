# Product teardown — every capability, one verdict each

Filtered by the consultant ICP and reconciled with
[`11-strategy-reconciliation.md`](11-strategy-reconciliation.md): an independent consultant
juggling client engagements, internal work and a life that leaks onto the calendar. Every row
names the file(s) that own it, so a verdict is traceable. Verdicts: KEEP (part of the product),
REDESIGN (same need, new shape), MERGE (fold into another capability), LATER (real need, not in
the command-center launch shell), DELETE (dead weight for this ICP).

## Briefing (Today · Home · Week · Calendar)

| Capability | Where | Verdict | Note |
| --- | --- | --- | --- |
| Device calendar + Notion in one agenda | `data/repository/BriefingRepository.kt`, `data/api/DeviceCalendarSync.kt`, `NotionClient.kt` | KEEP | The core promise: one schedule. Server form = calendar connections, Notion becomes one connector among several. |
| Overlap detection + all-day exclusion | `core/ScheduleAnalysis.kt` (planning-core) | KEEP | All-day entries deliberately never clash; pinned by tests. |
| Clash handling on day view | `ui/screens/DayTimelineScreen.kt` | KEEP | The "anything clashing" Home entry point. |
| Gemini daily summary | `data/api/GeminiClient.kt`, `ui/screens/HomeScreen.kt` | MERGE → V1 "Daily brief" card | Personal key pasted on-device is not a SaaS posture (see 03); summary becomes a server-side, quota-managed feature. |
| Home = Now + Up next + clashes | `ui/screens/HomeScreen.kt`, `HomePlanSummary.kt` | KEEP → Today hero | 90-second magic moment anchor; the primary product surface, not a secondary dashboard. |
| Week / Calendar screens | `ui/screens/WeekScreen.kt`, `CalendarScreen.kt` | KEEP | Cheap, already there. |
| Voice / Gemini free-form intent | `GeminiClient.kt` (intent parsing) | LATER | The replan loop in 02 makes this redundant for V1. |

## Plan (the scheduling engine)

| Capability | Where | Verdict | Note |
| --- | --- | --- | --- |
| Board (columns, drag, rank) | `ui/screens/BoardScreen.kt`, `BoardDragPolicy.kt` | KEEP | Workflow stages. |
| Outline view | `ui/screens/PlanOutlineScreen.kt`, `PlanOutlineProjector.kt` | MERGE → Projects→Tasks | Redundant with board on phones; keep the projector logic. |
| Timeline / Gantt with working-time bands, direct manipulation | `ui/screens/GanttScreen.kt`, `core/GanttLayout.kt`, `GanttInteraction.kt` (planning-core) | LATER → project depth | The engine remains valuable; the launch shell exposes a unified Planner first and opens Gantt inside a serious project. |
| Gantt zoom + Today jump + overview strip | `core/GanttZoom.kt`, GanttScreen | KEEP | Pinned by tests. |
| Auto-plan with working schedules | `core/AutoPlan.kt`, `WorkingCalendar.kt` (planning-core) | REDESIGN | Proposal-not-decision stays; deadline handling (defect 1) becomes a first-class contract in V1 (see 04). |
| Plan health (risks, overload, repairs) | `core/PlanHealth.kt`, `MultiSchedulePlanHealth.kt`, `PlanHealthSchedulePolicy.kt` (app) | MERGE → Insights | "Can I take another client?" remains a paid explanation surface; it does not compete with Today. |
| Multi-schedule capacity | `core/MultiSchedulePlanHealth.kt` | REDESIGN → Insights | Preserve max-flow semantics, present three numbers and one repair sentence when the user asks whether work fits. |
| Baselines + variance + restore | `data/repository/PlanBaselineCodec.kt` (app), `core/BaselineVariance.kt` | KEEP | History is the trust story; already journal-based. |
| Undo via journal (both editors) | `data/repository/PlanMutationCodec.kt` (planning-core), journal writers | KEEP | Compare-and-set undo survives the server move (see 03 invariants). |
| Dependencies (4 types, CPM) | `core/CriticalPathEngine.kt`, `GanttDependencyPaths.kt`, `ui/components/DependencyManager.kt` | LATER → project depth | Progressive disclosure; expose when a project needs sequencing, never as an Inbox prerequisite. |
| Plan scenarios / compare | `core/PlanScenarios.kt`, `PortfolioAndScenarioTest.kt`, `PlanAnalysisDialogs.kt` | LATER → Insights | Valuable Pro analysis, not part of the first-run command center. |
| Saved views + pinning | `data/repository/PlanRepository.kt` (`SavedViewCodec`), `ui/screens/PlanScreen.kt` | LATER | Restore the workflow after the core Today/Planner loop is habitual. |
| Export CSV / ICS | `core/PlanExport.kt` (planning-core, commonMain) | MERGE → LATER | Fine on device; V1 server export is a public API, not files. |
| Board printing/PDF | `PlanScreen.kt` | LATER | Consultant decks need PDF; cheap on the server, not a V1 promise. |
| Portfolio rollup + weekly review | `core/PortfolioRollup.kt`, `WeeklyReview.kt` | MERGE → Insights | Same numbers, one secondary surface. |
| Schedule map page (immersive) | `ui/screens/PlanScreen.kt`, `Destination.ScheduleMap` | LATER | Keep the implementation available; do not make an immersive map a V1 navigation root. |
| Plan overflow (`⋮` root): saved views, health, tools, history, export | `ui/screens/PlanOverflow.kt`, `WorkspaceRootHeader.kt` | MERGE → contextual tools | Keep basic History/Undo reachable; advanced tools belong to project detail or Insights. |

## Settings & integration

| Capability | Where | Verdict | Note |
| --- | --- | --- | --- |
| Working schedules editor (windows, overrides, DST) | `ui/components/WorkingScheduleEditor.kt`, `core/WorkingCalendar.kt` | KEEP | The contract in 04 depends on it. |
| Device calendar write-back | `data/api/DeviceCalendarSync.kt`, `SyncMergePolicy.kt` | KEEP | Replaces "create calendar entries" in V1. |
| Notion workspace picker | `NotionClient.kt` | MERGE → Connections | One connector surface. |
| Gemini key management | `data/security/SecretStore.kt` (Keystore) | REDESIGN | Replaced by server-side platform key + per-tenant quota (03). AAD-bound ciphertext design carries over. |
| Inexact alarms + boot re-arm | `receiver/AlarmScheduler.kt`, `BootReceiver.kt` | KEEP | Local reminder needs; the server adds push later. |
| Secret storage rules | `data/security/SecretStore.kt`, `SECRET_SETTING_KEYS` | KEEP | Never in Room; the KMS mapping is in 03. |

## Verdict summary for V1

Core shell: Today, Planner, Inbox, Projects, calendar/task capture, proposal-based planning,
working hours, conflict recovery, daily brief, basic History/Undo, notifications, and billing.
Progressive/paid depth: Capacity/Insights, dependencies, Gantt, scenarios, baselines, portfolio,
and advanced exports. Later: voice intent, PDF decks, widgets, backup, enterprise features, and
deep layout customization. DELETE 0.

Nothing is deleted: the ICP-adjacent features all serve a purpose, and the teardown's job is
ordering, not culling.
