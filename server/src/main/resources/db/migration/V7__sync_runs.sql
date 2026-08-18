-- WP-14 hardening: the reconciliation ledger. Every reconcile records a row — what ran,
-- when, how long, how many events were seen, and whether the merge committed. This is the
-- "metrics" half of the worker's ops surface: a deployment can watch sync health without
-- instrumenting the provider, and a failed run is visible even though the merge rolled back.
CREATE TABLE sync_runs (
  id TEXT PRIMARY KEY,
  workspace_id uuid NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
  started_at BIGINT NOT NULL,
  finished_at BIGINT NOT NULL,
  duration_ms BIGINT NOT NULL,
  events_seen INT NOT NULL,
  status TEXT NOT NULL
);

CREATE INDEX idx_sync_runs_workspace_started ON sync_runs (workspace_id, started_at DESC);