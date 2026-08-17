# KMP portability audit

Current state of `:planning-core`: which engine files run in `commonMain`, which still need
JVM APIs, why, and what unblocks what. Kept current as porting proceeds — last updated with
the Phase 2 / Phase 5 mechanical work (2026-08-17).

## Method

Each `core/` file is classified by its own JVM imports plus what it is blocked through,
transitively. Line counts are measured (`wc -l`) on the current tree, not estimates.

**A note on how purity is enforced.** `commonMain` purity is enforced by
`CommonMainPurityTest` (jvmTest), which reads the sources and fails on a JVM import or
`Math.` call. It is *not* enforced by the compiler: `compileCommonMainKotlinMetadata` is
SKIPPED, because Kotlin only produces a metadata compilation once some target needs one, and
`jvm` plus `androidTarget` are both JVM-family — a file importing `java.util.Calendar` in
`commonMain` compiles without complaint (confirmed by planting one). The check fails *open*,
which is worse than absent, which is why the test exists. Adding a non-JVM target (Kotlin/Native
is roughly a gigabyte, and not in the pinned toolchain) would hand the job back to the compiler.

## Two denominators, reconciled

This document tracks the **engine core** (`core/`). `CommonMainPurityTest` prints a **whole-module**
figure, which also counts the domain model, the journal codecs and the mapper. They differ, and both
are quoted in places, so:

| Scope | `commonMain` | `jvmShared` | Portable |
| --- | ---: | ---: | ---: |
| Engine core (`core/` only — this document) | 3,892 | 1,343 | **74%** |
| Whole module (what the test prints) | 4,657 | 3,136 | **59%** |

Neither is wrong; quote the scope with the number.

## Already portable — engine core in `commonMain`, 3,892 lines

| File | Lines | Notes |
| --- | ---: | --- |
| `CriticalPathEngine` | 764 | `PriorityQueue` → private lexicographic min-heap; `Math.*Exact` → `GuardedArithmetic` |
| `MultiSchedulePlanHealth` | 608 | the max-flow capacity engine; needed nothing but `WorkingCalendar` |
| `PlanHealth` | 349 | as above |
| `WorkingCalendar` | 302 | `Calendar`/`SimpleDateFormat` → `kotlinx-datetime`; DST policy named in `AmbiguousLocalTime` |
| `AutoPlan` | 282 | `Math.floorMod` → `Long.mod` |
| `DependencyAnalysis` | 257 | `Math.*Exact` → `GuardedArithmetic` |
| `PlanBlockPreview` | 237 | `Math.floorMod` → `Long.mod` |
| `PlanScenarios` | 157 | |
| `WeeklyReview` | 144 | |
| `PlanExport` | 133 | |
| `IsoDates` | 120 | `SimpleDateFormat` pattern list → one regex + `kotlinx-datetime` |
| `BaselineVariance` | 116 | |
| `GanttDependencyPaths` | 105 | |
| `MiniMarkdown` | 91 | |
| `PortfolioRollup` | 83 | |
| `Fuzzy` | 60 | |
| `AmbiguousLocalTime` | 53 | the DST resolution policy, written down rather than inherited |
| `GuardedArithmetic` | 31 | guarded `addExact`/`subtractExact`/`multiplyExact` |

**74% of the engine core is portable**, up from 34.7%. `WorkingCalendar` was the lever: moving
it released `PlanHealth`, `MultiSchedulePlanHealth`, `AutoPlan`, `PlanBlockPreview` and
`PlanScenarios` — 1,633 lines that needed nothing else.

## Still in `jvmShared`, 1,343 lines of engine core

| File | Lines | Own JVM dependency | Also blocked via |
| --- | ---: | --- | --- |
| `ScheduleAnalysis` | 374 | `Calendar`, `TimeZone`; `MessageDigest` + `ByteBuffer` for `signature` | — |
| `GanttLayout` | 287 | `BigDecimal`, `BigInteger`, `MathContext`, `RoundingMode`, `Calendar`, `TimeZone` | `ScheduleAnalysis` |
| `PlanGanttLayout` | 154 | `Math.subtractExact` | `GanttLayout`, `ScheduleAnalysis` |
| `TimelineLayout` | 131 | `Calendar`, `TimeZone` | `ScheduleAnalysis` |
| `DayPulse` | 122 | `TimeZone` | `ScheduleAnalysis` |
| `GanttInteraction` | 108 | `Serializable` (draft state) | — |
| `GanttZoom` | 98 | — | `ScheduleAnalysis` |
| `PortfolioGantt` | 69 | — | `GanttLayout`, `ScheduleAnalysis` |

**`ScheduleAnalysis` is now the only root blocker in `core/`**, gating 861 lines behind it.
Clearing it and `GanttInteraction`'s `Serializable` takes the engine core to ~100%.

It carries one decision that is not mechanical: `signature` is SHA-256 over length-prefixed
fields and is the **daily-brief cache key**. A common-Kotlin SHA-256 that differs by a byte
invalidates every cached brief once. That is acceptable, but it should be chosen rather than
discovered — expect one cache miss per user at the upgrade, not a bug report.

### Non-`core/` files in the module

Counted by the purity test, not by the engine-core figure above.

| File | Lines | Source set | Note |
| --- | ---: | --- | --- |
| `PlanningModels`, `BriefingEvent` | 501 | `commonMain` | Domain model; keeps its Room annotations because `room-common` is multiplatform |
| `SavedViewCodec` | 252 | `commonMain` | Moved from `jvmShared` — nothing was blocking it |
| `WorkScheduleDefaults` | 10 | `commonMain` | Moved from `jvmShared` — nothing was blocking it |
| `PlanMutationCodec` | 808 | `jvmShared` | Via `LegacyNameKeys`, `WorkingCalendarMapper` |
| `WorkingScheduleMutationCodec` | 363 | `jvmShared` | Via `LegacyNameKeys`, `WorkingCalendarMapper` |
| `PlanCatalogMutationCodec` | 359 | `jvmShared` | Via `LegacyNameKeys`, `WorkingCalendarMapper` |
| `WorkingCalendarMapper` | 239 | `jvmShared` | `Calendar` |
| `LegacyNameKeys` | 22 | `jvmShared` | `Normalizer`, `StandardCharsets`, `Locale`, `UUID` — small, and it blocks all three codecs |

**The three journal codecs are one unit, and it is not a cheap unblock.** An earlier version of
this document called `LegacyNameKeys` "22 lines gating 1,530 lines of codec — the cheapest
remaining unblock". That was wrong, and the compiler said so when it was attempted:

- `PlanMutationCodec`, `PlanCatalogMutationCodec` and `WorkingScheduleMutationCodec` implement
  one sealed `PlanMutationState` hierarchy. *Extending a sealed interface from another module or
  source set is prohibited*, so all three move together or none do — 1,530 lines as a single
  step.
- The unit is gated on **both** `LegacyNameKeys` (NFKC `Normalizer`, name-based `UUID`) **and**
  `WorkingCalendar`, which `WorkingScheduleMutationCodec` uses. So the codecs sit behind the
  date-time cluster, not in front of it.
- `LegacyNameKeys` also resists a straight port: `nameKey` is **stored on rows and compared on
  Undo**, and `stableId` is how the v5 catalog import derives IDs that survive every later
  migration. A reimplementation that differs by one character breaks existing databases. This is
  an `expect`/`actual` job (JVM `Normalizer`; iOS `precomposedStringWithCompatibilityMapping`),
  not a rewrite.

### A note on method: only the compiler is authoritative

Two cheaper methods were tried and both are unsafe here:

| Method | Blind spot |
| --- | --- |
| Scan `import` lines | **Same-package references need no import.** `core/` is one package, and `PlanMutationCodec` reaches `WorkingScheduleMutationCodec` with no import at all. |
| Regex for declared symbol names | **False positives** across packages, and it cannot see sealed-hierarchy or visibility constraints. |

Both were used to produce earlier versions of this table, and each produced a wrong answer that
survived until a file was actually moved. **Move the file, compile, read the error.** The tables
here are now confirmed that way.

## Classification

- 🟢 **Cleared in this pass.** `DependencyAnalysis` (guarded arithmetic) and
  `CriticalPathEngine` (`MinStringHeap` replacing `PriorityQueue` — determinism preserved
  because the heap order is a lexicographic min-heap of strings, as before). Both were moved
  without changing behaviour; every existing test stayed green.
- 🟡 **Trivial — arithmetic only, still pending.** `Math.floorMod` → `a.mod(b)` in `AutoPlan`
  and `PlanBlockPreview`, and `Math.subtractExact` in `PlanGanttLayout`. Nothing else blocks
  these files except the date-time cluster.
- 🟠 **Mechanical but real.** `GanttLayout`'s `BigDecimal`/`BigInteger` keep positions stable
  near `Long` limits. `ScheduleAnalysis.signature` needs a common SHA-256 (or the brief cache
  key changes, which invalidates every cached brief once).
- 🔴 **A genuine project — the date-time cluster.** `WorkingCalendar`, `ScheduleAnalysis`,
  `IsoDates`, `TimelineLayout`, `DayPulse`, `GanttLayout` — 1,291 lines, carrying a behavioural
  decision (the DST policy below) rather than a mechanical substitution.

**Why the cluster is blocked right now:** the pinned offline toolchain has no
`kotlinx-datetime` (checked — it is not in the Gradle cache), and kotlinx has no time-zone
engine anyway (that is the `kotlinx-datetime` + IANA tzdb story). Porting the cluster means
either adding a time library to the pinned toolchain or writing a small zone-offset engine in
`commonMain` — the latter is its own project, and the former is a toolchain decision for the
machine owner, not an offline code change.

## Leverage

Three files are now *root* blockers: `WorkingCalendar`, `ScheduleAnalysis`, `IsoDates`.
Clearing `WorkingCalendar` unblocks `AutoPlan`, `GanttInteraction`, `PlanBlockPreview`,
`MultiSchedulePlanHealth`, `PlanHealth` and `PlanScenarios` — 1,705 lines downstream. Clearing
`ScheduleAnalysis` unblocks `GanttLayout`, `TimelineLayout`, `DayPulse`, `GanttZoom` and
`PlanGanttLayout` — 792 lines.

**Fix the date-time cluster plus the arithmetic and BigDecimal substitutions, and essentially
the whole engine becomes `commonMain`.**

## The DST decision that has to be made deliberately

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

## Related extraction work outside `core/`

Portability is not only about the engine files. Two more untanglings shipped alongside this
audit:

- **Journal codecs untangled from the Room migration file.** `PlanMutationCodec`,
  `WorkingScheduleMutationCodec`, `PlanCatalogMutationCodec`, `SavedViewCodec` and
  `WorkingCalendarMapper` now live in `planning-core`'s `jvmShared`
  (`com.example.data.repository` / `com.example.data.database`), with their tests moved to
  `jvmTest`. `PlanMigration.kt` keeps only migrations, delegating identity helpers
  (`nameKey`/`stableId`) to `LegacyNameKeys` and schedule defaults to `WorkScheduleDefaults`;
  `LegacyPlanCatalogBuilder` (still app-side, Room-adjacent) calls into them. The codec surface
  the app reads had to be widened `internal` → `public` across the module boundary; that is
  recorded in the git history of this pass.
- **`GanttInteraction` split.** The px/dp touch geometry — `GanttDirectManipulationPolicy`,
  `GanttManipulationTargets`, `GanttDirectManipulationTargets` — moved back to `:app`
  (`com.example.core.GanttDirectManipulation`, with its tests in `app/src/test`). The engine
  keeps the time policy (`GanttBlockEditPolicy`), the saveable draft (`GanttBlockDraft`,
  `Serializable` for `rememberSaveable`) and `GanttWorkingBands`; `GanttBlockDraft` keeps the
  JVM marker, which is why this file remains in `jvmShared`.

## Change log

- 2026-08-17: `DependencyAnalysis`, `CriticalPathEngine`, `GuardedArithmetic` → `commonMain`;
  codec/`WorkingCalendarMapper`/`SavedViewCodec` untangled into `planning-core`; `GanttInteraction`
  split back into `:app`; `CommonMainPurityTest` added. All suites green (`scripts/verify.sh
  --fast`), 380 JVM tests.
- 2026-08-17 (later): `SavedViewCodec` (252) and `WorkScheduleDefaults` (10) → `commonMain`;
  they had no JVM dependency and nothing blocking them, and had simply been left behind by the
  Phase 5 untangling. Whole module 29% → 32%. `PortfolioGantt` added to `jvmShared` (correctly
  — it composes `GanttLayout`). Tables re-measured against the tree; the two denominators are
  now reconciled at the top of this document.
- 2026-08-17 (later): `jvmTest` now declares `src/commonMain/kotlin` and `src/jvmShared/kotlin`
  as task inputs. `CommonMainPurityTest` reads the source tree at runtime, so Gradle could not
  see that moving a file invalidates it — the task stayed UP-TO-DATE and the guard reported the
  *previous* layout's percentage. The one number this effort is tracked by was silently stale.- 2026-08-17 (later still): `RowBackupCodec` (156) and its test moved out to `:app` — a Room-row
  backup codec is not planning logic (`06-scope-deviations.md` D3). Module 32% → 33%. Attempted
  to move the journal codecs to `commonMain`; the compiler refused on the sealed hierarchy, and
  the "cheapest unblock" claim above was corrected as a result.
- 2026-08-17 (Stage 1.1): `kotlinx-datetime` 0.8.0 adopted; `IsoDates`, then `WorkingCalendar`
  and everything it gated, moved to `commonMain`. Engine core 34.7% → **74%**, whole module
  33% → **59%**. The DST tie-break is now `AmbiguousLocalTime`, named and tested, instead of
  whatever `java.util.Calendar` happened to do. Cost: `:app` needs core library desugaring,
  because `kotlinx-datetime` is `java.time`-backed and `minSdk` is 24.
