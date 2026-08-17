# Scope deviations register

Where the codebase has drifted from the agreed objective. Each entry names the commit, the
document it contradicts, the cost, and a recommendation. Nothing here has been reverted —
scaling the work down is the owner's call, not the reviewer's.

**Status key:** 🔴 contradicts a written decision · 🟠 process gap · 🟡 architectural drift ·
🟢 aligned, noted for the record.

**The objective these are measured against**, from `00-programme-plan.md` §1:

> Scope of the current step: **specification + engine extraction**. No Next.js app, auth,
> Stripe, deployed Postgres, Ktor service, Expo, iOS, **or UI implementation**.

And the standard the whole SaaS reduction was meant to apply:

> "Does this improve the core recurring workflow enough to deserve permanent UI?"

---

## 🔴 D1 — Three features on the "not in V1" list were built anyway

**Commits:** `a5bc8c1` (home-screen widget + PDF export, 615 lines), `43dea69` (encrypted
backup and restore, 573 lines), `181f4cb` (cross-board portfolio timeline, 393 lines).
**Total: 1,581 lines of new Android feature code.**

**Contradicts:** `02-v1-product-spec.md` § *Explicitly not in V1*, written at 13:37 the same
day:

> - PDF export (server-side after launch; teardown marks LATER)
> - Mobile widgets, encrypted backup/restore (LATER; `SecretStore` design carries over)

and `01-product-teardown.md`, which independently reaches the same verdict — "Board
printing/PDF → **LATER**", and a summary line reading "LATER 6 (voice intent, **PDF**,
**widgets**, **backup**, multi-window polish, physical-device calendars)".

The timeline is not ambiguous. The spec excluding these was committed at **13:37**; the widget
and PDF export landed at **13:51**, backup/restore at **14:03**, the portfolio timeline at
**14:09** — 14, 26 and 32 minutes after the document saying not to build them.

**Why it matters beyond the rule.** The entire premise of this exercise was that the prototype
suffers from *"this could be useful, therefore it should exist"*, and that the SaaS needs
**product reduction before more development**. These three commits are that exact pattern,
executed after the reduction was written down. The V1 scope did not shrink; it grew by 1,581
lines in the client that the plan says is *not* the V1 surface (V1 is web-first).

Widgets and PDF also fail the ICP filter. A consultant deciding "can I take another client?"
is not blocked by the absence of a home-screen widget.

**Cost if kept:** they are now in the gate, in `preview.html`, and in three instrumented tests
(`b446d11`). Each is permanent UI to maintain through the web rewrite, and `43dea69` adds a
whole-workspace backup format that becomes a compatibility obligation.

**Recommendation:** keep the code, revert the *surfaces*. The engine-adjacent parts are
genuinely reusable — `PortfolioGantt` (69 lines, tested) is real portfolio capability the
teardown already wanted under Capacity. The widget, the PDF exporter and the backup UI should
come out of the Plan overflow and Settings and sit on a branch until V1 ships. If they stay,
`02-v1-product-spec.md` must be amended so the spec and the product agree — right now one of
them is lying.

---

## 🟠 D2 — Engine behaviour changed without the approval that was flagged as required

**Commit:** `b11ad72` — "fix the four engine defects (deadlines, start constraints, all-day
events, buffering)".

**Contradicts:** `00-programme-plan.md` §6 and §9.1, which recorded the deadline defect and
then explicitly held it back:

> These are **documented and pinned by characterisation tests, not fixed in this step** —
> fixing them changes behaviour, and behaviour preservation is the completion criterion for
> the extraction. […] **Open question 1: Deadline fix — approve changing `AutoPlan` to treat
> `dueAt` as a hard constraint? It is a behaviour change, so it is not bundled with the
> extraction.**

That question was never answered. The change was made anyway.

**In its favour — and this is not a small caveat.** The change is well built and directionally
exactly what `01`/`02`/`04` call for. It introduces `DeadlinePolicy` with `HARD` as the default
and `SOFT` preserving the previous behaviour, so the old semantics remain reachable rather than
being deleted; the contract suite references deadlines 20 times. This is a better change than
the one that was proposed.

**Why it is still logged.** The extraction's whole claim to safety was "behaviour preserved,
proved by the existing suites". Once behaviour changes in the same stretch of work, a green
gate no longer distinguishes "the move was faithful" from "the move was faithful *and* the
semantics shifted". The evidence is still good here because `DeadlinePolicy.SOFT` exists — but
that was luck of good judgment, not the process working.

**Recommendation:** accept it, and record the acceptance in `00-programme-plan.md` §6 so the
defect list stops describing shipped behaviour as outstanding. Treat the remaining defects
(3: SS/FF/SF scheduling, 5: `AutoPlan` complexity, 7: coverage inversion) the same way — decide
explicitly, then do them.

---

## 🟡 D3 — `planning-core` is accumulating things that are not planning

**Commit:** `43dea69` put `data/backup/RowBackupCodec.kt` (156 lines) into
`planning-core/jvmShared`.

**Contradicts:** the module's stated purpose in `CLAUDE.md` and `00-programme-plan.md` — "the
scheduling engine and the domain model it works on", shared by a JVM planning service, Android
and later iOS.

A codec for backing up **Room cursor rows** is none of those things. The planning service will
never call it; iOS will never call it. It is an Android persistence concern that happens to be
written in portable Kotlin, and "portable" is not the same as "belongs here". Left alone, this
is how a shared engine module turns into a second app module.

Note the tell: it has **zero imports** and nothing blocking it, yet it was placed in
`jvmShared` rather than `commonMain` — so it is both in the wrong module *and* in the wrong
source set of that module.

**Recommendation:** move it to `:app` under `data/backup/` alongside `BackupManager`. If the
backup feature is reverted per D1, this goes with it. Add a rule to `CLAUDE.md`: a file earns a
place in `planning-core` by being called by the planner, not by being free of Android imports.

---

## 🟡 D4 — The portability guard could report stale numbers *(fixed in this pass)*

`CommonMainPurityTest` reads the source tree at runtime, so Gradle had no way to know that
moving a file between source sets invalidates it. Moving two files left `jvmTest` UP-TO-DATE
and the guard reported the *previous* layout's percentage — the one number the whole
portability effort is tracked by.

**Fixed:** `planning-core/build.gradle.kts` now declares `src/commonMain/kotlin` and
`src/jvmShared/kotlin` as inputs of the `jvmTest` task.

---

## 🟢 D5 — Two files were sitting in `jvmShared` with nothing blocking them *(fixed in this pass)*

`SavedViewCodec` (252 lines) and `WorkScheduleDefaults` (11) had no JVM dependency and no
blocked dependency, but were left in `jvmShared` by the Phase 5 codec untangling. Moved to
`commonMain`: **29% → 32% portable**.

`RowBackupCodec` (156) is also unblocked but is not moved, per D3 — it should leave the module
entirely rather than move deeper into it.

---

## 🟢 D6 — Aligned work, recorded so the register is not only complaints

- `17cdc00`, `f87635b`, `86e62ad` — Phase 2 and 5 as planned: guarded arithmetic,
  `CriticalPathEngine` and `DependencyAnalysis` into `commonMain`, journal codecs untangled
  from the Room migration file, `GanttInteraction` split (297 → 109 lines) with the px/dp
  manipulation geometry returned to `:app`. This is exactly the plan's Phase 5 list.
- `b649ac7` — engine contract tests, Phase 3.
- `b0ecbd3` — the four specification documents, Phase 4.
- `4a15b2c` — Gantt date axis (P24), continuing the existing HCI ledger rather than adding
  scope.

---

## Ledger

| ID | Issue | Severity | State |
| --- | --- | --- | --- |
| D1 | Widget, PDF export, backup/restore built against the V1 exclusion list | 🔴 | **Open — needs a decision** |
| D2 | Deadline behaviour changed without the flagged approval | 🟠 | **Open — recommend accept & record** |
| D3 | `RowBackupCodec` misplaced in `planning-core` | 🟡 | **Open — recommend move to `:app`** |
| D4 | Purity guard could report stale numbers | 🟡 | Fixed this pass |
| D5 | Two unblocked files left in `jvmShared` | 🟢 | Fixed this pass |
| D6 | Phases 2–5 delivered as planned | 🟢 | No action |

**The one decision that matters:** D1. Everything else is bookkeeping. Either the three
features stay and `02-v1-product-spec.md` is amended to admit them, or they come out and the
spec stands. What cannot hold is the current state, where the plan of record says one thing and
the shipped app does another.
