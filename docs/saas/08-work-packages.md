# Work packages — handover brief

Discrete, self-contained units of work. Each one names its goal, its files, its acceptance test
and the traps that will bite you. Take one package at a time; each is independently verifiable
and independently committable.

**Strategy lives in `07-roadmap.md`. This document is execution.** If the two disagree, the
roadmap is the intent and this is the detail.

---

## 0. Read this first

### The repository

Two Gradle modules. `:app` is a shipping Android app (Compose, Room, no DI framework).
`:planning-core` is the scheduling engine and its domain model, a Kotlin Multiplatform module
being made portable so a server and eventually iOS can run the same code the phone runs.

`CLAUDE.md` is the working agreement and lists the invariants. Read it before your first change;
several of them look like arbitrary style until you know the defect that caused them.

### How to verify anything

```bash
scripts/verify.sh --fast      # :planning-core:jvmTest + app unit tests, ~seconds warm
scripts/verify.sh             # the whole gate: both suites, both lints, both APKs, R8
scripts/verify.sh --device    # ...plus instrumentation on the `dailybrief` AVD
```

`scripts/verify.sh` is the same task list CI runs. **A change is not done until it is green.**

Current baseline, so you can tell whether you broke something: **243 engine tests / 34 suites,
179 app tests / 40 suites, zero failures.** The engine core is **100% portable** — `core/` has no
files left in `jvmShared`, and the whole module is **99%** (11 lines remain: the JVM `actual` for
`LegacyNameKeys`).

**Stage 1 is complete.** WP-1 through WP-9 are done. Start at WP-10 unless a package below says
otherwise.

### Seven traps that have already cost time

1. **A piped Gradle command always reports success.** `./gradlew … | tail` gives you `tail`'s
   exit code, not Gradle's. Read the output text, never the status.
2. **`compileSdk` must be 37.1, never bare 37.** The pinned SDK has only `platforms;android-37.1`.
   `compileSdk = 37` asks for 37.0 and sends Gradle to the network for a platform that is not
   there — and because a download reports no task progress, the build looks hung rather than
   failed. It sat for thirteen minutes once. Both modules write
   `compileSdk { version = release(37) { minorApiLevel = 1 } }`.
3. **Only the compiler can tell you whether a file is movable.** Import scanning misses
   same-package references (`core/` is one package). Regex over symbol names produces false
   positives and cannot see sealed-hierarchy or visibility rules. Both methods produced
   confident wrong answers here. **Move the file, compile, read the error.**
4. **`commonMain` purity is compiler-enforced now — but it was not, and it failed *open*.**
   `compileCommonMainKotlinMetadata` used to be SKIPPED, because Kotlin only builds metadata once
   a non-JVM target needs it. WP-7 added `linuxX64`, so the task executes and a `java.*` import in
   `commonMain` now fails the build (`Unresolved reference 'java'`), verified by planting one.
   `CommonMainPurityTest` remains as a faster duplicate with a better error message, and it
   catches things that are *not* imports and so slipped past the old scan: `toSortedMap`,
   `@JvmOverloads`, `String.format`, `System.currentTimeMillis`. The native target only
   **compiles** — `LegacyNameKeys`'s native `actual` throws — so never assume `linuxX64` runs
   the planner.
5. **Kotlin will not smart-cast a `val` from another module.** `if (row.dayOfWeek != null)
   use(row.dayOfWeek)` does not compile in `:app` against a `:planning-core` type. Bind a local
   first. Expect this on every nullable model field you touch.
6. **`minSdk` is 24 and the engine's dates are `java.time`-backed.** `:app` therefore has core
   library desugaring enabled. Do not remove it while `minSdk < 26`.
7. **Some tests read the source tree at runtime.** `CommonMainPurityTest` and
   `UiConsistencyTest` do. Their Gradle inputs are declared in `planning-core/build.gradle.kts`;
   if you add another such test, declare its inputs or it will silently report stale results.

### Rules that are not negotiable

- **Behaviour preservation is the default.** A port is proved by the *existing* tests passing
  unchanged. If you need to change a test to make a port pass, stop — you have changed
  behaviour, and that is a separate, explicitly-approved commit.
- **A file earns a place in `:planning-core` by being called by the planner**, not by being free
  of Android imports. Ask "would the server run this?"
- **Name the surface and the document before building anything new.** If no document says a
  feature ships, that is the change to argue for, not the code. See `06-scope-deviations.md` for
  what ignoring this cost.

---

## Dependency order

```
WP-1 ScheduleAnalysis ──┬── WP-2 the six files it gates
                        └── WP-8 remove the NotionClient bridge

WP-3 GanttInteraction   (independent, small)

WP-4 LegacyNameKeys ────┬── WP-5 journal codecs (needs WP-6 too)
WP-6 WorkingCalendarMapper ─┘

WP-7 non-JVM target     (after WP-1..WP-6; makes the guard redundant)

WP-9 engine defects     (independent of all the above)

Stage 2 (WP-10..WP-12) starts once WP-1..WP-7 are done.
```

**All of Stage 1 (WP-1 … WP-9) is complete.** The graph above is kept as the record of how it
was sequenced. Stage 2 starts at WP-10, and WP-10 → WP-11 → WP-12 is a chain: the schema decides
the invariants, and both decide the contract.

---

# Stage 1 — finish the portability project

## WP-1 — Port `ScheduleAnalysis` to `commonMain`  ✅ done (`c32c7ad`)

**Size:** large. The last root blocker in `core/`, and unlike `WorkingCalendar` this one has a
caller blast radius.

**Goal:** `planning-core/src/jvmShared/kotlin/com/example/core/ScheduleAnalysis.kt` (374 lines)
moves to `commonMain`, unblocking 861 lines behind it.

**What it needs replacing:** `java.util.Calendar`, `java.util.TimeZone`,
`java.security.MessageDigest`, `java.nio.ByteBuffer`.

### The part that is not mechanical

**Nine public functions take `java.util.TimeZone`**, and **40 files in `:app` call
`ScheduleAnalysis.`**:

```
dayBounds, startOfDay, startOfDayOffset, localDayFromUtcMillis,
normalizeAllDayUtcRange, utcMillisFromLocalDay, withTimeOfDay,
suggestedEventStart, moveEventByDays, moveEventToDay, hourOf, minuteOf,
isSameDay, groupByDay
```

`WorkingCalendar` was invisible to callers because its API takes `spec.zoneId: String`. This one
is not. You must change the parameter type to `kotlinx.datetime.TimeZone` and fix every call
site. Ten `:app` files import `java.util.TimeZone` today:

```
main/java/com/example/data/api/DeviceCalendarSync.kt
main/java/com/example/data/api/NotionClient.kt
main/java/com/example/data/database/PlanMigration.kt
main/java/com/example/data/repository/PlanRepository.kt
main/java/com/example/ui/components/WorkingScheduleEditor.kt
main/java/com/example/ui/viewmodel/EventDraftEdits.kt
test/java/com/example/data/api/NotionClientTest.kt
test/java/com/example/ui/viewmodel/EventDraftEditsTest.kt
androidTest/java/com/example/PlanMigrationInstrumentedTest.kt
androidTest/java/com/example/WorkingCalendarRepositoryInstrumentedTest.kt
```

Convert with kotlinx-datetime's own exact converters — `zone.toZoneId().toKotlinTimeZone()` and
`zone.toJavaZoneId()`. **Do not write a converter with a UTC fallback**; a bad zone id must still
fail rather than silently become UTC. `DeviceCalendarSync` genuinely needs the Java type for the
Android CalendarProvider, so it converts at its own boundary and keeps the Java one internally.

### The decision inside it — get this signed off before you start

`signature` (line 90) is SHA-256 over length-prefixed fields and is the **daily-brief cache
key**: `BriefingRepository` compares it to decide whether a cached brief is still valid.

There is no SHA-256 in the Kotlin common stdlib. Options:

- **(a) Implement SHA-256 in `commonMain`** — ~80 lines, well-specified, testable against known
  vectors. If your bytes match the JVM's, no cache is invalidated at all.
- **(b) `expect`/`actual`** — JVM keeps `MessageDigest`, iOS uses CryptoKit. No cache change, but
  a platform seam in the middle of the engine.
- **(c) A different hash** (e.g. a common-Kotlin FNV/xxHash) — simplest, but **invalidates every
  cached brief once**. Acceptable, but it is a product decision: expect one regenerated brief per
  user at upgrade, not a bug report.

Recommendation: **(a)**, with a test asserting the digest of a known event list matches the value
the current JVM implementation produces. Capture that expected value *before* you change
anything.

`ByteBuffer` is only used to turn `Int`/`Long` into big-endian bytes (lines 350–356) — trivial to
replace with shifts, but **keep the byte order identical** or the digest changes.

### Steps

1. Capture the current signature for a fixed event list as a test fixture. This is your proof.
2. Replace the date maths with `kotlinx-datetime`, reusing `AmbiguousLocalTime.resolve` for any
   wall-clock → instant conversion. Every day-offset in this file hops via **local noon** so DST
   cannot skip or repeat a day — preserve that.
3. Replace `MessageDigest`/`ByteBuffer` per the decision above.
4. Change the `TimeZone` parameter type; fix the call sites listed above.
5. `git mv` to `commonMain`; compile; run the gate.

### Acceptance

- `ScheduleAnalysisTest` (23 cases) passes **unchanged** — including the DST cases
  `moving into a spring DST gap normalizes forward on the target day` and
  `moving all-day events preserves their calendar span across DST`.
- The signature fixture test passes (or, under option (c), the change is documented and approved).
- `scripts/verify.sh` green; `scripts/verify.sh --device` green.

### Traps

- `ScheduleAnalysis.startOfDay` is called *without* a zone argument in `GanttZoom`, so it picks
  up the system default. That ambient dependency is fine today and must stay behaviourally
  identical.
- All-day normalisation (`normalizeAllDayUtcRange`) is subtle and heavily tested. Do not
  "simplify" it.

---

## WP-2 — Move the six files `ScheduleAnalysis` gates  ✅ done (`34f4385`)

**Size:** medium. **Precondition: WP-1 merged.**

| File | Lines | Own blocker to clear |
| --- | ---: | --- |
| `GanttLayout` | 287 | `BigDecimal`, `BigInteger`, `MathContext`, `RoundingMode` — plus `Calendar`/`TimeZone` |
| `PlanGanttLayout` | 154 | `Math.subtractExact` → `GuardedArithmetic` |
| `TimelineLayout` | 131 | `Calendar`, `TimeZone` |
| `DayPulse` | 122 | `TimeZone` |
| `GanttZoom` | 98 | nothing of its own |
| `PortfolioGantt` | 69 | nothing of its own |

Do them in that order; the last two are free once `GanttLayout` lands.

**`GanttLayout`'s `BigDecimal`/`BigInteger` are not decoration.** They keep `positionOf`/`timeAt`
stable near `Long` limits, and `GanttLayoutTest` has a case named
`time conversion clamps overscroll and remains stable near long limits`. Kotlin common has no
`BigDecimal`. Either carry enough precision with `Long`/`Double` and prove that test still
passes, or `expect`/`actual` it. Do not drop the precision and assume nobody scrolls that far.

**Acceptance:** all existing tests unchanged and green; `GanttLayoutTest`,
`TimelineLayoutTest` and `DayPulseTest` DST cases explicitly verified as *run*, not just absent.

---

## WP-3 — Free `GanttInteraction` from `java.io.Serializable`  ✅ done (`3b5af10`)

**Size:** small, self-contained. No preconditions.

`GanttBlockDraft` implements `java.io.Serializable` purely for Android state restoration.
Compose saves it via `rememberSaveable`.

Replace the interface with a Compose `Saver` in `:app` (there are existing custom `Saver`s in
`DailyBriefApp.kt` — `NullableEventDraftSaver`, `NullablePlanItemDraftSaver` — copy that shape).
Then move `GanttInteraction.kt` (108 lines) to `commonMain`.

**Acceptance:** `GanttInteractionTest` unchanged and green; the Gantt move-mode instrumented
journeys still pass (`scripts/verify.sh --device`), because that is what actually exercises
state restoration.

---

## WP-4 — `LegacyNameKeys` via `expect`/`actual`  ✅ done (`2c7ea46`)

**Size:** small in lines, **highest blast radius in the module.** Read this whole section before
touching it.

`planning-core/src/jvmShared/kotlin/com/example/data/database/LegacyNameKeys.kt` is 22 lines:

```kotlin
fun nameKey(value: String): String =
  Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

fun stableId(seed: String): String =
  UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
```

**Why this is dangerous.** `nameKey` is **stored on board, column and work-schedule rows** and
compared on Undo. `stableId` is how the v5 catalog import derives IDs that survive every later
migration. A reimplementation that differs by one character for one input breaks existing
installs — silently, and only for users who have the affected characters in a board name.

**Do not reimplement NFKC.** Use `expect`/`actual`: JVM keeps `Normalizer`; iOS uses
`precomposedStringWithCompatibilityMapping`. `UUID.nameUUIDFromBytes` is MD5-based (RFC 4122 v3)
and can be implemented in common, but only against test vectors.

**Steps**
1. **First**, add a fixture test asserting known input → output pairs for both functions,
   including: plain ASCII, mixed case, leading/trailing space, a full-width character (`Ｗｏｒｋ`
   vs `Work` — `LegacyPlanCatalogBuilderTest` already relies on these colliding), an accented
   character in both composed and decomposed forms, and an emoji. Capture the values from the
   **current JVM implementation** and commit that test before changing anything.
2. Then introduce the `expect`/`actual` split, keeping the JVM `actual` byte-identical.
3. Move the `expect` declaration to `commonMain`.

**Acceptance:** the fixture test passes against both the old and new implementation;
`LegacyPlanCatalogBuilderTest`, `WorkingCalendarMapperTest` and `PlanMigrationInstrumentedTest`
all green — the last one on a device, because it is the one that proves existing databases still
migrate.

---

## WP-5 — The journal codecs, as one unit  ✅ done (`0bbdf42`)

**Size:** large. **Preconditions: WP-4 and WP-6 merged.**

`PlanMutationCodec` (808), `PlanCatalogMutationCodec` (359) and `WorkingScheduleMutationCodec`
(363) implement one sealed `PlanMutationState` hierarchy. **A sealed interface cannot be extended
from another source set**, so all three move together or none do. This has already been attempted
and refused by the compiler; do not re-litigate it file by file.

They are otherwise pure string handling and should move without behaviour change once
`LegacyNameKeys` and `WorkingCalendarMapper` are out of the way.

**Acceptance:** `PlanMutationCodecTest`, `SavedViewCodecTest`,
`WorkingScheduleMutationCodecTest`, `PlanItemScheduleMutationCodecTest` unchanged and green, plus
the journal instrumented tests on a device (`PlanMutationJournalInstrumentedTest`,
`PlanCatalogJournalInstrumentedTest`) — Undo is compare-and-set and the encoding is what it
compares.

---

## WP-6 — `WorkingCalendarMapper` off `Calendar`  ✅ done (`72e8db6`)

**Size:** small. **Precondition: none** (it can precede WP-4, but WP-5 needs both).

239 lines, `java.util.Calendar` only. It is the strict Room ↔ `WorkingCalendarSpec` converter and
validates weekday numbers. Remember the stored convention: **Sunday is 1** (`Calendar`
numbering), not ISO. `WorkingCalendar.calendarDayOfWeek()` already shows the translation.

**Acceptance:** `WorkingCalendarMapperTest` (282 lines) unchanged and green.

---

## WP-7 — Compiler-enforced `commonMain` purity  ✅ done

**Size:** medium, mostly waiting on a download. **Do last in Stage 1.** Done; the acceptance
was verified by planting `import java.util.Calendar` in `GuardedArithmetic.kt` — the native
compile rejects it.

The one surprise: `room-common` 2.8.4 does publish a `linuxX64` variant, so `linuxX64()`
survives where the plan feared it would not — but the first real compile then caught what the
source-scan test had sailed past for months. `commonMain` held JVM-only stdlib extensions that
are not imports, so no scan rule saw them: `toSortedMap`/`toSortedSet` (CriticalPathEngine,
WorkingScheduleMutationCodec, MultiSchedulePlanHealth, SavedViewCodec), `@JvmOverloads`
(TimelineLayout), `String.format` (SavedViewCodec) and `java.lang.System.currentTimeMillis`
(DailyBriefing). All were replaced with portable equivalents, and `CommonMainPurityTest` now
scans for the whole class. `LegacyNameKeys` needed a native `actual`; it throws on linuxX64
rather than approximate NFKC, because this target exists to compile the engine, not run it —
Android and the JVM server both resolve the byte-identical JVM actual, and the fixture test
pins the split.

The gate now runs `:planning-core:compileCommonMainKotlinMetadata` (it was not reachable from
jvmTest, so CI would never have fired the guard). The ~1.8 GB toolchain in `~/.konan` needs one
`verify.sh --online` run; `--offline` works afterwards. `CommonMainPurityTest`'s first test is
kept as the fast duplicate with the better error message, per the plan.

---

## WP-8 — Remove the `NotionClient` bridge  ✅ done (`8a618ce`)

**Size:** trivial. **Precondition: WP-1 merged.**

`app/src/main/java/com/example/data/api/NotionClient.kt` line ~76 holds a transitional
`commonZone` conversion introduced because `IsoDates` moved before `ScheduleAnalysis`. Once both
speak `kotlinx-datetime`, delete the bridge and the comment above it.

---

## WP-9 — The three open engine defects  ✅ done

All three done; defect 3 confirmed as wanted before building (`4aac835`, `df1ef68`).

**Defect 3 — only `FINISH_TO_START` dependencies are scheduled around.**
`AutoPlan` emits an explicit `UnplacedTask` for SS/FF/SF saying it does not schedule around them.
`CriticalPathEngine` already models all four via generalized precedence, so the maths exists; the
planner needs to use it. Consultants with client hand-offs hit this immediately.

**Defect 5 — `AutoPlan` is O(tasks × chunks × free × taken).** Fine for one week on one device.
The V1 architecture runs the planner server-side for many tenants over longer horizons. Add a
benchmark first, then optimise against it. `AutoPlanBenchmarkTest` defines the shape (the doc's
"`BaselineVarianceTest`'s perf case" does not exist): 800 tasks / 4 weeks, Mon-Fri 9-17, buffer;
~11× over capacity by construction, which is the point — it measures search cost, not placement.
It measured 66 ms, so no optimisation was needed; the tripwire is the deliverable.

**Defect 7 — coverage is inverted against product value.** `AutoPlan` is the feature the SaaS is
sold on. Grow its cases toward `MultiSchedulePlanHealth`'s 17. Done: 17 (was 5), adding hard and
soft deadlines, preferred order, remainder reporting, the sub-floor rounding contract, start
constraints, milestone exclusion, and empty ranges.

---

# Stage 2 — domain model and server contract

Design-led; read `03-domain-and-architecture.md` and `04-planner-api-contract.md` first. These
are briefs, not recipes.

## WP-10 — Multi-tenant Postgres schema  ✅ done (`2c65d34`, `da4ad63`)

`PlanBoard` → `Project` (a client engagement), plus **`Client`, which the current schema lacks
entirely** and the consultant ICP requires. Then `PlanColumn` → `WorkflowStage`, `PlanItem` →
`Task`, `PlanBlock` → `ScheduledBlock`, `BriefingEvent` → `ExternalEvent`, `PlanMutation` →
`AuditEntry`, `WorkSchedule` unchanged, plus `User`, `Workspace`, `Membership`,
`CalendarConnection`, `PlanRun`, `PlanProposal`, `Subscription`.

**Every row carries `workspace_id` from day one**, even though V1 is single-user. Retrofitting
tenancy is the six-month rewrite this exists to avoid. (The brief said `tenant_id`; the domain
doc said `workspace_id`. They are the same thing — the workspace IS the tenant — and the column
is named for the entity, documented in `db/schema.sql`.)

Two constraints currently enforced only in application code must become real database
constraints: `saved_views` unique on `(boardId, surface, nameKey)`, and `work_schedules`
"exactly one non-archived default" as a partial unique index.

Source the exact column types from `app/schemas/com.example.data.database.AppDatabase/9.json` —
it is the authoritative current schema. Both constraints, the per-workspace re-scoping of
Room's global unique indexes, and the composite FKs are proven against a real Postgres 16 by
`db/verify-schema.sh` — four refusals must appear or the script exits non-zero.

## WP-11 — The four invariants that need redesign, not translation  ✅ done (`f42b1e4`)

Design in `docs/saas/09-server-invariants.md`; the four code sites carry `REDESIGN` markers.

1. **`SCHEDULE_MUTEX`** is a process-wide `Mutex` holding provider I/O *inside* the lock. Server
   form: per-tenant advisory lock (`pg_advisory_xact_lock` over the workspace id) with the
   network fetch **outside** it. Preserve the property — reconciliation of a source's rows and a
   concurrent user edit must not interleave. The merge policy running at write time is *more*
   correct than the device's fetch-start merge; `lock_timeout` bounds the wait.
2. **Undo's staleness check** is whole-row value equality inside one SQLite transaction on a
   single-writer database. Postgres form chosen: claim-the-entry `UPDATE … RETURNING` +
   `SELECT … FOR UPDATE` on the affected entities in id order + the same
   `matchesMutationState` comparison verbatim. `SERIALIZABLE` rejected: same guarantee, less
   concurrency, retry loop; the one un-lockable predicate is already a partial unique index.
3. **`SecretStore`** → KMS envelope encryption. Keep its design properties: AAD bound to the
   setting key, never destroy ciphertext on a failed read. Data key per write, AAD extended to
   workspace + key, `SECRET_SETTING_KEYS` routing carries over.
4. **Gemini keys** move from per-user BYOK to a platform key with per-tenant quota. The browser
   never receives a long-lived secret. Limits in `subscriptions.quota_json`; a monthly
   `gemini_usage` ledger (lands with its consumer, WP-13); reserve-then-refuse. The device
   keeps its local BYOK path.

## WP-12 — Freeze the planner API  ✅ done (`b229dfd`)

`04-planner-api-contract.md` is **FROZEN at v1**. The `:planning-contract` module (new, plain
JVM, kotlinx-serialization-json 1.8.1) turns it into executable bytes:

- Wire DTOs for `PlanningRequest` (+ Task/ScheduledBlock/Interval/Dependency/WorkSchedule/
  WorkScheduleWindow), `PlanningResult` (+ Proposal/Unplaced/Health/Conflict/ExternalEvent).
- Every top-level message carries `v`; `PlannerApi.checkVersion` refuses anything newer with
  `ApiVersionNotSupported` before fields are read; unknown fields are ignored (forward
  compat), missing fields default (backward compat).
- Golden files `request-v1.json` / `result-v1.json` — a re-encode must be byte-identical, so
  a rename/reorder/default change fails loudly instead of silently renegotiating the wire.
- Contract rules as code: `refusalReason()` for the range contract ("end must be > start;
  otherwise a refusal, never a plan"), whole-minute + forward-span `init` checks on
  proposals, all-day as carried state, string enums with the device's stored spellings.
- 19 tests across golden / version / round-trip suites; `:planning-contract:test` joined the
  verify.sh gate explicitly (a plain JVM module has no app-style task name to inherit).

---

# Stage 3 and beyond

## WP-13 — The vertical slice  (in progress)

Progress (commit `f9f0d24`): `:server` module complete — Ktor service, Flyway V1
from `db/schema.sql`, `SyncMergePolicy` in `planning-core` (commit `e3fe951`), sessions,
tasks, reconcile, plan/apply/reject/undo/today, `Envelope` dev-key wrapping, all unit
tests green and wired into `verify.sh`. Automated acceptance `scripts/journey.sh` passes
in ~3 s against docker Postgres + fixture provider. Remaining: the `web/` client and the
real-Google runbook, then the gate runs the journey.

The journey, nothing else: **sign in → connect a calendar → add tasks → "Plan my week" → see a
proposal with reasons → apply → see it on Today.** One user, one Google account, one weekend of
schedule. The 90-second magic moment is the acceptance test, literally: signup to an
accepted-or-rejected proposal, on a real Google account, in 90 seconds. Over three minutes is a
bug in this stage.

**Exit criteria:** the loop works end to end; a rejected proposal writes nothing; Undo restores
exactly; the journey runs headless against a fixture provider in CI (gate) and is walked
manually against a real Google account (runbook).

### Shape

- **`:server`** — new Gradle module, plain JVM: Ktor (Netty) + Postgres JDBC + Flyway
  migrations (the proven `db/schema.sql` is V1) + kotlinx-serialization. Depends on
  `:planning-core` (jvm) and `:planning-contract`. The engine's Room-annotated models are inert
  JVM data classes, usable directly; the service maps contract DTOs ⇄ engine types and never
  reimplements a decision the engine owns.
- **`web/`** — Next.js + TypeScript, two screens only: Planner (tasks, Plan my week, the
  proposal with reasons, apply/undo) and Today (overlaps + day shape). No Projects, no Board,
  no Gantt. The client renders; the server is authoritative for Apply.
- **Google OAuth** — one consent covers identity + calendar scopes. Server-side tokens, stored
  in `calendar_connections.token_ciphertext` in the WP-11 envelope shape. There is no KMS in
  the slice: a dev key from config fills the envelope's place so the production swap is
  configuration, not shape (documented deviation; KMS is a deployment concern).
- **Reconciliation worker** — per-tenant advisory lock, provider fetch *outside* the lock
  (invariant 1), `SyncMergePolicy` applied, merged rows written. WP-14's sync worker is
  consumed by this slice; what remains there is hardening and ops (retries, backoff, metrics,
  partial failure handling).
- **Planner endpoint** — `POST /v1/plan`: wire request → engine → wire result. Determinism
  cache by request hash + `data_version` (04). **Apply** — `POST /v1/plan/{id}/apply`: the
  proposal becomes blocks through the journal (one `AuditEntry`, compare-and-set undo per
  invariant 2). A rejection writes nothing. Undo restores exactly.
- **Today** — `ScheduleAnalysis` (already in `jvmShared`) over external events + plan blocks:
  conflicts, day shape. No Gemini in the slice; summaries are WP-15.

### Porting that this package needs

- **`SyncMergePolicy` moves into `planning-core`** (jvmShared). It is pure today except
  `DeviceCalendarSync.providerEventId` (the `device_<id>_<begin>` convention). The policy takes
  a `providerIdOf: (BriefingEvent) -> Long?` parameter; the app passes its device extractor,
  the server passes its Google one. The policy's own test suite moves with it and runs in both
  places — the rules are the same code, never a re-implementation.
- Everything else the worker touches (`ScheduleAnalysis`, codecs, models) is already in
  `planning-core`.

### Acceptance

1. **Automated journey (gate)**: docker Postgres + fixture provider, drive the HTTP API
   through the whole loop — signup, connect, tasks, plan, proposal with reasons, apply,
   Today shows the blocks, reject-a-proposal writes nothing, undo restores. Timed: the service
   leg must not be where the 90 seconds die.
2. **Manual runbook**: the same journey against a real Google account, with the 90-second
   stopwatch and a table of where time goes.

### Out of scope (later WPs)

Notion on the server, Gemini summaries, alarms/push, billing, scale-out beyond one node, KMS
proper, Expo mobile (WP-16).
- **WP-14 Google Calendar sync worker** — OAuth server-side, tokens encrypted at rest,
  `SyncMergePolicy`'s rules ported exactly: times and location are source-owned, wording and
  placement are user-owned, `userEdited` is sticky.
- **WP-15 depth and paid tier** — Capacity first (it answers "can I take another client?", the
  ICP's actual question), then Projects/Board/Timeline, then dependencies and critical path,
  then scenarios/baselines/portfolio, then Stripe.
- **WP-16 mobile** — Expo + React Native UI, `planning-core` via KMP. **Local engine previews;
  the server is authoritative for Apply.**

---

## Reporting back

For each package, the commit message should say what changed, what proves it, and anything you
found that the documents got wrong — that last part matters most. Four of the seven entries in
`06-scope-deviations.md` were cases where a document was confidently wrong and the tree was
right. If you find another, fix the document in the same commit and say so.
