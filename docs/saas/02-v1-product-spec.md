# V1 product specification

For the consultant ICP: independent consultants whose time is sold, whose work product is
scheduled, and whose calendar is not under their control. V1 is web-first (a server runs the
same `planning-core` engine the app runs) with the Android app kept as the strongest client.

## Information architecture

```
Today        — agenda, clashes, the day's shape (greeting lives here), daily brief
Planner      — Projects → Tasks / Board / Timeline (Gantt), Schedule Map page
Inbox        — captured-but-unrouted tasks (columnId = null today)
Capacity     — working schedules, plan health, overload, portfolio rollup, weekly review
Integrations — calendar connections, Notion, Gemini (platform key), write-back toggle
Settings     — account, workspace, tier, export API, danger zone
```

This is the existing app's IA minus the fold-ins: `PlanOutline` merges into `Projects → Tasks`,
`PortfolioRollup` + `WeeklyReview` merge into `Capacity`, `Notion` folds into `Integrations`.

## Progressive disclosure

- **Today** opens with Now, Up next, anything clashing — no chrome above the fold. Everything
  else is a card the user adds under Edit (current Home behaviour, kept).
- **Planner → Timeline** keeps the disclosure discipline already enforced by
  `ScheduleMapProportionInstrumentedTest`: explanations behind labelled disclosures, status on
  one line, chrome measured in dp.
- **Capacity** hides the max-flow machinery; it shows three numbers and one sentence: available
  hours this week, planned hours, and whether the answer to "can I take another client?" is yes,
  no, or "here is what would have to move".
- A control row wraps or it crushes: `FlowRow` for chip rows, ellipsis on status text — the
  invariants in `CLAUDE.md` are the spec, not suggestions.

## Onboarding and the 90-second magic moment

1. **Connect a calendar** (Google/Outlook; device calendar stays for the app). Real events
   appear in under 10 seconds.
2. **Set working hours** — one screen, defaults from the app's `WorkScheduleDefaults`
   ("Default working week", 09:00–17:00 Mon–Fri). DST-safe by construction (`WorkingCalendar`).
3. **The magic moment (90 seconds in):** Today shows Now, Up next and one clash — the user's
   actual week, already reconciled. The summary line ("3 overlapping meetings today") is
   generated, not templated, and it lands before any plan exists.
4. **First plan:** import one client project (or Notion board), hit auto-plan, and see blocks
   land in working time with reasons attached — the proposal UI from `AutoPlan` verbatim.

Onboarding is complete when the user has seen a proposal *and rejected or accepted it*: the
replan loop, not the plan, is the product.

## The replan loop

The loop is the product's core motion, unchanged from the app's contract:

1. **Propose** — `AutoPlan.propose` returns blocks + reasons, writes nothing.
2. **Review** — the user sees blocks in working time, each with its sentence; scenarios
   compare orderings via `preferredOrder` without mutating the plan.
3. **Commit or adjust** — Apply goes through the journal (one History entry, Undo takes it
   back); every transient mode owns its Back (the app's `BackHandler` invariant).
4. **Health** — after commit, `PlanHealth.evaluate` re-runs and either confirms or names the
   new risk, with a repair candidate.

V1 adds one loop turn the app lacks: **the deadline turn**. Auto-plan takes `dueAt` as a
constraint (the engine defect 1 the app ships with; the contract in 04 makes it mandatory),
so "I must have this to the client Friday" produces either a plan that ends before Friday or
an explicit refusal naming what would have to move.

## Tier boundary

| Free | Paid (V1) |
| --- | --- |
| One workspace, one calendar connection | Unlimited connections, multi-workspace |
| Planner: one project, board + timeline | Unlimited projects, capacity, baselines |
| Daily brief, limited | Platform-key Gemini summary with per-tenant quota |
| 1 client engagement tracked | N client engagements, portfolio capacity |

The engine itself is not tiered: `MultiSchedulePlanHealth` runs for everyone; the paid tier
unlocks *multiple* engagements so the max-flow question becomes real.

## Explicitly not in V1

- Voice/free-form intent (the replan loop makes it redundant)
- PDF export (server-side after launch; teardown marks LATER)
- Mobile widgets, encrypted backup/restore (LATER; `SecretStore` design carries over)
- Multi-window / tablet layout polish
- Recurring tasks (Notion repeats via connectors only)
- Slack/Teams connectors (calendar connection is the funnel)
- Anything that edits a user's plan without a visible proposal