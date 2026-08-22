-- Tags, the server half (docs/adr/0004-tags.md).
--
-- A fourth synced table beside `tasks`, `projects` and `sections`, and one new column on `tasks`.
--
-- **There is no join table**, deliberately: which tasks wear a tag is `tasks.tag_ids`, a `uuid[]`
-- on the task itself. See ADR 0004 for the trade — one row per rename or delete instead of one per
-- labelled task, at the price of last-writer-wins over a task's whole label set. The mirror
-- follows the client rather than normalising, because the merge rule is the client's: a shape the
-- two disagree about is a shape that has to be reconciled somewhere, and there is nowhere.
--
-- `tag_ids` carries no foreign key and no check, the same rule the first migration states for
-- `tasks.project_id`: rows arrive in batches in whatever order the push sends them, and a task
-- naming a tag that has not arrived yet is repaired on the *client* rather than rejected here.
--
-- Idempotent on purpose, like every migration in this directory: applied with `supabase db push`,
-- or pasted into the SQL editor of a project that may already carry half of it.

-- ── tags ───────────────────────────────────────────────────────────────────────────

create table if not exists public.tags (
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    id uuid not null,
    name text not null,
    color_hex text not null,
    sort_order integer not null,
    -- The device's clock. What the merge resolves conflicts by, on both sides.
    updated_at timestamptz not null,
    -- Tombstone. A deleted row is a row with a version, not an absent row.
    deleted_at timestamptz,
    -- The server's clock, written only by the trigger below — see `tasks.server_updated_at`.
    server_updated_at timestamptz not null default now(),
    primary key (user_id, id)
);

create index if not exists tags_by_server_updated_at
    on public.tags (user_id, server_updated_at);

-- The task's half of the link. Added rather than declared, because `public.tasks` already exists
-- wherever this project has ever synced.
--
-- Defaulted to the empty array rather than left nullable: the client always sends the column
-- (`encodeDefaults` is on — see `RemoteRecords.kt`), so a null could only ever come from a row
-- written before this migration, and `coalesce` in every reader is a worse deal than a default.
alter table public.tasks add column if not exists tag_ids uuid[] not null default '{}';

-- What "everything labelled X" costs on the server, for the web client ADR 0002 keeps room for.
-- GIN is the index type for array containment (`tag_ids @> array[...]`); btree cannot answer it.
create index if not exists tasks_by_tag_ids on public.tasks using gin (tag_ids);

-- ── Last writer wins, enforced here as well as on the client ───────────────────────
--
-- Verbatim the shape of `reject_stale_task`/`reject_stale_project`/`reject_stale_section` — see
-- the first migration for why a stale write returns NULL rather than raising, and why ties keep
-- the incumbent.

create or replace function public.reject_stale_tag()
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
        from public.tags t
        where t.user_id = new.user_id and t.id = new.id;
    end if;

    if stored is not null and new.updated_at <= stored then
        return null;
    end if;

    new.server_updated_at := now();
    return new;
end;
$$;

drop trigger if exists tags_reject_stale on public.tags;
create trigger tags_reject_stale
    before insert or update on public.tags
    for each row execute function public.reject_stale_tag();

-- ── Row-level security ─────────────────────────────────────────────────────────────
--
-- `force` matters as much as `enable` here as it does on the other three: without it the table
-- owner bypasses every policy.

alter table public.tags enable row level security;
alter table public.tags force row level security;

drop policy if exists tags_owner on public.tags;
create policy tags_owner on public.tags
    for all
    to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- Nothing is readable signed out. Stated rather than assumed: Supabase grants `anon` table
-- privileges by default, and only the absence of a policy for that role stops it.
revoke all on public.tags from anon;

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
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'tags'
    ) then
        alter publication supabase_realtime add table public.tags;
    end if;
end
$$;

alter table public.tags replica identity full;

-- ── The sweep learns about tags ────────────────────────────────────────────────────
--
-- The same extension `20260819120000_sections_tombstone_gc.sql` made for sections, and for the
-- same reason: a synced table whose tombstones nobody collects is the one part of the mirror that
-- grows without bound, and nobody would notice except by reading the table.
--
-- In this file rather than in a separate one only because the table it collects from is created
-- here — the sections split existed because that table shipped before the sweep knew about it.

create index if not exists tags_tombstones
    on public.tags (server_updated_at)
    where deleted_at is not null;

-- `create or replace` cannot add a column to a function's result, so the old one is dropped first.
-- Nothing holds a reference across that: `cron.schedule` stores the command as text and resolves
-- the name at run time, and the job's command string is unchanged.
--
-- `tags_collected` is appended rather than slotted in, so a query written against the three-column
-- shape still reads the same numbers out of the same positions.

drop function if exists public.collect_tombstones(interval);

create or replace function public.collect_tombstones(horizon interval default interval '90 days')
returns table (
    tasks_collected bigint,
    projects_collected bigint,
    sections_collected bigint,
    tags_collected bigint
)
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    cutoff timestamptz := now() - horizon;
begin
    if horizon < interval '90 days' then
        raise exception 'tombstone horizon % is shorter than the 90 days the clients assume', horizon;
    end if;

    -- Counted with `get diagnostics` rather than `returning`, which would hand row *contents* to a
    -- job that has no business reading them — the counts are the whole result.
    delete from public.tasks
    where deleted_at is not null and server_updated_at < cutoff;
    get diagnostics tasks_collected = row_count;

    delete from public.projects
    where deleted_at is not null and server_updated_at < cutoff;
    get diagnostics projects_collected = row_count;

    delete from public.sections
    where deleted_at is not null and server_updated_at < cutoff;
    get diagnostics sections_collected = row_count;

    -- A task that outlives a swept tag keeps its id in `tag_ids`, naming nothing. That is the same
    -- repair the client already makes for a label it has not pulled yet, and the same reason
    -- deleting a tag rewrites no task in the first place.
    delete from public.tags
    where deleted_at is not null and server_updated_at < cutoff;
    get diagnostics tags_collected = row_count;

    return next;
end;
$$;

-- The dropped function took its grants with it, so they are restated rather than assumed: a
-- `security definer` function is executable by `public` the moment it exists, and this one deletes
-- rows across every account.
revoke all on function public.collect_tombstones(interval) from public;
revoke all on function public.collect_tombstones(interval) from anon, authenticated;

-- The policies the sweep runs under on the new table, for whichever role owns this migration —
-- `postgres` under `supabase db push` and in the SQL editor alike, hence `current_user` rather than
-- a literal. Two of them, and not `for all`: a `DELETE ... WHERE` reads the columns it filters on,
-- so the SELECT policy is what stops the statement from being legal, matching nothing, and
-- reporting success. All this role gains is the right to see and hard-delete a row that is already
-- a tombstone; a live tag stays as invisible to the job as it is to another account.

drop policy if exists tags_collect_tombstones on public.tags;
drop policy if exists tags_see_tombstones on public.tags;

do $$
begin
    execute format(
        'create policy tags_see_tombstones on public.tags'
        || ' for select to %I using (deleted_at is not null)', current_user);
    execute format(
        'create policy tags_collect_tombstones on public.tags'
        || ' for delete to %I using (deleted_at is not null)', current_user);
end
$$;
