-- Cadence sync, the server half (docs/adr/0002-supabase-sync.md).
--
-- Committed here rather than left in the dashboard because it is the only part of the system the
-- Kotlin test suite cannot reach: a trigger and two policies that the whole "push everything above
-- the watermark" protocol depends on being exactly right. Applied with `supabase db push`, or by
-- pasting it into the SQL editor of a fresh project.
--
-- Two tables mirroring the domain records, and nothing else. No foreign keys between them on
-- purpose: rows arrive in batches in whatever order the push happens to send, and a task whose
-- project has not arrived yet is repaired on the *client* (BackupCodec's rules, reused by the
-- merge) rather than rejected here.

-- ── tasks ──────────────────────────────────────────────────────────────────────────

create table if not exists public.tasks (
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    id uuid not null,
    title text not null,
    notes text,
    priority smallint not null,
    project_id uuid,
    parent_id uuid,
    spawned_from_id uuid,
    -- The published shape, not the storage shape: a date is a date here, not the epoch-day
    -- integer SqlDelightStores keeps (decision 5). `20309` means nothing in the table editor.
    due_date date,
    due_time time,
    reminder_time time,
    completed_at timestamptz,
    created_at timestamptz not null,
    sort_order integer not null,
    -- Recurrence as an object, deliberately not the packed `v1;key=value` column.
    recurrence jsonb,
    -- The device's clock. What the merge resolves conflicts by, on both sides.
    updated_at timestamptz not null,
    -- Tombstone. A deleted row is a row with a version, not an absent row.
    deleted_at timestamptz,
    -- The server's clock, written only by the trigger below. What the pull cursor reads, so a
    -- device with a wrong clock can lose a conflict but can never make itself invisible.
    server_updated_at timestamptz not null default now(),
    -- Keyed by (user, id) rather than id alone: ids are minted per device and a recurring
    -- successor's id is *derived*, so two accounts could in principle land on the same one.
    primary key (user_id, id)
);

create table if not exists public.projects (
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    id uuid not null,
    name text not null,
    color_hex text not null,
    parent_id uuid,
    sort_order integer not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    server_updated_at timestamptz not null default now(),
    primary key (user_id, id)
);

-- The pull reads "everything at or after the cursor, oldest first", so this is the only index the
-- protocol actually needs.
create index if not exists tasks_by_server_updated_at
    on public.tasks (user_id, server_updated_at);
create index if not exists projects_by_server_updated_at
    on public.projects (user_id, server_updated_at);

-- ── Last writer wins, enforced here as well as on the client ───────────────────────
--
-- A stale write is skipped by returning NULL, which drops *that row* without failing the
-- statement — so one stale record cannot poison a batch of two hundred. That is what makes the
-- push safe to run over everything above the watermark: a row that arrived *from* the server is
-- above the watermark and gets pushed straight back, this trigger sees an equal timestamp, and
-- the round is a no-op. No `dirty` column anywhere as a result.
--
-- Ties keep the incumbent (`<=`, not `<`), the same rule the client's merge applies.

create or replace function public.reject_stale_task()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $$
declare
    stored timestamptz;
begin
    if tg_op = 'UPDATE' then
        stored := old.updated_at;
    else
        select t.updated_at into stored
        from public.tasks t
        where t.user_id = new.user_id and t.id = new.id;
    end if;

    if stored is not null and new.updated_at <= stored then
        return null;
    end if;

    new.server_updated_at := now();
    return new;
end;
$$;

create or replace function public.reject_stale_project()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $$
declare
    stored timestamptz;
begin
    if tg_op = 'UPDATE' then
        stored := old.updated_at;
    else
        select p.updated_at into stored
        from public.projects p
        where p.user_id = new.user_id and p.id = new.id;
    end if;

    if stored is not null and new.updated_at <= stored then
        return null;
    end if;

    new.server_updated_at := now();
    return new;
end;
$$;

drop trigger if exists tasks_reject_stale on public.tasks;
create trigger tasks_reject_stale
    before insert or update on public.tasks
    for each row execute function public.reject_stale_task();

drop trigger if exists projects_reject_stale on public.projects;
create trigger projects_reject_stale
    before insert or update on public.projects
    for each row execute function public.reject_stale_project();

-- ── Row-level security ─────────────────────────────────────────────────────────────
--
-- The anon key ships in the app; it identifies the project and nothing more. *This* is what
-- protects the rows, which is why it is in the migration rather than left to a dashboard toggle
-- someone forgets. `force` matters too: without it the table owner bypasses every policy.

alter table public.tasks enable row level security;
alter table public.tasks force row level security;
alter table public.projects enable row level security;
alter table public.projects force row level security;

drop policy if exists tasks_owner on public.tasks;
create policy tasks_owner on public.tasks
    for all
    to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

drop policy if exists projects_owner on public.projects;
create policy projects_owner on public.projects
    for all
    to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- Nothing is readable signed out. Stated rather than assumed: Supabase grants `anon` table
-- privileges by default, and only the absence of a policy for that role stops it.
revoke all on public.tasks from anon;
revoke all on public.projects from anon;
