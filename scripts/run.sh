#!/usr/bin/env bash
# Daily Brief — one command to see the app.
#
#   ./scripts/run.sh            # window + build + install + launch (do this)
#   ./scripts/run.sh --headless  # no window, just build + install + screencap
#   ./scripts/run.sh --rebuild   # force Gradle recompile first
#
# No JAVA_HOME / ANDROID_HOME setup needed — this sets it. No second command,
# no adb dance. Kills a headless AVD that would hide the window, waits for
# unlocked user 0 (Compose needs it), installs the debug APK under its real
# id com.aistudio.dailybrief.jxhvqy, and launches com.example.MainActivity.
#
# If it says "ready — Daily Brief is on screen" you can touch the window.
# If you stay headless it prints a screencap path instead.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

TOOLCHAIN_JDK="$HOME/.local/android-toolchain/jdk"
SDK="$HOME/.local/android-toolchain/android-sdk"
export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN_JDK}"
export ANDROID_HOME="${ANDROID_HOME:-$SDK}"
export PATH="$JAVA_HOME/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

REBUILD=0
HEADLESS=0
for a in "$@"; do
  case "$a" in
    --rebuild) REBUILD=1 ;;
    --headless) HEADLESS=1 ;;
    -h|--help)
      echo "Usage: ./scripts/run.sh [--headless] [--rebuild]"
      echo "  (no args)  window + build + install + launch — run this on your laptop screen"
      echo "  --headless just build + install + screencap to /tmp/dailybrief-screen.png (servers/SSH)"
      echo "  --rebuild  force ./gradlew assembleDebug first (otherwise reuses apps/android/build/outputs/apk/debug/app-debug.apk)"
      exit 0 ;;
    *) echo "run: unknown option $a (try --help)" >&2; exit 2 ;;
  esac
done

# No display server → no window possible (SSH/server/CI). Fall back to headless.
if [ "$HEADLESS" = 0 ] && [ -z "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
  echo "run: no DISPLAY/WAYLAND_DISPLAY — staying headless (open a terminal on the laptop screen, or run ./scripts/run.sh --headless)." >&2
  HEADLESS=1
fi
# A headless dailybrief already holds the emulator socket; a window needs a restart.
if [ "$HEADLESS" = 0 ] && adb devices 2>/dev/null | grep -q "emulator-.*device"; then
  echo "run: restarting headless emulator with a window so you can see it..."
  adb emu kill 2>/dev/null || true
  sleep 3
fi

# 1. Emulator — window when we can, headless when we must
if [ "$HEADLESS" = 1 ]; then
  scripts/emulator.sh
else
  scripts/emulator.sh --window
fi

# 2. Build — only when missing or forced; the pipeline is offline
APK="apps/android/build/outputs/apk/debug/app-debug.apk"
if [ "$REBUILD" = 1 ] || [ ! -f "$APK" ]; then
  echo "run: building debug APK..."
  ./gradlew --offline assembleDebug
else
  echo "run: using existing $APK ($(du -h "$APK" | cut -f1)) — --rebuild to force a compile"
fi

# 3. Install & launch — real id is com.aistudio.dailybrief.jxhvqy, activity com.example.MainActivity
echo "run: installing $APK..."
adb install -r "$APK"
echo "run: launching Daily Brief..."
adb shell am start -n com.aistudio.dailybrief.jxhvqy/com.example.MainActivity >/dev/null
sleep 2
if ! adb shell dumpsys window 2>/dev/null | grep -q "com.aistudio.dailybrief.jxhvqy/com.example.MainActivity"; then
  echo "run: warning — window is not Daily Brief yet, dumping current focus:"
  adb shell dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus" || true
fi

if [ "$HEADLESS" = 0 ]; then
  echo ""
  echo "ready — Daily Brief is on screen."
  echo "  Try: Home → Plan (bottom bar) → Gantt → Full map → (back)"
  echo "  FAB is now \"+ New task / + New event\" (the breakpoint is gone)."
  echo "  Logs: adb logcat --pid=\$(adb shell pidof -s com.aistudio.dailybrief.jxhvqy)"
  echo "  Stop: adb emu kill"
else
  CAP="/data/local/tmp/run-screen.png"
  adb shell screencap -p "$CAP" >/dev/null
  adb pull "$CAP" /tmp/dailybrief-screen.png >/dev/null 2>&1 || true
  echo ""
  echo "ready (headless) — no display, so a screencap was saved:"
  echo "  /tmp/dailybrief-screen.png  (open it, or run with a window on your laptop)"
  echo "  Launch again: adb shell am start -n com.aistudio.dailybrief.jxhvqy/com.example.MainActivity"
fi
