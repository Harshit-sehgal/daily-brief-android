# Strategy reconciliation — command center shell, consultant wedge

Status: decided for the current repository shape on 2026-08-19.

## Why this exists

The earlier SaaS documents chose a hybrid product for independent consultants: Today and
auto-planning provide the free magic moment, while project depth is paid. The later product
review made the shell more deliberately outcome-led: Today should be the hero, Planner should
unify calendar and planned work, and Gantt/dependencies/capacity should appear only when the
user enters a project or asks for an explanation.

These are compatible decisions. The customer and wedge stay consultant-focused; the navigation
and first-run experience become a command center instead of exposing the planning engine as the
product.

## Product sentence

> Daily Brief turns a consultant's calendar, tasks, and deadlines into a realistic daily plan,
> then proposes safe changes when the day moves.

The product is not sold as Calendar + Gantt + Kanban + AI. Those are implementation surfaces
behind the recurring outcome.

## V1 surface hierarchy

```text
Today         the day's answer: now, next, planned work, clashes, brief, replan action
Planner       one timeline joining fixed calendar events and planned task blocks
Inbox         captured work with no project or schedule yet
Projects      project detail: Tasks first, Board second, Timeline/Gantt progressively disclosed

Insights      capacity, plan health, scenarios, baselines, and weekly review
Integrations  Google calendar first; later calendar/connectors such as Outlook and Notion;
              server-managed AI connection state
Settings      account, workspace, working hours, subscription, exports, and recovery
```

Today, Planner, Inbox, and Projects are the primary work loop. Insights is intentionally a
secondary destination even though its engine is commercially important. A consultant first needs
to know what to do; they inspect capacity when deciding whether commitments fit.

## Core journey

1. Sign up and connect a calendar.
2. Confirm working hours.
3. Capture tasks in Inbox or add them to a project.
4. Choose **Plan my day** or **Plan my week**.
5. Review a proposal with reasons; no proposal writes to the schedule.
6. Apply or reject it; Undo remains available after Apply.
7. Return to Today to work from Now/Next and see any conflict.
8. When life changes, request a replan and approve the deterministic proposal.

The AI interprets intent and explains options. The planning engine validates constraints and owns
the transaction. No model response directly mutates a calendar or task schedule.

## Scope boundary

### Core launch promise

- Account and workspace
- Calendar connection and server-side reconciliation
- Today command center
- Inbox and tasks
- Projects with task and board views
- Unified weekly planner
- Working hours, conflicts, and deterministic auto-planning
- Daily brief and proposal-based planning assistant
- Basic notifications, history, and Undo
- Subscription and server-enforced usage limits

### Progressive or paid depth

- Capacity and Plan Health
- Dependencies and critical path
- Timeline/Gantt inside a project
- Scenarios, baselines, variance, portfolio rollups, and weekly review
- Advanced exports and team administration

The repository may already contain implementations for depth features. Their existence is not a
commitment to put every feature in the first-run shell or in the launch price tier.

### Explicitly later

Voice/free-form mutation, enterprise administration, broad connector expansion, PDF decks,
portfolio-wide professional Gantt, and deep layout customization remain later unless a new
product decision promotes them.

## Source-of-truth rule

`01-product-teardown.md` records feature disposition, this document records the reconciled
product hierarchy, and `02-v1-product-spec.md` records the web V1 contract. Android remains a
shipping prototype/client and can retain useful power features; every port or new surface must
name its product document before implementation.
