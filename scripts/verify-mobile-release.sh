#!/usr/bin/env bash
# Generate and verify the Expo Android release project for the mobile client.
# This is intentionally separate from the root Compose Android gate: the two apps have
# different application IDs, build roots, and native dependency caches.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.local/android-toolchain/android-sdk}}"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk}"
export JAVA_HOME
export EXPO_PUBLIC_API_BASE="${EXPO_PUBLIC_API_BASE:-http://10.0.2.2:8090}"
export EXPO_PUBLIC_FIXTURE="${EXPO_PUBLIC_FIXTURE:-1}"
export NODE_ENV=production
if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -z "${ANDROID_HOME:-}" ]; then
  export ANDROID_HOME="$SDK"
fi
export PATH="$JAVA_HOME/bin:$SDK/platform-tools:$PATH"

cd "$ROOT"

echo "mobile: generating Expo Android project"
(cd apps/mobile && npx expo prebuild --platform android --no-install)

echo "mobile: publishing local engine AARs"
./scripts/publish-engine-local.sh --mobile

echo "mobile: assembling release APK"
# Same DAILYBRIEF_GRADLE_ONLINE hatch as publish-engine-local.sh: a fresh CI checkout has a
# cold Gradle cache and --offline cannot resolve anything from it.
if [ "${DAILYBRIEF_GRADLE_ONLINE:-0}" = "1" ]; then
  (cd apps/mobile/android && ./gradlew --no-daemon --stacktrace :app:assembleRelease)
else
  (cd apps/mobile/android && ./gradlew --offline --no-daemon --stacktrace :app:assembleRelease)
fi

APK="$ROOT/apps/mobile/android/app/build/outputs/apk/release/app-release.apk"
BUNDLE="$ROOT/apps/mobile/android/app/build/generated/assets/react/release/index.android.bundle"
MANIFEST="$ROOT/apps/mobile/android/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
[ -f "$APK" ] || { echo "mobile: release APK is missing: $APK" >&2; exit 1; }
[ -f "$BUNDLE" ] || { echo "mobile: release JS bundle is missing: $BUNDLE" >&2; exit 1; }
[ -f "$MANIFEST" ] || { echo "mobile: merged release manifest is missing: $MANIFEST" >&2; exit 1; }

grep -a -Fq "$EXPO_PUBLIC_API_BASE" "$BUNDLE" || {
  echo "mobile: configured API origin is absent from the release JS bundle" >&2
  exit 1
}

grep -q 'android:networkSecurityConfig="@xml/network_security_config"' "$MANIFEST" || {
  echo "mobile: emulator-only network policy is missing from the merged manifest" >&2
  exit 1
}

echo "mobile: OK — $(stat -c '%s bytes' "$APK") release APK"
