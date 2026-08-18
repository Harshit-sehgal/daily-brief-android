-- Daily Brief V1 server schema (multi-tenant Postgres 16+).
--
-- Migrated from the Room schema in app/schemas/com.example.data.database.AppDatabase/9.json,
-- which is the authoritative source for the app-side shape; every column that exists there
-- appears here with the same semantics. The WP-10 rules:
--
--   * Every business row carries workspace_id — the workspace IS the tenant (a consultant
--     plus an assistant, per Membership). The work-package brief says "tenant_id"; the domain
--     doc says workspace_id. They are the same thing here, and the column is named for the
--     entity that exists, not the abstraction.
--   * Two constraints that live only in application code today become real constraints:
--     saved_views unique on (project_id, surface, name_key) — Room has no index on it at all —
--     and work_schedules' "exactly one non-archived default" as a partial unique index.
--   * Room's global unique indexes (e.g. plan_boards.nameKey) become per-workspace unique
--     indexes, because tenancy changes what "unique" means.
--   * Timestamps stay bigint epoch milliseconds: the engine and the journal codecs speak ms,
--     and the codecs move verbatim, so the schema should not invent a timestamp conversion
--     layer between the audit entries and the engine that reads them.
--   * Client-generated ids (the app's v5 stable ids) stay TEXT on the migrated tables so
--     existing databases survive the move; the platform tables get native uuid.
--
-- App-owned tables whose ids the client generates keep their TEXT id; the engine's
-- stableId/nameKey are the compatibility layer, unchanged.

-- ── Identity and tenancy ──────────────────────────────────────────────────────────────────

create table users (
  id uuid primary key,
  email text not null unique,
  name text not null,
  tier text not null default 'free',
  created_at bigint not null,
  updated_at bigint not null
);

create table workspaces (
  id uuid primary key,
  name text not null,
  owner_user_id uuid not null references users (id),
  created_at bigint not null,
  updated_at bigint not null
);

-- ICP: consultant + 1 assistant. role: 'owner' | 'assistant'.
create table memberships (
  workspace_id uuid not null references workspaces (id) on delete cascade,
  user_id uuid not null references users (id),
  role text not null,
  primary key (workspace_id, user_id)
);

-- quota_json holds the per-tenant Gemini usage envelope (WP-11 invariant 4); tier and
-- status drive which quota applies.
create table subscriptions (
  id uuid primary key,
  workspace_id uuid not null unique references workspaces (id) on delete cascade,
  tier text not null,
  status text not null,
  quota_json text not null,
  created_at bigint not null,
  updated_at bigint not null
);

-- ── Engagements and projects ───────────────────────────────────────────────────────────────

-- New entity the app schema lacks entirely; the consultant ICP requires an engagement with
-- hours, a rate and a deadline posture. Nullable project linkage means personal projects exist.
create table clients (
  id uuid primary key,
  workspace_id uuid not null references workspaces (id) on delete cascade,
  name text not null,
  contract_hours_minutes bigint,
  rate_cents bigint,
  deadline_posture text not null default 'soft',   -- 'soft' | 'hard'
  archived_at bigint,
  created_at bigint not null,
  updated_at bigint not null,
  unique (workspace_id, name)
);

-- plan_boards → projects. is_default survives (the app's "which board is home") and the
-- per-workspace nameKey uniqueness is Room's global one, re-scoped.
create table projects (
  id text primary key,
  workspace_id uuid not null references workspaces (id) on delete cascade,
  client_id uuid references clients (id),
  name text not null,
  name_key text not null,
  rank bigint not null,
  is_default boolean not null default false,
  archived_at bigint,
  created_at bigint not null,
  updated_at bigint not null,
  unique (workspace_id, name_key)
);

-- plan_columns → workflow_stages.
create table workflow_stages (
  id text not null,
  project_id text not null references projects (id) on delete cascade,
  workspace_id uuid not null,
  name text not null,
  name_key text not null,
  rank bigint not null,
  archived_at bigint,
  created_at bigint not null,
  updated_at bigint not null,
  primary key (project_id, id),
  unique (project_id, name_key)
);

-- plan_items → tasks. The composite FKs mirror Room's: a child always sits in its parent's
-- project, and a stage belongs to the same project as its task.
create table tasks (
  id text not null,
  project_id text not null references projects (id) on delete cascade,
  stage_id text,
  parent_id text,
  workspace_id uuid not null,
  title text not null,
  notes text,
  rank bigint not null,
  start_constraint bigint,
  due_at bigint,
  effort_minutes integer,
  progress integer not null default 0,
  priority text not null default 'normal',
  owner text,
  scheduling_mode text not null default 'auto',
  locked boolean not null default false,
  is_milestone boolean not null default false,
  completed_at bigint,
  archived_at bigint,
  created_at bigint not null,
  updated_at bigint not null,
  primary key (project_id, id),
  foreign key (project_id, stage_id) references workflow_stages (project_id, id),
  foreign key (project_id, parent_id) references tasks (project_id, id)
);

-- ── Scheduling ─────────────────────────────────────────────────────────────────────────────

-- plan_blocks → scheduled_blocks. plan_run_id ties blocks to the proposal that created them
-- (null for hand-drawn blocks). Whole-minute starts/ends are the planner contract
-- (04-planner-api-contract.md) and the journal enforces them; the column types carry them
-- faithfully.
create table scheduled_blocks (
  id text not null,
  project_id text not null,
  task_id text not null,
  plan_run_id uuid,
  workspace_id uuid not null,
  start_at bigint not null,
  end_at bigint not null,
  position integer not null default 0,
  locked boolean not null default false,
  linked_event_id text,
  created_at bigint not null,
  updated_at bigint not null,
  primary key (project_id, id),
  foreign key (project_id, task_id) references tasks (project_id, id)
);

-- plan_dependencies → task_dependencies. Same four types as the engine; lag is signed.
create table task_dependencies (
  id text not null,
  project_id text not null,
  predecessor_id text not null,
  successor_id text not null,
  workspace_id uuid not null,
  type text not null check (type in ('FS', 'SS', 'FF', 'SF')),
  lag_minutes integer not null default 0,
  created_at bigint not null,
  updated_at bigint not null,
  primary key (project_id, id),
  unique (project_id, predecessor_id, successor_id),
  foreign key (project_id, predecessor_id) references tasks (project_id, id),
  foreign key (project_id, successor_id) references tasks (project_id, id)
);

-- work_schedules unchanged in shape. THE second real constraint: exactly one non-archived
-- default per workspace — the app enforced this in WorkingCalendarMapper/WorkspacePreference
-- code; Postgres enforces it now.
create table work_schedules (
  id text primary key,
  workspace_id uuid not null references workspaces (id) on delete cascade,
  name text not null,
  name_key text not null,
  time_zone_id text not null,
  is_default boolean not null default false,
  minimum_chunk_minutes integer not null,
  maximum_chunk_minutes integer not null,
  buffer_minutes integer not null default 0,
  rank bigint not null,
  archived_at bigint,
  created_at bigint not null,
  updated_at bigint not null,
  unique (workspace_id, name_key)
);

create unique index work_schedules_one_default
  on work_schedules (workspace_id)
  where is_default and archived_at is null;

-- work_schedule_windows: recurring (day_of_week) or one-off (local_date) windows.
create table work_schedule_windows (
  id text not null,
  schedule_id text not null references work_schedules (id) on delete cascade,
  workspace_id uuid not null,
  kind text not null,
  day_of_week integer,
  local_date text,
  start_minute integer not null,
  end_minute integer not null,
  is_closed boolean not null default false,
  rank bigint not null,
  created_at bigint not null,
  updated_at bigint not null,
  primary key (schedule_id, id)
);

-- plan_item_schedules → task_schedule_assignments (which schedule a task's demand draws on).
create table task_schedule_assignments (
  project_id text not null,
  task_id text not null,
  work_schedule_id text not null,
  workspace_id uuid not null,
  created_at bigint not null,
  updated_at bigint not null,
  primary key (task_id, work_schedule_id),
  foreign key (project_id, task_id) references tasks (project_id, id),
  foreign key (work_schedule_id) references work_schedules (id)
);

-- ── Proposals and runs ─────────────────────────────────────────────────────────────────────

-- One planning request, recorded before anything is applied. request_hash + data_version is
-- the deterministic-cache key (03-domain-and-architecture.md): the engine is pure and
-- deterministic by contract, so a repeat request with an unchanged data version is the same
-- plan without re-running the search.
create table plan_runs (
  id uuid primary key,
  workspace_id uuid not null references workspaces (id) on delete cascade,
  request_hash text not null,
  request_json text not null,
  data_version bigint not null default 1,
  status text not null,                        -- 'accepted' | 'rejected'
  result_hash text,
  created_at bigint not null
);

-- The blocks a run would create, each with its reason. Apply is the separate journal call;
-- status: 'proposed' → 'applied' | 'rejected'. Nothing is saved until apply says so.
create table plan_proposals (
  id uuid primary key,
  plan_run_id uuid not null references plan_runs (id) on delete cascade,
  workspace_id uuid not null,
  project_id text not null,
  task_id text not null,
  start_at bigint not null,
  end_at bigint not null,
  reason text not null,
  status text not null default 'proposed',
  applied_audit_entry_id text,
  created_at bigint not null
);

-- ── Journal and history ────────────────────────────────────────────────────────────────────

-- plan_mutations → audit_entries, byte-for-byte the journal the codecs serialize
-- (PlanMutationCodec et al. move verbatim). before_json/after_json hold the whole-row
-- compare-and-set state Undo depends on (WP-11 invariant 2); expires_at is the recovery
-- window the UndoWindowPolicy owns.
create table audit_entries (
  id text not null,
  project_id text,
  workspace_id uuid not null,
  mutation_type text not null,
  target_type text not null,
  target_ids_json text not null,
  summary text not null,
  before_json text,
  after_json text,
  status text not null,
  origin text not null,
  schema_version integer not null,
  created_at bigint not null,
  updated_at bigint not null,
  undone_at bigint,
  expires_at bigint,
  primary key (workspace_id, id)
);

-- plan_baselines → plan_baselines; journal-encoded like today (PlanBaselineCodec), so a
-- baseline survives the tasks it describes being edited.
create table plan_baselines (
  id text not null,
  project_id text not null references projects (id) on delete cascade,
  workspace_id uuid not null,
  name text not null,
  name_key text not null,
  captured_at bigint not null,
  items_json text not null,
  blocks_json text not null,
  primary key (project_id, id),
  unique (project_id, name_key)
);

-- saved_views: workspace-scoped; THE first real constraint — unique on (project_id, surface,
-- name_key). Room has no index on that combination at all; the app enforced it in
-- SavedViewRepository. project_id is nullable (workspace-level views), and Postgres treats
-- NULLs as distinct in unique indexes, which is exactly SQLite's behaviour.
create table saved_views (
  id text not null,
  project_id text references projects (id) on delete cascade,
  workspace_id uuid not null,
  name text not null,
  name_key text not null,
  surface text not null,
  filters_json text not null,
  grouping text not null,
  sort_json text not null,
  columns_json text not null,
  range_days integer,
  zoom text,
  collapsed_ids_json text not null,
  pinned boolean not null default false,
  rank bigint not null,
  created_at bigint not null,
  updated_at bigint not null,
  primary key (workspace_id, id),
  unique (project_id, surface, name_key)
);

-- ── Calendar, briefings, settings ──────────────────────────────────────────────────────────

-- Server-side OAuth lives here; tokens are envelope-encrypted at rest (WP-11 invariant 3),
-- never in a plain column.
create table calendar_connections (
  id uuid primary key,
  workspace_id uuid not null references workspaces (id) on delete cascade,
  provider text not null,                      -- 'google' | 'samsung' | ...
  external_account text not null,
  token_ciphertext text not null,
  token_kms_key_id text not null,
  status text not null,                        -- 'connected' | 'revoked' | 'error'
  last_sync_at bigint,
  created_at bigint not null,
  updated_at bigint not null,
  unique (workspace_id, provider, external_account)
);

-- briefing_events → external_events. is_all_day is state, never inferred from duration
-- (04-planner-api-contract.md), and the column survives as exactly that.
create table external_events (
  id text not null,
  calendar_connection_id uuid references calendar_connections (id) on delete set null,
  workspace_id uuid not null,
  title text not null,
  start_time bigint not null,
  end_time bigint not null,
  source text not null,
  description text,
  is_deadline boolean not null default false,
  is_urgent boolean not null default false,
  is_all_day boolean not null default false,
  location text,
  kanban_status text not null default '',
  kanban_board text not null default '',
  user_edited boolean not null default false,
  primary key (workspace_id, id)
);

-- daily_briefings: the device keyed this on dateString alone; the server keys it on the
-- workspace too.
create table daily_briefings (
  workspace_id uuid not null references workspaces (id) on delete cascade,
  date_string text not null,
  brief_text text not null,
  created_at bigint not null,
  signature text not null,
  primary key (workspace_id, date_string)
);

-- system_settings → workspace_settings; values are envelope-encrypted at rest. The app's
-- SECRET_SETTING_KEYS routing and its "reject these outright" rule carry over (WP-11).
create table workspace_settings (
  workspace_id uuid not null references workspaces (id) on delete cascade,
  key text not null,
  value text not null,
  primary key (workspace_id, key)
);

-- ── Common indexes (carried over from Room, re-scoped by workspace) ───────────────────────

create index tasks_project_rank on tasks (project_id, stage_id, rank);
create index tasks_parent on tasks (project_id, parent_id);
create index tasks_due_at on tasks (due_at);
create index tasks_archived_at on tasks (archived_at);
create index scheduled_blocks_task_start on scheduled_blocks (task_id, start_at);
create index scheduled_blocks_start_end on scheduled_blocks (start_at, end_at);
create index scheduled_blocks_linked_event on scheduled_blocks (linked_event_id);
create index task_dependencies_successor on task_dependencies (project_id, successor_id);
create index task_dependencies_predecessor on task_dependencies (project_id, predecessor_id);
create index saved_views_rank on saved_views (project_id, surface, rank);
create index saved_views_pinned on saved_views (surface, pinned);
create index work_schedules_default_rank on work_schedules (is_default, rank);
create index work_schedules_archived on work_schedules (archived_at);
create index work_schedule_windows_recurring on work_schedule_windows (schedule_id, kind, day_of_week, rank);
create index work_schedule_windows_dated on work_schedule_windows (schedule_id, kind, local_date, rank);
create index task_schedule_assignments_schedule on task_schedule_assignments (work_schedule_id);
create index audit_entries_project_time on audit_entries (project_id, created_at);
create index audit_entries_status_time on audit_entries (status, created_at);
create index audit_entries_expiry on audit_entries (expires_at);
create index plan_baselines_captured on plan_baselines (project_id, captured_at);
