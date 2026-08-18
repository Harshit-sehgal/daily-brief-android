# Domain and architecture — V1 server

The server runs the same `packages/planning-core` the app runs. This document maps the Room schema to a
multi-tenant Postgres model and names the invariants that must be redesigned, not translated.

## Tenant and identity

```
User (email, name, tier, subscription_id)
Workspace (id, name, owner_user_id)
Membership (workspace_id, user_id, role)   -- ICP: consultant + 1 assistant
Subscription (id, workspace_id, tier, status, quota)
```

`Client` is a **new entity the current schema lacks entirely** and the ICP requires: an
engagement with hours, a rate, a deadline posture. `PlanBoard` becomes `Project` and every
project belongs to a `Client` (nullable for personal projects).

## Room → Postgres mapping

| Room (app) | Postgres (server) |
| --- | --- |
| `PlanBoard` | `Project` (client_id nullable; engagement) |
| `PlanColumn` | `WorkflowStage` |
| `PlanItem` | `Task` (board_id → project_id, column_id → stage_id) |
| `PlanBlock` | `ScheduledBlock` (project_id, task_id, plan_run_id) |
| `BriefingEvent` | `ExternalEvent` (calendar_connection_id; is_all_day preserved) |
| `WorkSchedule` | `WorkSchedule` (workspace_id; the multi-schedule max-flow becomes "client A hours vs internal hours") |
| `PlanMutation` (journal) | `AuditEntry` (workspace_id, tenant-scoped) |
| `PlanBaseline` | `PlanBaseline` (project_id, journal-encoded like today) |
| `SavedView` | `SavedView` (workspace-scoped; the `(boardId, surface, nameKey)` uniqueness becomes a real constraint) |
| `PlanDependency` | `TaskDependency` (task-level, same four types, signed lag) |
| `SettingKey/Value` | `WorkspaceSetting` (encrypted values, envelope-encrypted at rest) |
| `SecretStore` (Keystore) | KMS / envelope encryption (see invariants) |

Every entity gains `workspace_id`; every query is scoped by it. The codecs move verbatim:
`PlanMutationCodec`, `WorkingScheduleMutationCodec`, `PlanCatalogMutationCodec` and
`SavedViewCodec` are already JVM-portable in `packages/planning-core`'s `jvmShared` and speak only
strings/lists — they serialize into the same journal encoding, so history survives the move.

## Service architecture

- **Planner service** (stateless, scale-out): `AutoPlan.propose`, `PlanHealth.evaluate`,
  `MultiSchedulePlanHealth.evaluate`, `CriticalPathEngine.analyze` — the engine is pure; the
  service adds the transaction and the audit entry. Deterministic by contract (04), so results
  are cacheable by `(request hash, data version)`.
- **Reconciliation service**: per-tenant advisory lock; fetch from calendar connections /
  Notion pulled *outside* the lock (the app's `SCHEDULE_MUTEX` holds provider I/O inside a
  process-wide mutex — that cannot scale; the property to preserve is that a source's rows and
  a concurrent user edit never interleave). `SyncMergePolicy` stays the only place that decides
  what a re-sync may overwrite.
- **Briefing service**: overlap analysis (`ScheduleAnalysis`), day shape, summary generation
  with platform Gemini key + per-tenant quota.
- **Queue**: one job per connection per sync interval; alarms (`AlarmScheduler`) become
  server-side schedule + push.

## The four invariants that need redesign, not translation

1. **Concurrency.** `BriefingRepository.SCHEDULE_MUTEX` is process-wide and holds provider I/O
   in the lock. Server: per-tenant advisory lock, fetch outside it. Property to preserve:
   reconciliation of a source's rows and a concurrent user edit must not interleave.
2. **Undo's staleness check.** `matchesMutationState` is whole-row value equality inside one
   SQLite transaction on a single-writer database. Postgres: claim-the-entry `UPDATE …
   RETURNING` plus `SELECT … FOR UPDATE` on the affected entities in id order, with the same
   whole-row comparison (settled in `09-server-invariants.md` §2 — `SERIALIZABLE` was
   rejected: same guarantee with less concurrency and a retry loop). The compare-and-set
   semantics of the journal writers (a stale entry refuses rather than overwrites) are the
   contract.
3. **Secrets.** Android Keystore → KMS/envelope encryption. Keep the design properties:
   AAD bound to the setting key, never destroy ciphertext on a failed read, secrets never in
   plain columns (`SECRET_SETTING_KEYS` routing carries over).
4. **Real constraints.** Two constraints live only in application code today and become
   Postgres constraints: `saved_views` uniqueness on `(boardId, surface, nameKey)` (no index
   today — enforce with a unique index), and `work_schedules` "exactly one non-archived
   default" (a partial unique index).

## Security posture change

Today users paste their own Gemini key, encrypted on-device. A commercial SaaS uses a platform
key with per-tenant quota and server-side OAuth tokens; the browser never receives a long-lived
secret. This changes `activeGeminiKey()`, `parseGeminiKeys`/`encodeGeminiKeys` and the Gemini
settings surface in the app (teardown marks it REDESIGN).

## Deployment notes

- Postgres 16+, `SERIALIZABLE` for journal transactions, partial unique indexes for the two
  invariants above, `citext` for tenant names.
- The engine's `jvmShared` codec + `WorkingCalendar` JVM dependencies run unmodified on the
  server JVM; the `commonMain` porting work (05-kmp-portability-audit.md) is for the future
  iOS client, not a server prerequisite.