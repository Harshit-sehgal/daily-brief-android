#!/usr/bin/env bash
# Build PlannerCore for both Apple slices and prove a Swift client can import the module.
# This intentionally runs only on macOS: Kotlin/Native and Apple's SDK are not available on
# the Linux development host.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ "$(uname -s)" != "Darwin" ]; then
  echo "ios: macOS is required for Kotlin/Native Apple targets and xcrun" >&2
  exit 2
fi

command -v xcrun >/dev/null 2>&1 || {
  echo "ios: xcrun is unavailable; install Xcode and select its command-line tools" >&2
  exit 2
}

./scripts/build-ios-framework.sh

FRAMEWORK="packages/planning-core/build/PlannerCore.xcframework"
SLICE="$FRAMEWORK/ios-arm64-simulator"
[ -d "$SLICE" ] || {
  echo "ios: expected simulator slice is missing from $FRAMEWORK" >&2
  exit 1
}

SMOKE="$(mktemp -t dailybrief-plannercore-import).swift"
trap 'rm -f "$SMOKE"' EXIT
cat > "$SMOKE" <<'EOF'
import PlannerCore

// Import and name the public bridge entry point used by the Expo module. The actual planning
// contract is exercised by EnginePreviewTest; this check proves the Apple module is linkable to
// Swift after Kotlin/Native produces the framework.
let _ = EnginePreview.shared
EOF

# -sdk is required: without it swiftc resolves the stdlib against the macOS SDK and cannot
# load the simulator target's library. First hosted run caught both this and the Kotlin
# compile failure before it.
xcrun swiftc \
  -typecheck \
  -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
  -target arm64-apple-ios16.4-simulator \
  -F "$SLICE" \
  "$SMOKE"

echo "ios: OK — PlannerCore XCFramework built and imported from Swift"
