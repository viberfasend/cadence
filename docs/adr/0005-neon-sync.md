# ADR 0005 — Move the sync backend from Supabase to Neon

**Status:** accepted
**Date:** 2026-08-25
**Supersedes:** ADR 0002 decisions 6 (supabase-kt as the client) and 12 (realtime as the
accelerant); moves decision 2's account into Neon Auth; makes the server half of the tombstone
sweep client-driven. Everything else in ADR 0002 — the hub-and-spoke shape, the cursors, the
watermark, the stale-write trigger, tombstones, RLS, the sync triggers of decision 11 — is
unchanged and still governs.

## Context

ADR 0002 chose "a Postgres we don't operate", and Supabase was the Postgres. The project moved to
[Neon](https://neon.tech) — same idea, different operator: a fresh Neon project with Neon Auth
enabled, and the Supabase project retired. Neon is plain Postgres plus two managed pieces this
app uses — the **Data API** (a PostgREST endpoint per branch) and **Neon Auth** (Stack Auth under
Neon's console) — and *without* two pieces the Supabase design leaned on: there is no realtime
change feed, and pg_cron on a compute that scales to zero only fires while something else keeps
the compute awake.

There was no data migration, deliberately: the local SQLite on each device is the source of truth
(ADR 0001), so the server starts empty and the first signed-in device pushes its whole database
up — the ordinary full-push path that null cursors and an EPOCH watermark already are.

## Decisions

1. **The wire is the Neon Data API, spoken directly.** PostgREST conventions over plain Ktor
   HTTP (`PostgrestHttp`): `gte.`-filtered ordered selects for the pull, `POST` with
   `Prefer: resolution=merge-duplicates` and `on_conflict=user_id,id` for the push, `DELETE`
   with a filter for the sweep. No SDK — supabase-kt is gone, and nothing replaced it, because
   three requests do not need a library. The DTOs (`RemoteRecords.kt`) are unchanged; the wire
   shape survived the operator.

2. **Auth is Neon Auth (Stack Auth), hand-rolled.** No Kotlin SDK exists, so `StackAuthClient`
   makes the three REST calls the app needs: password sign-in, session refresh, sign-out. The
   account is still created in the console, never in the app (ADR 0002, decision 2). The
   publishable client key is committed exactly as the anon key was: it identifies the project;
   RLS protects the rows.

3. **The session is ours now, refresh machine included.** `StackSession` (access JWT + refresh
   token + email) is serialised into `syncStateRow.session`, where the supabase-kt `UserSession`
   used to live — same column, same "beside the cursors it must stay consistent with" reasoning.
   ADR 0002 decision 6 kept supabase-kt precisely because silent token refresh is where a
   hand-rolled client goes subtly wrong; that risk is accepted now and contained in one small
   class (`SessionTokens`): refresh preemptively near the JWT's `exp`, once more on a 401, under
   a mutex so parallel pulls produce one refresh, persist before use, and read a refused refresh
   as SESSION_EXPIRED while keeping the session stored so Settings can still say whose it was.

   **Upgrade consequence:** an install that was signed in against Supabase finds a stored session
   that no longer decodes, and is therefore signed out — one re-sign-in, from cleared state, and
   the full push/pull that follows is the data migration.

4. **Polling replaces realtime.** Neon has no change feed, and ADR 0002 decision 12 already
   named realtime an accelerant the round must never depend on — every reconnect ran a full
   `syncOnce()`. The replacement is `startForegroundPoll()`: a 60-second `syncOnce()` loop with
   exactly the socket's old lifetime (Android foreground-only; desktop for the process). The
   change a websocket delivered in a second now arrives within a minute while both apps are
   open. Accepted: more requests while foregrounded, a minute of staleness instead of a second.
   The desktop's older 15-minute `syncPollInterval` poll stays, redundant but harmless.

5. **The tombstone sweep is client-driven.** The nightly pg_cron job and `collect_tombstones()`
   were not ported: cron on scale-to-zero compute is a job that mostly does not run. Instead the
   engine, after a successful push and at most daily (the local sweep's own cadence, same
   `lastSweepAt`), issues `DELETE /{table}?deleted_at=not.is.null&server_updated_at=lt.{now-90d}`
   for all four tables, then collects locally. Server first, local second: a failed server sweep
   fails the round and is retried, so the local copy is never collected before the server's.
   The filter is by `server_updated_at` for the retired job's own reason — `deleted_at` is a
   device's clock. The owner `FOR ALL` RLS policy is what authorises it; the 90-day floor the
   old function enforced is now the client's discipline alone, which one device talking to its
   own rows can carry.

6. **No foreign key to the users table.** `user_id uuid default (auth.user_id())::uuid`, cast
   because pg_session_jwt hands back the JWT `sub` as text. The old `references auth.users on
   delete cascade` has no counterpart: Neon Auth mirrors users into `neon_auth.users_sync`,
   whose id is text and which lags the auth service — a constraint on row arrival order, which
   this schema has never tolerated anywhere else.

7. **Migrations are a bash script and a bookkeeping table.** `neon/migrate.sh` applies
   `neon/migrations/*.sql` in filename order, each exactly once, recorded by *filename* in
   `public.schema_migrations` — the Supabase CLI's server-clock version stamps were the mismatch
   that made `db push` refuse, and that failure mode is designed out. The files stay idempotent
   for the SQL-editor paste path. `neon/tests/run.sh` is the ported docker harness. The Data API
   must be enabled before migrating (it provisions `auth.user_id()` and the roles); the baseline
   refuses with a sentence when it was not.

8. **PROJECT_ASLEEP is retired.** Supabase's paused-project 540 has no Neon counterpart; a Neon
   cold start is the first request taking seconds, which generous HTTP timeouts absorb as
   latency. `SyncFailure` is back to OFFLINE / SESSION_EXPIRED / SERVER.

## Consequences

- One class still holds the whole backend coupling (`CadenceSyncEngine` and its two small
  helpers); the engine's public surface changed by exactly one pair of names
  (`startRealtime`/`stopRealtime` → `startForegroundPoll`/`stopForegroundPoll`), so the
  ViewModel, both shells, the widgets and `SyncWorker` are otherwise untouched.
- The engine is now constructible and testable on any JVM — supabase-kt's Android-runtime need
  was why the ViewModel tests sat in `jvmTest`; they may move to `jvmSharedTest` some day, but
  nothing forces it.
- `core`'s only wire dependency is Ktor + kotlinx.serialization; the Ktor version is no longer
  pinned to what supabase-kt resolves.
- Environment/config surface: `CADENCE_NEON_DATA_API_URL`, `CADENCE_STACK_API_URL`,
  `CADENCE_STACK_PROJECT_ID`, `CADENCE_STACK_PUBLISHABLE_CLIENT_KEY` (committed defaults in
  `NeonConfig`), plus `CADENCE_NEON_DB_URL` for `migrate.sh` only.
- The `supabase/` tree, the supabase CLI devDependency (and with it `package.json`), and the
  realtime/pg_cron migrations are deleted. ADR 0002 remains the protocol's record.
