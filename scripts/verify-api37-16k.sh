#!/usr/bin/env bash
# Verify the Android app on a real API 37.1 / 16 KB-page device.
#
# The host must start the AVD or attach a physical device first. This script deliberately
# refuses to infer API coverage from the API 36 emulator or from APK zip alignment alone.
#
#   ANDROID_SERIAL=emulator-5556 scripts/verify-api37-16k.sh
#   ANDROID_SERIAL=emulator-5556 scripts/verify-api37-16k.sh --instrumentation
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.local/android-toolchain/android-sdk}}"
ADB="$SDK/platform-tools/adb"
SERIAL="${ANDROID_SERIAL:-}"
RUN_INSTRUMENTATION=0
JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

case "${1:-}" in
  "") ;;
  --instrumentation) RUN_INSTRUMENTATION=1 ;;
  -h | --help)
    sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "16k: unknown option $1 (try --help)" >&2
    exit 2
    ;;
esac

[ -x "$ADB" ] || { echo "16k: adb is unavailable at $ADB" >&2; exit 1; }

if [ -z "$SERIAL" ]; then
  while read -r candidate; do
    [ -n "$candidate" ] || continue
    sdk_full="$($ADB -s "$candidate" shell getprop ro.build.version.sdk_full 2>/dev/null | tr -d '\r')"
    pages="$($ADB -s "$candidate" shell getconf PAGESIZE 2>/dev/null | tr -d '\r')"
    if [ "$sdk_full" = "37.1" ] && [ "$pages" = "16384" ]; then
      SERIAL="$candidate"
      break
    fi
  done < <($ADB devices | awk 'NR > 1 && $2 == "device" { print $1 }')
fi

[ -n "$SERIAL" ] || {
  echo "16k: no online API 37.1 / 16384-byte-page device found; set ANDROID_SERIAL" >&2
  exit 1
}

target() { "$ADB" -s "$SERIAL" "$@"; }
fail() { echo "16k: FAILED — $1" >&2; exit 1; }

[ "$(target get-state 2>/dev/null || true)" = "device" ] || fail "$SERIAL is not online"
SDK_FULL="$(target shell getprop ro.build.version.sdk_full | tr -d '\r')"
PAGESIZE="$(target shell getconf PAGESIZE | tr -d '\r')"
[ "$SDK_FULL" = "37.1" ] || fail "expected SDK 37.1, got $SDK_FULL"
[ "$PAGESIZE" = "16384" ] || fail "expected 16384-byte pages, got $PAGESIZE"

echo "16k: selected $SERIAL — SDK $SDK_FULL, page size $PAGESIZE"

for _ in $(seq 1 30); do
  boot="$(target shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  services="$(target shell service list 2>/dev/null || true)"
  user="$(target shell dumpsys user 2>/dev/null || true)"
  if [ "$boot" = "1" ] &&
    printf '%s\n' "$services" | grep -Eq 'package:' &&
    printf '%s\n' "$services" | grep -Eq 'activity:' &&
    printf '%s\n' "$user" | grep -Eq 'RUNNING_UNLOCKED|Started users state:.*\{0=3([,}]|$)'; then
    break
  fi
  sleep 2
done

services="$(target shell service list 2>/dev/null || true)"
user="$(target shell dumpsys user 2>/dev/null || true)"
[ "$(target shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || fail "Android boot did not complete"
printf '%s\n' "$services" | grep -Eq 'package:' || fail "package service is unavailable"
printf '%s\n' "$services" | grep -Eq 'activity:' || fail "activity service is unavailable"
printf '%s\n' "$user" | grep -Eq 'RUNNING_UNLOCKED|Started users state:.*\{0=3([,}]|$)' ||
  fail "user 0 is not unlocked"

echo "16k: building current release APK"
./gradlew --offline --no-daemon assembleRelease >/dev/null

echo "16k: running targeted release smoke"
ANDROID_SERIAL="$SERIAL" ./scripts/smoke-release.sh

if [ "$RUN_INSTRUMENTATION" = 1 ]; then
  echo "16k: running targeted instrumentation"
  ANDROID_SERIAL="$SERIAL" ./gradlew --offline --no-daemon --stacktrace :app:connectedDebugAndroidTest
fi

echo "16k: OK — API 37.1 / 16 KB runtime proof completed on $SERIAL"
