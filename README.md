# Daily Brief

Daily Brief is a local-first Android app that combines device calendar events
and a Notion database, detects overlaps, and can ask Gemini for a concise daily
summary.

## Features

- **Home** is the landing page and the default screen. Rather than listing the
  day it answers it: what you are in and how long is left, what follows, how
  much of the booked day is behind you, and anything clashing. Which blocks
  appear, and in what order, is configurable.
- **Today** is the full agenda, grouped by time, source, or priority, with
  foldable sections you can turn off individually.
- **Day timeline** shows the day as a calendar grid — blocks placed and sized by
  real time, overlaps packed side by side, a live marker on the current minute.
  Tap empty space to create, long-press and drag to move.
- **Week** shows the next 7, 14, or 30 days; empty days collapse to one line.
- **Board** organizes the same events into independent kanban boards and columns.
- **Command palette** for fuzzy jumping to any event, day, or screen.
- **Settings** controls calendar and Notion access, the runtime Gemini key and
  model, notification timing, theme, information density, and which blocks each
  screen shows.

### Gestures

- Swipe left/right on the day timeline to change day.
- Swipe an agenda row right to push it to tomorrow, left to delete it with undo.
- Pull down on Home or Today to sync.

Row actions and day paging are deliberately kept on separate screens: a single
horizontal swipe cannot mean two things at once.

Three information densities (Compact, Cozy, Relaxed) drive every row height,
gap, and type size in the app, so the setting genuinely changes how much fits
on screen rather than only scaling a font.

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
platforms `android-36` and `android-37.1`, and an API 36 emulator image. Android
Studio can use the same JDK and SDK paths. `local.properties` remains a local,
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

## Emulator test

The primary compatibility AVD is `dailybrief_api37_1_16k` (Android 17 / API
37, Google APIs, x86_64, 16 KB pages). Start it in one terminal with the
Minigbm graphics path; this is the verified stable renderer for this host:

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
standard Android debug keystore automatically. The `dailybrief` API 36 AVD is
also retained for upgrade and conventional 4 KB page-size coverage. When both
devices are connected, prefix the install and Gradle commands with the intended
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
core/        Pure date, overlap, timeline packing, day-state, and parsing logic
data/
  api/       Gemini, Notion, and device-calendar clients
  database/  Room entities, DAOs, database, and migrations
  prefs/     Settings keys and secure credential storage
  repository/Sync, briefing cache, and settings access
receiver/    Alarm scheduling and notifications
ui/
  theme/     Color, type, spacing, density, and window-size behavior
  components/Shared workspace primitives, gestures, and event editing
  screens/   Home, Today, Day timeline, Week, Board, and Settings
  viewmodel/ App state and repository coordination
```

GitHub Actions runs JVM tests, lint, debug/test APK assembly, an unsigned
minified release build, R8 analysis, and instrumentation on min SDK 24 and
target SDK 37. Local emulator verification additionally covers API 36 upgrades.
