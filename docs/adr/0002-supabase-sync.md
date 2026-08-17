# ADR 0002 — Sync through Supabase instead of a synced folder

**Status:** accepted
**Date:** 2026-08-09
**Supersedes:** ADR 0001 decision 6 entirely, decision 10, and the file half of decision 5; extends
decision 7; replaces phases 6 and 6b.

## Context

ADR 0001 decided the phone and the desktop would stay in step "through a file in a folder the user
already syncs (Syncthing, Nextcloud, Dropbox, iCloud Drive) rather than through a server Cadence
operates". That was tried. It does not work, for three separate reasons, and only the first is a
matter of implementation quality.

**The shared file conflicts.** What actually ships today is the phase-5 design, not phase 6: one
`backup.json`, written debounced on every change and read on foreground, synced by a third-party
client. Two devices writing one file is precisely the write-write conflict phase 6 was designed to
avoid, and the sync client resolves it the only way it can — by keeping both and renaming one. The
user is then holding two files and a merge that no program will do for them.

**Import is destructive.** `CadenceRepository.restore` replaces every row (`BackupStore.replaceAll`:
delete all, then reinsert). So the losing side of a conflict is not "one stale field" but "everything
this device added since the file was written". ADR 0001 decision 5 already called for import to be a
merge; that half was never built.

**Deletes do not propagate at all.** `deletedAt` columns exist on both tables and *nothing has ever
written them* — every delete is a hard `DELETE`. A sync built on "the union of what each device has"
therefore reads a deletion as an absence, and the other device helpfully puts the task back. This
would sink any sync design, file-based or not.

Phase 6 was committed (`SyncMergeEngine`, `SyncTransport`, `SyncManager`, both transports) and is
**dead code**: neither `AppContainer` constructs any of it, and `AndroidSyncTransport` called a
`DocumentsContract.listDocuments` that does not exist in the Android SDK. Nothing depends on it, so
nothing is owed to it.

Two things have also changed since ADR 0001 was written. A web companion is now wanted sooner than
"optional, later", and a synced folder is the one transport a browser cannot read. And the premise
that a server means *a server Cadence operates* turns out to be false: a Supabase free-tier project
costs $0/month, is confirmed as $0 for this org, and is not a thing to run.

## Decisions

### 1. Sync goes through a Postgres we do not operate

One Supabase project. Two tables, `tasks` and `projects`, mirroring the domain records. Row-level
security scopes every row to `auth.uid()`.

This is a real reversal of "no cloud, no account", and it is worth naming rather than smuggling.
What it is not is a reversal of **local-first**: the SQLite database on each device stays the source
of truth, the app is fully usable signed out and fully usable offline, and sync is one opt-in
Settings section. Nothing is stored server-side that is not already on the device.

The cost of the reversal is one account and a dependency on a company. The cost of not taking it is
the conflict-copy problem above, forever, plus no path to the browser.

### 2. One account, email and password, created by hand

There is one user. Sign-ups are **disabled** in the dashboard and the account is created there; the
app carries a sign-in form and nothing else — no registration, no password reset, no magic link.

The anon key ships in the app. That is what it is for: it identifies the project, and RLS is what
protects the rows. The `service_role` key never leaves the dashboard, is never a CI secret, and
nothing in the build reads one. `SupabaseConfig` reads `CADENCE_SUPABASE_URL` and
`CADENCE_SUPABASE_ANON_KEY` from the environment when present so a fork points elsewhere without
editing Kotlin.

The failure mode to guard is not a stolen anon key, it is **forgetting to enable RLS**, which leaves
every row readable by anyone holding it. Enabling and *forcing* RLS is in the migration, and running
the Security Advisor is an acceptance criterion of phase 2, not a nicety.

### 3. Hub and spoke, and one merge rule instead of two

Every device talks to the server and never to another device. There is no device id anywhere: the
per-device file layout is what needed one.

A sync round, under a mutex, every step idempotent so any failure means "stop, try again later":

1. Ensure a session — refresh if expired; one 401 retries once after a refresh, then gives up.
2. **Pull** rows whose `server_updated_at` is at or after the stored cursor, paged.
3. **Merge** by the rules below and advance the cursor **in the same transaction**, so a crash
   between them cannot skip a row.
4. **Push** local rows whose `updatedAt` is above the stored watermark, tombstones included, as an
   upsert.
5. Collect tombstones past the horizon, at most once a day, and only after a round that succeeded.

**Merge rules**, unchanged from ADR 0001 decision 6 except where noted:

1. Records are keyed by UUID.
2. The record with the greatest `updatedAt` wins the whole record. No field-level merge.
3. ~~Ties break on device id~~ — **dropped.** There are no device ids here. **Ties keep the
   incumbent**, on the client and in the server trigger alike.
4. Deletion is a tombstone: `deletedAt` set, row kept. A tombstone competes on timestamp like any
   other version.
5. Referential repair runs after the merge, reusing the rules the codec already applies on import.

Rule 3 mattering at all is worth a note: today there are *two* contradicting implementations of this
rule — `SyncMergeEngine.shouldReplace` breaks ties on device id, `SqlDelightBackupStore.mergeAll`
keeps the local row. One of them had to go, and the one that survives is the one the server can also
enforce.

**The server enforces rule 2 too.** A `BEFORE INSERT OR UPDATE` trigger rejects a write whose
`updated_at` is not strictly greater than the stored row's, by returning `NULL` — which skips *that
row* without aborting the statement, so one stale record cannot fail a batch of two hundred. This is
what makes "push everything above the watermark" safe: a row that arrived *from* the server sits
above the watermark and gets pushed straight back, the trigger sees an equal timestamp and drops it,
and the round is a no-op. No `dirty` column is needed anywhere as a result.

**The cursor is the server's clock, the merge is the device's.** `server_updated_at` is written only
by the trigger, so a device with a wrong clock can lose a conflict but can never make itself
invisible to the other device. The pull re-reads a five-second overlap because `now()` is
transaction-start time, so a transaction that began earlier may commit later and land behind a
cursor already advanced past it. Re-reading five seconds is free and the merge is idempotent.

### 4. Deletion becomes a tombstone

`deletedAt` is set and the row is kept; every read filters `deletedAt IS NULL`. Tombstones are
collected after 90 days, the horizon ADR 0001 already chose.

This is the prerequisite for everything else, which is why it ships in its own phase, before any
network code exists. A build where deletes are tombstones *and* import still replaces every row is
the one genuinely dangerous configuration — a merge-aware device that wipes itself on import — so
tombstones, merge-import and the removal of `replaceAll` are one commit, not three.

Attachment rows are still deleted outright and their blobs still reclaimed: attachments do not sync
(see consequences), so there is nothing for a tombstone to reconcile.

**The server collects its own** (phase 4, `supabase/migrations/*_tombstone_gc.sql`). A device can
only ever collect its copy, and the copy that matters is the server's: it is what the next device
pulls, and what a fresh install pages over before it sees a single live task. So a `pg_cron` job
runs `public.collect_tombstones()` nightly, deleting tombstoned rows across every account whose
`server_updated_at` is past the same 90-day horizon. `server_updated_at`, not `deleted_at` —
`deleted_at` is a device's clock, and a device whose clock runs fast could hand over a tombstone
that is already collectable and have it swept before the other device ever pulled it. The server's
clock is the one the pull cursor reads, so 90 days by that clock is 90 days every device had.

The job runs as the migration's owner under `security definer`, and that role gets exactly two
policies — select and delete, both `using (deleted_at is not null)`. A live row is as invisible to
the sweep as it is to another account, which is a narrower grant than the obvious alternative of a
role with `bypassrls`. Both are needed rather than the delete alone: `DELETE ... WHERE` reads the
columns it filters on, so the `SELECT` policies apply to it too, and with the delete policy alone
the statement is legal, matches nothing and reports success.

### 5. The wire carries the published shape, not the storage shape

Dates go over as ISO-8601 (`date`, `time`, `timestamptz`) and recurrence as a `jsonb` object — the
same reasoning `BackupCodec` gives for the backup file, and deliberately *not* the epoch-day integers
and the packed `v1;key=value` recurrence column. Epoch day is a private detail of
`SqlDelightStores.kt`; `20309` means nothing in the Supabase table editor and less to a future web
client.

The remote DTOs are **new types, not the backup DTOs reused**. The backup file is a published v2
contract; a Postgres column rename must not be able to change the shape of an exported file. Twenty
duplicated field names is the price of that independence.

### 6. `supabase-kt`, not a hand-rolled client

`supabase-kt` 3.7.0, the `auth-kt` and `postgrest-kt` modules, with OkHttp as the Ktor engine —
the one engine that covers Android *and* the desktop JVM, since `ktor-client-java` needs
`java.net.http`, which `android.jar` does not carry.

This decision was taken the other way first, and reversed. The build was pinned to Kotlin 2.0.21 by
Compose Multiplatform 1.7.3, `supabase-kt` requires 2.1+, and a hand-rolled Ktor client against
GoTrue and PostgREST looked like the cheaper of two bad options. Raising Kotlin was the better one:
the toolchain was two years behind regardless, nothing in it was separable — coroutines 1.11 and
SQLDelight 2.1 both need Kotlin 2.2+ metadata, and SQLDelight 2.1's generated code crashes the
2.0.21 compiler outright — and it had to happen before any of this anyway. On Kotlin 2.4.10,
`supabase-kt` 3.7.0 compiles for both the JVM and the Android target, verified rather than assumed.

What that buys is the part worth not writing by hand: session persistence and token refresh. Silent
refresh is where a hand-rolled client is most likely to be subtly wrong, and the failure — an expired
session that presents as "sync just stopped" — is exactly the one a single user would never manage
to diagnose.

Two costs, both accepted. It pulls in `kotlinx-datetime`, which ADR 0001 decision 3 deliberately kept
off this classpath; it arrives transitively as the library's own dependency, our code keeps
`java.time`, and phase 7 wants it there eventually anyway. And its default session storage writes to
`java.util.prefs` on the JVM, so a `SessionManager` is supplied that persists to `syncStateRow`
instead — one small class rather than the whole auth flow.

### 7. The sync client lives in `:core`, and is not a port

`ui/platform/Ports.kt` exists for what the *machine* does differently — alarms, a file picker, a SAF
uri. HTTPS and JSON are not that: both platforms do them identically and the only difference, the
Ktor engine, is one dependency line. `CadenceSyncEngine` is therefore a concrete class in `:core`,
constructed by hand in each shell's `AppContainer` on the existing `applicationScope`, and passed to
`CadenceViewModel` directly the way the concrete `CadenceRepository` already is.

The session is persisted in a single-row `syncStateRow` table in the app's own database, alongside
the cursor it has to stay consistent with, rather than in a settings file.

### 8. Timestamps truncate to milliseconds

Local storage is epoch millis, Postgres `timestamptz` is microseconds, and `Instant.now()` on JDK 17
is microseconds. A row pushed and pulled back would return with a strictly greater `updated_at` than
the local copy, so the merge would overwrite it, which bumps `updatedAt`, which pushes it again —
forever. `CadenceRepository` stamps `Instant.now().truncatedTo(ChronoUnit.MILLIS)` at every call
site, and then the database, the wire and the merge agree exactly.

### 9. Reminders become a per-device setting

Once a task exists on two devices, both schedule a reminder for it: an AlarmManager notification on
the phone and a tray balloon on the desktop, for the same task at the same minute. Nothing about the
sync design causes this — it falls out of `ReminderScheduler.sync(tasks)` reconciling against a task
list that is now shared.

So reminders gain a per-device switch, on by default on Android and **off** by default on the
desktop, and it stays out of sync like every other setting (ADR 0001 decision 9). Off means the
desktop's scheduler is not started at all rather than started and told to stay quiet: it is a
30-second poll plus a tray icon, and there is no reason to burn either to decide not to notify.

The alternative — a synced "this reminder already fired" marker so the first device suppresses the
rest — needs both devices online at the moment the reminder is due, which is exactly when the
desktop is most likely closed. A missed reminder is worse than a doubled one.

### 10. What this deletes

On top of ADR 0001 decision 7's list, which stands:

- The whole phase-6 file stack, none of it ever wired: `SyncTransport`, `SyncManager`,
  `DeviceSyncState` and the device-file naming helpers, `AndroidSyncTransport`,
  `DesktopSyncTransport`.
- `AutoBackupSync`, `DesktopAutoBackupSync`, `AutoBackupPolicy` and its test, the
  `AutoBackupController` port, `AutoBackupSettings` and the "asked exactly once" offer dialog.
- `BackupStore.replaceAll` and the `deleteAll` statements that exist only to serve it.

Manual export and import stay. They are the offline escape hatch and the only way to hand the data
to something that is not Cadence, and neither has anything to do with keeping two devices in step.

### 11. Sync runs itself, and the manual gesture is the one people already know

Phase 2 shipped a **Sync now** button in Settings, which is a chore disguised as a feature: a user
who forgets to press it sees stale data and concludes sync is broken. Sync becomes automatic, on
these triggers and no others:

- on app start, and on every return to the foreground;
- debounced two seconds after a write — every task and project mutation arms it, a checkbox tick
  included. Settings never do, since they are per-device. A row merged *in* from a pull never
  re-arms it, or two devices push each other awake forever;
- on stop and on window close, **fire-and-forget**: the process does not wait for the round. The
  local database is the source of truth, so a push that misses its window ships on the next start,
  and an app that hesitates when you close it feels worse than one that syncs four seconds late.
  Android's `onStop` carries no completion guarantee anyway;
- a poll on the **desktop only**, every 15 minutes, as the safety net for a socket that believes it
  is connected and is not.

**No `WorkManager`, and no Android poll.** Nothing here is time-critical: the phone is stale only
while nobody is looking at it, and it syncs on foreground before the user reads a row. A background
job buys a fresher database nobody is reading, and pays for it in a doze-mode fight.

The manual path stays, but as a gesture rather than a chore: pull-to-refresh on Android's four
top-level lists, a refresh control and `Ctrl`/`Cmd`+`R` on the desktop, and Settings keeps its
**Sync now** button for the case where someone went looking for it.

### 12. Realtime is an accelerant, and it never touches the cursor

`postgres_changes` over `realtime-kt` gets a change onto the other device in about a second, which
is what "fluent" actually means to a user with a phone in one hand and a desktop on the next
screen. It is bolted on *beside* the existing round, never in place of it:

- the payload row decodes with the same `RemoteTask`/`RemoteProject` the pull already uses, and is
  merged through the same `mergeAndAdvance` and the same last-writer-wins rule;
- **the cursor is advanced only by a real pull.** Realtime is at-most-once — a dropped socket loses
  events silently, and a cursor advanced past an event that never arrived has skipped that row
  forever. Merging a payload is idempotent and therefore safe; advancing from one is not. The
  merge call passes a null cursor, and the next pull re-fetches the same rows harmlessly;
- every reconnect runs a full `syncOnce()`, because the socket's downtime is exactly the gap the
  cursor already covers;
- one channel for both tables, filtered server-side on `user_id`. Without the filter the socket
  carries every user's rows and RLS filters them at delivery, which is waste plus one more thing to
  get wrong. Needs the migration to add both tables to the `supabase_realtime` publication and to
  set `replica identity full` for update payloads under RLS;
- the socket is open **only in the foreground on Android** — a background websocket is the wakelock
  `WorkManager` was rejected to avoid — and for the whole process lifetime on the desktop,
  minimised included. A desktop that drops the socket on alt-tab drops it exactly when the phone is
  being used, which is the one case realtime exists for.

Soft delete means a deletion is an `UPDATE`, so `INSERT` and `UPDATE` events cover everything and
`DELETE` needs no handling.

### 13. Sync gets a place in the header, and is invisible without an account

Neither shell has a `TopAppBar`; what exists is `ScreenHeader`, pinned above the list on Today,
Upcoming, Inbox and Projects, with an `actions` slot. That slot is where sync becomes visible:

- a **status indicator** on all four screens, in four looks — absent (signed out), spinner
  (syncing), plain (idle), error tint (offline, failed, or last synced more than 24 hours ago).
  Tapping it opens Settings, where the actual reason is spelled out in words;
- a **refresh control** beside it on the desktop only. Android has the pull gesture and does not
  need a fourth icon next to sort and search; the desktop has neither a pull gesture nor a
  discoverable `Ctrl`+`R`, so it does. Same `:ui` code, one flag from the shell;
- **signed out, none of it exists** — no greyed icon, no inert pull gesture. A fresh install looks
  exactly as it does today, and the app does not advertise machinery behind an account nobody has.

"Stale" is a tint and never a dialog. An app unopened for a week is not an emergency.

### 14. Every failed round is reported, offline included

The alternative considered was to stay quiet about failures the user cannot act on — a phone in a
tunnel fails on foreground, on every edit debounce, and says nothing. It was rejected: a sync that
fails silently is how a user ends up trusting data that is not there. So every round that fails
raises a snackbar, `OFFLINE` included, with the cause the engine already distinguishes
(`OFFLINE`, `PROJECT_ASLEEP`, `SESSION_EXPIRED`, `SERVER`).

Two things keep that from being unusable, and neither weakens it. A new failure **replaces** the
snackbar on screen rather than queueing behind it, so eight failed rounds cost one snackbar's worth
of screen time instead of minutes of them. And there is no **Retry** action, because the next
trigger already is one.

A `Connectivity` port — ask the OS before attempting, and never fail at all when offline — was
considered and **not** built. Android's `ConnectivityManager` would answer honestly and the desktop
has no equivalent worth the name, so it would buy a quieter phone at the price of a port that lies
on one of the two platforms.

## Consequences

- Editing the same task on two devices while both are offline keeps one edit and discards the other,
  as ADR 0001 already accepted. The server trigger at least makes the loss deterministic rather than
  a function of which push happened to arrive last.
- Sync depends on the devices' clocks agreeing, still flagged rather than solved — but only conflict
  *resolution* does. Delivery does not, because the cursor is the server's clock.
- A device offline longer than the 90-day tombstone horizon re-inserts what the others deleted: the
  server tombstone has been collected and the row is simply gone. ADR 0001 promised Settings would
  ask in this case; it does not, and this is an accepted loss rather than a solved problem.
- A task created independently on both devices *before* the first sync exists twice. Id derivation
  collapses recurring successors, not independently created tasks. Signing in on one device, letting
  it upload, and only then signing in on the other avoids it.
- Attachments do not sync. A task that crosses over arrives without its files. Supabase Storage is
  the obvious home and is deferred wholesale; ADR 0001 decision 10's `blobs/` folder is superseded
  along with the folder it sat in.
- A free-tier project pauses after roughly a week with no requests, and **restore is manual** —
  no incoming request wakes it, someone has to click Resume in the dashboard. A paused project
  answers with Supabase's custom **HTTP 540**, and a paused project still resolves DNS and
  completes TLS, so this *is* distinguishable from having no network — the app says which. A
  weekly scheduled ping keeps the project alive; the 540 wording is what saves us if that ping
  ever stops.
- The headline changes from "no cloud, no account" to "local-first, with an optional account for
  syncing your own devices; no analytics". README, CLAUDE.md, ROADMAP and the desktop package
  description all state the old claim and all have to change with the code.

## Alternatives rejected

**Per-device files in a synced folder — i.e. actually finishing phase 6.** This would genuinely fix
the conflict copies, since each device writes only its own file. It was rejected on three counts: it
still depends on a third-party sync client's timing and conflict behaviour, Android SAF folder
access is the most painful I/O surface either platform offers, and a browser cannot read a synced
folder at all, so it forecloses the web companion that is now wanted sooner rather than later.

**A Ktor server of our own on Railway.** Attractive because the wire format would be one contract
tested in one place, and because the same service would later serve the website. Rejected on cost
and operations: ~$5/month against $0, plus a Dockerfile, a deploy, a volume to keep, and a
cold-start 502 to retry around. Supabase's cost is a little SQL living outside the Kotlin test suite
— a trigger and two policies — which is the cheaper of the two prices.

**AWS Lambda with DynamoDB.** Cheapest on paper and the most work by a distance: no persistent disk
means the data layer is rewritten against DynamoDB rather than reusing SQLite, JVM cold starts run
to seconds, and IAM and a deploy pipeline arrive with it.

**Field-level merge or a CRDT.** Rejected by ADR 0001 for the same reason it is rejected here:
disproportionate for a single-user todo app where concurrent edits of one task are rare and the loss
is one field.

**Keeping automatic file sync alongside server sync.** Two mechanisms writing one database, each able
to undo the other. This is the current bug, not a fallback for it.

## Phases

Each ends on a green build — `./gradlew testDebugUnitTest :core:jvmTest` and
`./gradlew :ui:compileKotlinJvm` — and both shells launching.

| # | Work | Rough size |
|---|---|---|
| 1 | Soft delete at every call site, the single merge rule, millisecond truncation, `syncStateRow` and the schema migration on both platforms, import becomes a merge, `replaceAll` deleted, tombstone GC. **No network.** | ~500 lines changed |
| 2 | Supabase project and its committed migration SQL. Ktor client, session handling, pull and push, `syncOnce()`. Settings gains sign-in and **Sync now**. The file-sync stack and the dead phase-6 code go in the same change. | ~900 new, ~700 deleted |
| 3 | **Sync runs itself** (decision 11): start and foreground, debounced two seconds after a write, fire-and-forget flush on stop and close, a 15-minute poll on the desktop only. Plus sync in the header (decision 13) — indicator on the four top-level screens, pull-to-refresh on Android, refresh control and `Ctrl`/`Cmd`+`R` on the desktop — and the failure snackbar of decision 14. | ~450 lines |
| 3b | **Realtime** (decision 12): `realtime-kt`, the publication and `replica identity` migration, one filtered channel, payload merged without advancing the cursor, full round on every reconnect. Ships after 3 so a bug has one suspect rather than two. | ~150 lines |
| 4 | Hardening: server-side tombstone collection, paging past the first thousand rows, the clock-skew warning, the weekly keep-alive ping, Security Advisor clean, and every document that still says "no cloud". | small |
| 5 | Attachments through Supabase Storage. `storage-kt` ships in the same BOM and covers both targets; content-addressed blobs never conflict, so the protocol is "upload the hashes the server lacks, download the ones you lack" and nothing more. Free tier allows 1 GB with a 50 MB per-object cap. | new |

Phase 1 must ship before phase 2. Without it the first sync reads a missing row as a row that never
existed and puts deleted tasks back on the other device.

Phase 2 is the one that matters to the user: at the end of it the phone and the desktop sync, by
hand. Phase 3 removes the "by hand" and gives sync somewhere to stand outside Settings; 3b makes it
feel immediate. Phase 3 is shippable on its own — after it, nobody has to press anything again —
which is why 3b is a separate change and not a bigger one.

## Still open

Recorded rather than silently assumed. None blocks phase 1.

- Whether the weekly keep-alive ping lives in this repo's Actions or is skipped in favour of
  relying on daily use plus the 540 message.
- What signing out clears. The intent is: session, cursor and push watermark, and no local data —
  but it is not yet decided whether the app should offer to delete server-side data at all.
- ~~How loudly a failed background sync complains~~ — settled in decision 14: every failure, offline
  included, replacing rather than queueing. Staleness earns an error tint on the header indicator
  after 24 hours (decision 13), not a mark on the Settings entry.
- Whether attachment *metadata* should sync ahead of the bytes, so the other device can at least
  say a file exists. The cost is that every attachment row grows a "do I have the blob" state and
  the orphan sweeper has to learn not to reclaim hashes it has never seen.
