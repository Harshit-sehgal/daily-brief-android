# Daily Brief → SaaS: programme plan and status

Living document. Status as of 2026-08-17, branch `saas-extraction`.

Records what was decided, what has been done and proved, what was found along the way that
changed the plan, and what is left. Where a claim is made, the evidence for it is named.

---

## 1. What is being built, and for whom

**Thesis.** Daily Brief becomes an intelligent planning system for independent consultants
managing multiple client commitments. It turns deadlines, estimated work and calendar
availability into a realistic execution plan, then continuously shows whether those commitments
are still achievable.

**Positioning under test:** *Know what you can commit to before you commit to it.*

### Locked decisions

| Decision | Choice | Consequence |
| --- | --- | --- |
| **Wedge** | Hybrid | Auto-plan + Today is the free 90-second magic moment. Project depth — Gantt, dependencies, capacity, critical path, plan health, baselines, scenarios — is Pro. Neither half is deleted. |
| **ICP** | Independent consultants and high-end freelancers running 3–8 concurrent client projects against a meeting-heavy calendar | Capacity analytics and dependencies answer commercial questions ("can I take another client next week?"), not productivity-theatre ones. Expansion path: consultant → consultant + subcontractor → small consultancy → team capacity. |
| **Out of scope forever** | Invoicing, CRM, expenses, proposals, contracts, time sheets | The boundary is *commitment → realistic execution plan*. Other software handles getting paid. |
| **Engine** | Kotlin Multiplatform `planning-core` | One engine shared by a JVM planning service, Android, and later iOS. No TypeScript rewrite of the scheduling maths. |
| **Stack** | Next.js web · TypeScript SaaS API · Kotlin/Ktor planner · Postgres · Expo mobile with native Kotlin/Swift modules | Language ownership is clean: product UI in TypeScript, planning intelligence in Kotlin. |
| **Scope of the current step** | Specification + engine extraction | No Next.js app, auth, Stripe, deployed Postgres, Ktor service, Expo, iOS, or UI implementation. |

### Tier boundary (draft, to be firmed in `02-v1-product-spec.md`)

| Trial / entry | Pro |
| --- | --- |
| Calendar connection | Unlimited projects |
| 1–2 active projects | Full auto-planning |
| Today | Multi-project capacity |
| Basic weekly planner | Dependencies, timeline/Gantt |
| Limited auto-plan | Deadline risk, Plan Health |
| Basic conflicts | Scenarios, baselines, advanced AI replanning |

Price to test: $19–29/month. Not locked. Market anchors: Motion $19/seat, Akiflow $34/$19
annualised, Sunsama $25/$20 annualised.

Never paywalled, carried over from `HCI_REDESIGN_PLAN.md` §10: accessibility alternatives,
readable text, source/ownership warnings, error recovery, basic Undo, reaching core views, and
a machine-readable export floor.

---

## 2. Status

### Done and proved

**Phase 0 — the existing work is committed.** `fa89f56`, 203 files, 54,850 insertions.

Eight days of work sat in the working tree unrecorded: 127 untracked files including the entire
planning engine, plus 37 modified, on a history whose last commit was 2026-08-09. The extraction
moves files between modules, so this had to land first or a mistake would have been
unrecoverable. `scripts/verify.sh` was green before and after.

**Phase 1 — the engine is its own module.** `3d05da8`, 60 files changed, of which **47 are pure
renames**.

- `:planning-core` created as a Kotlin Multiplatform module (`jvm()` + `androidLibrary`).
- Moved: the 23 Android-free files of `core/`, the domain model they operate on, and 22 test
  files. **Package names preserved**, so none of the 53 files in `:app` referencing those types
  needed an import change.
- `:app` now depends on `:planning-core`. Room's KSP resolves the entities from the module —
  verified with a forced `--rerun-tasks` rebuild, not merely an up-to-date check.
- `scripts/verify.sh` and CI now name `:planning-core:jvmTest` explicitly. An unqualified task
  name would have skipped the engine suite silently, because a KMP module has `jvmTest`, not
  `testDebugUnitTest`.

**Phase 2 — first `commonMain` migration.** *Complete but uncommitted.*

- `androidx.room:room-common` turns out to be **fully multiplatform** — it publishes iOS, wasm,
  JS and JVM variants. The domain model therefore keeps its `@Entity` metadata *and* lives in
  `commonMain`. This removed the model as a blocker for every downstream file, which was the
  single biggest unlock available.
- Moved to `commonMain`: the domain model plus `Fuzzy`, `GanttDependencyPaths`, `MiniMarkdown`,
  `BaselineVariance`, `PlanExport`, `PortfolioRollup`, `WeeklyReview`.
- `CommonMainPurityTest` added — see §3 for why it exists instead of a compiler check.

### Current shape

| | Files | Lines | Share |
| --- | ---: | ---: | ---: |
| `planning-core/commonMain` | 9 | 1,233 | **22%** |
| `planning-core/jvmShared` | 16 | 4,410 | 78% |
| `planning-core` tests | 23 | — | 192 tests, 0 failures |
| `app/src/main` | 67 | 31,747 | — |
| `app` tests | 41 suites | — | 188 tests, 0 failures |

### Not started

Phase 3 (engine contract tests) and Phase 4 (the five specification documents).

---

## 3. Findings that changed the plan

Recorded because each one invalidates something a reasonable person would otherwise assume.

**The engine was never `java.time`-based.** All date maths is `java.util.Calendar`,
`TimeZone` and `SimpleDateFormat` across 8 files. These are JVM-only and cannot enter
`commonMain`. More importantly, `WorkingCalendarTest` **pins `Calendar`'s DST tie-break**: a
fall-back ambiguous wall time resolves to the *later, standard-time* occurrence.
`kotlinx-datetime` and `java.time` resolve fall-back to the *earlier* occurrence. A naive port
silently flips a tested behavioural contract. See §5.

**`commonMain` purity is not enforced by the compiler here, and it fails open.**
`compileCommonMainKotlinMetadata` exists but is **SKIPPED**: Kotlin only produces a metadata
compilation once some target needs one, and `jvm` plus `androidTarget` are both JVM-family.
Verified by planting a file importing `java.util.Calendar` in `commonMain` — the build stayed
green and said nothing. An earlier commit message in this branch (`3d05da8`) claims the
compiler catches this; **that claim is wrong**. Enforcement now comes from
`CommonMainPurityTest`, which reads the sources the way `UiConsistencyTest` reads the UI, and
which was itself verified by planting a violation and watching it fail with a precise message.
Adding a non-JVM target — Kotlin/Native is roughly a gigabyte and is not in the pinned
toolchain — would hand the job back to the compiler.

**Every module must compile against SDK 37.1, never bare 37.** The pinned SDK carries
`platforms;android-37.1` only. `compileSdk = 37` asks for 37.0 and sends Gradle to the network,
and because the download reports no task progress the build looks hung rather than failed — it
sat for thirteen minutes before this was spotted. Both modules now write
`compileSdk { version = release(37) { minorApiLevel = 1 } }`.

**Kotlin will not smart-cast a `val` it does not own.** With the model in another module,
`if (row.dayOfWeek != null) use(row.dayOfWeek)` no longer compiles in `:app`. Six sites now bind
a local first. Expect this on every nullable model field the app reads.

**The journal codecs look portable and are not.** `PlanMutationCodec` reaches
`WorkingScheduleMutationCodec`, which reaches `LegacyPlanCatalogBuilder` inside the Room
migration file. They were moved, found to break the build, and moved back. Untangling that chain
is a content change and is queued as Phase 5 work.

**One online Gradle run was unavoidable, twice.** The `org.jetbrains.kotlin.multiplatform`
plugin marker and the `-metadata` artifact variants of `room-common`, `androidx.annotation` and
`kotlin-stdlib` were not in the offline cache. Both are now cached; `--offline` works again.

**AGP 9 rejects `com.android.library` alongside the multiplatform plugin.** The replacement is
`com.android.kotlin.multiplatform.library`, whose DSL differs (`androidLibrary { }` inside
`kotlin { }`, and no `compileSdkMinor` property).

---

## 4. The KMP portability audit

Measured, not estimated. `core/` is one package, so intra-package references are invisible to
import analysis; this is computed from a reference graph over declared symbols, then closed
transitively.

### Already portable — `commonMain`, 1,233 lines

Domain model (`PlanningModels`, `BriefingEvent`), `Fuzzy`, `GanttDependencyPaths`,
`MiniMarkdown`, `BaselineVariance`, `PlanExport`, `PortfolioRollup`, `WeeklyReview`.

### Still in `jvmShared`, 4,410 lines

| File | Lines | Own JVM dependency | Also blocked via |
| --- | ---: | --- | --- |
| `CriticalPathEngine` | 716 | `PriorityQueue`; `Math.addExact/subtractExact` | — |
| `ScheduleAnalysis` | 363 | `ByteBuffer`, `MessageDigest`, `Calendar`, `TimeZone` | — |
| `WorkingCalendar` | 309 | `ParsePosition`, `SimpleDateFormat`, `Calendar`, `Locale`, `TimeZone` | — |
| `GanttInteraction` | 297 | `Serializable`; `Math.*Exact` | `WorkingCalendar` |
| `GanttLayout` | 287 | `BigDecimal`, `BigInteger`, `MathContext`, `RoundingMode`, `Calendar`, `TimeZone` | `ScheduleAnalysis` |
| `DependencyAnalysis` | 257 | **`Math.*Exact` only** | — |
| `AutoPlan` | 246 | `Math.floorMod` | `WorkingCalendar` |
| `PlanBlockPreview` | 237 | `Math.floorMod` | `DependencyAnalysis`, `WorkingCalendar` |
| `PlanGanttLayout` | 154 | `Math.subtractExact` | `GanttLayout`, `ScheduleAnalysis` |
| `TimelineLayout` | 131 | `Calendar`, `TimeZone` | `ScheduleAnalysis` |
| `DayPulse` | 122 | `TimeZone` | `ScheduleAnalysis` |
| `IsoDates` | 79 | `ParsePosition`, `SimpleDateFormat`, `Date`, `Locale`, `TimeZone` | — |
| `MultiSchedulePlanHealth` | 608 | — | `DependencyAnalysis`, `WorkingCalendar` |
| `PlanHealth` | 349 | — | `DependencyAnalysis`, `WorkingCalendar` |
| `PlanScenarios` | 157 | — | `AutoPlan`, `WorkingCalendar` |
| `GanttZoom` | 98 | — | `ScheduleAnalysis` |

### Classification

- 🟡 **Trivial — arithmetic only.** `DependencyAnalysis` (257) is blocked by nothing but
  `Math.addExact/multiplyExact/subtractExact`, at two call sites. `Math.floorMod` → `a.mod(b)`.
  `Math.addExact` → a guarded helper in `commonMain` using the standard overflow check; the
  fail-closed behaviour is already pinned by tests, so a correct helper keeps them green.
- 🟠 **Mechanical but real.** `CriticalPathEngine`'s `PriorityQueue` — determinism depends on it
  being a lexicographic min-heap of strings, so the replacement must reproduce that exactly.
  `GanttLayout`'s `BigDecimal`/`BigInteger` exist to keep positions stable near `Long` limits.
  `ScheduleAnalysis.signature` needs a common SHA-256 (or the brief cache key changes, which
  invalidates every cached brief once).
- 🔴 **A genuine project — the date-time cluster.** `WorkingCalendar`, `ScheduleAnalysis`,
  `IsoDates`, `TimelineLayout`, `DayPulse`, `GanttLayout` — 1,291 lines, carrying a behavioural
  decision (§5) rather than a mechanical substitution.

### Leverage

Five files are *root* blockers: `WorkingCalendar`, `ScheduleAnalysis`, `CriticalPathEngine`,
`DependencyAnalysis`, `IsoDates`. Clearing `WorkingCalendar` alone unblocks `AutoPlan`,
`GanttInteraction`, `PlanBlockPreview`, `MultiSchedulePlanHealth`, `PlanHealth` and
`PlanScenarios` — 1,894 lines downstream. Clearing `ScheduleAnalysis` unblocks `GanttLayout`,
`TimelineLayout`, `DayPulse`, `GanttZoom` and `PlanGanttLayout` — 792 lines.

**Fix the date-time cluster plus `PriorityQueue`, and essentially the whole engine becomes
`commonMain`.** That is a far more actionable framing than "5,142 lines need porting".

---

## 5. The DST decision that has to be made deliberately

`WorkingCalendarTest` pins two behaviours that come from `java.util.Calendar` rather than from
anything the product chose:

- a spring-forward gap normalises **forward** to the next valid wall-clock minute;
- a fall-back ambiguous wall time resolves to the **later, standard-time** occurrence.

`kotlinx-datetime` and `java.time` resolve fall-back to the **earlier** occurrence by default.
Porting naively flips a tested contract.

**Recommendation:** implement the policy as named code — an explicit
`AmbiguousLocalTime.LATER_OFFSET` — preserving today's behaviour and leaving the existing tests
untouched. The choice becomes visible and reviewable rather than an inherited library default.
This matters more on a server than on a phone, because the server resolves many time zones at
once and a silent flip would move real working windows for real users.

---

## 6. Engine defects found — recorded, not yet fixed

Behaviour preservation is the completion criterion for the extraction, so none of these were
changed. They are the first candidates once the boundary is trusted.

1. **`AutoPlan` does not treat a deadline as a constraint.** It sorts by `dueAt` and appends
   *"ahead of its due date"* to its reason string, but there is **no check that a proposal ends
   before `item.dueAt`**, and no test asserts one. `WorkingCalendar.propose` *does* accept
   `dueAt` and clamp to it; `PlanHealth` detects the risk only after the fact. **A product
   positioned on "know what you can commit to before you commit to it" cannot ship this.** This
   is the top of the backlog.
2. **`AutoPlan` ignores `item.startConstraint`.** It is read by `PlanHealth` and
   `MultiSchedulePlanHealth` for risk proving, never by the planner.
3. **Only `FINISH_TO_START` dependencies are scheduled around.** SS/FF/SF produce an explicit
   `UnplacedTask` naming the limitation — disclosed, not silently dropped, which is the right
   failure mode. Consultants with client hand-offs will want the other three.
4. **All-day events become full-day hard blocks.** The ViewModel feeds every calendar row into
   `fixedCommitments` without filtering; `ScheduleAnalysis.findConflicts` deliberately excludes
   all-day entries. An all-day marker currently destroys a day of capacity.
5. **`AutoPlan` is O(tasks × chunks × free × taken).** Fine for one week on one device; a
   concern for a multi-tenant server planning 8 projects over 4 weeks.
6. **Two buffer code paths.** `AutoPlan` applies `bufferMinutes` itself then calls
   `workingIntervals`; `PlanHealth` calls `freeIntervals`, which applies the buffer internally.
   Same net effect, two implementations.
7. **Test coverage is inverted against product value.** `AutoPlan` — the feature the SaaS is
   sold on — has 5 tests for 246 lines. `MultiSchedulePlanHealth`, an assessment rather than a
   scheduler, has 17.

### Worth knowing: the crown jewel

`MultiSchedulePlanHealth` (608 lines, 17 tests) builds a **max-flow network** to combine
overlapping working schedules without double-counting a shared human hour, and refuses to call
chunk fragmentation a proven overload. For the consultant ICP — "can I take another client next
week?" — this is the most commercially valuable code in the repository.

---

## 7. Remaining work

### Phase 2 — finish and commit *(next)*

- [ ] Add a guarded-arithmetic helper to `commonMain`; move `DependencyAnalysis` (+257 lines →
      ~26% portable).
- [ ] Write `docs/saas/05-kmp-portability-audit.md` from §4 above.
- [ ] Run `scripts/verify.sh`; commit, including the §3 correction to the `3d05da8` claim.

### Phase 3 — engine contract tests

Canonical scenarios that pin *current* behaviour so future Android / iOS / server divergence is
caught rather than discovered. Coverage: working calendars (including both DST edges),
dependencies across all four types, critical path and slack, deadline feasibility,
multi-schedule capacity, plan health, conflict handling, auto-plan determinism.

Includes a **characterisation test for defect 1** that documents the missing deadline
constraint rather than asserting the behaviour is correct.

### Phase 4 — the specification set

| File | Contents |
| --- | --- |
| `01-product-teardown.md` | Every existing capability → KEEP / REDESIGN / MERGE / LATER / DELETE, anchored to file paths, filtered by the consultant ICP |
| `02-v1-product-spec.md` | IA (Today · Planner · Inbox · Projects → Tasks/Board/Timeline · Capacity · Integrations · Settings), progressive-disclosure rules, onboarding and the 90-second magic moment, the replan loop, tier boundary, explicit "not in V1" list |
| `03-domain-and-architecture.md` | Multi-tenant Postgres model, Room→Postgres mapping, service architecture, the four invariants below |
| `04-planner-api-contract.md` | `PlanningRequest` / `PlanningResult` / `PlanProposal` / `PlanConflict` / `PlanHealth`, derived from the real signatures |
| `05-kmp-portability-audit.md` | §4 above, kept current |

Published as an Artifact at the end so the set has a shareable link.

**Domain model.** `PlanBoard` → `Project` (a client engagement). **`Client` is a new entity the
current schema lacks entirely** and the ICP requires. Then `PlanColumn` → `WorkflowStage`,
`PlanItem` → `Task`, `PlanBlock` → `ScheduledBlock`, `BriefingEvent` → `ExternalEvent`,
`WorkSchedule` unchanged (the multi-schedule max-flow becomes "client A hours vs internal
hours"), `PlanMutation` → `AuditEntry`, plus `User`, `Workspace`, `Membership`,
`CalendarConnection`, `PlanRun`, `PlanProposal`, `Subscription`.

**Four invariants that need redesign, not translation.**

- `BriefingRepository.SCHEDULE_MUTEX` is a **process-wide** `Mutex` holding provider I/O inside
  the lock. Server-side: a per-tenant advisory lock with the fetch pulled *outside* it — you
  cannot hold a DB lock across an outbound HTTP call at scale. Property to preserve:
  reconciliation of a source's rows and a concurrent user edit must not interleave.
- Undo's staleness check (`matchesMutationState`) is whole-row value equality inside one SQLite
  transaction on a single-writer database. Postgres needs `SERIALIZABLE` or `SELECT … FOR
  UPDATE` on the journal row plus the affected entities. The codecs themselves are fully
  portable once untangled from the migration file.
- `SecretStore` is Android Keystore — replaced by KMS/envelope encryption. Keep its design
  properties: AAD bound to the setting key, never destroy ciphertext on a failed read.
- Two constraints live only in application code and should become real Postgres constraints:
  `saved_views` uniqueness on `(boardId, surface, nameKey)` (no index today), and
  `work_schedules` "exactly one non-archived default" (a partial unique index).

**Security change for the SaaS.** Today users paste their own Gemini key, encrypted on-device.
A commercial SaaS uses a platform key with per-tenant quota and server-side OAuth tokens; the
browser never receives a long-lived secret. This changes `activeGeminiKey()`,
`parseGeminiKeys`/`encodeGeminiKeys` and the whole Gemini settings surface.

### Phase 5 — the portability project

- [ ] Untangle `PlanMutationCodec` → `WorkingScheduleMutationCodec` →
      `LegacyPlanCatalogBuilder` by splitting the pure builder out of the Room migration file,
      then move the journal codecs and their 3 test files to `planning-core`.
- [ ] Replace `PriorityQueue` in `CriticalPathEngine` with a deterministic common-Kotlin heap.
- [ ] The date-time cluster, with the §5 decision made explicitly.
- [ ] Split `GanttInteraction`: `GanttBlockEditPolicy` and `GanttWorkingBands` are domain;
      `GanttDirectManipulationPolicy` and `GanttDirectManipulationTargets` are px/dp touch
      geometry and belong back in `:app`.
- [ ] Optional: add a Kotlin/Native or wasm target so the compiler enforces `commonMain`, and
      `CommonMainPurityTest` becomes a fast duplicate rather than the only guard.

### Phase 6 — engine defects

Fix defect 1 (deadline as a hard constraint) first, with tests, as an explicitly approved
behaviour change. Then 2, 4, 6.

### Phase 7 — the vertical slice

Only after the domain model and engine boundary are frozen: login → connect calendar → create
tasks → "Plan my week" → valid schedule → apply. Then the AI assistant (interprets and explains;
deterministic code validates and commits), then billing, email, analytics, observability.

---

## 8. Verification

```bash
scripts/verify.sh --fast        # :planning-core:jvmTest + app unit tests
scripts/verify.sh               # the whole gate: both suites, both lints, both APKs, R8
scripts/verify.sh --device      # ...plus instrumentation on the dailybrief AVD
./gradlew :planning-core:jvmTest --offline
```

Notes for anyone repeating this work:

- The gate must name `:planning-core:jvmTest` explicitly. A KMP module has no
  `testDebugUnitTest`, so an unqualified task list skips the engine suite **without failing**.
- `compileCommonMainKotlinMetadata` is SKIPPED and proves nothing (§3). `CommonMainPurityTest`
  is the guard.
- A `BUILD SUCCESSFUL` from a piped Gradle invocation (`./gradlew … | tail`) reports the exit
  code of `tail`, not of Gradle. Read the output, not the status.
- Moving files between source sets inside one module leaves `:app` legitimately UP-TO-DATE,
  because the compiled classes are identical. Use `--rerun-tasks` when that needs proving.

## 9. Open questions for the user

1. **Deadline fix** — approve changing `AutoPlan` to treat `dueAt` as a hard constraint? It is a
   behaviour change, so it is not bundled with the extraction.
2. **Kotlin/Native target** — worth ~1 GB of toolchain to get compiler-enforced `commonMain`, or
   is the source-scanning test enough until iOS work actually starts?
3. **Monorepo reshuffle** — `apps/ services/ packages/` is deferred until the web app exists, so
   `app/` moves once rather than twice. Confirm that ordering.
