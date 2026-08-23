# Daily Brief HCI redesign and delivery plan

Status: implementation and adaptive/direct-manipulation tranches reconciled against the current green device gate, 2026-08-19 (Asia/Kolkata)

This document turns the product direction in [PREMIUM_PRODUCT_PLAN.md](PREMIUM_PRODUCT_PLAN.md) into an HCI acceptance contract and dependency-ordered delivery plan. The premium boundary, three-root information architecture, ownership rules, and continuous time-spine concept remain unchanged.

This is not a release-readiness declaration. Section 12 records the exact automated, emulator, release-smoke, artifact-size, and limited visual evidence produced by the verification run on 2026-08-11. Those passing layers do not prove the unrun window-boundary matrix, physical-provider behavior, manual accessibility, or user comprehension. A source file, test, or green local slice must not be stretched beyond the evidence layer that passed.

That distinction earned its keep in this tranche. The Gantt direct-manipulation source compiled, its pure policies passed, and the screen rendered — while the rendered device journey proved that the move target could not actually be dragged, because the preview panel and the two resize handles were drawn over the bar. Section 12 records the failure and the fix.

## Decision summary

The best organization is:

- three stable roots: Home, Calendar, and Plan;
- Calendar representations: Agenda, Timeline, and Week;
- Plan representations: Outline, Board, and Gantt;
- Search and commands at the top-right of every root;
- Settings beside Search, but never as a primary root;
- one contextual create button at the bottom-right;
- date, range, filter, and view controls beside the content they change;
- every gesture as an accelerator for a visible button, menu, field, or accessibility action;
- solid bars for app-owned Plan blocks and outlined bars for fixed calendar commitments;
- one continuous time spine linking the selected date, live-now marker, working time, Plan blocks, and Gantt today marker.

Do not add Calendar, Timeline, Board, Gantt, Search, or Settings as additional root tabs. Do not add root-changing swipe gestures. Do not make drag, swipe, pinch, color, or an unlabeled icon the only way to understand or perform an action.

The verified current source contains the default and alternate work-schedule lifecycle, per-task assignment, full saved-view depth and lifecycle, import-disclosure, multi-schedule Plan Health, dependency, critical-path, Home Edit, change digest, weekly review, explainable auto-plan/scenarios, baselines, portfolio rollups, timestamped exports/print, and mutation/Undo journeys. It also contains the adaptive Plan ledger, atomic Board batch move with a drag accelerator, Timeline pre-commit preview with Move-to-date and keyboard equivalents, and Gantt move/resize by field, increment, keyboard, pinch, overview strip, and non-overlapping 48 dp targets. Section 8D remains the separate external evidence boundary; it is not an implementation backlog.

## 1. Product job, audience, and success

### Product job

Daily Brief helps a person answer three questions without rebuilding the same context in separate tools:

1. What is happening now and next?
2. What time is already committed?
3. Where can flexible work fit, and what will change if it moves?

The distinctive product is not a collection of dashboards. It is a calm mobile planning workspace where fixed commitments and flexible work share one time model but retain different ownership and interaction rules.

### Primary audience

The primary user has a busy calendar and a mutable task plan, often works on a phone, and needs to make quick changes without accidentally editing provider-owned events or losing planning state. They may later use a tablet, foldable, keyboard, mouse, switch device, or screen reader. Those modes are part of the same product, not secondary versions.

### Outcome measures

A release candidate should demonstrate:

- all critical tasks below are reachable without a hidden gesture;
- Calendar and Plan are never confused about which records own time;
- the same selected date and active Plan remain understandable across views;
- interrupted, invalid, refused, or cancelled mutations leave no partial state;
- every app-owned destructive or direct-manipulation command is recoverable;
- compact, medium, expanded, large, and extra-large windows preserve task parity;
- TalkBack, Switch Access, Voice Access, keyboard, and touch can complete the same core journeys;
- premium features reduce planning uncertainty rather than merely adding visual density.

## 2. User tasks and end-to-end journeys

These tasks are the product-level test cases. Screen-level requirements are subordinate to them.

| ID | User task | Start and expected finish | Primary surface | Critical acceptance |
| --- | --- | --- | --- | --- |
| T1 | Orient to now | Open the app and understand current work, next commitment, clashes, and available time | Home | The answer is visible without opening Settings or decoding color alone |
| T2 | Inspect another date | Move to a date, compare Agenda, Timeline, and Week, then return to Today | Calendar | The selected date persists across the three representations and Today is always explicit |
| T3 | Capture the right object | Create either a task or event, switch type if needed, and keep shared draft text | Global create | Task and Event ownership is stated before save; title and notes survive a type switch |
| T4 | Organize work | Route a task from Inbox to a workflow column, change progress, add a subtask, or remove it | Outline / Board | Buttons and menus work without drag; group effects are stated before commit |
| T5 | Schedule flexible work | Add or split one Plan item into blocks around fixed commitments and working time | Gantt | The result is a solid Plan block; fixed commitments remain outlined and non-draggable in Plan |
| T6 | Reschedule safely | Move or resize app-owned work, inspect the proposed date and impact, apply once, and Undo | Timeline / Gantt | Gesture, fields, accessibility actions, and keyboard commands call the same validation and transaction |
| T7 | Recover from error | Cancel a gesture, correct a validation error, Undo a command, or handle a provider refusal | Any write surface | No false success, no partial persistent change, focus remains useful, and the draft or prior state survives |
| T8 | Review feasibility | See capacity, overload, dependency risk, slack, and the reason a plan does not fit | Home / Gantt / Plan Health | Calculations name their inputs and distinguish facts, assumptions, and suggestions |
| T9 | Reopen a working view | Save filters, grouping, sort, collapsed state, range, zoom, and board context | Plan | A named view survives process death and cold restart; malformed saved state fails closed |
| T10 | Work on another form factor | Continue the same task after resize, rotation, fold, split-screen, or keyboard attachment | All roots | No task disappears because layout changed; current selection and unsaved draft are preserved |
| T11 | Export or restore | Export or restore planning data and understand what was included | Settings / Plan | Scope, sources, range, generated time, and omissions are explicit |

## 3. Conceptual model

The interface should teach this model through names, shapes, placement, and constraints.

| Concept | Meaning | Visual and interaction rule |
| --- | --- | --- |
| Calendar commitment | A local or provider-sourced event that already occupies time | Outlined in Plan/Gantt; shows source and edit capability; never treated as a movable Plan bar |
| Plan item | App-owned flexible intent with hierarchy, status, effort, due date, priority, and owner | Lives in Outline and Board; does not occupy time until scheduled |
| Plan block | App-owned allocation of a Plan item to a time interval | Solid bar on the time spine; may be moved or resized only through validated, recoverable commands |
| Plan board / column | Stable project and workflow state | Board movement changes workflow, not time; active board stays visible in every Plan representation |
| Working schedule | Reusable availability, exceptions, buffers, time zone, and chunk limits | Background planning constraint; not rendered as a calendar commitment |
| Dependency | A typed relation between Plan items | Visible direction, type, lag, affected successors, and conflict explanation |
| Saved view | Named presentation state | May change visibility, grouping, sort, range, zoom, and collapse only; never changes task data |
| Scenario | A preview set of proposed commands | Clearly marked as unapplied; can be compared, applied once, or discarded with no live mutation |
| Mutation journal | Durable record of an applied app-owned command | Enables exact Undo, status, origin, and later history without reconstructing intent from current rows |

### Non-negotiable invariants

1. A Plan item is never persisted as a BriefingEvent merely to make it appear on a timeline.
2. A provider-owned commitment never becomes a draggable Plan record.
3. Board movement changes workflow state; Gantt movement changes time allocation. A gesture cannot silently do both.
4. Working availability is not busy time.
5. A Saved View cannot mutate Plan content.
6. A scenario cannot write until Apply.
7. A cancelled or invalid gesture produces no data mutation and no applied journal entry.
8. A successful app-owned command and its journal row are one atomic transaction.
9. A provider refusal or ambiguous provider result is not success; the local row is restored or visibly pending until ownership is resolved.
10. Color reinforces type or status but is never its only signifier.

## 4. Which “seven HCI principles” govern this plan

The phrase “seven HCI principles” is ambiguous. This plan uses two different seven-part frameworks for different purposes.

[HCI_PRINCIPLES.md](HCI_PRINCIPLES.md) applies both frameworks to the code as an audit rather than a crosswalk: it grades what it finds, and the five defects it produced — Back destroying an unsaved preview, editors refusing to save without saying why, a silent move preview, a thirty-second ceiling on all recovery, and direct movement with no visible way in — are fixed and guarded by named tests. Read that document for the findings; this section stays as the mapping.

### Primary acceptance qualities: ISO 9241-110

[ISO 9241-110:2020](https://www.iso.org/standard/75258.html), confirmed current by ISO in 2025, defines interaction principles for interactive systems. The official record and [ISO Online Browsing Platform entry](https://www.iso.org/obp/ui/en/#iso:std:iso:9241:-110:ed-2:v1:en) are the normative references. The publicly available [UXQB CPUX-F curriculum](https://uxqb.org/public/documents/CPUX-F_EN_Curriculum.pdf) lists and explains the same seven principles.

These are the primary acceptance qualities. A feature is not accepted by averaging them; a serious failure in any applicable quality blocks the journey.

| ISO interaction principle | Daily Brief acceptance question | Required evidence |
| --- | --- | --- |
| Suitability for the user’s tasks | Does the design directly support T1–T11 without administrative detours or duplicate data entry? | Task-based journey tests and moderated task observation |
| Self-descriptiveness | Can a person tell where they are, what an object is, what actions exist, and what happened? | Visible labels, ownership cues, semantics dump, status and error-state review |
| Conformity with user expectations | Do Back, navigation, selection, time, save, cancel, and edit behave consistently with Android and calendar/planning conventions? | Navigation tests, state tests, and usability review |
| Learnability | Can a first-time user discover the core model and controls without memorizing gestures? | Visible controls, first-run content review, and first-use task study |
| Controllability | Can the person start, stop, inspect, change, cancel, and Undo operations? | Non-drag alternatives, cancellation tests, previews, and durable Undo tests |
| Use error robustness | Are errors prevented where possible and recoverable when they occur? | Validation, transaction, provider-refusal, process-death, and duplicate-Undo tests |
| User engagement | Does the workspace maintain trust, clarity, meaningful progress, and appropriate personalization? | Comprehension and confidence findings; no dark patterns or artificial gamification |

ISO explicitly concerns interaction, not aesthetic branding. The paper-and-ink visual direction is valuable only when it improves these qualities.

### Design mechanisms: Norman’s seven fundamentals

Don Norman’s seven fundamentals in [The Design of Everyday Things, page 72](https://books.google.com/books?id=qBfRDQAAQBAJ&pg=PA72) are Discoverability, Feedback, Conceptual Model, Affordances, Signifiers, Mappings, and Constraints. The [publisher page](https://www.hachettebookgroup.com/titles/don-norman/the-design-of-everyday-things/9780465050659/) identifies the edition used here. Norman’s own explanation, [Signifiers, Not Affordances](https://jnd.org/signifiers-not-affordances/), is important: a visible cue is a signifier; an affordance is a possible relationship between person and object.

Norman’s seven are mechanisms for producing the ISO qualities, not a substitute list and not an official one-to-one ISO mapping. The crosswalk below is a Daily Brief design inference.

| Norman mechanism | Primary ISO qualities supported | Daily Brief implementation |
| --- | --- | --- |
| Discoverability | Self-descriptiveness, Learnability, Suitability | Stable roots, labeled subview switchers, visible Today, 7/30/90 ranges, per-row add, Board arrows, Move menu, and visible Select |
| Feedback | Self-descriptiveness, Controllability, Use error robustness, User engagement | Press/selected state, exact drag preview, working-time and dependency impact, saving state, success or failure status, and Undo |
| Conceptual Model | Conformity, Learnability, Suitability | Calendar commitment → fixed outlined bar; Plan item → flexible intent; Plan block → solid scheduled allocation; Saved View → presentation only |
| Affordances | Suitability, Learnability, Controllability, User engagement | Buttons are tappable areas, handles can be grabbed, bars can be selected, fields can be edited, and scrollable canvases expose scroll context |
| Signifiers | Self-descriptiveness, Learnability, User engagement | Text labels, shape, border style, source badges, handle marks, selection outlines, headings, state descriptions, and cursor/focus treatment |
| Mappings | Conformity, Suitability, Controllability | Left/right changes time left/right; vertical Board order matches workflow order; Today aligns to the time spine; resize edges affect the corresponding start or end |
| Constraints | Use error robustness, Controllability, Suitability | Fixed commitments cannot move in Plan; milestones cannot have duration blocks; locked blocks resist automation; invalid dates, cycles, overlaps, and permissions fail closed |

User engagement is broader than any single Norman mechanism. In this product it means trust, momentum, readable density, explainability, and user-controlled personalization—not badges, streaks, urgency tricks, or paywalls around safety.

## 5. Chosen organization and placement

### Structural visual direction

Keep the warm paper, dark ink, restrained terracotta, teal, and deadline palette already established in the premium plan. Keep decoration quiet. The signature motif is the continuous time spine:

- Calendar header establishes the selected date.
- Timeline adds the live-now line and real-time gaps.
- Gantt carries the same date context into Plan blocks, fixed commitments, date ticks, working-time shading, dependency paths, and Today.
- Solid versus outlined ownership is reinforced with labels and semantics.

Atkinson Hyperlegible and IBM Plex Mono remain candidates only after font licensing, glyph, weight, fallback, rendering, and 200% text QA. The current font must not be described as changed until those checks and assets exist.

### Navigation decision

| Decision | Chosen placement | Why this is the best option | Rejected alternative |
| --- | --- | --- | --- |
| Primary navigation | Home / Calendar / Plan in bottom bar; same three in a rail from 600 dp | Stable, thumb-reachable roots with consistent identity across window sizes | Five or more roots fragment date and Plan context |
| Calendar representations | Agenda / Timeline / Week segmented control below the Calendar header | One selected date, three ways to inspect it | Separate root tabs or hidden overflow |
| Plan representations | Outline / Board / Gantt segmented control below the Plan header | Hierarchy, workflow, and schedule stay one tap apart | A separate premium Gantt root |
| Global search and commands | Top-right on every root | Predictable and global without consuming a primary destination | Search as a root tab or FAB |
| Settings | Top-right beside Search, secondary route | Discoverable but lower frequency | Settings in primary navigation |
| Global create | One contextual bottom-right FAB | One dominant write action; Calendar creates Event, Plan creates Task, Home defaults to Event with type switch | Multiple competing FABs or a hidden long-press menu |
| Local create | Labeled section action or trailing row plus | Preserves routing context | Reusing the global FAB for every nested action |
| Board to Gantt | Labeled Schedule action beside the active Plan selector | High-value cross-view jump stays near the selected board | Buried card overflow or fourth root |
| Board move | Adjacent left/right buttons plus Move task group to… menu | Fast and accessible; establishes the required non-drag path before drag exists | Horizontal swipe, which conflicts with Board scrolling and is hard to discover |
| Gantt schedule | Trailing add control on each Plan row | Scheduling remains possible without dragging | Tap-empty-canvas as the only path |
| Today and date jump | Calendar/Timeline header and Gantt time-spine header | The control sits beside the context it changes | Global toolbar or hidden double tap |
| Gantt range | Visible 7/30/90 controls; later pinch around focus | Discoverable baseline plus expert accelerator | Pinch-only zoom |
| Sync | Pull-to-refresh plus command palette | Familiar gesture with a labeled alternative | Permanent root or unannounced pull-only action |
| Home customization | Visible Edit/Done in the Home header, with the same show/hide and Move up/down controls retained in Settings | The action sits beside its result while preserving a fallback route | Long-press-only edit mode |

### What to add, keep, merge, and remove

Present in the current source tranche:

- a first named Saved View control that saves the Plan surface and Gantt range, applies it, deletes it, and rejects malformed stored state;
- a persisted default working week with IANA time zone, split weekly windows, date exceptions, chunk limits, commitment buffer, saveable editor draft, and explicit save;
- per-task schedule inheritance or explicit assignment in the Task editor, journaled independently with Undo and enforced by Gantt block validation;
- partial-day non-working shading that keeps split shifts, exceptions, and DST, plus a working-calendar-aware Plan-block preview;
- a durable 30-second Plan Undo surface plus recent History, including aggregate board/column catalog changes;
- a migration-only legacy-import disclosure and a Plan-native Home Board summary;
- compact Plan Health on Home and an explanatory seven-day detail view in Plan;
- a title-first, non-drag dependency ledger for add, edit, and delete, plus critical-path calculation and Gantt highlighting;
- Timeline source/capability wording, app-owned Move 15 minutes earlier/later and Move-to-another-date accessibility actions, a keyboard path, one shared pre-commit preview, and exact stale-guarded move Undo;
- a visible Agenda Actions menu alongside swipe and accessibility actions;
- five explicit width classes, an Agenda/Timeline wide Calendar desk, and a wide Plan ledger;
- visible Home Edit/Done layout controls and Board Select/multi-select whose hierarchy-aware move commits as one atomic journaled step;
- alternate reusable working schedules with create, rename, default transfer, and replacement-checked archive, each journaled with Undo;
- combined multi-schedule Plan Health capacity that unions availability on the absolute time line;
- Gantt move mode with fields, 15-minute increments, arrow keys, custom actions, and three non-overlapping 48 dp manipulation targets.

Still add:

- dependency paths, slack visualization, minimap, and pinch zoom;
- advanced Saved View fields and management: filters, grouping, sort, columns, collapse, pin, rename, duplicate, and update;
- Outline expand/collapse and selection semantics;
- explainable auto-plan/scenarios, baselines, portfolio history, restore, and export.

Keep:

- three roots and two subview switchers;
- one contextual FAB;
- selected-date continuity;
- 7/30/90 range buttons;
- Board arrows and Move menu now that drag exists;
- per-row Gantt scheduling;
- solid Plan / outlined fixed ownership contrast;
- quick capture’s Task/Event switch and preserved shared text.

Merged or replaced in the current source:

- Home’s Board summary now projects the active Plan board, columns, and tasks rather than treating BriefingEvent rows as flexible work;
- the Plan shell now hosts Saved Views, Plan Health, recent History, and exact Plan Undo in one consistent context;
- current Plan item, block, dependency, board, and column writes emit the shared journal-backed Undo message path.

Still merge or replace:

- merge duplicate date labels into one clear date context per Calendar surface;
- consolidate filters, grouping, sort, zoom, and collapse under the named View control rather than scattering independent icons;
- bring non-journal event status and Plan journal status into one carefully worded command vocabulary without implying provider-owned Undo.

Remove or avoid:

- Settings, Search, Timeline, Board, or Gantt as additional root destinations;
- decorative card walls that hide chronology or ownership;
- icon-only high-value commands when a short label fits;
- root-switching swipes;
- drag-only, swipe-only, pinch-only, hover-only, or color-only behavior;
- draggable fixed commitments in Plan;
- silent provider fallbacks, silent normalization, and false-success messages;
- a second competing primary create action.

## 6. Gesture and input contract

Gestures accelerate a complete visible interaction model. [WCAG 2.5.7 Dragging Movements](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html) requires a single-pointer, non-drag method for drag functionality; [technique G219](https://www.w3.org/WAI/WCAG22/Techniques/general/G219.html) uses a Kanban example. Android recommends [custom accessibility actions](https://developer.android.com/develop/ui/compose/accessibility/semantics#custom-actions) for gesture-heavy controls.

| Surface | Gesture accelerator | Visible and accessible equivalent | Commit and cancellation |
| --- | --- | --- | --- |
| Agenda | Horizontal swipe on a row to defer or delete | Edit/menu action plus custom action; delete remains labeled | Action label appears before threshold; release commits once; provider refusal restores row |
| Agenda / Timeline | Horizontal page swipe to previous/next date | Previous day, Next day, Today, and date picker | Never changes root or Calendar subview |
| Timeline event | Long-press then vertical drag | Tap Edit with date/start/end fields; custom Move 15 minutes earlier/later and Move to another date actions | Preview exact time and ownership; cancel changes nothing; release opens the preview and only Apply commits one command, then offers Undo |
| Board task | Long-press then drag between columns, with edge auto-scroll | Left/right buttons, Move to… menu, keyboard command, and custom actions | Preview destination and group effect; invalid target rejected before write; release stages the same atomic batch command the buttons use |
| Gantt canvas | One-finger horizontal pan | Scroll controls, date/range controls, keyboard horizontal scroll | Pan never edits data |
| Gantt zoom | Later pinch around focus | 7/30/90 buttons and keyboard zoom | Zoom changes presentation only and keeps the focus date under the gesture |
| Gantt Plan bar | Long-press to enter move mode, then drag the bar or either boundary handle | Date/time fields; Move/Extend/Shorten increments; arrow keys with Shift/Alt; custom actions on the bar, both handles, and the panel | Preview date, working-time effect, conflicts, dependency effect; releasing a pointer never saves; only Apply commits once and offers Undo |
| Home layout | Later direct reorder | Move up/down controls and Edit mode with Done | No implicit save on accidental long press |

Additional rules:

- One-finger scroll must win over a drag until move mode is explicitly entered.
- A selected bar gets a visible outline, labeled handles, exact dates, and a Cancel action.
- Manipulation targets may not overlap each other or the bar they act on. A one-hour block is under 3 dp wide at the 7-day zoom, so the move target and its two 48 dp handles are laid out as neighbours rather than at their literal boundaries, and the preview panel takes layout space instead of floating over the canvas. A target drawn above another target silently steals the command the person aimed at.
- Fixed commitments never enter Plan move mode. Their tap action is Inspect/Edit at source capability.
- Pointer actions execute on release, can be aborted before release, and provide Undo when cancellation is no longer possible, following [WCAG 2.5.2 Pointer Cancellation](https://www.w3.org/WAI/WCAG22/Understanding/pointer-cancellation.html).
- Haptic feedback supplements, but never replaces, visible and spoken state.
- Reduced-motion preference removes unnecessary translation/zoom animation without removing feedback.

## 7. Screen-by-screen source audit and target

The “observed” column reflects the source tree at this document’s timestamp. It is not a runtime claim.

| Surface | Observed in current source | HCI gap or risk | Chosen target |
| --- | --- | --- | --- |
| App shell | DailyBriefApp defines Home, Calendar, and Plan as primary roots; bottom navigation becomes a rail; each root is held by a saveable state provider; one contextual FAB opens shared editors. Layout.kt implements Compact, Medium, Expanded, Large, and ExtraLarge at the official boundaries | The five buckets and shell are source-complete; exact boundary resize, focus, and 200% text proof is still limited to the recorded device tests | Keep the shell and five classes; preserve exact root/subview/selection state through resize |
| Home | HomeScreen shows now/next, booked progress, conflicts, quick Timeline/Search/Sync actions, a Plan-native active-board summary, compact fail-closed Plan Health, and visible Edit/Done layout controls with show/hide and Move up/down | Portrait was inspected, but 200% text, full adaptive boundaries, assistive technology, and task comprehension remain unproved | Keep the Plan-native summary, Health signal, and inline editor; retain Settings as the equivalent fallback |
| Calendar shell | CalendarScreen keeps Agenda, Timeline, and Week under one root. Expanded, Large, and ExtraLarge windows show a stable Agenda-left/Timeline-right desk whose selected representation changes emphasis rather than order | Week remains single-pane, and live-resize/state/focus proof is pending | Keep one root and fixed pane mapping; verify every breakpoint and add only task-useful wide refinements |
| Agenda | TodayScreen has grouping, pull-to-refresh, labeled swipe backdrops, a source badge on rows that cannot be edited here, accessibility actions, and a per-row overflow menu with capability-filtered Open, Move, and Delete commands | Grouping is not part of a named Saved View; gesture/menu parity and provider success/refusal wording still need rendered/device tests | Keep menu, gesture, and custom-action parity; persist grouping only as presentation state and report provider outcomes exactly |
| Timeline | DayTimelineScreen limits long-press drag to app-owned events, labels other sources FIXED, exposes Move 15 minutes earlier/later and Move to another date custom actions plus a keyboard path, and routes every one of them into the same saveable pre-commit preview with an exact stale-guarded snapshot Undo | A stale or externally owned row fails the preview closed, which is correct but has only automated proof; provider-owned edit/refusal still relies on the editor path | Keep every input on the one preview-then-Apply path; prove the refusal wording on a physical provider |
| Week | WeekScreen shows a configurable span, conflicts, collapsible days, open-day actions, and saveable in-composition collapse state | Collapse state is not a named durable view and is lost on cold restart | Store collapse in view state; preserve selected day; keep a one-tap path into Agenda |
| Plan shell | PlanScreen exposes named Saved Views with rename, update, duplicate, an exclusive per-board pin and reset, Plan Health, recent mutation History with unexpired Undo, and migration-only legacy-import disclosure. A view stores the surface, Gantt range, Outline order, hide-done filter and folded branches, and restores them together. Malformed Saved Views are omitted fail-closed. Expanded and wider windows move the secondary planning controls into a ledger beside the workspace | Grouping and column presets remain unused because no surface has columns to preset; a pinned view applies once per board switch by design | Keep the visible Plan-level controls and the presentation-only rule: a view may reorder and hide, never edit |
| Plan Outline | PlanOutlineScreen uses stable Plan data, Inbox/workflow sections, hierarchy, task metadata, and an expanded Inbox + workflow composition. Every parent carries a labelled fold control that reports how many subtasks it hid, and the surface can be ordered by plan order, due date, priority or effort and filtered to hide finished work — all persisted and captured by saved views | Multi-select lives on Board rather than here; hiding a finished parent surfaces its unfinished subtasks as orphan rows, which is deliberate and worth a usability check | Keep folding, ordering and filtering presentation-only |
| Plan Board | BoardScreen is Plan-native; it shows Inbox/workflow/Needs-routing lanes, explicit arrows, Move to… menu, group movement wording, per-column add, Schedule jump, and visible Select/Done multi-select. Batch preview includes linked hierarchy and every move now commits as one atomic Plan History step that a compare-and-set Undo reverses whole. Long-press drag with edge auto-scroll stages the same command the buttons and menu stage | Saved View board-state fields and a wide Board overview are still missing; drag remains an accelerator that no journey requires | Keep buttons, menu, and custom actions canonical; add board state to Saved Views before any further Board surface work |
| Plan Gantt | GanttScreen has persisted 7/30/90 range, named surface/range views, partial-day non-working shading that keeps split shifts, exceptions, and DST, per-task schedule resolution, fixed-commitment/dependency preview, transactionally revalidated block saves, journal Undo, a non-drag dependency ledger, fail-closed critical-path summary/highlighting, and drawn dependency links plus slack bands that disclose whatever they could not draw. Move mode adds date/time fields, 15-minute increments, arrow keys with Shift/Alt, custom actions, and bar/handle dragging over three non-overlapping 48 dp targets, with the panel taking layout space and standing explanations stepping aside so the canvas keeps its height | No pinch or minimap; the focused move mode and the drawn links have device-test proof but no manual assistive-technology pass | Add pinch and minimap only once they agree with the same repository commands |
| Task/Event editors | Shared capture preserves common text while retaining type-specific drafts; the Task editor can inherit the default or explicitly assign any active work schedule with its own journaled Undo; save state survives recreation. User-facing task Delete archives a leaf row for exact Undo instead of physically deleting it | Validation failures are still often global rather than attached to the field that failed; provider event recovery is intentionally separate from the Plan journal | Keep ownership and schedule wording explicit; attach errors to fields, focus the first error, and preserve exact provider-versus-app wording |
| Search / command palette | Global entry is consistently top-right and command palette supports capture/navigation/sync | Query and result context are transient; command availability/capability semantics need coverage | Keep placement; add recent commands only if locally controlled; expose disabled reasons and keyboard shortcuts |
| Settings | Supports theme, density, integrations, Home/Agenda reordering, explicit move controls, and a controlled persisted Working Week editor with IANA zone, split windows, date exceptions, buffer, and chunk limits. A schedule manager creates, edits, renames, transfers the default, and archives alternate reusable schedules; archiving a default demands an explicit replacement and reroutes its assignments in one transaction. Its Serializable state is held with rememberSaveable, and initial absence is labeled as loading rather than a false error | Integration, validation, and assistive-technology states need broader rendered/manual review; the manager has automated 200% text proof but no manual screen-reader pass | Keep schedule policy configuration here and retain Settings as a non-gesture fallback |

## 8. Evidence ledger

### A. Present in the source tree

The following integrated paths are observable in the current tree and covered by the automated/device evidence in Section 12 unless a narrower limit is stated:

- `DailyBriefApp.kt` contains the three-root shell, bottom-bar/rail switch, one contextual FAB, per-root saveable state, a shared Plan/event Undo snackbar host, and task/event draft savers.
- `Layout.kt` implements the five official width buckets; `CalendarScreen.kt` uses Expanded, Large, and ExtraLarge for a stable Agenda-left/Timeline-right composition.
- `WorkingCalendarMapper.kt`, `PlanRepository.kt`, `BriefingViewModel.kt`, `SettingsScreen.kt`, and `WorkingScheduleEditor.kt` form an end-to-end default Working Week path. Weekly windows, date exceptions, IANA zone, buffer, and chunk limits are validated, saved atomically, observed, and edited through saveable state. Task editing can inherit the default or explicitly pin an active schedule with an independent journaled Undo.
- `PlanBlockPreview.kt` is used before save and again inside `PlanRepository.saveBlockWithUndo`; the exact task-resolved schedule, fixed commitments plus buffer, active blocks, locks, milestones, and dependencies can block the transaction before any row or journal entry is written.
- `PlanMutationCodec.kt` and `PlanCatalogMutationCodec.kt` cover Plan items, groups, progress, blocks, dependencies, task schedule assignment, boards, and columns. User-facing leaf-task Delete writes an archived snapshot rather than physically deleting the row, preserving blocks for exact Undo. Current UI write paths return mutation IDs, the snackbar offers a bounded Undo window, and Plan History can invoke compare-and-set Undo while the entry remains unexpired.
- `SavedViewCodec.kt`, DAO/repository methods, ViewModel state, and `PlanScreen.kt` expose the first named Saved View. It saves/applies/deletes the Plan surface and Gantt range, and malformed records are omitted fail-closed.
- Migration 5→6 records that the legacy catalog was imported; `PlanScreen.kt` discloses retained board/column names without claiming calendar rows became tasks, while fresh v7 seeds do not show a false migration notice.
- `HomeScreen.kt` projects its Board summary from active Plan records, includes compact fail-closed Plan Health, and exposes in-context Edit/Done layout controls while Settings retains the same fallback controls. `PlanScreen.kt` exposes the seven-day explanation, contributing inputs, warnings, risks, and suggested repairs.
- `MultiSchedulePlanHealth.kt` combines capacity across differing per-task schedules: availability is unioned on the absolute time line and assigned to schedule-specific demand with a maximum-flow calculation, so two overlapping schedules yield one hour of human capacity rather than two. `PlanHealthSchedulePolicy.kt` still resolves every active task strictly first — an unresolved assignment, a duplicate ID, or a missing active default refuses the result instead of estimating one.
- `DependencyManager.kt` provides a visible title-first add/edit/delete ledger for FS, SS, FF, and SF relations with signed lead/lag. Repository validation rejects self-links, duplicates, cross-board links, and cycles, and dependency mutations join the journal.
- `CriticalPathEngine.kt` computes a fail-closed critical path from typed dependencies and durations; `GanttScreen.kt` reports availability and highlights critical task bars without pretending that dependency paths or slack graphics exist.
- `DayTimelineScreen.kt` exposes source capability, limits dragging to app-owned events, and supplies exact ±15-minute and Move-to-another-date custom accessibility actions plus a keyboard path. Every input lands on the same saveable pre-commit preview naming the exact times and conflicts; releasing a drag opens that preview rather than saving, and a preview whose row changed underneath fails closed. `moveEventTo` offers an exact before-snapshot Undo guarded against overwriting a newer edit.

- `GanttScreen.kt` move mode carries the same rule into direct manipulation. `GanttDirectManipulationTargets` lays the move target and both 48 dp handles out as neighbours because a one-hour block is under 3 dp wide at the 7-day zoom, the preview panel takes layout space instead of floating over the canvas, and the standing header explanations step aside while a block is being moved. Pointer release, Cancel, and pointer cancellation all write nothing; only Apply commits, once, with journal Undo.

- Transient modes own the Back gesture: Gantt move mode, Board Select, and Home layout editing each cancel themselves rather than letting Back navigate away and discard the work. Escape does the same for a keyboard in move mode.

- `EditorSaveGuard.kt` gives both editors a diagnosis: the field that blocks Save carries the sentence that fixes it, and a notice beside Save says what Save is waiting for and announces itself. Save remains disabled while a draft is invalid, but never silently.

- `UndoWindowPolicy.kt` makes the recovery window a setting — 30 seconds, 5 minutes, 1 hour, or 24 hours, defaulting to 5 minutes — read at write time by both journal writers. The snackbar stays brief; Plan History is the durable path.

- The Gantt block editor exposes `Move on map`, so direct movement is reachable without knowing the long-press.

- `SettingsScreen.kt` and `PlanRepository.kt` complete the working-schedule lifecycle: create, edit, rename, transfer the default, and archive, each as its own journaled command with Undo. Archiving the default requires an explicit replacement and reroutes every assignment in one transaction, and a create Undo goes stale rather than orphaning tasks once the new schedule has been assigned.

- `PlanScreen.kt` composes a wide Plan workspace: at Expanded and wider windows the secondary planning controls move into a ledger beside the workspace instead of stacking above it, without duplicating navigation state.
- `TodayScreen.kt` supplies a labeled Actions menu as the visible counterpart to row swipes and custom accessibility actions, filtered by event ownership.
- `BoardScreen.kt` exposes Select/Done, per-task selection, Select all/Clear, and a destination preview. `PlanBatchMovePolicy.kt` previews the exact hierarchy scope and rejects cyclic, cross-board, and unavailable inputs before anything is written; the repository then commits every affected row and one journal entry inside a single transaction, so a failure part-way writes nothing and Undo restores every original placement. Long-press drag with edge auto-scroll stages the same command as the arrows and the Move to… menu.
- `GanttScreen.kt` persists its range, shades non-working time inside a day rather than only whole days, resolves task-specific policy per block, previews scheduling issues and downstream effects, exposes dependencies without drawing, and keeps fixed commitments outlined and non-draggable. Loading and default labels do not claim a schedule is unavailable or default before that fact is known.
- Migration, repository, codec, projection, layout, accessibility, and rendered-journey tests passed in the recorded automated/device layers; Section 12 gives the exact counts and limits.

### B. Automated/device evidence passed; broader proof still pending

The current tree passed diff hygiene, JVM and Android-test compilation, the named suites of
272 planning-core, 26 contract, 57 server, and 184 app unit tests, 134 full instrumentation
tests, both lints, debug/test/release assembly, and R8 analysis. That evidence is real but bounded:

- the instrumentation suite covers implemented recreation/journal journeys, but not every process-death moment in the acceptance contract;
- automated semantics do not substitute for TalkBack, Switch Access, Voice Access, keyboard, or usability proof;
- the rendered inspection covers portrait Home, Calendar, Plan, Gantt, and Settings plus one landscape Expanded Calendar, which does not prove every state or the full boundary/device matrix;
- five width classes with two proved wide compositions do not prove exact boundary behavior or a complete adaptive product;
- Saved Views now store the outline order, grouping, visible Board columns, hide-done filter, and folded branches as well as the surface and range;
- dependency paths, rendered slack, pinch zoom, the overview strip, weekly review, auto-plan, scenarios, baselines with variance and restore, portfolio rollups, and export are built and each carries a named test; none of them has been watched in a real week by a person who did not build it;
- multi-schedule capacity is proved by unit and device tests over constructed schedules, not by a person planning a real week across two of them;
- API 37.1/16 KB, physical provider behavior, full manual accessibility, and user studies remain separate evidence requirements; API 24 now has a local rendered capture in addition to the CI instrumentation matrix.

### C. Repository-contained remaining work

Every feature item from the previous revision of this list is built, and the three coverage items
that replaced them are now closed too:

1. ~~Adversarial multi-schedule overlap cases.~~ `MultiSchedulePlanHealthTest` now includes the cases where naive allocation gives the wrong answer: a wide schedule starving a narrow one's only hour, three schedules that each fit alone while the person does not, a commitment landing on the only shared hour, a schedule with no hours in range, and a chain of partial overlaps. The engine was already right; it is now proved right.
2. ~~Empty and refusal states for the analysis surfaces.~~ `PlanAnalysisEmptyStatesInstrumentedTest` and `PlanBaselineRepositoryInstrumentedTest` cover the empty list, the empty rollup, the dialog that stays shut, and every refusal a baseline can issue. The wider state matrix across *older* screens is still open.
3. ~~Baseline cost at portfolio scale.~~ 800 tasks and 2,400 blocks encode, decode and compare in about 0.1 s (`BaselineVarianceTest`), which matters because a baseline is taken inside the schedule mutex.

What is still repository-local and open:

1. ~~The rendered empty, loading, permission, provider-refusal, conflict and offline states of the
   older screens.~~ The capture harness now covers the Android empty, permission, provider-owned,
   conflict, baseline, scenario, and wide-screen states in 25 deterministic plates. An Android
   transport-offline plate is not applicable because the local calendar path is provider-backed;
   mobile cache serialization, key rules, web-storage reload, modeled native-adapter behavior,
   corruption cleanup, atomic replacement, and failed-write posture are covered by the three
   focused mobile cache test files; native file persistence and offline/restart behavior remain
   device evidence.
2. ~~A rendered inspection at API 24 to match the CI instrumentation run there.~~ The local
   `dailybrief_api24_render` AVD produced 25 phone and wide-state plates through
   `scripts/capture-preview.sh`; representative phone, wide Calendar, and wide Plan artifacts
   were inspected. API 24 uses the capture harness's bounded `screencap -p` fallback because its
   framework lacks the PixelCopy overload used by Compose.

Closed since the last revision, each with a named test: saved-view depth, lifecycle, grouping and
column presets; Outline fold/filter/sort and multi-select; the exact width-boundary matrix;
field-level editor validation; the adjustable undo window; Back leaving a mode rather than the
screen; the announced move preview; a visible way into direct movement; the Board's Alt+Arrow
shortcut, which moved the focus ring instead of the task; pinch zoom and the overview strip; drawn
dependency links with slack; weekly review; CSV and calendar export; explainable auto-plan and
scenario comparison; baselines with variance and restore (schema v8); and portfolio rollups. See
[HCI_PRINCIPLES.md](HCI_PRINCIPLES.md).

### D. External or device proof

These cannot be honestly completed by source inspection alone:

- rendered phone portrait, phone landscape, foldable, tablet, large tablet, desktop/Chromebook, and split-screen review;
- API 37.1 with 16 KB page-size execution; the recorded local device run covered API 36, and API 24 now has local rendered evidence; CI still runs the same suite on API 24;
- physical-device calendar read/write behavior for supported providers and read-only/refused calendars;
- TalkBack linear navigation and action menu;
- Switch Access scanning and selection;
- Voice Access label targeting;
- hardware keyboard, mouse, trackpad, and stylus behavior;
- 200% font size, display scaling, dark mode, high contrast, reduced motion, RTL, and at least one long-localized-text pass;
- process death during edit, drag preview, commit, provider write, and Undo;
- font license/assets and glyph-rendering proof if typography changes;
- task-based usability sessions covering T1–T11, including at least one screen-reader or switch user before accessibility sign-off.

## 9. Exact Android and WCAG acceptance contract

### 9.1 Touch and pointer targets

[Android Compose accessibility guidance](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) sets a minimum 48 by 48 dp touch target for interactive elements. [WCAG 2.5.8 Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) sets a 24 by 24 CSS pixel web minimum with defined exceptions. Daily Brief uses the stricter native Android product bar:

- every interactive semantics node is at least 48 dp wide and 48 dp high;
- this includes icon buttons, checkboxes, chips, segmented choices, bar hit areas, resize handles, row plus controls, overflow triggers, date cells, and empty-canvas create targets;
- visual art may be smaller inside the 48 dp hit area;
- expanded hit areas must not overlap an adjacent action or cause ambiguous focus;
- dense mode may reduce visual spacing but never the interactive bounds;
- automated Compose accessibility checks inspect every screen/state, and manual bounds inspection covers custom pointer-input elements;
- touch, mouse, keyboard, switch, and accessibility actions must target the same logical command.

### 9.2 No gesture-only or drag-only action

For every drag, swipe, pinch, long press, or pull gesture:

- a single-tap or sequential-tap path exists on the same surface or its explicit editor;
- the alternative is visible or discoverable from a labeled menu;
- TalkBack exposes a custom action when direct manipulation is relevant;
- keyboard users get focusable commands with the same increment and validation;
- the alternative and gesture call the same command object and produce the same result;
- a drag can be aborted before release;
- a cancelled, below-threshold, invalid, interrupted, or lost-pointer gesture performs zero persistent mutation;
- release causes at most one commit;
- the result is announced and offers Undo when the action is app-owned and reversible.

Required equivalence examples:

- Board drag ↔ left/right buttons ↔ Move to… ↔ custom action;
- Timeline drag ↔ edit date/start/end ↔ Move ±15 minutes ↔ custom action;
- Gantt move/resize ↔ schedule fields ↔ Move/Extend/Shorten increments ↔ custom action;
- pinch ↔ 7/30/90 range controls;
- Home reorder ↔ Move up/down.

### 9.3 Names, roles, values, relationships, and status

Compose semantics follow [Android’s semantics guidance](https://developer.android.com/develop/ui/compose/accessibility/semantics). The web-standard acceptance references are [WCAG 4.1.2 Name, Role, Value](https://www.w3.org/WAI/WCAG22/Understanding/name-role-value.html), [2.5.3 Label in Name](https://www.w3.org/WAI/WCAG22/Understanding/label-in-name.html), [1.3.1 Info and Relationships](https://www.w3.org/WAI/WCAG22/Understanding/info-and-relationships.html), [1.3.2 Meaningful Sequence](https://www.w3.org/WAI/WCAG22/Understanding/meaningful-sequence.html), and [4.1.3 Status Messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html).

Acceptance:

- every actionable node has a non-empty accessible name, correct role, enabled/disabled state, selected/checked/expanded state, and current value when applicable;
- the visible label text appears in the accessible name;
- decorative icons are excluded from semantics;
- icon-only actions name the action and object, such as “Move Research task group right” rather than “Arrow right”;
- Board columns, Plan hierarchy, Gantt groups, fixed commitments, and Plan blocks expose their relationships and meaningful reading order;
- a Plan block announces title, ownership, start, end, duration, lock, progress, and dependency/conflict state that applies;
- a fixed commitment announces title, source/capability, start, end, and “fixed commitment”;
- selection and move mode are announced before custom actions;
- save, sync, provider refusal, validation, preview, commit, and Undo status are announced without forcing focus away from the person’s work;
- after a dialog closes, focus returns to the invoking control or resulting item;
- semantics tests are paired with manual TalkBack, Switch Access, and Voice Access tests as required by [Android accessibility testing guidance](https://developer.android.com/guide/topics/ui/accessibility/testing) and [Compose automated accessibility checks](https://developer.android.com/develop/ui/compose/accessibility/testing).

### 9.4 Adaptive layout matrix

Use the current window, not the device label. Android’s [window size class guidance](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes) defines:

- compact: width below 600 dp;
- medium: width from 600 dp through 839 dp;
- expanded: width from 840 dp through 1199 dp;
- large: width from 1200 dp through 1599 dp;
- extra-large: width 1600 dp or greater.

Daily Brief may map large and extra-large to shared components, but it must test them explicitly and use their space deliberately. The quality bar also follows Android’s [adaptive optimized guidance](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2), [core app quality](https://developer.android.com/docs/quality-guidelines/core-app-quality), and [support for different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes).

Required matrix:

| Case | Exact viewport or boundary | Required composition and proof |
| --- | --- | --- |
| Compact phone | 360 × 800 dp and 599 dp boundary | Bottom navigation; single pane; all roots, controls, and editors usable |
| Medium landscape/small foldable | 600 × 360 dp and 839 dp boundary | Navigation rail; compact-height review; no clipped header/FAB/editor |
| Expanded foldable | 841 × 701 dp | Rail; dual-pane Calendar candidate; Outline Inbox + workflow; hinge/fold posture check |
| Expanded tablet | 1024 × 640 dp | Rail; useful dual-pane composition; keyboard/mouse path |
| Large tablet | 1280 × 800 dp | No stretched single column; centered reading and planning panes |
| Extra-large Chromebook/desktop | 1600 × 900 dp | Persistent rail, multi-pane Plan, keyboard focus, mouse/trackpad scrolling |
| Resizable boundaries | 599/600, 839/840, 1199/1200, and 1599/1600 dp | No lost selection, duplicate screen, focus jump, or unavailable command during live resize |
| Accessibility text | Every case at 200% font | Reflow without clipped text, overlapping actions, horizontal text scrolling, or lost controls |

At every size, Home/Calendar/Plan order and labels remain the same. A pane may appear or disappear, but no core task or command may exist only at a wider width.

### 9.5 State preservation

Follow [Android Compose state-saving guidance](https://developer.android.com/develop/ui/compose/state-saving). Keep small identifiers and user input in rememberSaveable or SavedStateHandle; restore larger data from Room or repositories rather than putting graphs or record lists into a Bundle.

After rotation, live resize, background recreation, and system-initiated process death, preserve:

- primary root and its back destination;
- Calendar or Plan subview;
- selected date and Today relationship;
- active Plan board;
- Gantt range and date anchor;
- vertical focus item and horizontal time anchor, restored by stable ID/time rather than raw pixel only;
- Outline/Week/Gantt collapsed IDs;
- Board/Gantt selection and visible Select mode when safe;
- filter, grouping, sort, density, zoom, and named Saved View identity;
- Task/Event capture type and all draft fields;
- editor origin and safe busy/completion reconciliation;
- pending app-owned command identity and latest unexpired Undo.

Cold restart additionally restores durable user choices: home root, Calendar/Plan subview, active board, named view, working schedule, and layout preferences. Ephemeral open menus need not return. An in-progress pointer gesture must return to the pre-gesture state, never auto-commit.

State acceptance is a parameterized recreation/process-death test per root, subview, editor, and mutation state, plus manual boundary resizing.

### 9.6 Errors, prevention, provider results, and Undo

The error bar follows [WCAG 3.3.1 Error Identification](https://www.w3.org/WAI/WCAG22/Understanding/error-identification.html) and [3.3.3 Error Suggestion](https://www.w3.org/WAI/WCAG22/Understanding/error-suggestion.html). For high-risk planning operations the product also adopts the reversible/check/confirm principle from [3.3.6 Error Prevention (All)](https://www.w3.org/WAI/WCAG22/Understanding/error-prevention-all.html), even though that criterion is a stronger AAA target.

Acceptance:

- invalid fields are identified in text, linked to the field, and include a correction when known;
- save moves focus to the first invalid field and preserves every draft value;
- a disabled action exposes why it is unavailable when that reason is not already obvious;
- app-owned create/update/move/resize/delete writes domain rows and one versioned mutation row in the same Room transaction;
- Plan task Delete may satisfy that contract by archiving the leaf row for reversible Undo; UI, tests, and History must not describe that as immediate physical deletion;
- a sequential multi-command operation states that it is not atomic before Apply and reports partial completion; each completed step remains independently visible and undoable in History;
- invalid or cancelled commands write neither domain changes nor an applied journal entry;
- the success message names the actual operation and object;
- the Undo action remains available for the full announced window and the journal survives process death;
- Undo performs one compare-and-set transition from applied to undone and restores the exact before state atomically;
- a duplicate or expired Undo cannot apply twice and produces a clear status;
- destructive Plan actions use confirmation until the same command has proven recoverable Undo;
- provider-owned operations distinguish success, refusal, read-only, authentication failure, offline, and ambiguous result;
- refusal or failure restores the local representation and says nothing changed;
- an ambiguous provider result remains pending or is reconciled before any success claim;
- local delete Undo never claims to restore a provider record it does not own;
- status messages are exposed to accessibility services without an unexpected context change.

### 9.7 Visual perception

Use [WCAG 1.4.3 Contrast (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html), [1.4.11 Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html), and [1.4.1 Use of Color](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html):

- normal text is at least 4.5:1;
- large text is at least 3:1 under the WCAG definition;
- essential controls, focus, selection, graph lines, handles, and state boundaries are at least 3:1 against adjacent colors;
- ownership, priority, conflict, progress, selection, and lock are not conveyed by color alone;
- solid/outline style is paired with Plan/FIXED text and accessible descriptions;
- 200% text does not clip, overlap, or hide an action;
- focus indication remains visible in light and dark themes.

## 10. Core versus premium boundary

Safety and access are product infrastructure, not premium value.

| Must remain core | Appropriate premium depth |
| --- | --- |
| Three-root navigation, Agenda/Timeline/Week, Outline/Board, basic Gantt access | Dependency-rich professional Gantt, baselines, critical path, slack |
| Search, capture, edit, basic organization | Advanced saved views and reusable templates |
| All accessibility alternatives and semantics | Capacity analytics and estimate calibration |
| Ownership badges, source rules, validation, confirmation, immediate Undo | Scenarios, 30-day history, restore points |
| Basic work schedule and safe manual blocks | Explainable auto-plan, repair suggestions, portfolio scheduling |
| Machine-readable complete export/backup floor | Polished PDF/image/portfolio reporting |

Do not paywall a non-drag alternative, readable text, a source warning, error recovery, basic Undo, or the ability to reach core views.

## 11. Dependency-ordered implementation plan

No phase is complete merely because its data class, mock, pure function, or test file exists. Each exit gate requires implemented journeys plus current evidence.

### Gate 0 — Re-establish the current baseline

Dependencies: none.

Current source position: **automated and API 36 device baseline passed; full visual/manual matrix pending**. The current named test suites are 272 planning-core, 26 contract, 57 server, and 184 app unit tests, with 134/134 API 36 instrumentation tests; the historical ledger below remains separate from this current refresh.

Work:

1. Preserve the recorded source inventory and exact Section 12 command/count/artifact ledger.
2. Add API 37.1/16 KB execution without reusing API 36 as its evidence; API 24 rendered
   inspection is now recorded locally, while the CI artifact remains a separate workflow gate.
3. Complete captures for editors and empty/loading/error/permission/refusal/Undo states at every required viewport.
4. Run the manual accessibility and task-usability matrix separately from automated gates.

Exit:

- current commands, counts, failures, APKs, schema versions, and captures are recorded;
- v7 source either compiles and tests or its blockers are explicit;
- no historical count is presented as proof for changed code.

### Gate 1 — Close the planning foundation

Dependencies: Gate 0.

Current source position: **current-scope automated/device evidence passed; broader product proof pending**. Default Working Week persistence/UI, the alternate-schedule lifecycle with default transfer and archive, per-task assignment/inheritance, transactional block preview/revalidation, typed journal/History Undo including schedule and catalog changes, the first Saved View, import disclosure, and Home reconciliation are integrated. Advanced Saved View management, weekly review, export, auto-plan with scenarios, baselines, and portfolio rollups are integrated too.

Work:

1. Retain the passing migration, default-schedule, editor, transaction, journal, Saved View, import, and rendered-journey coverage in the full device gate.
2. Retain the fail-closed rules the alternate-schedule lifecycle and multi-schedule Plan Health were built on: an unresolved assignment refuses a result, archiving a default demands a replacement, and a stale Undo is refused rather than applied.
3. Extend journal coverage with any new command; task Delete must continue to mean reversible archive in source and tests, not physical row loss.
4. Extend the deliberately limited first Saved View in Gate 6 without mutating Plan content.
5. Complete the unrun manual accessibility, provider, device/API, and usability evidence in Section 8D.

Exit:

- a person can edit a working week and exception, reopen it, and see the same availability;
- scheduling preview uses the persisted schedule and correctly handles DST;
- every current Plan write has atomic journal evidence and exact Undo;
- one named Board or Gantt view survives cold restart and malformed state fails closed;
- legacy import is visible and existing event/briefing/settings data is preserved;
- migration, repository, recreation, and rendered error tests pass.

### Gate 2 — Adaptive navigation, semantics, and state

Dependencies: Gate 1, because new layouts must compose the real persisted state.

Current source position: **partial; both wide compositions now exist and one is device-tested**. Five width classes, the wide Agenda/Timeline Calendar desk, and the wide Plan ledger are present, and the Plan ledger has rendered proof at Expanded and at a 2× text scale. Exact boundary transitions, focus restoration by stable ID and time anchor, and the full state/input matrix remain unproved.

Work:

1. Keep the selected three-root and subview placement.
2. Verify the implemented five-width classification at every exact boundary.
3. Verify the wide Calendar desk and implement a useful wide Plan composition without duplicating navigation state.
4. Complete state restoration by stable IDs/time anchors.
5. Complete the 48 dp and semantics audit.
6. Extend the current Agenda/Timeline custom actions with complete keyboard focus order, shortcuts, and reduced-motion handling.

Exit:

- T1–T5 and T9–T10 pass at every matrix size and 200% text;
- resize across every boundary preserves task, selection, draft, and focus;
- automated checks plus TalkBack/Switch/Voice manual passes find no gesture-only core action;
- root and command order remain consistent, aligning with [WCAG 3.2.3 Consistent Navigation](https://www.w3.org/WAI/WCAG22/Understanding/consistent-navigation.html) and [3.2.4 Consistent Identification](https://www.w3.org/WAI/WCAG22/Understanding/consistent-identification.html).

### Gate 3 — Safe direct manipulation

Dependencies: Gate 1 journal/work schedule; Gate 2 semantics/state.

Current source position: **implemented for Timeline, Board, and Gantt, including pinch zoom, the overview strip, and drawn dependency paths**. Timeline drag, ±15-minute actions, Move to another date, and the keyboard path all reach one saveable pre-commit preview with ownership-checked exact-snapshot Undo; provider/read-only rows stay fixed. Board multi-move is one atomic journaled step, and long-press drag with edge auto-scroll stages that same command. Gantt move mode adds fields, increments, keyboard, custom actions, and bar/handle dragging across three non-overlapping 48 dp targets, where only Apply writes. Pinch moves through the same three ranges as the chips while keeping the day under the fingers fixed, an overview strip marks the visible window on the whole span and moves there when tapped, and dependency links are drawn with slack bands that state how many links they could not draw. Manual pointer, stylus, and screen-reader passes over these gestures are still outstanding.

The rendered device journey, not the source, is what proved this gate. It first failed: the preview panel floated over the canvas and both resize handles were drawn across the move target, so the bar the person had selected could not be dragged at all. The fix laid the three targets out as neighbours, gave the panel its own space, and made move mode a focused mode that reclaims header height. Both the pure target policy and the rendered non-overlap assertion are now part of the suite.

Work order:

1. Verify the routed Timeline capability/command/Undo path and add the missing exact preview, Move-to-date, keyboard, and focus/scroll preservation.
2. Verify Board Select with manual accessibility/input methods; decide whether multi-move needs a new atomic batch command.
3. Add Board drag only after arrows/menu/custom actions share the same truthful atomic-or-sequential command semantics.
4. Add Gantt move/resize fields and increment actions first.
5. Add large handles and long-press manipulation.
6. Add pinch focus zoom and minimap while retaining 7/30/90.
7. Retain the implemented non-drag dependency ledger as canonical; add canvas dependency paths only after they agree with the same repository commands.

Exit:

- T6–T7 pass for touch, TalkBack, switch, keyboard, and mouse;
- gesture and button paths produce the same validated command;
- exact preview names date, working time, conflicts, group/dependency impact, and lock/source constraints;
- cancel, interruption, invalid target, and provider refusal produce zero partial change;
- release commits once; Undo restores exact prior rows after process death.

### Gate 4 — First premium wedge: Plan Health and capacity

Dependencies: Gates 1–3.

Current source position: **multi-schedule capacity implemented and unit/device tested; manual acceptance pending**. Home shows a compact fail-closed result and Plan explains the seven-day inputs, warnings, risks, and repairs. Differing per-task schedules now produce one combined result by unioning availability on the absolute time line and assigning it with a maximum-flow calculation, so overlapping schedules never double-count an hour. An unresolved assignment still suppresses the result rather than estimating one.

Work:

1. Retain passing default-schedule capacity, DST/exception, effort, overcommit, unscheduled, dependency/milestone, and missing-estimate coverage.
2. Extend the multi-schedule assignment with scale, partial-data, and adversarial overlap cases, and keep an unresolved assignment refusing a result.
3. Render and accessibility-test the compact Home summary and detailed Plan explanation.
4. Complete visible data freshness, horizon, source exclusions, and unknown-estimate disclosure where current wording is insufficient.

Exit:

- T8 produces deterministic results for the same inputs;
- every warning explains the contributing work, commitments, schedule, and uncertainty;
- incomplete data cannot render a confident “fits” state;
- free core navigation and safety remain unchanged.

### Gate 5 — Dependencies, slack, and professional Gantt

Dependencies: Gate 4 trustworthy schedule/capacity; Gate 3 manipulation.

Current source position: **partial with current calculations/repository journeys passing**. Typed dependency persistence, lead/lag editing, cycle/duplicate/cross-board checks, a title-first accessible ledger, critical-path calculation, and critical-bar highlighting are present. Dependency paths, rendered slack, direct manipulation, the overview strip, and grouping and column presets are now present with named tests; manual graph review and broader scale proof are not.

Work:

1. Verify the integrated typed dependencies, lead/lag, repository constraints, journal, and non-drag editor.
2. Extend the current critical-path summary/highlighting with dependency paths, milestones, rendered slack, conflicts, and affected-successor inspection.
3. Add hierarchy collapse, grouping, density, columns, and advanced named view settings.
4. Keep the existing accessible dependency list/edit path as the canonical alternative to any future canvas path.

Exit:

- cycle, missing-block, lag, milestone, lock, DST, and cross-board cases fail closed;
- graph visuals and semantics agree;
- a person can inspect and edit each dependency without drawing a line;
- scale tests cover large boards and dense overlapping blocks.

### Gate 6 — Saved views and weekly review

Dependencies: Gates 4–5.

Current source position: **complete for this gate's scope, with named tests**. A named view stores and restores the surface, Gantt range, outline order, grouping, visible Board columns, hide-done filter, and folded branches, with rename, update-to-screen, duplicate, exclusive per-board pin, reset, and delete; malformed state still fails closed. Weekly review, export, explainable auto-plan, scenario comparison, baselines with variance and restore, and portfolio rollups are built. What is unproved here is use, not code: no one outside this repository has planned a real week with them.

Work:

1. Extend the first surface/range Saved View to filters, grouping, sort, columns, anchor, zoom, and collapsed IDs.
2. Add pin, rename, duplicate, update, reset, and delete.
3. Add weekly planned-versus-done, schedule churn, unfinished work, and change digest.
4. Preserve source/provenance when converting a brief recommendation into a Plan item.

Exit:

- T9 passes across cold restart, board rename/archive, missing IDs, malformed JSON, and schema evolution;
- review numbers are reproducible and disclose horizon/exclusions;
- resetting a view changes presentation only.

### Gate 7 — Explainable auto-plan and scenarios

Dependencies: working calendars, journal, dependencies, capacity, and saved views.

Work:

1. Generate deterministic proposals around fixed commitments, locks, effort, deadlines, dependencies, buffers, and chunk limits.
2. Show before/after scenario, every moved block, rule, trade-off, and unresolved conflict.
3. Support compare, Apply once, and Discard.
4. Reuse the same command/journal and accessibility paths as manual planning.

Exit:

- no proposal writes before Apply;
- identical inputs and policy produce identical output;
- locks and source-owned time never move;
- discard makes zero change;
- Apply is atomic and Undo restores the pre-scenario plan.

### Gate 8 — Baselines, history, portfolio, and polished export

Dependencies: all earlier gates.

Work:

1. Add named baselines, variance, durable history, restore points, and cross-board rollups.
2. Add timestamped PDF/image/CSV/ICS exports and print layouts.
3. Add deterministic restore preview and encrypted backup.
4. Add widgets, shortcuts, notification actions, and focus timer only where their ownership and Undo model is clear.

Exit:

- T11 discloses included boards, sources, ranges, generated time, omissions, and partial data;
- restore is previewed, deterministic, and reversible;
- portfolio calculations pass scale and partial-data tests;
- exported rendered artifacts pass visual and accessibility review.

## 12. Verification matrix and claim discipline

### Current root-owned verification ledger

Refreshed on 2026-08-20. `PASS` applies only to the named command or inspected state; the
remaining rows are deliberately still pending.

| Status | Evidence layer | Current command or review |
| --- | --- | --- |
| **PASS** | Diff hygiene | `git diff --check` |
| **PASS** | Current named JVM/app suites | planning-core 272, contract 26, server 57, app unit 184; zero failures |
| **PASS** | Full API 36 device gate | `scripts/verify.sh --device` — 134/134 instrumentation tests; both lints, APK assemblies, and R8 analysis |
| **PASS** | Current server acceptance | `scripts/journey.sh` — health/readiness/CORS plus signup through push, 4.237 seconds in the latest local run |
| **PASS** | Server operational-route tests | `:server:test` — 57/57, including provider failure policy, liveness, fail-closed readiness, and absent-Google OAuth configuration |
| **PASS** | Mobile cache and workspace-planning rules | `apps/mobile/npm test` — 18/18 focused platform-neutral, web-adapter, modeled native-adapter persistence/key, and multi-project planning-contract tests |
| **PASS** | Mobile production API policy | `apps/mobile/npm test` — 5/5 `resolveApiBase` cases; production export without `EXPO_PUBLIC_API_BASE` refuses, and a configured production origin exports successfully |
| **PASS — local API36 release slice** | Mobile rendered/restart/offline journey | Release APK exits the splash, completes fixture signup, renders Today, survives force-stop/relaunch with the saved session, and after the fixture server is stopped renders Today with the explicit offline cached-day label; physical storage-failure coverage remains open |
| **PASS — generated-project hardening** | Mobile Expo prebuild reproducibility | `npx expo prebuild --platform android --no-install` now re-applies the Kotlin 2.3.20, compileSdk 36, `mavenLocal()`, and emulator network-policy settings through the tracked config plugin; a generated `:app:assembleRelease` passed locally |
| **PASS — local release verifier** | Mobile production/release packaging | `scripts/verify-mobile-release.sh` sets `NODE_ENV=production` with an explicit emulator API origin, regenerates Expo Android, publishes the local engine AARs, bundles JS, asserts the origin is embedded, assembles all four ABIs, and verifies the merged network manifest; APK size is 105,463,757 bytes |
| **PASS** | Mobile/web static checks | mobile lint, TypeScript, Expo web export/dependency check, web lint, web TypeScript, web Vitest (4/4), and `apps/web/npm run build` |
| **PASS — packaging only** | 16 KB APK alignment | `scripts/smoke-release.sh` now aligns and verifies the install artifact with `zipalign -P 16`; this does not prove 16 KB runtime execution |
| **PENDING — external/device** | Android coverage tail | API 24 rendered capture is complete and representative plates were inspected; API 37.1/16 KB app execution remains open because the local guest reports SDK 37.1 and `PAGESIZE=16384` but cannot complete user-0 boot (`BOOTING`), lacks the `storage` service, and previously crashed `surfaceflinger` under SwiftShader before reliable app execution |
| **PASS — scoped** | Mobile dependency maintenance | `npm audit --omit=dev` has no moderate findings after the `xcode` → `uuid@11.1.1` override; 8 high upstream Expo/React Native/Metro findings remain pending a planned framework upgrade |
| **PENDING — external/device** | Native mobile cache runtime tail | physical/reliable-device restart plus direct native-file proof for corruption, workspace isolation, and failed-storage-write behavior; the local API36 release slice now proves session restart and cached Today fallback |
| **PENDING — CI/external** | Newly authored evidence workflows | The web lint/typecheck/Vitest/build workflow is now locally validated; the API 24 rendered-capture workflow, Postgres journey, macOS PlannerCore/Swift import workflow, Expo mobile-release workflow, and this web workflow still require hosted execution and artifact inspection. Local plates and release verifiers do not substitute for hosted evidence |

### Historical root-owned verification ledger

Recorded on 2026-08-12 after the analysis tranche — baselines, portfolio rollups, scenario comparison, and grouping/column presets. The older counts below are retained as chronology, not current evidence. `PASS` applies only to the named command or inspected state. The remaining rows are deliberately still pending.

| Status | Evidence layer | Command or review |
| --- | --- | --- |
| **PASS** | Diff hygiene | `git diff --check` |
| **PASS** | Fast repository gate | `scripts/verify.sh --fast` |
| **PASS** | Full no-device repository gate | `scripts/verify.sh` |
| **PASS** | JVM tests | 378 JVM tests passed, 0 skipped |
| **FAILED, then fixed** | First device gate of this tranche | `scripts/verify.sh --device` — 82/83; `GanttDirectManipulationJourneyInstrumentedTest` proved the Gantt move target was unreachable because the preview panel and both resize handles were drawn over the bar |
| **PASS** | Full repository/device gate after the fix | `scripts/verify.sh --device` — 128/128 instrumentation tests passed; debug and release lint passed; debug, Android-test, and unsigned release APKs assembled; R8 analysis passed |
| **PASS — limited** | Minified release smoke | `scripts/smoke-release.sh` — unsigned minified release installed, launched, and navigated the three roots; reported size 2551 KiB. It taps the primary destinations only, so R8 damage *inside* a dialog would not show up here; the analysis code paths use no reflection or runtime class lookup, which is the reason R8 is unlikely to reach them, not proof that it did not |
| **FAILED, then fixed** | Board keyboard shortcut | `BoardSelectionJourneyInstrumentedTest` — Alt+Arrow acted on key-up while letting key-down fall through to focus traversal, so the shortcut moved the focus ring and did nothing else |
| **PASS** | Interaction-principle fixes | `HciPrinciplesJourneyInstrumentedTest` — 5/5: Back cancels each transient mode without writing, a disabled Save names its field, the move preview announces its exact times, direct movement has a visible entry, and the chosen Undo window is what the journal stamps |
| **PASS** | Baseline storage and migration | `PlanMigrationInstrumentedTest` — 5 → 8 and 6 → 8 preserve every seeded row; 7 → 8 adds `plan_baselines` to a fully populated database, refuses two baselines with one name on a board, and is safe to re-run |
| **PASS** | Analysis journeys | `PlanAnalysisJourneyInstrumentedTest` — 4/4: a baseline is taken, drifts, is compared and restored as one undoable entry; a duplicate name is refused; the rollup names its unestimated work; comparing approaches writes nothing and hands the chosen ordering to the ordinary review; a saved view restores grouping and visible columns |
| **FAILED, then fixed** | Gate after a preview capture | `scripts/verify.sh --device` — 100/107, all seven in suites that ran *before* the new ones. The capture harness had left its seeded rows and flipped settings on the device; `capture-preview.sh` now clears app data when it finishes |
| **FAILED, then fixed** | Re-audit of this tranche | Deleting a baseline destroyed it on one tap — no confirmation, and unlike every other destructive act here, no journal entry to undo. It now names the date being discarded and asks; `PlanAnalysisJourneyInstrumentedTest` guards it |
| **PASS** | New dialogs at 2× text | `UiAccessibilityInstrumentedTest` — baselines, the comparison, Compare approaches, and the rollup keep their asserted text unclipped with every action at or above 48 dp; `PlanAnalysisTextTest` pins the drift sentences, including the singular minute |
| **PASS** | Command parity across window sizes | `PlanAdaptiveWorkspaceInstrumentedTest` — the compact menu and the wide rail's menu offer the same fourteen commands |
| **FAILED, then fixed** | Journal audit | Three faults, each of which made an Undo claim untrue: a restore that emptied the schedule recorded an after-state equal to its before-state so the blocks could not be recovered; a restore whose rows had been recreated failed with an internal message; and undoing a multi-block plan called a single-target accessor and threw. `PlanBlockGroupState.absentIds` and target-wide inverses fix all three |
| **PASS** | Adversarial capacity | `MultiSchedulePlanHealthTest` — the greedy trap, three schedules that each fit alone while the person does not, a commitment on the only shared hour, a schedule with no hours in range, and a chain of partial overlaps |
| **PASS** | Baseline cost at scale | 800 tasks and 2,400 blocks encode, decode and compare in ~0.1 s, which matters because capture runs inside the schedule mutex |
| **PASS** | Static analysis | Debug and release lint report **zero** issues; the Kotlin compile is warning-free across all three source sets |
| **FAILED, then fixed** | Referential integrity | `plan_baselines` shipped with no foreign key, so deleting a plan orphaned its baselines while `saved_views` cascaded. Schema v9 rebuilds the table with `ON DELETE CASCADE`, keeping every row whose board survives |
| **PASS** | Schema v9 migration | `PlanMigrationInstrumentedTest` — 5 → 9, 6 → 9, 7 → 9 and 8 → 9 all preserve their seeded rows; the rebuild keeps payloads intact, drops an already-orphaned baseline, and the live constraint then cascades |
| **FAILED, then fixed** | Real-device report | A phone showed what one emulator size hid: every phone is `Compact`, so nothing adapted between a 308 dp screen and a 448 dp one. At 360 dp a control chip rendered one letter per line, the health chip wrapped to three lines, Home's bespoke header wrapped to four, and the Schedule map drew its axis and no bars. Fixed with `FlowRow`, one-line status chips, the shared header treatment, and measured-size tokens |
| **PASS** | Four phone geometries | The capture harness walks every screen at 360×640, 411×914, 448×997 and 308×685 dp; two of those could not complete before |
| **PASS — limited** | Rendered inspection | `scripts/capture-preview.sh` — portrait Home, Agenda, Timeline, Week, Outline, Board, Schedule map, Gantt move mode, the baseline comparison, Compare approaches, working schedules, Settings, and two dark screens, plus Expanded-width Calendar and Plan, recaptured into `preview.html` |
| **PENDING** | API/device completion | API 37.1/16 KB execution, physical provider behavior, foldable/hinge, large tablet, desktop/Chromebook, and split-screen. API 24 instrumentation plus local rendered capture now exist; the API24 CI artifact and the remaining form-factor evidence still require their respective external runs/inspection |
| **PENDING** | Exact adaptive/state matrix | 599/600, 839/840, 1199/1200, and 1599/1600 dp transitions; 200% text; all loading/empty/error/permission/refusal/Undo states; focus and draft preservation |
| **PENDING — manual** | Accessibility and input | TalkBack, Switch Access, Voice Access, keyboard, mouse/trackpad, stylus, dark/high-contrast, reduced-motion, RTL, and long-localized-text journeys |
| **PENDING — manual** | Task usability | T1–T11 task sessions and comprehension findings; no source or automated test can mark this passed |

Recorded APK artifacts:

| Artifact | Bytes |
| --- | ---: |
| Debug APK | 15,121,984 |
| Android-test APK | 1,978,432 |
| Unsigned minified release APK | 2,612,685 |

Never translate “not run,” “compiled,” “visually inspected once,” or “test file exists” into a broader pass. This tranche is the worked example: the Gantt manipulation source was complete, its pure policies passed, the screen rendered, and the feature was still unusable by pointer. Only the rendered device journey could say so.

For every gate, record four separate evidence layers:

| Layer | What can pass it | What it cannot prove |
| --- | --- | --- |
| Pure/local tests | deterministic calculations, codecs, validation, projections, state reducers | Room migration, rendered UI, provider behavior, accessibility service behavior |
| Android build/instrumentation | schema migration, repository transactions, recreation, device journeys | every form factor, physical provider, human comprehension |
| Rendered/device matrix | layout, clipping, focus, touch bounds, animations, specific API/device behavior | learnability or correct mental model for real users |
| Task/user proof | whether people can understand and complete T1–T11 | untested technical edge cases |

A gate may be “repository-complete, external proof pending,” but not “complete” when required external evidence is absent. The green full device suite is not full visual sign-off. The inspected API 24/API 36 phone plates and Expanded states are not tablet/desktop or boundary proof. Neither API 36 nor API 24 proves API 37.1/16 KB execution. Automated semantics are not a TalkBack/Switch/Voice pass. Passing WorkingCalendar and schedule journeys do not prove real-user comprehension.

## 13. Final product decision

The selected structure should be retained and deepened, not replaced:

- Home answers now, next, and risk.
- Calendar owns commitments and chronological inspection.
- Plan owns flexible work, workflow, and scheduling.
- The time spine connects them.
- Placement stays stable; gestures make it faster but never exclusive.
- Premium value comes from explainable planning depth, not restricted safety or a decorative Gantt.

The next roadmap tranche is Gate 6 Saved View depth. Extend the stored view from surface and range to the filters, grouping, sort, columns, zoom, and collapse the codec already accepts, add Outline collapse and selection semantics so there is state worth saving, then add rename, update, pin, duplicate, and reset — none of which may mutate Plan content. Alongside it, finish focus and time-anchor restoration at the exact width boundaries. Only after that should the plan advance to drawn dependency paths and slack, then pinch and minimap, weekly review, auto-plan/scenarios, baselines, portfolio restore, and export.

The passing automated, API 36, and release-smoke evidence is a strong repository baseline, and this tranche showed exactly how far it reaches: it caught a defect that no amount of source reading had. The cross-device, full manual accessibility, and usability matrices in Section 8D remain open, and they will catch a different class of defect again.
