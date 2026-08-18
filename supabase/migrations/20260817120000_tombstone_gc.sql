-- Server-side tombstone collection (docs/adr/0002-supabase-sync.md, phase 4).
--
-- A deletion in Cadence is a tombstone: the row stays, `deleted_at` is stamped, and it travels
-- like any other version so the other device cannot mistake "deleted" for "never heard of it".
-- Kept forever, those rows are the only part of the mirror that grows without bound — every task
-- ever ticked off and deleted is still paged over by a fresh install's first pull.
--
-- The client already collects its own after 90 days (`CadenceSyncEngine.TOMBSTONE_HORIZON`), but
-- it can only ever collect *its* copy: the server's row is what the next device pulls, and no
-- device is allowed to hard-delete another's. So the server collects its own, on the same horizon,
-- from a `pg_cron` job that runs nightly.
--
-- The 90 days is the whole safety argument, and it is the accepted loss ADR 0002 already names: a
-- device offline longer than the horizon never learns about the deletion, because the tombstone it
-- would have pulled is gone. Shortening this shortens that window; do not.
--
-- Idempotent like the two migrations before it — applied with `supabase db push`, or pasted into
-- the SQL editor of a project that may already carry half of it.

-- ── pg_cron ────────────────────────────────────────────────────────────────────────
--
-- The extension creates schema `cron` itself. It can only be installed into the database named by
-- `cron.database_name` (`postgres`, which is the one Supabase gives you), so this fails loudly on
-- a second database rather than scheduling a job that never fires.

create extension if not exists pg_cron;

-- Supabase's own snippet. Harmless where the grants already hold; skipped rather than fatal where
-- this migration is applied by a role that may not grant them, since owning `cron` is the normal
-- case and not owning it means the schedule below will fail with a clearer message anyway.
do $$
begin
    grant usage on schema cron to postgres;
    grant all privileges on all tables in schema cron to postgres;
exception
    when insufficient_privilege or undefined_object then
        raise notice 'cron grants skipped: %', sqlerrm;
end
$$;

-- ── What the sweep scans ───────────────────────────────────────────────────────────
--
-- The pull's index is `(user_id, server_updated_at)` and the sweep has no user to lead with, so it
-- gets its own — partial, so it indexes only the tombstones and stays a rounding error on a table
-- that is overwhelmingly live rows.
--
-- `server_updated_at`, not `deleted_at`: `deleted_at` is a device's clock and a device with a wrong
-- one could hand over a tombstone that is already older than the horizon and have it collected
-- before the other device ever pulls it. The server's clock is what the pull cursor reads, so a row
-- older than the horizon by *that* clock is a row every device has had 90 days to see.

create index if not exists tasks_tombstones
    on public.tasks (server_updated_at)
    where deleted_at is not null;
create index if not exists projects_tombstones
    on public.projects (server_updated_at)
    where deleted_at is not null;

-- ── The sweep ──────────────────────────────────────────────────────────────────────
--
-- Runs across every account at once — the rows are only reachable through RLS by the account that
-- owns them, but staleness is not a per-account question and a job per user would be one cron entry
-- per sign-up.
--
-- `security definer` because the job runs unattended and both tables `force` row level security,
-- which subjects even the owner to the policies; the two policies below are what let this through,
-- and they are narrower than the alternative (a role with `bypassrls`) because they can only ever
-- match a row that is already a tombstone.
--
-- Returns the counts so a human can run it by hand and see what it did:
--     select * from public.collect_tombstones();
--     select * from public.collect_tombstones(interval '365 days');   -- a gentler one-off

-- Dropped first, not merely replaced: `create or replace` cannot change a function's result
-- columns, and a later migration widens them (sections). Re-applying this file over that wider
-- function — which is what pasting the whole directory into a SQL editor a second time does —
-- would otherwise fail here. The files run in name order, so the later one puts its own shape
-- back in the same pass.
drop function if exists public.collect_tombstones(interval);

create or replace function public.collect_tombstones(horizon interval default interval '90 days')
returns table (tasks_collected bigint, projects_collected bigint)
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

    return next;
end;
$$;

-- A `security definer` function is executable by `public` the moment it exists, and this one
-- deletes rows across every account. Nothing but the job needs to call it, so nothing else may.
revoke all on function public.collect_tombstones(interval) from public;
revoke all on function public.collect_tombstones(interval) from anon, authenticated;

-- The policies the sweep runs under, for whichever role owns this migration (`postgres` under
-- `supabase db push` and in the SQL editor alike, hence the `current_user` rather than a literal).
-- Deliberately not `for all`: the only thing this role gains over the policies above it is the
-- right to *see* and hard-delete a row that is already a tombstone. A live task stays as invisible
-- to the job as it is to another account.
--
-- Two policies rather than one, because a `DELETE ... WHERE` reads the columns it filters on and
-- Postgres therefore applies the `SELECT` policies to it as well. With the delete policy alone the
-- statement is legal, matches nothing, and reports success — which is exactly how this migration
-- would have shipped a job that swept nothing every night.
drop policy if exists tasks_collect_tombstones on public.tasks;
drop policy if exists tasks_see_tombstones on public.tasks;
drop policy if exists projects_collect_tombstones on public.projects;
drop policy if exists projects_see_tombstones on public.projects;

do $$
begin
    execute format(
        'create policy tasks_see_tombstones on public.tasks'
        || ' for select to %I using (deleted_at is not null)', current_user);
    execute format(
        'create policy tasks_collect_tombstones on public.tasks'
        || ' for delete to %I using (deleted_at is not null)', current_user);
    execute format(
        'create policy projects_see_tombstones on public.projects'
        || ' for select to %I using (deleted_at is not null)', current_user);
    execute format(
        'create policy projects_collect_tombstones on public.projects'
        || ' for delete to %I using (deleted_at is not null)', current_user);
end
$$;

-- ── The schedule ───────────────────────────────────────────────────────────────────
--
-- Nightly at 03:17 UTC. Off the hour on purpose: every cron job anybody ever writes runs at :00,
-- and a shared instance has better things to do at midnight.
--
-- `cron.schedule` replaces a job of the same name, so re-applying this migration re-points the
-- schedule rather than stacking a second copy. Job history lands in `cron.job_run_details`, which
-- is keyed by `jobid` rather than by name:
--     select j.jobname, d.status, d.start_time, d.return_message
--       from cron.job_run_details d join cron.job j using (jobid)
--      where j.jobname like 'cadence-%'
--      order by d.start_time desc;

select cron.schedule(
    'cadence-collect-tombstones',
    '17 3 * * *',
    $$select public.collect_tombstones()$$);

-- `cron.job_run_details` is itself data nobody uses after a week, and pg_cron never trims it — a
-- nightly job writes a row a night forever. A second job collects the log of the first.
select cron.schedule(
    'cadence-collect-cron-history',
    '42 3 * * *',
    $$delete from cron.job_run_details where end_time < now() - interval '7 days'$$);
