#!/usr/bin/env bash
# Checks the non-destructive production boundary before a real-account journey.
#
#   API_BASE=https://api.example.com WEB_ORIGIN=https://app.example.com \
#     scripts/production-probe.sh
#
# This does not sign in, mutate data, or claim that Google/Stripe/Gemini/Expo are configured.
# It proves only that the public API is live, ready, and admits the configured browser origin.
set -euo pipefail

API_BASE="${API_BASE:?set API_BASE to the deployed API origin}"
WEB_ORIGIN="${WEB_ORIGIN:-}"

case "$API_BASE" in
  http://*|https://*) ;;
  *) echo "probe: API_BASE must be an http(s) origin" >&2; exit 2 ;;
esac

API_BASE="${API_BASE%/}"
curl_json() {
  curl --fail --silent --show-error --max-time 15 "$1"
}

echo "probe: liveness"
LIVE="$(curl_json "$API_BASE/health/live")"
echo "$LIVE" | jq -e '.status == "ok"' >/dev/null

echo "probe: readiness"
READY="$(curl_json "$API_BASE/health/ready")"
echo "$READY" | jq -e '.status == "ready"' >/dev/null

if [ -n "$WEB_ORIGIN" ]; then
  echo "probe: CORS origin"
  HEADERS="$(mktemp)"
  BODY="$(mktemp)"
  trap 'rm -f "$HEADERS" "$BODY"' EXIT
  STATUS="$(curl --silent --show-error --max-time 15 -o "$BODY" -D "$HEADERS" -w '%{http_code}' \
    -X OPTIONS "$API_BASE/v1/projects" \
    -H "Origin: $WEB_ORIGIN" \
    -H 'Access-Control-Request-Method: GET' \
    -H 'Access-Control-Request-Headers: authorization,content-type,x-dailybrief-csrf')"
  [ "$STATUS" = "204" ] || [ "$STATUS" = "200" ] || {
    echo "probe: CORS preflight returned HTTP $STATUS" >&2
    exit 1
  }
  grep -Fqi "access-control-allow-origin: $WEB_ORIGIN" "$HEADERS" || {
    echo "probe: configured web origin was not admitted by CORS" >&2
    exit 1
  }
  grep -Fqi "access-control-allow-credentials: true" "$HEADERS" || {
    echo "probe: credentialed browser requests were not admitted by CORS" >&2
    exit 1
  }
fi

echo "probe: OK — live, ready${WEB_ORIGIN:+, and CORS-admitted for $WEB_ORIGIN}"
