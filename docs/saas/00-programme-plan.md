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
| **Product shell** | Command center | Today is the hero; Planner unifies calendar and planned work; Inbox captures unrouted tasks; Projects progressively disclose Board and Timeline; Insights contains capacity and other depth. See `11-strategy-reconciliation.md`. |
| **ICP** | Independent consultants and high-end freelancers running 3–8 concurrent client projects against a meeting-heavy calendar | Capacity analytics and dependencies answer commercial questions ("can I take another client next week?"), not productivity-theatre ones. Expansion path: consultant → consultant + subcontractor → small consultancy → team capacity. |
| **Out of scope forever** | Invoicing, CRM, expenses, proposals, contracts, time sheets | The boundary is *commitment → realistic execution plan*. Other software handles getting paid. |
| **Engine** | Kotlin Multiplatform `packages/planning-core` | One engine shared by a JVM planning service, Android, and later iOS. No TypeScript rewrite of the scheduling maths. |
| **Stack** | Next.js web · TypeScript SaaS API · Kotlin/Ktor planner · Postgres · Expo mobile with native Kotlin/Swift modules | Language ownership is clean: product UI in TypeScript, planning intelligence in Kotlin. |
| **Scope of the current step** | Product hardening and evidence | The specification, server, web vertical slice, planning engine, Android client, and mobile MVP exist. Remaining work is implementation hardening plus external/manual proof, not another broad feature sweep. |

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

### Current shape (2026-08-19, `scripts/verify.sh --fast` green)

| | Files | Lines | Share |
| --- | ---: | ---: | ---: |
| `packages/planning-core/core` | all engine files | — | **100%** in `commonMain` |
| `packages/planning-core` | `commonMain` + `jvmShared` | 9 JVM-actual lines remain | **99.9%** portable |
| `:planning-core:jvmTest` | — | — | 272 tests, 0 failures |
| `:planning-contract:jvmTest` | — | — | 26 tests, 0 failures |
| `:server:test` | — | — | 57 tests, 0 failures |
| `:app:testDebugUnitTest` | — | — | 184 tests, 0 failures |

The mobile cache persistence rules now have an executable test layer:
`apps/mobile/npm test` passes 10 tests covering round-trip encoding, legacy entries, corruption
rejection, workspace/project key isolation, web-storage and modeled native-adapter reload,
malformed-entry cleanup, atomic replacement, and the failed-storage-write posture. This does not
replace native-device proof of document-directory persistence, restart recovery, or storage
failures.
The separate production API-origin policy has 5 focused tests and a positive/negative Expo export
check; it refuses an absent or blank origin in production while retaining emulator/localhost
defaults only for development.

Two denominators, both quoted in places; `05-kmp-portability-audit.md` reconciles them.

### Scope deviations — all closed

Seven entries, catalogued in **`06-scope-deviations.md`**. D1 (three features built against the
V1 exclusion list) was resolved by amending `02` to separate the Android surface from web V1
scope rather than deleting working code; D2 (the deadline change) accepted and recorded;
D3/D4/D5/D7 fixed in code. The pattern is the useful part: **every entry was a document and the
tree disagreeing, and in four of seven the document was what was wrong.**

**Forward work is in `07-roadmap.md`; `05-kmp-portability-audit.md` is the source of truth for
engine placement.**

---

## 3. Historical findings that changed the plan

Recorded because each one invalidated something a reasonable person would otherwise assume at
the time. The completion state below supersedes any old "blocked" wording in these notes.

**The original engine was not `java.time`-based.** Its date maths used `java.util.Calendar`,
`TimeZone` and `SimpleDateFormat` across 8 files. These are JVM-only and cannot enter
`commonMain`. More importantly, `WorkingCalendarTest` **pins `Calendar`'s DST tie-break**: a
fall-back ambiguous wall time resolves to the *later, standard-time* occurrence.
`kotlinx-datetime` and `java.time` resolve fall-back to the *earlier* occurrence. A naive port
silently flips a tested behavioural contract. See §5.

**Before the Native target, `commonMain` purity was not enforced by the compiler and failed open.**
At that time `compileCommonMainKotlinMetadata` was **SKIPPED**: Kotlin only produced a metadata
compilation once some target needs one, and `jvm` plus `androidTarget` are both JVM-family.
Verified by planting a file importing `java.util.Calendar` in `commonMain` — the build stayed
green and said nothing. An earlier commit message in this branch (`3d05da8`) claims the
compiler catches this; **that claim is wrong**. Enforcement now comes from
`CommonMainPurityTest`, which reads the sources the way `UiConsistencyTest` reads the UI, and
which was itself verified by planting a violation and watching it fail with a precise message.
The `linuxX64` target now supplies the non-JVM metadata consumer, so the compiler enforces this
boundary and `CommonMainPurityTest` remains as a faster diagnostic duplicate.

**Every module must compile against SDK 37.1, never bare 37.** The pinned SDK carries
`platforms;android-37.1` only. `compileSdk = 37` asks for 37.0 and sends Gradle to the network,
and because the download reports no task progress the build looks hung rather than failed — it
sat for thirteen minutes before this was spotted. Both modules now write
`compileSdk { version = release(37) { minorApiLevel = 1 } }`.

**Kotlin will not smart-cast a `val` it does not own.** With the model in another module,
`if (row.dayOfWeek != null) use(row.dayOfWeek)` no longer compiles in `:app`. Six sites now bind
a local first. Expect this on every nullable model field the app reads.

**The journal codecs initially looked portable and were not.** `PlanMutationCodec` reached
`WorkingScheduleMutationCodec`, which reaches `LegacyPlanCatalogBuilder` inside the Room
migration file. They were moved, found to break the build, and moved back. Untangling that chain
was a content change; the codecs are now separated in `packages/planning-core`, and the failed
move remains documented as a lesson.

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

Headline as of 2026-08-19: **100% of the engine core is in `commonMain`** and the whole module is
99.9% portable. The only JVM-specific lines are the `LegacyNameKeys` JVM actual; the iOS actual
and Linux compiler guard are present. See `05-kmp-portability-audit.md` for the measured table.


## 5. The DST policy retained deliberately

`WorkingCalendarTest` pins two behaviours that come from `java.util.Calendar` rather than from
anything the product chose:

- a spring-forward gap normalises **forward** to the next valid wall-clock minute;
- a fall-back ambiguous wall time resolves to the **later, standard-time** occurrence.

`kotlinx-datetime` and `java.time` resolve fall-back to the **earlier** occurrence by default.
Porting naively flips a tested contract.

**Implemented:** the policy is named in code as `AmbiguousLocalTime.LATER_OFFSET`, preserving
today's behaviour; the existing tests remain the proof. The choice is visible and reviewable
rather than an inherited library default.
This matters more on a server than on a phone, because the server resolves many time zones at
once and a silent flip would move real working windows for real users.

---

## 6. Engine defects — all closed

Four were fixed in `b11ad72`, ahead of the approval this document asked for; see
`06-scope-deviations.md` D2 for why that is logged and why the change is still worth keeping.

1. ✅ **`AutoPlan` did not treat a deadline as a constraint.** It sorted by `dueAt` and appended
   *"ahead of its due date"* to its reason string without checking that a proposal ends before
   `item.dueAt`. **Fixed** with a `DeadlinePolicy` enum: `HARD` (the default) only proposes work
   that finishes before `dueAt` and names whatever does not fit; `SOFT` preserves the previous
   behaviour but no longer claims work is ahead of a deadline when it is not. Keeping the old
   semantics reachable is what makes the extraction's "behaviour preserved" claim still checkable.
2. ✅ **`AutoPlan` ignored `item.startConstraint`.** Fixed in the same commit.
3. ✅ **Only `FINISH_TO_START` dependencies are scheduled around.** SS/FF/SF originally produced an explicit
   `UnplacedTask` naming the limitation — disclosed, not silently dropped, which is the right
   failure mode. Consultants with client hand-offs will want the other three. **Fixed** in the
   Stage 4.3 work (`4aac835`): the planner schedules around all four types in
   dependency-topological order.
4. ✅ **All-day events became full-day hard blocks.** The ViewModel fed every calendar row into
   `fixedCommitments` without filtering, so an all-day marker destroyed a day of capacity. Fixed.
5. ✅ **`AutoPlan` is O(tasks × chunks × free × taken).** Fine for one week on one device; a
   concern for a multi-tenant server planning 8 projects over 4 weeks. **Closed** (`df1ef68`):
   `AutoPlanBenchmarkTest` measured 66 ms for 800 tasks / 4 weeks — no optimisation was needed;
   the benchmark stays as the tripwire.
6. ✅ **Two buffer code paths.** Unified.
7. ✅ **Test coverage is inverted against product value.** It was initially inverted: `AutoPlan`
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

The original extraction, multi-tenant schema, frozen contract, server vertical slice, depth
features, billing seam, and mobile MVP are implemented. The current tail is deliberately smaller:

1. **Mobile cache evidence.** The mobile client now caches Today, projects, boards, and the
   portfolio strip per workspace; stale reads are labelled and Apply remains server-authoritative.
   Encoding, corruption, workspace isolation, web reload, modeled native-adapter reload, atomic
   replacement, and failed-write behavior are covered by 10 focused tests. Physical native
   persistence, restart, and offline behavior still need a real device. The local production
   release verifier now also passes after fresh Expo generation and verifies the merged manifest;
   hosted workflow execution remains separate.
2. **iOS verification on macOS.** Run `scripts/verify-ios-framework.sh`, which builds both Apple
   slices and type-checks a Swift import against `PlannerCore`; Linux can review the script but
   cannot prove Apple toolchain output until the macOS workflow runs.
3. **Real-account and production evidence.** Walk the Google runbook, verify OAuth refresh and
   sync failure recovery, configure KMS/Stripe/Gemini/Expo providers, and run
   `API_BASE=... WEB_ORIGIN=... scripts/production-probe.sh` plus the public-domain probes before
   calling the SaaS launch-ready. The fixture journey now also has a dedicated CI job; that is
   still not evidence for a real Google account or deployed provider configuration.
4. **Product validation.** Test the command-center journey with real consultants. Code coverage
   does not prove that Today is understandable or that the proposed schedule earns trust.
5. **Dependency maintenance.** The nested `xcode`/`uuid` moderate findings are closed with a
   verified `uuid` 11.x override. The current audit reports eight high upstream
   Expo/React Native/Metro findings; resolving them requires a planned framework upgrade rather
   than `npm audit fix --force`.

The API24 emulator, physical-device behavior, broad accessibility matrix, and iOS evidence are
evidence tracks; they are not silently counted as passed by a Linux build.

The real Google calendar adapter was also hardened during the 2026-08-19 residual audit: date-only
all-day events are converted to a half-open day, `nextPageToken` is followed, and non-2xx provider
responses fail closed. The focused provider tests, full server suite, and fixture journey pass;
the reconciliation worker now records provider-fetch failures and retries only transient errors;
the real-account runbook is still required for provider and timezone evidence.

### Phases 2–5 — done

Delivered and green; see §2 for commits. The specification set (`01`–`05`) exists, the engine
contract tests exist, the codecs are untangled and `CriticalPathEngine` is portable.

The following are retained as historical design notes; the corresponding schema, server, and
contract work is now implemented and covered by `07-roadmap.md`, `08-work-packages.md`, and the
server tests.

**Domain model.** `PlanBoard` → `Project` (a client engagement). The original design identified
**`Client` as a new entity the schema lacked**; the current product uses project/workspace
boundaries until a dedicated client directory is validated. Then `PlanColumn` → `WorkflowStage`,
`PlanItem` → `Task`, `PlanBlock` → `ScheduledBlock`, `BriefingEvent` → `ExternalEvent`,
`WorkSchedule` unchanged (the multi-schedule max-flow becomes "client A hours vs internal
hours"), `PlanMutation` → `AuditEntry`, plus `User`, `Workspace`, `Membership`,
`CalendarConnection`, `PlanRun`, `PlanProposal`, `Subscription`.

**Four invariants that needed redesign, not translation.**

- `BriefingRepository.SCHEDULE_MUTEX` was a **process-wide** `Mutex` holding provider I/O inside
  the lock. Server-side now uses a per-tenant advisory lock with the fetch pulled *outside* it — you
  cannot hold a DB lock across an outbound HTTP call at scale. Property to preserve:
  reconciliation of a source's rows and a concurrent user edit must not interleave.
- Undo's staleness check (`matchesMutationState`) was whole-row value equality inside one SQLite
  transaction on a single-writer database. The server uses a claimed journal entry plus row
  locking on affected entities; the codecs are separated in the planning-core module.
- `SecretStore` is Android Keystore — replaced by KMS/envelope encryption. Keep its design
  properties: AAD bound to the setting key, never destroy ciphertext on a failed read.
- Two constraints originally lived only in application code and are now enforced by schema and
  migration checks: saved-view uniqueness and exactly one non-archived default work schedule.

**Security change for the SaaS.** Today users can paste their own Gemini key on-device. The
server implementation uses a platform key seam, per-tenant quota, and server-side OAuth tokens;
the browser never receives a long-lived secret.

### Everything after this

The forward tail is now recorded in **`07-roadmap.md`**: mobile cache evidence, Mac-side iOS
verification, real-account/production evidence, and product validation.

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
- `compileCommonMainKotlinMetadata` now executes through the `linuxX64` consumer and is part of
  the gate; `CommonMainPurityTest` remains the faster diagnostic guard.
- A `BUILD SUCCESSFUL` from a piped Gradle invocation (`./gradlew … | tail`) reports the exit
  code of `tail`, not of Gradle. Read the output, not the status.
- Moving files between source sets inside one module leaves `:app` legitimately UP-TO-DATE,
  because the compiled classes are identical. Use `--rerun-tasks` when that needs proving.

## 9. Open questions for the user

All earlier questions are closed — see `06-scope-deviations.md` for how, and `07-roadmap.md`
for what follows. The command-center shell is now recorded as the product hierarchy; it does
not change the consultant ICP or delete the existing depth implementation.

1. ~~**Kotlin/Native target**~~ — **resolved**: `linuxX64` landed (WP-7), the compiler enforces
   `commonMain` purity, and `iosArm64`/`iosSimulatorArm64` followed with the mobile MVP.
2. ~~**Monorepo reshuffle**~~ — **resolved** (2026-08-19): the web app exists, so `app/`
   moved once — `apps/android`, `apps/mobile`, `apps/web`; `services/server`;
   `packages/planning-core`, `packages/planning-contract`. Gradle module names are
   unchanged (`:app`, `:server`, `:planning-core`, `:planning-contract`) via
   `projectDir` mappings in `settings.gradle.kts`, so scripts and CI spell the same
   tasks. The full gate plus the WP-13 journey pass after the move.

3. ~~**Command-center shell**~~ — **resolved** (2026-08-19): `11-strategy-reconciliation.md`
   reconciles the outcome-led Today/Planner/Inbox/Projects shell with the consultant/hybrid
   wedge. Depth remains progressively disclosed and paid.

And one standing decision, recorded rather than asked: the three Android-only features from D1
stay. `git revert a5bc8c1 43dea69` remains available if you would rather carry less Android
surface through the rewrite — a product call, not a correctness one.
