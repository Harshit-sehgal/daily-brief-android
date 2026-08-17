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
  mobile. Current step is the portability project (Stage 1) on the road to the web V1.
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
3. Only FINISH_TO_START dependencies are scheduled around (others named as
   `UnplacedTask`, disclosed) — still open.
4. All-day events become full-day hard blocks in `fixedCommitments` — **fixed**
   (`b11ad72`: `ScheduleAnalysis.fixedCommitments()` filters them out, all three
   ViewModel call sites).
5. `AutoPlan` is O(tasks × chunks × free × taken) — fine for a week, concern for
   multi-tenant server — still open.
6. Two buffer code paths (`AutoPlan` applies `bufferMinutes` itself; `PlanHealth` via
   `freeIntervals`) — **fixed** (`b11ad72`: AutoPlan's buffer path unified on
   `WorkingCalendar.freeIntervals`).
7. Test coverage inverted against product value: `AutoPlan` 5 tests / 246 lines vs
   `MultiSchedulePlanHealth` 17 tests / 608 lines — still open.

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
- All 24 HCI-principle fixes (P1–P24) are done and guarded by named tests. Still open:
  manual TalkBack/Switch/Voice passes, physical providers, API 24 rendered inspection,
  API 37.1/16 KB execution (needs a healthier host), boundary matrix at
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

### Stage 1 status

- [ ] **WP-1** Port `ScheduleAnalysis` to `commonMain` (374 lines; the last root
      blocker in `core/`, gating 861). Needs `kotlinx-datetime` + common SHA-256 for
      `signature` (decision (a) — keep the daily-brief cache key byte-identical) +
      `TimeZone` param type change across ~10 `:app` files. Signature fixture test
      first. **Next up.**
- [ ] **WP-2** Move the six files: `GanttLayout` (BigDecimal/BigInteger — keep the
      Long-limit stability, `GanttLayoutTest` pins it), `PlanGanttLayout`,
      `TimelineLayout`, `DayPulse`, `GanttZoom`, `PortfolioGantt`.
- [ ] **WP-3** `GanttInteraction` off `java.io.Serializable` (Compose `Saver` in
      `:app`, copy `NullableEventDraftSaver` shape) → 108 lines to `commonMain`.
- [ ] **WP-4** `LegacyNameKeys` via `expect`/`actual` — fixture test of known
      input→output pairs FIRST (NFKC name keys are stored on rows and compared on
      Undo; `stableId` derives v5 import IDs). JVM actual stays byte-identical.
- [ ] **WP-5** The three journal codecs (1,530 lines) as one unit — sealed
      `PlanMutationState` cannot span source sets. Needs WP-4 + WP-6.
- [ ] **WP-6** `WorkingCalendarMapper` off `Calendar` (239 lines; Sunday-is-1
      convention preserved). Can precede WP-4; WP-5 needs both.
- [x] **WP-7** `linuxX64` target added (room-common publishes linuxX64, so it works);
      metadata compile is real and wired into `verify.sh`; planting a `java.util` import
      fails the build (verified). Caught real JVM-only pollution the scan had missed —
      `toSortedMap`/`toSortedSet`, `@JvmOverloads`, `String.format`, `java.lang.System` —
      all replaced; `CommonMainPurityTest` scans the class now; `LegacyNameKeys` native
      actual throws (compile-only target; JVM actual stays byte-identical). Toolchain
      (~1.8 GB in `~/.konan`) needs one `--online` run, then `--offline` holds.
- [ ] **WP-8** Delete the `NotionClient` `commonZone` bridge (line ~76) once
      `ScheduleAnalysis` speaks kotlinx-datetime. Trivial.
- [x] **WP-9** Defects 3 (SS/FF/SF — confirmed wanted, then built), 5 (benchmark
      800 tasks / 4 weeks; measured 66 ms, no optimisation needed), 7 (coverage 5 → 17).

### Stage 2+ (not started)

- [x] **WP-10** multi-tenant Postgres schema (tenancy from day one; `saved_views` unique
  index + work_schedules partial unique index become real constraints; source column
  types from `app/schemas/.../9.json`). `db/schema.sql`; proven by `db/verify-schema.sh`
  against docker postgres:16 (4 expected refusals or the script fails).
- [x] **WP-11** the four invariants redesigned (docs/saas/09-server-invariants.md):
  SCHEDULE_MUTEX → per-tenant advisory lock with fetch outside; Undo staleness →
  claim-the-entry + FOR UPDATE (SERIALIZABLE rejected); SecretStore → KMS envelope
  (AAD = workspace + key, keep ciphertext on failed read); Gemini platform key +
  per-tenant quota (reserve-then-refuse). Code sites carry REDESIGN markers.
- **WP-12** freeze the planner API: `PlanningRequest`/`PlanningResult`/
  `PlanProposal`/`PlanConflict`/`PlanHealth` as a versioned wire contract with
  round-trip tests. Nothing in Stage 3 starts until frozen.
- **WP-13..16** vertical slice (90-second magic moment is the acceptance test),
  Google Calendar sync worker, depth + paid tier, Expo mobile. Summarised in `08`;
  plan properly when Stage 2 closes.

### Product / HCI (not part of the WP flow)

- Product: PREMIUM_PRODUCT_PLAN Phase 3 (change digest, weekly review), Phase 4
  leftovers (change log/restore points/audit UI, timestamped exports + print,
  shortcuts, notification actions, deterministic restore preview), Phase 2
  (pinch zoom, minimap, Today jump, canvas dependency creation), typography QA.
- HCI evidence: 16 KB AVD not recordable on this host (125/129 once; system_server
  crash-loops — needs a healthier host); API 24 rendered inspection; physical
  devices; manual AT passes; boundary matrix 599/600…1599/1600; 200% text; T1–T11;
  RTL/long text; process death at every edit point.

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
