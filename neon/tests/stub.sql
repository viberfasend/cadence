-- Enough of Neon to apply the real migrations against a stock `postgres:16` image.
--
-- Enabling the Data API on a Neon branch provisions three things the migrations lean on that a
-- plain Postgres has not got: the `auth.user_id()` function (pg_session_jwt, reading the JWT's
-- `sub` claim), and the `authenticated`/`anonymous` roles PostgREST runs requests as. The stub
-- supplies all three; `auth.user_id()` reads a settable GUC instead of a JWT, so a test
-- impersonates a user with `set cadence.test_user_id to '<uuid>'` after `set role authenticated`.
--
-- `owner_role` stands in for the Neon role that owns the schema: it owns the tables and is
-- deliberately `nobypassrls`, so the run exercises the policies rather than skipping them.
-- Whether Neon's own owning role bypasses RLS is exactly the thing not to depend on.

create role anonymous nologin;
create role authenticated nologin;
create role owner_role login password 'pg' nosuperuser nobypassrls;

create schema auth;
-- pg_session_jwt returns the sub claim as text; the migrations cast it to uuid themselves.
create function auth.user_id() returns text language sql stable
as $$ select nullif(current_setting('cadence.test_user_id', true), '') $$;

grant usage on schema auth to owner_role, anonymous, authenticated;
-- `with grant option`, because the baseline migration itself grants schema usage to
-- `authenticated` and owner_role is not the schema's owner here.
grant usage, create on schema public to owner_role with grant option;
grant create on database postgres to owner_role;
