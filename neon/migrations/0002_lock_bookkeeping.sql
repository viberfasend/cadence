-- Take `public.schema_migrations` back out of the Data API, and stop the next table from
-- landing in it by accident (docs/adr/0005-neon-sync.md, amendment 1).
--
-- What went wrong, found by Neon's own advisor after the first real deployment: enabling the
-- Data API leaves a default-privilege entry behind —
--
--     neondb_owner | public | r | authenticated=arwd/neondb_owner
--
-- — so **every table the owner creates in `public` from then on is granted to `authenticated`
-- the moment it exists**, without anyone granting anything. `schema_migrations`, created by
-- `migrate.sh` rather than by a migration, picked that up: any signed-in account could read the
-- migration history over HTTP, and *insert* into it — a row claiming a migration had run would
-- make the next `migrate.sh` skip it.
--
-- The four app tables were never at risk: they carry RLS with an owner policy, so a signed-in
-- account sees only its own rows there, which is the design. The bookkeeping table had neither,
-- because nothing was ever supposed to reach it.
--
-- Three fixes, deliberately overlapping — the first is the repair, the second and third are
-- what stop it happening again:

-- 1. Take the grants away from the table that has them.
revoke all on public.schema_migrations from authenticated;
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anonymous') then
        revoke all on public.schema_migrations from anonymous;
    end if;
end
$$;

-- 2. Belt and braces: RLS with no policy at all, which denies every role that is not the table's
--    owner (and is what Neon's advisor asks for). `enable`, not `force`: `migrate.sh` writes
--    this table as the owner, and on Neon that role carries BYPASSRLS anyway — but the throwaway
--    Postgres the tests run against has an owner that does not, and forcing would lock the
--    migration runner out of its own bookkeeping.
alter table public.schema_migrations enable row level security;

-- 3. Stop granting future tables away. `for role current_user` rather than a literal
--    `neondb_owner`, because the tests apply this file as a role of their own — the same reason
--    the retired Supabase GC migration built its policies with `format()`.
--
--    Consequence worth knowing: a table added to `public` after this is **not** in the Data API
--    until a migration grants it, which is how `0001_cadence_sync.sql` already does it (it
--    states its grants outright rather than relying on the default). Default-deny is the point.
do $$
begin
    execute format(
        'alter default privileges for role %I in schema public revoke all on tables from authenticated',
        current_user);
    execute format(
        'alter default privileges for role %I in schema public revoke all on sequences from authenticated',
        current_user);
    execute format(
        'alter default privileges for role %I in schema public revoke all on functions from authenticated',
        current_user);
end
$$;
