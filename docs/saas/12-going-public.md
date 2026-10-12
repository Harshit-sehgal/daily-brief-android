# Going public — the launch runbook

What stands between this repository and a notebook strangers can sign in to. Everything
in the code is in place and proven against fixtures (`scripts/journey.sh`,
`scripts/web-journey.sh`, `apps/web/e2e/notebook.mjs`, `apps/web/e2e/offline.mjs`). What
is left needs accounts and decisions only the operator has, and one thing only Google can
grant. Each step says how to check it.

## 0. A link today, before any of this

`scripts/share-tunnel.sh` serves the notebook from one computer through a Cloudflare
quick tunnel and prints an `https://….trycloudflare.com` link. It is for showing the
notebook to someone: the link lasts as long as the script runs, its address changes on
every run, and sign-in is the fixture account, so **everyone with the link shares one
notebook**. It uses the single-address mode below, so it needs no domain.

**One address instead of two.** Building the web app with
`NEXT_PUBLIC_API_BASE=same-origin` and `API_PROXY_TARGET=<the server>` makes the web
server forward `/v1/…` to the planning server. The session cookie is then first-party
everywhere and section 1's two-names rule does not apply; set the server's `WEB_ORIGIN`
to that one address.

## 1. Two origins on one site

The browser session is an HttpOnly cookie set by the API, and the web app calls the API
from another origin. Over https the cookie is `SameSite=None; Secure`, which a browser
that blocks third-party cookies — Safari always, Firefox and Chrome increasingly — will
still refuse unless the two origins are the same *site*. So the web app and the API must
share a registrable domain:

| | Example | Serves |
| --- | --- | --- |
| Web | `https://app.example.com` | `apps/web` (every page is static; any static host or `next start`) |
| API | `https://api.example.com` | `services/server` |

A web app on `something.vercel.app` talking to an API on another domain signs in and,
in those browsers, is signed out again on the next request. Both must be `https`: that is what marks the cookie
`Secure`, and what makes the web app installable.

## 2. Google Cloud: the OAuth client

In a Google Cloud project, APIs & Services:

1. **Enable** the Google Calendar API and the Google Drive API.
2. **OAuth consent screen**: External. App name, support email, the logo
   (`apps/web/public/icons/icon-512.png`), and the three links Google requires:
   home `https://app.example.com`, privacy `https://app.example.com/privacy`, terms
   `https://app.example.com/terms`. Add the domain under Authorised domains.
3. **Scopes** to declare, exactly what `GoogleScopes` in the server requests:

   | Scope | Asked for | Google's class |
   | --- | --- | --- |
   | `openid`, `email`, `profile` | at sign-in | non-sensitive |
   | `.../auth/drive.file` | when the reader presses Connect on Drive | non-sensitive |
   | `.../auth/calendar.app.created` | when the reader presses Connect on Plan in Calendar | check on the consent screen |
   | `.../auth/calendar.readonly` | when the reader presses Connect on Calendar | **sensitive** |

4. **Credentials**: an OAuth client ID of type Web application. Authorised redirect URI
   `https://app.example.com/auth/callback` (plus `http://localhost:3000/auth/callback`
   for development). The client id and secret go in the server's environment.

**Verification is the long pole.** Sign-in and Drive work for anyone as soon as the
consent screen is published. The calendar scope is sensitive: until Google verifies the
app, people outside the test-user list see an "unverified app" warning when they connect
their calendar, and the app is capped at 100 such users. Submit for verification from the
consent screen; Google asks for the privacy policy (written, at `/privacy`), a
justification for the scope (the planner reads events so that plans fit around them; it
never writes), and a short screen recording of the consent and the feature. Allow a few
weeks. Nothing in the code changes when it is granted.

This is why sign-in asks only for identity: a new visitor never meets the warning before
they have seen the notebook.

## 3. The quick way: one machine

`deploy/docker-compose.yml` runs everything — Postgres, the server, the web app, and
Caddy in front, which gives both names https and renews the certificates itself.

```bash
cp deploy/.env.example deploy/.env        # fill in the two host names, secrets, Google client
./gradlew :server:installDist
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
```

Point `app.` and `api.` DNS records at the machine first, and open ports 80 and 443.
Steps 3a and 3b below are the same thing by hand, for a host that is not Docker.

**Rehearsed here** with `APP_HOST=app.dailybrief.localhost`, `FIXTURE_PROVIDER=1` and
the browser journey driven through Caddy over https: sign-in, the Secure session cookie
crossing from the app's name to the API's, plan, apply and the Drive copy all pass. That
proves the topology; it does not prove Google, which the fixture replaces.

## 3a. The server

```bash
./gradlew :server:installDist
docker build -t dailybrief-server services/server      # or run build/install/server/bin/server
```

Environment: `services/server/.env.example` lists every variable. It needs Postgres 16+
(Flyway migrates on boot), a `SESSION_SECRET`, `WEB_ORIGIN`, the Google client, and a
token-encryption key — `ENVELOPE_KEY_HEX`, or AWS KMS. **Back the envelope key up
separately from the database**: without it every stored Google token and day note is
unreadable. `FIXTURE_PROVIDER` must be unset.

Check: `API_BASE=https://api.example.com WEB_ORIGIN=https://app.example.com scripts/production-probe.sh`.

## 3b. The web app

```bash
cd apps/web
NEXT_PUBLIC_API_BASE=https://api.example.com NEXT_PUBLIC_CONTACT_EMAIL=you@example.com npm run build
```

`NEXT_PUBLIC_FIXTURE` must be unset or `0`; the build refuses to run without
`NEXT_PUBLIC_API_BASE`. Read `/privacy` and `/terms` before launch: they describe what
the code does today, but they are the operator's promises and want a lawyer's eye, a
legal name and a contact address.

Check, in a real browser on the deployed site:

- Continue with Google signs in and lands on the week.
- Settings → Calendar → Connect shows Google's consent for the calendar only; events
  appear on the week. Settings → Drive → Connect creates `Daily Brief.md` in Drive.
  Settings → Plan in Calendar → Connect creates a "Daily Brief" calendar; apply a plan
  and its blocks appear there within a few seconds.
- Chrome's address bar offers Install; on an iPhone, Share → Add to Home Screen. The
  installed app opens full screen, and opens with the network off.
- Settings → Delete account removes the account; the app is gone from
  `myaccount.google.com/permissions`.

Then run the timed real-account loop in `10-wp13-journey-runbook.md`.

## 4. Optional services

Each stays off, and the UI says so honestly, until its keys are set: `GEMINI_API_KEY`
(the written brief), the three `STRIPE_*` variables (paid plans; point the Stripe webhook
at `/v1/billing/webhook`), `EXPO_ACCESS_TOKEN` (push to the phone app).

## What "synced" means here

- **Calendar → notebook**, read only. Fetched on connect, on every sign-in, and again
  whenever the notebook is opened or returned to and the last fetch is over ten minutes
  old. Settings has Read now. Events are never written back.
- **Plan → calendar**, one way, opt-in. Planned work is written to a secondary
  calendar named "Daily Brief" that the notebook creates and alone manages, from
  yesterday to two months ahead, a few seconds after the plan changes. Events are
  derived from block, time and title, so the same plan written twice changes nothing and
  a moved block is a delete and an insert. The reader's own calendars cannot be touched
  under this scope. **This path has only been run against a local stand-in for Google**
  (`PlanCalendarTest`) and the fixture: on the first real account, connect it and check
  that the calendar appears, that an applied plan shows, and that Undo removes it.
- **Notebook → Drive**, one way. `Daily Brief.md` is rewritten a few seconds after the
  last change: the plan by day, every project's tasks, and the last month of typed notes.
  Edits made to the file are not read back, and the file says so on its first line.
- **Between devices**: the server is the source of truth. A device shows the current
  notebook when it loads and re-reads it whenever its window is returned to. A device
  with no signal shows its last pages and keeps new jots to send; other edits need a
  connection and say so.

## Not built

Said plainly so nobody discovers it in production: nothing changed in Google Calendar
is read back into the plan, and the reader's own events are never edited; a Drive copy
cannot be restored from; two devices side by side do not
update each other while both are being watched (each re-reads when returned to); there is
no email/password sign-in; the phone apps in `apps/mobile` and `apps/android` do not yet
have the tools, notes or Drive.
