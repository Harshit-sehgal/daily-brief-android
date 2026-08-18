#!/usr/bin/env bash
# The WP-13 acceptance test, automated: the whole loop — sign in → connect a calendar →
# add tasks → Plan my week → proposal with reasons → apply → Today — headless against a
# fixture provider and a real Postgres, with each step timed. The 90-second budget is the
# slice's acceptance criterion, not a metaphor: the fixture leg must stay well under it.
#
# The manual leg — the same journey on a real Google account with the stopwatch — is the
# runbook in docs/saas/10-wp13-journey-runbook.md.
set -euo pipefail

CONTAINER=dailybrief-journey
PORT_DB=54331
PORT_API=8090
DB_URL="jdbc:postgresql://localhost:$PORT_DB/dailybrief"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/home/harshit/.local/android-toolchain/jdk}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

step() { echo "== $1"; }
fail() { echo "FAIL: $1" >&2; exit 1; }
start_ms() { date +%s%3N; }

# ── Postgres ──────────────────────────────────────────────────────────────────────────────
step "Postgres"
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=dailybrief \
    -p "$PORT_DB:5432" postgres:16 >/dev/null
fi
for i in $(seq 1 60); do
  if docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  [ "$i" = 60 ] && fail "postgres did not come up"
  sleep 0.5
done

# ── Server ────────────────────────────────────────────────────────────────────────────────
step "Build + boot server"
# A previous run's server (or a stray dev server) squatting the API port turns step 1's
# signup into a success against stale code and step 3 into a 401 with no explanation.
# Check before booting. A server is `java -cp ... com.example.server.MainKt` — the launcher
# script's path is gone from its cmdline after exec, so match the main class instead.
if ss -tlnp 2>/dev/null | grep -q ":$PORT_API\b"; then
  OWNER=$(ss -tlnp 2>/dev/null | grep ":$PORT_API\b" | sed -E 's/.*users:\(\("([^"]+)".*/\1/' | head -1)
  fail "port $PORT_API is taken by pid $OWNER (stray server?). Kill it and re-run."
fi
(cd "$ROOT" && ./gradlew --offline :server:installDist >/dev/null 2>&1)
ENVELOPE_KEY=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
DATABASE_URL="$DB_URL" DATABASE_USER=postgres DATABASE_PASSWORD=postgres \
  SESSION_SECRET="journey-session-secret-0123456789abcdef" \
  ENVELOPE_KEY_HEX="$ENVELOPE_KEY" FIXTURE_PROVIDER=1 PORT="$PORT_API" \
  "$ROOT/server/build/install/server/bin/server" >/tmp/opencode/journey-server.log 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true; docker stop "$CONTAINER" >/dev/null 2>&1 || true' EXIT

T0=$(start_ms)
for i in $(seq 1 120); do
  if curl -sf -X POST "http://localhost:$PORT_API/v1/fixture/signup" -o /tmp/opencode/journey-signup.json 2>/dev/null; then break; fi
  [ "$i" = 120 ] && fail "server did not come up; log: $(tail -5 /tmp/opencode/journey-signup.json 2>/dev/null || tail -5 /tmp/opencode/journey-server.log)"
  sleep 0.5
done
TOKEN=$(jq -r .token /tmp/opencode/journey-signup.json)
WS=$(jq -r .workspaceId /tmp/opencode/journey-signup.json)
step "1. signup → session   (+$(( $(start_ms) - T0 )) ms)"

api() { # api <method> <path> <json-or-dash> <outfile>
  local method=$1 path=$2 body=$3 out=$4
  if [ "$body" = "-" ]; then
    curl -sf -X "$method" -H "Authorization: Bearer $TOKEN" "http://localhost:$PORT_API$path" -o "$out"
  else
    curl -sf -X "$method" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -d "$body" "http://localhost:$PORT_API$path" -o "$out"
  fi
}

# ── Steps, timed ──────────────────────────────────────────────────────────────────────────
T=$(start_ms)
NOW=$(( $(date -u +%s%3N) / 86400000 * 86400000 ))   # today 00:00 UTC
HOUR=3600000
TASK1='{"id":"t1","boardId":"b1","title":"Write the proposal","rank":0,"effortMinutes":90,"dueAt":'$((NOW + 17*HOUR))'}'
TASK2='{"id":"t2","boardId":"b1","title":"Prep the slides","rank":1,"effortMinutes":45}'
TASK3='{"id":"t3","boardId":"b1","title":"Review the deck","rank":2,"effortMinutes":60,"dueAt":'$((NOW + 40*HOUR))'}'
api POST /v1/tasks "$TASK1" /tmp/opencode/journey-t1.json
api POST /v1/tasks "$TASK2" /tmp/opencode/journey-t2.json
api POST /v1/tasks "$TASK3" /tmp/opencode/journey-t3.json
step "2. add three tasks   (+$(( $(start_ms) - T )) ms)"

T=$(start_ms)
api POST /v1/reconcile - /tmp/opencode/journey-reconcile.json
step "3. connect calendar   (+$(( $(start_ms) - T )) ms)"

# ── Plan: the request composes the calendar's busy time into fixedCommitments, exactly as
# the web client will. The stand-up is today 14:00-15:00.
T=$(start_ms)
WINDOWS=$(jq -cn '[range(1;6) as $d | {id:("w"+($d|tostring)), dayOfWeek:$d, startMinute:540, endMinute:1020, rank:$d}]')
REQUEST=$(jq -cn \
  --argjson now "$NOW" \
  --arg ws "$WS" \
  --argjson windows "$WINDOWS" \
  '{v:1, workspaceId:$ws,
    rangeStartMs:$now, rangeEndMs:($now+7*86400000), nowMs:$now,
    items:[
      {id:"t1", boardId:"b1", title:"Write the proposal", rank:0, effortMinutes:90, dueAt:($now+17*3600000)},
      {id:"t2", boardId:"b1", title:"Prep the slides", rank:1, effortMinutes:45},
      {id:"t3", boardId:"b1", title:"Review the deck", rank:2, effortMinutes:60, dueAt:($now+40*3600000)}
    ],
    blocks:[], dependencies:[],
    fixedCommitments:[{startAt:($now+14*3600000), endAt:($now+15*3600000)}],
    scheduleIdByTaskId:{}, preferredOrder:[],
    schedules:[{id:"s1", name:"Weekdays", timeZoneId:"UTC", isDefault:true, minimumChunkMinutes:30, maximumChunkMinutes:120, bufferMinutes:0, rank:0, windows:$windows}],
    deadlinePolicy:"HARD"}')
api POST /v1/plan "$REQUEST" /tmp/opencode/journey-plan.json
jq -e '.result.proposals | length >= 2' /tmp/opencode/journey-plan.json >/dev/null || fail "expected >= 2 proposals, got $(jq '.result.proposals | length' /tmp/opencode/journey-plan.json)"
jq -e '.result.proposals[0].reason | length > 0' /tmp/opencode/journey-plan.json >/dev/null || fail "proposal carries no reason"
jq -e --argjson start $(( NOW + 14*HOUR )) --argjson end $(( NOW + 15*HOUR )) \
  '[.result.proposals[] | select(.startAt < $end and .endAt > $start)] | length == 0' /tmp/opencode/journey-plan.json >/dev/null \
  || fail "a proposal overlaps the stand-up"
RUN_ID=$(jq -r .runId /tmp/opencode/journey-plan.json)
step "4. plan my week       (+$(( $(start_ms) - T )) ms)"

# ── Capacity: the same request asks "can I take another client?" — three numbers, one
# sentence (Stage 4.1). The plan fits, so an 8 h/week client must fit too.
T=$(start_ms)
CAPACITY=$(jq -cn --argjson plan "$REQUEST" '{v:1, plan:$plan, newClientHoursPerWeek:8}')
api POST /v1/capacity "$CAPACITY" /tmp/opencode/journey-capacity.json
jq -e '.verdict == "CAN_TAKE"' /tmp/opencode/journey-capacity.json >/dev/null \
  || fail "expected CAN_TAKE, got $(jq -r .verdict /tmp/opencode/journey-capacity.json)"
jq -e '.availableMinutes > 0 and .spareMinutes >= 480' /tmp/opencode/journey-capacity.json >/dev/null \
  || fail "capacity numbers look wrong: $(jq -c . /tmp/opencode/journey-capacity.json)"
jq -e '.sentence | length > 0' /tmp/opencode/journey-capacity.json >/dev/null || fail "capacity carries no sentence"
step "4b. capacity          (+$(( $(start_ms) - T )) ms)"

# ── Apply → Today ─────────────────────────────────────────────────────────────────────────
T=$(start_ms)
api POST "/v1/plan/$RUN_ID/apply" - /tmp/opencode/journey-apply.json
jq -e '.blocks >= 2' /tmp/opencode/journey-apply.json >/dev/null || fail "apply wrote too few blocks"
ENTRY_ID=$(jq -r .entryId /tmp/opencode/journey-apply.json)
api GET /v1/today - /tmp/opencode/journey-today.json
jq -e '.blocks | length >= 2' /tmp/opencode/journey-today.json >/dev/null || fail "today does not show the applied blocks"
jq -e '[.events[].title] | index("Client stand-up") != null' /tmp/opencode/journey-today.json >/dev/null || fail "today does not show the calendar event"
step "5. apply → on Today   (+$(( $(start_ms) - T )) ms)"

# ── Reject writes nothing ─────────────────────────────────────────────────────────────────
T=$(start_ms)
api POST /v1/plan "$REQUEST" /tmp/opencode/journey-plan2.json
RUN2=$(jq -r .runId /tmp/opencode/journey-plan2.json)
api POST "/v1/plan/$RUN2/reject" - /tmp/opencode/journey-reject.json
api GET /v1/today - /tmp/opencode/journey-today2.json
jq -e --argjson before "$(jq '.blocks | length' /tmp/opencode/journey-today.json)" \
  '.blocks | length == $before' /tmp/opencode/journey-today2.json >/dev/null \
  || fail "a rejected proposal changed the schedule"
step "6. reject → nothing   (+$(( $(start_ms) - T )) ms)"

# ── Undo restores exactly ─────────────────────────────────────────────────────────────────
T=$(start_ms)
api POST "/v1/plan/$ENTRY_ID/undo" - /tmp/opencode/journey-undo.json
jq -e '.restored >= 2' /tmp/opencode/journey-undo.json >/dev/null || fail "undo restored too few blocks"
api GET /v1/today - /tmp/opencode/journey-today3.json
jq -e '.blocks | length == 0' /tmp/opencode/journey-today3.json >/dev/null || fail "undo did not remove the blocks"
api POST "/v1/plan/$ENTRY_ID/undo" - /tmp/opencode/journey-undo2.json \
  && fail "double-undo must be refused (conflict), not succeed"
step "7. undo → restored    (+$(( $(start_ms) - T )) ms)"

TOTAL=$(( $(start_ms) - T0 ))
echo "== journey complete in ${TOTAL} ms"
if [ "$TOTAL" -gt 90000 ]; then
  fail "the journey blew the 90-second budget (${TOTAL} ms)"
fi