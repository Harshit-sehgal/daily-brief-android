# Daily Brief — mobile app

The Expo (React Native) client for the Daily Brief SaaS: Login, Today, Planner and
Settings. Talks to the Ktor server (`:server`, see `docs/saas/`); the server is
authoritative for Apply, and the local engine (`modules/planner-engine`) powers preview.

## Screens

- **Login** — local fixture builds can expose "Try the demo" to create a fixture workspace;
  production builds hide that path and expose only "Sign in with Google". The OAuth flow
  returns to `auth-callback`.
- **Today** — the day's external events, plan blocks and the overlap/day-shape summary
  (`GET /v1/today`).
- **Planner** — add tasks, "Plan my week" or "Replan my week" (server `POST /v1/plan`,
  with proposal-based replacement when persisted work exists), review the proposal with
  reasons, Apply, and Undo. Mirrors the web client's planner.
- **Settings** — the server URL shown to the user, session reset.

## Offline posture

Today, the project list, project boards, and the weekly portfolio strip cache their last
successful payload per workspace under the app's document directory. If the server is
unreachable, those screens show the cached data with an explicit offline label. Planning can
still produce a local preview when the native engine is available, but a missing or stale
schedule snapshot keeps that preview non-authoritative; Apply, task edits,
calendar sync, capacity checks, brief generation, and Undo remain server-authoritative and do
not pretend to succeed offline.

The persistence rules are covered by `npm test` in
`src/lib/offline-cache-core.test.ts`, `src/lib/offline-cache.test.ts`, and
`src/lib/offline-cache.native.test.ts`: payload round-trip, legacy-entry compatibility,
corruption rejection, workspace/project key isolation, web-storage reload behavior, native
document-storage reload, malformed-entry cleanup, atomic replacement, and failed-storage-write
posture. The native test uses an in-memory adapter model; native file persistence and
offline/restart behavior still require a real device run.

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
`http://localhost:8090` during development. Set `EXPO_PUBLIC_API_BASE` explicitly for a
physical device or deployed API; release builds fail closed when it is absent. See
`.env.example` for the local emulator value.

## Run

```bash
npm install
npx expo start            # dev server; press a for Android
npx tsc --noEmit          # type check
npx expo export --platform web   # web build check
```

`package.json` pins `xcode`'s nested `uuid` dependency to the latest CommonJS-compatible
11.x security release. Do not run `npm audit fix --force`: the current audit reports 8 high
findings, all from Metro's transitive `image-size@1.2.1` parser chain. `image-size@2.x` changes Metro's
callable CommonJS API, and npm's latest 2.0.2 release remains in the published advisory range.
Resolving this safely requires a compatible Metro/Expo upgrade or a maintained upstream patch,
not an unverified override.

Release builds need the generated `android/` project (`npx expo prebuild -p android`),
followed by the Gradle adjustments below. The tracked
`plugins/with-local-emulator-cleartext.js` config plugin is re-applied by prebuild and
allows only the Android emulator host (`10.0.2.2`) to use the local HTTP fixture; all
other cleartext traffic remains denied. Production builds should set
`EXPO_PUBLIC_API_BASE` to an HTTPS service.

## The planner-engine module

`modules/planner-engine` is a local native module: `previewPlan(requestJson)` returns the
planning-contract v1 result JSON. Android runs `planning-core`'s `EnginePreview` (via the
mavenLocal `planner-engine`/`planning-core` AARs); iOS would run the same code through the
`PlannerCore` XCFramework (built and Swift-import checked by `scripts/verify-ios-framework.sh`
on macOS — not verifiable on this Linux machine). The JSON contract means no engine type crosses the
native boundary; a phone preview and a server run meet at `EnginePreview`, so they compute
the same proposals and cannot disagree about the plan (`EnginePreviewTest` pins the
equality).

## Android build quirks (generated project)

`mobile/android/` is generated and gitignored (`mobile/.gitignore`: `/android`). The
pinned toolchain has no `android-35`, no bare `android-37`, and RN 0.86's AGP 8.12 knows
no minor API levels. The tracked `plugins/with-local-emulator-cleartext.js` config plugin
re-applies the Gradle adjustments on every `expo prebuild`, so a fresh generated project
does not depend on undocumented manual edits. The Android build needs:

1. **`scripts/publish-engine-local.sh --mobile`** first, to publish the planning AARs
   compiled at `compileSdk 36` to `~/.m2/repository`.
2. `mobile/android/local.properties`: `sdk.dir=/home/harshit/.local/android-toolchain/android-sdk`
   (the prebuild drops it; without it Gradle cannot find the SDK; CI supplies the SDK
   through `ANDROID_HOME` instead).
3. The config plugin sets `kotlinVersion=2.3.20`, pins the Kotlin Gradle plugin to
   `2.3.20`, adds `mavenLocal()`, and sets `ext.compileSdkVersion = 36`. The engine AARs are
   compiled with Kotlin 2.4.10 (metadata 2.4.0); a compiler reads metadata up to one minor
   version ahead, and the Expo toolchain's pika plugin caps at 2.3.20. RN 0.86 itself
   pins Kotlin 2.1.20 + AGP 8.12.0.
   The compile-SDK override must remain an Int before `expo-root-project` is applied;
   a Gradle-properties value arrives as a String and AGP 8.12 fails with "Value is null".

Any new native dependency (e.g. `expo-notifications`' `installreferrer`) needs one online
Gradle run to fill the offline cache; `--offline` holds afterwards.

Build: `./scripts/verify-mobile-release.sh` from the repository root. It regenerates the
native project, publishes the local engine AARs, assembles the mobile module (not the root
Compose app), and verifies the merged network policy. For a faster incremental build after
prebuild, run `./gradlew :app:assembleRelease` from `apps/mobile/android`.

## Runtime evidence and remaining limits

The API 36 `dailybrief` emulator now renders the release APK after the root layout hides
the native splash. A clean install completed the fixture signup journey and rendered
Today; force-stop/relaunch preserved the session; stopping the fixture server and
relaunching rendered Today with `Offline — showing the last synced day`. This is local
API 36 release evidence, not physical-device coverage or proof of every native storage
failure mode. Release APKs are not debuggable, so their document-directory files cannot
be inspected with `run-as`.

A fresh API 37.1 16 KB image reports the correct `sdk_full=37.1` and `PAGESIZE=16384`,
and APK installation succeeds, but its `package`/`activity` framework services
repeatedly become unavailable before launch. Physical/reliable-device proof of native
file persistence, corruption recovery, workspace isolation, and failed-storage writes
is still open, as are provider delivery, real OAuth, and production HTTPS checks.
