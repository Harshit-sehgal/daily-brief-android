\set ON_ERROR_STOP on
-- identity
insert into users (id, email, name, tier, created_at, updated_at) values
  ('11111111-1111-1111-1111-111111111111', 'consultant@example.com', 'Ada', 'pro', 1, 1);
insert into workspaces (id, name, owner_user_id, created_at, updated_at) values
  ('22222222-2222-2222-2222-222222222222', 'Northwind', '11111111-1111-1111-1111-111111111111', 1, 1);
insert into memberships (workspace_id, user_id, role) values
  ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'owner');
insert into subscriptions (id, workspace_id, tier, status, quota_json, created_at, updated_at) values
  ('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', 'pro', 'active', '{}', 1, 1);
insert into clients (id, workspace_id, name, contract_hours_minutes, rate_cents, deadline_posture, created_at, updated_at) values
  ('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', 'Acme', 4000, 15000, 'hard', 1, 1);
-- project + stages + tasks
insert into projects (id, workspace_id, client_id, name, name_key, rank, is_default, created_at, updated_at) values
  ('proj-a', '22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444', 'Acme launch', 'acme-launch', 1, true, 1, 1);
insert into workflow_stages (id, project_id, workspace_id, name, name_key, rank, created_at, updated_at) values
  ('stage-1', 'proj-a', '22222222-2222-2222-2222-222222222222', 'To do', 'to-do', 1, 1, 1),
  ('stage-2', 'proj-a', '22222222-2222-2222-2222-222222222222', 'Done', 'done', 2, 1, 1);
insert into tasks (id, project_id, stage_id, workspace_id, title, rank, effort_minutes, progress, created_at, updated_at) values
  ('task-1', 'proj-a', 'stage-1', '22222222-2222-2222-2222-222222222222', 'Write brief', 1, 120, 0, 1, 1),
  ('task-2', 'proj-a', 'stage-1', '22222222-2222-2222-2222-222222222222', 'Ship launch', 2, 240, 0, 1, 1);

\echo '=== proof 1a: duplicate saved_view (project, surface, name_key) must be refused'
\set ON_ERROR_STOP off
insert into saved_views (id, project_id, workspace_id, name, name_key, surface, filters_json, grouping, sort_json, columns_json, collapsed_ids_json, pinned, rank, created_at, updated_at) values
  ('view-1', 'proj-a', '22222222-2222-2222-2222-222222222222', 'Board', 'board', 'board', '{}', 'none', '[]', '[]', '[]', false, 1, 1, 1);
insert into saved_views (id, project_id, workspace_id, name, name_key, surface, filters_json, grouping, sort_json, columns_json, collapsed_ids_json, pinned, rank, created_at, updated_at) values
  ('view-2', 'proj-a', '22222222-2222-2222-2222-222222222222', 'Board copy', 'board', 'board', '{}', 'none', '[]', '[]', '[]', false, 2, 1, 1);
\echo 'proof 1a result: expected duplicate-key error above'

\echo '=== proof 1b: same name_key on a different surface must be allowed'
insert into saved_views (id, project_id, workspace_id, name, name_key, surface, filters_json, grouping, sort_json, columns_json, collapsed_ids_json, pinned, rank, created_at, updated_at) values
  ('view-3', 'proj-a', '22222222-2222-2222-2222-222222222222', 'Board', 'board', 'timeline', '{}', 'none', '[]', '[]', '[]', false, 3, 1, 1);
\echo 'proof 1b result: ok'

\echo '=== proof 1c: workspace-level view (project null) duplicates are distinct from project views'
insert into saved_views (id, project_id, workspace_id, name, name_key, surface, filters_json, grouping, sort_json, columns_json, collapsed_ids_json, pinned, rank, created_at, updated_at) values
  ('view-4', NULL, '22222222-2222-2222-2222-222222222222', 'Board', 'board', 'board', '{}', 'none', '[]', '[]', '[]', false, 4, 1, 1);
insert into saved_views (id, project_id, workspace_id, name, name_key, surface, filters_json, grouping, sort_json, columns_json, collapsed_ids_json, pinned, rank, created_at, updated_at) values
  ('view-5', NULL, '22222222-2222-2222-2222-222222222222', 'Board again', 'board', 'board', '{}', 'none', '[]', '[]', '[]', false, 5, 1, 1);
\echo 'proof 1c result: ok (NULLs distinct, matching SQLite)'

\echo '=== proof 2a: a second non-archived default schedule must be refused'
insert into work_schedules (id, workspace_id, name, name_key, time_zone_id, is_default, minimum_chunk_minutes, maximum_chunk_minutes, buffer_minutes, rank, created_at, updated_at) values
  ('sched-1', '22222222-2222-2222-2222-222222222222', 'Focus', 'focus', 'Asia/Kolkata', true, 30, 120, 15, 1, 1, 1);
insert into work_schedules (id, workspace_id, name, name_key, time_zone_id, is_default, minimum_chunk_minutes, maximum_chunk_minutes, buffer_minutes, rank, created_at, updated_at) values
  ('sched-2', '22222222-2222-2222-2222-222222222222', 'Internal', 'internal', 'Asia/Kolkata', true, 30, 120, 15, 2, 1, 1);
\echo 'proof 2a result: expected unique-violation error above'

\echo '=== proof 2b: an archived default does not block a new default (partial index)'
insert into work_schedules (id, workspace_id, name, name_key, time_zone_id, is_default, minimum_chunk_minutes, maximum_chunk_minutes, buffer_minutes, rank, archived_at, created_at, updated_at) values
  ('sched-3', '22222222-2222-2222-2222-222222222222', 'Old focus', 'old-focus', 'Asia/Kolkata', true, 30, 120, 15, 3, 9, 1, 1);
\echo 'proof 2b-1 result: ok (archived default coexists with the active one)'
-- hand the default over: archive the active one, then the new default must be accepted
update work_schedules set archived_at = 10 where id = 'sched-1';
insert into work_schedules (id, workspace_id, name, name_key, time_zone_id, is_default, minimum_chunk_minutes, maximum_chunk_minutes, buffer_minutes, rank, created_at, updated_at) values
  ('sched-4', '22222222-2222-2222-2222-222222222222', 'Focus again', 'focus-again', 'Asia/Kolkata', true, 30, 120, 15, 4, 1, 1);
\echo 'proof 2b-2 result: ok (new default accepted once the old one is archived)'
-- and now the new default is the only one the index sees
insert into work_schedules (id, workspace_id, name, name_key, time_zone_id, is_default, minimum_chunk_minutes, maximum_chunk_minutes, buffer_minutes, rank, created_at, updated_at) values
  ('sched-5', '22222222-2222-2222-2222-222222222222', 'Focus again again', 'focus-again-again', 'Asia/Kolkata', true, 30, 120, 15, 5, 1, 1);
\echo 'proof 2b-3 result: expected unique-violation error above (sched-4 is the active default now)'

\echo '=== proof 3: workspace scoping — same name_key in another workspace is fine'
insert into workspaces (id, name, owner_user_id, created_at, updated_at) values
  ('55555555-5555-5555-5555-555555555555', 'Other', '11111111-1111-1111-1111-111111111111', 1, 1);
insert into projects (id, workspace_id, name, name_key, rank, is_default, created_at, updated_at) values
  ('proj-b', '55555555-5555-5555-5555-555555555555', 'Acme launch', 'acme-launch', 1, false, 1, 1);
insert into saved_views (id, project_id, workspace_id, name, name_key, surface, filters_json, grouping, sort_json, columns_json, collapsed_ids_json, pinned, rank, created_at, updated_at) values
  ('view-6', 'proj-b', '55555555-5555-5555-5555-555555555555', 'Board', 'board', 'board', '{}', 'none', '[]', '[]', '[]', false, 1, 1, 1);
\echo 'proof 3 result: ok'

\echo '=== proof 4: a task in a foreign project cannot borrow this project'"'"'s stage (composite FK)'
insert into tasks (id, project_id, stage_id, workspace_id, title, rank, effort_minutes, progress, created_at, updated_at) values
  ('task-3', 'proj-b', 'stage-1', '55555555-5555-5555-5555-555555555555', 'Cross-project', 1, 60, 0, 1, 1);
\echo 'proof 4 result: expected foreign-key error above'
\set ON_ERROR_STOP on
\echo 'ALL PROOFS COMPLETE'
