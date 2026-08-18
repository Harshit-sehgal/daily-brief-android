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

Current position (updated 2026-08-18): **Stage 1 is complete** (engine core 100%
portable, purity compiler-enforced via `linuxX64`, 422 JVM tests green), **Stages 2–3 are
complete** (multi-tenant schema, frozen v1 contract, and the vertical slice with its
automated `journey.sh` acceptance in the gate), and **the Stage 5 mobile MVP has shipped**
(WP-M1…WP-M4: an Expo app with Login/Today/Planner/Settings wired to the Ktor server, plus
the `planner-engine` local-preview module running `planning-core`'s engine on-device).
What is left is Stage 4 depth (Capacity, Board, dependencies, scenarios, billing), the
remaining Stage 5 depth (Projects, Gantt, alarms, Gemini summaries, offline-first), and
the open engine defects below.

**Next: Stage 2** — the multi-tenant domain model and a frozen planner contract. Execution detail
in `08-work-packages.md`, WP-10 onward.

---

## Stage 1 — Finish the portability project

**Why first:** every later stage assumes one engine runs in three places. Until the date-time
cluster moves, "one engine" is an intention rather than a fact, and the Ktor service in Stage 3
would be built against a `jvmShared` engine that iOS can never load.

### 1.1 The date-time cluster — mostly done

**`IsoDates` and `WorkingCalendar` are ported** (`b436b1e`, `e440cd3`). `WorkingCalendar` was
the lever: it released `PlanHealth`, `MultiSchedulePlanHealth`, `AutoPlan`, `PlanBlockPreview`
and `PlanScenarios` — 1,633 lines needing nothing else. The DST policy is written down in
`AmbiguousLocalTime` and both pinned tests pass unchanged.

**What is left: `ScheduleAnalysis` (374 lines), the last root blocker**, gating `GanttLayout`,
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

Clearing `WorkingCalendar` alone unblocks `AutoPlan`, `PlanHealth`, `MultiSchedulePlanHealth`,
`PlanBlockPreview`, `PlanScenarios` and `GanttInteraction`. Clearing `ScheduleAnalysis` unblocks
`GanttZoom`, `PlanGanttLayout`, `PortfolioGantt` and the two layout files. Together they take
the engine from 33% to roughly 90%.

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

Add a non-JVM target so `compileCommonMainKotlinMetadata` stops being SKIPPED and the compiler
takes over from `CommonMainPurityTest`. On Linux the cheap option is `linuxX64`; the real one is
`iosArm64` when iOS work starts. Roughly 1 GB of Kotlin/Native toolchain, and it removes a guard
that currently exists only because the compiler check fails open.

**Exit criteria for Stage 1:** engine ≥90% `commonMain`; DST policy named in code and still
pinned by the original tests; `LegacyNameKeys` fixture-tested; purity enforced by the compiler.

---

## Stage 2 — The domain model and the server contract

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
  never destroy ciphertext on a failed read.
- **Two constraints live only in app code** and must become real Postgres constraints:
  `saved_views` unique on `(boardId, surface, nameKey)`, and `work_schedules` "exactly one
  non-archived default" as a partial unique index.

### 2.3 Freeze the planner API

`04-planner-api-contract.md` becomes executable: `PlanningRequest` / `PlanningResult` /
`PlanProposal` / `PlanConflict` / `PlanHealth` as a versioned wire contract with round-trip
tests. Nothing in Stage 3 starts until this is frozen.

**Exit criteria:** schema migrated and seeded locally; contract round-trips; the four invariants
have written designs with the failure mode named.

---

## Stage 3 — The vertical slice

One journey, excellent, nothing else: **sign in → connect a calendar → add tasks → "Plan my
week" → see a proposal with reasons → apply → see it on Today.**

- Ktor planning service wrapping `planning-core`; the API calls it, never reimplements it.
- Next.js + TypeScript for Today and Planner only. No Projects, no Board, no Gantt.
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
   on the web Planner). **Shipped 2026-08-18.** What remains here: per-project schedules on the
   wire's `scheduleIdByTaskId` once Stage 4.2 lands, and the Capacity surface in the mobile app.
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

Expo + React Native for the UI, native Kotlin/Swift modules where needed, `planning-core` via
KMP for local preview and offline planning.

**The rule that keeps this sane:** the local engine is for *preview, simulation and instant
feedback*; the **server is authoritative for Apply**. Both run the same code, so a preview and
a commit cannot disagree about what is possible — only about what is current.

What shipped (WP-M1…WP-M4):

- `planning-core` gained `iosArm64`/`iosSimulatorArm64` (WP-M1) so iOS can load the same
  engine; `LegacyNameKeys` has a real iOS `actual`.
- The `planner-engine` native module (`mobile/modules/planner-engine`): `previewPlan(json)`
  — one String in, one String out through `EnginePreview` in `commonMain`, which reuses the
  exact `Mapping` the server runs. `:planning-contract` became KMP so the contract types are
  portable with the engine.
- The `mobile/` Expo app: Login (fixture demo + Google OAuth), Today, Planner (Plan my
  week / proposal / Apply / Undo) and Settings, talking to `:server`.
- Android ships: the AARs publish with `scripts/publish-engine-local.sh --mobile` (compileSdk
  36), and both debug and release APKs build with the engine embedded. On-device interactive
  smoke testing is blocked by an emulator-image first-frame bug on this machine — see
  `mobile/README.md` "Known limitation" — so the on-device evidence is: APK installs, JS
  runs, every screen's view hierarchy lays out, and the full signup→plan→apply→undo journey
  is green against the fixture server at the wire level.

Remaining in this stage: Projects/Board/Timeline, Gantt, alarms, Gemini summaries,
offline-first (local preview already exists), and the iOS side (XCFramework build is
written and unverified on this Linux machine).

The existing Android app continues as the shipping product throughout, and does not block any
of this.

---

## Engine defects still open

| # | Defect | Where it bites |
| --- | --- | --- |
| 3 | Only `FINISH_TO_START` dependencies are scheduled around; SS/FF/SF are disclosed as unplaced | Stage 4.3 — consultants with client hand-offs |
| 5 | `AutoPlan` is O(tasks × chunks × free × taken) | Stage 3 — fine on a phone for one week, a shared server planning 8 projects over 4 weeks is different |
| 7 | Coverage inverted against product value — `AutoPlan` is the sold feature and the least tested | Stage 1, alongside the date-time work |

Defects 1, 2, 4 and 6 are fixed (`b11ad72`).

---

## Risks worth naming

- **The date-time port is the whole programme's critical path.** If DST resolution changes
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

---

## What "done" means for the current programme

The extraction is finished when the engine is ≥90% `commonMain`, the compiler enforces it, the
domain model is multi-tenant, the planner contract is frozen, and the vertical slice runs a
real calendar end to end. Everything after that is product.
