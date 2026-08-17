#!/usr/bin/env bash
# Proves db/schema.sql on a throwaway Postgres 16: applies the DDL, then runs the
# constraint proofs. Exits non-zero unless every expected error appears.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

CONTAINER=dailybrief-pg
trap 'docker rm -f "$CONTAINER" >/dev/null 2>&1 || true' EXIT
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -e POSTGRES_HOST_AUTH_METHOD=trust -p 54329:5432 postgres:16 >/dev/null
sleep 3

docker exec -i "$CONTAINER" psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
  -c 'create database dailybrief' >/dev/null

docker exec -i "$CONTAINER" psql -U postgres -d dailybrief -v ON_ERROR_STOP=1 \
  -f /dev/stdin < db/schema.sql >/dev/null

OUTPUT=$(docker exec -i "$CONTAINER" psql -U postgres -d dailybrief -v ON_ERROR_STOP=0 \
  -f /dev/stdin < db/verify-schema.sql 2>&1 || true)

printf '%s\n' "$OUTPUT" | grep -E '^===|result:' || true

# The four statements that must be refused: one saved_views duplicate, two
# work_schedules defaults, and the cross-project stage borrow. If any of them did
# NOT error, the guard is open.
ERRORS=$(printf '%s\n' "$OUTPUT" | grep -cE 'ERROR:')
[ "$ERRORS" -eq 4 ] || {
  echo "verify-schema: expected exactly 4 refused statements, saw $ERRORS" >&2
  exit 1
}
grep -q 'saved_views_project_id_surface_name_key_key' <<<"$OUTPUT"
grep -q 'work_schedules_one_default' <<<"$OUTPUT"
grep -q 'tasks_project_id_stage_id_fkey' <<<"$OUTPUT"
# And the success proofs must have run; a proof that silently failed would hide here.
grep -q 'proof 3 result: ok' <<<"$OUTPUT"
echo "verify-schema: schema and constraints proven"
