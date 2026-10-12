#!/usr/bin/env bash
# A temporary public link to the notebook, served from this computer through a
# Cloudflare quick tunnel. For showing it to someone, not for running it: the link dies
# when this script stops, its address changes on every run, and sign-in is the fixture
# account — EVERYONE WITH THE LINK SHARES ONE NOTEBOOK and can read and change it.
#
#   scripts/share-tunnel.sh          # prints the https://….trycloudflare.com link; Ctrl-C stops it
#
# One address serves both the pages and the API (the web server forwards /v1/… to the
# planning server), so no domain or DNS is needed. Data lives in a docker Postgres
# container named dailybrief-share and survives restarts; `docker rm -f dailybrief-share`
# starts over. Needs docker, cloudflared, node and the pinned JDK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/build/share"
CONTAINER="dailybrief-share"
PORT_DB="${DAILYBRIEF_SHARE_DB_PORT:-54340}"
PORT_API="${DAILYBRIEF_SHARE_API_PORT:-8095}"
PORT_WEB="${DAILYBRIEF_SHARE_WEB_PORT:-3020}"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$STATE"

fail() { echo "FAIL: $1" >&2; exit 1; }
for tool in docker cloudflared node curl; do command -v "$tool" >/dev/null || fail "$tool is not installed"; done
for port in "$PORT_API" "$PORT_WEB"; do
  ss -tln 2>/dev/null | grep -q ":${port}\b" && fail "port $port is in use; set DAILYBRIEF_SHARE_API_PORT / DAILYBRIEF_SHARE_WEB_PORT"
done

# Secrets are made once and kept, so notes written on one run can be read on the next.
[ -f "$STATE/session.secret" ] || head -c 48 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$STATE/session.secret"
[ -f "$STATE/envelope.key" ] || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$STATE/envelope.key"
chmod 600 "$STATE/session.secret" "$STATE/envelope.key"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do [ -n "$pid" ] && { pkill -P "$pid" 2>/dev/null || true; kill "$pid" 2>/dev/null || true; }; done
  for port in "$PORT_API" "$PORT_WEB"; do
    listener=$(ss -tlnp 2>/dev/null | sed -n "s/.*:${port}\\b.*pid=\\([0-9]*\\).*/\\1/p" | head -1)
    [ -n "$listener" ] && kill "$listener" 2>/dev/null || true
  done
  docker stop "$CONTAINER" >/dev/null 2>&1 || true
  echo "share: stopped. The link no longer works."
}
trap cleanup EXIT

echo "== Postgres"
if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  docker start "$CONTAINER" >/dev/null
else
  docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=dailybrief \
    -p "127.0.0.1:${PORT_DB}:5432" postgres:16 >/dev/null
fi
for i in $(seq 1 60); do
  docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1 && break
  [ "$i" = 60 ] && fail "postgres did not come up"
  sleep 0.5
done

echo "== Tunnel"
: > "$STATE/tunnel.log"
cloudflared tunnel --no-autoupdate --url "http://localhost:${PORT_WEB}" > "$STATE/tunnel.log" 2>&1 &
PIDS+=($!)
URL=""
for i in $(seq 1 60); do
  URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$STATE/tunnel.log" | head -1 || true)
  [ -n "$URL" ] && break
  sleep 1
done
[ -n "$URL" ] || fail "cloudflared gave no address; see $STATE/tunnel.log"

echo "== Server"
# Offline first (fast, and the usual case); online if the module cache is not warm.
(cd "$ROOT" && ./gradlew --offline :server:installDist -q >/dev/null 2>&1) \
  || (cd "$ROOT" && ./gradlew :server:installDist -q >/dev/null 2>&1) || fail "the server did not build"
DATABASE_URL="jdbc:postgresql://localhost:${PORT_DB}/dailybrief" DATABASE_USER=postgres DATABASE_PASSWORD=postgres \
  SESSION_SECRET="$(cat "$STATE/session.secret")" ENVELOPE_KEY_HEX="$(cat "$STATE/envelope.key")" \
  FIXTURE_PROVIDER=1 PORT="$PORT_API" WEB_ORIGIN="$URL" \
  "$ROOT/services/server/build/install/server/bin/server" > "$STATE/server.log" 2>&1 &
PIDS+=($!)
for i in $(seq 1 120); do
  curl -sf "http://localhost:${PORT_API}/health/ready" >/dev/null 2>&1 && break
  [ "$i" = 120 ] && fail "the server did not become ready; see $STATE/server.log"
  sleep 0.5
done

echo "== Web"
(cd "$ROOT/apps/web" && NEXT_PUBLIC_FIXTURE=1 NEXT_PUBLIC_API_BASE=same-origin \
  API_PROXY_TARGET="http://localhost:${PORT_API}" npm run build > "$STATE/web-build.log" 2>&1) \
  || fail "the web app did not build; see $STATE/web-build.log"
(cd "$ROOT/apps/web" && npx next start --hostname 127.0.0.1 --port "$PORT_WEB" > "$STATE/web.log" 2>&1) &
PIDS+=($!)
for i in $(seq 1 120); do
  curl -sf "http://localhost:${PORT_WEB}/" >/dev/null 2>&1 && break
  [ "$i" = 120 ] && fail "the web app did not start; see $STATE/web.log"
  sleep 0.5
done

echo "$URL" > "$STATE/url"
echo
echo "   $URL"
echo
echo "share: up. Everyone with this link shares one notebook. Ctrl-C stops it."
wait
