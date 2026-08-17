# Roadmap — everything left, in order

Written 2026-08-17, after the extraction, the specification set and the deviation register were
closed. This is the forward plan: what to build, in what order, and what has to be true before
each step starts.

Sequencing principle, unchanged since the teardown: **the engine is the asset, the web app is
the product, and the Android app is the shipping proof.** Work that makes the engine portable
and the domain model right comes before work that makes screens.

Current position (updated 2026-08-17 after Stage 1.1): the **engine core is 74% portable**
(59% of the whole module), 401 JVM tests green, the four specification documents exist, and no
scope deviation is open. `ScheduleAnalysis` is the last root blocker in `core/`.

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

1. **Capacity** — `MultiSchedulePlanHealth` behind three numbers and one sentence. This answers
   *"can I take another client next week?"*, which is the ICP's actual question and the reason
   the max-flow code is the most commercially valuable in the repository.
2. **Projects → Tasks / Board / Timeline** — progressive disclosure; Timeline last.
3. **Dependencies and critical path** — including engine defect 3 (SS/FF/SF scheduling), which
   consultants with client hand-offs will hit immediately.
4. **Scenarios, baselines, portfolio** — `PortfolioGantt` folds in here.
5. **Billing** — Stripe, per-tenant quota, the trial boundary in `02`.

---

## Stage 5 — Mobile

Expo + React Native for the UI, native Kotlin/Swift modules where needed, `planning-core` via
KMP for local preview and offline planning.

**The rule that keeps this sane:** the local engine is for *preview, simulation and instant
feedback*; the **server is authoritative for Apply**. Both run the same code, so a preview and
a commit cannot disagree about what is possible — only about what is current.

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
