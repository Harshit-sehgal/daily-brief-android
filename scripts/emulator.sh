#!/usr/bin/env bash
# Brings up an emulator that Compose tests can actually run on.
#
#   scripts/emulator.sh            # boot headless, unlock, wake, quiet animations
#   scripts/emulator.sh --window   # same, with a visible window
#   scripts/emulator.sh --check    # exit 0 if a prepared device is already up
#   scripts/emulator.sh --avd NAME # pick a different AVD
#
# Two device states fail every Compose test for reasons that look like test bugs:
#
#   "Unable to resolve activity" / "credential encrypted storage ... until user
#   (id 0) is unlocked" — user 0 is still locked, so direct boot hides the app.
#   "No compose hierarchies found in the app" — the screen is asleep, so the
#   activity never resumes.
#
# This script waits out the first and prevents the second.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.local/android-toolchain/android-sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"

# The default AVD boots from a snapshot into an already-unlocked user, which the
# cold-booting API 37 image does not reliably do headless.
AVD="dailybrief"
WINDOW="-no-window"
CHECK_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --window) WINDOW="" ;;
    --check) CHECK_ONLY=1 ;;
    --avd)
      AVD="${2:?--avd needs a name}"
      shift
      ;;
    -h | --help)
      sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "emulator: unknown option $1 (try --help)" >&2
      exit 2
      ;;
  esac
  shift
done

[ -x "$ADB" ] || {
  echo "emulator: no adb at $ADB — set ANDROID_SDK_ROOT" >&2
  exit 1
}

device_online() { [ -n "$("$ADB" devices | awk 'NR>1 && $2=="device"')" ]; }
user_unlocked() { "$ADB" shell dumpsys user 2>/dev/null | grep -q "RUNNING_UNLOCKED"; }
screen_awake() { "$ADB" shell dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake"; }

prepare() {
  "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  # Without this the screen sleeps mid-suite and the activity stops resuming.
  "$ADB" shell svc power stayon true >/dev/null 2>&1 || true
  for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" shell settings put global "$scale" 0 >/dev/null 2>&1 || true
  done
}

if [ "$CHECK_ONLY" = 1 ]; then
  device_online && user_unlocked || exit 1
  screen_awake || prepare
  exit 0
fi

if ! device_online; then
  [ -x "$EMULATOR" ] || {
    echo "emulator: no emulator binary at $EMULATOR" >&2
    exit 1
  }
  echo "emulator: booting $AVD"
  # shellcheck disable=SC2086
  nohup "$EMULATOR" -avd "$AVD" $WINDOW -no-audio -no-boot-anim \
    -gpu swiftshader_indirect >/tmp/emulator-"$AVD".log 2>&1 &
  "$ADB" wait-for-device
fi

echo "emulator: waiting for boot"
for _ in $(seq 1 120); do
  [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 3
done

echo "emulator: waiting for user 0 to unlock"
for _ in $(seq 1 60); do
  user_unlocked && break
  # A device sitting on the lock screen needs a nudge; a still-booting one ignores it.
  "$ADB" shell input keyevent 82 >/dev/null 2>&1 || true
  sleep 3
done

if ! user_unlocked; then
  echo "emulator: user 0 never unlocked — Compose tests would fail here." >&2
  echo "          Try 'scripts/emulator.sh --window' to see what it is waiting on." >&2
  exit 1
fi

prepare
echo "emulator: ready — $("$ADB" shell getprop ro.build.version.release | tr -d '\r') (API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r'))"
