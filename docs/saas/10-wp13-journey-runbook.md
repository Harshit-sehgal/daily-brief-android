# WP-13 journey runbook — the real-account leg

The automated acceptance (`scripts/journey.sh`) proves the loop headless against the
fixture provider. This runbook walks the same loop on a real Google account, with the
90-second stopwatch. The slice's acceptance criterion is literal: signup → an
accepted-or-rejected proposal in **under 90 seconds**. Over three minutes is a bug in this
stage.

## What you need

- The server running in **real mode** (no `FIXTURE_PROVIDER`):

  ```bash
  DATABASE_URL=jdbc:postgresql://localhost:54331/dailybrief \
  DATABASE_USER=postgres DATABASE_PASSWORD=postgres \
  SESSION_SECRET=<long random string> \
  ENVELOPE_KEY_HEX=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n') \
  GOOGLE_CLIENT_ID=<oauth client id> GOOGLE_CLIENT_SECRET=<oauth client secret> \
  server/build/install/server/bin/server   # after :server:installDist
  ```

- A Google Cloud OAuth client of type "Web application" with the redirect URI
  `http://localhost:3000/auth/callback` and the scope
  `https://www.googleapis.com/auth/calendar.readonly`. The client must be in a
  **test** publishing state (or the consent screen needs a test user).
- The web client (fixture off, real mode):

  ```bash
  cd web && npm run dev        # http://localhost:3000, NEXT_PUBLIC_FIXTURE=0
  ```

- A test calendar with a handful of events over the next week, and your test Google
  account signed into the browser.

## The loop, timed

Start the stopwatch at the moment you click **Sign in with Google**.

| # | Step | What should happen | Budget |
| --- | --- | --- | --- |
| 1 | Sign in | Google consent → lands back on the Planner, signed in | ≤ 20 s |
| 2 | Add three tasks | Titles + effort minutes appear in the table | ≤ 10 s |
| 3 | Connect calendar | The calendar card shows your event count and busy minutes | ≤ 10 s |
| 4 | Plan my week | Proposal card: ≥ 1 block, each with a reason sentence | ≤ 10 s |
| 5 | Apply | Blocks land on Today; the card shows them | ≤ 10 s |
| 6 | Reject leg (optional) | A second proposal, Reject → Today unchanged | ≤ 10 s |
| 7 | Undo | Undo apply → the blocks leave Today | ≤ 10 s |

**Acceptance:** step 4 (proposal with reasons) is reached at or before 90 seconds.
Steps 5–7 have no hard clock of their own; the 90-second number is the signup→proposal
journey.

## Where time goes, and what the slice does not do

- **Token refresh is WP-14 hardening, now shipped.** An expired access token (Google's live
  ~1 hour) is refreshed once through the OAuth endpoint, the fresh pair is stored back in the
  envelope, and the fetch is retried — a long demo no longer needs a fresh sign-in. A refresh
  that is itself refused (revoked consent) still fails the sync; that is the honest answer.
- **The consent screen is the one thing outside the server's control** — it is Google's
  page. If the client is in "testing" state, the first sign-in adds a test user step.
  The 20 s budget assumes consent was already granted once.
- **Reconcile fetches the next 14 days** (the planner's range); events with no
  `dateTime` (all-day) are imported with the day's start/end, never inferred.
- The planner's working window is Mon–Fri 09:00–17:00 UTC, as the web client sends it;
  the proposal reasons come straight from the engine (`AutoPlan`), not from the server.

## If something breaks

- `scripts/journey.sh` failing? That is the fixture leg's proof; fix the server, not the
  runbook.
- The web page shows a CORS error: the server's `WEB_ORIGIN` (default
  `http://localhost:3000`) must match the dev server's origin exactly, scheme included.
- "No connected calendar": `/v1/reconcile` refuses when `calendar_connections` has no
  `connected` row — sign in again.
- Plan returns 422: the refusal is a first-class answer (`request.refusalReason()`), the
  message names the field.

## The fixture mode recap

`FIXTURE_PROVIDER=1` swaps Google for the deterministic fixture calendar (today 14:00
"Client stand-up", tomorrow 09:00 "Focus writing") and `/v1/fixture/signup` for OAuth.
`scripts/journey.sh` drives exactly that leg end to end and asserts it — it is the gate's
journey, and the runbook here is the same loop with a real account and a human clock.