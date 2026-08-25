-- Cadence sync, the server half, on Neon (docs/adr/0005-neon-sync.md; the protocol is still
-- docs/adr/0002-supabase-sync.md).
--
-- Committed here rather than left in the console because it is the only part of the system the
-- Kotlin test suite cannot reach: four triggers and four policies that the whole "push everything
-- above the watermark" protocol depends on being exactly right. Applied with `bash neon/migrate.sh`
-- (needs `CADENCE_NEON_DB_URL`), or by pasting it into the SQL editor of the Neon console —
-- idempotent on purpose, because a pasted file lands on a project that may already carry half of
-- it.
--
-- One consolidated baseline rather than the six files the Supabase project accreted: this schema
-- starts on a fresh Neon project with no history to preserve. What did NOT come along, and why:
--   * the realtime publication — Neon has no realtime; the client polls (ADR 0005).
--   * pg_cron and `collect_tombstones()` — Neon compute scales to zero and cron only fires while
--     it is awake, so the sweep moved into the client: after a successful push, at most daily,
--     each device DELETEs its own tombstones past the 90-day horizon through the Data API. The
--     owner policy below is what authorises that; the partial indexes are what make it cheap.
--   * `references auth.users` — Neon Auth mirrors users into `neon_auth.users_sync`, whose id is
--     text and which lags the auth service; a foreign key against either is a constraint on row
--     arrival order, which is exactly what this schema has never had (see the tasks comment).
--
-- Four tables mirroring the domain records, and nothing else. No foreign keys between them on
-- purpose: rows arrive in batches in whatever order the push happens to send, and a task whose
-- project has not arrived yet is repaired on the *client* (BackupCodec's rules, reused by the
-- merge) rather than rejected here.

-- ── Preconditions ──────────────────────────────────────────────────────────────────
--
-- Everything below leans on what enabling the Data API provisions: the `auth.user_id()` function
-- (pg_session_jwt, reading the JWT's `sub`) and the `authenticated`/`anonymous` roles PostgREST
-- runs requests as. Enable the Data API for the branch *before* running this file; the check is
-- here so a wrong order fails with a sentence rather than with a missing-function error mid-file.

do $$
begin
    if not exists (
        select 1
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'auth' and p.proname = 'user_id'
    ) then
        raise exception 'auth.user_id() is missing - enable the Data API for this branch in the '
            'Neon console before applying migrations (it installs pg_session_jwt and the roles)';
    end if;
    if not exists (select 1 from pg_roles where rolname = 'authenticated') then
        raise exception 'role "authenticated" is missing - enable the Data API for this branch '
            'in the Neon console before applying migrations';
    end if;
end
$$;

-- ── tasks ──────────────────────────────────────────────────────────────────────────

create table if not exists public.tasks (
    -- Filled by the JWT, never by the client: the DTOs deliberately carry no user_id
    -- (RemoteRecords.kt). pg_session_jwt's auth.user_id() returns the `sub` claim as text,
    -- hence the cast.
    user_id uuid not null default (auth.user_id())::uuid,
    id uuid not null,
    title text not null,
    notes text,
    priority smallint not null,
    project_id uuid,
    parent_id uuid,
    spawned_from_id uuid,
    section_id uuid,
    -- The published shape, not the storage shape: a date is a date here, not the epoch-day
    -- integer SqlDelightStores keeps (ADR 0002, decision 5). `20309` means nothing in the
    -- table editor.
    due_date date,
    due_time time,
    reminder_time time,
    completed_at timestamptz,
    created_at timestamptz not null,
    sort_order integer not null,
    -- Recurrence as an object, deliberately not the packed `v1;key=value` column.
    recurrence jsonb,
    -- Which tags this task wears — a uuid[] on the task, deliberately no join table (ADR 0004).
    -- No foreign key and no check: a task naming a tag that has not arrived yet is repaired on
    -- the client. Defaulted to the empty array rather than left nullable: the client always
    -- sends the column (`encodeDefaults` is on — see RemoteRecords.kt).
    tag_ids uuid[] not null default '{}',
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
    user_id uuid not null default (auth.user_id())::uuid,
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

create table if not exists public.sections (
    user_id uuid not null default (auth.user_id())::uuid,
    id uuid not null,
    -- The project this section groups. A section never nests and is never project-less.
    project_id uuid not null,
    name text not null,
    sort_order integer not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    server_updated_at timestamptz not null default now(),
    primary key (user_id, id)
);

create table if not exists public.tags (
    user_id uuid not null default (auth.user_id())::uuid,
    id uuid not null,
    name text not null,
    color_hex text not null,
    sort_order integer not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    server_updated_at timestamptz not null default now(),
    primary key (user_id, id)
);

-- ── Indexes ────────────────────────────────────────────────────────────────────────

-- The pull reads "everything at or after the cursor, oldest first", so these are the only
-- indexes the round-trip protocol needs.
create index if not exists tasks_by_server_updated_at
    on public.tasks (user_id, server_updated_at);
create index if not exists projects_by_server_updated_at
    on public.projects (user_id, server_updated_at);
create index if not exists sections_by_server_updated_at
    on public.sections (user_id, server_updated_at);
create index if not exists tags_by_server_updated_at
    on public.tags (user_id, server_updated_at);

-- What "everything labelled X" costs on the server, for the web client ADR 0002 keeps room for.
-- GIN is the index type for array containment (`tag_ids @> array[...]`); btree cannot answer it.
create index if not exists tasks_by_tag_ids on public.tasks using gin (tag_ids);

-- What the client-driven sweep's DELETE filters on: tombstones past the horizon, by the server's
-- clock. Partial, because tombstones are the sliver of the table the sweep ever reads.
create index if not exists tasks_tombstones
    on public.tasks (server_updated_at) where deleted_at is not null;
create index if not exists projects_tombstones
    on public.projects (server_updated_at) where deleted_at is not null;
create index if not exists sections_tombstones
    on public.sections (server_updated_at) where deleted_at is not null;
create index if not exists tags_tombstones
    on public.tags (server_updated_at) where deleted_at is not null;

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

drop trigger if exists tasks_reject_stale on public.tasks;
create trigger tasks_reject_stale
    before insert or update on public.tasks
    for each row execute function public.reject_stale_task();

drop trigger if exists projects_reject_stale on public.projects;
create trigger projects_reject_stale
    before insert or update on public.projects
    for each row execute function public.reject_stale_project();

drop trigger if exists sections_reject_stale on public.sections;
create trigger sections_reject_stale
    before insert or update on public.sections
    for each row execute function public.reject_stale_section();

drop trigger if exists tags_reject_stale on public.tags;
create trigger tags_reject_stale
    before insert or update on public.tags
    for each row execute function public.reject_stale_tag();

-- ── Row-level security ─────────────────────────────────────────────────────────────
--
-- The Stack publishable key ships in the app; it identifies the project and nothing more. *This*
-- is what protects the rows, which is why it is in the migration rather than left to a console
-- toggle someone forgets. `force` matters too: without it the table owner bypasses every policy.
--
-- One policy per table, `for all`: the owner reads, upserts and — since the sweep moved into the
-- client — DELETEs their own rows. The 90-day filter on that DELETE is the client's discipline,
-- not the server's; what RLS guarantees is only that no account can touch another's rows, which
-- is the same guarantee the Supabase schema gave.

alter table public.tasks enable row level security;
alter table public.tasks force row level security;
alter table public.projects enable row level security;
alter table public.projects force row level security;
alter table public.sections enable row level security;
alter table public.sections force row level security;
alter table public.tags enable row level security;
alter table public.tags force row level security;

drop policy if exists tasks_owner on public.tasks;
create policy tasks_owner on public.tasks
    for all
    to authenticated
    using (user_id = (auth.user_id())::uuid)
    with check (user_id = (auth.user_id())::uuid);

drop policy if exists projects_owner on public.projects;
create policy projects_owner on public.projects
    for all
    to authenticated
    using (user_id = (auth.user_id())::uuid)
    with check (user_id = (auth.user_id())::uuid);

drop policy if exists sections_owner on public.sections;
create policy sections_owner on public.sections
    for all
    to authenticated
    using (user_id = (auth.user_id())::uuid)
    with check (user_id = (auth.user_id())::uuid);

drop policy if exists tags_owner on public.tags;
create policy tags_owner on public.tags
    for all
    to authenticated
    using (user_id = (auth.user_id())::uuid)
    with check (user_id = (auth.user_id())::uuid);

-- ── Grants ─────────────────────────────────────────────────────────────────────────
--
-- Unlike Supabase, Neon grants the Data API roles nothing by default, so the grant is stated.
-- `authenticated` gets the four tables and only those — not `schema_migrations`, which therefore
-- never shows up in the Data API. Nothing is readable signed out: `anonymous` gets no grant, and
-- the revoke makes that an invariant a later default cannot quietly undo.

grant usage on schema public to authenticated;
grant select, insert, update, delete
    on public.tasks, public.projects, public.sections, public.tags
    to authenticated;
revoke all on public.tasks, public.projects, public.sections, public.tags from anonymous;
