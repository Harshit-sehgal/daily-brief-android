# Daily Brief — the seven principles, audited and applied

Status: research, source audit, principle-ordered plan, and the twenty-four changes it produced,
2026-08-12 (Asia/Kolkata). Every item in Section 4 is implemented and guarded by a named test; the
manual and user-study evidence in Section 5 remains open and is not claimed. Three of the defects were found by rendered device journeys after the source read clean, and four
more — an unconfirmed delete, and three journal faults that made an Undo claim untrue — were
introduced by this work and caught by re-auditing it, which is the whole argument of Section 2.

[HCI_REDESIGN_PLAN.md](HCI_REDESIGN_PLAN.md) decides *what the product is* and sequences its delivery.
This document is narrower and sharper: it takes the seven principles seriously as an evaluation
instrument, applies them to the code that exists today, and records what they found. Section 4 of
the delivery plan states the crosswalk; this one does the work and names defects.

Findings are graded, and the grading is deliberately unkind to this codebase — a principle that
cannot fail an existing feature is decoration.

---

## 1. Which seven, and why two frameworks are needed

"The seven principles of HCI" names two different lists, and using either alone produces a weak
review.

**ISO 9241-110:2020**, *Ergonomics of human-system interaction — Part 110: Interaction principles*,
defines seven **interaction principles** (renamed from "dialogue principles" in the 2020 revision).
They are *acceptance qualities*: statements about what an interactive system must achieve for the
person using it.

1. Suitability for the user's tasks
2. Self-descriptiveness
3. Conformity with user expectations
4. Learnability
5. Controllability
6. Use error robustness
7. User engagement

**Don Norman's seven fundamental principles of design** (*The Design of Everyday Things*, revised
edition) are Discoverability, Feedback, Conceptual Model, Affordances, Signifiers, Mappings, and
Constraints. These are *mechanisms*: the things a designer actually builds.

The two lists are not competitors and not a one-to-one mapping. ISO says what must be true; Norman
says what to build so it becomes true. This plan uses ISO as the pass/fail contract and Norman as
the vocabulary for the fix. Norman's own correction matters here — he argues designers should stop
worrying about affordances and provide **signifiers**, the perceivable clues that tell a person
what to do. Several findings below are missing-signifier findings precisely in that sense.

A third instrument does the fine measurement. ISO and Norman are general by design, so where a
principle needs a threshold this plan cites the specific rule: WCAG 2.2 success criteria and
Android platform conventions. That is the difference between "the system should be controllable"
and "a 30-second undo window is a content-imposed time limit with no way to extend it".

### The audit lens: Norman's action cycle

Every finding below was produced by walking one real task through the seven stages of action and
asking where the two gulfs open:

- **Gulf of execution** — the person knows their goal but not how to act. *Can I see the action?
  Do I know it exists? Can I tell it applies to this object?*
- **Gulf of evaluation** — the person acted but cannot tell what happened. *Did it work? What
  state am I in now? Can I still change my mind?*

A planning app has an unusually wide evaluation gulf: moving a block changes a schedule, capacity,
dependents, and possibly nothing at all if a provider refuses. That is why feedback and
recoverability dominate the findings.

---

## 2. How a principle is allowed to pass

The evidence discipline from the delivery plan applies unchanged: a source file, a pure test, or a
green local gate proves only its own layer. Each finding therefore records the layer that can
falsify it.

| Layer | Can establish | Cannot establish |
| --- | --- | --- |
| Source review | that a control, label, or constraint exists at all | that it is reachable, legible, or understood |
| Pure/unit test | that a rule is exact and total | anything about rendering, focus, or announcement |
| Instrumented journey | that a person's route through the real UI works on a device | comprehension, or any device not run |
| Manual assistive-technology pass | that TalkBack/Switch/Voice/keyboard can complete the task | that the task made sense to do |
| Task-based user study | comprehension and confidence | technical edge cases |

No finding below is closed by source review alone. Where a fix landed, the row names the test that
now guards it.

---

## 3. Audit findings

Severity: **blocking** — the principle fails for a core task and the feature should not be called
done; **serious** — the task completes but a foreseeable person is left stuck or misinformed;
**minor** — friction with a cheap fix.

### 3.1 Suitability for the user's tasks

> The system supports users in completing their tasks; operating functions and interactions are
> based on the task's characteristics rather than the technology's.

**Passes.** Quick capture keeps title and notes when switching between Task and Event, which is the
behaviour WCAG 2.2's *3.3.7 Redundant Entry* asks for and the reason the same thought does not have
to be typed twice. Scheduling a task from a Gantt row pre-fills the task, its resolved working
schedule, and its effort as the duration, so the frequent path is not a blank form. Calendar
commitments are never converted into movable tasks, which keeps the two task models from
contaminating each other.

**Finding S-1 (minor).** Entering Gantt move mode is reachable by long-press or by the
`Move Plan block` accessibility action, but there is no visible control for a mouse, trackpad, or
touch user who never guesses the long-press (`GanttScreen.kt`, `enterMoveActions`). The task still
completes — tapping the bar opens an editor with exact date and time fields — but the fastest path
is invisible. See L-1; the fix is shared.

### 3.2 Self-descriptiveness

> The system presents the information a person needs, where they need it, to make its capabilities
> and use immediately obvious without extra interaction.

**Finding SD-1 (blocking).** *An editor refuses to save and never says why.* Both editors disable
Save when the draft is invalid — `EventEditorSheet.kt:294` requires a non-blank title and a valid
board/column pair, and the task editor does the same. Neither field carries `isError` or
`supportingText`, and nothing near the disabled button explains the condition. A person with an
empty title sees a dead button; a person whose board/column pair became invalid (the column was
renamed or deleted while the sheet was open) sees a dead button with no visible cause anywhere on
screen. Error *prevention* without explanation is not self-descriptiveness — it is a locked door
with no sign. This is also a use-error-robustness failure (see R-2) and the missing signifier in
Norman's sense.

**Finding SD-2 (serious).** *The move preview changes silently for anyone not watching it.* The
Gantt move panel renders the exact start, end, and a status line — "Ready to apply", "Cannot apply:
…" — at `GanttScreen.kt` `gantt_move_status`. Those values change when the person uses
`Move 15 minutes later`, an arrow key, or a handle action, and nothing announces the change:
there is no `liveRegion` on the panel's times or status. A screen-reader user can perform the
action and receives no confirmation of the new time or of whether Apply will now work — the exact
gulf of evaluation the feature was built to close. WCAG 2.2 *4.1.3 Status Messages* is the specific
rule. The newer `DependencyManager.kt:231` and `WorkingScheduleEditor.kt:1056` already do this
correctly, so the codebase disagrees with itself.

**Passes elsewhere.** Ownership is stated, not implied: fixed rows are labelled `FIXED`, sources are
badged on agenda rows, and the Gantt distinguishes solid app-owned bars from outlined commitments.
Loading, empty, permission, and refusal states use sentences that name the next step rather than a
bare spinner.

### 3.3 Conformity with user expectations

> Behaviour is predictable from the context of use and from commonly accepted conventions.

**Finding C-1 (blocking).** *Back does not leave a mode; it leaves the screen.* The application has
exactly one `BackHandler` (`DailyBriefApp.kt:456`) and it only navigates between roots. Three
transient modes have no Back handling at all:

| Mode | State | What Back does today |
| --- | --- | --- |
| Gantt direct movement | `GanttScreen.kt:169` `moveDraft` | leaves the Plan root; the unsaved preview is destroyed with no prompt and no message |
| Board multi-select | `BoardScreen.kt:145` `selectionBoardId` | leaves the Board with the selection silently discarded |
| Home layout editing | `HomeScreen.kt:119` `editingLayout` | leaves Home still in edit mode on return |

On Android, Back is the universal "get me out of this" gesture, and every calendar and planning app
a person has already used honours it. Sending Back to navigation while a modal-like mode is active
breaks *conformity*, breaks *controllability* (the cancel path a person reaches for does not
cancel), and destroys in-progress work, which makes it a *use error robustness* failure too. The
same reflex applies to a hardware keyboard's Escape.

**Passes elsewhere.** Roots keep a fixed order and identity across the app (WCAG *3.2.3 Consistent
Navigation*, *3.2.4 Consistent Identification*); Search and Settings stay top-right on every root;
one create action stays bottom-right; legacy launch destinations are normalised rather than left
pointing at screens that no longer exist.

### 3.4 Learnability

> The system supports discovery of its capabilities, allows safe exploration, minimises what must
> be learned, and helps when learning is needed.

**Finding L-1 (serious).** *The most powerful interaction in the product is invisible.* Direct
movement — the tranche's headline capability — is entered only by long-press or by an accessibility
action. Nothing on screen says a bar can be moved. The project's own gesture contract requires every
gesture to accelerate "a visible button, menu, field, or accessibility action"; the visible
counterpart here is the block editor, which reaches the same *outcome* by a different route, but no
visible control reaches *move mode itself*. A person exploring the Gantt will not find it, and
exploration is the mechanism ISO names for learnability.

**Passes elsewhere.** Exploration is safe: pointer release never saves, Cancel writes nothing, and
every destructive action is either confirmed or reversible. Empty states teach the next step
("Capture a task, choose another date, or widen the range") instead of stating a null result.

### 3.5 Controllability

> The person maintains control of the interface and interaction, including speed, sequence, and
> individualisation, and can undo.

**Finding CT-1 (blocking).** *Undo expires after thirty seconds — everywhere.*
`PlanRepository.UNDO_WINDOW_MS = 30_000` stamps `expiresAt` on every journal entry
(`PlanRepository.kt:1357`), `BriefingRepository.kt:1015` uses the same constant for event snapshots,
`PlanScreen.kt` greys the History entry to "Expired" the moment it passes, and the snackbar uses the
same 30 seconds. So "recent Plan changes with Undo" is really "Undo for thirty seconds, then a
read-only log". The window cannot be turned off, adjusted, or extended.

Thirty seconds is not a neutral choice. It is roughly the time it takes a TalkBack user to reach a
snackbar action, and less than the time it takes to notice a wrong move, switch to Plan, open
History, and read the entry. WCAG 2.2 *2.2.1 Timing Adjustable* exists for exactly this: a time
limit set by the content must be able to be turned off, adjusted, or extended, unless the limit is
essential. Nothing here is essential — the Undo path is already fail-closed by compare-and-set, so a
longer window cannot corrupt anything; it can only refuse.

**Passes elsewhere.** Every mutating gesture has a non-gesture equivalent; previews precede commits;
sync is available from four visible places; density, launch page, Home blocks, Agenda sections, and
theme are all user-controlled. Interruption is respected where it exists: a cancelled pointer writes
nothing.

### 3.6 Use error robustness

> The system helps avoid errors, tolerates the ones that happen, and helps the person recover.

**Finding R-1 (blocking, same defect as C-1).** In-progress work is destroyed by an ordinary system
gesture. Error *tolerance* means an accidental Back should not vaporise an unsaved preview.

**Finding R-2 (blocking, same defect as SD-1).** Error avoidance is implemented as a disabled
control with no diagnosis. WCAG *3.3.1 Error Identification* and *3.3.3 Error Suggestion* describe
the missing half: name the field that is wrong, in text, and say what would fix it.

**Passes elsewhere, and strongly.** This is the codebase's best area. Deletes fail closed when a
source refuses and the row returns with a reason. Block saves are revalidated inside the
transaction, so a preview that went stale cannot commit. `PlanBatchMovePolicy` rejects cyclic,
cross-board, and unavailable inputs before a single row is written, and the batch move is one
transaction, so a partial failure leaves nothing behind. Undo is compare-and-set: if the row changed
underneath, Undo refuses and says so rather than overwriting a newer edit. Task delete is a
reversible archive, not a row drop.

### 3.7 User engagement

> The system presents functions and information invitingly, supporting continued interaction —
> trust, reassurance, and anticipation of needs.

**Passes.** Engagement here is earned by explainability rather than by mechanics: Plan Health names
its inputs, warnings, risks, and suggested repairs, and refuses a confident answer when an
assignment cannot be resolved rather than estimating one. There are no streaks, badges, artificial
urgency, or safety features held behind a paywall. Accessibility, ownership honesty, and recovery
are core, not premium.

The engagement risk in this product is the opposite of gamification: silence. Every finding above
(SD-2's unannounced status, CT-1's expiring recovery, C-1's destroyed drafts) withdraws
reassurance at the moment a person most needs it, which is why they are graded as harshly as they
are.

---

## 4. The plan

Ordered by principle severity, not by implementation convenience. Each item states the acceptance
criterion and the evidence layer that closes it.

### P1 — Back and Escape leave the mode, not the screen *(C-1, R-1)* — **done**

Each transient mode now owns a `BackHandler`, and Compose's innermost-first dispatch does the
ordering for free: Gantt move mode cancels the preview (`GanttScreen.kt`), Board Select clears and
exits selection (`BoardScreen.kt`), Home layout editing returns to Done (`HomeScreen.kt`). Only
when no mode is active does Back navigate roots, so the existing navigation contract is untouched.
Escape in the move panel does what Back does, for a keyboard.

*Acceptance:* with a mode active, Back cancels exactly that mode and writes nothing; a second Back
navigates as before.
*Evidence:* `HciPrinciplesJourneyInstrumentedTest.backCancelsGanttMoveModeBoardSelectionAndHomeEditingWithoutWriting`
— asserts the mode is gone, the root is unchanged, the block is byte-identical, and the journal
gained no entry.

### P2 — Editors say what is wrong, in the field that is wrong *(SD-1, R-2)* — **done**

`EditorSaveGuard` turns a draft into the one blocker that is holding Save, in a stable order, with
two registers: the field carries `isError` and the sentence that fixes it, and a notice beside Save
says what Save is waiting for and announces itself politely when it appears. Save stays disabled —
prevention is right here — but is never silent again. The two registers are deliberately different
sentences; the same words twice on a short sheet reads as a stutter.

Focus is *not* moved. With Save disabled there is no attempt to hang a focus jump on, and stealing
focus while someone is typing would trade one violation for another. The field error is adjacent to
the field, and the announced notice reaches anyone whose attention is on the button.

*Acceptance:* every condition that disables Save has a visible sentence naming the field and the
fix, plus an announced summary beside Save.
*Evidence:* `EditorSaveGuardTest` (order, totality, and that both registers are real sentences) and
`HciPrinciplesJourneyInstrumentedTest.aDisabledSaveNamesTheFieldItIsWaitingOn`.

### P3 — The move preview announces itself *(SD-2)* — **done**

The move panel's status line is a polite live region whose description carries the exact start and
end alongside the status, so one announcement answers "what is it now?" and "can I apply it?"
together instead of firing three times.

*Acceptance:* a status or time change reaches assistive technology without focus moving.
*Evidence:* `HciPrinciplesJourneyInstrumentedTest.theMovePreviewAnnouncesItsExactTimesAndStatus`
asserts the live region and the composed announcement. A manual TalkBack pass is still required and
stays open in Section 8D of the delivery plan — an automated semantics assertion is not a
screen-reader pass.

### P4 — Undo lasts as long as the person needs *(CT-1)* — **done**

`UndoWindowPolicy` offers 30 seconds, 5 minutes, 1 hour, or 24 hours and defaults to 5 minutes.
Settings → Planning carries the control; both journal writers (`PlanRepository.undoWindowMs`, and
the event-snapshot path in `BriefingRepository`) read it at write time, so a change applies to
everything written afterwards. Malformed storage falls back to the default rather than to the
shortest window — failing closed here would mean failing *short*, which is the defect.

The snackbar still waits 30 seconds, renamed `PLAN_UNDO_SNACKBAR_MS` so the two are no longer
confusable: the message is the accelerator, History is the durable path. Compare-and-set is
untouched, so a wider window widens recovery without weakening safety — a stale Undo still refuses.

*Acceptance:* the choice persists and stamps the journal; History offers Undo for the full window.
*Evidence:* `UndoWindowPolicyTest` and
`HciPrinciplesJourneyInstrumentedTest.theChosenUndoWindowIsWhatTheJournalStamps`, which drives the
real Settings control and then reads the stamp off the row.

### P5 — Move mode has a visible way in *(L-1, S-1)* — **done**

Tapping a Plan bar already opens its block editor with exact date and time fields; that editor now
carries a **Move on map** action which closes it and enters direct movement on the same block. This
is a better home than the row menu the plan first proposed: a task can own several blocks, and the
editor is unambiguous about which one is being moved. Long-press stays as the accelerator, and the
action is disabled for a locked block or a locked task, exactly like the gesture.

*Acceptance:* move mode is reachable without a gesture and without assistive technology; both routes
produce the same state and write nothing on the way in.
*Evidence:* `HciPrinciplesJourneyInstrumentedTest.theBlockEditorOffersAVisibleWayIntoDirectMovement`.

### P6 — Alt+Arrow moves the task, not the focus ring *(C-1 family, found by test)*  — **done**

The Board's keyboard move waited for the key *up* while letting the key *down* fall through. Focus
traversal took the down, moved the focus ring off the card, and delivered the up somewhere else, so
the documented shortcut did nothing visible. The handler now claims the key on the way down and
acts on release. This was invisible to every reading of the code and to the pure tests; a rendered
journey caught it, twice over.

*Evidence:* `BoardSelectionJourneyInstrumentedTest.compactBoardStagesHierarchyMovesAndAppliesOneAtomicStep`.

### P7 — A view remembers how the work is shown *(suitability, controllability)* — **done**

Saved views stored only the surface and the Gantt range, so "save this view" saved almost nothing.
The Outline now has real presentation state — fold a branch, hide finished work, order by plan
order, due date, priority or effort — persisted as settings so a fold survives a cold start, and a
saved view stores and restores all of it. Views gained the lifecycle that makes them usable:
rename, update to what is on screen, duplicate, pin one view per board so that board opens with it,
delete, and reset to defaults. Every one of those is presentation only; the journey asserts the
tasks are byte-identical afterwards.

A view also remembers how the work is *banded* and which lanes were showing: grouping (section,
priority, due) and the Board's visible columns are part of the presentation, so applying a view puts
them back too. Hiding lanes is a filter and never a deletion — hiding the last one is refused,
because a Board with nothing on it is a bug rather than a preset.

*Evidence:* `PlanOutlineProjectorTest` (folding, filtering, four orders, grouping, stable
tiebreaks), `SavedPlanViewLifecycleInstrumentedTest` (restore, rename, update, duplicate, exclusive
pin), `PlanViewDepthJourneyInstrumentedTest` (the rendered journey, and that the work never
changes), and `PlanAnalysisJourneyInstrumentedTest` (grouping and columns survive the round trip).

### P8 — The width boundaries are checked where they actually are — **done**

The five width classes had unit tests for the classification and one inspected wide screen. The
rendered matrix now runs at 599/600, 839/840, 1199/1200 and 1599/1600 dp on a real window: the
navigation moves from bottom bar to rail exactly at 600, the planning ledger appears exactly at
840, and its width steps at 1200 and 1600. Crossing a boundary keeps the root, the selected day and
an unsaved draft.

*Evidence:* `WidthBoundaryMatrixInstrumentedTest`.

### P9 — The Schedule map draws its dependencies and the room each task has — **done**

`GanttDependencyPaths` computes link geometry and slack bands from rows the canvas has already
placed, and the map draws them as one overlay: elbow connectors between scheduled predecessors and
successors, critical links in the error tone, and a quiet band showing the slack a task has after
its own bar. Three honesty rules are built into the policy rather than the drawing — a link whose
ends are not both scheduled in this range is not drawn, a link that runs right-to-left is flagged
as backwards rather than drawn as if it were fine, and beyond 120 links the map stops drawing and
*says how many it kept back*, pointing at the ledger that lists them all. Slack stops at the edge
of the visible range instead of implying the plan continues off-screen. The overlay takes no
pointer input: dependencies are edited in the ledger, where they have names.

*Evidence:* `GanttDependencyPathsTest` — eligibility, backwards links, the dense-graph subset and
its disclosure, and slack clamping — plus `GanttLinkRenderingInstrumentedTest`, which seeds a link
to unscheduled work and asserts the rendered map discloses it and names the ledger.

### P10 — The screen belongs to the work, not to the description of it — **done**

A measured redesign rather than a preference. On a 1080×2400 phone the Schedule map carried a root
title, a standing subtitle, a segmented control, a six-item control row that ran off the right edge,
a section title, a five-line legend, range chips, a date line and a critical-path line — about
300 dp of chrome — before the first bar. The canvas got roughly a fifth of the window, which is also
why the move panel had nowhere to stand.

What changed:

- **Root subtitles carry live state or nothing.** "Flexible work and fixed commitments" told a
  returning person nothing and cost a line on every root; the slot now holds the board being
  planned, or is absent.
- **The legend became a labelled disclosure.** A "Legend" control opens the same exact wording about
  solid bars, outlined commitments and which schedule the shading used. Learnability does not
  require the explanation to be permanent — it requires it to be findable.
- **The range and critical-path lines merged into one status line**, because two standing lines for
  one sentence of status is a tax on the thing being described.
- **The Plan control row went from six controls to three.** Saved views collapsed into one menu
  named after whichever view is applied — so the row answers "what am I looking at?" before
  offering to change it — beside the Health chip and History. Nothing essential hides behind a
  horizontal scroll with no signifier that it is there. *(P22 later removed the row entirely: the
  three survivors moved into the header's menu, and the workspace became the work.)*
- **Bars became legible.** A one-hour block is under 3 dp wide at the seven-day zoom, and the 4 dp
  floor drew a hairline nobody reads as work; the floor is now 10 dp, with exact times still in the
  label, the description and the move panel.

*Acceptance:* the schedule map starts in the top half of a phone screen, the legend is closed by
default and opens on request, and the status line survives as one line.
*Evidence:* `ScheduleMapProportionInstrumentedTest` measures the ratio on a real device, so chrome
creeping back is a failing test rather than a slow disappointment.

### P11 — One visual vocabulary, enforced rather than agreed — **done**

*Conformity with user expectations* is usually read as "behave like the platform", but it starts
inside the app: the same kind of thing should look the same everywhere. It had stopped doing so, and
the numbers say it plainly — **fourteen different corner radii** (1, 2, 3, 4, 5, 6, 7, 8, 10, 12,
14, 16, 18, 24 dp) and **three sizes for the same inline icon** (16, 18, 20 dp). Nobody chose that;
it accumulated one reasonable-looking literal at a time. A theme shape scale already existed and
almost nothing used it.

Radius now says *what kind of thing you are looking at*, in four values: `mark` for things drawn
rather than pressed, `control` for anything a finger presses, `block` for grouped information, and
`container` for what is layered above the page. Material's own scale is expressed in the same four,
so built-in dialogs and menus match the hand-built surfaces. One `InlineIconSize` covers every icon
that sits beside a label, while an icon that *is* the control keeps the density token, because that
one is supposed to scale with the density setting. A chevron in the Outline was also using the
non-mirrored variant, which points the wrong way for a right-to-left reader.

The point is the enforcement, not the tidy-up. Style review cannot hold a line like this — every
individual diff looks fine — so `UiConsistencyTest` reads the source and fails the build on a raw
radius, a hand-sized inline icon, a non-mirrored directional icon, or a fifth entry creeping into
the scale. It is the same instrument as `ContrastTest`: a claim about the whole UI, checked over the
whole tree. Both rules were verified to fail by introducing violations on purpose before being
trusted.

*Evidence:* `UiConsistencyTest`, plus the recaptured plates in `preview.html`.

### P12 — Zoom belongs to the hand, and the range has a map — **done**

Changing the Gantt's range meant finding a chip. Pinching did nothing, which is the worst answer a
canvas can give a gesture: the person's mental model says "this is a map", and the app says nothing
back. Pinch now moves through the same three ranges the chips offer — 7, 30, 90 days — and keeps the
day under the fingers fixed, so zooming is a magnification of what you were already looking at
rather than a jump to somewhere else. Above the canvas, an overview strip draws the whole planned
span with the visible window marked on it, and tapping it moves there.

The strip is not decoration: it answers "where am I in the plan, and how much of it am I seeing?" —
the gulf of evaluation question a zoomable canvas creates the moment it can zoom. It carries custom
accessibility actions, because a strip you can only use by aiming at 10 dp of pixels is not a
control for everyone.

*Evidence:* `GanttZoomTest` (range selection, focus-preserving start, overview maths, tap-to-day),
`GanttZoomAndOverviewInstrumentedTest` (the rendered gesture and strip).

### P13 — Selecting several tasks, and acting once — **done**

The Outline could only act on one task at a time, so "reschedule these four" was four separate
commands and four History entries — and four chances to be interrupted halfway. Long-press starts a
selection; the count is announced; Back leaves selection without touching the work; and the batch
lands as one journaled command that one Undo takes back. The Board's selection already worked this
way, and the two now behave identically, which is the point: *conformity with user expectations*
includes the app's own expectations.

*Evidence:* `OutlineSelectionAndExportInstrumentedTest`, `PlanBatchMovePolicyTest`.

### P14 — The week, reviewed; the plan, taken elsewhere — **done**

Two gaps at either end of the loop. **Weekly review** answers "what actually happened?" with planned
time, what finished, what carried over, how much the plan moved and what was captured — and, where
the data cannot support a number, says so instead of rounding the week into something flattering.
Unknown effort is reported as a floor, never as zero.

**Export** is the other end: tasks as CSV, schedule as an ICS calendar, both through the ordinary
share sheet. Quoting and escaping are the whole risk here — a task title with a comma or a newline
must not silently corrupt someone's spreadsheet — so both encoders are pure and tested against
exactly those inputs.

*Evidence:* `WeeklyReviewTest`, `PlanExportTest`, `OutlineSelectionAndExportInstrumentedTest`.

### P15 — A planner that proposes, explains, and offers a choice — **done**

Auto-planning is where an app is most tempted to act on someone's behalf. This one never writes
first: it produces a proposal, each block carrying the reason it landed where it did, and everything
it refused to place is listed *with its reason* — a planner that quietly drops the awkward half of
your work is worse than none. Apply writes the whole set as one History entry, or nothing.

A single proposal still hides that a choice was made, so **Compare approaches** runs the same engine
three defensible ways — due date first, priority first, quick wins first — and shows what each
ordering costs in placed work, leftovers and finish date. Choosing one does not apply it; it opens
the ordinary proposal review, where Apply is still a deliberate act. Ordering is passed *into* the
planner rather than written onto the tasks, so comparing approaches cannot change the plan being
compared.

*Evidence:* `AutoPlanTest` (placement, dependencies, buffered commitments, minute alignment),
`PortfolioAndScenarioTest` (the three orderings differ and never mutate the input),
`AutoPlanJourneyInstrumentedTest` and `PlanAnalysisJourneyInstrumentedTest` (nothing is written
before Apply; one Undo takes the week back).

### P16 — Baselines: what moved, and the way back — **done**

A plan that only ever shows its current state cannot answer "is this slipping?". A baseline names
what the schedule looked like at a moment, and the comparison reports drift in **both** directions,
plus work scheduled since and work that has lost its schedule — a variance report that only shows
slippage teaches people to distrust it, and a task the baseline never knew about is not "on time",
it is new.

Restore is deliberately not offered from the list of baselines. It lives inside the comparison,
after the drift is on screen, because an irreversible-looking command with no preview is the kind of
thing people learn never to press. It rewrites blocks only — titles, notes and progress are today's
work, and restoring them would quietly undo real progress in the name of a snapshot — lands as one
History entry, and is itself undoable.

The snapshot is stored in the journal's own field encoding, so a baseline stays readable after the
tasks it describes are edited or archived, and a malformed record refuses to decode rather than
returning half a baseline that would report precise-looking nonsense. Schema version 8 adds the
table and nothing else: every board, key and journal entry survives the upgrade, which the migration
test asserts on a fully populated v7 database. Version 9 then gives it the foreign key it should
have had — see P20.

**Deleting one asks first**, which is the correction this feature needed after it was first built.
Every other destructive act in the app is a journaled command that Undo reverses, so it can be
offered directly; a baseline is not plan state, has no journal entry, and its Delete sits one tap
from Compare. The first version shipped that as a bare tap — the same class of error robustness
failure this document was written to find, introduced while fixing others. It now names the date
being discarded and says the act cannot be undone, matching how deleting a saved view already
behaved.

*Evidence:* `BaselineVarianceTest` (drift both ways, new and dropped work, split blocks, round-trip,
truncated payload refused), `PlanAnalysisTextTest` (the drift sentences, including the singular
minute), `PlanMigrationInstrumentedTest.migrationFrom7AddsBaselinesWithoutDisturbingAnythingElse`,
and `PlanAnalysisJourneyInstrumentedTest` (take, drift, compare, restore, undo; and that Delete asks
before it destroys).

### P17 — Every plan at once, without laundering the uncertainty — **done**

A rollup is the easiest place in an app to make an over-committed portfolio look comfortable: sum
enough columns and unestimated work silently becomes zero effort. This one counts tasks with no
stated effort, reports them per board, and says plainly in the note above the rows whether the
totals are exact or a floor. It reads each board once on demand rather than observing every board
continuously, because a rollup is something a person opens to think with.

*Evidence:* `PortfolioAndScenarioTest`, `PlanAnalysisJourneyInstrumentedTest`.

### P18 — The new dialogs at the largest font scale — **done**

Three dialogs shipped without the 2× check this repository requires of every new layout, which is
how a fixed-height body and a two-button row quietly become a truncated sentence. At `fontScale = 2f`
all four analysis surfaces keep their asserted text unclipped and every action at or above the 48 dp
target. The strings chosen are the ones that would invert their meaning if cut — a drift line, a
scenario name, a board name, a baseline's own name.

The audit also caught the prose: a one-minute drift read "1 minutes later than the baseline" because
days and hours pluralised and minutes did not. One helper now owns the plural, and the sentences are
tested as sentences.

*Evidence:* `UiAccessibilityInstrumentedTest` (four dialogs at 2×), `PlanAnalysisTextTest`,
`PlanAdaptiveWorkspaceInstrumentedTest` (both window sizes offer the same commands).

### P19 — The journal had to learn to say "this row did not exist" — **done**

Re-auditing the tranche found three defects in the same place, and they are the most serious in this
document because each one lied about recoverability.

A baseline restore *replaces* a set of blocks, and the two sides rarely name the same rows — a block
deleted since the baseline exists on one side only. The journal requires both sides of a command to
describe the same targets, and its block group could only say "these rows have these values", never
"this row was not there". So:

- restoring a baseline that cleared the schedule recorded an after-state identical to the
  before-state — the entry claimed nothing had changed while the blocks were gone, and Undo then
  refused as stale. **The blocks were unrecoverable.**
- restoring a baseline whose rows had been recreated since failed outright with an internal message
  about differing targets.
- and separately, undoing a *multi-block* auto-plan called a single-target accessor on a
  many-target entry, so the whole week's Undo threw instead of reversing. Only the single-block
  path had ever been exercised.

`PlanBlockGroupState` now carries `absentIds`, encoded as a reserved one-field record that a damaged
block record can never be mistaken for, so the fail-closed rule is unchanged. Both sides of a
replacement span the union of ids, Undo removes what the command created as well as restoring what
it overwrote, and the create/delete inverses iterate every target instead of assuming one.

The lesson is the same one Section 2 keeps making: all three were invisible to source review and to
every pure test. They needed a test that took a baseline, changed the plan underneath it, restored,
and then asked for the schedule back.

*Evidence:* `PlanMutationCodecTest` (absence round-trips; a damaged record still fails closed; a row
cannot be present and absent at once), `PlanBaselineRepositoryInstrumentedTest` (replacement with
changed row identity, an emptied schedule, and a multi-block plan undone in full).

### P20 — A baseline of a plan that no longer exists — **done**

`saved_views` cascades when its board is deleted; `plan_baselines` shipped with no foreign key at
all, so deleting a plan left its baselines behind forever, describing a board that could never be
opened again. Schema version 9 rebuilds the table with `ON DELETE CASCADE` — SQLite cannot add a
constraint in place — copying every row whose board still exists and dropping any already orphaned,
because keeping those would make the new constraint unsatisfiable.

One scan in the same pass produced a *false* finding worth recording, because the discipline that
caught it is the point of Section 2. Six files fold text with `lowercase()`, and the argument that
this breaks under Turkish case rules is correct for Java's `toLowerCase()` — but Kotlin's no-argument
`lowercase()` is locale-invariant by design, so there was no defect. The check that proved it was
reverting the "fix" and watching the new test still pass. The edit was undone rather than left in
place as harmless-looking churn carrying a comment that stated something untrue; the test stayed,
rewritten to say what it actually guards, and it now fails if any of those folds is made
locale-sensitive.

*Evidence:* `PlanMigrationInstrumentedTest.migrationFrom8RebuildsBaselinesWithTheirForeignKeyAndKeepsRealRows`,
`PlanBaselineRepositoryInstrumentedTest.deletingAPlanTakesItsBaselinesWithIt`, `FuzzyTest`.

### P21 — The phone it is actually held on — **done**

Reported from a real device, which is the evidence layer Section 5 says this document cannot reach
on its own: *"everything looks very inconsistent, and it doesn't optimise itself according to the
phone's display size."* Both halves were true, and reproducing the app at four real geometries —
360×640, 411×914, 448×997 and 308×685 dp — showed why within minutes.

Every phone is `WindowWidth.Compact`. The class spans 320 dp to 599 dp, so nothing in the app ever
branched on how big a phone actually is; the 411×914 emulator was the only size anyone had looked
at. At 360 dp:

- the Outline's control row put three chips in a `Row`, and a `Row` gives the last child whatever
  the others left — **"Hide done" rendered one letter per line**, a column of letters;
- the Plan Health chip wrapped to three lines and took more height than the workspace it described;
- Home used a bespoke header — 25 sp, no `maxLines` — while Calendar and Plan used the shared one at
  21 sp with ellipsis, so **only Home wrapped**, into four lines with its actions floating in the
  middle of them;
- the Schedule map's chrome left the canvas under one row: the day header drew and not a single
  bar, so there was nothing to read and nothing to long-press.

The fixes are the boring ones: `FlowRow` where controls must wrap, one line and an ellipsis where a
status line is a status line, Home on the shared header treatment, and the standing "FLEXIBLE WORK"
label deleted — it named the screen you were already looking at. The adaptive part is new:
`LocalWindowWidthDp` and `LocalWindowHeightDp` carry the measured size so tokens can respond inside
a width class as well as across them, `rootTitleSizeFor` steps type down at 380 dp and 340 dp,
`gutterFor` narrows the margin on the tightest phones, and `isShortWindow` drops the Schedule map's
subtitle and section title on a screen that has no height to spend on naming itself twice.

The Schedule map needed one more pass, and the first two attempts at it were wrong in instructive
ways. A minimum height on the canvas made it *worse*: a weighted child with a floor larger than the
space left does not push the chrome up, it overflows the column, and the canvas lands below the
window where its bars measure zero by zero. Trimming the status line was not enough either. Printing
the semantics tree with bounds ended the guessing in one run — the canvas viewport was 76 dp, the
axis and one row need 111, and the missing 48 was being spent on the overview strip. A map of the
map is worth its height only while the map has any, so on a short window the strip stands down.

`GanttSmallScreenInstrumentedTest` now asserts what the eye was checking: a bar is *on screen*, not
merely in the tree, and a long press still takes it into move mode. It passes at 308×685, 360×640
and 411×914, and the full capture harness — which could not finish at either small size — now walks
every screen at all four geometries.

*Evidence:* `AdaptiveLayoutTest` (the steps, and that they are monotonic),
`PlanAdaptiveWorkspaceInstrumentedTest` (both control rows rendered at 360 dp minus the gutter, with
the crushed-chip signature asserted against), `GanttSmallScreenInstrumentedTest` (a bar on screen and
a working long press at three sizes), and four rendered geometries captured before and after.

### P22 — The screen belongs to the work, again — **done**

Asked for plainly: *"I don't want you to keep the unwanted info like the health check and all… keep
everything minimal… this app has good features but still feels a lot un-organised."* Both halves are
fair, and the second explains the first — the features were not the problem, their placement was.

Before a single task appeared, the Plan spent a saved-views control, a standing Plan Health chip, a
History button, a board summary and three presentation chips. Home opened with five cards including
Plan Health. Every one of those is a real feature and none of them is what a person opens the app to
see.

The rule now is that a root screen shows its work. `WorkspaceRootHeader` gained an overflow slot, and
the Plan's `⋮` holds five entries, each opening one focused place:

- **View options** — order, grouping, what is hidden, and which saved view is applied. All of it is
  the same kind of decision, so it is answered in one dialog rather than across a chip row, a menu
  and a rail.
- **Plan health**, **Plan tools** (plan my week, compare approaches, weekly review, baselines,
  across every plan), **History**, and export.

Home opens with Now, Up next and anything clashing. Plan Health, the Board preview, quick actions
and the day bar are all still there, one tap away under Edit, because they answer questions nobody
asked on opening the app.

The measure is the same one this document has used throughout: on a 360×640 phone the Outline showed
**no tasks at all** before and shows two tasks and a section header now. Two composables became dead
code in the process and were deleted rather than left behind.

*Evidence:* `PlanAdaptiveWorkspaceInstrumentedTest` (the overflow offers every command; the tools
dialog names what each does; View options survives 360 dp without crushing a chip), and every Plan
journey now navigates through `PlanMenu`, one helper, so the next restructure moves in one file.

### P23 — The same pass, everywhere it had become the odd one out — **done**

Restructuring the Plan made the screens around it look wrong, which is the ordinary cost of changing
one surface and the reason to keep going rather than stop. Photographing every screen at 360 dp named
the offenders without argument:

- **The agenda put a wide "Actions ▾" button on every row.** Six rows meant six dropdowns competing
  with the titles they belonged to, and on a narrow screen they took the width the titles needed. It
  is the same overflow icon the rest of the app uses now; the menu, the swipe actions and the custom
  accessibility actions are all unchanged.
- **Every agenda row repeated its source.** "Device Calendar" under six consecutive events tells a
  person nothing they did not already know. The badge now appears only on a row this app cannot edit,
  which is the case where the source explains a limitation instead of restating a fact.
- **The Board carried a second overflow menu** and a "Schedule" button that duplicated the Gantt tab
  sitting directly above it. The button is gone, and which lanes are shown moved into View options
  with every other presentation choice.
- **The Schedule map's chip row mixed navigation with reference.** 7/30/90 days is how you move
  around the canvas and stays on it; dependencies and the legend are read once, so they moved into
  the map's own menu.

The rule that came out of it, now in the working notes: a root screen shows its work, and a standing
control is the thing to argue for rather than the default.

*Evidence:* the full suite still passes through `PlanMenu` and its new `openMapOption`, and
`preview.html` is recaptured from the restructured app.

### P24 — The schedule map became a place you can work — **done**

Asked for directly: *"the Gantt chart should have a whole page view like the timeline does to make it
more readable and editable, it should also have drag and drop."* Both halves were fair. Inside the
Plan the map is a chart under a root header, a tab row and a board line; on a 360×640 phone that left
it about a fifth of the window. The day timeline, by contrast, gives its canvas the page — which is
why blocks there feel like objects you can pick up and the map's did not.

The map is now a destination of its own, marked `immersive`: the bottom bar and rail stand down, the
page carries its own back arrow, title and menu, and Back returns to the Plan. The canvas went from
roughly a fifth of the window to about three quarters — from one row and a day header to five rows
and a legible chart.

**Dragging.** On the page a horizontal drag on a bar picks it up and moves it in one gesture. The
first attempt keyed the pointer input on `selected`, which was wrong in a way worth recording:
picking the block up flips that flag, re-keying restarts the pointer input, and the gesture that did
the picking up is cancelled — the panel opened saying "No preview change yet" and the person had to
drag a second time, which is precisely what dragging is meant to avoid. Keyed on the block instead,
one drag moves it.

What did *not* change is the rule: dragging writes nothing. Release leaves the preview open with the
exact times, and Apply is still the only thing that commits — during the first device run the drag
put a block at 11:15 PM on a Saturday and the panel said "Cannot apply: 90 minutes fall outside
configured working time" with Apply disabled, which is the fail-closed promise doing its job live.

Three smaller decisions came out of rendering it: the page takes the status-bar inset itself (as a
tab it inherits one, and taking it twice leaves a band of nothing); the label column widens to 190 dp
on the page, because at 158 dp it was breaking "Migrate" into "Migr / ate"; and bars are 36 dp rather
than 30 dp there, because a bar you are about to drag with a thumb should look like something a thumb
can land on.

**Not done, and named rather than glossed:** the date axis still scrolls away with the rows. Pinning
it means hoisting the axis out of the vertical scroll while keeping the shared horizontal scroll, and
the dependency overlay is positioned in coordinates that assume the axis is inside it. That is a real
refactor of the most intricate layout here, and bolting it on at the end of a large change is how the
overlay quietly breaks.

*Evidence:* `ScheduleMapPageInstrumentedTest` — the page opens, the bottom bar stands down, one drag
enters the move preview without a long press, dragging writes nothing, Cancel leaves the schedule
exactly as it was, and the way back returns to the Plan.

### Still open

Nothing from the delivery backlog remains unbuilt. What is still open is *evaluation*, not
implementation — see Section 5.

---

## 4b. Next, in order of what it would buy

Written after the minimalism pass, from the same evidence: every screen photographed at 360×640,
411×914 and 308×685 dp. These are ordered by how much they change an ordinary morning, not by how
interesting they are to build.

1. **The first five minutes.** Every empty state says what is missing; none says what to do about it.
   A person who installs this, grants nothing and captures nothing sees three empty rooms. The fix is
   one sentence and one action per root — connect a calendar, capture a task, plan a week — and it is
   the difference between an app that works and an app someone keeps.
2. **The agenda row is four lines tall for a one-line fact.** Time, title, location, source badge and
   a menu, stacked, for "Team stand-up at 9:30". Tightening this doubles the number of events a
   person sees at once, which is the whole job of an agenda.
3. ~~**The Board card spends its height on a progress bar and a chevron.**~~ Withdrawn on inspection:
   the "chevron" is the move-to-next-lane control, the keyboard and screen-reader route to the same
   thing dragging does. Deleting it would have removed a real affordance to save a few dp. The
   progress bar beside its percentage is still arguably redundant, but that is a smaller claim than
   the one this item made.
4. **The Calendar date header takes about 110 dp** to say "Today, Wednesday 12 August" with three
   controls around it. One line would do, and the swipe gesture already moves days.
5. **Home's greeting is decoration.** "Good morning" is the largest text on the screen and the least
   useful; the date beneath it is already in the system bar. A root title that named the day's shape
   would earn the space it takes.
6. **Settings opens six accordions on a list that fits.** The disclosure was right when categories
   were long; several are two rows now, and opening one closes another, which is a cost with no
   remaining benefit.

Each is a rendered-evidence item: none of them can be settled by reading the source, and each has a
measurement that would say whether it worked.

### What that list produced

Items 1, 2 and 4 are done, and the numbers are from the same 360×640 screen photographed before and
after:

- **Empty states now offer the next step.** A day with no calendar connected says so and offers to
  connect one; a day that is genuinely free says that instead, because those two want opposite
  things said to them. An empty Plan offers to capture the first task. `EmptyState` is one component
  so the three roots cannot drift apart again, and it takes no action when there is honestly no next
  step to offer.
- **The agenda went from three-and-a-half events on screen to five.** The per-row "Actions ▾" became
  the overflow icon the rest of the app uses, and the repeated source badge now appears only on rows
  this app cannot edit.
- **The day header is one line.** "Today" over "Wednesday, August 12" was two sizes of one fact. The
  first attempt joined them with a dot and truncated at 360 dp — worse than what it replaced — so the
  header shows the identifying label alone and the long form goes to the screen reader, where it
  costs no height.

Item 5 — Home's greeting — is closed: the root now gives the primary title to the day and keeps
the time-of-day greeting as secondary context. Item 6 — the Settings accordion default/open model
— remains a product-evaluation question rather than an unverified implementation defect. Item 3
was withdrawn above.

## 5. What this document does not claim

Every finding here was produced by source audit plus device runs. That is enough to *find* a defect
and to prove a fix compiles, runs, and behaves — it is not enough to declare the principle satisfied
for real people. Specifically still open, unchanged from Section 8D of the delivery plan: manual
TalkBack, Switch Access, and Voice Access passes; hardware keyboard, mouse, trackpad, and stylus;
200% text and display scaling on real devices; RTL and long-localised text; API 24 and API 37.1
with 16 KB pages; physical calendar providers; and task-based sessions with people who did not
build this, including at least one screen-reader or switch user.

ISO's own framing is the reason for that caution: these are principles for *analysis, design, and
evaluation*, and evaluation means watching someone try.

---

## Sources

- [ISO 9241-110:2020 — Ergonomics of human-system interaction — Part 110: Interaction principles](https://www.iso.org/standard/75258.html) — the normative record; [ISO Online Browsing Platform entry](https://www.iso.org/obp/ui/#iso:std:iso:9241:-110:ed-2:v1:en)
- [DialogDesign — ISO's dialogue principles (2020)](https://www.dialogdesign.dk/isos-dialogue-principles-2019/) — the seven principles with their definitions and recommendation categories
- [UXQB CPUX-F curriculum](https://uxqb.org/public/documents/CPUX-F_EN_Curriculum.pdf) — the seven interaction principles, and the distinction between interaction principle, heuristic, and user interface guideline
- [usability.de glossary — Interaction principles](https://www.usability.de/en/usability-user-experience/glossary/interaction-principles.html) — notes the rename from dialogue to interaction principles
- Don Norman, *The Design of Everyday Things*, revised edition — the seven fundamental principles ([publisher page](https://www.hachettebookgroup.com/titles/don-norman/the-design-of-everyday-things/9780465050659/))
- [Don Norman — Signifiers, Not Affordances](https://jnd.org/signifiers-not-affordances/) — "what people need, and what design must provide, are signifiers"
- [Seven stages of action](https://en.wikipedia.org/wiki/Seven_stages_of_action) — the action cycle and the gulfs of execution and evaluation used as this audit's lens
- [W3C — What's New in WCAG 2.2](https://www.w3.org/WAI/standards-guidelines/wcag/new-in-22/) — 2.4.11, 2.5.7, 2.5.8, 3.2.6, 3.3.7 and the rest of the 2.2 additions
- [W3C — Understanding 2.2.1 Timing Adjustable](https://www.w3.org/WAI/WCAG22/Understanding/timing-adjustable.html) — the rule behind finding CT-1
- [W3C — Understanding 4.1.3 Status Messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html) — the rule behind finding SD-2
- [Android — Adaptive app quality guidelines](https://developer.android.com/docs/quality-guidelines/large-screen-app-quality) — tiers, external input support, and the test device matrix
- [Android — Accessibility in Compose](https://developer.android.com/develop/ui/compose/accessibility) and [Semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics) — custom actions, live regions, and semantics used by P2 and P3
