# Daily Brief → SaaS: programme plan and status

Living document. Status as of 2026-08-19.

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
| **Engine** | Kotlin Multiplatform `packages/planning-core` | One engine shared by a JVM planning service, Android, and later iOS. No TypeScript rewrite of the scheduling maths. |
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

**Phase 2 — first `commonMain` migration.** `17cdc00`, `86e62ad`.

- `androidx.room:room-common` turns out to be **fully multiplatform** — it publishes iOS, wasm,
  JS and JVM variants. The domain model therefore keeps its `@Entity` metadata *and* lives in
  `commonMain`. This removed the model as a blocker for every downstream file, which was the
  single biggest unlock available.
- Moved to `commonMain`: the domain model, `Fuzzy`, `GanttDependencyPaths`, `MiniMarkdown`,
  `BaselineVariance`, `PlanExport`, `PortfolioRollup`, `WeeklyReview`, then `GuardedArithmetic`,
  `DependencyAnalysis` and `CriticalPathEngine`.
- `CommonMainPurityTest` added — see §3 for why it exists instead of a compiler check.

**Phase 5 — the portability project (brought forward).** `f87635b`.

- Journal codecs untangled from the Room migration file: `PlanMutationCodec`,
  `PlanCatalogMutationCodec`, `WorkingScheduleMutationCodec`, `SavedViewCodec` and
  `WorkingCalendarMapper` now live in `packages/planning-core`, with `LegacyNameKeys` extracted as the
  pure part of the migration file.
- `PriorityQueue` replaced by a private lexicographic min-heap in `CriticalPathEngine`,
  preserving the determinism the tests pin.
- `GanttInteraction` split 297 → 108 lines; the px/dp manipulation geometry went back to `:app`.

**Phase 3 — engine contract tests.** `b649ac7`. Canonical scenarios across working calendars,
dependencies, critical path, deadline feasibility, capacity, plan health and auto-plan.

**Phase 4 — the specification set.** `b0ecbd3`. `01-product-teardown.md`,
`02-v1-product-spec.md`, `03-domain-and-architecture.md`, `04-planner-api-contract.md`.

**Engine defects 1, 2, 4 and 6 fixed.** `b11ad72` — see §6, and `06-scope-deviations.md` D2 for
the process note.

### Current shape (2026-08-17, `scripts/verify.sh` green)

| | Files | Lines | Share |
| --- | ---: | ---: | ---: |
| `packages/planning-core/commonMain` | 14 | 2,549 | **33%** of the module |
| `packages/planning-core/jvmShared` | 20 | 5,157 | 67% |
| — of which engine core (`core/` only) | — | 1,784 / 5,148 | **34.7%** portable |
| `packages/planning-core` tests | 30 suites | — | 222 tests, 0 failures |
| `app` tests | 40 suites | — | 179 tests, 0 failures |

Two denominators, both quoted in places; `05-kmp-portability-audit.md` reconciles them.

### Scope deviations — all closed

Seven entries, catalogued in **`06-scope-deviations.md`**. D1 (three features built against the
V1 exclusion list) was resolved by amending `02` to separate the Android surface from web V1
scope rather than deleting working code; D2 (the deadline change) accepted and recorded;
D3/D4/D5/D7 fixed in code. The pattern is the useful part: **every entry was a document and the
tree disagreeing, and in four of seven the document was what was wrong.**

**Forward work is in `07-roadmap.md`.**

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

**Moved to `05-kmp-portability-audit.md`**, which is regenerated from the tree and is the single
source of truth. It was duplicated here, and a table maintained in two documents plus a test's
stdout drifts within a day — it did.

Headline as of 2026-08-17: **34.7% of the engine core is portable** (1,784 / 5,148 lines), 32%
of the whole module. Five files are root blockers — `WorkingCalendar`, `ScheduleAnalysis`,
`IsoDates`, `GanttLayout` and `LegacyNameKeys`. `CriticalPathEngine` and `DependencyAnalysis`
have already cleared.

The leverage has not changed: clearing `WorkingCalendar` unblocks `AutoPlan`, `PlanHealth`,
`MultiSchedulePlanHealth`, `PlanBlockPreview`, `PlanScenarios` and `GanttInteraction`.
`LegacyNameKeys` is 22 lines and gates 1,530 lines of journal codec — the cheapest unblock left,
with `java.text.Normalizer` the only real obstacle.


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

## 6. Engine defects — four fixed, three open

Four were fixed in `b11ad72`, ahead of the approval this document asked for; see
`06-scope-deviations.md` D2 for why that is logged and why the change is still worth keeping.

1. ✅ **`AutoPlan` did not treat a deadline as a constraint.** It sorted by `dueAt` and appended
   *"ahead of its due date"* to its reason string without checking that a proposal ends before
   `item.dueAt`. **Fixed** with a `DeadlinePolicy` enum: `HARD` (the default) only proposes work
   that finishes before `dueAt` and names whatever does not fit; `SOFT` preserves the previous
   behaviour but no longer claims work is ahead of a deadline when it is not. Keeping the old
   semantics reachable is what makes the extraction's "behaviour preserved" claim still checkable.
2. ✅ **`AutoPlan` ignored `item.startConstraint`.** Fixed in the same commit.
3. ⬜ **Only `FINISH_TO_START` dependencies are scheduled around.** SS/FF/SF produce an explicit
   `UnplacedTask` naming the limitation — disclosed, not silently dropped, which is the right
   failure mode. Consultants with client hand-offs will want the other three. **Fixed** in the
   Stage 4.3 work (`4aac835`): the planner schedules around all four types in
   dependency-topological order.
4. ✅ **All-day events became full-day hard blocks.** The ViewModel fed every calendar row into
   `fixedCommitments` without filtering, so an all-day marker destroyed a day of capacity. Fixed.
5. ⬜ **`AutoPlan` is O(tasks × chunks × free × taken).** Fine for one week on one device; a
   concern for a multi-tenant server planning 8 projects over 4 weeks. **Closed** (`df1ef68`):
   `AutoPlanBenchmarkTest` measured 66 ms for 800 tasks / 4 weeks — no optimisation was needed;
   the benchmark stays as the tripwire.
6. ✅ **Two buffer code paths.** Unified.
7. ⬜ **Test coverage is inverted against product value.** Improved but not resolved: `AutoPlan`
   is now 282 lines with the contract suite referencing deadlines 20 times, against
   `MultiSchedulePlanHealth`'s 17 tests for an assessment. **Closed** (`df1ef68`): `AutoPlan`
   grew to 17 tests, matching the assessment suite.

### Worth knowing: the crown jewel

`MultiSchedulePlanHealth` (608 lines, 17 tests) builds a **max-flow network** to combine
overlapping working schedules without double-counting a shared human hour, and refuses to call
chunk fragmentation a proven overload. For the consultant ICP — "can I take another client next
week?" — this is the most commercially valuable code in the repository.

---

## 7. Remaining work

### Phases 2–5 — done

Delivered and green; see §2 for commits. The specification set (`01`–`05`) exists, the engine
contract tests exist, the codecs are untangled and `CriticalPathEngine` is portable.

Carried forward from Phase 4's design notes, because the server work still depends on them:

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

### Everything after this

Moved to **`07-roadmap.md`**, sequenced with entry and exit criteria: finish the portability
project (the date-time cluster, the journal codecs, compiler-enforced purity), then the
multi-tenant domain model and a frozen planner contract, then the vertical slice, then depth
and the paid tier, then mobile.

The three engine defects once open (3, 5, 7) were closed with the depth work — see
`07-roadmap.md`.

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

All earlier questions are closed — see `06-scope-deviations.md` for how, and `07-roadmap.md`
for what follows. One judgement call remains, and it does not block any stage:

1. ~~**Kotlin/Native target**~~ — **resolved**: `linuxX64` landed (WP-7), the compiler enforces
   `commonMain` purity, and `iosArm64`/`iosSimulatorArm64` followed with the mobile MVP.
2. ~~**Monorepo reshuffle**~~ — **resolved** (2026-08-19): the web app exists, so `app/`
   moved once — `apps/android`, `apps/mobile`, `apps/web`; `services/server`;
   `packages/planning-core`, `packages/planning-contract`. Gradle module names are
   unchanged (`:app`, `:server`, `:planning-core`, `:planning-contract`) via
   `projectDir` mappings in `settings.gradle.kts`, so scripts and CI spell the same
   tasks. The full gate plus the WP-13 journey pass after the move.

And one standing decision, recorded rather than asked: the three Android-only features from D1
stay. `git revert a5bc8c1 43dea69` remains available if you would rather carry less Android
surface through the rewrite — a product call, not a correctness one.
