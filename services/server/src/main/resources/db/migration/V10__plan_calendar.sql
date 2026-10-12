-- The plan, written into Google Calendar: a secondary calendar the notebook creates and
-- alone manages, so the reader's own events are never touched. Its id and the last
-- successful write live on the Google connection.
ALTER TABLE calendar_connections ADD COLUMN plan_calendar_id TEXT;
ALTER TABLE calendar_connections ADD COLUMN plan_pushed_at BIGINT;
