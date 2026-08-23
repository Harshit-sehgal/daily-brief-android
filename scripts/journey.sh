#!/usr/bin/env bash
# The WP-13 acceptance test, automated: the whole loop — sign in → connect a calendar →
# add tasks → Plan my week → proposal with reasons → apply → Today — headless against a
# fixture provider and a real Postgres, with each step timed. The 90-second budget is the
# slice's acceptance criterion, not a metaphor: the fixture leg must stay well under it.
#
# The manual leg — the same journey on a real Google account with the stopwatch — is the
# runbook in docs/saas/10-wp13-journey-runbook.md.
# JSON responses and server/probe logs are kept under build/journey-evidence by default; set
# DAILYBRIEF_JOURNEY_DIR to place them elsewhere (CI uploads the default directory).
set -euo pipefail

CONTAINER=dailybrief-journey
PORT_DB=54331
PORT_API=8090
DB_URL="jdbc:postgresql://localhost:$PORT_DB/dailybrief"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JOURNEY_RUN_DIR="${DAILYBRIEF_JOURNEY_DIR:-$ROOT/build/journey-evidence}"
mkdir -p "$JOURNEY_RUN_DIR"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE_ARGS=(--offline)
if [ "${DAILYBRIEF_GRADLE_ONLINE:-0}" = "1" ]; then
  GRADLE_ARGS=()
fi

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
# Rebuild the distribution from the current source. After a monorepo move, Gradle can
# consider an old installDist directory up to date even though its launcher JAR predates
# the current server entrypoint; that produces a launcher which names MainKt but cannot load it.
(cd "$ROOT" && ./gradlew "${GRADLE_ARGS[@]}" :server:installDist --rerun-tasks >/dev/null 2>&1)
ENVELOPE_KEY=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
DATABASE_URL="$DB_URL" DATABASE_USER=postgres DATABASE_PASSWORD=postgres \
  SESSION_SECRET="journey-session-secret-0123456789abcdef" \
  ENVELOPE_KEY_HEX="$ENVELOPE_KEY" FIXTURE_PROVIDER=1 PORT="$PORT_API" \
  "$ROOT/services/server/build/install/server/bin/server" >"${JOURNEY_RUN_DIR}"/journey-server.log 2>&1 &
SERVER_PID=$!
BROWSER_COOKIE_JAR=$(mktemp)
trap 'rm -f "$BROWSER_COOKIE_JAR"; kill "$SERVER_PID" 2>/dev/null || true; docker stop "$CONTAINER" >/dev/null 2>&1 || true' EXIT

T0=$(start_ms)
for i in $(seq 1 120); do
  if curl -sf -X POST "http://localhost:$PORT_API/v1/fixture/signup" -o "${JOURNEY_RUN_DIR}"/journey-signup.json 2>/dev/null; then break; fi
  [ "$i" = 120 ] && fail "server did not come up; log: $(tail -5 "${JOURNEY_RUN_DIR}"/journey-signup.json 2>/dev/null || tail -5 "${JOURNEY_RUN_DIR}"/journey-server.log)"
  sleep 0.5
done
curl -sf "http://localhost:$PORT_API/health/live" | jq -e '.status == "ok"' >/dev/null \
  || fail "liveness endpoint did not report ok"
curl -sf "http://localhost:$PORT_API/health/ready" | jq -e '.status == "ready"' >/dev/null \
  || fail "readiness endpoint did not report ready"
API_BASE="http://localhost:$PORT_API" WEB_ORIGIN="http://localhost:3000" \
  "$ROOT/scripts/production-probe.sh" >"${JOURNEY_RUN_DIR}"/journey-production-probe.log \
  || fail "production boundary probe failed: $(cat "${JOURNEY_RUN_DIR}"/journey-production-probe.log)"
TOKEN=$(jq -r .token "${JOURNEY_RUN_DIR}"/journey-signup.json)
WS=$(jq -r .workspaceId "${JOURNEY_RUN_DIR}"/journey-signup.json)
step "1. signup → session   (+$(( $(start_ms) - T0 )) ms)"

# The web client must use the HttpOnly cookie path. Keep the cookie jar temporary so a CI
# artifact can never contain a reusable seven-day browser credential.
curl -sf -c "$BROWSER_COOKIE_JAR" -X POST "http://localhost:$PORT_API/v1/fixture/browser-signup" \
  -o "${JOURNEY_RUN_DIR}"/journey-browser-signup.json
BROWSER_WS=$(jq -r .workspaceId "${JOURNEY_RUN_DIR}"/journey-browser-signup.json)
BROWSER_CSRF=$(jq -r .csrfToken "${JOURNEY_RUN_DIR}"/journey-browser-signup.json)
[ "$BROWSER_WS" = "$WS" ] || fail "browser signup returned a different workspace"
[ -n "$BROWSER_CSRF" ] && [ "$BROWSER_CSRF" != "null" ] || fail "browser signup returned no CSRF token"
curl -sf -b "$BROWSER_COOKIE_JAR" "http://localhost:$PORT_API/v1/projects" \
  -o "${JOURNEY_RUN_DIR}"/journey-browser-projects.json
jq -e --arg ws "$WS" 'length > 0 and all(.[]; .workspaceId == $ws)' \
  "${JOURNEY_RUN_DIR}"/journey-browser-projects.json >/dev/null \
  || fail "HttpOnly browser session could not read workspace projects"
curl -sf -b "$BROWSER_COOKIE_JAR" -H "X-DailyBrief-CSRF: $BROWSER_CSRF" \
  -H 'Content-Type: application/json' -X POST "http://localhost:$PORT_API/v1/projects" \
  -d '{"v":1,"name":"Browser Session Probe"}' \
  -o "${JOURNEY_RUN_DIR}"/journey-browser-mutation.json
jq -e '.name == "Browser Session Probe" and .workspaceId == $ws' --arg ws "$WS" \
  "${JOURNEY_RUN_DIR}"/journey-browser-mutation.json >/dev/null \
  || fail "CSRF-protected browser mutation did not persist"
CSRF_STATUS=$(curl -sS -b "$BROWSER_COOKIE_JAR" -H 'Content-Type: application/json' \
  -X POST "http://localhost:$PORT_API/v1/projects" -d '{"v":1,"name":"Missing CSRF Probe"}' \
  -o /dev/null -w '%{http_code}')
[ "$CSRF_STATUS" = "401" ] || fail "cookie mutation without CSRF was not refused (got $CSRF_STATUS)"
step "1a. browser cookie session   (+$(( $(start_ms) - T0 )) ms)"

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
api GET /v1/settings/planning - "${JOURNEY_RUN_DIR}"/journey-schedule.json
jq -e '.isDefault == true and .timeZoneId == "UTC" and ([.windows[] | .dayOfWeek] | sort) == [2,3,4,5,6]' \
  "${JOURNEY_RUN_DIR}"/journey-schedule.json >/dev/null \
  || fail "workspace default schedule is not the explicit Monday-Friday UTC schedule"
# The acceptance must hold on any day of the week. On a Saturday or Sunday the Mon-Fri
# default makes the planner (correctly) push every block to Monday, so /v1/today would
# show nothing and the apply→Today leg would fail for calendar reasons rather than
# product ones. Re-day the five weekly windows onto today and the next four days — the
# stored convention is Calendar numbering, Sunday=1..Saturday=7.
TODAY_ISO_DOW=$(date -u +%u)   # 1=Monday..7=Sunday
DAYS=$(for k in 0 1 2 3 4; do echo $(( ((TODAY_ISO_DOW - 1 + k) % 7 + 1) % 7 + 1 )); done | jq -s .)
SCHEDULE_UPDATE=$(jq -c --argjson days "$DAYS" '
  .timeZoneId = "Asia/Kolkata"
  | .windows = ((.windows | sort_by(.rank)) | to_entries
      | map(.value.dayOfWeek = $days[.key] | .value))' \
  "${JOURNEY_RUN_DIR}"/journey-schedule.json)
api PUT /v1/settings/planning "$SCHEDULE_UPDATE" "${JOURNEY_RUN_DIR}"/journey-schedule-updated.json
jq -e '(.timeZoneId == "Asia/Kolkata" and (.windows | length == 5))' "${JOURNEY_RUN_DIR}"/journey-schedule-updated.json >/dev/null \
  || fail "working-hours update did not persist the timezone and windows"
api GET /v1/settings/planning - "${JOURNEY_RUN_DIR}"/journey-schedule-roundtrip.json
jq -e '.timeZoneId == "Asia/Kolkata"' "${JOURNEY_RUN_DIR}"/journey-schedule-roundtrip.json >/dev/null \
  || fail "working-hours GET did not return the persisted timezone"
SCHEDULE=$(cat "${JOURNEY_RUN_DIR}"/journey-schedule-roundtrip.json)
step "1b. working hours   (+$(( $(start_ms) - T )) ms)"

T=$(start_ms)
NOW=$(( $(date -u +%s%3N) / 86400000 * 86400000 ))   # today 00:00 UTC
HOUR=3600000
TASK1='{"id":"t1","boardId":"b1","title":"Write the proposal","rank":0,"effortMinutes":90,"dueAt":'$((NOW + 17*HOUR))'}'
TASK2='{"id":"t2","boardId":"b1","title":"Prep the slides","rank":1,"effortMinutes":45}'
TASK3='{"id":"t3","boardId":"b1","title":"Review the deck","rank":2,"effortMinutes":60,"dueAt":'$((NOW + 40*HOUR))'}'
api POST /v1/tasks "$TASK1" "${JOURNEY_RUN_DIR}"/journey-t1.json
api POST /v1/tasks "$TASK2" "${JOURNEY_RUN_DIR}"/journey-t2.json
api POST /v1/tasks "$TASK3" "${JOURNEY_RUN_DIR}"/journey-t3.json
api GET /v1/projects - "${JOURNEY_RUN_DIR}"/journey-default-projects.json
DEFAULT_PROJECT_ID=$(jq -r '[.[] | select(.isDefault == true)][0].id' "${JOURNEY_RUN_DIR}"/journey-default-projects.json)
[ -n "$DEFAULT_PROJECT_ID" ] && [ "$DEFAULT_PROJECT_ID" != "null" ] || fail "workspace has no default project for Inbox capture"
api GET "/v1/projects/$DEFAULT_PROJECT_ID" - "${JOURNEY_RUN_DIR}"/journey-inbox-board.json
jq -e '[.tasks[] | select(.id == "t1" or .id == "t2" or .id == "t3")] | length == 3 and all(.[]; .columnId == null)' \
  "${JOURNEY_RUN_DIR}"/journey-inbox-board.json >/dev/null \
  || fail "captured tasks did not remain in the Inbox without a workflow stage"
step "2. add three tasks   (+$(( $(start_ms) - T )) ms)"

# ── Projects (Stage 4.2): a second project with its own board and task, then a plan
# whose items span both projects.
T=$(start_ms)
api POST /v1/projects '{"v":1,"name":"Client A"}' "${JOURNEY_RUN_DIR}"/journey-project.json
PROJECT_ID=$(jq -r .id "${JOURNEY_RUN_DIR}"/journey-project.json)
[ -n "$PROJECT_ID" ] && [ "$PROJECT_ID" != "null" ] || fail "project create returned no id"
api GET /v1/projects - "${JOURNEY_RUN_DIR}"/journey-projects.json
jq -e --arg id "$PROJECT_ID" '[.[] | select(.id == $id)] | length == 1' "${JOURNEY_RUN_DIR}"/journey-projects.json >/dev/null || fail "created project is not listed"
api GET "/v1/projects/$PROJECT_ID" - "${JOURNEY_RUN_DIR}"/journey-board.json
jq -e '[.stages[].name] == ["To Do", "In Progress", "Done"]' "${JOURNEY_RUN_DIR}"/journey-board.json >/dev/null || fail "board lacks the default stages: $(jq -c '[.stages[].name]' "${JOURNEY_RUN_DIR}"/journey-board.json)"
api POST "/v1/projects/$PROJECT_ID/tasks" \
  '{"id":"c1","boardId":"'"$PROJECT_ID"'","columnId":"todo","title":"Client kickoff","rank":0,"effortMinutes":120}' \
  "${JOURNEY_RUN_DIR}"/journey-client-task.json
api POST "/v1/projects/$PROJECT_ID/tasks" \
  '{"id":"c2","boardId":"'"$PROJECT_ID"'","columnId":null,"title":"Client follow-up","rank":1,"effortMinutes":45}' \
  "${JOURNEY_RUN_DIR}"/journey-client-inbox-task.json
api GET "/v1/projects/$PROJECT_ID" - "${JOURNEY_RUN_DIR}"/journey-board2.json
jq -e '[.tasks[].title] | index("Client kickoff") != null' "${JOURNEY_RUN_DIR}"/journey-board2.json >/dev/null || fail "board does not show the new task"
api GET /v1/tasks - "${JOURNEY_RUN_DIR}"/journey-workspace-tasks.json
jq -e --arg project "$PROJECT_ID" '
  ([.[] | select(.id == "t1" or .id == "t2" or .id == "t3" or .id == "c1" or .id == "c2")] | length == 5) and
  ([.[] | select(.id == "c1")][0].columnId == "todo") and
  ([.[] | select(.id == "t1" or .id == "t2" or .id == "t3" or .id == "c2")] | all(.[]; .columnId == null)) and
  ([.[] | select(.id == "c2")][0].boardId == $project)
' "${JOURNEY_RUN_DIR}"/journey-workspace-tasks.json >/dev/null \
  || fail "workspace task listing lost project or Inbox stage information"
step "2b. projects + board   (+$(( $(start_ms) - T )) ms)"

T=$(start_ms)
api POST /v1/reconcile - "${JOURNEY_RUN_DIR}"/journey-reconcile.json
# WP-14: the sync ledger records the run and the fixture events it merged.
api GET /v1/fixture/sync-runs - "${JOURNEY_RUN_DIR}"/journey-sync-runs.json
jq -e '.[0].status == "ok" and .[0].eventsSeen >= 2 and .[0].durationMs >= 0' "${JOURNEY_RUN_DIR}"/journey-sync-runs.json >/dev/null \
  || fail "sync ledger missing the committed reconcile: $(jq -c . "${JOURNEY_RUN_DIR}"/journey-sync-runs.json)"
step "3. connect calendar   (+$(( $(start_ms) - T )) ms)"

# ── Plan: the request composes the calendar's busy time into fixedCommitments, exactly as
# the web client will. The stand-up is today 14:00-15:00.
T=$(start_ms)
REQUEST=$(jq -cn \
  --argjson now "$NOW" \
  --arg ws "$WS" \
  --arg projectId "$PROJECT_ID" \
  --argjson schedule "$SCHEDULE" \
  '{v:1, workspaceId:$ws,
    rangeStartMs:$now, rangeEndMs:($now+7*86400000), nowMs:$now,
    items:[
      {id:"t1", boardId:"b1", title:"Write the proposal", rank:0, effortMinutes:90, dueAt:($now+17*3600000)},
      {id:"t2", boardId:"b1", title:"Prep the slides", rank:1, effortMinutes:45},
      {id:"t3", boardId:"b1", title:"Review the deck", rank:2, effortMinutes:60, dueAt:($now+40*3600000)},
      {id:"c1", boardId:$projectId, title:"Client kickoff", rank:0, effortMinutes:120}
    ],
    blocks:[], dependencies:[{id:"d1", predecessorId:"t2", successorId:"t3", type:"finish_to_start", lagMinutes:15}],
    fixedCommitments:[{startAt:($now+14*3600000), endAt:($now+15*3600000)}],
    scheduleIdByTaskId:{}, preferredOrder:[],
    schedules:[$schedule],
    deadlinePolicy:"HARD"}')
api POST /v1/plan "$REQUEST" "${JOURNEY_RUN_DIR}"/journey-plan.json
jq -e '.result.proposals | length >= 2' "${JOURNEY_RUN_DIR}"/journey-plan.json >/dev/null || fail "expected >= 2 proposals, got $(jq '.result.proposals | length' "${JOURNEY_RUN_DIR}"/journey-plan.json)"
jq -e '.result.proposals[0].reason | length > 0' "${JOURNEY_RUN_DIR}"/journey-plan.json >/dev/null || fail "proposal carries no reason"
jq -e --argjson start $(( NOW + 14*HOUR )) --argjson end $(( NOW + 15*HOUR )) \
  '[.result.proposals[] | select(.startAt < $end and .endAt > $start)] | length == 0' "${JOURNEY_RUN_DIR}"/journey-plan.json >/dev/null \
  || fail "a proposal overlaps the stand-up"
RUN_ID=$(jq -r .runId "${JOURNEY_RUN_DIR}"/journey-plan.json)
# Stage 4.3: "Review the deck" waits for "Prep the slides" plus 15 min of lag.
T2_END=$(jq -r '[.result.proposals[] | select(.itemId == "t2")][0].endAt' "${JOURNEY_RUN_DIR}"/journey-plan.json)
[ -n "$T2_END" ] && [ "$T2_END" != "null" ] || fail "dependency predecessor t2 was not placed"
jq -e --argjson bound $(( T2_END + 15*60000 )) \
  '[.result.proposals[] | select(.itemId == "t3")][0].startAt >= $bound' "${JOURNEY_RUN_DIR}"/journey-plan.json >/dev/null \
  || fail "t3 did not wait 15 min after t2 finished"
jq -e '[.result.proposals[] | select(.itemId == "t3")][0].reason | contains("Prep the slides finishes")' \
  "${JOURNEY_RUN_DIR}"/journey-plan.json >/dev/null || fail "t3's reason does not name its predecessor"
step "4. plan my week       (+$(( $(start_ms) - T )) ms)"

# ── Capacity: the same request asks "can I take another client?" — three numbers, one
# sentence (Stage 4.1). The plan fits, so an 8 h/week client must fit too.
T=$(start_ms)
CAPACITY=$(jq -cn --argjson plan "$REQUEST" '{v:1, plan:$plan, newClientHoursPerWeek:8}')
api POST /v1/capacity "$CAPACITY" "${JOURNEY_RUN_DIR}"/journey-capacity.json
jq -e '.verdict == "CAN_TAKE"' "${JOURNEY_RUN_DIR}"/journey-capacity.json >/dev/null \
  || fail "expected CAN_TAKE, got $(jq -r .verdict "${JOURNEY_RUN_DIR}"/journey-capacity.json)"
jq -e '.availableMinutes > 0 and .spareMinutes >= 480' "${JOURNEY_RUN_DIR}"/journey-capacity.json >/dev/null \
  || fail "capacity numbers look wrong: $(jq -c . "${JOURNEY_RUN_DIR}"/journey-capacity.json)"
jq -e '.sentence | length > 0' "${JOURNEY_RUN_DIR}"/journey-capacity.json >/dev/null || fail "capacity carries no sentence"
step "4b. capacity          (+$(( $(start_ms) - T )) ms)"

# ── Scenarios: the same week three ways, computed by the server, written nowhere (Stage 4.4).
T=$(start_ms)
SCENARIOS=$(jq -cn --argjson plan "$REQUEST" '{v:1, plan:$plan}')
api POST /v1/scenarios "$SCENARIOS" "${JOURNEY_RUN_DIR}"/journey-scenarios.json
jq -e '.scenarios | length == 3' "${JOURNEY_RUN_DIR}"/journey-scenarios.json >/dev/null \
  || fail "expected 3 default scenarios, got $(jq '.scenarios | length' "${JOURNEY_RUN_DIR}"/journey-scenarios.json)"
jq -e '[.scenarios[].key] == ["due", "priority", "short"]' "${JOURNEY_RUN_DIR}"/journey-scenarios.json >/dev/null \
  || fail "scenario keys not the default three: $(jq -c '[.scenarios[].key]' "${JOURNEY_RUN_DIR}"/journey-scenarios.json)"
jq -e '.scenarios[0].result.proposals | length >= 2' "${JOURNEY_RUN_DIR}"/journey-scenarios.json >/dev/null \
  || fail "scenario results carry no proposals"
jq -e '.spread | length > 0' "${JOURNEY_RUN_DIR}"/journey-scenarios.json >/dev/null || fail "scenarios carry no spread sentence"
# Analysis writes nothing: a scenario result carries no runId to apply or reject.
jq -e '.scenarios[0].result | has("runId") | not' "${JOURNEY_RUN_DIR}"/journey-scenarios.json >/dev/null \
  || fail "a scenario result leaked a run id"
step "4c. scenarios          (+$(( $(start_ms) - T )) ms)"

# ── Apply → Today ─────────────────────────────────────────────────────────────────────────
T=$(start_ms)
api POST "/v1/plan/$RUN_ID/apply" - "${JOURNEY_RUN_DIR}"/journey-apply.json
jq -e '.blocks >= 2' "${JOURNEY_RUN_DIR}"/journey-apply.json >/dev/null || fail "apply wrote too few blocks"
ENTRY_ID=$(jq -r .entryId "${JOURNEY_RUN_DIR}"/journey-apply.json)
api GET /v1/today - "${JOURNEY_RUN_DIR}"/journey-today.json
jq -e '.blocks | length >= 2' "${JOURNEY_RUN_DIR}"/journey-today.json >/dev/null || fail "today does not show the applied blocks"
jq -e '.blocks | all(.[]; (.title | length) > 0 and (.projectName | length) > 0)' "${JOURNEY_RUN_DIR}"/journey-today.json >/dev/null \
  || fail "today plan blocks do not carry task and project labels"
jq -e '[.events[].title] | index("Client stand-up") != null' "${JOURNEY_RUN_DIR}"/journey-today.json >/dev/null || fail "today does not show the calendar event"
api GET /v1/portfolio - "${JOURNEY_RUN_DIR}"/journey-cross-project-portfolio.json
jq -e --arg project "$PROJECT_ID" '[.rows[] | select(.projectId == $project) | .weekBlocks[]] | length >= 1' \
  "${JOURNEY_RUN_DIR}"/journey-cross-project-portfolio.json >/dev/null \
  || fail "cross-project proposal was not stored under its own project"
# A second planning request includes the real applied block ids, as the web client does when the
# user asks to replan. A new fixed commitment occupies the first block's slot; the engine must
# propose the remaining work elsewhere, and Apply must replace the old blocks atomically.
api GET /v1/plan-blocks - "${JOURNEY_RUN_DIR}"/journey-plan-blocks.json
OLD_BLOCK_IDS=$(jq -c '[.[].id]' "${JOURNEY_RUN_DIR}"/journey-plan-blocks.json)
REPLAN_BLOCKS=$(jq -c '[.[] | {id, planItemId:.taskId, startAt, endAt, position, locked, linkedEventId}]' \
  "${JOURNEY_RUN_DIR}"/journey-plan-blocks.json)
BLOCK_START=$(jq -r '.[0].startAt' "${JOURNEY_RUN_DIR}"/journey-plan-blocks.json)
BLOCK_END=$(jq -r '.[0].endAt' "${JOURNEY_RUN_DIR}"/journey-plan-blocks.json)
REPLAN_REQUEST=$(jq -cn --argjson plan "$REQUEST" --argjson blocks "$REPLAN_BLOCKS" \
  --argjson start "$BLOCK_START" --argjson end "$BLOCK_END" \
  '$plan | .blocks = $blocks | .fixedCommitments += [{startAt:$start, endAt:$end}]')
api POST "/v1/plan?replan=1" "$REPLAN_REQUEST" "${JOURNEY_RUN_DIR}"/journey-replan.json
jq -e '.result.proposals | length >= 1' "${JOURNEY_RUN_DIR}"/journey-replan.json >/dev/null \
  || fail "replan did not propose work after the new fixed commitment"
REPLAN_RUN=$(jq -r .runId "${JOURNEY_RUN_DIR}"/journey-replan.json)
api POST "/v1/plan/$REPLAN_RUN/apply" - "${JOURNEY_RUN_DIR}"/journey-replan-apply.json
REPLAN_ENTRY_ID=$(jq -r .entryId "${JOURNEY_RUN_DIR}"/journey-replan-apply.json)
[ -n "$REPLAN_ENTRY_ID" ] && [ "$REPLAN_ENTRY_ID" != "null" ] || fail "replan apply returned no journal entry"
api GET /v1/plan-blocks - "${JOURNEY_RUN_DIR}"/journey-replanned-blocks.json
jq -e --argjson old "$OLD_BLOCK_IDS" '[.[] | select(.id as $id | $old | index($id))] | length == 0' \
  "${JOURNEY_RUN_DIR}"/journey-replanned-blocks.json >/dev/null \
  || fail "replan apply left an old block in place"
api POST "/v1/plan/$REPLAN_ENTRY_ID/undo" - "${JOURNEY_RUN_DIR}"/journey-replan-undo.json
jq -e '.restored >= 1' "${JOURNEY_RUN_DIR}"/journey-replan-undo.json >/dev/null || fail "replan undo restored too few blocks"
step "5. apply → on Today   (+$(( $(start_ms) - T )) ms)"

# ── Reject writes nothing ─────────────────────────────────────────────────────────────────
T=$(start_ms)
api POST /v1/plan "$REQUEST" "${JOURNEY_RUN_DIR}"/journey-plan2.json
RUN2=$(jq -r .runId "${JOURNEY_RUN_DIR}"/journey-plan2.json)
api POST "/v1/plan/$RUN2/reject" - "${JOURNEY_RUN_DIR}"/journey-reject.json
api GET /v1/today - "${JOURNEY_RUN_DIR}"/journey-today2.json
jq -e --argjson before "$(jq '.blocks | length' "${JOURNEY_RUN_DIR}"/journey-today.json)" \
  '.blocks | length == $before' "${JOURNEY_RUN_DIR}"/journey-today2.json >/dev/null \
  || fail "a rejected proposal changed the schedule"
step "6. reject → nothing   (+$(( $(start_ms) - T )) ms)"

# ── Undo restores exactly, including the original apply after a replacement replan ───────────
T=$(start_ms)
api POST "/v1/plan/$ENTRY_ID/undo" - "${JOURNEY_RUN_DIR}"/journey-undo.json
jq -e '.restored >= 2' "${JOURNEY_RUN_DIR}"/journey-undo.json >/dev/null || fail "undo restored too few blocks"
api GET /v1/today - "${JOURNEY_RUN_DIR}"/journey-today3.json
jq -e '.blocks | length == 0' "${JOURNEY_RUN_DIR}"/journey-today3.json >/dev/null || fail "undo did not remove the blocks"
api POST "/v1/plan/$ENTRY_ID/undo" - "${JOURNEY_RUN_DIR}"/journey-undo2.json \
  && fail "double-undo must be refused (conflict), not succeed"
step "7. undo → restored    (+$(( $(start_ms) - T )) ms)"

# ── Baseline + portfolio: take a baseline of the empty week, plan and apply again, and the
# variance must report the work that appeared while the portfolio counts it (Stage 4.4).
T=$(start_ms)
api POST /v1/baselines - "${JOURNEY_RUN_DIR}"/journey-baseline.json
BASELINE_ID=$(jq -r .id "${JOURNEY_RUN_DIR}"/journey-baseline.json)
[ -n "$BASELINE_ID" ] && [ "$BASELINE_ID" != "null" ] || fail "baseline create returned no id"
api GET /v1/baselines - "${JOURNEY_RUN_DIR}"/journey-baselines.json
jq -e --arg id "$BASELINE_ID" '[.[] | select(.id == $id)] | length == 1' "${JOURNEY_RUN_DIR}"/journey-baselines.json >/dev/null \
  || fail "created baseline is not listed"
api POST /v1/plan "$REQUEST" "${JOURNEY_RUN_DIR}"/journey-plan3.json
RUN3=$(jq -r .runId "${JOURNEY_RUN_DIR}"/journey-plan3.json)
api POST "/v1/plan/$RUN3/apply" - "${JOURNEY_RUN_DIR}"/journey-apply3.json
api GET "/v1/baselines/$BASELINE_ID/variance" - "${JOURNEY_RUN_DIR}"/journey-variance.json
jq -e '.rows | length >= 2' "${JOURNEY_RUN_DIR}"/journey-variance.json >/dev/null \
  || fail "variance does not see the applied work: $(jq -c '.summary' "${JOURNEY_RUN_DIR}"/journey-variance.json)"
jq -e '.rows[0].addedSinceBaseline == true' "${JOURNEY_RUN_DIR}"/journey-variance.json >/dev/null \
  || fail "applied work is not reported as added since baseline"
api GET /v1/portfolio - "${JOURNEY_RUN_DIR}"/journey-portfolio.json
jq -e '.rows | length >= 1' "${JOURNEY_RUN_DIR}"/journey-portfolio.json >/dev/null \
  || fail "portfolio has no rows"
jq -e '[.rows[].weekBlocks[]] | length >= 2' "${JOURNEY_RUN_DIR}"/journey-portfolio.json >/dev/null \
  || fail "portfolio Gantt sees none of the applied blocks"
jq -e '.note | length > 0' "${JOURNEY_RUN_DIR}"/journey-portfolio.json >/dev/null || fail "portfolio carries no note"
step "8. baseline + portfolio (+$(( $(start_ms) - T )) ms)"

# ── Billing: the tier boundary from 02, on a fresh trial workspace (Stage 4.5). The main
# journey workspace is paid so the loop's paid surfaces (capacity, baselines, a second
# project) run first; this leg exercises the free limits and the webhook upgrade.
T=$(start_ms)
api POST /v1/fixture/trial-workspace - "${JOURNEY_RUN_DIR}"/journey-trial-session.json
TRIAL_TOKEN=$(jq -r .token "${JOURNEY_RUN_DIR}"/journey-trial-session.json)
TRIAL_WS=$(jq -r .workspaceId "${JOURNEY_RUN_DIR}"/journey-trial-session.json)
[ -n "$TRIAL_TOKEN" ] && [ "$TRIAL_TOKEN" != "null" ] || fail "trial workspace signup returned no session"
api_post() { # api_post PATH BODY FILE [TOKEN]
  curl -sS -o "$3" -w '%{http_code}' -X POST "http://localhost:8090$1" \
    -H "Authorization: Bearer ${4:-$TRIAL_TOKEN}" -H 'Content-Type: application/json' \
    -d "${2:-}" > "${JOURNEY_RUN_DIR}"/journey-status.txt
}
api_get_trial() { # api_get_trial PATH FILE
  curl -sS -o "$2" -w '%{http_code}' -X GET "http://localhost:8090$1" \
    -H "Authorization: Bearer $TRIAL_TOKEN" > "${JOURNEY_RUN_DIR}"/journey-status.txt
}
api_get_trial /v1/billing "${JOURNEY_RUN_DIR}"/journey-billing.json
jq -e '.tier == "free" and .status == "trial"' "${JOURNEY_RUN_DIR}"/journey-billing.json >/dev/null \
  || fail "trial workspace is not on the free tier: $(jq -c '{tier,status}' "${JOURNEY_RUN_DIR}"/journey-billing.json)"
jq -e '.limits.maxProjects == 1 and .limits.baselines == false and .limits.capacity == false' "${JOURNEY_RUN_DIR}"/journey-billing.json >/dev/null \
  || fail "trial limits are not the free ones: $(jq -c '.limits' "${JOURNEY_RUN_DIR}"/journey-billing.json)"
jq -e '.usage.projects == 0' "${JOURNEY_RUN_DIR}"/journey-billing.json >/dev/null || fail "trial workspace should start with 0 projects"
jq -e '.trialEndsAt != null and .checkoutUrl != null' "${JOURNEY_RUN_DIR}"/journey-billing.json >/dev/null \
  || fail "trial lacks an end date or a checkout url"
# The free tier can hold exactly one project; the second is refused with 402.
api_post /v1/projects '{"v":1,"name":"Client A"}' "${JOURNEY_RUN_DIR}"/journey-trial-project1.json
PROJECT_CODE=$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)
[ "$PROJECT_CODE" = "200" ] || fail "the first project should fit the free tier (got $PROJECT_CODE)"
api_post /v1/projects '{"v":1,"name":"Client B"}' "${JOURNEY_RUN_DIR}"/journey-trial-project2.json
PROJECT_CODE=$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)
[ "$PROJECT_CODE" = "402" ] || fail "a second project should be refused with 402 (got $PROJECT_CODE)"
# Capacity and baselines are paid surfaces: 402 while free.
api_post /v1/capacity "{}" "${JOURNEY_RUN_DIR}"/journey-trial-capacity.json
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "402" ] || fail "capacity should be refused with 402 while free"
api_post /v1/baselines "{}" "${JOURNEY_RUN_DIR}"/journey-trial-baseline.json
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "402" ] || fail "baselines should be refused with 402 while free"
# The checkout opens, and the webhook (signature-checked) turns the trial paid.
api_post /v1/billing/checkout "{}" "${JOURNEY_RUN_DIR}"/journey-checkout.json
jq -e '.url | length > 0' "${JOURNEY_RUN_DIR}"/journey-checkout.json >/dev/null || fail "checkout returned no url"
CHECKOUT_URL=$(jq -r .url "${JOURNEY_RUN_DIR}"/journey-checkout.json)
WEBHOOK_PAYLOAD="{\"type\":\"checkout.completed\",\"url\":\"$CHECKOUT_URL\",\"client_reference_id\":\"$TRIAL_WS\"}"
curl -sS -o /dev/null -w '%{http_code}' -X POST "http://localhost:8090/v1/billing/webhook" \
  -H 'Content-Type: application/json' -H 'X-DailyBrief-Signature: fixture-billing-secret' \
  -d "$WEBHOOK_PAYLOAD" > "${JOURNEY_RUN_DIR}"/journey-status.txt
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "204" ] || fail "webhook was not accepted"
curl -sS -o /dev/null -w '%{http_code}' -X POST "http://localhost:8090/v1/billing/webhook" \
  -H 'Content-Type: application/json' -H 'X-DailyBrief-Signature: wrong-secret' \
  -d "$WEBHOOK_PAYLOAD" > "${JOURNEY_RUN_DIR}"/journey-status.txt
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "204" ] || fail "webhook with a wrong signature was not ignored"
api_get_trial /v1/billing "${JOURNEY_RUN_DIR}"/journey-billing2.json
jq -e '.tier == "paid" and .status == "active"' "${JOURNEY_RUN_DIR}"/journey-billing2.json >/dev/null \
  || fail "the webhook did not upgrade the workspace: $(jq -c '{tier,status}' "${JOURNEY_RUN_DIR}"/journey-billing2.json)"
jq -e '.limits.maxProjects > 1 and .limits.baselines == true and .limits.capacity == true' "${JOURNEY_RUN_DIR}"/journey-billing2.json >/dev/null \
  || fail "paid limits did not open: $(jq -c '.limits' "${JOURNEY_RUN_DIR}"/journey-billing2.json)"
api_post /v1/projects '{"v":1,"name":"Client B"}' "${JOURNEY_RUN_DIR}"/journey-trial-project3.json
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "a second project should fit after upgrade"
api_post /v1/baselines "{}" "${JOURNEY_RUN_DIR}"/journey-trial-baseline2.json
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "baselines should open after upgrade"
step "9. billing boundary    (+$(( $(start_ms) - T )) ms)"

# Step 10: the daily brief — platform key on the server, per-tenant quota in the ledger.
# The fixture provider writes a deterministic brief; the limit (3) comes from quota_json's
# default. Three requests fit, the fourth is refused with 429, and the refusal rolls back
# the reservation (the ledger never counts a request that did not run).
api_post /v1/summary '{}' "${JOURNEY_RUN_DIR}"/journey-summary1.json "$TOKEN"
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "the daily brief should answer 200"
jq -e '.text | length > 20' "${JOURNEY_RUN_DIR}"/journey-summary1.json >/dev/null || fail "the brief is empty"
jq -e '.used == 1 and .limit == 3 and .source == "fixture"' "${JOURNEY_RUN_DIR}"/journey-summary1.json >/dev/null \
  || fail "first brief quota wrong: $(jq -c '{used,limit,source}' "${JOURNEY_RUN_DIR}"/journey-summary1.json)"
api_post /v1/summary '{}' "${JOURNEY_RUN_DIR}"/journey-summary2.json "$TOKEN"
jq -e '.used == 2' "${JOURNEY_RUN_DIR}"/journey-summary2.json >/dev/null || fail "second brief did not count"
api_post /v1/summary '{}' "${JOURNEY_RUN_DIR}"/journey-summary3.json "$TOKEN"
jq -e '.used == 3' "${JOURNEY_RUN_DIR}"/journey-summary3.json >/dev/null || fail "third brief did not count"
api_post /v1/summary '{}' "${JOURNEY_RUN_DIR}"/journey-summary4.json "$TOKEN"
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "429" ] || fail "the fourth brief should be refused with 429"
# The trial workspace's ledger is its own: one request fits there.
api_post /v1/summary '{}' "${JOURNEY_RUN_DIR}"/journey-trial-summary.json "$TRIAL_TOKEN"
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "the trial brief should answer 200"
jq -e '.used == 1 and .limit == 3' "${JOURNEY_RUN_DIR}"/journey-trial-summary.json >/dev/null \
  || fail "trial quota wrong: $(jq -c '{used,limit}' "${JOURNEY_RUN_DIR}"/journey-trial-summary.json)"
step "10. daily brief        (+$(( $(start_ms) - T )) ms)"

# Step 11: push. The registry is tenant-bound: tokens A and B belong to the main workspace,
# C to the trial workspace. B is deleted before the apply. Applying a fresh plan must
# deliver exactly one notification — to A — with the right payload, and never to B or C.
api_post /v1/devices '{"token":"journey-token-a","platform":"expo"}' "${JOURNEY_RUN_DIR}"/journey-device-a.json "$TOKEN"
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "device registration should answer 200"
api_post /v1/devices '{"token":"journey-token-b","platform":"expo"}' "${JOURNEY_RUN_DIR}"/journey-device-b.json "$TOKEN"
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "second device registration should answer 200"
curl -sS -o /dev/null -w '%{http_code}' -X DELETE "http://localhost:8090/v1/devices" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"token":"journey-token-b"}' > "${JOURNEY_RUN_DIR}"/journey-status.txt
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "device deregistration should answer 200"
api_post /v1/devices '{"token":"trial-token-c","platform":"expo"}' "${JOURNEY_RUN_DIR}"/journey-trial-device.json "$TRIAL_TOKEN"
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "trial device registration should answer 200"
# A fresh run on the main workspace; applying it is what triggers delivery. The request is
# the step-4 shape (same items, same windows) — the server is stateless about items.
PUSH_PLAN_REQUEST=$(jq -cn \
  --argjson now "$NOW" \
  --arg ws "$WS" \
  --argjson schedule "$SCHEDULE" \
  '{v:1, workspaceId:$ws,
    rangeStartMs:$now, rangeEndMs:($now+7*86400000), nowMs:$now,
    items:[
      {id:"t1", boardId:"b1", title:"Write the proposal", rank:0, effortMinutes:90, dueAt:($now+17*3600000)},
      {id:"t2", boardId:"b1", title:"Prep the slides", rank:1, effortMinutes:45},
      {id:"t3", boardId:"b1", title:"Review the deck", rank:2, effortMinutes:60, dueAt:($now+40*3600000)}
    ],
    blocks:[], dependencies:[], fixedCommitments:[], scheduleIdByTaskId:{}, preferredOrder:[],
    schedules:[$schedule],
    deadlinePolicy:"HARD"}')
api_post /v1/plan "$PUSH_PLAN_REQUEST" "${JOURNEY_RUN_DIR}"/journey-push-plan.json "$TOKEN"
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "push leg: plan should answer 200"
PUSH_RUN=$(jq -r .runId "${JOURNEY_RUN_DIR}"/journey-push-plan.json)
[ -n "$PUSH_RUN" ] && [ "$PUSH_RUN" != "null" ] || fail "push leg: the plan run did not produce a runId"
api_post "/v1/plan/$PUSH_RUN/apply" '{}' "${JOURNEY_RUN_DIR}"/journey-push-apply.json "$TOKEN"
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "push leg: apply should answer 200"
PUSH_ENTRY=$(jq -r .entryId "${JOURNEY_RUN_DIR}"/journey-push-apply.json)
[ -n "$PUSH_ENTRY" ] || fail "push leg: apply returned no entryId"
curl -sS -o "${JOURNEY_RUN_DIR}"/journey-push-deliveries.json -w '%{http_code}' \
  "http://localhost:8090/v1/fixture/push-deliveries" -H "Authorization: Bearer $TOKEN" \
  > "${JOURNEY_RUN_DIR}"/journey-status.txt
[ "$(cat "${JOURNEY_RUN_DIR}"/journey-status.txt)" = "200" ] || fail "push deliveries should answer 200"
jq -e '.deliveries | length == 1' "${JOURNEY_RUN_DIR}"/journey-push-deliveries.json >/dev/null \
  || fail "expected exactly one delivery, got: $(jq -c '.deliveries | map(.token)' "${JOURNEY_RUN_DIR}"/journey-push-deliveries.json)"
jq -e '.deliveries[0].token == "journey-token-a"' "${JOURNEY_RUN_DIR}"/journey-push-deliveries.json >/dev/null \
  || fail "the delivery went to the wrong token"
jq -e --arg e "$PUSH_ENTRY" '.deliveries[0].data.entryId == $e' "${JOURNEY_RUN_DIR}"/journey-push-deliveries.json >/dev/null \
  || fail "the delivery does not name the applied entry: $(jq -c '.deliveries[0]' "${JOURNEY_RUN_DIR}"/journey-push-deliveries.json)"
jq -e '.deliveries[0].title == "Your plan was applied"' "${JOURNEY_RUN_DIR}"/journey-push-deliveries.json >/dev/null \
  || fail "the delivery has the wrong title"
# The deleted token B and the trial workspace's token C must never have been delivered to.
jq -e '.deliveries | map(.token) | index("journey-token-b") == null and index("trial-token-c") == null' "${JOURNEY_RUN_DIR}"/journey-push-deliveries.json >/dev/null \
  || fail "a delivery leaked to a deregistered or foreign token"
step "11. push               (+$(( $(start_ms) - T )) ms)"

TOTAL=$(( $(start_ms) - T0 ))
echo "== journey complete in ${TOTAL} ms"
if [ "$TOTAL" -gt 90000 ]; then
  fail "the journey blew the 90-second budget (${TOTAL} ms)"
fi
echo "journey evidence: $JOURNEY_RUN_DIR"
