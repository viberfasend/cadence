-- Two accounts; live rows, a fresh tombstone and stale ones on both sides. Applied as the
-- superuser, because the fixtures are the one thing in this run that is allowed to ignore RLS.
-- No users table to seed: with the auth.users FK gone, an account is just a uuid in user_id.

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

insert into public.tags
    (user_id, id, name, color_hex, sort_order, updated_at, deleted_at, server_updated_at)
values
    ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-0000000000c1',
     'live', '#ffffff', 0, now(), null, now()),
    ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-0000000000c2',
     'stale tombstone', '#ffffff', 0, now(), now(), now());

-- The stale-write trigger stamps `server_updated_at` itself and drops any UPDATE that does not
-- raise `updated_at`, so it has to be off to age the rows meant to sit past the horizon. Which is
-- also the proof that nothing but the server's own clock ever writes that column.
alter table public.tasks disable trigger tasks_reject_stale;
alter table public.projects disable trigger projects_reject_stale;
alter table public.sections disable trigger sections_reject_stale;
alter table public.tags disable trigger tags_reject_stale;

update public.tasks set server_updated_at = now() - interval '200 days'
    where id in ('00000000-0000-0000-0000-000000000003',
                 '00000000-0000-0000-0000-000000000004',
                 '00000000-0000-0000-0000-000000000005');
update public.projects set server_updated_at = now() - interval '200 days'
    where id = '00000000-0000-0000-0000-0000000000a2';
update public.sections set server_updated_at = now() - interval '200 days'
    where id = '00000000-0000-0000-0000-0000000000b2';
update public.tags set server_updated_at = now() - interval '200 days'
    where id = '00000000-0000-0000-0000-0000000000c2';

-- The live task wears both tags, so the run can check the thing the packed column trades away: a
-- swept tag leaves its id behind on the task, and nothing repairs that server-side.
--
-- Inside the triggers-off block, not beside the inserts above. `tasks_reject_stale` drops any
-- UPDATE that does not raise `updated_at`, and an UPDATE that only sets `tag_ids` does not — so
-- run with the trigger on, this wrote nothing at all and the check read an empty array.
update public.tasks
set tag_ids = array['00000000-0000-0000-0000-0000000000c1',
                    '00000000-0000-0000-0000-0000000000c2']::uuid[]
where id = '00000000-0000-0000-0000-000000000001';

alter table public.tasks enable trigger tasks_reject_stale;
alter table public.projects enable trigger projects_reject_stale;
alter table public.sections enable trigger sections_reject_stale;
alter table public.tags enable trigger tags_reject_stale;
