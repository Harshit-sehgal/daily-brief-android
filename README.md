# Daily Brief

Daily Brief is a local-first Android app that combines device calendar events
and a Notion database, detects overlaps, and can ask Gemini for a concise daily
summary.

## Features

- **Navigation** keeps Home, Calendar, and Plan as three stable primary
  destinations in the bottom bar or side rail. Search and Settings stay visible
  at the top of each root without displacing daily work.
- **Home** is the default landing page, although any primary destination can be
  chosen as the launch page. Rather than listing the day it answers it: what you
  are in and how long is left, what follows, how much of the booked day is
  behind you, and anything clashing. Its Board preview comes from the active
  Plan rather than calendar rows, and its fail-closed Plan Health block
  summarizes seven-day capacity and risk across every working schedule the
  active tasks use. Its blocks and their order are
  configurable directly from the visible Edit/Done control; Settings retains
  the same show/hide and Move up/down controls as a fallback.
- **Calendar** preserves one selected date across Agenda, Timeline, and Week.
  Agenda can be grouped by time, source, or priority; Summary, Conflicts, and
  Brief remain optional sections that can be reordered, folded, or hidden.
- **Day timeline** shows the day as a calendar grid — blocks placed and sized by
  real time, overlaps packed side by side, a live marker on the current minute.
  Tap empty space to create. App-owned events can be long-pressed and dragged,
  moved exactly 15 minutes earlier/later, sent to another date, or moved from the
  keyboard; every one of those paths opens the same preview naming the exact times
  and conflicts, and only Apply writes, with an exact stale-guarded Undo. A row
  that changed underneath you fails the preview closed rather than overwriting the
  newer edit. Calendar and read-only sources stay visibly fixed.
- **Week** shows the next 7, 14, or 30 days inside Calendar; empty days collapse
  to one line.
- **Plan** adds an app-owned Outline/Inbox for flexible tasks, with effort, due
  date, priority, milestone, hierarchy, progress, completion, edit, and delete
  controls. Any parent folds with a labelled control that says how many subtasks
  it hid, finished work can be hidden, and the outline can be ordered by plan
  order, due date, priority, or effort, and banded by section, priority, or when it
  is due — all of it presentation, none of it an
  edit, and all of it remembered across a cold start. Long-press starts a selection there too, so
  several tasks reschedule as one journaled command. Board uses those same tasks in Inbox/workflow lanes with explicit
  move controls, task-group disclosure, and visible Select/Done multi-select.
  Multi-move previews the linked hierarchy and commits as one atomic Plan History
  step, so a rejected move writes nothing and Undo restores every original
  placement; long-press drag with edge auto-scroll stages that same command. The
  Schedule map renders split Plan blocks as solid bars and source-owned calendar
  commitments as outlined fixed bars, with DST-safe 7/30/90-day ranges reachable by pinch as well as by
  control — the day under the fingers stays put — an overview strip that marks the visible window on
  the whole planned span and moves there when tapped, Today/previous/next controls, overlap lanes, milestones, progress, and an
  accessible add-block form. The form previews the task-resolved working schedule,
  fixed commitments, buffers, locks, and dependency effects, and the repository
  revalidates the same rules transactionally. An app-owned bar can be long-pressed
  into a move mode with date/time fields, 15-minute increments, arrow keys, custom
  actions, and draggable bar and boundary handles; releasing a pointer never saves
  and only Apply commits. Plan also provides named views that store the surface, Gantt range, outline
  order, grouping, visible Board columns, hide-done filter, and folded branches together — with rename, update to
  what is on screen, duplicate, an exclusive per-board pin so a board opens with
  it, and reset — plus recent mutation History with Undo, Plan Health details, a non-drag dependency ledger,
  critical-path highlighting, drawn dependency links with slack bands that say how
  many links they could not draw, and partial-day non-working shading that keeps
  split shifts, exceptions, and DST. Task Delete is a reversible leaf-task archive,
  preserving its exact journal/Undo state rather than physically dropping the row.
- **Plan my week** proposes blocks for unscheduled effort without writing anything: each proposal
  says why it chose that slot, and everything it will not place is listed with its reason. Apply
  writes the whole proposal through the same validation a hand-made block obeys, as one History
  entry that a single Undo reverses. **Compare approaches** runs that same planner three defensible
  ways — due date first, priority first, quick wins first — and shows what each ordering costs in
  placed work, leftovers, and finish date; choosing one opens the ordinary proposal review rather
  than writing it. **Weekly review** reports the week from the journal — planned
  time, finished, carried over, how much the plan moved — and refuses a number it cannot support.
  **Export** hands the plan to another app as CSV or calendar, stating its own scope on the first
  line.
- **Baselines** name what the schedule looked like at a moment, and the comparison reports what has
  moved in both directions, what has been scheduled since, and what has lost its schedule. Restore
  is offered from inside that comparison rather than from a list, rewrites blocks only — titles,
  notes, and progress are today's work — and lands as one History entry that Undo reverses. A
  baseline is stored in the journal's own encoding, so it survives the tasks it describes being
  edited or archived, and a malformed record refuses to decode rather than reporting precise-looking
  nonsense. **Across every plan** totals open work, unscheduled effort, and overdue tasks for every
  board, and says plainly when a total is a floor because some work states no effort.
- **Quick capture** defaults to Event on Home/Calendar and Task on Plan; new-item
  sheets retain both complete drafts while switching type and share title/notes.
  A draft that cannot be saved says so: the field that blocks Save carries the
  sentence that fixes it, and a note beside Save says what Save is waiting for.
- **Command palette** for fuzzy jumping to any event, day, or screen, plus task
  capture.
- **Settings** controls calendar and Notion access, the runtime Gemini key and
  model, notification timing, theme, information density, launch page, Home
  blocks, Agenda sections, the Week range, and how long a change stays undoable
  (30 seconds, 5 minutes, 1 hour, or 24 hours — 5 minutes by default, because a
  30-second ceiling on recovery is a time limit nobody chose). Planning settings edit and persist
  the default IANA-zone working week with split hours, date exceptions,
  commitment buffer, and minimum/maximum chunk limits, and a schedule manager
  creates, renames, and archives alternate reusable schedules or transfers which
  one is the default. Archiving the default demands an explicit replacement and
  reroutes its assignments in one transaction. Existing tasks can inherit the
  default or explicitly select any active schedule in the Task editor, as a
  separate journaled command with Undo. Legacy Today, Week, and Board launch
  choices are normalized to Calendar or Plan while Week remains the selected
  Calendar view. Migrated catalogs get a one-time disclosure that retained
  board/column names did not convert calendar commitments into movable tasks.

A root screen shows its work. Everything a screen can do that is not that work sits
behind the one menu in its header, opening a focused dialog: Plan's holds View
options (order, grouping, what is hidden, lanes, saved views), Plan health, Plan
tools, History and export. Home opens with what you are in, what is next, and
anything clashing; the rest are cards you add under Edit. The Schedule map keeps
its zoom chips on the canvas because they are navigation, and puts dependencies
and the legend in its own menu because they are references. On a short screen it
also drops its title, subtitle, status line and overview strip, in that order,
rather than leaving the canvas less than one row tall.

The adaptive shell distinguishes Compact, Medium, Expanded, Large, and
ExtraLarge windows. Expanded and wider Calendar screens use a stable Agenda-left,
Timeline-right day desk while preserving the same selected date and primary-view
identity; Plan moves its secondary planning controls into a ledger beside the
workspace at the same widths.

Plan Health combines capacity across differing per-task schedules but still
reports unavailable rather than estimating when an assignment cannot be resolved.
Feature inventory does not replace the verification commands and
device/accessibility review below.

### Gestures

- Swipe left/right on Agenda's date header or the day timeline to change day.
  Both also provide previous/next-day buttons.
- Swipe a timed local agenda row right to push it to tomorrow, or any agenda row
  left to delete it. Local events delete with an undo; calendar events are
  deleted in their calendar, and a source that refuses the delete — Notion,
  a repeating series, or write-back turned off — puts the row back and says why.
  The same safe operations are available from each row's labeled Actions menu
  and as accessibility actions.
- Long-press and drag an app-owned Timeline event to move it. Releasing opens the
  same preview the Move 15 minutes earlier/later, Move to another date, keyboard,
  and editor paths open; fixed source events never enter Timeline drag mode.
- Long-press a Board task to drag it between lanes, with edge auto-scroll. The
  arrows, Move to… menu, custom actions, and Alt+Left/Right on a focused card all
  stage the identical atomic command.
- Back leaves the mode you are in before it leaves the screen: it cancels Schedule
  map movement, clears a Board selection, and finishes Home layout editing, without
  writing anything. Escape does the same for a keyboard in move mode.
- Long-press an app-owned Schedule map bar to enter move mode, or open the block
  and choose **Move on map** — the gesture is the accelerator, not the only door. Then drag the bar
  or either boundary handle. The panel's date fields, 15-minute increments, arrow
  keys, and custom actions do the same thing without a gesture, and only Apply
  saves. Move mode sets the standing explanations aside so the canvas keeps its
  height, and the move target and both handles are laid out so they never cover
  each other.
- Horizontally pan the Schedule map; visible 7/30/90-day controls make zoom
  available without requiring a gesture.
- Pull down on Home or Agenda to sync.

On Agenda, day paging is scoped to the date header while row actions are scoped
to agenda rows, so a single horizontal hit area never has two meanings. Sync is
also available from Agenda's overflow menu, the command palette, and Settings.

Three information densities (Compact, Cozy, Relaxed) tune workspace row
heights, gaps, icons, and supporting text. Primary headings stay stable for
readability while the setting changes how much working content fits on screen.

App data is stored locally with Room. Network requests are limited to the
Notion and Gemini APIs. API keys are entered at runtime in Settings and are not
injected into the APK at build time.

## Pinned toolchain

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 9.3.1 |
| Gradle wrapper | 9.7.0, SHA-256 pinned |
| Kotlin / Compose compiler plugin | 2.4.10 |
| KSP | 2.3.11 |
| Compose BOM | 2026.06.01 |
| Room | 2.8.4 |
| compileSdk | Android 37.1 |
| targetSdk / minSdk | 37 / 24 |
| Gradle runtime JDK | 21 |
| Java/Kotlin bytecode target | 17 |

AGP 9 uses built-in Kotlin. The root build file explicitly opts into the Kotlin
and KSP versions above using AGP's supported classpath upgrade mechanism; there
is no dependency-resolution force or alternate Maven mirror.

## Local setup

The verified toolchain is installed outside the repository. The paths below are
from the machine this was developed on; substitute your own:

```bash
export JAVA_HOME=/home/harshit/.local/android-toolchain/jdk
export ANDROID_HOME=/home/harshit/.local/android-toolchain/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

java -version
sdkmanager --list_installed
```

`java -version` should report Corretto 21.0.12. The SDK needs Build Tools 36.0.0,
platforms `android-36` and `android-37.1`, plus emulator images for the API 36
test AVD and the optional API 37.1/16 KB verification AVD. Android Studio can
use the same JDK and SDK paths. `local.properties` remains a local,
ignored file and should contain:

```properties
sdk.dir=/home/harshit/.local/android-toolchain/android-sdk
```

No `.env` file is used. Add Gemini and Notion credentials only through the
app's Settings screen.

## Verification gates

From the repository root, run:

```bash
./gradlew --no-daemon --stacktrace testDebugUnitTest
./gradlew --no-daemon --stacktrace lintDebug assembleDebug assembleDebugAndroidTest
```

The release build is deliberately allowed to remain unsigned for CI and local
R8 verification. Clear every signing variable first so a partially configured
environment fails explicitly:

```bash
env -u KEYSTORE_PATH -u STORE_PASSWORD -u KEY_ALIAS -u KEY_PASSWORD \
  ./gradlew --no-daemon --stacktrace lintRelease assembleRelease :app:analyzeReleaseR8Config
```

Release builds enable R8 optimization and resource shrinking. The Room Gradle
plugin exports schema history to `app/schemas`; commit newly generated schema
JSON whenever the database version or entities change.

### Current recorded evidence

The 2026-08-12 run recorded:

- `git diff --check` passed;
- `scripts/verify.sh --fast` passed;
- the full no-device `scripts/verify.sh` passed with 378 JVM tests and 0 skipped;
- `scripts/verify.sh --device` passed 128/128 instrumentation tests, debug and
  release lint, debug/Android-test/unsigned-release APK assembly, and R8 analysis.
  Three runs along the way failed first and are worth recording: the Gantt move
  target could not be dragged because the preview panel and both resize handles
  were drawn over the bar; the Board's Alt+Arrow shortcut moved the focus ring
  instead of staging a move; and a gate run straight after a preview capture failed
  seven tests that had nothing to do with the change, because the capture harness
  had left its seeded rows and flipped settings on the device. The first two are
  fixed and guarded by tests, and `capture-preview.sh` now clears app data when it
  finishes. Re-auditing this tranche then found a defect it had introduced: deleting
  a baseline destroyed it on one tap, with no confirmation and no journal entry to
  undo, which is not how any other destructive action in the app behaves. It now
  asks first. A second audit pass then found three faults in the mutation journal
  that made an Undo claim untrue — an emptied schedule recorded an after-state
  identical to its before-state, so its blocks could not be recovered; a restore
  whose rows had been recreated failed outright; and undoing a multi-block plan
  threw instead of reversing. All three are fixed and guarded, and both lints now
  report zero issues. A third pass found that `plan_baselines` had shipped with no
  foreign key, so deleting a plan orphaned its baselines while saved views cascaded;
  schema version 9 rebuilds the table with `ON DELETE CASCADE`;
- `scripts/smoke-release.sh` installed, launched, and navigated the minified
  unsigned release successfully, reporting 2551 KiB.

| Artifact | Bytes |
| --- | ---: |
| Debug APK | 15,121,984 |
| Android-test APK | 1,978,432 |
| Unsigned minified release APK | 2,612,685 |

`scripts/capture-preview.sh` recaptured `preview.html` from the same emulator:
portrait Home, Agenda, Timeline, Week, Outline, Board, Schedule map, Gantt move
mode, the baseline comparison, Compare approaches, working schedules, Settings,
and two dark screens, plus Expanded-width Calendar and Plan. Those plates are the rendered inspection and nothing more.
This is not the full adaptive or accessibility matrix. CI runs the instrumentation
suite on API 24 as well as API 36, and specific components are asserted at a 2×
font scale; what remains unverified is everything else: exact width boundaries,
API 37.1/16 KB, foldable/desktop and split-screen states, TalkBack/Switch/Voice,
keyboard/pointer, rendered 200% text beyond those components, provider-device
behavior, and task-based user studies.

## Emulator test

The `dailybrief_api37_1_16k` AVD (Android 17 / API 37, Google APIs, x86_64,
16 KB pages) is available for page-size and visual verification. Start it in
one terminal with the Minigbm graphics path:

```bash
emulator -avd dailybrief_api37_1_16k \
  -gpu lavapipe -feature Minigbm \
  -no-snapshot -no-snapshot-save -no-boot-anim
```

Then verify boot completion, install the current debug APK, and run the device
tests from another terminal:

```bash
adb wait-for-device
adb shell getprop sys.boot_completed
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew --no-daemon --stacktrace connectedDebugAndroidTest
```

Do not install until `sys.boot_completed` prints `1`. The debug build uses the
standard Android debug keystore automatically. The current verified local device
run used the `dailybrief` API 36 AVD for upgrade and conventional 4 KB coverage;
API 37.1/16 KB requires a fresh recorded run before it is current evidence. When
both devices are connected, prefix the install and Gradle commands with the intended
`adb -s <serial>` or `ANDROID_SERIAL=<serial>` selection.

## Signed release

A release is signed only when all four environment variables are non-empty:

- `KEYSTORE_PATH` — preferably an absolute path to the upload keystore
- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

With all four configured, create the Play upload bundle with:

```bash
./gradlew --no-daemon --stacktrace lintRelease bundleRelease
```

Keystores, `.env` files, local SDK configuration, and generated build outputs
are ignored by Git.

## Permissions

- `READ_CALENDAR` is requested in context. Denial leaves non-calendar features
  available.
- `POST_NOTIFICATIONS` is requested when notifications are enabled.
- `RECEIVE_BOOT_COMPLETED` lets scheduled briefs and reminders be restored.

Alarms are inexact and doze-friendly, so no exact-alarm permission is needed.

## Project layout

```text
core/        Pure date, overlap, timeline/Gantt geometry, day-state, and parsing logic
data/
  api/       Gemini, Notion, and device-calendar clients
  database/  Room entities, DAOs, database, and migrations
  prefs/     Settings keys and secure credential storage
  repository/Sync, briefing cache, settings, and app-owned planning access
receiver/    Alarm scheduling and notifications
ui/
  theme/     Color, type, spacing, density, and window-size behavior
  components/Shared workspace primitives, gestures, and task/event editing
  screens/   Home, Calendar (Agenda/Timeline/Week), Plan (Outline/Board/Gantt), Settings
  viewmodel/ App state and repository coordination
```

GitHub Actions runs JVM tests, lint, debug/test APK assembly, an unsigned
minified release build, R8 analysis, and instrumentation on API 24 and API 36.
The latest local device matrix also used API 36.
