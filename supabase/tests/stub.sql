-- Enough of Supabase to apply the real migrations against a stock `postgres:16` image.
--
-- Two things the migrations lean on that a plain Postgres has not got: the `auth` schema Supabase
-- creates (the tables' `user_id` references it, and the policies call `auth.uid()`), and the
-- `anon`/`authenticated` roles they revoke from and grant to.
--
-- `owner_role` stands in for Supabase's `postgres`: it owns the tables and is deliberately
-- `nobypassrls`, so the run exercises the policies rather than skipping them. Whether Supabase's
-- own `postgres` bypasses RLS is exactly the thing not to depend on.

create role anon nologin;
create role authenticated nologin;
create role owner_role login password 'pg' nosuperuser nobypassrls;

create schema auth;
create table auth.users (id uuid primary key);
create function auth.uid() returns uuid language sql stable
as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;

grant usage on schema auth to owner_role, anon, authenticated;
grant select, references on auth.users to owner_role;
grant usage, create on schema public to owner_role;
grant create on database postgres to owner_role;

-- pg_cron is not in the stock image. The schedule call is stubbed so the migration runs verbatim
-- (minus its `create extension`, which run.sh strips): what is under test is the sweep, not
-- pg_cron's own ability to fire it.
create schema cron;
create table cron.job (jobid bigserial primary key, jobname text, schedule text, command text);
create table cron.job_run_details (
    jobid bigint, runid bigserial primary key, status text,
    return_message text, start_time timestamptz, end_time timestamptz);
create function cron.schedule(jobname text, schedule text, command text)
returns bigint language sql as $$ select 1::bigint $$;
grant usage on schema cron to owner_role;
grant all on cron.job, cron.job_run_details to owner_role;
grant execute on function cron.schedule(text, text, text) to owner_role;
