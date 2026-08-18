-- Sections, the server half (docs/adr/0002-supabase-sync.md).
--
-- A third synced table beside `tasks` and `projects`, and one new column on `tasks`. A section is a
-- heading inside one project's list, so `sections.project_id` and `tasks.section_id` are plain
-- uuids with no foreign key — the same rule the first migration states for `tasks.project_id`:
-- rows arrive in batches in whatever order the push happens to send, and a task whose section has
-- not arrived yet is repaired on the *client* rather than rejected here.
--
-- Idempotent on purpose, the same as the two migrations before it: this file is applied with
-- `supabase db push` *or* pasted into the SQL editor of a project that may already carry half of
-- it.

-- ── sections ───────────────────────────────────────────────────────────────────────

create table if not exists public.sections (
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    id uuid not null,
    -- The project this section groups. A section never nests and is never project-less.
    project_id uuid not null,
    name text not null,
    sort_order integer not null,
    -- The device's clock. What the merge resolves conflicts by, on both sides.
    updated_at timestamptz not null,
    -- Tombstone. A deleted row is a row with a version, not an absent row.
    deleted_at timestamptz,
    -- The server's clock, written only by the trigger below — see `tasks.server_updated_at`.
    server_updated_at timestamptz not null default now(),
    primary key (user_id, id)
);

create index if not exists sections_by_server_updated_at
    on public.sections (user_id, server_updated_at);

-- The task's half of the link. Added rather than declared, because `public.tasks` already exists
-- wherever this project has ever synced.
alter table public.tasks add column if not exists section_id uuid;

-- ── Last writer wins, enforced here as well as on the client ───────────────────────
--
-- Verbatim the shape of `reject_stale_task`/`reject_stale_project` — see the first migration for
-- why a stale write returns NULL rather than raising, and why ties keep the incumbent.

create or replace function public.reject_stale_section()
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
        select s.updated_at into stored
        from public.sections s
        where s.user_id = new.user_id and s.id = new.id;
    end if;

    if stored is not null and new.updated_at <= stored then
        return null;
    end if;

    new.server_updated_at := now();
    return new;
end;
$$;

drop trigger if exists sections_reject_stale on public.sections;
create trigger sections_reject_stale
    before insert or update on public.sections
    for each row execute function public.reject_stale_section();

-- ── Row-level security ─────────────────────────────────────────────────────────────
--
-- `force` matters as much as `enable` here as it does on the other two: without it the table owner
-- bypasses every policy.

alter table public.sections enable row level security;
alter table public.sections force row level security;

drop policy if exists sections_owner on public.sections;
create policy sections_owner on public.sections
    for all
    to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- Nothing is readable signed out. Stated rather than assumed: Supabase grants `anon` table
-- privileges by default, and only the absence of a policy for that role stops it.
revoke all on public.sections from anon;

-- ── Realtime ───────────────────────────────────────────────────────────────────────
--
-- The same two changes the realtime migration makes for the other tables: join the publication and
-- carry a full replica identity, without which an RLS-checked UPDATE payload — which is what every
-- deletion travels on — is dropped.

do $$
begin
    if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        create publication supabase_realtime;
    end if;

    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'sections'
    ) then
        alter publication supabase_realtime add table public.sections;
    end if;
end
$$;

alter table public.sections replica identity full;
