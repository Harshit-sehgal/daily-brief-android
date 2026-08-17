# Agent working notes (Daily Brief)

Agent-owned living notes file, created 2026-08-17. The canonical plan documents are
**read-only** for me — `CLAUDE.md`, `README.md`, `docs/PREMIUM_PRODUCT_PLAN.md`,
`docs/HCI_REDESIGN_PLAN.md`, `docs/HCI_PRINCIPLES.md`, `docs/saas/*`. Any update or
observation about the work lands here, in the work log at the bottom.

## Context snapshot (what the plans say as of today)

### The product
Local-first Android app: device calendar + Notion in one schedule, overlap detection,
optional Gemini summary. Compose UI, Room storage, no DI framework. Two Gradle modules:
`:app` and `:planning-core` (Kotlin Multiplatform: `commonMain` portable half,
`jvmShared` JVM half, both `jvm` and Android depend on `jvmShared`).

Three roots: Home (now/next/conflicts), Calendar (Agenda/Timeline/Week), Plan
(Outline/Board/Schedule map). Plan is the premium wedge: auto-plan, Plan Health
(max-flow multi-schedule capacity — "the crown jewel"), dependencies/critical path,
baselines, scenarios, portfolio rollups, journal + Undo.

### The SaaS programme (`docs/saas/00-programme-plan.md`, branch `saas-extraction`)
- Thesis: intelligent planning for independent consultants (3–8 concurrent client
  projects). Positioning: *Know what you can commit to before you commit to it.*
- Wedge: hybrid — auto-plan + Today free; project depth (Gantt, deps, capacity,
  baselines, scenarios) Pro, $19–29/mo (not locked).
- Stack: Next.js web · TypeScript SaaS API · Kotlin/Ktor planner · Postgres · Expo
  mobile. Current step is specification + engine extraction only.
- Phase 0 committed (`fa89f56`, 203 files); Phase 1 `:planning-core` extraction done
  (`3d05da8`, 47/60 files pure renames, package names preserved);
  Phase 2 first `commonMain` migration done but **uncommitted** (domain model + 7 pure
  files, 22% of engine portable, 1,233 lines).
- Phases 3–7 not started. Verification: `scripts/verify.sh` (fast/full/--device),
  `:planning-core:jvmTest` must be named explicitly or the engine suite is skipped.
- KMP audit lives in `docs/saas/05-kmp-portability-audit.md` — does not exist yet,
  must be written from §4 of the programme plan (Phase 2 checklist).

### Key technical facts that bite
- `commonMain` purity is enforced by `CommonMainPurityTest` (source scan), NOT the
  compiler — `compileCommonMainKotlinMetadata` is SKIPPED (jvm + androidTarget are both
  JVM-family). Fails open; a planted `java.util.Calendar` import compiled silently.
- All date maths is `java.util.Calendar`/`TimeZone`/`SimpleDateFormat` (8 files) — never
  `java.time`. `WorkingCalendarTest` pins Calendar's DST tie-breaks: gap → next valid
  minute (forward), fall-back → **later** standard-time occurrence (kotlinx-datetime and
  `java.time` choose the earlier one). Porting naively flips a tested contract;
  programme plan recommends explicit `AmbiguousLocalTime.LATER_OFFSET` policy.
- Every module compiles against SDK 37.1, never bare 37 (pinned SDK has only 37.1;
  `compileSdk = 37` downloads 37.0 silently and looks hung for ~13 min).
- Kotlin does not smart-cast a `val` from another module — bind a local first
  (`val dayOfWeek = requireNotNull(row.dayOfWeek)`).
- AGP 9: use `com.android.kotlin.multiplatform.library` (not `com.android.library`
  alongside KMP plugin); DSL is `androidLibrary { }` inside `kotlin { }`.
- Journal codecs (`PlanMutationCodec` → `WorkingScheduleMutationCodec` →
  `LegacyPlanCatalogBuilder`) are entangled with the Room migration file — queued as
  Phase 5 untangling before they can move to `planning-core`.
- `room-common` is fully multiplatform → the domain model keeps `@Entity` in `commonMain`.
- Toolchain pinned at `~/.local/android-toolchain` (JDK 21 + SDK); system `java` is 8,
  so `JAVA_HOME` must be set; Gradle runs `--offline` (deps cached). AGP 9.3.1, Gradle
  9.7.0 (SHA-256 pinned), Kotlin/Compose compiler 2.4.10, KSP 2.3.11, Compose BOM
  2026.06.01, Room 2.8.4, compileSdk 37.1, minSdk 24, targetSdk 37.

### Engine defects recorded but deliberately unfixed (behaviour preservation)
1. `AutoPlan` does not treat `dueAt` as a hard constraint (top of backlog; needs
   explicit approval, it is a behaviour change).
2. `AutoPlan` ignores `item.startConstraint`.
3. Only FINISH_TO_START dependencies are scheduled around (others named as
   `UnplacedTask`, disclosed).
4. All-day events become full-day hard blocks in `fixedCommitments`.
5. `AutoPlan` is O(tasks × chunks × free × taken) — fine for a week, concern for
   multi-tenant server.
6. Two buffer code paths (`AutoPlan` applies `bufferMinutes` itself; `PlanHealth` via
   `freeIntervals`).
7. Test coverage inverted against product value: `AutoPlan` 5 tests / 246 lines vs
   `MultiSchedulePlanHealth` 17 tests / 608 lines.

### HCI evidence (last recorded, 2026-08-12)
- 378 JVM tests, 128/128 instrumentation (API 36 `dailybrief` AVD), both lints zero
  issues, all three APK assemblies, R8 analysis, minified release smoke (2551 KiB,
  debug 15,121,984 B, android-test 1,978,432 B, release 2,612,685 B).
- `scripts/verify.sh --device` is the gate; `scripts/emulator.sh` boots the `dailybrief`
  AVD from snapshot (API 36; API 37.1/16 KB AVD `dailybrief_api37_1_16k` also exists,
  needs a fresh recorded run).
- All 24 HCI-principle fixes (P1–P24) are done and guarded by named tests. Still open:
  manual TalkBack/Switch/Voice passes, physical providers, API 24 rendered inspection,
  API 37.1/16 KB execution, boundary matrix at 599/600…1599/1600 dp, 200% text beyond
  named components, task-based usability (T1–T11), RTL/long-localized text, process
  death at every edit point.
- Capture harness: `scripts/capture-preview.sh` + `embed-preview-plates.py`; ends with
  `pm clear` (leaving seeded state breaks the next `verify.sh --device` in misleading
  ways). Harness is `@CaptureOnly`, never part of the gate.

### Open questions for the user (programme plan §9)
1. Approve `AutoPlan` treating `dueAt` as a hard constraint?
2. Kotlin/Native target (~1 GB) for compiler-enforced `commonMain`, or keep the source
   scan test until iOS work starts?
3. Monorepo reshuffle ordering — `apps/ services/ packages/` deferred until the web
   app exists.

## Standing rules for my work
- Never edit the canonical docs; record updates in this file's log instead.
- Run `scripts/verify.sh` before reporting work finished; it is the same task list CI
  runs. `--fast` for the inner loop (~2 s warm).
- `:planning-core:jvmTest` must be named explicitly in any Gradle invocation.
- Don't trust a green build for `commonMain` purity — `CommonMainPurityTest` is the guard.
- Don't trust piped Gradle exit codes (`./gradlew … | tail` reports tail's exit code).
- Use `--rerun-tasks` when proving a move between source sets (compiled classes are
  identical, so up-to-date checks pass legitimately).
- Date arithmetic is where midnight, DST and "the picker hands back UTC" quietly go
  wrong; extract pure objects rather than testing through the database.
- Every module compiles against SDK 37.1 (`compileSdk { version = release(37) { minorApiLevel = 1 } }`).
- Logic worth testing → pure object pattern (`ScheduleAnalysis`, `TimelineLayout`,
  `SyncMergePolicy`, `WorkspacePreferencePolicy`, `EventDraftEdits`, `DayPulse`).
- Room schema changes need a version bump + Migration + exported schema update
  (`app/schemas/`); plan-scoped tables cascade from their board.
- Secrets never touch Room (`SECRET_SETTING_KEYS` → `SecretStore`).
- One schedule lane: `BriefingRepository.SCHEDULE_MUTEX` serializes sync/mutations.
- Deletes fail closed; Undo is compare-and-set; undo windows owned by
  `UndoWindowPolicy` (default 5 min, never the shortest on malformed storage).
- UI conventions: 2-space indent, ~100 cols, trailing commas; four radii
  (`Radius.mark/control/block/container`) + one `InlineIconSize` (guarded by
  `UiConsistencyTest`); 48 dp minimum touch targets; check layouts at 360×640 and
  308×685 dp and at 2× font scale; `FlowRow` for control rows; sp never inside a dp
  container.
- Back leaves the mode, not the screen; keyboard shortcuts must claim key-down;
  manipulation targets may not overlap.

## Remaining work — complete list (2026-08-17)

Consolidated from all plan docs. Grouped by stream; within a stream, ordered as the
source orders it. Checkboxes are for tracking here, not in the canonical docs.

### A. SaaS programme — Phase 2 finish (next per programme plan §7)

- [x] Add a guarded-arithmetic helper to `commonMain`; move `DependencyAnalysis`
      (+257 lines → ~26% portable). Trivial class: `Math.addExact` →
      guarded helper, `Math.floorMod` → `a.mod(b)`.
- [x] Write `docs/saas/05-kmp-portability-audit.md` from programme plan §4.
- [x] Run `scripts/verify.sh`. **Commit not made** — the user has not asked for one;
      everything is left in the working tree (and the §3 correction to the `3d05da8`
      claim is documented in the audit doc instead of the plan).

### B. SaaS programme — Phase 3: engine contract tests

- [x] Canonical scenarios pinning *current* behaviour so Android/iOS/server divergence
      is caught: working calendars (both DST edges), dependencies (all four types),
      critical path and slack, deadline feasibility, multi-schedule capacity, plan
      health, conflict handling, auto-plan determinism. *(Existing per-area suites
      already pin most; `EngineContractTest` adds the planner-level DST week, explicit
      preferred-order determinism, and more.)*
- [x] Characterisation test for defect 1 (missing deadline constraint) that documents
      the behaviour rather than asserting it is correct.

### C. SaaS programme — Phase 4: the specification set (published as an Artifact)

- [x] `01-product-teardown.md` — every capability → KEEP/REDESIGN/MERGE/LATER/DELETE,
      anchored to file paths, filtered by consultant ICP.
- [x] `02-v1-product-spec.md` — IA (Today · Planner · Inbox · Projects →
      Tasks/Board/Timeline · Capacity · Integrations · Settings), progressive
      disclosure, onboarding + 90-second magic moment, replan loop, tier boundary,
      "not in V1" list.
- [x] `03-domain-and-architecture.md` — multi-tenant Postgres model, Room→Postgres
      mapping, service architecture, four invariants needing redesign (SCHEDULE_MUTEX →
      per-tenant advisory lock with fetch outside it; Undo staleness → SERIALIZABLE /
      FOR UPDATE; SecretStore → KMS/envelope with AAD; app-code constraints → real
      Postgres constraints incl. partial unique index on one non-archived default).
      New entity: `Client`. Security: platform Gemini key with per-tenant quota,
      server-side OAuth — changes `activeGeminiKey()` and the Gemini settings surface.
- [x] `04-planner-api-contract.md` — `PlanningRequest` / `PlanningResult` /
      `PlanProposal` / `PlanConflict` / `PlanHealth` from the real signatures.
- [x] `05-kmp-portability-audit.md` — programme plan §4, kept current (same as A).

### D. SaaS programme — Phase 5: the portability project

- [x] Untangle `PlanMutationCodec` → `WorkingScheduleMutationCodec` →
      `LegacyPlanCatalogBuilder` (split the pure builder out of the Room migration
      file), then move journal codecs + 3 test files to `planning-core`.
- [x] Replace `PriorityQueue` in `CriticalPathEngine` (716 lines; must reproduce the
      lexicographic min-heap determinism exactly).
- [ ] The date-time cluster: `WorkingCalendar`, `ScheduleAnalysis`, `IsoDates`,
      `TimelineLayout`, `DayPulse`, `GanttLayout` (1,291 lines) — with the §5 DST
      decision made explicitly (`AmbiguousLocalTime.LATER_OFFSET`); also
      `ScheduleAnalysis.signature` SHA-256 (or brief cache key changes) and
      `GanttLayout`'s BigDecimal/BigInteger stability near Long limits.
      Leverage: clearing `WorkingCalendar` unblocks 1,894 lines downstream; clearing
      `ScheduleAnalysis` unblocks 792. **Blocked this session: `kotlinx-datetime` is
      not in the offline Gradle cache and has no TZ engine anyway; a zone-offset
      engine is its own project. Needs the toolchain owner's decision (open
      question 2).**
- [x] Split `GanttInteraction`: `GanttBlockEditPolicy` / `GanttWorkingBands` are
      domain; `GanttDirectManipulationPolicy` / `GanttDirectManipulationTargets` are
      touch geometry → back in `:app`.
- [ ] Optional: add Kotlin/Native or wasm target (~1 GB toolchain) so the compiler
      enforces `commonMain`; `CommonMainPurityTest` becomes a fast duplicate.

### E. SaaS programme — Phase 6: engine defects (with user approval)

- [ ] Defect 1: `AutoPlan` treats `dueAt` as a hard constraint — explicitly approved
      behaviour change, tests first. (Top of the backlog; open question 1.)
- [ ] Defect 2: `AutoPlan` honours `item.startConstraint`.
- [ ] Defect 4: all-day events no longer become full-day hard blocks (ViewModel feeds
      every calendar row into `fixedCommitments`; filter like
      `ScheduleAnalysis.findConflicts` does).
- [ ] Defect 6: one buffer code path (`AutoPlan` vs `PlanHealth.freeIntervals`).
- (Defect 3: SS/FF/SF scheduling; Defect 5: O(tasks × chunks × free × taken); Defect 7:
  inverted test coverage — recorded, no committed order.)

### F. SaaS programme — Phase 7: the vertical slice (after model/engine frozen)

- [ ] login → connect calendar → create tasks → "Plan my week" → valid schedule →
      apply; then AI assistant (interprets/explains; deterministic code validates and
      commits), billing, email, analytics, observability. Monorepo reshuffle
      (`apps/ services/ packages/`) deferred until the web app exists (open question 3).

### G. Product — PREMIUM_PRODUCT_PLAN open phase items

- [ ] Phase 2: pinch zoom, minimap, Timeline/minimap Today jump, dependency creation
      on the canvas. *(Most of the rest of Phase 2 is marked done.)*
- [ ] Phase 3: explainable scheduling engine proposals, scenario comparison, change
      digest, weekly review. *(Capacity slice done.)*
- [ ] Phase 4 (all): cross-board portfolio Gantt and rollups; baselines, change log,
      restore points, audit UI; baseline-variance + timestamped PDF/image exports and
      print layouts; widgets, shortcuts, notification actions, encrypted
      backup/restore with deterministic restore preview.
- [ ] Typography: Atkinson Hyperlegible / IBM Plex Mono remain candidates only after
      font licensing, glyph, weight, fallback, rendering and 200% text QA.

### H. HCI evidence still open (REDESIGN §8D/§12 PENDING rows, PRINCIPLES §5)

- [ ] API 37.1 / 16 KB execution on `dailybrief_api37_1_16k` AVD — needs a fresh
      recorded run (README documents the exact emulator invocation).
- [ ] Rendered inspection at API 24 (CI runs instrumentation there, local proof does not).
- [ ] Physical-device calendar read/write for supported providers + read-only/refused
      calendars; provider refusal wording on a real provider.
- [ ] Manual assistive-technology passes: TalkBack linear navigation, Switch Access,
      Voice Access label targeting.
- [ ] Hardware keyboard, mouse, trackpad, stylus behaviour.
- [ ] 200% font size, display scaling, dark mode, high contrast, reduced motion, RTL,
      at least one long-localized-text pass.
- [ ] Exact adaptive/state matrix: 599/600, 839/840, 1199/1200, 1599/1600 dp live
      resize; focus + draft + selection preservation across recreation; vertical
      focus/horizontal time anchor by stable ID.
- [ ] Process death during edit, drag preview, commit, provider write, Undo.
- [ ] Task-based usability sessions T1–T11, incl. at least one screen-reader or switch
      user before accessibility sign-off.
- [ ] Rendered empty/loading/permission/refusal/conflict/offline state matrix for the
      *older* screens (predates this work, never inspected as a matrix).

### I. Known open UI items (PRINCIPLES §4b)

- [x] Home's greeting is decoration — largest text on screen, least useful; a root
      title naming the day's shape would earn the space. *(Assessed: the greeting
      already exists as `greeting(nowMs, name)` in `HomeScreen.kt:897`; the item is
      about its *content*. Kept open in spirit — any day-shape title change is a
      wording decision for the user.)*
- [x] Settings opens six accordions on a list that fits; several categories are two
      rows now and opening one closes another. *(Assessed: `SettingsDisclosure`
      accordions already exist in `SettingsScreen.kt:242`. The open question is
      whether the default-open categories still deserve it — a product call, no
      code defect.)*
- [x] Gantt date axis scrolls away with the rows (P24 "not done, named rather than
      glossed"): pinning needs hoisting the axis out of the vertical scroll while
      keeping shared horizontal scroll; the dependency overlay assumes axis-inside —
      a real refactor of the most intricate layout. **Done 2026-08-17** — axis and
      "WORK" header hoisted into a pinned row sharing the canvas's `ScrollState`;
      `GanttAxisRow`/`GanttLabelsHeader` added, overlay y-offsets now row-relative.
      Evidence: `scripts/verify.sh --device` green incl. `ScheduleMapPage`,
      `ScheduleMapProportion`, `GanttSmallScreen`, `GanttDirectManipulation` suites;
      `preview.html` recaptured.

### Open questions for the user (programme plan §9)

1. Approve `AutoPlan` treating `dueAt` as a hard constraint (behaviour change)?
2. Kotlin/Native target (~1 GB) for compiler-enforced `commonMain`, or source scan
   test until iOS work starts?
3. Monorepo reshuffle ordering — deferred until the web app exists; confirm.

## Work log

(Updates from here on. Each entry: date, what changed, evidence — same discipline as
the canonical docs: name the test or command that proves a claim.)
### 2026-08-17 — SaaS Phases 2/3/4 + Phase 5 mechanical + Gantt axis pinning

**SaaS Phase 2 (complete, uncommitted).**
- `GuardedArithmetic.kt` added to `commonMain` (`addExact`/`subtractExact`/`multiplyExact`).
- `DependencyAnalysis.kt` + `CriticalPathEngine.kt` moved `jvmShared` → `commonMain`.
  `java.util.PriorityQueue` replaced by a private lexicographic binary min-heap
  (`MinStringHeap`, appended to `CriticalPathEngine.kt`) — determinism pinned by the
  existing suite. `Math.*Exact` → `GuardedArithmetic` at every call site.
- `docs/saas/05-kmp-portability-audit.md` written and kept current: measured tables
  (commonMain 1,784 / 5,032 lines = 35.5%, root blockers now WorkingCalendar +
  ScheduleAnalysis + IsoDates), the DST policy note, the purity-test-is-the-guard
  correction, and a change log.

**SaaS Phase 3 (complete).** `EngineContractTest.kt` (planning-core jvmTest):
- `a deadline is not yet a constraint` — the defect-1 characterisation (proposal ends
  after `dueAt`, reason still claims "ahead of its due date").
- `a spring-forward week keeps local day semantics end to end` — planner-level DST
  contract through `AutoPlan` on America/New_York 2026-03-08.
- `an explicit order is honoured and the plan is repeatable` — `preferredOrder`
  determinism, tasks never mutated.

**SaaS Phase 4 (complete).** `docs/saas/01-product-teardown.md`, `02-v1-product-spec.md`,
`03-domain-and-architecture.md`, `04-planner-api-contract.md` — written from the real
file paths and engine signatures (see section C above).

**SaaS Phase 5 mechanical (complete); date-time cluster blocked.**
- Codec untangling: `LegacyNameKeys.kt` + `WorkScheduleDefaults.kt` created in
  planning-core jvmShared; `LegacyPlanCatalogBuilder.kt` extracted in `:app`;
  `PlanMigration.kt` cleaned (436 lines, migrations only). Codecs moved to planning-core
  `com.example.data.repository` (package preserved): `PlanMutationCodec`,
  `WorkingScheduleMutationCodec`, `PlanCatalogMutationCodec`, `SavedViewCodec`,
  `WorkingCalendarMapper` + their 5 test files. Internal → public on the codec surface
  the app reads (`encodeStateRecord`, `decodeItemRecordFor`, `decodeBlockRecordFor`,
  `toMutationState` × 4, `PlanCatalogState`, `WorkingScheduleMutationState`, string
  array/object codecs). One stale comment fixed in `SavedViewCodec.kt`.
- `GanttInteraction` split: px/dp geometry (`GanttDirectManipulationPolicy`,
  `GanttManipulationTargets`, `GanttDirectManipulationTargets`) moved to
  `app/src/main/java/com/example/core/GanttDirectManipulation.kt` + its tests to
  `app/src/test/.../GanttDirectManipulationTest.kt`; the engine keeps
  `GanttBlockDraft` (Serializable for `rememberSaveable`), `GanttBlockEditPolicy`,
  `GanttWorkingBands`, `GanttDragTarget`.
- Date-time cluster: **blocked** — no `kotlinx-datetime` in the offline Gradle cache
  (checked), and kotlinx has no TZ engine anyway. From-scratch zone engine = own
  project. Needs open question 2 answered.
- `GanttZoom`-level state: the earlier PriorityQueue/heap work + this pass keeps all
  suites green; JVM tests now 383 (380 + 3 contract tests).

**Product: Gantt axis pinning (P24 "not done" — now done).**
- `GanttScreen.kt`: date axis + "WORK" header hoisted out of the vertical scroll into a
  pinned row that shares the canvas's `horizontalScroll` ScrollState (both scroll
  together; disabled together in move mode). New `GanttAxisRow` + `GanttLabelsHeader`;
  `GanttLinkOverlay` y-offsets are now row-relative (axis no longer inside its
  coordinate space); `GanttCanvas.totalHeight` starts at 0. Pinch still lives on the
  canvas viewport.

**Verification (all green).**
- `scripts/verify.sh --fast`, full `scripts/verify.sh`, and `scripts/verify.sh --device`
  (129 instrumented tests). JVM suite: 383 tests, 0 failures.
- **Emulator gotcha found:** the `dailybrief` snapshot's clock was stale (3 days behind
  the host) → `PlanAnalysisJourneyInstrumentedTest` seeded blocks on a Sunday and
  failed with "60 minutes fall outside configured working time" — looked like a
  regression, was a clock. Fixed with `adb shell date 0817163526.00` (root); re-run
  green. Worth checking the clock before any `--device` run.
- `preview.html` recaptured and re-embedded (17 plates, 1113 KB).

**Not done (documented above):** commit (not requested); date-time cluster (blocked);
engine defects (outside chosen scope); widgets/backup/PDF (teardown says LATER);
Phase 7 slice, manual/HCI evidence, Kotlin/Native (human-only or deferred).
