# The four invariants that need redesign, not translation

WP-11. Each section names the device design it replaces, the server shape, and the property
that must survive. The code sites are marked `REDESIGN` in the tree, pointing here. This is
the design the V1 service (WP-13) implements; the schema it uses is `db/schema.sql` (WP-10).

## 1. Concurrency: `SCHEDULE_MUTEX` → per-tenant advisory lock, fetch outside

**Device design.** `BriefingRepository.SCHEDULE_MUTEX` is a process-wide `Mutex` that
serializes every sync and event mutation, and it holds provider I/O (calendar fetch, Notion
fetch) *inside* the lock. There is one process, so a process-wide mutex is a complete answer.

**Server design.** The property to preserve: *a source's rows and a concurrent user edit
never interleave.* Postgres gives that property per tenant with a transaction-scoped advisory
lock:

```sql
SELECT pg_advisory_xact_lock(hashtextextended(:workspace_id::text, 0));
```

- **The lock is tenant-keyed and transaction-scoped.** `pg_advisory_xact_lock` dies with the
  transaction, so a crashed job cannot leak a lock and hold a tenant hostage. The two writers
  that touch the same rows — reconciliation jobs and journal-writer transactions (task edits,
  undo, applyProposal) — take the same lock; different tenants proceed in parallel, which is
  the entire reason the process-wide mutex cannot carry over.
- **The network fetch is outside the lock.** The reconciliation job fetches from the provider
  first, then takes the lock and re-reads the tenant's rows from the database, then applies
  `SyncMergePolicy` — still the only place that decides what a re-sync may overwrite — and
  writes. This is *more* correct than the device: the device merges against the state at fetch
  start (it has no other choice); the server merges against the state at write time, so a
  user edit made during the fetch is seen by the policy, never clobbered by it.
- **The wait is bounded.** `SET lock_timeout = '5s'` on the transaction. The device's
  invariant is "provider I/O sits inside the lock; keep network calls bounded by timeouts" —
  the server equivalent is "a stuck job must not queue user edits forever". On timeout the
  job retries with backoff and the user edit proceeds.
- **Reads do not take the lock.** Planning reads a committed snapshot under READ COMMITTED;
  every writer is atomic, so the planner sees either the state before or after a reconcile.
  This keeps the determinism-cache contract (04): the same request hash against the same
  `data_version` is the same plan.
- **The queue dedupes; the lock guards.** One job per connection per sync interval (03), plus
  manual refreshes — two overlapping jobs for one tenant are serialized by the same advisory
  lock.

## 2. Undo's staleness check → `SELECT … FOR UPDATE`, not `SERIALIZABLE`

**Device design.** `PlanRepository.matchesMutationState` (PlanRepository.kt:1751) is
whole-row value equality, checked and applied inside one SQLite transaction on a
single-writer database. A stale entry refuses rather than overwrites — that compare-and-set
is the contract.

**Server design.** The audit entry row is the CAS primitive, and the affected entity rows are
the values being compared. One transaction:

1. **Claim the entry.** `UPDATE audit_entries SET undone_at = :now WHERE workspace_id = :ws
   AND id = :id AND undone_at IS NULL AND (expires_at IS NULL OR expires_at > :now)
   RETURNING *`. Zero rows affected → the entry is already undone or outside its recovery
   window → refuse, never overwrite. One row → the claim is ours.
2. **Lock the affected entities.** `SELECT … FROM tasks WHERE project_id = :p AND id = ANY
   (:ids) ORDER BY id FOR UPDATE` — `ORDER BY id` is the deadlock-avoidance discipline (every
   multi-target transaction locks in the same order; the app's `insertMutation` enforces the
   same single ordering). Rows absent from the table (the journal must be able to say a row
   was not there) are handled by the codec's `absentIds` as today.
3. **Re-verify the values.** Compare the locked rows against `before_json` with the same
   `matchesMutationState` code that runs on the device — the codecs move verbatim, so the
   comparison is byte-identical. Any difference → roll back, refuse.
4. **Apply the inverse and record it** (one new audit entry, targets = every affected id),
   commit.

`SERIALIZABLE` is not the tool here: `FOR UPDATE` + value re-check gives the same guarantee
with better concurrency (rows outside the target set stay lock-free) and no retry loop on
serialization failures. The one place a predicate cannot be locked — "exactly one
non-archived default work schedule" — is already a partial unique index in the schema
(WP-10), so it never needs application serialization at all.

The same shape serves `insertMutation`'s pre-write check: lock targets, compare, write.

## 3. `SecretStore` → KMS envelope encryption

**Device design.** `SecretStore` (SecretStore.kt) wraps Android Keystore; AAD is bound to the
setting key; a failed read never destroys the ciphertext; `SECRET_SETTING_KEYS` keeps secrets
out of Room entirely.

**Server design.** The same properties, with the workspace added to the AAD. The envelope is
one JSON document in `workspace_settings.value` (or `calendar_connections.token_ciphertext`):

```json
{ "v": 1, "kmsKeyId": "...", "wrappedDataKey": "b64", "nonce": "b64", "ciphertext": "b64" }
```

- **Data key per write, wrapped by the deployment KMS key.** A fresh data key per (workspace,
  key) write; only the wrapped key crosses into storage. Key rotation re-wraps without
  re-encrypting content.
- **AAD = `workspace_id + ":" + setting_key`.** The device binds AAD to the setting key;
  tenancy extends it to the workspace. A ciphertext copied into another tenant's row cannot
  be opened even with the KMS key in hand.
- **A failed read returns an error and keeps the ciphertext.** The device property — never
  destroy ciphertext on a failed read — is the anti-recovery-destroyer rule: a transient KMS
  outage must not erase a token the user could restore by retrying.
- **`SECRET_SETTING_KEYS` routing carries over unchanged.** The same key list decides which
  settings get envelopes; non-secret settings stay plain text in the same table.
- **Device Keystore stays for device-local secrets.** The app is local-first; its own Gemini
  key remains on-device. The server never accepts a long-lived user secret — it holds only
  server-side OAuth tokens (calendar_connections) and the platform Gemini key.

## 4. Gemini: platform key with per-tenant quota

**Device design.** The user pastes their own Gemini key, encrypted on-device
(`activeGeminiKey`, BriefingRepository.kt:445), and the device calls the API directly.

**Server design.**

- **One platform key, held like a SecretStore envelope** (invariant 3 shape; key id from
  deployment config), loaded once at boot. It never appears in a response, a log, or the
  browser.
- **The browser never receives a long-lived secret.** The client calls the briefing service;
  the service proxies to Gemini. The device app keeps its local BYOK path for on-device
  summaries (local-first), so `activeGeminiKey`/`parseGeminiKeys` stay on the device; the
  Gemini settings surface gains a "server uses the platform key" state and a quota readout
  (teardown: REDESIGN).
- **Per-tenant quota lives in `subscriptions.quota_json`** (limits) and a monthly usage
  ledger:

  ```sql
  create table gemini_usage (
    workspace_id uuid not null references workspaces (id) on delete cascade,
    period_month text not null,          -- '2036-02'
    requests bigint not null default 0,
    input_tokens bigint not null default 0,
    output_tokens bigint not null default 0,
    primary key (workspace_id, period_month)
  );
  ```

  The table lands with the briefing service in WP-13; the schema file gains it then, not
  before it has a consumer.
- **Enforcement is reserve-then-refuse, in the request transaction.** Increment the ledger
  first; if the limit would be exceeded, roll back and return a typed quota error. Never a
  silent fallback that hides the limit — a total that cannot be complete must say so.
- **The platform key is a deployment credential, not a tenant row.** Rotating it re-wraps
  nothing (envelopes hold their own data keys); replacing it is a config change, not a data
  migration.

## Teardown

| Code site | Device design | Verdict |
| --- | --- | --- |
| `BriefingRepository.SCHEDULE_MUTEX` (app) | process-wide mutex holding provider I/O | REDESIGN → invariant 1 |
| `PlanRepository.matchesMutationState` (app) | whole-row equality, single-writer SQLite | REDESIGN → invariant 2 |
| `SecretStore` / `SECRET_SETTING_KEYS` (app) | Android Keystore | REDESIGN on server (invariant 3); device Keystore stays for local secrets |
| `activeGeminiKey` / Gemini settings (app) | user BYOK, on-device calls | REDESIGN → invariant 4; local BYOK path stays |
