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
| Engine core (`core/` only — this document) | 1,784 | 3,364 | **34.7%** |
| Whole module (what the test prints) | 2,547 | 5,311 | **32%** |

Neither is wrong; quote the scope with the number.

## Already portable — engine core in `commonMain`, 1,784 lines

| File | Lines | Notes |
| --- | ---: | --- |
| `CriticalPathEngine` | 764 | `PriorityQueue` replaced by a private lexicographic min-heap (`MinStringHeap`); `Math.*Exact` → `GuardedArithmetic` |
| `DependencyAnalysis` | 257 | `Math.*Exact` → `GuardedArithmetic` |
| `BaselineVariance` | 116 | |
| `PlanExport` | 133 | |
| `WeeklyReview` | 144 | |
| `PortfolioRollup` | 83 | |
| `MiniMarkdown` | 91 | |
| `GanttDependencyPaths` | 105 | |
| `Fuzzy` | 60 | |
| `GuardedArithmetic` | 31 | the guarded `addExact`/`subtractExact`/`multiplyExact` helper |

34.7% of the engine core (1,784 / 5,148 lines) is portable — up from ~25% at the end of the
Phase 1 extraction (`3d05da8`). The share dipped from the 35.5% reported earlier in the day
without anything moving backwards: `PortfolioGantt` (+69) was added to `jvmShared`, and
`AutoPlan` (+36) and `ScheduleAnalysis` (+11) grew with the deadline fix. A growing denominator
is the normal way this number falls.

## Still in `jvmShared`, 3,364 lines

| File | Lines | Own JVM dependency | Also blocked via |
| --- | ---: | --- | --- |
| `ScheduleAnalysis` | 374 | `ByteBuffer`, `MessageDigest`, `Calendar`, `TimeZone` | `WorkingCalendar` |
| `WorkingCalendar` | 309 | `ParsePosition`, `SimpleDateFormat`, `Calendar`, `Locale`, `TimeZone` | — |
| `GanttLayout` | 287 | `BigDecimal`, `BigInteger`, `MathContext`, `RoundingMode`, `Calendar`, `TimeZone` | `ScheduleAnalysis` |
| `AutoPlan` | 282 | `Math.floorMod` | `WorkingCalendar` |
| `PlanBlockPreview` | 237 | `Math.floorMod` | `DependencyAnalysis`*, `WorkingCalendar` |
| `PlanScenarios` | 157 | — | `AutoPlan`, `WorkingCalendar` |
| `PlanGanttLayout` | 154 | `Math.subtractExact` | `GanttLayout`, `ScheduleAnalysis` |
| `TimelineLayout` | 131 | `Calendar`, `TimeZone` | `ScheduleAnalysis` |
| `DayPulse` | 122 | `TimeZone` | `ScheduleAnalysis` |
| `GanttInteraction` | 108 | `Serializable` (draft state) | `WorkingCalendar` |
| `GanttZoom` | 98 | — | `ScheduleAnalysis` |
| `IsoDates` | 79 | `ParsePosition`, `SimpleDateFormat`, `Date`, `Locale`, `TimeZone` | — |
| `MultiSchedulePlanHealth` | 608 | — | `DependencyAnalysis`*, `WorkingCalendar` |
| `PlanHealth` | 349 | — | `DependencyAnalysis`*, `WorkingCalendar` |
| `PortfolioGantt` | 69 | — | `GanttLayout`, `ScheduleAnalysis`, `WorkingCalendar` |

\* Only via the schedule engine they compose, not through JVM APIs.

`DependencyAnalysis` and `CriticalPathEngine` no longer appear — they moved to `commonMain`.

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
| `RowBackupCodec` | 156 | `jvmShared` | **Does not belong in this module** — see `06-scope-deviations.md` D3 |

`LegacyNameKeys` is 22 lines and gates 1,530 lines of codec. `Normalizer` is the only real
obstacle; the rest have common equivalents. It is the cheapest remaining unblock in the module.

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
  *previous* layout's percentage. The one number this effort is tracked by was silently stale.