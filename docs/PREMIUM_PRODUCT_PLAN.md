# Daily Brief premium product plan

Status: Phase 0, the Phase 1A planning foundation, the Phase 1B Plan-native Board/Schedule-map cutover, the Phase 2 direct-manipulation slice, and the Phase 3 Plan Health/capacity slice are locally verified. Saved-view depth, auto-plan and scenarios, change digest, weekly review, and the wider visual/device matrix remain open.

## Product decision

Daily Brief should answer three questions without making the person decode the app:

1. **Home — what matters now?** Focus, next commitment, risk and a small plan snapshot.
2. **Calendar — when is it happening?** Agenda, day timeline and week are views of the same date context.
3. **Plan — how will the work get done?** Outline, Board and Gantt are three representations of app-owned flexible work; Gantt alone overlays calendar-owned commitments as a clearly fixed type.

The selected navigation is `Home · Calendar · Plan`. It is stronger than keeping four or five roots because related views stay together, all roots fit comfortably in phone navigation, and the Gantt remains one tap away without displacing the daily landing page. A drawer was rejected because it hides high-frequency destinations. A dedicated Timeline root was rejected because Agenda, Timeline and Week share the same date and should preserve it when switching.

Settings is global but secondary. Search is global and high-frequency. They therefore sit at the top-right of each root instead of consuming bottom-navigation positions. One contextual create button stays at the bottom-right.

## Signature interaction: the time spine

The distinctive element is a continuous time spine rather than another decorative card treatment. It begins as the selected date in Calendar, becomes the live-now marker in Timeline, and extends through Plan blocks, fixed-commitment outlines, date ticks and today shading in Gantt. Phase 2 adds dependency paths and safe manipulation to the same spine. Switching views preserves the same date whenever that is meaningful.

The existing warm paper, ink, terracotta, teal and deadline palette remains. Distinctiveness should come from structure, legible information density and an instrument-like time treatment—not gradients, glass effects or extra containers. The target type system is an accessible interface face such as Atkinson Hyperlegible Next plus a tabular/monospaced utility face such as IBM Plex Mono for times, durations and variance. Font assets and licensing need their own QA before replacing the current system typography.

## Chosen layout

### Phone

```text
┌──────────────────────────────────┐
│ Calendar             Search  ••• │
│ [ Agenda | Timeline | Week ]     │
├──────────────────────────────────┤
│ selected view                    │
│                                  │
│                            (+)   │
├──────────────────────────────────┤
│   Home       Calendar      Plan  │
└──────────────────────────────────┘
```

Plan uses the same frame with `[ Outline | Board | Gantt ]`. Home places Search and Settings beside the greeting. The create button is reachable from every primary root and opens quick capture with sensible context: selected day in Calendar and Plan Inbox in Plan. New-item sheets can switch between Task and Event without losing the title or notes.

### Wide screens

The same three roots move to a rail. Calendar may place Agenda beside Timeline; Plan’s Outline fixes Inbox in a narrow left rail and gives the workflow ledger the remaining width. A later adaptive pass may place Outline beside the now-Plan-native Gantt canvas on expanded screens. This is adaptive composition, not a separate navigation model.

## Button placement

| Action | Placement | Reason |
| --- | --- | --- |
| Home, Calendar, Plan | bottom bar / wide rail | three stable, high-frequency roots |
| Search and commands | top-right on every root | global, frequent, visible without taking a root |
| Settings | top-right overflow or explicit gear | global, lower frequency |
| Create | one bottom-right floating button | one dominant write action with root context |
| Agenda / Timeline / Week | Calendar segmented control | one date context, three representations |
| Outline / Board / Gantt | Plan segmented control | hierarchy, workflow and schedule remain one tap apart |
| Add task to a section | trailing `+ Task` in Inbox/workflow header | contextual routing without duplicating the global FAB |
| Board → Schedule | labelled action beside the active Plan selector | the highest-value cross-view jump stays visible without becoming a fourth root |
| Board move | adjacent arrows plus `Move task group to…` menu | fast taps and an explicit accessible alternative; no horizontal swipe conflict |
| Gantt add block | trailing `+` on each Plan row | scheduling is reachable without drag and never targets fixed commitments |
| Sync | pull-to-refresh plus command palette | available without permanent toolbar noise |
| Date jump / Today | Calendar header and Timeline/Gantt time spine | close to the context it changes |
| Zoom | 7/30/90-day Gantt range control now; pinch in Phase 2 | discoverable tap target first, faster gesture later |
| Add/remove/reorder Home blocks | Settings now; Home edit mode in Phase 2 | safe fallback today; customization later moves beside its result |

## Gesture contract

Current gestures and Phase 2 targets follow the contract below. Items explicitly marked Phase 2 are design requirements, not shipped behavior; every direct-manipulation operation must gain a visible control and accessibility action before release.

- Swipe left/right in Agenda or Timeline changes the selected day. It must never switch the root view, so date navigation and view navigation cannot conflict.
- Horizontally pan the Gantt canvas with one finger today. Phase 2 adds pinch scaling around the gesture focus while retaining the 7/30/90-day controls.
- Tap a solid Plan bar to edit its task; tap an outlined commitment to inspect its calendar event. Phase 2 adds long-press move mode only for Plan bars; synced calendar commitments remain non-draggable because their source owns the time.
- In Phase 2, dragging a plan bar must show the exact proposed date, working-time effect and dependency impact before commit. Release commits once and leaves Undo visible.
- Before Phase 2 direct manipulation ships, drag handles, keyboard/accessibility increment actions and date fields must provide equivalent non-gesture paths.
- Destructive gestures show their action label before commit. Local event deletion retains recoverable Undo; provider-owned deletion remains ownership-aware and restores the row when the provider refuses. Phase 2 plan-item destructive gestures must add confirmation or recoverable Undo before shipping.

## Premium capability map

Daily Brief should use one **Pro** tier for planning leverage—answering “will this fit, what breaks if I move it, and can I recover?”—while keeping basic organization complete.

| Area | Core product | Pro planning leverage |
| --- | --- | --- |
| Navigation | Home / Calendar / Plan, search, create, all core views | Never paywalled |
| Capture | Event/task switch, Inbox, full editor | natural-language/voice/share parsing and reusable routing templates |
| Home | Now, Next, conflicts and booked/free time | Plan Health, milestone risk, remaining capacity and recovery suggestions |
| Calendar | Agenda, Timeline, Week, CRUD and ordinary filters | named saved views, advanced filters and focus-window rules |
| Plan | Board, direct movement, deadlines and basic progress | Gantt depth, hierarchy, dependencies, baselines, critical path and slack |
| Workload | today’s booked/free summary | weekly capacity, effort budgets, overcommit warnings and estimate calibration |
| Customization | one directly editable layout, theme and density | multiple named layouts, conditional blocks and reusable view templates |
| Safety | immediate Undo for app-owned/local mutations, provider ownership rules and machine-readable export with completeness metadata | scenarios, 30-day history, snapshots, plan restore and polished portfolio/print export |
| Automation | recurrence and manual planning | preview-first auto-plan, rules and explainable replanning |

Market anchors for this boundary include [Asana’s workload/capacity planning](https://help.asana.com/s/article/portfolio-workload-and-universal-workload), [ClickUp’s Gantt dependencies, milestones, critical path and baselines](https://help.clickup.com/hc/en-us/articles/6310249474967-Create-and-share-a-Gantt-view), [Linear’s project timeline](https://linear.app/docs/timeline), and [Motion’s auto-scheduling inputs](https://www.usemotion.com/help/time-management/auto-scheduling). Daily Brief’s opportunity is to make that depth genuinely usable on mobile; Motion currently documents Gantt and saved custom views as desktop features in its [mobile reference](https://www.usemotion.com/help/getting-started/mobile-app/reference-mobile-app).

### 1. Professional Gantt

- hierarchy and collapsible work breakdown structure
- milestones, progress, effort and owner
- finish-to-start, start-to-start, finish-to-finish and start-to-finish dependencies
- lead/lag, constraint dates and explainable scheduling conflicts
- critical path, slack and overdue-path highlighting
- baseline snapshots and variance columns
- saved filters, grouping, density and zoom presets
- workload and capacity overlay
- portfolio rollups across boards
- print/PDF, image and CSV export with a generated-at timestamp

The current slice renders board-scoped `PlanItem`/`PlanBlock` relations, split-work lanes, milestones, progress, unscheduled/off-range states, partial-day working shading, typed dependencies with a non-drag ledger, critical-path highlighting and a visible add-block form. Calendar commitments use a separate outlined row type and cannot be moved as Plan work. App-owned bars are now draggable in an explicit move mode where only Apply writes; drawn dependency paths, rendered slack, baselines, portfolio rollups and export remain later phases.

### 2. Fast timeline access

- persistent Agenda / Timeline / Week switcher
- remembered view, selected date, range and scroll position
- live-now marker, Today jump and minimap/overview rail
- snap-to-working-hours and configurable working week
- conflict heatmap and free-time lens
- multi-day and all-day lanes that remain correct through daylight-saving changes

### 3. Explainable smart planning

- effort and deadline-aware plan suggestions
- split work into app-owned `PlanBlock` chunks around fixed calendar commitments
- preview every move before writing; list the rule and trade-off that caused it
- lock/freeze items and time blocks so replanning cannot move them
- scenarios that can be compared, named, applied or discarded
- “repair this week” suggestions for overload, dependency risk and missed deadlines

Automation must remain advisory until the person applies a preview. Synced Notion data remains read-only, and ambiguous provider failures must not be reported as successful edits.

### 4. Saved views and review loop

- saved views with filter, grouping, sort, visible columns, zoom and collapsed state
- reusable day/week/project templates
- weekly review with planned-versus-done, schedule churn and unfinished work
- brief-to-plan loop: convert a brief recommendation into a plan item with provenance
- change digest since the last review instead of a silent recomputation

### 5. History, safety and portability

- append-only change log for planning mutations
- named baselines and scenario restore points
- local encrypted backup and deterministic restore preview
- ICS/CSV/PDF export and explicit completeness metadata
- source badges and ownership-aware edit affordances everywhere

### 6. Mobile power features

- home-screen widgets for Now, Next and Today progress
- notification actions for Done, Defer and Start focus
- share-sheet quick capture and launcher shortcuts
- offline-first search across events, plan items, boards and commands
- optional focus timer linked to a plan block, not hidden inside the calendar event

Accessibility, basic event creation, source ownership, backup, and the ability to reach core views should not be paywalled. Premium should charge for advanced planning depth, automation, analytics and portfolio scale.

## Data architecture

Do not add premium planning fields directly to `BriefingEvent`. That entity represents both local events and source-owned calendar commitments; sync currently refreshes source-owned times and location while preserving allowed user wording. Mixing planning metadata into it would make ownership and conflict resolution unclear.

Add an app-owned planning overlay instead:

```text
PlanItem
  id, title, notes, boardId, columnId, parentId
  startConstraint, dueAt, effortMinutes, progress, priority, owner
  schedulingMode, locked, createdAt, updatedAt

PlanBlock
  id, planItemId, startAt, endAt, position, locked
  optional linkedEventId

PlanDependency
  id, boardId, predecessorId, successorId, type, lagMinutes

PlanBaseline / PlanBaselineItem
  snapshot dates, duration and progress for variance

SavedView
  surface, filters, grouping, sort, columns, range, zoom, collapsedIds

Scenario / ChangeLog
  proposed or applied mutations with provenance and undo metadata

PlanBoard / PlanColumn
  stable IDs, display names, rank and archival state

WorkSchedule
  weekly availability, exceptions, focus hours and chunk limits
```

Boards and columns need stable IDs before dependencies or saved views refer to them. Existing display-name settings can be migrated without deleting or rewriting event data.

## Delivery plan

The chosen sequence is:

1. finish the Plan-native Board/Gantt cutover and working-calendar contract;
2. add safe move/resize, visible Select, accessible equivalents and Undo;
3. ship the first premium wedge—Plan Health, capacity and overcommit explanations;
4. add dependencies, slack and critical path;
5. add saved views and weekly review;
6. add explainable auto-plan and scenarios;
7. add baselines, history, portfolio rollups and polished export.

This order makes the premium layer depend on trustworthy Plan data and recoverable mutations instead of using advanced visuals as decoration.

### Phase 0 — navigation and honest preview

- [x] Decide Home / Calendar / Plan information architecture.
- [x] Ship the three-root shell, visible search/settings placement and global create action.
- [x] Embed Timeline under Calendar instead of opening it only as a modal.
- [x] Add Board / Gantt switching and a DST-safe, non-draggable Gantt schedule preview.
- [x] Normalize legacy `Today`, `Week`, and `Board` launch choices to Calendar or Plan while preserving Week as the selected Calendar view.
- [x] Update navigation and accessibility journey tests.

Exit: all existing schedule ownership rules remain intact; old settings still open the equivalent place; Agenda, Timeline, Week, Board and Gantt are reachable in at most two taps.

Phase 0 proof (2026-08-11): the pre-foundation gate passed with 127 JVM tests and 23 emulator tests, including real Room-to-Timeline-to-Gantt rendering. Those counts are the historical record of that gate, not current evidence. The image plates in `preview.html` have since been recaptured from the running app by `scripts/capture-preview.sh`; phone portrait and two Expanded compositions are covered, and landscape, foldable, tablet, and desktop captures remain open.

### Phase 1A — planning foundation (complete)

- [x] Create Room v6 entities and a non-destructive migration for stable boards/columns, `PlanItem`, `PlanBlock`, dependencies and saved views.
- [x] Import the legacy board/column catalog with deterministic IDs while leaving existing v5 event, briefing, and legacy setting values untouched; add only the derived active-plan-board setting.
- [x] Introduce a repository boundary that separates calendar commitments from flexible work and rejects invalid progress, effort, hierarchy, blocks and dependency cycles.
- [x] Add a responsive Outline/Inbox alongside Board and Gantt, including task CRUD, effort, due date, priority, milestone and progress controls.
- [x] Add contextual Task/Event capture switching, a Plan FAB, section-local `+ Task`, and command-palette task capture.
- [x] Keep legacy Board/column CRUD and active selection transactionally bridged to stable Plan IDs until Board/Gantt complete their data cutover; block board deletion while it owns Plan tasks.
- [x] Harden exact Unicode identity, same-board/same-section hierarchy, milestone/block exclusivity, cross-board dependency keys, medium-width layout, accessibility labels and editor recreation.
- [x] Add generated v6 schema evidence, pure import/hierarchy/dependency tests, Room-open migration validation and a calendar/planning separation test.
- [x] Make hierarchy editable with parent-aware subtask capture; projection collapse exists for Board and becomes persisted with saved views.

### Phase 1B — Plan-native Board and Schedule map (core cutover locally verified)

- [x] Cut Board over to stable `PlanBoard`, `PlanColumn` and `PlanItem` data with Inbox, workflow and Needs-routing lanes.
- [x] Add progress, milestone, effort, owner and hierarchy-aware task cards; make task-group moves explicit and provide arrows plus `Move to…` controls.
- [x] Use focused transactional progress/move mutations so stale UI snapshots cannot overwrite newer task metadata.
- [x] Cut Gantt over to atomic board-scoped task/block relations while retaining calendar commitments as a separately typed, outlined, non-movable overlay.
- [x] Keep unscheduled and off-range work visible; render split and overlapping Plan blocks in deterministic lanes and milestones only from explicit due dates.
- [x] Add an accessible schedule-block form, locked/flexible state, Today and previous/next range controls, and direct Board → Schedule access.
- [x] Add pure Board/Gantt projection tests and repository coverage for split blocks, append order, focused progress and hierarchy-wide movement.
- [x] Add working calendars; this is an entry gate for duration-changing direct manipulation and auto-plan. Default and alternate reusable schedules now exist with create, rename, default transfer, replacement-checked archive, and per-task assignment, each journaled with Undo.
- [x] Expose the first named saved-view UI. It stores the Plan surface and Gantt range only; collapse, filter, group, and sort persistence is Phase 5 work even though the codec already accepts those fields.
- [x] Add a visible legacy-import disclosure before removing the transactional catalog compatibility bridge.

Current proof (2026-08-11, latest run): `scripts/verify.sh --device` passes with 306 JVM
tests and 83 API 36 emulator tests, and `scripts/smoke-release.sh` launches and navigates
the minified release. The Phase 1 slice below was recorded earlier at 159 JVM tests and
41 emulator tests; those counts are kept as the historical record of that slice, not as
current evidence. A separate full `scripts/verify.sh`
passes debug/release lint, debug and test APK assembly, minified release assembly,
and R8 analysis. The device matrix includes v5→v6 Room-open
schema validation, representative legacy-row preservation assertions, calendar/planning
separation, task create/read/delete persistence, exact-Unicode catalog bridging,
fail-closed planning invariants, Task/Event capture handoff, Gantt-origin capture,
Board→Schedule block creation, focused hierarchy moves, split-block persistence,
and activity recreation during task save. A direct schema diff also confirms the
three pre-existing v5 entity definitions are unchanged in v6. A final phone render
confirmed the empty Outline and title autofocus. Populated Board, Gantt, and schedule-
dialog reviews drove a visible card menu and taller Gantt rows; landscape, tablet,
large-font, and rendered error-state sign-off remain pending.

Exit for this slice: a plan item can exist without masquerading as a calendar event; source-owned commitments are non-draggable in Plan/Gantt; the supported v5→v6 migration preserves existing event, briefing, and legacy-setting data.

### Phase 2 — direct manipulation with safety (core slice locally verified)

- [x] entry gate: working calendars and a mutation/Undo journal are implemented first
- [x] bar resize/move and multi-select for app-owned plan items, with Board multi-move committing as one atomic journaled step
- [x] accessible move/resize actions, keyboard equivalents and large touch handles; the move target and both 48 dp handles are laid out so they never overlap each other or the bar
- [x] optimistic previews, validation, one mutation commit and undo; releasing a pointer never saves
- [x] a visible Select action owns multi-select; long-press remains reserved for move mode
- [x] conflict, slack and critical-path calculations with pure tests
- [x] Home edit mode for add/remove/reorder with a visible Done action
- [ ] pinch zoom, minimap, a Timeline/minimap Today jump, and dependency creation on the canvas

Exit: gesture and button paths produce the same validated mutation; interrupted or invalid gestures make no data change. The first device run of this phase failed exactly this exit criterion — the panel and handles covered the bar, so the gesture path reached no command at all — and passes it now.

### Phase 3 — Plan Health, smart planning and workload (capacity slice locally verified)

- [x] first premium release: Plan Health, effort budgets, remaining capacity and overcommit explanations
- [x] capacity across differing per-task schedules, unioned on the absolute time line so overlapping schedules never double-count an hour
- [x] split effort into `PlanBlock` chunks and lock/freeze controls
- [x] capacity/workload view, overload explanations and repair suggestions
- [ ] explainable scheduling engine that proposes placements, scenario comparison, change digest and weekly review

Exit: suggestions are deterministic for the same inputs, never write before Apply, state which constraints moved each item, and preserve locked/source-owned time.

### Phase 4 — portfolio, history and export

- cross-board portfolio Gantt and rollups
- baselines, change log, restore points and audit UI
- baseline-variance calculations, complete timestamped PDF/image exports and print layouts
- retain a smaller machine-readable core export before this polished portfolio layer
- widgets, shortcuts, notification actions and encrypted backup/restore

Exit: exported and restored plans disclose included ranges and sources; portfolio calculations are covered by scale and partial-data tests.

## Quality gates for every phase

Pending proof before visual/release sign-off: Plan and Task editor at 200% font size; landscape and tablet Plan rendering; rendered Plan error states; API 24 and API 37.1/16 KB execution; real device-calendar ownership behavior; and historical v3/v4 migration fixtures. The current phone reviews and API 36 matrix do not establish those environments.

- 48 dp minimum targets, screen-reader labels and no gesture-only action
- 200% font-size and phone/tablet/landscape layout checks
- daylight-saving, all-day and overlapping-event tests
- source-ownership tests for Notion and device-calendar events
- Room migration tests with old on-device schemas
- unit, lint, debug/release build and instrumented journey checks
- rendered emulator verification for each root, state and empty/error case

## Code anchors

- Root navigation and shared editor: `apps/android/src/main/java/com/example/ui/DailyBriefApp.kt`
- Calendar commitment model: `apps/android/src/main/java/com/example/data/model/BriefingEvent.kt`
- Source merge rules: `apps/android/src/main/java/com/example/data/repository/SyncMergePolicy.kt`
- Workspace preference normalization: `apps/android/src/main/java/com/example/ui/viewmodel/WorkspacePreferencePolicy.kt`
- Day, Timeline, Week and Board surfaces: `apps/android/src/main/java/com/example/ui/screens/`
- Verification entry point: `scripts/verify.sh`
