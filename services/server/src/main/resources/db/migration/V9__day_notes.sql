-- The notes sheet beside each day: a few typed lines and a freehand sketch. One row per
-- workspace and calendar day. The body is the reader's own writing, so like every stored
-- secret it is an envelope (docs/saas/09-server-invariants.md §3), never plain text.
CREATE TABLE day_notes (
  workspace_id uuid NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
  day TEXT NOT NULL,                 -- 'YYYY-MM-DD', the reader's local date
  body_ciphertext TEXT NOT NULL,
  body_kms_key_id TEXT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (workspace_id, day)
);
