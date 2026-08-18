-- Stage 4.4: a baseline is a snapshot of the schedule at one moment, kept so the variance
-- endpoint can say what moved since. The payload is the frozen wire (TaskWire +
-- ScheduledBlockWire arrays), so decoding needs no per-row SQL and a future contract bump
-- only changes the payload, never the table shape.
CREATE TABLE baselines (
  id text primary key,
  workspace_id uuid not null references workspaces (id) on delete cascade,
  created_at bigint not null,
  task_count integer not null,
  block_count integer not null,
  payload jsonb not null
);
CREATE INDEX baselines_workspace_created ON baselines (workspace_id, created_at);