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
EMULATOR_GPU="${DAILYBRIEF_EMULATOR_GPU:-swiftshader_indirect}"
EMULATOR_PID=""
AVD_WAS_EXPLICIT=0
EXPECT_AVD=0
DEVICE_SERIAL=""
SERIAL_REQUEST="${ANDROID_SERIAL:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --window) WINDOW="" ;;
    --check) CHECK_ONLY=1 ;;
    --avd)
      AVD="${2:?--avd needs a name}"
      AVD_WAS_EXPLICIT=1
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

device_serial_for_request() {
  local serial avd_name
  while read -r serial; do
    [ -n "$serial" ] || continue
    if [ "$AVD_WAS_EXPLICIT" = 1 ] || [ "$EXPECT_AVD" = 1 ]; then
      avd_name="$("$ADB" -s "$serial" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')"
      # Older emulator guests expose the same identity under the kernel
      # namespace instead of ro.boot.
      [ -n "$avd_name" ] ||
        avd_name="$("$ADB" -s "$serial" shell getprop ro.kernel.qemu.avd_name 2>/dev/null | tr -d '\r')"
      [ "$avd_name" = "$AVD" ] || continue
    fi
    printf '%s\n' "$serial"
    return 0
  done < <(
    if [ -n "$SERIAL_REQUEST" ]; then
      "$ADB" devices | awk -v wanted="$SERIAL_REQUEST" 'NR > 1 && $1 == wanted && $2 == "device" { print $1 }'
    else
      "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
    fi
  )
  return 1
}

refresh_device() {
  DEVICE_SERIAL="$(device_serial_for_request || true)"
  [ -n "$DEVICE_SERIAL" ]
}

target_adb() {
  [ -n "$DEVICE_SERIAL" ] || return 1
  "$ADB" -s "$DEVICE_SERIAL" "$@"
}

device_online() { refresh_device; }
# Android 7's dumpsys user does not print the symbolic state name. Its state
# value 3 is the same RUNNING_UNLOCKED state emitted by newer guests.
user_unlocked() {
  target_adb shell dumpsys user 2>/dev/null |
    grep -Eq 'RUNNING_UNLOCKED|Started users state:.*\{0=3([,}]|$)'
}
screen_awake() { target_adb shell dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake"; }

emulator_process_alive() {
  if [ -n "$EMULATOR_PID" ] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
    return 0
  fi
  # The emulator launcher can exit after handing the guest to qemu-system. Keep
  # checking the detached guest process instead of mistaking that hand-off for
  # a crash, while still requiring the requested AVD identity.
  ps -eo args= 2>/dev/null |
    grep -Eq "qemu-system[^ ]*[[:space:]].*[[:space:]]-avd[[:space:]]+$AVD([[:space:]]|$)"
}

prepare() {
  target_adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  target_adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  # Without this the screen sleeps mid-suite and the activity stops resuming.
  target_adb shell svc power stayon true >/dev/null 2>&1 || true
  for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    target_adb shell settings put global "$scale" 0 >/dev/null 2>&1 || true
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
  # Detach the emulator from the launcher process group. Some CI/PTY hosts reap
  # background children as soon as this script returns, which looks like an
  # emulator crash immediately after a successful boot.
  nohup setsid "$EMULATOR" -avd "$AVD" $WINDOW -no-audio -no-boot-anim \
    -gpu "$EMULATOR_GPU" >/tmp/emulator-"$AVD".log 2>&1 &
  EMULATOR_PID=$!
  EXPECT_AVD=1
fi

echo "emulator: waiting for boot"
for _ in $(seq 1 120); do
  if refresh_device && [ "$(target_adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    break
  fi
  sleep 3
done

echo "emulator: waiting for user 0 to unlock"
for _ in $(seq 1 60); do
  refresh_device && user_unlocked && break
  # A device sitting on the lock screen needs a nudge; a still-booting one ignores it.
  target_adb shell input keyevent 82 >/dev/null 2>&1 || true
  sleep 3
done

if ! user_unlocked; then
  echo "emulator: user 0 never unlocked — Compose tests would fail here." >&2
  echo "          Try 'scripts/emulator.sh --window' to see what it is waiting on." >&2
  exit 1
fi

prepare

# A host-side emulator can finish Android boot and then exit while adb still has a
# transient device row. Hold the readiness claim until the process and unlocked
# device both survive a short stability window.
if [ -n "$EMULATOR_PID" ]; then
  for _ in $(seq 1 10); do
    if ! emulator_process_alive || ! device_online || ! user_unlocked; then
      echo "emulator: process/device became unstable after boot — see /tmp/emulator-$AVD.log" >&2
      exit 1
    fi
    sleep 1
  done
fi

echo "emulator: ready — $(target_adb shell getprop ro.build.version.release | tr -d '\r') (API $(target_adb shell getprop ro.build.version.sdk | tr -d '\r')) [$DEVICE_SERIAL]"
