-- The Expo client's push device registry: a token belongs to exactly one workspace, so a
-- delivery can never leak across tenants. Registration is upserted; DELETE removes the row.
-- (Fixture-mode deliveries are recorded in memory by FixturePushProvider — they are test
-- state, not tenant state, and stay out of the database.)
create table push_tokens (
  workspace_id uuid not null references workspaces (id) on delete cascade,
  token text not null,
  platform text not null,
  created_at bigint not null,
  updated_at bigint not null,
  primary key (workspace_id, token)
);