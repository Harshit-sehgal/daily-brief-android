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

### The SaaS programme (`docs/saas/00-programme-plan.md`)
- Thesis: intelligent planning for independent consultants (3–8 concurrent client
  projects). Positioning: *Know what you can commit to before you commit to it.*
- Wedge: hybrid — auto-plan + Today free; project depth (Gantt, deps, capacity,
  baselines, scenarios) Pro, $19–29/mo (not locked).
- Stack: Next.js web · TypeScript SaaS API · Kotlin/Ktor planner · Postgres · Expo
  mobile. Portability, schema, vertical slice, depth, and mobile MVP are complete; the
  current step is final external/manual evidence for the web and mobile product.
- Phase 0 committed (`fa89f56`); Phase 1 extraction (`3d05da8`); Phase 2 migrations
  (`17cdc00`, `86e62ad`); Phase 5 codec untangling + `CriticalPathEngine` heap
  (`f87635b`); Phase 3 contract tests (`b649ac7`); Phase 4 spec set (`b0ecbd3`);
  engine defects 1/2/4/6 (`b11ad72`); **Stage 1.1 date-time port (`b436b1e`,
  `e440cd3`)** — engine core 74% portable, whole module 59% (`c097596`).
- Scope deviations: **all closed** — see `06-scope-deviations.md` (D1 resolved by
  amending `02`, D2 accepted, D3/D4/D5/D7 fixed in code).
- **Forward plan: `07-roadmap.md` (the why and order); execution detail:
  `08-work-packages.md` (WP-1..WP-16 with files, steps, acceptance tests, traps).**
- Verification: `scripts/verify.sh` (fast/full/--device), `:planning-core:jvmTest`
  must be named explicitly or the engine suite is skipped.
- Baseline (2026-08-17, WP-1 start): **222 engine tests / 30 suites, 179 app tests /
  40 suites, zero failures.**

### Key technical facts that bite
- `commonMain` purity is enforced by `CommonMainPurityTest` (source scan), NOT the
  compiler — `compileCommonMainKotlinMetadata` is SKIPPED (jvm + androidTarget are both
  JVM-family). Fails open; a planted `java.util.Calendar` import compiled silently.
  WP-7 (non-JVM target) is what hands the job back to the compiler.
- **The engine's dates are `kotlinx-datetime` 0.8.0** (adopted `b436b1e`). On Android it
  is `java.time`-backed, which is why `:app` has `isCoreLibraryDesugaringEnabled` +
  `desugar_jdk_libs` (minSdk 24 < java.time's API 26). `java.util.Calendar` is gone
  from `commonMain`; its DST tie-breaks — gap → next valid minute, fall-back → **later**
  standard-time occurrence — are now named code in `AmbiguousLocalTime.LATER_OFFSET`,
  and `WorkingCalendarTest` pins them. `IsoDatesTest` still builds its expectations
  with `java.util.Calendar` on purpose (jvmTest, allowed).
- Every module compiles against SDK 37.1, never bare 37 (pinned SDK has only 37.1;
  `compileSdk = 37` downloads 37.0 silently and looks hung for ~13 min).
- Kotlin does not smart-cast a `val` from another module — bind a local first
  (`val dayOfWeek = requireNotNull(row.dayOfWeek)`).
- AGP 9: use `com.android.kotlin.multiplatform.library` (not `com.android.library`
  alongside KMP plugin); DSL is `androidLibrary { }` inside `kotlin { }`.
- The three journal codecs (`PlanMutationCodec`, `PlanCatalogMutationCodec`,
  `WorkingScheduleMutationCodec`) are one sealed `PlanMutationState` hierarchy — they
  move to `commonMain` together or not at all (WP-5), gated on `LegacyNameKeys` (WP-4,
  `expect`/`actual` — stored NFKC name keys) and `WorkingCalendarMapper` (WP-6).
- `room-common` is fully multiplatform → the domain model keeps `@Entity` in `commonMain`.
- Toolchain pinned at `~/.local/android-toolchain` (JDK 21 + SDK); system `java` is 8,
  so `JAVA_HOME` must be set; Gradle runs `--offline` (deps cached). AGP 9.3.1, Gradle
  9.7.0 (SHA-256 pinned), Kotlin/Compose compiler 2.4.10, KSP 2.3.11, Compose BOM
  2026.06.01, Room 2.8.4, compileSdk 37.1, minSdk 24, targetSdk 37.

### Engine defects recorded in the plans (fix status as of 2026-08-17)
1. `AutoPlan` does not treat `dueAt` as a hard constraint — **fixed** (`b11ad72`:
   `DeadlinePolicy.SOFT/HARD`, HARD default, placement clamped via
   `nextSlot(..., notAfter)`).
2. `AutoPlan` ignores `item.startConstraint` — **fixed** (`b11ad72`: per-task
   `notBefore` in placement).
3. Generalized dependency bounds — **fixed**: `AutoPlan` now translates all four precedence
   types and `AutoPlanTest` covers each type, lag, and lead behavior.
4. All-day events become full-day hard blocks in `fixedCommitments` — **fixed**
   (`b11ad72`: `ScheduleAnalysis.fixedCommitments()` filters them out, all three
   ViewModel call sites).
5. `AutoPlan` search cost — **bounded by a regression benchmark**: `AutoPlanBenchmarkTest`
   runs 800 tasks over four weeks and requires completion under five seconds. Production-scale
   profiling remains part of deployment capacity validation, not an untested code defect.
6. Two buffer code paths (`AutoPlan` applies `bufferMinutes` itself; `PlanHealth` via
   `freeIntervals`) — **fixed** (`b11ad72`: AutoPlan's buffer path unified on
   `WorkingCalendar.freeIntervals`).
7. Test coverage inversion — **fixed**: `AutoPlanTest` now contains 19 focused tests plus the
   800-task benchmark; the multi-schedule suite retains its 17 adversarial cases.

### HCI evidence (last recorded, 2026-08-17)
- 401 JVM tests (227 planning-core + 174 app), 129/129 instrumentation (API 36
  `dailybrief` AVD), both lints zero issues, all three APK assemblies, R8 analysis.
- `scripts/verify.sh --device` is the gate; `scripts/emulator.sh` boots the `dailybrief`
  AVD from snapshot. The API 37.1/16 KB AVD (`dailybrief_api37_1_16k`) is **not**
  recordable on this host: system_server crash-loops during APK installs and
  mid-instrumentation (Binder `DeadObjectException`, "System has crashed"), regardless
  of fresh cold boot, snapshot-less runs, or manual `pm install-*` sessions; the one
  complete pass ran 129 tests with 125 green, the 4 failures being the documented
  locked-user launch condition. The API 36 gate (129/129) is the recorded evidence.
- All 24 HCI-principle fixes (P1–P24) are done and guarded by named tests. API 24 rendered
  capture is now complete locally; still open are manual TalkBack/Switch/Voice passes,
  physical providers, API 37.1/16 KB execution (needs a healthier host), boundary matrix at
  599/600…1599/1600 dp, 200% text beyond named components, task-based usability
  (T1–T11), RTL/long-localized text, process death at every edit point.
- Capture harness: `scripts/capture-preview.sh` + `embed-preview-plates.py`; ends with
  `pm clear` (leaving seeded state breaks the next `verify.sh --device` in misleading
  ways). Harness is `@CaptureOnly`, never part of the gate.

### Open questions for the user (programme plan §9)
1. ~~Approve `AutoPlan` treating `dueAt` as a hard constraint?~~ **Approved and
   implemented** (`b11ad72`, Defect 1; open question closed).
2. ~~kotlinx-datetime absent from the offline cache~~ — **resolved by the toolchain
   owner**: kotlinx-datetime 0.8.0 + desugar_jdk_libs 2.1.5 are cached and in use.
   The remaining half is the Kotlin/Native target (~1 GB) for compiler-enforced
   `commonMain` (WP-7) — the source-scan test stays the guard until then.
3. ~~Monorepo reshuffle ordering~~ — **resolved** (2026-08-19): `apps/ services/ packages/`
    landed once the web app existed; `app/` moved a single time. Module names survive via
    `projectDir` mappings (`settings.gradle.kts`), so scripts and CI are unchanged in what
    they spell.

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
  (`apps/android/schemas/`); plan-scoped tables cascade from their board.
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

## Remaining work — work-package tracker (2026-08-17, Stage 1)

The canonical forward plan is `docs/saas/07-roadmap.md`; execution detail is
`docs/saas/08-work-packages.md`. This tracker mirrors the WP list so a session can
start from a one-line status. Do each package as its own commit, green before commit.

```
WP-1 ScheduleAnalysis ──┬── WP-2 the six files it gates
                        └── WP-8 remove the NotionClient bridge
WP-3 GanttInteraction   (independent, small)
WP-4 LegacyNameKeys ────┬── WP-5 journal codecs (needs WP-6 too)
WP-6 WorkingCalendarMapper ─┘
WP-7 non-JVM target     (after WP-1..WP-6; makes the guard redundant)
WP-9 engine defects     ✅ (defect 3 confirmed wanted before building)
Stage 2 (WP-10..WP-12) starts once WP-1..WP-7 are done.
```

### Stage 1 status — all done

- [x] **WP-1** Port `ScheduleAnalysis` to `commonMain` (`c32c7ad`).
- [x] **WP-2** Move the six files (`34f4385`).
- [x] **WP-3** `GanttInteraction` off `java.io.Serializable` (`3b5af10`).
- [x] **WP-4** `LegacyNameKeys` via `expect`/`actual` (`2c7ea46`).
- [x] **WP-5** The three journal codecs (`0bbdf42`).
- [x] **WP-6** `WorkingCalendarMapper` off `Calendar` (`72e8db6`).
- [x] **WP-7** `linuxX64` target added; purity compiler-enforced.
- [x] **WP-8** `NotionClient` bridge deleted (`8a618ce`).
- [x] **WP-9** Defects 3 (`4aac835`), 5 (`df1ef68`), 7 (`df1ef68`).

### Stage 2+ — all done

- [x] **WP-10** multi-tenant Postgres schema (`2c65d34`, `da4ad63`).
- [x] **WP-11** the four invariants redesigned (`f42b1e4`).
- [x] **WP-12** the planner API contract FROZEN at v1 (`b229dfd`).
- [x] **WP-13** the vertical slice (`f9f0d24` + `ffd10be`): sign in → connect calendar →
  add tasks → Plan my week → proposal with reasons → apply → Today. `:server` Ktor
  module + Next.js `apps/web/` client; automated journey in the gate + real-account runbook.
- [x] **WP-14** sync-worker hardening — see the work log; the worker shipped with WP-13,
  hardening (token refresh, fetch retries, sync ledger) landed after.
- [x] **WP-15** depth and paid tier: Capacity (4.1), Board (4.2), dependencies (4.3),
  scenarios/baselines/portfolio (4.4), billing (4.5).
- [x] **WP-16** mobile MVP + depth: WP-M1…WP-M4 (`19b0d71` + WP-M3 + WP-M2/M4),
  Projects/Board, offline preview fallback, daily brief, "This week" strip.

### Remaining after this pass (see work log)

- Mobile: the payload cache and offline posture are implemented for Today, project lists,
  boards, and the portfolio strip; Apply, edits, sync, and brief generation correctly remain
  server-authoritative. Remaining mobile work is runtime proof on physical/reliable devices,
  not another cache implementation pass.
- iOS: first `xcodebuild -create-xcframework` + Swift compile — needs a Mac.
- Android implementation backlog: none of the previously named core items remain. Canvas
  dependency creation, change digest, PDF/PNG export, print layouts, Saved View depth,
  shortcuts, notification actions, focus timer, pinch zoom, the minimap/overview strip,
  Today jump, and weekly review are present and locally tested. Typography/font QA and
  broader manual proof remain open.
- Evaluation (manual/external): TalkBack/Switch/Voice, T1–T11, boundary matrix, healthy
  API 37.1/16 KB and physical-device behavior, real providers, RTL, 200% text, process death,
  landscape/tablet/foldable renders, and typography/font QA. API 24 rendered capture and v3/v4
  migration fixtures are complete locally; they are not open implementation tasks.

### Product / HCI (not part of the WP flow)

- Product implementation: the prior Phase 2–4 leftovers are closed in the current tree;
  later product decisions remain separately recorded in `docs/saas/11-strategy-reconciliation.md`.
- HCI/evidence: a healthy API 37.1/16 KB runtime, hosted CI artifacts, macOS XCFramework/
  Swift verification, physical devices, manual AT passes, boundary matrix
  599/600…1599/1600, 200% text, T1–T11, RTL/long text, process death at each edit point,
  and typography/font QA.

### Assessed UI items (PRINCIPLES §4b — kept for the record)

- Home's greeting is decoration — assessed: `greeting(nowMs, name)` already exists
  (`HomeScreen.kt:897`); the item is about its *content*. Any day-shape title change
  is a wording decision for the user.
- Settings' six accordions — assessed: `SettingsDisclosure` accordions already exist
  (`SettingsScreen.kt:242`); whether default-open categories still deserve it is a
  product call, no code defect.
- Gantt date axis (P24) — **done 2026-08-17**: axis + "WORK" header hoisted into a
  pinned row sharing the canvas's `ScrollState`; overlay y-offsets now row-relative.
  Evidence: `--device` green incl. `ScheduleMapPage`, `ScheduleMapProportion`,
  `GanttSmallScreen`, `GanttDirectManipulation`; `preview.html` recaptured.

## Work log

(Updates from here on. Each entry: date, what changed, evidence — same discipline as
the canonical docs: name the test or command that proves a claim.)
### 2026-08-17 (user's pass) — deviations closed, roadmap + work packages, date-time port

**`d238f75`** — reconciled docs with the tree; opened `06-scope-deviations.md`
(seven entries: D1 widget/PDF/backup built against the V1 exclusion list — resolved
by *amending `02`* to separate Android surface from web V1 scope, code kept;
D2 deadline change accepted and recorded in `00` §6; D3 `RowBackupCodec` moved back
to `:app`; D4 jvmTest inputs declared for the source-reading purity test; D5
`SavedViewCodec` + `WorkScheduleDefaults` to commonMain; D6 aligned work; D7 the
audit's method — only the compiler is authoritative).

**`5250c5a`** — closed the deviations and wrote `07-roadmap.md`: Stage 1 portability
(1.1 date-time cluster, 1.2 `signature`, 1.3 journal codecs, 1.4 compiler purity),
Stage 2 domain model + server contract (schema, four invariants, frozen planner API),
Stage 3 vertical slice (90-second magic moment is the acceptance test), Stage 4 depth
+ paid tier, Stage 5 mobile (local engine previews; server authoritative for Apply).

**`b436b1e`** — date-time port started: `kotlinx-datetime` 0.8.0 +
`desugar_jdk_libs` 2.1.5 (toolchain owner added them to the cache — open question 2
resolved); `:app` gains `isCoreLibraryDesugaringEnabled` (minSdk 24 vs java.time's
API 26); `IsoDates` → commonMain (regex replaces the SimpleDateFormat stack;
accepted shapes and failure mode unchanged; `IsoDatesTest` still builds expectations
with `java.util.Calendar` on purpose).

**`e440cd3`** — `WorkingCalendar` → commonMain, releasing `PlanHealth`,
`MultiSchedulePlanHealth`, `AutoPlan`, `PlanBlockPreview`, `PlanScenarios` (1,633
lines). The inherited DST policy is now named code: `AmbiguousLocalTime.LATER_OFFSET`
(gap → next valid minute; fall-back → later, standard-time occurrence — what
`java.util.Calendar` did, and what kotlinx/java.time do *not*). Both pinned
`WorkingCalendarTest` DST cases pass unchanged.

**`c097596`** — audit re-measured against the tree: engine core 34.7% → **74%**,
whole module 33% → **59%**; `ScheduleAnalysis` is the only root blocker in `core/`,
gating 861 lines.

**`c7c4faa`** — `08-work-packages.md`: WP-1..WP-16 with files, steps, acceptance
tests and traps; three-agent parallel split (A: WP-1→2→8; B: WP-4→6→5; C: WP-3,
WP-9; WP-7 last).

Baseline verified at WP-1 start: 222 engine / 179 app tests, 0 failures.

### 2026-08-17 (second) — engine defects 1/2/4/6 + Phase 4 features + device gate

**Engine defects (`b11ad72`).**
- `AutoPlan`: `DeadlinePolicy { SOFT, HARD }`, HARD default — placement clamped with
  `nextSlot(..., notAfter = dueAt)`, remainder reason names "…past its due date";
  `item.startConstraint` honoured as per-task `notBefore`. `EngineContractTest` gains
  HARD-default, SOFT-disclosure and start-constraint tests (characterisation test
  rewritten from "not yet a constraint" to the approved behaviour).
- `ScheduleAnalysis.fixedCommitments()` filters all-day events (like `findConflicts`
  does); all three ViewModel call sites (`eventSnapshot`, `moveToEmptySlot`,
  `planDayOverlay`) switch to it.
- Buffer unification: AutoPlan's gap-selection path now consumes
  `WorkingCalendar.freeIntervals` (same min-space logic as `PlanHealth`).
- `ScheduleAnalysisTest` gains all-day-blocked vs all-day-not-blocked tests;
  existing AutoPlanTest buffer tests stay green.

**Product features.**
- `a5bc8c1` — home-screen widget (`TodayWidgetProvider`/`TodayWidgetContent`,
  `today_widget.xml`, `today_widget_info.xml`, manifest receiver; refresh broadcast
  `com.example.dailybrief.action.REFRESH_WIDGET` sent from `sync()`) and PDF export
  (`PdfPlanExporter`/`PdfPlanLayout` A4 via framework `PdfDocument`, no new
  dependency; PlanScreen `CreateDocument("application/pdf")` →
  `daily-brief-plan.pdf`; overflow item "Export plan (PDF)").
- `43dea69` — encrypted backup/restore: `RowBackupCodec` (MAGIC `dailybrief-backup`
  v1, single-pass escape/unescape) + 5 tests; `SecretStore.encryptBackup`/
  `decryptBackup` (AES-GCM, purpose-bound AAD); `BackupManager` (whitelisted
  export tables, child-first delete incl. `plan_mutations`, FK off during restore
  transaction, events deliberately not backed up); ViewModel `exportBackup`/
  `restoreBackup`; Settings DataSection `BackupDisclosure` with confirm dialog;
  `.dbb` files.
- `181f4cb` — cross-board portfolio Gantt: pure `PortfolioGantt` projector (block
  rows per board over a 14-day range) + 3 tests; ViewModel `portfolioTimeline`
  (`PORTFOLIO_TIMELINE_DAYS = 14`); Portfolio dialog gains Rollup/Timeline tabs with
  board-colour bars, day ticks and a today line. `Radius.block` used (UiConsistencyTest).
- `b446d11` — instrumented tests updated for the new surfaces
  (`PlanOverflowMenu.onExportPdf`, `PortfolioDialog(result, timeline, onDismiss)`).

**Verification.**
- `scripts/verify.sh --fast` and full `scripts/verify.sh`: green. JVM suite now 401
  tests, 0 failures (227 planning-core + 174 app).
- `scripts/verify.sh --device` on the `dailybrief` AVD: 129/129 instrumented tests
  green (first attempt had one cold-start flake —
  `cancellingEventAfterTaskOnlyEditsRequiresDiscardConfirmation` timed out waiting
  for `event_editor`; clean re-run green).
- 16 KB AVD: not recordable on this host — see section "HCI evidence". One complete
  pass ran 125/129 (4 locked-user failures); system_server crash-loops on installs
  thereafter.

**Not done:** Phase 5 date-time cluster (kotlinx-datetime absent), Phase 7 slice,
manual/HCI evidence, Kotlin/Native, remaining Phase 4 items (change log/restore
points/audit UI, print layouts, shortcuts, notification actions).

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
  `apps/android/src/main/java/com/example/core/GanttDirectManipulation.kt` + its tests to
  `apps/android/src/test/.../GanttDirectManipulationTest.kt`; the engine keeps
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
### 2026-08-19 (android tranche + reshuffle + device-suite repair)

- **`9ad0d04`** — saved-view depth (Outline text filter persisted as the `text` filter,
  restored on apply; density presets ride the codec's zoom vocabulary), one-tall-PNG
  export beside the PDF, focus timer (one doze-friendly alarm, Done/Defer notification
  actions writing through the journal), dynamic launcher shortcuts to Plan and Today.
- **`c2a675b`/`151b23c`/`194c8f7`** — the monorepo reshuffle: `apps/android`, `apps/mobile`,
  `apps/web`, `services/server`, `packages/planning-core`, `packages/planning-contract`;
  module names survive via `projectDir` mappings, so scripts/CI still spell `:app` etc.
- **`7bab74d`** — the device suite was red since v10 landed and no one had run
  `--device`: four migration tests stopped at v9, `RuntimeSafety` pinned user_version 9,
  and `MIGRATION_9_10` created a partial index Room's validation rejects (schema has no
  WHERE clause). The migration is now schema-exact; the "one non-archived default"
  partial index rides `AppDatabase`'s onCreate callback through the shared
  `installNonArchivedDefaultScheduleIndex`, which the migration test registers the same
  way. View-options journeys scroll to the commands my Search/Density sections pushed
  below the fold. **Evidence:** `scripts/verify.sh --device` green — 130 instrumented
  tests, both lints, both APKs, R8; `scripts/verify.sh --journey` green.

### 2026-08-19 (mobile depth, server hardening, per-project schedules)

- **`c8313f6`** — WP-14 hardening lands: the calendar provider refreshes once on
  401/403 and persists the refreshed pair through the envelope (keeping the old refresh
  token when the issuer does not rotate it); ReconcileWorker gains three fetch attempts
  with 200ms<<attempt backoff and a V7 `sync_runs` ledger row per run; the journey
  asserts it at `/v1/fixture/sync-runs`.
- **`b71460a`** — the Expo push-token wiring (`expo-notifications` mints the token,
  registers at app start once a session exists, deregisters at sign-out), the Planner
  Capacity section (`/v1/capacity`), a per-project week Gantt over `GET /v1/portfolio`,
  and Today's wall-clock day timeline.
- **`64b8cf9`** — the engine honours per-project schedules on the wire:
  `AutoPlan.propose`/`Mapping.propose` take schedules plus `scheduleIdByItemId`;
  assigned tasks are proposed inside their schedule's working time (per-schedule free
  intervals over one shared taken timeline) and health switches to the
  `MultiSchedulePlanHealth` evaluation; unassigned tasks fall back to the default
  schedule; single-spec requests keep the byte-for-byte path and the golden files never
  move. `PerProjectSchedulePlanTest` + `PerProjectScheduleWireTest` pin it.

### 2026-08-19 (continuation: final Android/mobile hardening and verification)

- Fixed the new Android print path: `PrintPlan.Adapter` now uses `android.print.PageRange`.
  The fast gate, full repository gate, and release/R8 checks pass after the fix.
- Completed the Gantt canvas dependency journey assertion (`substring = true` was required
  by Compose's exact-text default). The final API 36 `scripts/verify.sh --device` run passed
  **131/131** instrumentation tests, with both lints, all APK assemblies, and R8 analysis.
- Added the Expo ESLint configuration and dependencies required by the existing `npm run lint`
  entrypoint; fixed the web color-scheme hydration lint error, duplicate router imports, and
  array-style warnings. `npm run lint` and `npx tsc --noEmit` pass. `npm audit --omit=dev`
  still reports upstream Expo/Metro `image-size` and `uuid` advisories; the only suggested fix
  is a breaking Expo downgrade, so this remains an external dependency-maintenance task.
- Hardened `scripts/journey.sh` with `--rerun-tasks` for `:server:installDist`. A stale
  post-reshuffle distribution had omitted `MainKt` even though the launcher named it; the
  forced distribution now contains `MainKt`, and `scripts/verify.sh --journey` passes the
  complete signup → plan → apply → undo → billing → brief → push journey in **2.5 seconds**.
- Recaptured and embedded the final `preview.html` gallery: 17 plates, including the new
  Plan/Gantt/export states; the capture harness cleared seeded app data afterward.

**Remaining after this continuation:** Mac-only XCFramework/Swift verification; API 24
rendered inspection; API 37.1/16 KB execution on a healthier host; physical provider
read/write/refusal behavior; full form-factor/split-screen and exact-boundary visual review;
manual TalkBack/Switch/Voice, keyboard/pointer/stylus, 200% text, RTL/localization,
reduced-motion/high-contrast checks; process-death coverage at every edit/mutation point;
font licensing/glyph QA if typography changes; and moderated T1–T11 usability sessions.
These are evidence or operator/device tasks, not safely closable by local source edits alone.

### 2026-08-19 (final rail parity and rerun evidence)

- Added the PDF, PNG, and print commands to the wide Plan ledger through the same shared
  `PlanToolsMenuItems` list used by compact overflow. `PlanAdaptiveWorkspaceInstrumentedTest`
  now covers those three tags in both menu presentations; the focused suite passes 7/7.
- Re-ran the repository gates after that production UI change: `scripts/verify.sh --fast`, full
  `scripts/verify.sh`, and `scripts/verify.sh --device` all pass. The final device run is
  **131/131** on API 36, with both lints, debug/test APKs, unsigned release APK, and R8 analysis.
- Re-ran `scripts/verify.sh --journey` against the hardened `installDist --rerun-tasks` path:
  complete signup → plan → apply → undo → billing → brief → push journey passed in 2.5 seconds.
- Recaptured and re-embedded `preview.html` after the final source state: 17 plates, 1119 KB;
  `git diff --check` is clean.

### 2026-08-19 (remaining-work reconciliation)

- The previously listed mobile offline-first slice is implemented in `apps/mobile/src/lib/offline-cache.ts`
  and `apps/mobile/src/lib/api.ts`: Today, project lists, project boards, and portfolio reads refresh
  a per-workspace persistent cache and fall back to stale data only for transport/5xx failures;
  Apply, edits, sync, and brief generation remain server-authoritative. `npm run lint`, `npx tsc
  --noEmit`, and `npx expo export --platform web` pass.
- The previously listed Gantt canvas dependency creation, change digest, PDF/PNG export, and print
  layout work is present in the current tree and is covered by the Gantt journey, `ChangeDigestTest`,
  full Android gates, and the final Plan menu parity suite. The older tracker wording is stale and
  should not be used as evidence that those features are still unbuilt.
- Added real historical Room fixtures for versions 3 and 4. They now prove the v3→v10 and v4→v10
  paths preserve legacy fields, add the signature/user-edited/all-day columns, infer all-day only for
  provider-aligned rows, and leave a manual 24-hour event non-all-day. The focused migration suite
  passes 7/7 on API 36. The current full API 36 gate supersedes the earlier 131-test record with
  **133/133** instrumentation tests passing.
- Attempted API 37.1/16 KB execution on a second AVD. It boots with SwiftShader, but `pm install`
  fails because Android storage/package services are unhealthy (`DeadObjectException`, missing
  `storage` service, and `StorageManager.getVolumes()` null in `PackageInstallerService`). No APK
  or instrumentation result can be claimed from that AVD; this remains a healthier-host blocker.

### 2026-08-19 (web cache persistence follow-up)

- Fixed the web branch of `apps/mobile/src/lib/offline-cache.ts` to persist successful read payloads
  in `localStorage`, while retaining the in-memory fallback for browsers with disabled or full
  storage. Native caching remains document-directory file storage. `npm run lint`, `npx tsc
  --noEmit`, `npx expo export --platform web`, and `git diff --check` pass after the change.
- Extended the capture-only Android harness and `preview.html` with real Agenda/Home conflict,
  provider-owned editor, connected-empty, calendar-permission, and empty Outline/Board/Gantt
  state plates. The capture run passed both phone and Expanded-window methods on API 36 and
  produced **25 plates**; the permission plate drives the ViewModel's existing permission-state
  callback in-process because Android kills the app when a runtime permission is revoked. This
  closes rendered local evidence for these states without claiming physical provider refusal,
  offline transport, or manual accessibility evidence.
- Re-ran `scripts/verify.sh --device` after the capture-harness changes. The complete current
  repository gate passed **133/133** API 36 instrumentation tests, both lints, debug and unsigned
  release APK assembly, and release R8 analysis.
- Retried the disposable `dailybrief_api37_1_16k` AVD after a clean `-wipe-data`: it reports
  `PAGE_SIZE=16384` and accepts APK installation, but `dumpsys user`/`cmd storage` remain
  unavailable, `/sdcard/Android` reports `Transport endpoint is not connected`, and the direct
  133-test Gradle run fails before app launch/unlock. This is confirmed platform/host evidence,
  not an application test failure. The known-good API 36 `dailybrief` AVD was restored with a
  cold boot and is ready again.

### 2026-08-19 (emulator stability follow-up)

- Rechecked the API 36 `dailybrief` AVD after the 16 KB investigation. `scripts/emulator.sh`
  reaches `ready — 16 (API 36)`, but the emulator process exits within seconds; the resolved
  SDK `adb` then reports no devices and `scripts/emulator.sh --check` fails. The log shows a
  cold boot with SwiftShader and no saved `default_boot` snapshot, but no application failure.
  Do not treat the AVD as currently available for a new device run; the earlier completed
  133/133 API 36 run remains valid evidence, while fresh device execution needs host/emulator
  stabilization.

- Tried the same AVD with `-no-snapshot -gpu off`: it reached boot and exposed `emulator-5554`,
  but the process still exited before the user reached a stable unlocked state. This fallback
  does not repair the host-side lifecycle failure.
- Checked the local SDK for API 24/25/26 system images; none are installed or advertised by the
  available `sdkmanager`, so API 24 rendered inspection remains unavailable on this host.
- Ran the Linux-side iOS framework slice task. It is blocked in offline mode by uncached
  `room-common`, `kotlinx-datetime`, and serialization iOS klibs; the final XCFramework and Swift
  compile remain macOS work even after those artifacts are available.
- `npx expo install --check` reports the mobile dependency set is aligned. `npm audit --omit=dev`
  still reports 22 upstream Expo/Metro and transitive `uuid` findings; its only automatic fix is
  a breaking Expo 53 downgrade, so no unsafe downgrade was applied.
- Final post-audit rerun: `git diff --check`, `scripts/verify.sh --fast`,
  `scripts/verify.sh --journey` (signup through push, 2.548 seconds), and mobile `npm run lint`,
  `npx tsc --noEmit`, `npx expo export --platform web`, and `npx expo install --check` all pass.
- Hardened `scripts/emulator.sh` so a newly launched emulator must survive a process/device/user
  stability window before it reports ready, and added `DAILYBRIEF_EMULATOR_GPU` for controlled
  host fallback testing. The current AVD still disappears after that window, so the host blocker
  remains; the earlier 133/133 API 36 run is the valid device evidence.
- Re-ran the complete non-device `scripts/verify.sh` after the emulator-script change; planning
  core/contract/server/app tests, both lints, debug/test APKs, unsigned release APK, and R8 all
  passed.

### 2026-08-19 (final local verification and evidence refresh)

- Root-caused the API 36 emulator lifecycle issue to the launcher process group reaping the
  background emulator. `scripts/emulator.sh` now launches through `nohup setsid`, waits through a
  process/device/user-unlocked stability window, and supports `DAILYBRIEF_EMULATOR_GPU`; the
  detached `dailybrief` AVD remains online after the launcher exits.
- Cleared the capture harness's inherited view settings before seeding so preview output is
  deterministic. The latest phone and Expanded capture runs pass and embed **25 plates** in
  `preview.html`.
- Fixed the baseline restore journal summary to interpolate the actual baseline name and added
  `PlanBaselineRepositoryInstrumentedTest.restoreJournalUsesTheBaselineNameInItsSummary`.
- A clean full `scripts/verify.sh --device` rerun passes **134/134** API 36 instrumentation
  tests, including the new regression test, plus both lints, debug/test APKs, unsigned release
  APK, and release R8 analysis. The isolated width-boundary test also passes after the aggregate
  run was interrupted at a harness stall.
- The remaining items listed above are still external/manual evidence: API 24 and healthy API
  37.1/16 KB execution, macOS XCFramework/Swift verification, physical provider behavior,
  broader form-factor and assistive-technology review, process-death matrix, typography QA,
  and moderated T1–T11 usability sessions.

### 2026-08-19 (continued reconciliation and Home hierarchy)

- Replaced the decorative Home greeting hierarchy with a useful `Today` root title; the time-of-day
  greeting remains as secondary context beside the localized short date. Added a rendered journey
  assertion for the title. The clean API 36 device gate passes **134/134** after this change.
- Reconciled `PREMIUM_PRODUCT_PLAN.md` and `HCI_REDESIGN_PLAN.md` with the current source: saved-view
  depth, pinch/overview, auto-plan/scenarios, change digest, weekly review, baselines, portfolio,
  exports, print, and mobile power paths are implemented and tested; only broader external/manual
  evidence remains open. The latest preview capture passes with **25 plates**, and the Home plate
  visibly shows `Today` as the primary title.
- Tested npm security overrides for `image-size` and `uuid`. The patched tree passed a shallow API
  probe but Metro web export failed in `image-size`'s asset decoder, so the overrides were removed
  and the supported Expo/Metro dependency tree was restored. `npm audit --omit=dev` remains an
  upstream Metro/image-size maintenance issue; no framework downgrade was applied.
- Re-ran `scripts/verify.sh --journey` after the reconciliation; the full signup → push journey
  remains green in **3.210 seconds**. Mobile lint, TypeScript, Expo web export, dependency alignment,
  and `git diff --check` also pass after restoring the supported dependency tree.
- The SDK catalog now advertises `system-images;android-24;google_apis;x86_64`; an install attempt
  was canceled after the 1.1 GB archive reached 19% in roughly eleven minutes at a very low transfer
  rate. No API 24 AVD or rendered result exists yet, so the API 24 evidence requirement remains open.

### 2026-08-19 (command-center SaaS reconciliation)

- Added `docs/saas/11-strategy-reconciliation.md` and reconciled the product direction: the
  consultant ICP and hybrid wedge remain, while Today becomes the hero, Planner unifies calendar
  and planned work, Inbox captures unrouted tasks, Projects progressively disclose depth, and
  Capacity/health/scenarios move under secondary Insights.
- Updated `docs/saas/00`/`01`/`02`/`05`/`07`/`08` to remove stale pre-portability status claims and
  name the actual remaining tail: mobile cache runtime evidence, macOS iOS verification,
  real-account/production evidence, and consultant usability validation.
- The web client now renders a Today command-center hero, exposes the reduced navigation, and
  displays `columnId = null` tasks in an Inbox instead of silently omitting them. `apps/web`
  `npm run build` passes; mobile lint, TypeScript, Expo web export, dependency alignment, and
  `git diff --check` pass.
- Reran the full `scripts/verify.sh --device` on the stable API 36 `dailybrief` AVD: **134/134
  instrumentation tests**, both lints, debug/test APKs, unsigned release APK, and R8 analysis
  passed in 2m08s. This does not close API24/16KB, physical-device, accessibility, iOS/Mac, or
  usability evidence.
- Corrected `docs/saas/10-wp13-journey-runbook.md` after the monorepo move: the real-account
  commands now point to `services/server` and `apps/web`.

### 2026-08-19 (mobile cache hardening and native build)

- Extracted the mobile offline-cache encoding and key rules into
  `apps/mobile/src/lib/offline-cache-core.ts`; native writes now serialize per key, use encoded
  filenames, validate persisted entries, and replace through a sibling temporary file so a
  failed write cannot truncate the last complete payload.
- Added `apps/mobile/src/lib/offline-cache-core.test.ts`: `npm test` passes 4 tests covering
  round-trip persistence, legacy v1 entries, corruption rejection, and workspace/project key
  isolation. Native restart/storage-failure behavior remains device evidence.
- Resolved the generated Expo Android dependency cache online and built the debug, Android-test,
  and release APKs successfully. The release APK embeds the JS bundle and installs on the API 36
  `dailybrief` AVD; the app reaches the foreground and runs `ReactNativeJS: Running "main"`.
- The API 36 headless software renderer reports zero rendered frames and leaves the starting
  splash visible (`gfxinfo: Total frames rendered: 0`), so this is not a valid visual/cache runtime
  pass. Metro development mode is separately blocked by the host inotify quota (`ENOSPC`). Do not
  claim native offline/restart or rendered mobile evidence from this run.
- Mobile `npm test` (4/4), lint, TypeScript, Expo web export, dependency alignment, web
  `npm run build`, and `git diff --check` pass. The root Android device gate completed **134/134**
  with zero failures; the current server acceptance journey also completed signup through push
  in **2.540 seconds**.
- Retried the native mobile renderer on the API 36 `dailybrief` AVD with `-gpu host` and
  `-gpu angle_indirect`; `host` segfaulted in the emulator process and `angle_indirect` booted
  but still reported zero app frames. Restored the known-good `swiftshader_indirect` AVD.
  This confirms a host/emulator rendering limitation, not native cache or UI runtime evidence.
- Added adapter-level mobile cache tests: web-storage reload, malformed-entry removal, and the
  successful in-memory fallback when storage writes fail. The mobile suite is now **7/7** tests.
  The downloaded API 37.1 16 KB image reports `sdk_full=37.1` and `PAGESIZE=16384`; APK installs
  succeeded on a fresh AVD, but `package`/`activity` services repeatedly became unavailable,
  so no 16 KB app execution claim is made. API 36 was restored afterward.
- Hardened `scripts/smoke-release.sh` to align and verify the install-only release APK with
  `zipalign -P 16`; the root Compose release smoke now passes after the change and still launches
  and navigates. This is packaging evidence only, not 16 KB runtime evidence.
- Fixed a production configuration gap: `Config.fromEnv` now reads `WEB_ORIGIN`, so deployed
  CORS and OAuth redirect defaults can follow the configured web host instead of silently staying
  on localhost. Added `ConfigTest` coverage for configured and default origins. The refreshed
  local server journey passes in **2.534 seconds**; current server tests are **30/30**.
- Hardened Stripe webhook verification: malformed headers are refused without throwing, signatures
  use constant-time comparison, and timestamps older or newer than five minutes are rejected to
  prevent replay. `StripeBillingProviderTest` covers fresh, stale, and malformed signatures.
- Hardened the real Google callback for SaaS tenancy: it now fetches and verifies the provider
  identity, scopes the workspace lookup/creation by verified email, and no longer assigns every
  OAuth user to the first workspace. `GoogleIdentityTest` covers verified and refused identities;
  the clean server run is **45/45**, the acceptance journey is **2.498 seconds**, and the full
  no-device verification gate remains green. This still needs a real-account OAuth run and
  production secret/provider verification.
- Added OAuth callback CSRF and redirect-boundary hardening: only the configured web callback or
  `mobile://auth/callback` is accepted, `/auth/start` issues a ten-minute signed state, and the
  callback refuses missing, altered, expired, or mismatched state. `OAuthStateTest` covers expiry,
  tampering, secret binding, and redirect allowlisting. The clean server run is now **48/48**;
  the journey remains green at **2.515 seconds**. Real Google consent and production configuration
  are still external evidence.
- Added `apps/mobile/src/lib/offline-cache.native.test.ts`: the native adapter model now covers
  document-storage reload, workspace isolation, corrupt-entry cleanup, and atomic replacement
  after a failed temporary write. The mobile suite is now **10/10**; physical native persistence,
  restart, and offline behavior remain device evidence.
- Added production network bounds to Stripe checkout and the Google OAuth token/identity client:
  10-second connect and 20–30-second read timeouts now prevent provider calls from hanging a
  request indefinitely. Also made partial KMS configuration fail closed instead of silently
  falling back to `ENVELOPE_KEY_HEX`; `ConfigTest` covers that refusal. The clean server suite is
  now **49/49**, and the full no-device journey remains green at **2.518 seconds**.
- Added boot-time session-secret validation: `Config.fromEnv` now refuses secrets shorter than 32
  characters, and `ConfigTest` covers the fail-closed path. The clean server suite is now **50/50**;
  the existing journey secret satisfies the new minimum, and the full no-device journey remains
  green at **2.544 seconds**.
- Real-mode `/auth/start` and `/auth/callback` now return an explicit 501 when Google credentials
  are absent, instead of reaching the OAuth client's non-null assertions and producing a 500.
  Fixture mode remains unchanged; the full no-device journey passes in **2.544 seconds**.
- Refreshed and partially hardened the mobile dependency audit: an `overrides` entry pins the
  nested `xcode` UUID dependency to `11.1.1`, which is CommonJS-compatible and removes the eight
  UUID/Xcode moderate findings. `npm audit --omit=dev` now reports **14 upstream high findings**
  in the Expo/React Native/Metro chain; the remaining automatic root fix is the incompatible
  Expo 53.0.27 change, and forcing `image-size` 2.x would change Metro's CommonJS API. The
  remaining audit items stay explicitly open rather than being hidden by an unsafe override.
- Added unauthenticated `/health/live` and database-backed `/health/ready` endpoints, and wired
  both plus exact-origin CORS into `scripts/production-probe.sh`. `scripts/journey.sh` runs the
  probe before the fixture acceptance loop; the journey still passes end to end in **2.682
  seconds**.
- Added boot-time validation for paired Google and Stripe credentials, Google calendar-id
  dependency, port/model shape, and selected KMS/local-key configuration. Partial provider
  configuration now fails closed before the server opens its listener; `ConfigTest` covers the
  paired-secret refusals and `:server:test` passes.
- Added `.github/workflows/ios.yml` and `scripts/verify-ios-framework.sh` to build both Apple
  PlannerCore slices and type-check a Swift import on macOS. Added an API 24 rendered-capture CI
  job that uploads the real preview plates. Both workflows are newly authored and remain
  unverified until their runners execute and the artifacts are inspected.
- Added `OperationalRoutesTest` with Ktor test-host coverage for public liveness, fail-closed
  readiness, and the real-mode OAuth 501 guard. The server suite is now **52/52**; the offline
  dependency cache was refreshed once online to make the test-host artifact available.
- Added a dedicated CI `server-journey` job and an explicit `DAILYBRIEF_GRADLE_ONLINE=1` mode for
  `scripts/journey.sh`; local default remains offline. The refreshed local journey, including
  health/readiness/CORS and signup through push, passes in **2.608 seconds**.
- Corrected the 16 KB retry record: the fresh `dailybrief_api37_1_16k_fresh` AVD really reports
  SDK **37.1**, Android release **17**, and `PAGESIZE=16384`. The debug APK installs and its
  manifest contains the exported launcher activity, but the guest repeatedly aborts
  `surfaceflinger` in `GoldfishMapper::readFromHost` (`hasReadColorBufferDma`), which causes
  package/activity services to return `DEAD_OBJECT` or broken-pipe errors. No 16 KB app launch or
  instrumentation result is claimed; this is a host/emulator graphics blocker, not an application
  failure. The known-good `dailybrief` API 36 AVD was restored and a fresh **134/134** device gate
  passed.
- Hardened `scripts/emulator.sh --avd NAME` to select and verify the requested
  `ro.boot.qemu.avd_name` before reporting readiness, so an already-online different AVD cannot
  be misreported as the requested device. The script syntax check and wrong-AVD rejection both
  pass.
- Corrected the Android CI build artifact paths after the monorepo move: unit-test evidence now
  comes from `apps/android` and both portable engine modules under `packages/`, rather than the
  stale root-level `app/` and `planning-core/` paths. YAML parsing and local artifact-path
  existence checks pass; the workflow still needs an actual GitHub Actions run.
- Retried API 24 image installation with the local JDK 21 SDK toolchain. The catalog exposes
  `system-images;android-24;google_apis;x86_64`, but the download stalled at roughly 10–11%
  without creating a payload; the attempt was stopped after about four minutes. No API 24 AVD or
  rendered artifact exists locally. The remote 2026-08-09 Android workflow proves API 24
  instrumentation only; it predates the rendered-capture job.
- Rechecked the mobile security path against the current npm registry: Expo `57.0.14` remains
  the stable release, it transitively pins `@expo/metro` to `56.0.0`/Metro `0.84.4`, and the latest
  `image-size` release is `2.0.2` but remains inside the published advisory range. No supported
  upgrade or safe override exists in this dependency graph. Mobile tests, lint, TypeScript, web
  export, dependency alignment, audit, and diff hygiene pass; the 14 high findings remain upstream.
- Extended `scripts/emulator.sh` to honor `ANDROID_SERIAL` while still verifying an explicit
  AVD name. The selected-serial success path and a mismatched-serial fail-closed path both pass.
- Completed the local API 24 rendered-coverage retry. The Google APIs x86_64 archive was fetched
  with its catalog size and SHA1 verified, installed as `dailybrief_api24_render`, and reported
  SDK 24 / Android 7.0. `scripts/capture-preview.sh` ran both `capturePlates` and
  `captureWidePlates`, producing 25 PNGs; representative phone and wide Calendar/Plan plates were
  inspected and show the real hierarchy. API 24's framework lacks the PixelCopy overload used by
  Compose, so the capture-only test now uses a bounded `screencap -p` fallback on API < 26. This
  is rendered evidence, not a claim about the still-open API 37.1/16 KB runtime.
- Hardened the emulator selector for legacy guests: API 24 identifies its AVD as
  `ro.kernel.qemu.avd_name` and reports user 0 unlocked as state `3`, rather than the newer
  `ro.boot.qemu.avd_name` / `RUNNING_UNLOCKED` strings. The explicit-AVD and wrong-serial checks
  still pass, and the current API 36 gate remains 134/134.
- Retried the API 37.1/16 KB AVD with `DAILYBRIEF_EMULATOR_GPU=off` after the SwiftShader
  surfaceflinger failure. The guest reports SDK **37.1**, release **17**, and **16384-byte** pages;
  package listing and the installed APK path work, but user 0 remains `BOOTING`, `cmd storage`
  has no storage service, and launch/instrumentation cannot start. The qemu process stays alive,
  so the failure is guest boot/storage readiness rather than an app assertion. The emulator
  launcher now recognizes a detached qemu process for the requested AVD instead of treating the
  wrapper hand-off as a crash; API 36 was restored and its selector reaches ready.
- Fixed the mobile release runtime's splash lifecycle: `apps/mobile/src/app/_layout.tsx`
  now calls `SplashScreen.hideAsync()` after the root mounts. Added the durable Expo config
  plugin `apps/mobile/plugins/with-local-emulator-cleartext.js`, which applies a network
  security config permitting HTTP only to the emulator host `10.0.2.2` so the release APK can
  exercise the local fixture without globally enabling cleartext. After `expo prebuild`,
  publishing the local engine AARs, and a clean `:app:assembleRelease`, the API 36 release
  APK completed fixture signup and rendered Today; force-stop/relaunch preserved the session,
  and after stopping the fixture server the relaunch rendered the explicit offline cached-day
  state. This closes the local release slice; physical native-file failure modes and API
  37.1/16 KB execution remain external/device blockers.
- Made the mobile native build reproducible after Expo regeneration. The tracked config plugin
  now also reapplies `kotlinVersion=2.3.20`, the Kotlin Gradle plugin pin, `mavenLocal()`, and
  the integer `ext.compileSdkVersion = 36` before the Expo root plugin. A fresh
  `npx expo prebuild --platform android --no-install`, local AAR publication, and offline
  `:app:assembleRelease` passed; the generated manifest retained the emulator-only network
  policy. Added an Android CI `mobile-release` job to perform the same prebuild, publication,
  and release assembly and upload the APK/manifest. The workflow and its artifact remain
  unverified until GitHub Actions runs.
- Hardened that CI job to install the pinned Android NDK `27.1.12297006` and CMake `3.22.1`,
  and to run the mobile Vitest, Expo lint, and TypeScript gates before native assembly. The API
  24 instrumentation and rendered-capture jobs now also install compile SDK 36 explicitly.
  The workflow parses locally and all three mobile JavaScript gates pass locally; the hosted
  jobs still require an actual Actions run and artifact inspection.
- Tightened the modeled native offline-cache adapter so writes fail when the parent directory
  has not been created. The real adapter already creates the directory on first write, and the
  strengthened native test continues to prove module-reload persistence, workspace isolation,
  corruption cleanup, and failed atomic replacement without claiming physical-device evidence.
- Re-ran the current local gates after the CI/cache tranche: `scripts/verify.sh --fast` passed,
  the mobile Vitest/lint/TypeScript gates passed, and `scripts/journey.sh` completed all eleven
  fixture/Postgres acceptance legs (health/readiness/CORS, planning, billing, brief, and push)
  in **3.148 seconds**. This remains local fixture evidence, not real-provider or hosted-CI proof.
- Closed a production web configuration gap: `apps/web/app/page.tsx` now enables fixture sign-in
  only when `NEXT_PUBLIC_FIXTURE=1` is explicitly set. An omitted variable therefore presents
  the real Google sign-in path instead of silently shipping the demo journey. The web production
  build passes with the variable unset; real OAuth and deployed API configuration remain external
  evidence.
- Added `.github/workflows/web.yml` to run the production web build with fixture mode explicitly
  disabled and a non-local API origin placeholder. The workflow parses locally but, like the
  Android and iOS evidence workflows, remains unverified until hosted Actions executes it.
- Made the web API boundary fail closed for production builds: `NEXT_PUBLIC_API_BASE` is now
  required when `NODE_ENV=production`, while development retains the localhost default. Added
  `apps/web/.env.example` documenting the deployed API and explicit real-account mode. The
  production build with the configured placeholder API origin passes.
- Re-ran the current repository gates after the mobile release/configuration tranche:
  `scripts/verify.sh --fast`, mobile Vitest (**15/15**), mobile lint, and mobile TypeScript all
  pass; `scripts/journey.sh` completed all eleven fixture/Postgres legs in **3.355 seconds**.
  This is still local fixture evidence, not hosted CI or real-provider proof.
- Rechecked `apps/mobile` with the current lockfile: `npm audit --omit=dev` reports **8 high,
  0 moderate** findings. They are the upstream Expo/Metro/`image-size` parser chain; npm's only
  suggested automatic fix is the incompatible Expo 53.0.27 downgrade. The prior 14-count note
  is historical; do not apply `npm audit fix --force` or an unverified Metro override.
- Retried the disposable `dailybrief_api37_1_16k_fresh` AVD with `DAILYBRIEF_EMULATOR_GPU=off`.
  It reached API **37** / Android **17** and `PAGESIZE=16384`, and the installed package was
  visible, but user 0 remained `BOOTING` and `cmd storage` reported no service. The exact AVD
  was stopped afterward and the known-good API 36 emulator remained ready; no 16 KB runtime
  claim is made.
- Started the web command-center against an isolated Postgres container and API port; the server
  and Next.js development build both came up, but the in-app browser connector could not attach
  to a tab after its documented recovery path. No visual web/UI pass is claimed, and the isolated
  server, container, and Next-generated files were cleaned up afterward.
- Made the mobile API boundary fail closed for production builds: `EXPO_PUBLIC_API_BASE` is
  required when `NODE_ENV=production`; the release verifier supplies the explicit local-emulator
  origin for its local fixture run, and `apps/mobile/.env.example` documents that value. The
  verifier now also sets `NODE_ENV=production`, so the release path exercises the production
  configuration boundary rather than an implicit development fallback.
- Extracted that origin policy into `apps/mobile/src/lib/api-config.ts` with five focused tests,
  covering configured production, absent/blank production, and both development fallbacks.
- Reproduced and fixed the Expo release packaging OOM. The tracked config plugin now generates a
  4 GB Gradle heap with a 1 GB metaspace cap and two workers for the four-ABI release package.
  `scripts/verify-mobile-release.sh` passes end to end after a fresh Expo prebuild, local engine
  AAR publication, JS bundling, native compilation, release packaging, and merged-manifest
  inspection; it also asserts that the configured API origin is embedded in the release bundle.
  The resulting APK is 105,460,145 bytes. Hosted CI and real-device execution are still separate
  evidence layers.
- Hardened the real Google Calendar adapter after the residual source audit: date-only all-day
  events now map to a half-open UTC day instead of failing through `Instant.parse`; paginated
  responses follow `nextPageToken`; and every non-2xx calendar response fails closed instead of
  becoming a misleading empty calendar. Focused provider tests and the complete `:server:test`
  suite pass, and `scripts/journey.sh` still completes all eleven fixture/Postgres legs in
  **3.265 seconds**. Real-account OAuth and calendar-time-zone evidence remain external.
- Re-ran the final local proof after that fix: `scripts/verify.sh` passed, the API 36
  `scripts/verify.sh --device` gate passed **134/134**, and `scripts/verify-mobile-release.sh`
  passed after a fresh Expo prebuild, local AAR publication, JS bundling, four-ABI release
  assembly, and merged-manifest checks (**105,460,145 bytes**). API 37.1/16 KB execution,
  hosted Actions artifacts, Mac/iOS output, physical-device cache behavior, real providers,
  and human HCI validation remain open evidence tracks.
- Hardened `ReconcileWorker` after the workflow/source audit: provider-fetch failures now also
  write a failed `sync_runs` row, failed-ledger writes cannot mask the original exception, and
  retries are limited to I/O plus HTTP 408/429/5xx rather than retrying auth or malformed-data
  failures. The server suite is now **57/57**. `ReconcileRetryTest`, the complete `:server:test`,
  `scripts/verify.sh --fast`, and
  the full eleven-leg journey pass; the latest journey completed in **3.303 seconds**.
- Rechecked hosted state: the local branch is still `saas-extraction` at `a7b04dd` and has not
  been published; GitHub still shows only the historical 2026-08-09 Android run. The newly
  authored iOS, web, server-journey, rendered-capture, and mobile-release workflows therefore
  remain unexecuted hosted evidence, not failures.
- Closed the workspace-wide planning boundary: `/v1/plan` now canonicalizes the legacy `b1`
  alias, validates every requested task against its workspace/project, stores each proposal's
  real project ID, and `/v1/plan/{runId}/apply` writes blocks grouped by that project instead
  of assigning all blocks to the default board. Journal Undo now locks and restores blocks
  across projects atomically while retaining compatibility with older audit JSON. The journey
  now plans a task from both projects and asserts the second project's Portfolio block; server
  tests, `scripts/verify.sh --fast`, and all eleven journey legs pass, latest journey **3.267
  seconds**.
- Closed the matching mobile production-safety gap: `apps/mobile/src/app/index.tsx` now shows
  `Try the demo` only when `EXPO_PUBLIC_FIXTURE=1`; production presents the real Google path
  without advertising a disabled fixture endpoint. The release verifier opts into fixture mode
  explicitly, `.env.example` documents the boundary, and the mobile README records it. The
  production-configured mobile JS gates and `scripts/verify-mobile-release.sh` pass after a
  fresh prebuild/AAR publication and four-ABI release assembly (**105,460,201 bytes**); the
  hosted workflow and real OAuth remain external evidence.
- Improved the shared Today contract after checking the actual rendered clients: planned blocks
  now carry their task title and project name, and Today conflict analysis sweeps calendar events
  together with planned blocks instead of silently ignoring calendar-versus-plan overlaps. The
  mobile timeline/cards render the title, the web Today route renders the richer block shape, and
  `TodayTest` covers both a human-readable overlap and the all-day-marker exclusion. Server tests,
  `scripts/verify.sh --fast`, the eleven-leg journey (**3.237 seconds**), mobile TypeScript, and
  the configured production web build pass.
- Completed the documented V1 Inbox loop in the web client: the Inbox now captures a task with an
  effort estimate through the existing `/v1/tasks` route, keeps it without a workflow stage, and
  returns the view to the workspace default project so the new capture is visible. The journey
  now asserts that captured tasks remain unrouted; web TypeScript, the configured production build,
  and the full eleven-leg journey pass (**3.355 seconds**).
- Added the first real web Planner surface: a seven-day compact timeline combines today’s calendar
  commitments with all applied portfolio work, while preserving the existing Plan my week action.
  The primary navigation now targets this unified timeline; project-board depth remains separately
  disclosed below it.
- Wired the web planner's existing-work boundary for replanning: requests now include the current
  workspace week blocks, and the Today action says `Replan my day` when work is already applied.
  This lets the deterministic engine count completed/scheduled effort and plan only the remainder
  around existing blocks; it does not silently move committed blocks. The journey now submits a
  second request with the applied portfolio blocks and asserts zero duplicate proposals. Web
  TypeScript, the configured production build, and the full journey pass (**3.341 seconds**).
- Implemented explicit proposal-based replacement for schedule disruptions. `POST /v1/plan?replan=1`
  removes only unlocked target-task blocks from the engine input, stores their exact expected rows
  with the run, and Apply now locks/compares/deletes those rows and inserts the replacement proposal
  atomically. The journal records before/after state, so replan Undo restores the original blocks;
  stale or changed rows return a conflict instead of overwriting work. Added the workspace-scoped
  `/v1/plan-blocks` read and wired the web client to use real block ids. The journey occupies an
  existing slot, applies a replacement, proves the old ids disappear, undoes the replacement, then
  undoes the original apply. Server tests, `scripts/verify.sh --fast`, web TypeScript/build, and
  the full journey pass (**3.319 seconds**).
- Brought the same proposal-based replan boundary to `apps/mobile`: the Planner reads fresh
  workspace block ids, sends `?replan=1` when existing work is present, refreshes the schedule after
  Apply/Undo, and mirrors the server's unlocked-target/locked-obstacle rule for native previews.
  If the block snapshot is stale or unavailable, the mobile path can preview locally but cannot
  claim an authoritative Apply. Added the pure mapping/replacement contract tests; mobile Vitest is
  **17/17**, lint and TypeScript pass, the root full Android gate passes, the full fixture journey
  passes in **4.237 seconds**, and `scripts/verify-mobile-release.sh` passes after fresh Expo
  generation and four-ABI assembly (**105,463,757 bytes**). Mac/iOS, hosted CI, physical-device
  runtime, real providers, and moderated HCI evidence remain external.
- Reconciled the current evidence ledgers after the mobile parity pass: current server coverage is
  **57** tests (not the historical 52), the latest local journey is **4.237 seconds**, and the
  latest mobile release APK is **105,463,757 bytes**. Historical entries remain unchanged; the
  remaining register is still limited to device, macOS, hosted-CI, provider, dependency-upgrade,
  accessibility, and consultant-usability evidence.
- Closed a CI coverage gap in `.github/workflows/android.yml`: the hosted Android build now runs
  both `:planning-contract:jvmTest` and `:planning-core:compileCommonMainKotlinMetadata`, matching
  the local purity/contract gate instead of silently omitting those checks. All workflow YAML and
  repository shell scripts parse, and `scripts/verify.sh --fast` remains green.
- Rendered the web command-center journey against an isolated Postgres/API instance: fixture sign-in,
  Inbox capture, Plan my week, proposal review, Apply, and Today all rendered with the expected
  state. The pass exposed a stale proposal after Apply; `apps/web/app/page.tsx` now clears the
  proposal after the server confirms the journaled Apply. The updated production build, TypeScript,
  `git diff --check`, browser journey, and eleven-leg `scripts/journey.sh` pass; no web lint script
  exists in the current package, so lint is not claimed.
- Closed the cross-project workspace boundary: `GET /v1/tasks` now returns every active workspace task
  with its real `project_id` and `stage_id` instead of only the default project while flattening every
  stage to Inbox. The web Planner now builds proposals from the workspace task set, and its Inbox shows
  unrouted tasks from every project with project labels. The acceptance journey proves a second project's
  staged task and unrouted task retain distinct states; the rendered browser check shows both Inbox rows
  and produces a two-block cross-project proposal. Server tests, web TypeScript/build, diff hygiene, and
  the eleven-leg journey pass (**3.406 seconds**).
- Closed the matching mobile workspace-planning boundary: the mobile Planner now reads and caches the
  full workspace task set, preserves each task's owning project in online and native-preview requests,
  and uses that set for Plan my week and capacity analysis while keeping the selected project board as
  the visible editing surface. Added the workspace task cache and a pure multi-project mapping test;
  mobile Vitest is **18/18**, lint, TypeScript, `scripts/verify.sh --fast`, the eleven-leg journey
  (**3.349 seconds**), and `git diff --check` pass.
- Closed the client-facing V1 schedule gap against the live web/mobile source: the server now
  exposes workspace-scoped `GET/PUT /v1/settings/planning` backed by the existing schedule tables,
  both clients load and edit the authoritative timezone/windows, and planning requests carry the
  saved schedule instead of a client hard-code. Calendar weekday numbering is explicit (Monday–Friday
  is 2–6), `WorkingScheduleStoreTest` covers Asia/Kolkata conversion, New York spring-forward, and
  overlap rejection, and the rendered browser journey saved a 13:00 Thursday start and produced a
  13:00 proposal. Server tests, mobile 18/18, web production build, `scripts/verify.sh --fast`,
  `scripts/journey.sh` (**3.377 seconds**), `bash -n`, and `git diff --check` pass. Real-provider,
  device, hosted-CI, and human-usability evidence remain external.
- Closed the web tooling gap: `apps/web` now has supported Next ESLint flat-config coverage,
  `typecheck`, and Vitest scripts. Extracted working-hours editor helpers into
  `apps/web/lib/working-schedule.ts` with four focused tests covering parsing, day selection,
  replacement, date-exception preservation, and closed-day removal. Web lint, TypeScript, and
  Vitest (**4/4**) pass; the remaining web build and rendered journey remain separate evidence.
- Extended `.github/workflows/web.yml` to run the web lint, typecheck, and Vitest gates before the
  real-account-mode production build. The workflow YAML parses and `npm ci --dry-run` confirms the
  new lockfile is reproducible; hosted execution and artifact inspection remain external.
- Repaired `PlanAnalysisJourneyInstrumentedTest` after the current API 36 device run exposed a
  date/state-sensitive fixture: it could seed a one-hour block on a weekend or outside a user's
  configured working calendar. The fixture now snapshots/restores the calendar, seeds a deterministic
  weekday schedule, and selects a valid future interval. The targeted six-test suite and the full
  `scripts/verify.sh --device` gate pass (**134/134**, zero failures, errors, or skips).
- Retried the disposable `dailybrief_api37_1_16k_fresh` AVD with a clean wipe and detached launch:
  it reports Android **37.1** and `PAGESIZE=16384`, reaches `sys.boot_completed=1`, and accepts the
  debug APK, but package/activity services still disappear or leave user 0's installed app disabled;
  repeated resolve/start attempts produced no app process or launch result. No API 37.1/16 KB runtime
  or instrumentation pass is claimed. `scripts/smoke-release.sh` now honors `ANDROID_SERIAL` for
  one-device release smoke selection; the targeted API 36 release smoke passes and navigates
  successfully. The Android CI server journey now uploads its logs and production-probe output.
- Final local recheck: `scripts/verify.sh --fast`, `scripts/journey.sh` (**3.473 seconds**), mobile
  Vitest (**18/18**), mobile lint/TypeScript, production Expo web export with an explicit origin,
  `scripts/verify-mobile-release.sh` (**105,472,525 bytes**), web lint/TypeScript/Vitest (**4/4**)
  and production build, targeted release smoke, all workflow YAML, all shell syntax, and
  `git diff --check` pass. `npm audit --omit=dev` remains **8 high** upstream Expo/Metro findings;
  Expo **57.0.14** is still the registry latest, so no unsupported forced downgrade/upgrade was
  applied. Hosted GitHub runs visible here are only for the old remote `main` SHA; the current
  `saas-extraction` worktree has no published branch or current artifact evidence.
- Fixed a CI portability defect in `scripts/journey.sh`: its fixture responses and server logs no
  longer assume the workstation-only `/tmp/opencode` directory. Evidence now goes to the ignored
  `build/journey-evidence` directory or `DAILYBRIEF_JOURNEY_DIR`, and the Android workflow uploads
  that directory. A run with an independent `/tmp/dailybrief-journey-ci-test` directory completed
  all eleven legs in **4.488 seconds** and produced the expected server/probe artifacts.
- Added `scripts/verify-api37-16k.sh` as the repeatable external handoff for the remaining Android
  runtime gate. It requires SDK **37.1**, `PAGESIZE=16384`, boot completion, unlocked user 0, and
  stable package/activity services before running the current release smoke; `--instrumentation`
  adds the full connected test task. It correctly rejects the API 36 device with an explicit SDK
  mismatch, and it will not turn APK alignment into a runtime claim. Exercising it against the
  disposable API 37.1 guest found and fixed its missing JDK fallback; the corrected verifier now
  reaches the guest's actual `user 0 is not unlocked` failure and exits without building or claiming
  runtime evidence. The guest was stopped afterward and API 36 remains online.
- Closed the API 37.1 / 16 KB emulator gate on the documented Lavapipe/Minigbm runtime: the release
  APK launched and navigated successfully, and the targeted full instrumentation run completed
  **134/134** with zero failures, errors, or skips. The XML result records `tests="134"`,
  `failures="0"`, `errors="0"`, and `skipped="0"`; the disposable emulator was stopped after
  evidence collection. Physical-device persistence/restart/corruption evidence remains external.
- Hardened the web session boundary: browser signup/OAuth now returns only `workspaceId` and sets a
  seven-day `HttpOnly` cookie (`SameSite=Lax` locally, `SameSite=None; Secure` for HTTPS), while
  mobile and headless scripts retain bearer responses. Ktor authentication accepts either path,
  CORS admits credentialed browser requests, and `/v1/auth/logout` expires the cookie. The web
  client no longer reads or writes the bearer token in localStorage and sends `credentials: include`.
  Added cookie-shape and logout-route tests; server tests, web lint/typecheck/Vitest/build, and the
  full fixture journey pass. The journey now proves the cookie can read workspace projects and
  keeps the cookie jar temporary so CI artifacts cannot contain a reusable browser credential.
- Added the missing CSRF boundary for cookie-authenticated mutations: the browser sign-in response
  carries a signed seven-day CSRF token in the body, the web client keeps it only in sessionStorage
  and sends `X-DailyBrief-CSRF` on non-safe requests, and bearer/mobile requests remain unaffected.
  CORS explicitly allows the header. Invalid or missing CSRF now registers a real Ktor challenge
  and returns 401 instead of reaching route code and producing a 500; the journey proves both the
  valid mutation and the refused mutation. `CsrfTokensTest`, server tests, and the full journey pass.
- Added a reproducible rendered web acceptance job: `apps/web/e2e/web-journey.mjs` plus
  `scripts/web-journey.sh` start a disposable Postgres/Ktor fixture and production Next server,
  then drive sign-in, calendar reconcile, Inbox capture, Plan my week, Apply, and Today with
  Playwright. `.github/workflows/web.yml` installs Chromium and uploads logs/failure screenshots.
  The first run exposed a real `/today` effect loop caused by depending on a newly-created session
  object; the effect now keys on the stable workspace ID. The isolated browser journey passes, and
  the API receives only the intended Today reads. The harness rejects occupied ports and allows
  explicit overrides for local development.
- Updated `scripts/production-probe.sh` for the browser session contract: its preflight now asks for
  `x-dailybrief-csrf` and requires `Access-Control-Allow-Credentials: true` in addition to the exact
  configured origin. The refreshed eleven-leg fixture journey and rendered browser journey both
  pass after this probe change. This is local fixture evidence; a deployed-domain probe and hosted
  CI run remain external.
- Residual register after the 2026-08-20 audit: repository-local web/API/mobile quality gates and
  fixture journeys are green. Launch evidence still needs (1) a hosted CI run and artifact
  inspection for this unpublished branch, (2) a real Google-account OAuth/calendar journey with
  refresh, timezone, and sync-failure recovery, (3) production KMS plus Stripe, Gemini, Expo,
  DNS/HTTPS, secrets, rate-limit, and observability configuration, (4) reliable-device proof of
  mobile native cache persistence/restart/corruption/isolation/failed-write behavior, (5) Mac-side
  XCFramework and Swift-import verification, (6) API 24 rendered inspection, and (7) manual
  accessibility/form-factor/process-death coverage. Product evidence still needs moderated
  consultant usability sessions and pricing/market validation. The mobile audit remains eight high
  upstream Expo/Metro findings; resolving it needs a planned framework upgrade, not
  `npm audit fix --force`. Later scope remains voice/free-form mutation, broader connectors,
  enterprise administration, PDF/deck output, and advanced portfolio/layout features. No commit or
  push was performed.

### 2026-08-23 (landing the hardening tranche; weekend-sensitive acceptance fixed)

- The 2026-08-20..22 hardening tranche (server session/CSRF/replan/workspace
  planning, web cookie client, mobile cache parity, Android print/links/digest)
  had sat uncommitted. Landed on branch `pre-launch-hardening` as five per-layer
  commits (`2bfe747` server, `e00e866` web, `e3495a0` mobile, `b3d494b`
  android, this one). Full `scripts/verify.sh`, `scripts/verify.sh --journey`,
  mobile vitest/lint/tsc, and web lint/typecheck/vitest/build re-run green on
  the landed tree before committing.
- **Defect found: the fixture acceptance was weekday-sensitive, and no tracker
  said so.** On Sunday 2026-08-23 leg 5 failed ("today does not show the
  applied blocks"): the saved Mon-Fri working week makes the planner correctly
  push every block to Monday, so `/v1/today` legitimately shows none. Every
  recorded "journey green" pass since the schedule settings landed had run on
  a weekday; `apps/web/e2e/web-journey.mjs` even carried a comment admitting it
  skipped its Today assertion at weekends — a standing evidence gap rather
  than a fix. Fix in both harnesses: seed five weekly windows onto today and
  the next four days (stored Calendar numbering, Sunday=1), so apply→Today is
  asserted unconditionally seven days a week. Journey green on the Sunday run
  (**3.7 s**); browser journey OK with the unconditional assertion.
- Remaining register unchanged: hosted CI run for the unpublished branch, real
  Google-account OAuth/calendar evidence, production KMS/Stripe/Gemini/Expo/
  DNS configuration, physical-device cache proof, Mac XCFramework/Swift
  verification, API 24 rendered inspection, manual accessibility/form-factor/
  process-death coverage, consultant usability sessions, and the eight high
  upstream Expo/Metro audit findings (needs a planned framework upgrade).

### 2026-08-25 (landing the mobile shell + portfolio-week tranche)

- Landed the uncommitted 2026-08-24..25 tranche as two per-layer commits after
  re-running every repository-local gate on the tree:
  - **Server** (`Routes.kt` + new `PortfolioWeekTest`): the `/v1/portfolio`
    week window was computed as midnight-*today* — the inline expression
    subtracted `DayOfWeek.MONDAY.getValue() - 1`, a constant zero days — so
    mid-week the window began at today and every earlier weekday's bars fell
    before its left edge (the same family of weekday-sensitive defect as the
    2026-08-23 acceptance fix, but on the read side). Extracted
    `PortfolioWeek.startOf`: Monday 00:00 UTC of the week containing now,
    matching the Monday-based axis both clients draw. Three-test suite pins
    all seven days of a known week, mid-week instants, and the exclusive far
    boundary.
  - **Mobile**: navigation moved from Stack to bottom Tabs (Today / Planner /
    Settings; login and auth-callback hidden from the bar), screen styles and
    `ui.ts` migrated from the raw `Palette` object to `C` (the launch-time
    scheme constant) so dark mode actually applies to existing screens, the
    login server footnote is dev-builds-only, Settings shows a hint when the
    schedule draft's timezone differs from the device's, and the Planner week
    Gantt clamps bar spans into the visible axis with a 1%-floor width so an
    edge block stays visible instead of vanishing between pixels.
- Gate evidence on the landed tree: `scripts/verify.sh --fast` green,
  `scripts/verify.sh --journey` green (**3.315 s**, 11 legs), mobile vitest
  **86/86**, expo lint clean, `tsc --noEmit` clean, `git diff --check` clean.
- Remaining register unchanged: hosted CI run for the unpublished branch,
  real Google-account OAuth/calendar evidence, production KMS/Stripe/Gemini/
  Expo/DNS configuration, physical-device cache proof, Mac XCFramework/Swift
  verification, API 24 rendered inspection, manual accessibility/form-factor/
  process-death coverage, consultant usability sessions, and the eight high
  upstream Expo/Metro audit findings (needs a planned framework upgrade).

