# Roadmap — everything left, in order

Written 2026-08-17, after the extraction, the specification set and the deviation register were
closed. This is the forward plan: what to build, in what order, and what has to be true before
each step starts.

**Execution detail is in `08-work-packages.md`** — discrete units with files, steps, acceptance
tests and traps, written to be handed to other agents. This document is the why and the order;
that one is the what and the how.

Sequencing principle, unchanged since the teardown: **the engine is the asset, the web app is
the product, and the Android app is the shipping proof.** Work that makes the engine portable
and the domain model right comes before work that makes screens.

Current position (updated 2026-08-19): **Stage 1 is complete** (engine core 100%
portable, purity compiler-enforced via `linuxX64`; the named current suites are 272
planning-core, 26 contract, 57 server, and 184 app unit tests with zero failures), **Stages 2–3 are
complete** (multi-tenant schema, frozen v1 contract, and the vertical slice with its
automated `journey.sh` acceptance in the gate), **Stage 4 is complete** (Capacity,
Projects/Board, dependencies and critical path, scenarios/baselines/portfolio, and
billing with the tier boundary enforced server-side), and **the Stage 5 mobile MVP and its
depth trail have shipped** (WP-M1…WP-M4 plus, 2026-08-19: the Expo push-token wiring,
the Capacity check, the Today wall-clock timeline, the per-project week Gantt, and the
server-side WP-14 hardening — token refresh and the `sync_runs` ledger). The engine now
honours per-project schedules on the wire (`scheduleIdByTaskId`, `64b8cf9`). All engine
defects are closed (WP-9, `4aac835`, `df1ef68`).
What is left is the external verification tail: direct native-file evidence for the mobile
offline cache, API 37.1/16 KB Android execution, the iOS build's first Mac-side verification
(`xcodebuild` and a Swift compile need a Mac), actual CI artifact runs, and real-provider and
human usability evidence. The API36 release slice now completes fixture signup, survives a
force-stop/relaunch, and serves the cached Today screen after the fixture server is stopped;
the config plugin also makes a fresh Expo prebuild reproducible, and
`scripts/verify-mobile-release.sh` now passes the complete local production release path,
including four-ABI packaging and merged-manifest inspection. The cache's
platform-neutral encoding, corruption, compatibility, key-isolation, web-storage, and
failed-write rules are now executable-tested in
`apps/mobile/src/lib/offline-cache-core.test.ts` and `apps/mobile/src/lib/offline-cache.test.ts`.

**Next: external verification and the final product-readiness evidence** — see the per-stage
sections below.
Execution detail in `08-work-packages.md`.

Update 2026-08-23: the 2026-08-20..22 hardening tranche (server session/CSRF, replan
with replacement, workspace-wide planning and schedule settings; web cookie client;
mobile offline-cache parity; Android print/dependency-links/digest) is landed on the
`pre-launch-hardening` branch. Landing it exposed that the fixture acceptance was
weekday-sensitive — on a Sunday the Mon–Fri working week correctly moves every block
to Monday and Today shows none — so both `scripts/journey.sh` and the rendered browser
journey now seed five weekly windows starting today (stored Calendar numbering,
Sunday=1) and assert apply→Today unconditionally, any day of the week.

The client-facing V1 schedule gap is now closed in the repository: workspace-scoped
`GET/PUT /v1/settings/planning` persists the working-week timezone and windows through the existing
schedule tables; web and mobile settings editors read/write that contract; planning requests use
the saved schedule; and server tests cover Asia/Kolkata conversion, New York spring-forward, and
overlap rejection. The fixture journey round-trips the schedule, and the rendered browser journey
saved a 13:00 Thursday start and produced a 13:00 proposal. This closes the implementation and
local-evidence task, but it does not replace real-provider, device, hosted-CI, or human-usability
evidence.

---

## Stage 1 — Finish the portability project (complete)

The detailed sequence below is retained as historical execution context. The current position
at the top of this file is authoritative; none of the old "what is left" statements below is an
active Stage 1 blocker.

**Why first:** every later stage assumes one engine runs in three places. Until the date-time
cluster moves, "one engine" is an intention rather than a fact, and the Ktor service in Stage 3
would be built against a `jvmShared` engine that iOS can never load.

### 1.1 The date-time cluster — complete

**`IsoDates` and `WorkingCalendar` are ported** (`b436b1e`, `e440cd3`). `WorkingCalendar` was
the lever: it released `PlanHealth`, `MultiSchedulePlanHealth`, `AutoPlan`, `PlanBlockPreview`
and `PlanScenarios` — 1,633 lines needing nothing else. The DST policy is written down in
`AmbiguousLocalTime` and both pinned tests pass unchanged.

**What was left before the final portability pass: `ScheduleAnalysis` (374 lines), the last root
blocker**, gating `GanttLayout`,
`TimelineLayout`, `DayPulse`, `GanttZoom`, `PlanGanttLayout` and `PortfolioGantt` — 861 lines.
Plus `GanttInteraction`'s `java.io.Serializable` (108 lines, independent).

One decision inside it is not mechanical, and is called out in 1.2 below.

Original scope, for the record — 1,282 lines across six files:

| File | Lines | What it needs |
| --- | ---: | --- |
| `WorkingCalendar` | 309 | `Calendar`, `SimpleDateFormat`, `TimeZone`, `ParsePosition` |
| `ScheduleAnalysis` | 374 | as above, plus `MessageDigest` + `ByteBuffer` for `signature` |
| `GanttLayout` | 287 | as above, plus `BigDecimal`/`BigInteger` for Long-limit stability |
| `TimelineLayout` | 131 | `Calendar`, `TimeZone` |
| `DayPulse` | 122 | `TimeZone` |
| `IsoDates` | 79 | `SimpleDateFormat`, `Date`, `Locale` |

Clearing `WorkingCalendar` alone unblocked `AutoPlan`, `PlanHealth`, `MultiSchedulePlanHealth`,
`PlanBlockPreview`, `PlanScenarios` and `GanttInteraction`. Clearing `ScheduleAnalysis` unblocked
`GanttZoom`, `PlanGanttLayout`, `PortfolioGantt` and the two layout files. Together those passes
took the engine from its earlier 33% snapshot to full core portability.

**The decision that must be made explicitly, not inherited.** `WorkingCalendarTest` pins
`java.util.Calendar`'s DST behaviour: a spring-forward gap normalises **forward**, and a
fall-back ambiguous wall time resolves to the **later, standard-time** occurrence.
`kotlinx-datetime` and `java.time` resolve fall-back to the **earlier** occurrence by default.
Port naively and a tested contract flips silently — and it flips on a server resolving many time
zones at once, not on one phone in one zone.

Implement the policy as named code (`AmbiguousLocalTime.LATER_OFFSET`), keep the existing tests
untouched as the proof, and let the name carry the decision.

**Order of work:** `IsoDates` (79 lines, no dependants — the rehearsal) → `WorkingCalendar` →
`ScheduleAnalysis` → the four that follow for free.

**Not a mechanical port.** Budget it as a project with its own review, not as a chore.

### 1.2 `signature`, separately

`ScheduleAnalysis.signature` is SHA-256 over length-prefixed fields, and it is the **daily-brief
cache key**. A common-Kotlin SHA-256 that differs by a byte invalidates every cached brief once
— acceptable, but it should be a decision, and the migration should expect one cache miss per
user rather than treating it as a bug.

### 1.3 The journal codecs — 1,530 lines, one step

`PlanMutationCodec`, `PlanCatalogMutationCodec` and `WorkingScheduleMutationCodec` implement one
sealed `PlanMutationState` hierarchy, so they move together or not at all. Gated on both
`LegacyNameKeys` and `WorkingCalendar`, which means **after 1.1**.

`LegacyNameKeys` is 22 lines and the most dangerous file in the module: `nameKey` is stored on
rows and compared on Undo, and `stableId` is how the v5 catalog import derives IDs that survive
every later migration. A reimplementation differing by one character breaks existing databases.
Use `expect`/`actual` — JVM keeps `Normalizer`; iOS uses
`precomposedStringWithCompatibilityMapping` — and add a fixture test asserting known
input→output pairs before touching anything.

### 1.4 Compiler-enforced purity

Added the non-JVM targets: `linuxX64` makes `compileCommonMainKotlinMetadata` execute and the
compiler takes over from `CommonMainPurityTest`; `iosArm64` and `iosSimulatorArm64` configure the
iOS path. The test remains as a faster diagnostic duplicate.

**Exit criteria for Stage 1:** engine ≥90% `commonMain`; DST policy named in code and still
pinned by the original tests; `LegacyNameKeys` fixture-tested; purity enforced by the compiler.

---

## Stage 2 — The domain model and the server contract (complete)

**Why here:** the model is easier to get right against a portable engine, and every later stage
writes against it. Getting it wrong is the expensive mistake — the one that forces a rewrite six
months in.

### 2.1 The multi-tenant schema

From `03-domain-and-architecture.md`. `PlanBoard` → `Project` (a client engagement), plus
**`Client`, which the current schema lacks entirely** and the ICP requires. Then
`PlanColumn` → `WorkflowStage`, `PlanItem` → `Task`, `PlanBlock` → `ScheduledBlock`,
`BriefingEvent` → `ExternalEvent`, `PlanMutation` → `AuditEntry`, `WorkSchedule` unchanged, plus
`User`, `Workspace`, `Membership`, `CalendarConnection`, `PlanRun`, `PlanProposal`,
`Subscription`.

Every row carries `tenant_id` from day one, even while V1 is single-user. Retrofitting tenancy
is the six-month rewrite this stage exists to avoid.

### 2.2 The four invariants that need redesign, not translation

- **`SCHEDULE_MUTEX`** is a process-wide `Mutex` holding provider I/O inside the lock. Server
  form: a per-tenant advisory lock with the fetch **outside** it — you cannot hold a DB lock
  across an outbound HTTP call at scale. Preserve the property: reconciliation of a source's
  rows and a concurrent user edit must not interleave.
- **Undo's staleness check** is whole-row value equality inside one SQLite transaction on a
  single-writer database. Postgres needs `SERIALIZABLE` or `SELECT … FOR UPDATE` on the journal
  row plus the affected entities. The codecs port unchanged once 1.3 lands.
- **`SecretStore`** → KMS envelope encryption. Keep the design: AAD bound to the setting key,
  never destroy ciphertext on a failed read. **Shipped 2026-08-18:** the envelope now stands on
  a `KeyProvider` seam — `LocalKeyProvider` (the dev master hex) and `AwsKmsKeyProvider`
  (KMS Encrypt/Decrypt over plain REST, SigV4 hand-rolled and pinned byte-for-byte to AWS's
  documented test vector, no SDK). The stored envelope shape is unchanged, so rotation and
  migration stay out of the data path; `calendar_connections.token_kms_key_id` records which
  authority wrapped the key.
- **Two constraints live only in app code** and must become real Postgres constraints:
  `saved_views` unique on `(boardId, surface, nameKey)`, and `work_schedules` "exactly one
  non-archived default" as a partial unique index. **Shipped 2026-08-18 (app schema v10):**
  the `saved_views` unique index is native Room; Room cannot express partial indexes, so the
  `work_schedules` one rides `MIGRATION_9_10` for upgrades and an onCreate callback for fresh
  installs, and `PlanMigrationInstrumentedTest` watches the database refuse the violations —
  a duplicate view name on a board+surface, a second non-archived default, and the archived
  default that stays legal.

### 2.3 Freeze the planner API

`04-planner-api-contract.md` becomes executable: `PlanningRequest` / `PlanningResult` /
`PlanProposal` / `PlanConflict` / `PlanHealth` as a versioned wire contract with round-trip
tests. Nothing in Stage 3 starts until this is frozen.

**Exit criteria:** schema migrated and seeded locally; contract round-trips; the four invariants
have written designs with the failure mode named.

---

## Stage 3 — The vertical slice (complete)

One journey, excellent, nothing else: **sign in → connect a calendar → add tasks → "Plan my
week" → see a proposal with reasons → apply → see it on Today.**

- Ktor planning service wrapping `packages/planning-core`; the API calls it, never reimplements it.
- Next.js + TypeScript for the original first slice: Today and Planner only. Projects, Board,
  and depth views were added later under Stage 4; the current command-center hierarchy is in
  `11-strategy-reconciliation.md`.
- Google Calendar sync as a background worker: OAuth server-side, tokens encrypted at rest,
  `SyncMergePolicy`'s rules ported exactly — **times and location are source-owned; wording and
  placement are user-owned; `userEdited` is sticky.**

**The 90-second magic moment is the acceptance test**, not a metaphor: from signup to a
proposal the user accepts or rejects, in 90 seconds, on a real calendar. If it takes three
minutes, that is a bug in this stage and not something to fix later.

**Exit criteria:** the loop works end to end on a real Google account; a rejected proposal
writes nothing; Undo restores exactly.

---

## Stage 4 — Depth, and the paid tier

Only once the loop is used. In `01-product-teardown.md` priority order:

1. **Capacity** — `MultiSchedulePlanHealth` behind three numbers and one sentence: `POST /v1/capacity`
   answers *"can I take another client next week?"* with available/planned/spare minutes and a
   verdict sentence — yes, no, or the minimal set of deferrable tasks that would have to move
   (`CapacityAnswer` in `commonMain`, golden-pinned in the contract, a journey step, and a strip
   on the web Planner). **Shipped 2026-08-18.** Per-project schedules on the wire's
   `scheduleIdByTaskId` and the Capacity surface in the mobile app shipped 2026-08-19: the
   engine plans and assesses health per assigned schedule (default-filled unassigned tasks,
   single-spec path untouched until a request assigns), and both web and mobile send the
   assignments through the same frozen v1 request.
2. **Projects → Tasks / Board / Timeline** — progressive disclosure; Timeline last. **Board
   shipped 2026-08-18** (contract + migration + routes + journey step + web surface); the
   Timeline is the portfolio Gantt, which folds in with the portfolio at 4.
3. **Dependencies and critical path** — including engine defect 3 (SS/FF/SF scheduling), which
   consultants with client hand-offs will hit immediately. **Shipped 2026-08-18:** the greedy
   now places candidates in dependency-topological order (a successor is never offered a slot
   before its predecessor has one — the real residue of defect 3, discovered when the journey
   proved FS ordering), the golden request pins all four types, and the web board links tasks
   with a "waits for" picker.
4. **Scenarios, baselines, portfolio** — `PortfolioGantt` folds in here. **Shipped 2026-08-18:**
   `POST /v1/scenarios` runs the same engine under the default three orderings (due, priority,
   quick wins — the app's `PlanScenarios`) and returns the engine's own spread sentence, writing
   nothing; `POST /v1/baselines` snapshots the schedule in frozen wire form (V3 migration,
   jsonb), and `GET /v1/baselines/{id}/variance` reports `BaselineVariance` against today;
   `GET /v1/portfolio` is the per-project rollup with the week's blocks, which is what the Gantt
   folds in with — the web surface draws the strip from them.
5. **Billing** — Stripe, per-tenant quota, the trial boundary in `02`. **Shipped 2026-08-18:**
   the boundary from the tier table is enforced server-side (`TierLimits`, pure and unit-tested:
   one project free, capacity and baselines paid, the engine itself never tiered) and reported
   on `GET /v1/billing` with usage, so a client renders the boundary instead of guessing it.
   Checkout and webhook sit behind a `BillingProvider` seam: fixture mode runs the whole
   boundary in the journey (402s, signature-checked upgrade), a real deployment activates
   `StripeBillingProvider` (REST, no SDK) when keys are present, and without keys the seam
   answers 501 honestly instead of pretending.

---

## Stage 5 — Mobile  (MVP shipped 2026-08-18)

Expo + React Native for the UI, native Kotlin/Swift modules where needed, `packages/planning-core` via
KMP for local preview and offline planning.

**The rule that keeps this sane:** the local engine is for *preview, simulation and instant
feedback*; the **server is authoritative for Apply**. Both run the same code, so a preview and
a commit cannot disagree about what is possible — only about what is current.

What shipped (WP-M1…WP-M4):

- `packages/planning-core` gained `iosArm64`/`iosSimulatorArm64` (WP-M1) so iOS can load the same
  engine; `LegacyNameKeys` has a real iOS `actual`.
- The `planner-engine` native module (`apps/mobile/modules/planner-engine`): `previewPlan(json)`
  — one String in, one String out through `EnginePreview` in `commonMain`, which reuses the
  exact `Mapping` the server runs. `:planning-contract` became KMP so the contract types are
  portable with the engine.
- The `apps/mobile/` Expo app: Login (fixture demo + Google OAuth), Today, Planner (Plan my
  week / proposal / Apply / Undo) and Settings, talking to `:server`.
- Android ships: the AARs publish with `scripts/publish-engine-local.sh --mobile` (compileSdk
  36), and both debug and release APKs build with the engine embedded. On-device interactive
  smoke testing is blocked by an emulator-image first-frame bug on this machine — see
  `apps/mobile/README.md` "Known limitation" — so the on-device evidence is: APK installs, JS
  runs, every screen's view hierarchy lays out, and the full signup→plan→apply→undo journey
  is green against the fixture server at the wire level.

Shipped 2026-08-19: the Expo-side push-token wiring (`expo-notifications` mints the
install token, registers it at app start once a session exists, deregisters at
sign-out), the Capacity check (hours/week input, verdict sentence, numbers and the moves
list), the Today wall-clock day timeline, and the per-project week Gantt beside the
portfolio strip.

The mobile cache implementation now covers Today, the workspace task set, project lists, boards,
and the portfolio strip;
TypeScript, lint, Expo web export, and dependency checks pass. Runtime proof of persistence,
corrupt-entry recovery, and workspace isolation still belongs in the device evidence matrix.
The iOS side remains Linux-reviewed but unverified: `xcodebuild` and a Swift compile need a Mac.
Projects/Board, the offline preview fallback, the daily brief with per-tenant quota, and the
"This week" portfolio strip shipped 2026-08-18.

The iOS build script got its Linux-side review 2026-08-18: the two framework tasks match
the KMP targets, `baseName = "PlannerCore"` matches the Swift `import PlannerCore`, the
output paths follow the KMP `releaseFramework` convention, and the module's podspec
statically links it. Two fixes landed: the repo-root resolution now survives invocation
from any directory (the old `dirname "$0"` broke from elsewhere), and the script's
comment points at `apps/mobile/README.md` "iOS" instead of a README that does not exist. What
remains unverifiable here is the actual `xcodebuild -create-xcframework` step and a
Swift compile against it — both need a Mac.

Shipped 2026-08-18 (mobile depth): Projects/Board in the mobile Planner (project chips,
tasks grouped by the board's stages, adds landing in the selected project), the offline
preview fallback wired into "Plan my week" — when the server is unreachable the same
request runs through the native `previewPlan` bridge (same Mapping, same engine; the
proposal is labelled "offline preview" and Apply is hidden because the server stays
authoritative), and the Daily brief as a server-side, quota-managed surface: `POST
/v1/summary` behind a `SummaryProvider` seam (fixture mode for the journey, plain REST to
Gemini's generateContent when `GEMINI_API_KEY` is set, 501 without one), the
`gemini_usage` ledger (V5) with reserve-then-refuse in the request transaction and a
refund when the provider fails, and quota shown next to the brief in both clients ("2 of
3 used this month"). The journey's step 10 burns the fixture limit and watches the fourth
request come back 429, then proves a second workspace's ledger is its own. **Alarms stay
with the Android app** — it continues as the shipping product.

Shipped 2026-08-18 (server push): the server-side half of "alarms" for the Expo client —
a tenant-bound device registry (`push_tokens`, V6) with `POST /v1/devices` /
`DELETE /v1/devices` (the primary key is `(workspace_id, token)`, so one tenant can
never ring another's phone), a `PushProvider` seam (fixture recorder for the journey,
plain REST to Expo's push service when `EXPO_ACCESS_TOKEN` is set, silent when it is
not), and the one meaningful trigger: Apply. Every applied run sends "Your plan was
applied" to the workspace's tokens, fire-and-forget — the delivery is wrapped so a push
outage can never fail an apply that already committed. The journey's step 11 registers
two tokens on the main workspace and one on the trial workspace, deregisters one, applies
a fresh plan, and asserts exactly one delivery (the right token, the applied entry's id
in `data`, never the deleted or the foreign token). The Expo-side wiring shipped
2026-08-19 (`b71460a`): `expo-notifications` mints the install token, `POST /v1/devices`
registers it at app start once a session exists, and sign-out deregisters.

Shipped 2026-08-18 (mobile week strip): the Planner gains a "This week" portfolio strip —
one row per project, bars placed across the week by `GET /v1/portfolio` (the same rows,
`weekBlocks` and note the web client renders), refreshing on focus and after every apply.
It is a view, not a control: a portfolio failure leaves the board untouched. TypeScript
and the web export stay green.

The existing Android app continues as the shipping product throughout, and does not block any
of this.

---

## Engine defects — all closed

| # | Defect | Resolution |
| --- | --- | --- |
| 3 | Only `FINISH_TO_START` dependencies are scheduled around | **Fixed** (`4aac835`): `AutoPlan` schedules around all four types; SS/FF/SF disclosed as unplaced only when a slot genuinely cannot honour them |
| 5 | `AutoPlan` is O(tasks × chunks × free × taken) | **Closed** (`df1ef68`): `AutoPlanBenchmarkTest` measures 800 tasks / 4 weeks at 66 ms — no optimisation needed; the tripwire is the deliverable |
| 7 | Coverage inverted against product value | **Closed** (`df1ef68`): `AutoPlan` grew from 5 to 17 tests, matching `MultiSchedulePlanHealth` |

Defects 1, 2, 4 and 6 were fixed earlier (`b11ad72`).

---

## Risks worth naming

- **Historical risk — the date-time port was the programme's critical path.** If DST resolution changes
  silently, working windows move for real users and no test says so. Mitigation: the policy is
  named code and the original tests are the proof.
- **`LegacyNameKeys` can corrupt existing installs.** Mitigation: fixture tests before the port,
  `expect`/`actual` rather than reimplementation.
- **Scope drift has already happened once.** Mitigation: the working agreement in `02` — name
  the surface and the document before building. `06-scope-deviations.md` is the record of what
  it costs.
- **Two products, one team.** The Android app keeps shipping while the web V1 is built. Every
  Android feature is a thing to port, reconcile or explicitly abandon. Say which, per feature,
  at the time.
- **Current external proof boundary.** Real Google OAuth, production provider configuration,
  Mac-side iOS output, physical devices, broad accessibility, and consultant usability remain
  evidence tasks; local green gates do not prove them.

---

## What "done" means for the current programme

The extraction and depth programme are finished when the engine is ≥90% `commonMain`, the
compiler enforces it, the domain model is multi-tenant, the planner contract is frozen, and the
vertical slice runs a real calendar end to end. Those repository gates now pass. Product launch
still requires the external evidence listed above and real-user validation of the command-center
loop.
