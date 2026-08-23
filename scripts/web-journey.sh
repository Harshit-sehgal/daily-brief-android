#!/usr/bin/env bash
# Run the V1 command-center journey in a real headless browser against a fixture API.
# This is separate from journey.sh: the latter proves the HTTP contract; this one proves
# the rendered web client and its cookie/CSRF path.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="dailybrief-web-journey"
PORT_DB="${DAILYBRIEF_WEB_DB_PORT:-54332}"
PORT_API="${DAILYBRIEF_WEB_API_PORT:-8091}"
PORT_WEB="${DAILYBRIEF_WEB_PORT:-3001}"
DB_URL="jdbc:postgresql://localhost:${PORT_DB}/dailybrief"
EVIDENCE_DIR="${DAILYBRIEF_WEB_EVIDENCE:-${ROOT}/build/web-journey-evidence}"
mkdir -p "$EVIDENCE_DIR"
rm -f "$EVIDENCE_DIR/web-journey-failure.png"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE_ARGS=(--offline)
if [ "${DAILYBRIEF_GRADLE_ONLINE:-0}" = "1" ]; then
  GRADLE_ARGS=()
fi

step() { echo "== $1"; }
fail() { echo "FAIL: $1" >&2; exit 1; }

for port in "$PORT_DB" "$PORT_API" "$PORT_WEB"; do
  if ss -tln 2>/dev/null | grep -q ":${port}\b"; then
    fail "port $port is already in use; set DAILYBRIEF_WEB_DB_PORT, DAILYBRIEF_WEB_API_PORT, or DAILYBRIEF_WEB_PORT"
  fi
done

step "Postgres"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=dailybrief \
  -p "${PORT_DB}:5432" postgres:16 >/dev/null
for i in $(seq 1 60); do
  if docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  [ "$i" = 60 ] && fail "postgres did not come up"
  sleep 0.5
done

SERVER_PID=""
WEB_PID=""
cleanup() {
  [ -n "$WEB_PID" ] && kill "$WEB_PID" 2>/dev/null || true
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

step "Build server and web"
(cd "$ROOT" && ./gradlew "${GRADLE_ARGS[@]}" :server:installDist --rerun-tasks >/dev/null)
(cd "$ROOT/apps/web" && NEXT_PUBLIC_FIXTURE=1 NEXT_PUBLIC_API_BASE="http://localhost:${PORT_API}" npm run build >"$EVIDENCE_DIR/web-build.log" 2>&1)

ENVELOPE_KEY=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
DATABASE_URL="$DB_URL" DATABASE_USER=postgres DATABASE_PASSWORD=postgres \
  SESSION_SECRET="web-journey-session-secret-0123456789abcdef" \
  ENVELOPE_KEY_HEX="$ENVELOPE_KEY" FIXTURE_PROVIDER=1 PORT="$PORT_API" \
  WEB_ORIGIN="http://localhost:${PORT_WEB}" \
  "$ROOT/services/server/build/install/server/bin/server" >"$EVIDENCE_DIR/server.log" 2>&1 &
SERVER_PID=$!

for i in $(seq 1 120); do
  if curl -sf "http://localhost:${PORT_API}/health/ready" >/dev/null 2>&1; then break; fi
  [ "$i" = 120 ] && fail "fixture API did not become ready"
  sleep 0.5
done

(cd "$ROOT/apps/web" && \
  NEXT_PUBLIC_FIXTURE=1 NEXT_PUBLIC_API_BASE="http://localhost:${PORT_API}" \
  npm run start -- --hostname 127.0.0.1 --port "$PORT_WEB" >"$EVIDENCE_DIR/web.log" 2>&1) &
WEB_PID=$!
for i in $(seq 1 120); do
  if curl -sf "http://localhost:${PORT_WEB}/" >/dev/null 2>&1; then break; fi
  [ "$i" = 120 ] && fail "web server did not become ready"
  sleep 0.5
done

step "Rendered browser journey"
export DAILYBRIEF_WEB_BASE="http://localhost:${PORT_WEB}"
export DAILYBRIEF_WEB_API_BASE="http://localhost:${PORT_API}"
export DAILYBRIEF_WEB_EVIDENCE="$EVIDENCE_DIR"
(cd "$ROOT/apps/web" && npm run test:e2e)
echo "web journey: evidence in $EVIDENCE_DIR"
