-- The daily brief's per-tenant usage ledger (09-server-invariants §4). The table lands now
-- because the briefing service is its consumer: reserve-then-refuse in the request
-- transaction, monthly period as UTC 'YYYY-MM'.
create table gemini_usage (
  workspace_id uuid not null references workspaces (id) on delete cascade,
  period_month text not null,
  requests bigint not null default 0,
  input_tokens bigint not null default 0,
  output_tokens bigint not null default 0,
  primary key (workspace_id, period_month)
);