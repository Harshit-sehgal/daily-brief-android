#!/usr/bin/env bash
# Runs the same gates CI runs, with the JDK this machine actually needs.
#
#   scripts/verify.sh              # the full CI gate, offline
#   scripts/verify.sh --fast       # unit tests only — the inner-loop check
#   scripts/verify.sh --device     # ...plus instrumentation on a running emulator
#   scripts/verify.sh --online     # allow Gradle to reach the network
#
# Everything is one Gradle invocation with --continue, so a lint failure still
# lets you see whether the tests passed.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

TOOLCHAIN_JDK="$HOME/.local/android-toolchain/jdk"

java_major() {
  local home="$1"
  [ -x "$home/bin/java" ] || return 1
  "$home/bin/java" -version 2>&1 | head -1 |
    sed -E 's/.*version "([0-9]+)(\.[0-9]+)?.*/\1/' | sed 's/^1$/8/'
}

# AGP needs 17+. The system java here is 8, so fall back to the pinned toolchain.
if [ -n "${JAVA_HOME:-}" ] && [ "$(java_major "$JAVA_HOME" || echo 0)" -ge 17 ] 2>/dev/null; then
  : # caller's JDK is fine
elif [ -d "$TOOLCHAIN_JDK" ]; then
  JAVA_HOME="$TOOLCHAIN_JDK"
else
  echo "verify: no JDK 17+ found. Set JAVA_HOME or install one at $TOOLCHAIN_JDK" >&2
  exit 1
fi
export JAVA_HOME

FAST=0
DEVICE=0
NETWORK="--offline"
for arg in "$@"; do
  case "$arg" in
    --fast) FAST=1 ;;
    --device) DEVICE=1 ;;
    --online) NETWORK="" ;;
    -h | --help)
      sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "verify: unknown option $arg (try --help)" >&2
      exit 2
      ;;
  esac
done

# The engine's tests are a separate task: :planning-core is a Kotlin Multiplatform
# module, so it has jvmTest rather than the app's testDebugUnitTest, and an unqualified
# task name would skip it silently.
if [ "$FAST" = 1 ]; then
  TASKS=(:planning-core:jvmTest testDebugUnitTest)
else
  # Mirrors .github/workflows: JVM tests, both lints, both APKs, R8 config.
  TASKS=(
    :planning-core:jvmTest
    testDebugUnitTest
    lintDebug
    assembleDebug
    assembleDebugAndroidTest
    lintRelease
    assembleRelease
    :app:analyzeReleaseR8Config
  )
fi

if [ "$DEVICE" = 1 ]; then
  if ! scripts/emulator.sh --check; then
    echo "verify: no device ready — run scripts/emulator.sh first" >&2
    exit 1
  fi
  TASKS+=(connectedDebugAndroidTest)
fi

echo "verify: JAVA_HOME=$JAVA_HOME"
echo "verify: ./gradlew ${NETWORK} --continue ${TASKS[*]}"
# shellcheck disable=SC2086
exec ./gradlew $NETWORK --continue "${TASKS[@]}"
