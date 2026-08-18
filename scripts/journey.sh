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

# ── Projects (Stage 4.2): a second project with its own board and task, then a plan
# whose items span both projects.
T=$(start_ms)
api POST /v1/projects '{"v":1,"name":"Client A"}' /tmp/opencode/journey-project.json
PROJECT_ID=$(jq -r .id /tmp/opencode/journey-project.json)
[ -n "$PROJECT_ID" ] && [ "$PROJECT_ID" != "null" ] || fail "project create returned no id"
api GET /v1/projects - /tmp/opencode/journey-projects.json
jq -e --arg id "$PROJECT_ID" '[.[] | select(.id == $id)] | length == 1' /tmp/opencode/journey-projects.json >/dev/null || fail "created project is not listed"
api GET "/v1/projects/$PROJECT_ID" - /tmp/opencode/journey-board.json
jq -e '[.stages[].name] == ["To Do", "In Progress", "Done"]' /tmp/opencode/journey-board.json >/dev/null || fail "board lacks the default stages: $(jq -c '[.stages[].name]' /tmp/opencode/journey-board.json)"
api POST "/v1/projects/$PROJECT_ID/tasks" \
  '{"id":"c1","boardId":"'"$PROJECT_ID"'","columnId":"todo","title":"Client kickoff","rank":0,"effortMinutes":120}' \
  /tmp/opencode/journey-client-task.json
api GET "/v1/projects/$PROJECT_ID" - /tmp/opencode/journey-board2.json
jq -e '[.tasks[].title] | index("Client kickoff") != null' /tmp/opencode/journey-board2.json >/dev/null || fail "board does not show the new task"
step "2b. projects + board   (+$(( $(start_ms) - T )) ms)"

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
    blocks:[], dependencies:[{id:"d1", predecessorId:"t2", successorId:"t3", type:"finish_to_start", lagMinutes:15}],
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
# Stage 4.3: "Review the deck" waits for "Prep the slides" plus 15 min of lag.
T2_END=$(jq -r '[.result.proposals[] | select(.itemId == "t2")][0].endAt' /tmp/opencode/journey-plan.json)
[ -n "$T2_END" ] && [ "$T2_END" != "null" ] || fail "dependency predecessor t2 was not placed"
jq -e --argjson bound $(( T2_END + 15*60000 )) \
  '[.result.proposals[] | select(.itemId == "t3")][0].startAt >= $bound' /tmp/opencode/journey-plan.json >/dev/null \
  || fail "t3 did not wait 15 min after t2 finished"
jq -e '[.result.proposals[] | select(.itemId == "t3")][0].reason | contains("Prep the slides finishes")' \
  /tmp/opencode/journey-plan.json >/dev/null || fail "t3's reason does not name its predecessor"
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

# ── Scenarios: the same week three ways, computed by the server, written nowhere (Stage 4.4).
T=$(start_ms)
SCENARIOS=$(jq -cn --argjson plan "$REQUEST" '{v:1, plan:$plan}')
api POST /v1/scenarios "$SCENARIOS" /tmp/opencode/journey-scenarios.json
jq -e '.scenarios | length == 3' /tmp/opencode/journey-scenarios.json >/dev/null \
  || fail "expected 3 default scenarios, got $(jq '.scenarios | length' /tmp/opencode/journey-scenarios.json)"
jq -e '[.scenarios[].key] == ["due", "priority", "short"]' /tmp/opencode/journey-scenarios.json >/dev/null \
  || fail "scenario keys not the default three: $(jq -c '[.scenarios[].key]' /tmp/opencode/journey-scenarios.json)"
jq -e '.scenarios[0].result.proposals | length >= 2' /tmp/opencode/journey-scenarios.json >/dev/null \
  || fail "scenario results carry no proposals"
jq -e '.spread | length > 0' /tmp/opencode/journey-scenarios.json >/dev/null || fail "scenarios carry no spread sentence"
# Analysis writes nothing: a scenario result carries no runId to apply or reject.
jq -e '.scenarios[0].result | has("runId") | not' /tmp/opencode/journey-scenarios.json >/dev/null \
  || fail "a scenario result leaked a run id"
step "4c. scenarios          (+$(( $(start_ms) - T )) ms)"

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

# ── Baseline + portfolio: take a baseline of the empty week, plan and apply again, and the
# variance must report the work that appeared while the portfolio counts it (Stage 4.4).
T=$(start_ms)
api POST /v1/baselines - /tmp/opencode/journey-baseline.json
BASELINE_ID=$(jq -r .id /tmp/opencode/journey-baseline.json)
[ -n "$BASELINE_ID" ] && [ "$BASELINE_ID" != "null" ] || fail "baseline create returned no id"
api GET /v1/baselines - /tmp/opencode/journey-baselines.json
jq -e --arg id "$BASELINE_ID" '[.[] | select(.id == $id)] | length == 1' /tmp/opencode/journey-baselines.json >/dev/null \
  || fail "created baseline is not listed"
api POST /v1/plan "$REQUEST" /tmp/opencode/journey-plan3.json
RUN3=$(jq -r .runId /tmp/opencode/journey-plan3.json)
api POST "/v1/plan/$RUN3/apply" - /tmp/opencode/journey-apply3.json
api GET "/v1/baselines/$BASELINE_ID/variance" - /tmp/opencode/journey-variance.json
jq -e '.rows | length >= 2' /tmp/opencode/journey-variance.json >/dev/null \
  || fail "variance does not see the applied work: $(jq -c '.summary' /tmp/opencode/journey-variance.json)"
jq -e '.rows[0].addedSinceBaseline == true' /tmp/opencode/journey-variance.json >/dev/null \
  || fail "applied work is not reported as added since baseline"
api GET /v1/portfolio - /tmp/opencode/journey-portfolio.json
jq -e '.rows | length >= 1' /tmp/opencode/journey-portfolio.json >/dev/null \
  || fail "portfolio has no rows"
jq -e '[.rows[].weekBlocks[]] | length >= 2' /tmp/opencode/journey-portfolio.json >/dev/null \
  || fail "portfolio Gantt sees none of the applied blocks"
jq -e '.note | length > 0' /tmp/opencode/journey-portfolio.json >/dev/null || fail "portfolio carries no note"
step "8. baseline + portfolio (+$(( $(start_ms) - T )) ms)"

# ── Billing: the tier boundary from 02, on a fresh trial workspace (Stage 4.5). The main
# journey workspace is paid so the loop's paid surfaces (capacity, baselines, a second
# project) run first; this leg exercises the free limits and the webhook upgrade.
T=$(start_ms)
api POST /v1/fixture/trial-workspace - /tmp/opencode/journey-trial-session.json
TRIAL_TOKEN=$(jq -r .token /tmp/opencode/journey-trial-session.json)
TRIAL_WS=$(jq -r .workspaceId /tmp/opencode/journey-trial-session.json)
[ -n "$TRIAL_TOKEN" ] && [ "$TRIAL_TOKEN" != "null" ] || fail "trial workspace signup returned no session"
api_post() { # api_post PATH BODY FILE [TOKEN]
  curl -sS -o "$3" -w '%{http_code}' -X POST "http://localhost:8090$1" \
    -H "Authorization: Bearer ${4:-$TRIAL_TOKEN}" -H 'Content-Type: application/json' \
    -d "${2:-}" > /tmp/opencode/journey-status.txt
}
api_get_trial() { # api_get_trial PATH FILE
  curl -sS -o "$2" -w '%{http_code}' -X GET "http://localhost:8090$1" \
    -H "Authorization: Bearer $TRIAL_TOKEN" > /tmp/opencode/journey-status.txt
}
api_get_trial /v1/billing /tmp/opencode/journey-billing.json
jq -e '.tier == "free" and .status == "trial"' /tmp/opencode/journey-billing.json >/dev/null \
  || fail "trial workspace is not on the free tier: $(jq -c '{tier,status}' /tmp/opencode/journey-billing.json)"
jq -e '.limits.maxProjects == 1 and .limits.baselines == false and .limits.capacity == false' /tmp/opencode/journey-billing.json >/dev/null \
  || fail "trial limits are not the free ones: $(jq -c '.limits' /tmp/opencode/journey-billing.json)"
jq -e '.usage.projects == 0' /tmp/opencode/journey-billing.json >/dev/null || fail "trial workspace should start with 0 projects"
jq -e '.trialEndsAt != null and .checkoutUrl != null' /tmp/opencode/journey-billing.json >/dev/null \
  || fail "trial lacks an end date or a checkout url"
# The free tier can hold exactly one project; the second is refused with 402.
api_post /v1/projects '{"v":1,"name":"Client A"}' /tmp/opencode/journey-trial-project1.json
PROJECT_CODE=$(cat /tmp/opencode/journey-status.txt)
[ "$PROJECT_CODE" = "200" ] || fail "the first project should fit the free tier (got $PROJECT_CODE)"
api_post /v1/projects '{"v":1,"name":"Client B"}' /tmp/opencode/journey-trial-project2.json
PROJECT_CODE=$(cat /tmp/opencode/journey-status.txt)
[ "$PROJECT_CODE" = "402" ] || fail "a second project should be refused with 402 (got $PROJECT_CODE)"
# Capacity and baselines are paid surfaces: 402 while free.
api_post /v1/capacity "{}" /tmp/opencode/journey-trial-capacity.json
[ "$(cat /tmp/opencode/journey-status.txt)" = "402" ] || fail "capacity should be refused with 402 while free"
api_post /v1/baselines "{}" /tmp/opencode/journey-trial-baseline.json
[ "$(cat /tmp/opencode/journey-status.txt)" = "402" ] || fail "baselines should be refused with 402 while free"
# The checkout opens, and the webhook (signature-checked) turns the trial paid.
api_post /v1/billing/checkout "{}" /tmp/opencode/journey-checkout.json
jq -e '.url | length > 0' /tmp/opencode/journey-checkout.json >/dev/null || fail "checkout returned no url"
CHECKOUT_URL=$(jq -r .url /tmp/opencode/journey-checkout.json)
WEBHOOK_PAYLOAD="{\"type\":\"checkout.completed\",\"url\":\"$CHECKOUT_URL\",\"client_reference_id\":\"$TRIAL_WS\"}"
curl -sS -o /dev/null -w '%{http_code}' -X POST "http://localhost:8090/v1/billing/webhook" \
  -H 'Content-Type: application/json' -H 'X-DailyBrief-Signature: fixture-billing-secret' \
  -d "$WEBHOOK_PAYLOAD" > /tmp/opencode/journey-status.txt
[ "$(cat /tmp/opencode/journey-status.txt)" = "204" ] || fail "webhook was not accepted"
curl -sS -o /dev/null -w '%{http_code}' -X POST "http://localhost:8090/v1/billing/webhook" \
  -H 'Content-Type: application/json' -H 'X-DailyBrief-Signature: wrong-secret' \
  -d "$WEBHOOK_PAYLOAD" > /tmp/opencode/journey-status.txt
[ "$(cat /tmp/opencode/journey-status.txt)" = "204" ] || fail "webhook with a wrong signature was not ignored"
api_get_trial /v1/billing /tmp/opencode/journey-billing2.json
jq -e '.tier == "paid" and .status == "active"' /tmp/opencode/journey-billing2.json >/dev/null \
  || fail "the webhook did not upgrade the workspace: $(jq -c '{tier,status}' /tmp/opencode/journey-billing2.json)"
jq -e '.limits.maxProjects > 1 and .limits.baselines == true and .limits.capacity == true' /tmp/opencode/journey-billing2.json >/dev/null \
  || fail "paid limits did not open: $(jq -c '.limits' /tmp/opencode/journey-billing2.json)"
api_post /v1/projects '{"v":1,"name":"Client B"}' /tmp/opencode/journey-trial-project3.json
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "a second project should fit after upgrade"
api_post /v1/baselines "{}" /tmp/opencode/journey-trial-baseline2.json
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "baselines should open after upgrade"
step "9. billing boundary    (+$(( $(start_ms) - T )) ms)"

# Step 10: the daily brief — platform key on the server, per-tenant quota in the ledger.
# The fixture provider writes a deterministic brief; the limit (3) comes from quota_json's
# default. Three requests fit, the fourth is refused with 429, and the refusal rolls back
# the reservation (the ledger never counts a request that did not run).
api_post /v1/summary '{}' /tmp/opencode/journey-summary1.json "$TOKEN"
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "the daily brief should answer 200"
jq -e '.text | length > 20' /tmp/opencode/journey-summary1.json >/dev/null || fail "the brief is empty"
jq -e '.used == 1 and .limit == 3 and .source == "fixture"' /tmp/opencode/journey-summary1.json >/dev/null \
  || fail "first brief quota wrong: $(jq -c '{used,limit,source}' /tmp/opencode/journey-summary1.json)"
api_post /v1/summary '{}' /tmp/opencode/journey-summary2.json "$TOKEN"
jq -e '.used == 2' /tmp/opencode/journey-summary2.json >/dev/null || fail "second brief did not count"
api_post /v1/summary '{}' /tmp/opencode/journey-summary3.json "$TOKEN"
jq -e '.used == 3' /tmp/opencode/journey-summary3.json >/dev/null || fail "third brief did not count"
api_post /v1/summary '{}' /tmp/opencode/journey-summary4.json "$TOKEN"
[ "$(cat /tmp/opencode/journey-status.txt)" = "429" ] || fail "the fourth brief should be refused with 429"
# The trial workspace's ledger is its own: one request fits there.
api_post /v1/summary '{}' /tmp/opencode/journey-trial-summary.json "$TRIAL_TOKEN"
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "the trial brief should answer 200"
jq -e '.used == 1 and .limit == 3' /tmp/opencode/journey-trial-summary.json >/dev/null \
  || fail "trial quota wrong: $(jq -c '{used,limit}' /tmp/opencode/journey-trial-summary.json)"
step "10. daily brief        (+$(( $(start_ms) - T )) ms)"

# Step 11: push. The registry is tenant-bound: tokens A and B belong to the main workspace,
# C to the trial workspace. B is deleted before the apply. Applying a fresh plan must
# deliver exactly one notification — to A — with the right payload, and never to B or C.
api_post /v1/devices '{"token":"journey-token-a","platform":"expo"}' /tmp/opencode/journey-device-a.json "$TOKEN"
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "device registration should answer 200"
api_post /v1/devices '{"token":"journey-token-b","platform":"expo"}' /tmp/opencode/journey-device-b.json "$TOKEN"
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "second device registration should answer 200"
curl -sS -o /dev/null -w '%{http_code}' -X DELETE "http://localhost:8090/v1/devices" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"token":"journey-token-b"}' > /tmp/opencode/journey-status.txt
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "device deregistration should answer 200"
api_post /v1/devices '{"token":"trial-token-c","platform":"expo"}' /tmp/opencode/journey-trial-device.json "$TRIAL_TOKEN"
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "trial device registration should answer 200"
# A fresh run on the main workspace; applying it is what triggers delivery. The request is
# the step-4 shape (same items, same windows) — the server is stateless about items.
PUSH_PLAN_REQUEST=$(jq -cn \
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
    blocks:[], dependencies:[], fixedCommitments:[], scheduleIdByTaskId:{}, preferredOrder:[],
    schedules:[{id:"s1", name:"Weekdays", timeZoneId:"UTC", isDefault:true, minimumChunkMinutes:30, maximumChunkMinutes:120, bufferMinutes:0, rank:0, windows:$windows}],
    deadlinePolicy:"HARD"}')
api_post /v1/plan "$PUSH_PLAN_REQUEST" /tmp/opencode/journey-push-plan.json "$TOKEN"
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "push leg: plan should answer 200"
PUSH_RUN=$(jq -r .runId /tmp/opencode/journey-push-plan.json)
[ -n "$PUSH_RUN" ] && [ "$PUSH_RUN" != "null" ] || fail "push leg: the plan run did not produce a runId"
api_post "/v1/plan/$PUSH_RUN/apply" '{}' /tmp/opencode/journey-push-apply.json "$TOKEN"
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "push leg: apply should answer 200"
PUSH_ENTRY=$(jq -r .entryId /tmp/opencode/journey-push-apply.json)
[ -n "$PUSH_ENTRY" ] || fail "push leg: apply returned no entryId"
curl -sS -o /tmp/opencode/journey-push-deliveries.json -w '%{http_code}' \
  "http://localhost:8090/v1/fixture/push-deliveries" -H "Authorization: Bearer $TOKEN" \
  > /tmp/opencode/journey-status.txt
[ "$(cat /tmp/opencode/journey-status.txt)" = "200" ] || fail "push deliveries should answer 200"
jq -e '.deliveries | length == 1' /tmp/opencode/journey-push-deliveries.json >/dev/null \
  || fail "expected exactly one delivery, got: $(jq -c '.deliveries | map(.token)' /tmp/opencode/journey-push-deliveries.json)"
jq -e '.deliveries[0].token == "journey-token-a"' /tmp/opencode/journey-push-deliveries.json >/dev/null \
  || fail "the delivery went to the wrong token"
jq -e --arg e "$PUSH_ENTRY" '.deliveries[0].data.entryId == $e' /tmp/opencode/journey-push-deliveries.json >/dev/null \
  || fail "the delivery does not name the applied entry: $(jq -c '.deliveries[0]' /tmp/opencode/journey-push-deliveries.json)"
jq -e '.deliveries[0].title == "Your plan was applied"' /tmp/opencode/journey-push-deliveries.json >/dev/null \
  || fail "the delivery has the wrong title"
# The deleted token B and the trial workspace's token C must never have been delivered to.
jq -e '.deliveries | map(.token) | index("journey-token-b") == null and index("trial-token-c") == null' /tmp/opencode/journey-push-deliveries.json >/dev/null \
  || fail "a delivery leaked to a deregistered or foreign token"
step "11. push               (+$(( $(start_ms) - T )) ms)"

TOTAL=$(( $(start_ms) - T0 ))
echo "== journey complete in ${TOTAL} ms"
if [ "$TOTAL" -gt 90000 ]; then
  fail "the journey blew the 90-second budget (${TOTAL} ms)"
fi