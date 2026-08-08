# Daily Brief

An Android app that pulls your day together from the calendars already on your
phone and from a Notion database, flags what overlaps, and asks Gemini for a
short read on it.

## What's in it

- **Today** — the day's agenda with a live "now" marker, a three-number summary,
  overlap detection, and the AI brief.
- **Week** — the next seven days, one card per day.
- **Board** — kanban boards and columns for the same events, independent of which
  day you happen to be looking at.
- **Settings** — connections, the Gemini key and model, notification timing,
  theme, and a sample day you can load to see the app with data in it.

Everything is stored locally in Room. The only network calls are to the Notion
API and to Gemini.

## Running it

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio, choose **Open**, and select this directory.
2. Let Android Studio resolve the Gradle sync.
3. Create a `.env` file in the project root and set `GEMINI_API_KEY` (see
   `.env.example`). Without it the app still runs — the brief falls back to a
   locally generated summary and tells you why.
4. Remove `signingConfig = signingConfigs.getByName("debugConfig")` from
   `app/build.gradle.kts` unless you have the matching `debug.keystore`.
5. Run on a device or emulator (minSdk 24).

You can also add a Gemini key at runtime from **Settings → Brief**, which
overrides the build-time one, and pin a specific model there if you want.

## Permissions

- `READ_CALENDAR` — requested in context, from Today or Settings. Denying it
  leaves the rest of the app working.
- `POST_NOTIFICATIONS` — requested the moment you switch a notification on.
- `RECEIVE_BOOT_COMPLETED` — so the morning brief and event reminders survive a
  reboot.

Alarms are inexact and doze-friendly, so no exact-alarm permission is needed.

## Project layout

```
core/        pure logic — overlap detection, day maths, date parsing, markdown
data/
  api/       Gemini, Notion and device-calendar clients
  database/  Room entities, DAOs, migrations
  prefs/     every settings key in one place
  repository/sync, briefing cache, settings access
receiver/    alarm scheduling and notifications
ui/
  theme/     colour, type, spacing, window-size buckets
  components/shared building blocks and the single event editor
  screens/   Today, Week, Board, Settings
  viewmodel/ one ViewModel over the repository
```

`core/` has no Android dependencies, which is what the unit tests in
`app/src/test` exercise: `./gradlew testDebugUnitTest`.
