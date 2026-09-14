# Self-hosting sync

Cadence is local-first: every device keeps its own SQLite database and is fully usable signed
out. Syncing your devices is optional, and the server half is a [Neon](https://neon.tech)
Postgres project **you** own — there is no Cadence-operated server, and the published builds are
wired to the maintainer's own project, which accepts no sign-ups. To sync your devices you point
a build at a project of your own. This page is the walkthrough; the design is in
[ADR 0002](adr/0002-supabase-sync.md) (the protocol) and [ADR 0005](adr/0005-neon-sync.md)
(the transport).

What you end up with: one Neon project, four tables under row-level security, one account per
person, and two URLs compiled into your builds. Neon's free tier is enough for a personal task
list; the compute scales to zero between rounds and wakes on the first request.

## 1. Create the project and enable the Data API

1. In the [Neon console](https://console.neon.tech/) create a project (any region; pick the one
   closest to your devices).
2. Open the project's branch (`main` by default) and enable the **Data API** on it. This
   provisions `auth.user_id()` (the `pg_session_jwt` extension) and the `authenticated` /
   `anonymous` roles the migrations lean on; the baseline migration refuses with a clear message
   when they are missing.
3. Copy the Data API endpoint. It looks like

   ```
   https://<endpoint>.apirest.<region>.aws.neon.tech/neondb/rest/v1
   ```

4. Open the **Auth** tab and enable **Neon Auth**. Copy its base URL, which looks like

   ```
   https://<endpoint>.neonauth.<region>.aws.neon.tech/neondb/auth
   ```

Both URLs identify the project and grant nothing on their own: row-level security is what keeps
one account's rows from another, and there is no API key anywhere in this design. The
credential is the account.

## 2. Apply the migrations

`neon/migrations/*.sql` is the whole server side: four tables, forced RLS over `auth.user_id()`,
the stale-write trigger, and a lock-down of the bookkeeping table. Apply them with the direct
Postgres connection string from the console (**Connect** → the `postgresql://…` string; this is
a secret, keep it in your shell only):

```bash
CADENCE_NEON_DB_URL='postgresql://…' bash neon/migrate.sh
```

Needs `psql`. Without one, `docker run --rm -i postgres:16 psql "$CADENCE_NEON_DB_URL"` speaks
to Neon just as well. Re-running the script is safe: applied files are recorded by name in
`public.schema_migrations` and skipped. Every file is idempotent besides, so pasting one into the
console's SQL editor also works.

> **Enabling the Data API grants every future table away.** It leaves a default-privilege entry
> behind, so any table the owner creates in `public` afterwards is readable and writable by every
> signed-in account until something revokes it. `0002_lock_bookkeeping.sql` revokes that default;
> if you add tables of your own, state their grants outright. `bash neon/tests/run.sh` (needs
> docker) applies the whole chain to a throwaway Postgres and checks the RLS isolation, the
> trigger and the lock-down.

## 3. Create the accounts

The app has sign-in only, no sign-up, on purpose: a registration form in a personal app could
only ever produce an error. Create each person's account in the console's **Auth** tab (email +
password). One account per person; every device that person signs in on shares the same rows.

## 4. Build with the endpoints

The two URLs are compiled in at build time. Export them in the shell that runs Gradle:

```bash
export CADENCE_NEON_DATA_API_URL='https://<endpoint>.apirest.<region>.aws.neon.tech/neondb/rest/v1'
export CADENCE_NEON_AUTH_URL='https://<endpoint>.neonauth.<region>.aws.neon.tech/neondb/auth'

./gradlew assembleDebug                              # Android
./gradlew :app-desktop:packageDistributionForCurrentOS   # desktop installer for this OS
bash .github/scripts/build.sh                        # everything, into dist/
```

`:core`'s `generateNeonConfig` task writes them into a generated `NeonBuildConfig` and
`NeonConfig.fromBuild` reads them back. A build made **without** both variables carries no
endpoint: Settings says so instead of showing a sign-in form, the header shows no sync
indicator, and no request is ever made. Setting only one of the two is treated as none, with a
warning in the Gradle output.

Then, on each device: **Settings → Sync → Sign in** with the account from step 3. The first
signed-in device pushes its whole database up; every later one merges. Signing out forgets the
session and cursors and deletes nothing locally.

### Your own fork's CI

If you cut releases from a fork, the workflows read the same two variables from repository
secrets `CADENCE_NEON_DATA_API_URL` and `CADENCE_NEON_AUTH_URL`, next to the four release-signing
secrets. `tools/release-signing-wizard.sh` walks through minting a release keystore and setting
all six:

```bash
bash tools/release-signing-wizard.sh
```

## 5. Maintenance

Tombstones older than 90 days are collected by the app itself after a successful push, at most
daily, for the account it is signed in as. Rows of an account that no longer signs in are out of
its reach, so there is a laptop-side tool over the direct connection:

```bash
CADENCE_NEON_DB_URL='postgresql://…' bash neon/db.sh status         # live rows, tombstones, per account
CADENCE_NEON_DB_URL='postgresql://…' bash neon/db.sh sweep          # dry run
CADENCE_NEON_DB_URL='postgresql://…' bash neon/db.sh sweep --yes    # collect
CADENCE_NEON_DB_URL='postgresql://…' bash neon/db.sh vacuum
```

The direct connection bypasses RLS on Neon, which is why `sweep` shows before it does, matches
only rows that are already tombstones, and refuses to go below the 90-day horizon without
`--force`: a device offline longer than the horizon puts back what the others deleted.

## What sync does and does not protect

Rows are protected from *other accounts* by RLS. They are **not** encrypted at rest against the
project owner: whoever holds the direct connection string can read every title. If that is you,
it is your data on your database. End-to-end encryption is on the [roadmap](../ROADMAP.md) as
an exploratory item. Attachments never sync at all; the rows travel, the bytes stay on the device
that has them.
