#!/usr/bin/env bash
# Publishes the engine artifacts the mobile planner-engine module needs to mavenLocal.
# Run from the repo root with the pinned toolchain (scripts/verify.sh does the same setup).
# iOS targets are not published here: building their klibs requires macOS.
#
# --mobile: the Expo app compiles with AGP 8.12, which has no minor API levels, so the
# android AARs are built at compileSdk 36 instead of 37.1 (the gate and :app keep 37.1).
# Use this flag after regenerating apps/mobile/android or after any engine change. The Expo
# config plugin in apps/mobile/plugins/with-local-emulator-cleartext.js reapplies the mobile
# Gradle toolchain pins during prebuild: Kotlin 2.3.20, compileSdk 36 as an Int before the
# Expo root plugin, and mavenLocal().
# Full rationale: apps/mobile/README.md.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

MOBILE=""
if [[ "${1:-}" == "--mobile" ]]; then MOBILE="-PmobileCompileSdk=36"; fi

# --offline is the local default (every dependency is cached); DAILYBRIEF_GRADLE_ONLINE=1
# opts out for a fresh CI checkout whose Gradle cache is cold — same hatch as journey.sh.
if [ "${DAILYBRIEF_GRADLE_ONLINE:-0}" = "1" ]; then
  ./gradlew $MOBILE \
    :planning-contract:publishJvmPublicationToMavenLocal \
    :planning-contract:publishAndroidPublicationToMavenLocal \
    :planning-contract:publishKotlinMultiplatformPublicationToMavenLocal \
    :planning-core:publishAndroidPublicationToMavenLocal \
    :planning-core:publishKotlinMultiplatformPublicationToMavenLocal
else
  ./gradlew --offline $MOBILE \
    :planning-contract:publishJvmPublicationToMavenLocal \
    :planning-contract:publishAndroidPublicationToMavenLocal \
    :planning-contract:publishKotlinMultiplatformPublicationToMavenLocal \
    :planning-core:publishAndroidPublicationToMavenLocal \
    :planning-core:publishKotlinMultiplatformPublicationToMavenLocal
fi

echo "Published com.example:planning-core-android:0.1.0 (+ planning-contract) to ~/.m2${MOBILE:+ (mobile: compileSdk 36)}"
