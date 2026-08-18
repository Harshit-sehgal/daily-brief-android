-- Stage 4.5: the trial boundary. subscriptions already exists (tier/status/quota_json);
-- the boundary needs an end date, and the enforcement needs nothing else.
ALTER TABLE subscriptions ADD COLUMN trial_ends_at bigint;