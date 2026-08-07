# ADR 0001 — Desktop app and multi-device sync

**Status:** accepted, not yet implemented
**Date:** 2026-08-07
**Supersedes:** the automatic-backup-sync design in `AutoBackupSync` / `AutoBackupPolicy`

## Context

Cadence is an Android app today. It should also run natively on Ubuntu, macOS and Windows, and
the two should stay in step through a file in a folder the user already syncs (Syncthing,
Nextcloud, Dropbox, iCloud Drive) rather than through a server Cadence operates.

The app has no users other than its author, so backwards compatibility costs nothing right now
and every constraint it would impose is worth spending instead of carrying. This ADR therefore
takes the decisions that are cheap today and expensive in a year: identifiers, the on-disk sync
contract, and the storage engine.

Where the codebase stands: `domain/` is 1.5k lines of pure logic (recurrence, quick-add,
sorting, backup codec) and is the actual product; `data/` is 1.1k lines of Room plus Storage
Access Framework I/O; `ui/` is 6.7k lines of Compose, of which the screens transfer to desktop
and the shell (bottom bar, FAB, bottom sheets) does not; `reminders/` is 163 lines of
AlarmManager that has no desktop counterpart.

## Decisions

### 1. One repository, Kotlin Multiplatform, Compose Multiplatform

Android and desktop live in this repo as sibling modules over a shared core.

The backup/sync file is a versioned contract between the two apps. Split across two repos, the
writer and the reader drift out of version and no single CI run can round-trip a file from
phone to desktop and back. The recurrence chain semantics, the completion idempotency rule and
the sorting rule are the product; a second implementation of them in another language is where
the bugs would come from.

Both targets are the JVM, which makes this the cheap case rather than the hard one.

```
cadence/
├─ core/          KMP (android, jvm[, wasmJs later]) — model, recurrence, parser, sorting,
│                 storage, repository, sync merge engine
├─ ui/            Compose Multiplatform (android, jvm) — theme, components, formatters +
│                 string resources, screens, the app-wide ViewModel
├─ app-android/   Android shell — Activity, SAF file access, AlarmManager, manifest
├─ app-desktop/   JVM shell — main(), window/menu/tray, java.nio file access + watcher,
│                 OS notifications, packaging
└─ .github/workflows/{android.yml, desktop.yml}
```

`:app` is renamed to `:app-android` rather than kept — churn is free now and the name will be
wrong for the rest of the project's life otherwise.

The `expect`/`actual` surface stays deliberately small:

```kotlin
expect object PlatformDirs      // data dir, database path
expect class SettingsStore      // SharedPreferences | JSON file under PlatformDirs
expect class SyncFolder         // SAF tree uri | java.nio.Path — list, read, write, watch
expect class Notifier           // NotificationManager | notify-send / osascript / toast
expect fun deviceLabel(): String
```

### 2. SQLDelight replaces Room

Room's KMP support covers Android and the JVM but not the browser. CLAUDE.md already names a
web companion as the reason the backup format is a published contract; the day that companion
exists, Room forces a second storage implementation and a second set of queries to keep in step
with the first. SQLDelight reaches Android, JVM and wasm/js from one set of `.sq` files.

Secondary benefits: schema and migrations are plain SQL files that diff readably, queries are
verified at compile time, and no KSP step is involved.

Cost: the DAO-shaped code in `data/db/Daos.kt` is rewritten as `.sq` queries. The subtle ones —
`completeIfOpen` returning whether *this* call closed the row, `openSuccessorsOf`,
`deleteWithChildren` in one transaction — translate directly, and their behaviour is what the
existing unit tests pin down.

### 3. `java.time` stays until a browser target needs otherwise

`commonMain` cannot see `java.*`, so shared code either gives up `java.time` for
`kotlinx-datetime` or is not in `commonMain`.

Take the second. `:core` declares a `jvmShared` source set that both the Android and the JVM
target depend on, and a source set whose targets are all JVM may use the JDK — verified in
practice, not assumed. `commonMain` stays empty. Nothing about the desktop app needs a date
library that runs in a browser, and opening the port with a rewrite of every date in the app
would put the riskiest change first for a benefit no phase before 7 collects.

`kotlinx-datetime` becomes necessary the day a wasm/js target does, and the rewrite is contained
to `RecurrenceEngine` (the month arithmetic is the fiddly part), `Entities` and the codec — the
same size then as now, because `jvmShared` is exactly the code that would have to move.

The cost of deferring is that `commonMain` cannot be used in the meantime, which is the point:
code that lands in `jvmShared` by default cannot silently acquire a JDK dependency that a later
browser target would have to discover the hard way. It is already all of `:core`.

Storage representation is unchanged: dates as epoch day (`Long`), times as second of day
(`Int`), converted at the entity boundary and nowhere else.

### 4. Identifiers become UUIDv7

`Long` autoincrement ids cannot survive a merge: two devices editing offline both mint id 7 and
the union of their files silently collapses two different tasks into one. Every id — task,
project — becomes a UUIDv7 stored as `TEXT`.

Version 7 rather than 4 because it is time-ordered: insertion stays local in the SQLite index
and "newest first" needs no extra column. Kotlin's `kotlin.uuid.Uuid` provides the type; a
small generator in `core` provides the v7 layout (48-bit millisecond prefix, random tail).

**Successor ids are derived, not random.** Completing a recurring task inserts a row for the
next occurrence. Two devices completing the same occurrence offline would each mint a different
successor id, and the merge would then show the task twice. The successor id is therefore
computed deterministically from `(spawnedFromId, occurrence date)` as a name-based UUID, so both
devices produce the same id and the merge collapses them. The same applies to the subtasks a
recurring parent hands to its next occurrence.

### 5. Backup and sync are separated

They are conflated today — one file that is both "give me my data" and "keep my devices in
step" — and the conflation is what forces the destructive semantics. Split them:

- **Backup** is a single snapshot file, written and read on demand by the user. Human-facing,
  one file, "everything I own right now".
- **Sync** is a folder of per-device state files, written automatically, never read by a human.

Both use the same record encoding. The difference is the container and the trigger.

**Import is a merge, never a replace.** `BackupDao.replaceAll` is deleted. Importing a snapshot
merges it into the current database by the rules in decision 6, which makes import idempotent
and makes importing a friend's file a sensible operation rather than a data-loss event.

### 6. Sync protocol: one file per device, merged on read

A synced folder contains one file per device:

```
Cadence/
  cadence-sync-01917f3c-…-a4e2.json     ← this phone
  cadence-sync-01917f40-…-9b71.json     ← this laptop
```

Each device **writes only its own file** and **reads all of them**. Write-write conflicts
cannot occur, so there is no lockfile, no `.sync-conflict-*` copies from the sync client, and no
"did someone else touch this since I last looked" check. The device id is a UUIDv7 minted on
first run and stored in settings alongside a human label ("Andi's Pixel") for the Settings
screen.

A device's file is its **full current state**, not a log — bounded size, and a new device
catches up by reading once.

**Merge rules**, applied over the union of every file plus the local database:

1. Records are keyed by UUID.
2. Every record carries `updatedAt`. The record with the greatest `updatedAt` wins the whole
   record — field-level merge is not worth its complexity here.
3. Ties break on device id, lexicographically, so every device reaches the same result.
4. Deletion is a tombstone: `deletedAt` set, payload dropped. A tombstone competes on timestamp
   like any other version, so a delete on one device and an edit on another resolve by time
   rather than by which arrived last.
5. Referential repair runs after the merge, reusing the rules the codec already applies on
   import: a task pointing at a project no one has lands in the Inbox, a subtask whose parent
   is missing or whose chain cycles is set free, a recurrence link to an occurrence nobody has
   is dropped.

**Tombstones are garbage-collected after 90 days.** A device that has been offline longer than
that would resurrect what the others deleted, so a peer file whose newest timestamp predates the
horizon is not merged automatically — Settings asks instead.

**Clock skew is the one thing this design trusts.** Last-writer-wins is only as good as the
clocks involved. A peer file timestamped more than an hour in the future is flagged in Settings
rather than silently believed.

**Writing is atomic**: write a temporary file in the same directory, `fsync`, rename over the
target. A sync client that copies a file mid-write otherwise propagates half of one.

**Reading is watched, not polled at startup.** Android reads on `ON_START` and writes debounced
on change plus on `ON_STOP`, as today. Desktop additionally watches the folder — a desktop app
stays open for days, and a start-only read would never see the phone's changes.

**The transport is an interface.** `SyncTransport` (list files, read one, write ours, watch) has
one implementation now, a synced folder. WebDAV or a future Cadence server slots in without the
merge engine noticing.

### 7. What this deletes

- `AutoBackupPolicy` and the entire `lastSyncedAt` mechanism. It exists only because import
  replaces every row and re-reading our own export would undo everything since. A merge is
  idempotent, so a device re-reading its own file is a no-op and the rule has nothing to guard.
- The "asked exactly once" offer flow after the first successful export. Sync is a Settings
  switch that names a folder; it is not something the app negotiates.
- `BackupDao.replaceAll`.
- The Room migration chain 1→4. The schema is re-declared from scratch at version 1 with UUID
  keys, `updatedAt` and `deletedAt`. Existing databases are not migrated — there is one
  install to re-seed and doing so costs less than carrying a migration path to a schema that no
  longer shares a primary key type.
- Backup format v1. The format becomes v2 and older files are not read. A `version` **lower**
  than the current one is refused for the same reason a higher one is: the file's shape is not
  the shape this code understands.

Every one of these is affordable exactly once, and this is that once.

### 8. Desktop specifics

- **Data location:** `$XDG_DATA_HOME/cadence` (Linux), `~/Library/Application Support/Cadence`
  (macOS), `%APPDATA%\Cadence` (Windows).
- **Packaging:** Compose Desktop's `jpackage` produces `.deb`/`.rpm`, `.dmg` and `.msi`.
  jpackage must run on the target OS, so CI gains a matrix over `ubuntu-latest`,
  `macos-latest`, `windows-latest`. Linux additionally gets a plain tarball for distro-agnostic
  use.
- **Signing:** unsigned macOS builds are blocked by Gatekeeper until right-click-opened;
  notarization needs a paid Apple Developer account. Unsigned Windows builds raise SmartScreen.
  This is the same accepted trade-off as the committed Android debug key: the app ships as a
  GitHub link, not through a store.
- **Reminders:** no AlarmManager. The decision of *when* a task fires moves into `core` as a
  pure function over the task list; the Android side keeps AlarmManager and the desktop side
  runs a coroutine timer while the app is open, firing through `Notifier`. A closed desktop app
  misses reminders — mitigated by an opt-in "start with the system, live in the tray"
  setting that writes a `.desktop` autostart entry, a LaunchAgent, or a `Run` registry value.
- **Interaction:** the bottom bar becomes a sidebar, the FAB becomes a menu item plus
  <kbd>Ctrl/Cmd+N</kbd>, the quick-add bottom sheet becomes a command-palette dialog over the
  same parser, and lists gain a detail pane instead of pushing a route. The screens themselves
  are shared; only the shell differs.

### 9. Strings move to Compose Multiplatform resources

`res/values/strings.xml` and `values-de/` become `composeResources/values/strings.xml` and
`values-de/`. The XML shape, including `<plurals>`, carries over; `R.string.x` becomes
`Res.string.x` across `ui/`. The rule stands unchanged: no user-visible string in Kotlin, no
prose in `core`.

The Android 13+ per-app language picker has no desktop equivalent, so language becomes an
explicit setting on desktop, read by the same `currentLocale()` the formatters already use.

Settings themselves stay per-device and out of the sync file — density, theme and language
describe a screen, not a task list.

### 10. Attachments ride alongside the state files, not inside them

[`docs/attachments-and-share.md`](../attachments-and-share.md) already decides that a `FILE`
attachment is copied into app-private storage and content-addressed by SHA-256. That decision
composes with this one better than a JSON state file has any right to expect.

The sync folder gains a `blobs/` subfolder holding files named by their hash. Content-addressed
means immutable, which means a blob can never conflict, never needs a timestamp and never needs
merging — a device that finds a hash it lacks copies it, and that is the whole protocol. The
per-device state file carries attachment *metadata* only, and metadata merges by the same
record-level rules as everything else.

Collection is by reference count across the merged record set, not per device: a blob is removed
once no surviving attachment row anywhere names its hash, and only after the tombstone horizon,
so a device that has not yet learned about an attachment cannot have its bytes deleted out from
under it.

A share sender's one-shot URI grant is an Android concern that does not reach `core`; the
desktop equivalents are drag-and-drop and a file picker, both of which hand over bytes directly.

## Phases

Each phase ends on a green build. Phases 1–3 change nothing a user sees.

| # | Work | Rough size |
|---|---|---|
| 1 | `:core` KMP module: model, recurrence, parser, backup codec in a `jvmShared` source set. Unit tests move with it. | 1.5k lines moved |
| 2 | UUIDv7 keys, `updatedAt`/`deletedAt`, fresh SQLDelight schema, repository on top of it. Android app re-seeded. | 1.1k lines rewritten |
| 3 | `:ui` Compose Multiplatform module: theme, components, formatters, screens, ViewModel; strings to `composeResources`. | 6.7k lines moved, mostly mechanical |
| 4 | `:app-android` reduced to shell. | small |
| 5 | `:app-desktop`: window, sidebar shell, storage, packaging, CI matrix. **First runnable desktop build.** | new |
| 6 | Backup format v2, merge engine, `SyncTransport`, per-device sync folder on both platforms. | new |
| 6b | Attachment blobs in the sync folder, once attachments themselves exist. | after `docs/attachments-and-share.md` |
| 7 | Optional: wasm/js target for the web companion — SQLDelight's web driver, `kotlinx-datetime`, and `jvmShared` finally moving into `commonMain`. | later |

`.github/scripts/next-version.sh` stays the single source of the version from phase 5 on, naming
both the APK and the three desktop installers in one release.

## Consequences

**Accepted losses.**

- Editing the same task on two devices while both are offline keeps one edit and discards the
  other. Record-level last-writer-wins is a deliberate choice over field-level merge or a full
  CRDT; the failure is one lost edit, not a corrupted list.
- Sync depends on the devices' clocks agreeing. Flagged, not solved.
- A device silent for more than 90 days needs an explicit answer before it merges.
- The existing install's data is discarded once, at phase 2.

**What gets better.**

- No path through the app replaces the database any more, so there is no operation that can
  silently discard what another device added. This is the actual point of the ADR; the desktop
  app is what forced the question.
- Import becomes idempotent and safe to run twice.
- One storage layer reaches Android, desktop and eventually the browser, and the one thing that
  does not — `java.time` — is fenced into a single source set rather than spread through the app.
- The domain rules exist once, tested once.

## Alternatives rejected

**A separate repo, or a non-Kotlin desktop stack (Tauri, Electron, Flutter).** Discards 1.5k
lines of tested domain logic and requires a second implementation of the recurrence chain,
completion idempotency and the sorting rule. The two would drift within weeks, and the file
contract between them could not be tested in one place.

**Keeping Room.** Lower effort now, and it forecloses the browser target that the backup format
was designed for.

**Keeping the snapshot-replace sync with conflict detection added.** Workable — write only when
the file has not moved, ask the user otherwise — and it keeps a mid-air-collision prompt in the
product forever, in exchange for not changing the id type. With no users to protect, the id
change is nearly free today and impossible later.

**An append-only operation log instead of per-device state files.** Merges more precisely and
grows without bound; compaction then needs the coordination the per-device layout was chosen to
avoid.

**Field-level merge or a CRDT.** Correct for concurrent edits of one task, and disproportionate
for a single-user todo app where that case is rare and the loss is one field.
