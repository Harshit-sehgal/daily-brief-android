#!/usr/bin/env bash
# Recaptures the screenshots in preview.html from the real app on an emulator.
#
#   scripts/capture-preview.sh            # PNGs into build/preview-plates
#
# The plates in preview.html are the app, not mockups, so they have to be taken
# again whenever the UI changes visibly. PreviewGalleryCaptureTest seeds one
# legible day and plan, walks the screens, and writes PNGs to the app's external
# files directory.
#
# It runs the instrumentation with `am instrument` rather than through Gradle on
# purpose: connectedDebugAndroidTest uninstalls both APKs when it finishes, which
# deletes the captures with them. Capture is a publishing step, not a gate, so it
# is not part of scripts/verify.sh.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

TOOLCHAIN_JDK="$HOME/.local/android-toolchain/jdk"
[ -n "${JAVA_HOME:-}" ] || JAVA_HOME="$TOOLCHAIN_JDK"
export JAVA_HOME

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.local/android-toolchain/android-sdk}}"
ADB="$SDK/platform-tools/adb"
OUT="build/preview-plates"
PACKAGE="com.aistudio.dailybrief.jxhvqy"
TEST_PACKAGE="$PACKAGE.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
REMOTE="/sdcard/Android/data/$PACKAGE/files/preview"

scripts/emulator.sh --check >/dev/null || {
  echo "capture: no device ready — run scripts/emulator.sh first" >&2
  exit 1
}

echo "capture: building"
./gradlew --offline assembleDebug assembleDebugAndroidTest -q

install_apk() {
  local apk="$1"
  # A broken pipe from the package service is a known flake on this emulator;
  # pushing first and installing from /data/local/tmp survives it.
  "$ADB" install -r -t "$apk" >/dev/null 2>&1 && return 0
  local staged="/data/local/tmp/$(basename "$apk")"
  "$ADB" push "$apk" "$staged" >/dev/null
  "$ADB" shell pm install -r -t "$staged" >/dev/null
  "$ADB" shell rm -f "$staged" >/dev/null
}

echo "capture: installing"
install_apk apps/android/build/outputs/apk/debug/app-debug.apk
install_apk apps/android/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

run_harness() {
  "$ADB" shell am instrument -w -e class "com.example.PreviewGalleryCaptureTest#$1" \
    "$TEST_PACKAGE/$RUNNER" | tee build/preview-capture.log
  # am instrument reports a failed test on stdout and still exits 0.
  grep -q "OK (" build/preview-capture.log || {
    echo "capture: $1 did not finish cleanly" >&2
    exit 1
  }
}

echo "capture: running the capture harness"
# Capture the app a person who granted calendar access actually sees, not the
# permission prompt state.
"$ADB" shell pm grant "$PACKAGE" android.permission.READ_CALENDAR || true
"$ADB" shell rm -rf "$REMOTE"
mkdir -p build
run_harness capturePlates

# The wide plates need a real Expanded window, so resize the device rather than
# pretending a phone capture proves the adaptive layout.
echo "capture: resizing to an expanded window"
"$ADB" shell wm size 2560x1600 >/dev/null
"$ADB" shell wm density 240 >/dev/null
trap '"$ADB" shell wm size reset >/dev/null 2>&1; "$ADB" shell wm density reset >/dev/null 2>&1' EXIT
run_harness captureWidePlates
"$ADB" shell wm size reset >/dev/null
"$ADB" shell wm density reset >/dev/null
trap - EXIT

rm -rf "$OUT"
mkdir -p "$OUT"
"$ADB" pull "$REMOTE/." "$OUT" >/dev/null
"$ADB" shell rm -rf "$REMOTE"

# The harness seeds a legible day, a plan, a baseline and a set of flipped
# settings. Left behind, that state fails a later `verify.sh --device` in ways
# that read like real regressions — a Home card is missing, a row will not
# scroll, a wait times out. The plates are already pulled, so the data has done
# its job.
echo "capture: clearing the seeded app data"
"$ADB" shell pm clear "$PACKAGE" >/dev/null

ls -1 "$OUT"
echo "capture: PNGs in $OUT — run scripts/embed-preview-plates.py to fill preview.html"
