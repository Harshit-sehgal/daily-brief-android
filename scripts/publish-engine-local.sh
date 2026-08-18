#!/usr/bin/env bash
# Publishes the engine artifacts the mobile planner-engine module needs to mavenLocal.
# Run from the repo root with the pinned toolchain (scripts/verify.sh does the same setup).
# iOS targets are not published here: building their klibs requires macOS.
#
# --mobile: the Expo app compiles with AGP 8.12, which has no minor API levels, so the
# android AARs are built at compileSdk 36 instead of 37.1 (the gate and :app keep 37.1).
# Use this flag after regenerating apps/mobile/android (expo prebuild overwrites it) or after
# any engine change; apps/mobile/android must also carry:
#   gradle.properties: kotlinVersion=2.3.20  # the engine AAR is Kotlin 2.4.10; 2.3.x reads its metadata
#   build.gradle: ext.compileSdkVersion = 36 (Int, before the expo-root-project apply; a
#     gradle.properties value arrives as a String and AGP 8.12 fails "Value is null"),
#     KGP 2.3.20 on the buildscript classpath, mavenLocal() in allprojects.
# Full rationale: apps/mobile/README.md.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

MOBILE=""
if [[ "${1:-}" == "--mobile" ]]; then MOBILE="-PmobileCompileSdk=36"; fi

./gradlew --offline $MOBILE \
  :planning-contract:publishJvmPublicationToMavenLocal \
  :planning-contract:publishAndroidPublicationToMavenLocal \
  :planning-contract:publishKotlinMultiplatformPublicationToMavenLocal \
  :planning-core:publishAndroidPublicationToMavenLocal \
  :planning-core:publishKotlinMultiplatformPublicationToMavenLocal

echo "Published com.example:planning-core-android:0.1.0 (+ planning-contract) to ~/.m2${MOBILE:+ (mobile: compileSdk 36)}"
