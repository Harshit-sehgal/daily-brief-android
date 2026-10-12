# Daily Brief — working notes

A local-first Android app: device calendar + Notion in one schedule, overlap
detection, and an optional Gemini summary. Compose UI, Room storage, no DI
framework.

Four Gradle modules. `:app` is the Android app. `:planning-core` is the scheduling
engine and the domain model it works on — extracted so a server can run the same code
the phone runs. It is a Kotlin Multiplatform module: `commonMain` holds the portable
half, `jvmShared` holds everything still needing JVM APIs, and both the `jvm` target and
Android depend on `jvmShared`. Moving a file from `jvmShared` to `commonMain` is the unit
of porting work; `docs/saas/05-kmp-portability-audit.md` tracks what is left and why.
`:planning-contract` is the frozen wire contract (docs/saas/04-planner-api-contract.md):
Kotlin Multiplatform, kotlinx-serialization only, golden byte-identical files — nothing
in the SaaS stages starts until it is stable. `:server` is the SaaS service (WP-13): Ktor + Flyway +
Postgres, unit-tested (`:server:test` in the gate) and acceptance-tested by
`scripts/journey.sh`, which runs the whole signup→plan→apply→undo loop against docker
Postgres in ~1.5 s; `scripts/verify.sh --journey` is the gate plus that acceptance. The
`apps/web/` client (Next.js: Today, Week, Projects, Settings) talks to it; the server is authoritative
for Apply. `apps/mobile/` is the Expo app (Login, Today, Planner, Settings) on the same
server; its `planner-engine` local module runs `packages/planning-core`'s `EnginePreview` on-device
for preview — the server stays authoritative for Apply. `:planning-contract` is now KMP
too (WP-M3), so the frozen wire types are portable with the engine; `Mapping` and
`EnginePreview` live in `packages/planning-core`'s `commonMain`, and the mobile preview meets a
server run at `EnginePreview` — same computation, same proposals; `EnginePreviewTest` pins
the equality.

**`commonMain` purity is enforced by the compiler, with a fast scan in reserve.** The
`linuxX64` target (WP-7) gives the metadata compilation a consumer, so
`compileCommonMainKotlinMetadata` executes and a stray `java.util.Calendar` import fails
the build — the gate runs it (`verify.sh`), and the acceptance was proven by planting one.
It is worth knowing the target only *compiles*: `LegacyNameKeys` has a native `actual`
that throws rather than approximate NFKC, because Android and the JVM server are the only
surfaces that run the engine. `CommonMainPurityTest` remains as the fast duplicate with the
better error message, and it scans for the JVM-only stdlib class the compiler caught first:
`toSortedMap`/`toSortedSet`, `@JvmOverloads`, `String.format` and `java.lang.System` are
not imports, so they need explicit rules. The native toolchain (~1.8 GB in `~/.konan`)
wants one `verify.sh --online` run; `--offline` holds afterwards.

## Build and test

This machine has a pinned toolchain at `~/.local/android-toolchain` (JDK 21 +
SDK). The system `java` is 8, so **`JAVA_HOME` must be set** — the scripts do it
for you, and every dependency is cached, so Gradle runs `--offline`.

```bash
scripts/verify.sh --fast      # unit tests — the inner loop, ~2s warm
scripts/verify.sh             # the whole CI gate: tests, both lints, both APKs, R8
scripts/verify.sh --journey   # the gate plus the WP-13 acceptance (needs docker)
scripts/emulator.sh           # boot + unlock + wake an emulator (~10s from a snapshot)
scripts/verify.sh --device    # the gate plus instrumentation on that emulator
scripts/web-journey.sh        # web acceptance: docker Postgres, server, next build, a rendered
                              # browser run, and screenshots of both surfaces into
                              # build/web-journey-evidence. Set DAILYBRIEF_WEB_PORT /
                              # DAILYBRIEF_WEB_API_PORT / DAILYBRIEF_WEB_DB_PORT if those are taken.
(cd apps/web && node e2e/mock-api.mjs)  # a believable week with no server, for UI work; then
                              # `next dev` with NEXT_PUBLIC_FIXTURE=1 NEXT_PUBLIC_API_BASE=http://localhost:8097,
                              # `node e2e/notebook.mjs` (gestures, ~15 s; DAILYBRIEF_BROWSER=firefox|webkit
                              # for the other engines) and `node e2e/shots.mjs` (screenshots)
scripts/share-tunnel.sh       # a temporary public link from this machine (Cloudflare quick tunnel,
                              # fixture sign-in: everyone with the link shares one notebook)
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
                              # the whole stack behind https on one machine (after :server:installDist)
scripts/publish-engine-local.sh  # publish planning-core/contract AARs to mavenLocal; --mobile for the mobile build (compileSdk 36)
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

**Mobile builds need three manual edits to the generated `apps/mobile/android/`** (gitignored,
so re-apply after every `expo prebuild`) — `kotlinVersion=2.3.20` in `gradle.properties`,
and in `build.gradle` a KGP 2.3.20 buildscript classpath, `ext.compileSdkVersion = 36`
before the expo-root-project apply, and `mavenLocal()`. Rationale and exact shapes:
`apps/mobile/README.md`. The mobile project compiles at Kotlin 2.3.20 because the Expo
toolchain's pika plugin caps there, and the engine AARs are Kotlin 2.4.10 (metadata
2.4.0) — a compiler reads metadata up to one minor version ahead. Build the Android app
with `scripts/publish-engine-local.sh --mobile` + `./gradlew :app:assembleRelease`
(toolchain `JAVA_HOME`, `--offline`).

**The API 36 emulator image never presents the mobile app's window** (first-frame
reveal deadlock — zero frames, splash stuck, input never activates; the native Compose
app renders fine on the same image). Documented in `apps/mobile/README.md`; the wire-level
journey the app drives is covered by `scripts/journey.sh`, so the mobile feature work is
verifiable without the device.

## Where things live

| Layer | Package | Notes |
| --- | --- | --- |
| Pure schedule maths | `:planning-core` `core/` | No Android, no Compose — unit-tested directly on the JVM |
| Domain model | `:planning-core` `data/model/` | The Room entities. They still carry Room annotations, which is the one thing keeping them out of `commonMain` |
| Storage | `data/database/` | Room; schema is exported to `apps/android/schemas/` |
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

- **One design system, mirrored — never re-decided.** `ui/theme/Color.kt` is the source of
  truth for every surface: unbleached paper over warm charcoal, terracotta the default of six
  accents in three complementary pairs, brick/honey/sage for status. `apps/web/app/tokens.css`
  and `apps/mobile/src/constants/palette.ts` are *mirrors* of it, and
  `apps/web/lib/tokens.test.ts` and `apps/mobile/src/constants/palette.test.ts` parse the Kotlin
  and fail on drift — so change Color.kt first, then mirror. The web app used to run its own
  green-on-cream palette and the Expo app stock blue on white; the three read as three products.
  Two rules carry the meaning and no surface may borrow them for anything else: a solid accent
  bar is work you own and the planner may move it, a dashed honey outline is a commitment it may
  not, and deadline red means a clash or an overrun and always appears with words as well as
  colour. `palette.ts` imports nothing on purpose — that is what lets the drift test load it
  without react-native. `apps/mobile/src/constants/ui.ts` holds the shared shapes (card, section
  rule, row, ownership marks, meter, controls) so four screens cannot each invent a card, and
  `ui-consistency.test.ts` is the mobile answer to `UiConsistencyTest`: it reads the source and
  fails on a hex literal, a fifth radius, `opacity` standing in for muted ink, or a control under
  48dp. All three checks were proven by planting a violation. They earn their keep because the
  API 36 emulator cannot present this app's window, so no rendered test can catch this — the
  mobile timeline had drawn calendar commitments with the *accent*, the mark that means "yours
  and movable", and nothing failed.
- **The web client is a pen-and-paper notebook, built on the same tokens.** It prints the
  notebook's structure (dates, hours, labels, explanations) in Literata and writes everything
  that is the user's (tasks, plans, page titles, buttons) in Kalam; there are no cards, only
  space and ruled lines. It adds one mark of its own — a dotted pencil outline is a block the
  planner proposed and the user has not applied — and defaults to the indigo accent as blue
  ink (a reader can pick any of the six under Settings; the choice stays in their browser).
  One `PlannerProvider` in the root layout holds the state every page reads, so a proposal or a
  "waits for" survives moving between pages. Day boundaries are *local* midnight
  (`lib/format.ts`): the old planner cut its week at UTC midnight and, in India, drew an all-day
  event on two days.
  Narrow windows get their own *structure*, not just smaller type: below 1000px the week is a
  strip of seven days with one open (`useIsOneLeaf`), below 720px Projects is a list and a
  task's details open on an index card (`useIsPhone`). Each renders one structure, never two
  with one hidden — `e2e/web-journey.mjs` finds titles with strict `getByText`, and a hidden
  duplicate fails it. The first phone layout simply stacked the desktop at the same density
  and read as cluttered; check phone work at 390px with touch emulation.
  Carrying a task to a day (`app/components/drag.tsx`) is pointer events, not HTML drag-and-drop,
  because phones give a finger none: a mouse lifts a row after a few pixels, a finger after a
  ~380 ms press so swipes still scroll. A drop never writes — `placeOnDay` asks the planner
  for that one task in that one day, with every other placed block passed as fixed time so
  nothing else can move, and the answer (Apply/Discard, or why it did not fit) is written in
  the day. Every drag has a twin on the task's card and in the pencil (WCAG 2.5.7). The
  server has no stage-move route, so there is no drag between board stages; a task's title,
  estimate, due date, highlight, done and erased state go through `PATCH /v1/tasks/{id}`. `lib/jot.ts` reads a
  jotted line ("Send invoice 45m fri #north"); effort and dates are read only from the end of
  the line so "Plan Monday stand-up" stays a title, and a due day means the end of that local
  day. All-day events are not fixed time for the planner: passing them as 00:00–24:00 made
  any day with a birthday unplannable.
- **On the web, the tool in hand is the verb.** `app/components/pencil-case.tsx` holds four:
  the pen writes and ticks (and a touch opens the task's card), the pencil drafts a task a
  place in the week, the highlighter marks it (`priority: high`, which the planner's
  priority ordering reads), the eraser rubs it out (archive, with Undo). `planner.touchTask`
  is the one entry point every task line calls. A tool is an accelerator, never the only
  way: every action is also on the one `TaskCard` the pen opens, which is how a keyboard or
  screen-reader user reaches it. On the Today notes sheet (`day-notes.tsx`, geometry in
  `lib/ink.ts`) the same tools draw: a stylus or mouse always draws, a finger draws only
  with pencil, highlighter or eraser in hand — with the pen a finger scrolls, or nobody
  could move past the sheet on a phone.
- **A jot never waits on the network.** `planner.jot` and `editTask` change the page first
  and the server behind it; a refusal puts the page back and says why. The last pages are
  kept in the browser (`lib/offline.ts`) and shown at once on the next visit, jots made
  offline queue in an outbox and are sent when the signal returns, and `public/sw.js`
  caches only the app's own shell — never the API, which is another origin. Signing out
  clears all of it. `e2e/offline.mjs` proves this against a production build; the worker is
  not registered in development.
- **Google is asked for one thing at a time.** Sign-in requests identity only; the calendar
  and Drive are each their own later consent (`GoogleScopes`, `/v1/auth/start?connect=`),
  and `GoogleAccount` keeps one connection row per workspace, merging scopes and keeping a
  refresh token a later consent did not re-issue. Sync is one-way in both directions and the
  UI says so: the calendar is read (again whenever the notebook is opened and the last read
  is over ten minutes old), a readable `Daily Brief.md` is written to Drive a few
  seconds after the last change, and (opt-in) the plan is written to a secondary "Daily
  Brief" Google calendar the notebook creates itself — never to the reader's own. `docs/saas/12-going-public.md` is the launch runbook.
- **A plan never reaches into the past.** The web client sends `nowMs` as the next quarter
  hour and the range from *local* midnight; it used to send UTC midnight for both, which
  placed work in hours of today that had already gone. All-day events arrive as UTC
  midnights of their date and are moved onto the reader's own day in `lib/api.ts`.
- **Every module compiles against SDK 37.1, never bare 37.** The pinned SDK has
  `platforms;android-37.1` and nothing else, so `compileSdk = 37` asks for 37.0 and sends
  Gradle to the network for a platform that is not there. Because the download reports no
  task progress, the build looks hung rather than failed — it sat for thirteen minutes
  before this was spotted. `:app` writes `compileSdk { version = release(37) { minorApiLevel = 1 } }`
  and `:planning-core` writes the same thing inside its `androidLibrary` block.
- **The engine's dates are `kotlinx-datetime`, and that costs core library desugaring.** On
  Android `kotlinx-datetime` is backed by `java.time`, which arrived in API 26 while `minSdk`
  here is 24 — so `:app` sets `isCoreLibraryDesugaringEnabled = true` and depends on
  `desugar_jdk_libs`. Without it the engine cannot run on Android 7 at all, and lint fails the
  build with `NewApi` rather than letting it crash at runtime. The alternative was raising
  `minSdk` to 26 and dropping those devices. Do not remove the desugaring dependency while
  `minSdk < 26`.
- **A file earns a place in `:planning-core` by being called by the planner**, not by being free
  of Android imports. `RowBackupCodec` is portable Kotlin and still does not belong there: it
  encodes Room cursor rows for a device backup, and neither the planning service nor iOS will
  ever call it. Portability is a property, not a purpose — apply the test "would the server run
  this?" before moving anything in.
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
  exported schema in `apps/android/schemas/` update.
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
