#!/usr/bin/env bash
# Builds the PlannerCore XCFramework the iOS side of the mobile app links against.
#
# macOS only: Kotlin/Native cannot cross-compile Apple targets from Linux. Run this on a
# Mac with the repo checked out, then point the mobile app's Swift module at the produced
# build/PlannerCore.xcframework (mobile/modules/planner-engine/ios/README.md).
#
# The framework is one static binary per slice with baseName "PlannerCore" (set in
# planning-core/build.gradle.kts) — the same name Swift imports as a module.
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew \
  :planning-core:linkReleaseFrameworkIosArm64 \
  :planning-core:linkReleaseFrameworkIosSimulatorArm64

xcodebuild -create-xcframework \
  -framework "planning-core/build/bin/iosArm64/releaseFramework/PlannerCore.framework" \
  -framework "planning-core/build/bin/iosSimulatorArm64/releaseFramework/PlannerCore.framework" \
  -output "planning-core/build/PlannerCore.xcframework"

echo "== build/PlannerCore.xcframework ready"