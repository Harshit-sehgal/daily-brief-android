# Daily Brief — working notes

A local-first Android app: device calendar + Notion in one schedule, overlap
detection, and an optional Gemini summary. Compose UI, Room storage, no DI
framework.

Two Gradle modules. `:app` is the Android app. `:planning-core` is the scheduling
engine and the domain model it works on — extracted so a server can run the same
code the phone runs. It is a Kotlin Multiplatform module: `commonMain` holds the
portable half, `jvmShared` holds everything still needing JVM APIs, and both the
`jvm` target and Android depend on `jvmShared`. Moving a file from `jvmShared` to
`commonMain` is the unit of porting work; `docs/saas/05-kmp-portability-audit.md`
tracks what is left and why.

**`commonMain` purity is enforced by a test, not by the compiler.** This is
counter-intuitive and worth knowing before trusting a green build:
`compileCommonMainKotlinMetadata` exists but is **SKIPPED**, because Kotlin only
produces a metadata compilation once some target needs one, and `jvm` plus
`androidTarget` are both JVM-family. A file importing `java.util.Calendar` in
`commonMain` therefore compiles without complaint — confirmed by planting one.
The check fails *open*, which is worse than absent, so `CommonMainPurityTest`
reads the sources instead, the way `UiConsistencyTest` reads the UI. Adding a
non-JVM target (Kotlin/Native is roughly a gigabyte, and not in the pinned
toolchain) would hand the job back to the compiler.

## Build and test

This machine has a pinned toolchain at `~/.local/android-toolchain` (JDK 21 +
SDK). The system `java` is 8, so **`JAVA_HOME` must be set** — the scripts do it
for you, and every dependency is cached, so Gradle runs `--offline`.

```bash
scripts/verify.sh --fast      # unit tests — the inner loop, ~2s warm
scripts/verify.sh             # the whole CI gate: tests, both lints, both APKs, R8
scripts/emulator.sh           # boot + unlock + wake an emulator (~10s from a snapshot)
scripts/verify.sh --device    # the gate plus instrumentation on that emulator
scripts/smoke-release.sh      # install and launch the *minified* build — catches R8 damage
scripts/capture-preview.sh    # recapture preview.html's plates from the running app
scripts/embed-preview-plates.py  # ...then inline them into preview.html
```

Run `scripts/verify.sh` before reporting work finished. It is the same task list
CI runs, so a green run here is a green run there.

**Emulator:** use the `dailybrief` AVD (API 36) — it has a boot snapshot and comes
up unlocked. Two device states fail every Compose test in ways that look like
test bugs, and `scripts/emulator.sh` handles both:

- `Unable to resolve activity` / "credential encrypted storage … until user (id 0)
  is unlocked" — user 0 is still locked, so direct boot hides the app.
- `No compose hierarchies found in the app` — the screen is asleep, so the
  activity never resumes.

If `adb install` fails with `Failure calling service package: Broken pipe`, push
the APK to `/data/local/tmp` and `pm install -r -t` it instead.

## Where things live

| Layer | Package | Notes |
| --- | --- | --- |
| Pure schedule maths | `:planning-core` `core/` | No Android, no Compose — unit-tested directly on the JVM |
| Domain model | `:planning-core` `data/model/` | The Room entities. They still carry Room annotations, which is the one thing keeping them out of `commonMain` |
| Storage | `data/database/` | Room; schema is exported to `app/schemas/` |
| Sources | `data/api/` | `DeviceCalendarSync`, `NotionClient`, `GeminiClient` |
| Secrets | `data/security/SecretStore` | Keystore-backed; never in Room |
| Coordination | `data/repository/BriefingRepository` | The only place sources and storage meet |
| State | `ui/viewmodel/BriefingViewModel` | One view model for the whole app |
| Screens | `ui/screens/`, `ui/components/` | Compose only; no business rules |
| Alarms | `receiver/` | Inexact alarms; re-armed from the receiver and after boot |

Logic worth testing gets extracted to a pure object rather than tested through
the database — `ScheduleAnalysis`, `TimelineLayout`, `SyncMergePolicy`,
`WorkspacePreferencePolicy`, `EventDraftEdits`, `DayPulse`. Follow that pattern
for anything new. Date arithmetic especially: it is where midnight, DST and
"the picker hands back UTC" quietly go wrong.

## Invariants worth knowing

- **Every module compiles against SDK 37.1, never bare 37.** The pinned SDK has
  `platforms;android-37.1` and nothing else, so `compileSdk = 37` asks for 37.0 and sends
  Gradle to the network for a platform that is not there. Because the download reports no
  task progress, the build looks hung rather than failed — it sat for thirteen minutes
  before this was spotted. `:app` writes `compileSdk { version = release(37) { minorApiLevel = 1 } }`
  and `:planning-core` writes the same thing inside its `androidLibrary` block.
- **Kotlin does not smart-cast a `val` it does not own.** Now that the domain model lives in
  `:planning-core`, `if (row.dayOfWeek != null) use(row.dayOfWeek)` no longer compiles in
  `:app` — the compiler will not assume a property from another module is stable. Bind a
  local first (`val dayOfWeek = requireNotNull(row.dayOfWeek)`), which is what
  `WorkingCalendarMapper`, `PlanRepository` and `HomeScreen` now do. Expect this on every
  nullable field the app reads off a model type.
- **The source owns times; the user owns wording.** `SyncMergePolicy` is the only
  place that decides what a re-sync may overwrite. Change it with tests.
- **Deletes fail closed.** A read-only source (Notion, a repeating series,
  write-back switched off) refuses the delete and the row stays. Callers must
  handle "not deleted" — the agenda row un-swipes itself when that happens.
- **One schedule lane.** `BriefingRepository.SCHEDULE_MUTEX` serializes every
  sync and event mutation process-wide, so a fetch in flight can never commit
  stale times over an edit the user just made. Provider I/O sits inside that lock
  on purpose; keep network calls bounded by timeouts.
- **Alarm work is off the main thread.** Every `AlarmScheduler` entry point
  suspends — laying down a horizon is a ledger read, a binder call per event and
  a ledger write.
- **Room schema changes need a migration**, not a wipe: saved integrations, keys
  and boards have to survive. Bump the version, add a `Migration`, and let the
  exported schema in `app/schemas/` update.
- **Recovery windows belong to the user.** Undo used to expire after a fixed 30
  seconds everywhere, which is a content-imposed time limit (WCAG 2.2.1 Timing Adjustable) and
  about how long it takes a screen reader to reach the snackbar. `UndoWindowPolicy`
  owns the choice; both journal writers read it at write time, and malformed
  storage falls back to the *default*, never to the shortest window. Widening it is
  safe because Undo is compare-and-set — a stale entry refuses rather than
  overwrites.
- **Four radii, one inline icon size.** `Radius.mark/control/block/container` says what kind of
  thing a corner belongs to, and `InlineIconSize` covers every icon beside a label; an icon that is
  itself the control keeps the density token. The UI had drifted to fourteen radii and three icon
  sizes, so `UiConsistencyTest` reads the source and fails on a raw literal, a non-mirrored
  directional icon, or a fifth radius. Add a value only by changing the scale, deliberately.
- **The schedule map has a page of its own.** `Destination.ScheduleMap` is `immersive`: no bottom
  bar, no rail, no create button, Back returns to the Plan. Inside the Plan tab the same screen is a
  chart under a header, a tab row and a board line; as a page it gets the window the day timeline
  has, which is what makes dragging a bar reasonable. On the page a horizontal drag picks the block
  up directly — no long press — and the pointer input is deliberately **not** keyed on `selected`,
  because re-keying would cancel the gesture that did the picking up. Release still writes nothing;
  Apply is the only thing that commits, everywhere.
- **A root screen shows its work.** Everything a Plan can do that is not the work — saved views,
  health, tools, history, export — lives behind the one `⋮` in `WorkspaceRootHeader`'s overflow slot,
  opening a focused dialog. It used to be a control row plus a chip row above every task, which on a
  360 dp phone was the entire screen. Home opens with Now, Up next and anything clashing;
  everything else is a card a person adds under Edit. Adding a standing control to a root is the
  change to argue for, not the default.
- **Chrome is charged rent.** The Schedule map once spent ~300 dp on a title,
  subtitle, legend paragraph and two status lines before the first bar, leaving the
  canvas a fifth of the phone. Standing explanations belong behind a labelled
  disclosure, root subtitles carry live state or nothing, and status shares a line.
  `ScheduleMapProportionInstrumentedTest` measures the ratio so it cannot creep back.
- **A keyboard shortcut must claim its key-down.** Acting on key-up while letting
  key-down fall through hands the key to focus traversal first: the focus ring
  moves, the key-up lands on a different node, and the shortcut silently does
  nothing. The Board's Alt+Arrow move returns `true` for both, and acts on the up.
- **Back leaves the mode, not the screen.** Every transient mode (Gantt move,
  Board Select, Home layout editing) owns a `BackHandler`. Without one, Back reaches
  the app-level navigation handler and silently discards an unsaved preview.
- **Manipulation targets may not overlap.** A one-hour Plan block is under 3 dp
  wide at the Gantt's 7-day zoom, so drawing the move target and its two 48 dp
  handles at their literal boundaries buries the bar under its own handles, and a
  bottom-aligned preview panel covers the row entirely. `GanttDirectManipulationTargets`
  lays the three regions out as neighbours and the panel takes layout space. This
  is invisible to source review and to every pure test — it took a rendered device
  journey to catch, so `GanttDirectManipulationJourneyInstrumentedTest` asserts the
  bounds do not intersect.
- **Every phone is one width class.** `Compact` spans 320–599 dp, so the bucket alone cannot tell a
  308 dp screen (a large phone with Display size turned up) from a 430 dp one, and a layout that
  only branches on the class does not adapt to phones at all. `LocalWindowWidthDp` and
  `LocalWindowHeightDp` carry the measured size; `rootTitleSizeFor`, `gutterFor` and `isShortWindow`
  read it. Check new screens at 360×640 and 308×685, not just the emulator's 411×914.
- **A control row wraps or it crushes.** `Row` hands the last child whatever width the others left:
  at 360 dp that rendered "Hide done" one letter per line and wrapped the health chip into three.
  Rows of chips and actions use `FlowRow`; a status chip is one line with `TextOverflow.Ellipsis`.
  `PlanAdaptiveWorkspaceInstrumentedTest` renders them at 360 dp minus the gutter and fails when a
  control comes out taller than it is wide.
- **A weighted child cannot be given a floor.** `Modifier.weight(1f).heightIn(min = …)` larger than
  the space left overflows its Column rather than pushing the chrome up: the child is placed below
  the window and everything inside it measures zero by zero. Room for a canvas comes from spending
  less above it — on a short window the Schedule map drops its section title, subtitle, status line
  and overview strip, in that order. `GanttSmallScreenInstrumentedTest` asserts a bar is *on screen*
  and still long-pressable; run it with the device resized to 720x1280 @ 320dpi.
- **A dp container must never hold sp text.** Density tokens are dp; text is sp
  and scales with the user's font setting, so a fixed-width column clips at large
  scales — and a clipped time reads as a different time. `gutterWidthFor` measures
  the widest sample with the *merged* text style (the theme's letter spacing is
  part of the width) and grows the column. Tested at 2× in `UiAccessibility…`.
- **The palette's AA claim is tested.** `ContrastTest` walks every text pair in
  both themes for all six accents. Hairlines are deliberately quieter than WCAG's
  non-text bar; the test pins that floor rather than pretending otherwise.
- **Secrets never touch Room.** `SECRET_SETTING_KEYS` routes them to
  `SecretStore`; `writeSettingsAtomically` rejects them outright.
- **A journal entry must be able to say a row was not there.** Both sides of one command name the
  same target ids — `insertMutation` enforces it — so a command that *replaces* rows (a baseline
  restore) records the union, with the ids each side lacks in `PlanBlockGroupState.absentIds`.
  Without that, an emptied schedule recorded an after-state equal to its before-state: the entry
  claimed nothing changed, Undo refused as stale, and the blocks were gone. Undo's inverses iterate
  every target, never `singleTargetId()`, or a multi-block command throws instead of reversing.
- **A plan-scoped table cascades from its board.** `saved_views` did; `plan_baselines` shipped
  without a foreign key and left orphans behind a deleted plan. Schema v9 rebuilds it with
  `ON DELETE CASCADE` — SQLite cannot add a constraint in place, so the table is recreated and its
  rows copied, dropping any already orphaned. Check any new plan-scoped table against this.
- **A baseline is history, not a second copy of the plan.** `PlanBaselineCodec` stores it in the
  journal's own field encoding — not foreign keys — so it survives the tasks it describes being
  edited or archived, and a malformed record throws rather than returning half a baseline whose
  variance would look precise. Restore rewrites *blocks only*: titles, notes and progress are
  today's work, and putting them back would quietly undo real progress in the name of a snapshot.
  It goes through the journal, so it is one History entry and Undo takes it back.
- **A total that cannot be complete must say so.** `PortfolioRollup` and `WeeklyReview` count the
  tasks that state no effort and report the number, because summing a column silently turns unknown
  effort into zero — which makes an over-committed plan look comfortable. Any new aggregate follows
  the same rule: report the floor and name it as one.
- **Analysis never writes.** `AutoPlan`, `PlanScenarios`, `BaselineVariance` and `WeeklyReview` are
  pure; the proposal is shown before anything is applied, and choosing a scenario opens the same
  review rather than committing it. Ordering is passed *into* the planner (`preferredOrder`) rather
  than written onto the tasks, so comparing approaches cannot change the plan being compared.

## Conventions

- 2-space indent, ~100 columns, trailing commas. Match the file you are in.
- Comments explain *why*, not what, and are worth writing when a line looks
  wrong until you know the reason. Skip narration of the obvious.
- User-facing strings are sentences, not shouty labels; every interactive
  element carries a content description or click label.
- Interactive Compose primitives honour `MinimumTouchTarget` (48dp) regardless
  of the density setting — visual density changes what fits, never the hit area.
- Check new layouts at a 2× font scale (`adb shell settings put system font_scale 2.0`).
  It is where fixed-width columns, single-line labels and tight rows break.
- `preview.html` is a screenshot gallery of the real app, not mockups. If the UI
  changes visibly, recapture rather than letting it drift: `capture-preview.sh`
  walks the screens on an emulator, `embed-preview-plates.py` inlines the PNGs
  keyed by each plate's `data-full`. The page keeps the words; the script only
  replaces pixels. The harness behind it is `@CaptureOnly`, so it never joins the
  gate — it seeds rows and flips settings that other tests would trip over. It
  leaves that state on the device, so `capture-preview.sh` ends with `pm clear`;
  without it the next `verify.sh --device` fails in ways that look like real
  regressions (a missing Home card, a row that will not scroll, a wait that times
  out) but are only the seeded data.
