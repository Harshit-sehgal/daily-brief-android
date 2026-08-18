# Daily Brief — mobile app

The Expo (React Native) client for the Daily Brief SaaS: Login, Today, Planner and
Settings. Talks to the Ktor server (`:server`, see `docs/saas/`); the server is
authoritative for Apply, and the local engine (`modules/planner-engine`) powers preview.

## Screens

- **Login** — "Try the demo" creates a fixture workspace on the server; "Sign in with
  Google" runs the server's OAuth flow and returns to `auth-callback`.
- **Today** — the day's external events, plan blocks and the overlap/day-shape summary
  (`GET /v1/today`).
- **Planner** — add tasks, "Plan my week" (server `POST /v1/plan`), review the proposal
  with reasons, Apply, and Undo. Mirrors the web client's planner.
- **Settings** — the server URL shown to the user, session reset.

## Push

`expo-notifications` (installed, in the Android prebuild) mints the install's push token
at app start once a session exists, registers it with the server (`POST /v1/devices`), and
deregisters it at sign-out (`DELETE /v1/devices`). The server half — the tenant-bound
registry, the provider seam, and the Apply trigger — shipped 2026-08-18. The client
half shipped 2026-08-19. Delivery on Android needs the release APK from the prebuild (the
managed-dev workflow does not run native modules); the journey proves the wire shape at
`/v1/fixture/push-deliveries`.

## API base

Android emulators reach the host at `http://10.0.2.2:8090`; iOS simulators and web use
`http://localhost:8090`. Override with `EXPO_PUBLIC_API_BASE` for a real device or a
deployed server.

## Run

```bash
npm install
npx expo start            # dev server; press a for Android
npx tsc --noEmit          # type check
npx expo export --platform web   # web build check
```

Release builds need the generated `android/` project (`npx expo prebuild -p android`),
plus three edits documented below.

## The planner-engine module

`modules/planner-engine` is a local native module: `previewPlan(requestJson)` returns the
planning-contract v1 result JSON. Android runs `planning-core`'s `EnginePreview` (via the
mavenLocal `planner-engine`/`planning-core` AARs); iOS would run the same code through the
`PlannerCore` XCFramework (built by `scripts/build-ios-framework.sh` on macOS — not
verifiable on this Linux machine). The JSON contract means no engine type crosses the
native boundary; a phone preview and a server run meet at `EnginePreview`, so they compute
the same proposals and cannot disagree about the plan (`EnginePreviewTest` pins the
equality).

## Android build quirks (generated project, re-apply after every `expo prebuild`)

`mobile/android/` is generated and gitignored (`mobile/.gitignore`: `/android`). The
pinned toolchain has no `android-35`, no bare `android-37`, and RN 0.86's AGP 8.12 knows
no minor API levels — so the Android build needs:

1. **`scripts/publish-engine-local.sh --mobile`** first, to publish the planning AARs
   compiled at `compileSdk 36` to `~/.m2/repository`.
2. `mobile/android/local.properties`: `sdk.dir=/home/harshit/.local/android-toolchain/android-sdk`
   (the prebuild drops it; without it Gradle cannot find the SDK).
3. `mobile/android/gradle.properties`: `kotlinVersion=2.3.20`. The engine AARs are
   compiled with Kotlin 2.4.10 (metadata 2.4.0); a compiler reads metadata up to one minor
   version ahead, and the Expo toolchain's pika plugin caps at 2.3.20. RN 0.86 itself
   pins Kotlin 2.1.20 + AGP 8.12.0.
4. `mobile/android/build.gradle`:
   - buildscript classpath `org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20`;
   - `ext.compileSdkVersion = 36` **before** `apply plugin: "expo-root-project"` (an Int —
     a gradle.properties value arrives as a String and AGP 8.12 fails with
     "Value is null");
   - `mavenLocal()` in the `allprojects` repositories block.

Any new native dependency (e.g. `expo-notifications`' `installreferrer`) needs one online
Gradle run to fill the offline cache; `--offline` holds afterwards.

Build: `./gradlew :app:assembleRelease` (with `JAVA_HOME` set to the pinned toolchain).

## Known limitation

The API 36 emulator image on this machine never presents this app's window: the app's
surface stays 0×0, zero frames are rendered, and the starting-reveal holds the layer
off-screen, so the splash never exits and input never activates. The repo's own native
Compose app renders fine on the same image, and the API 37.1 image crashes SurfaceFlinger
under the headless software renderer. Everything up to the first frame is verifiable
(`dumpsys activity top` shows the full laid-out hierarchy; the JS bundle runs), and the
full server journey the app drives is covered by `scripts/journey.sh` — but the
interactive on-device smoke test needs a physical device or a different emulator image.