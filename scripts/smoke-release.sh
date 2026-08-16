#!/usr/bin/env bash
# Proves the *shipping* build actually runs.
#
# CI assembles the release APK but never installs it, so anything R8 strips too
# eagerly stays invisible until someone opens a real release build. This signs
# the unsigned output with the debug key — purely so it can be installed — puts
# it on a running emulator, launches it, and fails on a crash or a dead window.
#
#   scripts/smoke-release.sh              # assemble if needed, install, launch
#   scripts/smoke-release.sh --keep       # leave it installed to poke at
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.local/android-toolchain/android-sdk}}"
ADB="$SDK/platform-tools/adb"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
APPLICATION_ID="com.aistudio.dailybrief.jxhvqy"
ACTIVITY="$APPLICATION_ID/com.example.MainActivity"
UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
WORK="$(mktemp -d)"
KEEP=0

[ "${1:-}" = "--keep" ] && KEEP=1
trap 'rm -rf "$WORK"' EXIT

[ -n "$BUILD_TOOLS" ] || {
  echo "smoke: no build-tools under $SDK" >&2
  exit 1
}

scripts/emulator.sh --check || scripts/emulator.sh

if [ ! -f "$UNSIGNED" ]; then
  echo "smoke: building the release APK"
  JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk}" ./gradlew --offline assembleRelease
fi

# A signed release build is only produced when the signing environment is set;
# for a smoke test any key will do, so borrow the debug one.
echo "smoke: signing with the debug key (install only — never ship this)"
"$BUILD_TOOLS/zipalign" -f 4 "$UNSIGNED" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$HOME/.android/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$WORK/release-signed.apk" "$WORK/aligned.apk"

echo "smoke: installing"
"$ADB" uninstall "$APPLICATION_ID" >/dev/null 2>&1 || true
"$ADB" install -r "$WORK/release-signed.apk" >/dev/null
"$ADB" logcat -c

echo "smoke: launching"
"$ADB" shell am start -n "$ACTIVITY" >/dev/null
sleep 6

fail() {
  echo "smoke: FAILED — $1" >&2
  "$ADB" logcat -d | grep -iE "AndroidRuntime|FATAL" | head -20 >&2 || true
  exit 1
}

"$ADB" shell pidof "$APPLICATION_ID" >/dev/null 2>&1 || fail "the process is not running"
"$ADB" logcat -d | grep -qiE "FATAL EXCEPTION|AndroidRuntime.*$APPLICATION_ID" && fail "it crashed"
"$ADB" shell dumpsys window 2>/dev/null | grep -q "$APPLICATION_ID" ||
  fail "no window belongs to the app"

# R8 is most likely to break something the UI needs a moment to reach, so give
# the shell a nudge through the primary destinations before calling it healthy.
for tab in 127 403 677 951; do
  "$ADB" shell input tap "$tab" 2203 >/dev/null 2>&1 || true
  sleep 1
done
"$ADB" logcat -d | grep -qiE "FATAL EXCEPTION" && fail "it crashed while navigating"

SIZE="$(stat -c%s "$UNSIGNED")"
echo "smoke: OK — release build launches and navigates ($((SIZE / 1024)) KiB APK)"

if [ "$KEEP" = 0 ]; then
  "$ADB" uninstall "$APPLICATION_ID" >/dev/null 2>&1 || true
fi
