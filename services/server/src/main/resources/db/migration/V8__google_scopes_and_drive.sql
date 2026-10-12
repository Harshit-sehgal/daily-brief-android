-- Going public: one Google connection per workspace now carries more than a calendar.
-- Sign-in asks for identity only; the calendar and Drive are granted later, each by its
-- own consent, so the row records which scopes Google actually granted. The Drive copy
-- of the notebook is one file, rewritten in place, so its id and last write live here too.
ALTER TABLE calendar_connections ADD COLUMN scopes TEXT NOT NULL DEFAULT '';
ALTER TABLE calendar_connections ADD COLUMN drive_file_id TEXT;
ALTER TABLE calendar_connections ADD COLUMN drive_backup_at BIGINT;

-- Every connection made before this migration came from the single consent that asked
-- for the calendar, so that is what it holds.
UPDATE calendar_connections
SET scopes = 'openid email https://www.googleapis.com/auth/calendar.readonly'
WHERE provider = 'google';
