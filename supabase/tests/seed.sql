-- Two accounts; live rows, a fresh tombstone and stale ones on both sides. Applied as the
-- superuser, because the fixtures are the one thing in this run that is allowed to ignore RLS.

insert into auth.users (id) values
    ('11111111-1111-1111-1111-111111111111'),
    ('22222222-2222-2222-2222-222222222222');

insert into public.tasks
    (user_id, id, title, priority, created_at, sort_order, updated_at, deleted_at, server_updated_at)
values
    ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001',
     'live', 1, now(), 0, now(), null, now()),
    ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000002',
     'fresh tombstone', 1, now(), 0, now(), now(), now()),
    ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000003',
     'stale tombstone', 1, now(), 0, now(), now(), now()),
    ('22222222-2222-2222-2222-222222222222', '00000000-0000-0000-0000-000000000004',
     'other account, stale tombstone', 1, now(), 0, now(), now(), now()),
    ('22222222-2222-2222-2222-222222222222', '00000000-0000-0000-0000-000000000005',
     'other account, old and live', 1, now(), 0, now(), null, now());

insert into public.projects
    (user_id, id, name, color_hex, sort_order, updated_at, deleted_at, server_updated_at)
values
    ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-0000000000a1',
     'live', '#ffffff', 0, now(), null, now()),
    ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-0000000000a2',
     'stale tombstone', '#ffffff', 0, now(), now(), now());

insert into public.sections
    (user_id, id, project_id, name, sort_order, updated_at, deleted_at, server_updated_at)
values
    ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-0000000000b1',
     '00000000-0000-0000-0000-0000000000a1', 'live', 0, now(), null, now()),
    ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-0000000000b2',
     '00000000-0000-0000-0000-0000000000a1', 'stale tombstone', 0, now(), now(), now());

-- The stale-write trigger stamps `server_updated_at` itself and drops any UPDATE that does not
-- raise `updated_at`, so it has to be off to age the rows meant to sit past the horizon. Which is
-- also the proof that nothing but the server's own clock ever writes that column.
alter table public.tasks disable trigger tasks_reject_stale;
alter table public.projects disable trigger projects_reject_stale;
alter table public.sections disable trigger sections_reject_stale;

update public.tasks set server_updated_at = now() - interval '200 days'
    where id in ('00000000-0000-0000-0000-000000000003',
                 '00000000-0000-0000-0000-000000000004',
                 '00000000-0000-0000-0000-000000000005');
update public.projects set server_updated_at = now() - interval '200 days'
    where id = '00000000-0000-0000-0000-0000000000a2';
update public.sections set server_updated_at = now() - interval '200 days'
    where id = '00000000-0000-0000-0000-0000000000b2';

alter table public.tasks enable trigger tasks_reject_stale;
alter table public.projects enable trigger projects_reject_stale;
alter table public.sections enable trigger sections_reject_stale;
