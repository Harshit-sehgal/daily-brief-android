-- Stage 4.2: projects become first-class, and every project owns its board columns.
-- Projects created before this migration (the fixture default project) have no stages,
-- so backfill the standard three. New projects get the same three from the server's
-- DefaultStages helper, so the shapes stay identical.
insert into workflow_stages (id, project_id, workspace_id, name, name_key, rank, created_at, updated_at)
select 'todo', p.id, p.workspace_id, 'To Do', 'to do', 0, p.created_at, p.created_at
from projects p
where not exists (select 1 from workflow_stages s where s.project_id = p.id);

insert into workflow_stages (id, project_id, workspace_id, name, name_key, rank, created_at, updated_at)
select 'in-progress', p.id, p.workspace_id, 'In Progress', 'in progress', 1, p.created_at, p.created_at
from projects p
where not exists (select 1 from workflow_stages s where s.project_id = p.id);

insert into workflow_stages (id, project_id, workspace_id, name, name_key, rank, created_at, updated_at)
select 'done', p.id, p.workspace_id, 'Done', 'done', 2, p.created_at, p.created_at
from projects p
where not exists (select 1 from workflow_stages s where s.project_id = p.id);
