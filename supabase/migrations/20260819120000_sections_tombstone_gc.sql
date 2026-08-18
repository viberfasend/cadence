-- The sweep learns about sections (docs/adr/0002-supabase-sync.md, phase 4).
--
-- `20260817120000_tombstone_gc.sql` collects tombstoned tasks and projects past the same 90-day
-- horizon the clients use. `20260818120000_sections.sql` then added a third synced table and did
-- not extend the sweep, so section tombstones were the one part of the mirror still growing
-- without bound — and the client sweeps its own copy, so nobody would ever have noticed except by
-- reading the table.
--
-- Its own file rather than an edit to the GC migration: that one has already been applied wherever
-- this project syncs, and `supabase db push` will not run it a second time. A migration that has
-- shipped is history; the fix is the next file along.
--
-- Idempotent like every migration here — applied with `supabase db push`, or pasted into the SQL
-- editor of a project that may already carry half of it.

-- ── What the sweep scans ───────────────────────────────────────────────────────────
--
-- Partial, and on `server_updated_at` rather than `deleted_at`, for the reasons the GC migration
-- spells out: the server's clock is the one every device's pull cursor reads, so a row older than
-- the horizon by that clock is a row every device has had 90 days to see.

create index if not exists sections_tombstones
    on public.sections (server_updated_at)
    where deleted_at is not null;

-- ── The sweep ──────────────────────────────────────────────────────────────────────
--
-- `create or replace` cannot add a column to a function's result, so the old one is dropped first.
-- Nothing holds a reference across that: `cron.schedule` stores the command as text and resolves
-- the name at run time, and the job's command string is unchanged.
--
-- `sections_collected` is appended rather than slotted in beside the other two, so a query written
-- against the two-column shape still reads the same numbers out of the same positions.

drop function if exists public.collect_tombstones(interval);

create or replace function public.collect_tombstones(horizon interval default interval '90 days')
returns table (tasks_collected bigint, projects_collected bigint, sections_collected bigint)
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

    -- Sections carry no foreign key — nothing here cascades and the order is free. A task that
    -- outlives a swept section keeps a `section_id` naming nothing, which is the same repair the
    -- client already makes for a heading it has not pulled yet.
    delete from public.sections
    where deleted_at is not null and server_updated_at < cutoff;
    get diagnostics sections_collected = row_count;

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
-- a tombstone; a live section stays as invisible to the job as it is to another account.

drop policy if exists sections_collect_tombstones on public.sections;
drop policy if exists sections_see_tombstones on public.sections;

do $$
begin
    execute format(
        'create policy sections_see_tombstones on public.sections'
        || ' for select to %I using (deleted_at is not null)', current_user);
    execute format(
        'create policy sections_collect_tombstones on public.sections'
        || ' for delete to %I using (deleted_at is not null)', current_user);
end
$$;
