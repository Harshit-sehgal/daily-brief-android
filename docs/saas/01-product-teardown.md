# Product teardown — every capability, one verdict each

Filtered by the consultant ICP: an independent consultant juggling client engagements, internal
work and a life that leaks onto the calendar. Every row names the file(s) that own it, so a
verdict is traceable. Verdicts: KEEP (as is), REDESIGN (same need, new shape), MERGE (fold into
another capability), LATER (real need, not for V1), DELETE (dead weight for this ICP).

## Briefing (Today · Home · Week · Calendar)

| Capability | Where | Verdict | Note |
| --- | --- | --- | --- |
| Device calendar + Notion in one agenda | `data/repository/BriefingRepository.kt`, `data/api/DeviceCalendarSync.kt`, `NotionClient.kt` | KEEP | The core promise: one schedule. Server form = calendar connections, Notion becomes one connector among several. |
| Overlap detection + all-day exclusion | `core/ScheduleAnalysis.kt` (planning-core) | KEEP | All-day entries deliberately never clash; pinned by tests. |
| Clash handling on day view | `ui/screens/DayTimelineScreen.kt` | KEEP | The "anything clashing" Home entry point. |
| Gemini daily summary | `data/api/GeminiClient.kt`, `ui/screens/HomeScreen.kt` | MERGE → V1 "Daily brief" card | Personal key pasted on-device is not a SaaS posture (see 03); summary becomes a server-side, quota-managed feature. |
| Home = Now + Up next + clashes | `ui/screens/HomeScreen.kt`, `HomePlanSummary.kt` | KEEP | 90-second magic moment anchor. |
| Week / Calendar screens | `ui/screens/WeekScreen.kt`, `CalendarScreen.kt` | KEEP | Cheap, already there. |
| Voice / Gemini free-form intent | `GeminiClient.kt` (intent parsing) | LATER | The replan loop in 02 makes this redundant for V1. |

## Plan (the scheduling engine)

| Capability | Where | Verdict | Note |
| --- | --- | --- | --- |
| Board (columns, drag, rank) | `ui/screens/BoardScreen.kt`, `BoardDragPolicy.kt` | KEEP | Workflow stages. |
| Outline view | `ui/screens/PlanOutlineScreen.kt`, `PlanOutlineProjector.kt` | MERGE → Projects→Tasks | Redundant with board on phones; keep the projector logic. |
| Timeline / Gantt with working-time bands, direct manipulation | `ui/screens/GanttScreen.kt`, `core/GanttLayout.kt`, `GanttInteraction.kt` (planning-core) | KEEP | The differentiation. Server must reproduce the layout deterministically. |
| Gantt zoom + Today jump + overview strip | `core/GanttZoom.kt`, GanttScreen | KEEP | Pinned by tests. |
| Auto-plan with working schedules | `core/AutoPlan.kt`, `WorkingCalendar.kt` (planning-core) | REDESIGN | Proposal-not-decision stays; deadline handling (defect 1) becomes a first-class contract in V1 (see 04). |
| Plan health (risks, overload, repairs) | `core/PlanHealth.kt`, `MultiSchedulePlanHealth.kt`, `PlanHealthSchedulePolicy.kt` (app) | KEEP | "Can I take another client?" — crown jewel; keep max-flow semantics. |
| Multi-schedule capacity | `core/MultiSchedulePlanHealth.kt` | REDESIGN → Capacity tab | "Client A hours vs internal hours" per 03. |
| Baselines + variance + restore | `data/repository/PlanBaselineCodec.kt` (app), `core/BaselineVariance.kt` | KEEP | History is the trust story; already journal-based. |
| Undo via journal (both editors) | `data/repository/PlanMutationCodec.kt` (planning-core), journal writers | KEEP | Compare-and-set undo survives the server move (see 03 invariants). |
| Dependencies (4 types, CPM) | `core/CriticalPathEngine.kt`, `GanttDependencyPaths.kt`, `ui/components/DependencyManager.kt` | KEEP | Generalized precedence already handles SS/FF/SF. |
| Plan scenarios / compare | `core/PlanScenarios.kt`, `PortfolioAndScenarioTest.kt`, `PlanAnalysisDialogs.kt` | KEEP | Compare is a V1 differentiator; `preferredOrder` API already exists. |
| Saved views + pinning | `data/repository/PlanRepository.kt` (`SavedViewCodec`), `ui/screens/PlanScreen.kt` | KEEP | Server-side saved views are the multi-device story. |
| Export CSV / ICS | `core/PlanExport.kt` (planning-core, commonMain) | MERGE → LATER | Fine on device; V1 server export is a public API, not files. |
| Board printing/PDF | `PlanScreen.kt` | LATER | Consultant decks need PDF; cheap on the server, not a V1 promise. |
| Portfolio rollup + weekly review | `core/PortfolioRollup.kt`, `WeeklyReview.kt` | MERGE → Capacity | Same numbers, one surface. |
| Schedule map page (immersive) | `ui/screens/PlanScreen.kt`, `Destination.ScheduleMap` | KEEP | Already a page with its own window. |
| Plan overflow (`⋮` root): saved views, health, tools, history, export | `ui/screens/PlanOverflow.kt`, `WorkspaceRootHeader.kt` | KEEP | One focused dialog; the 03 model turns these into tabs. |

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

KEEP 18 · REDESIGN 4 (auto-plan deadline, capacity surface, Gemini summary, key management) ·
MERGE 4 (outline→board, rollup/review→capacity, Notion→connections, export→API) · LATER 6
(voice intent, PDF, widgets, backup, multi-window polish, physical-device calendars) · DELETE 0.

Nothing is deleted: the ICP-adjacent features all serve a purpose, and the teardown's job is
ordering, not culling.