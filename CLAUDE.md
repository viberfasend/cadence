# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Cadence — a local-first native Android and desktop todo app (Kotlin, Compose Multiplatform,
Material 3, SQLDelight). No analytics. Package `de.andi1984.cadence` throughout.

**Local-first, with an optional account.** The SQLite database on each device is the source of
truth and the app is fully usable signed out and offline; signing in (Settings) syncs your own
devices through a Supabase project, hub-and-spoke, last-writer-wins
([ADR 0002](docs/adr/0002-supabase-sync.md)). The synced-`backup.json` design ADR 0001 chose was
tried and removed — two devices writing one file is a conflict no program resolves.

Four modules: `:core` (Kotlin Multiplatform, the domain layer), `:ui` (Compose Multiplatform —
theme, components, formatters, every screen, the ViewModel), `:app-android` (the Android shell,
renamed from `:app` in ADR 0001 phase 4) and `:app-desktop` (the JVM shell for Ubuntu/macOS/
Windows, added in ADR 0001 phase 5). Both shells
are built out of `:core` and `:ui` in the steps laid out in
[`docs/adr/0001-desktop-app-and-multi-device-sync.md`](docs/adr/0001-desktop-app-and-multi-device-sync.md).

The product rule the whole app is built on: **importance first, due date breaks ties**
(`:ui`'s `ui/TaskSorting.kt`). Recurrence supports both calendar rules and "n days after completion".

## Commands

```bash
# What CI runs, in one invocation — configuring this build costs more than running it, so a
# second ./gradlew pays for all of it again. :app-android is not in the list: it has had no
# unit tests since storage moved to :core, and a bare `testDebugUnitTest` would still build its
# whole debug variant (31 tasks) to run none of them.
./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest \
          :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm
./gradlew assembleDebug            # app-android/build/outputs/apk/debug/app-android-debug.apk
./gradlew assembleRelease          # falls back to the debug key when no CADENCE_KEYSTORE is set

./gradlew :app-desktop:run                          # launch the desktop app from source
./gradlew :app-desktop:packageDistributionForCurrentOS   # deb+rpm+tarball / dmg / msi, per OS
./gradlew :app-desktop:packageUberJarForCurrentOS   # a runnable jar, no native packaging tools
                                                     # needed — what to reach for off Linux CI

# a single test class / method (method names are backticked sentences)
./gradlew :core:jvmTest --tests "de.andi1984.cadence.RecurrenceEngineTest"
./gradlew :core:jvmTest --tests "*RecurrenceEngineTest.monthly on a fixed day*"

# the whole release, staged into dist/ under the names the release links use
bash .github/scripts/build.sh              # apk + everything this OS can package
bash .github/scripts/build.sh deb --skip-tests   # one format, no tests
bash .github/scripts/build.sh --dry-run    # what it would run, and at which version
```

**`build.sh` is the build, and CI only calls it.** `android.yml`, `desktop.yml` and `release.yml`
each used to carry their own copy of "derive the version, run Gradle, copy the outputs", which is
how the three drifted apart; now each one runs the same script a laptop does, so a release can be
cut by hand — `gh release create` over `dist/` — without spending Actions minutes at all. It
derives the version the way CI does (`next-version.sh`, unless `CADENCE_VERSION_NAME` is already
set or `--version` overrides it), runs one Gradle invocation for every artefact rather than one
per target, stages the APKs under the fixed names the download URLs point at, and runs
`check-signing.sh`. A format whose packaging tool is missing — no `rpmbuild`, no WiX — is skipped
with a warning when `all`/`desktop` implied it and is a hard error when it was named outright.

`:core`'s and `:ui`'s tests are each one source set compiled twice: `jvmTest` is the desktop
compilation and `testDebugUnitTest` the Android one. `testDebugUnitTest` alone therefore misses
nothing in either module today, but it also never exercises the JVM target the desktop app is
built on, so CI names both. Storage lives entirely in `:core` now (ADR 0001, phase 2), so
`:app-android` has no unit tests of its own left — `RecurrenceCodecTest` and the SQLDelight store
tests moved with it.

`:ui`'s tests live in a `jvmSharedTest` source set mirroring `jvmShared`, and cover the part of
the module that is plain JVM Kotlin: the `CadenceUiState` derivations, `sortedFor`, and
`CadenceViewModel`'s undo state machine (a `StandardTestDispatcher` sharing `runTest`'s scheduler,
so the five-second undo window costs no wall clock). The ViewModel runs on `runTest`'s
`backgroundScope` rather than on the test coroutine — its `init` starts collectors that never
finish, and `runTest` waits for its own children — and the test subscribes to `state`, because
`stateIn(WhileSubscribed)` keeps the upstream cold until something reads it. The ViewModel test
sits in `jvmTest` rather than `jvmSharedTest`, alone among them,
because it constructs a real `CadenceSyncEngine` — signed out that makes no request, but the
Android unit-test JVM has no Android runtime behind supabase-kt. No screen is tested: composables
would need the Compose test runtime, and none of the rules worth pinning live in one.

`:app-desktop` is a plain JVM module, so its task is `:app-desktop:test`. It covers
`DesktopSettingsStore` (the file round-trip and what a truncated one falls back to) and
`DesktopReminderScheduler` (fires once per due task, and forgets what the reconcile stops
listing) — the two classes there with no Compose in them.

`assembleDebug` only ever compiles `:ui`'s *Android* target, and its tests reach only the
plain-Kotlin half. CI therefore also runs `:ui:compileKotlinJvm` on its own: without it the
Android build could stay green while the screens the desktop app is mostly made of stopped
compiling for the JVM.

**A test that hands a class its own `CoroutineScope` must use `runTest`'s `backgroundScope`.**
This is the one rule here that has actually cost real time. `runTest` drains the shared
`TestScheduler` once the test body returns, and a `while (true) { … delay(n) }` loop running on
that scheduler — `DesktopReminderScheduler`'s poll, and anything like it — always has one more
`delay` queued, so the drain never reaches idle. Nothing fails: the test JVM spins at 100% CPU
forever, which on a runner means the job burns its time limit and reports nothing. A scope of
our own plus an `@After { scope.cancel() }` does not help, because `@After` runs *after* the
drain. `backgroundScope` already runs on the test's scheduler and is cancelled *before* it, so
it is both the shorter code and the only correct one. `CadenceViewModelUndoTest`,
`CadenceViewModelCrudTest` and `DesktopReminderSchedulerTest` all take their scope from it.

**No mocking library, and no second test framework.** JUnit 4 + `org.junit.Assert` +
`kotlin.test` + `kotlinx-coroutines-test`, with hand-written fakes for every port —
`CadenceRepositoryTest`'s style, and `:ui`'s `Fakes.kt` in the same shape. The fakes back a
*real* `CadenceRepository`, because the rules under test are the repository's; faking it would
assert only that the ViewModel called what the test told it to expect.

JDK 17, compileSdk/targetSdk 35, minSdk 26. No lint or format task is wired up.

The server half of sync is `supabase/migrations/*.sql` — three tables, forced RLS, the stale-write
trigger and the nightly `pg_cron` job that collects tombstones from all three past the same 90-day
horizon the client uses (by `server_updated_at`, the server's clock, since `deleted_at` is a device's). It is
committed rather than left in the dashboard because it is the one part of the system the Kotlin
suite cannot reach.

**Apply it with `npx supabase db push`, and only that.** The CLI arrives through `npx` (pinned in
`package.json`) and `supabase/config.toml` is committed, so a clone needs `supabase link
--project-ref <ref>` and nothing else. The dashboard's SQL editor and the Management API — which
is what an agent's `apply_migration` tool reaches — do apply the SQL, but they stamp
`supabase_migrations.schema_migrations` with a version taken from the server's clock at apply
time rather than from the filename. The filenames here are hand-picked (`…120000`), so the two
never match, and the next `db push` refuses with *"Remote migration versions not found in local
migrations directory"*. Untangling that is `supabase migration repair --status reverted <the
server's versions>` followed by `--status applied <the filenames>`; both touch the history table
only, never the schema. The SQL editor is still the right answer for a fresh project that will
never see the CLI.

`supabase db pull` is not the way out of a mismatch: it diffs the live database against the
migrations and writes the difference as a generated `_remote_schema.sql`, which is unreadable
beside these hand-written files and sorts to whatever timestamp it was generated at. Say no to
its *"Update remote migration history table?"* prompt and delete the file.

No Gradle task will tell you any of it is wrong, so it has a test of its own:
`bash supabase/tests/run.sh` applies every migration (twice, since they get pasted into projects
that already carry half of them) to a throwaway `postgres:16` container with `auth.users` and
`cron.schedule` stubbed, and checks what the sweep collects and what it leaves alone. Needs
docker and nothing else. Run it after editing anything under `supabase/`. `SupabaseConfig` reads `CADENCE_SUPABASE_URL` and
`CADENCE_SUPABASE_ANON_KEY` from the environment when set, so pointing a build at another project
edits no Kotlin. The anon key is committed on purpose: RLS is what protects the rows.

```bash
./gradlew connectedDebugAndroidTest   # needs a device; CI has no emulator
```

The only instrumented test is `QuickAddPatternsDeviceTest`: it compiles the quick-add grammar
with the device's ICU regex engine, which the JVM tests cannot do (see Localisation below).
Run it after touching `QuickAddPatterns` or a lexicon.

## Architecture

**One ViewModel for the entire app.** `CadenceViewModel` combines `repository.tasks`,
`repository.projects` and `settingsStore.state` into a single `CadenceUiState`, exposed as a
`StateFlow`. Every screen is a stateless composable that receives that whole `state` plus
callbacks — no per-screen ViewModel, no per-screen data loading. Derived views (overdue, inbox,
tasks-in-project, project labels) are computed by helper methods **on `CadenceUiState`**, so add
new derivations there rather than filtering inside a screen.

It is a **plain class**, not an `androidx.lifecycle.ViewModel`: there is no ViewModel on the
desktop, and the only two things the Android shell actually needs from the lifecycle library are
a scope that survives a rotation and a moment to stop. Both are constructor parameters
(`scope`) and a method (`close()`), and `:app-android`'s `CadenceViewModelHost` — an
`androidx.lifecycle.ViewModel` whose whole body is one field — supplies them from
`viewModelScope`. `:app-desktop`'s `main()` supplies them instead: a plain `CoroutineScope` it
creates and keeps alive for the process, and `Window`'s `onCloseRequest`.

**Wiring** is hand-rolled in an `AppContainer` class per shell. `:app-android`'s is built once in
`CadenceApplication.onCreate` and reached through
`ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY`; `:app-desktop`'s is a plain
`AppContainer()` constructed once at the top of `main()` — no Activity, no Application, so no
factory to hang it from. No DI framework either way — new singletons go in the shell's
`AppContainer`.

**Navigation** lives entirely in each shell — the part of the UI that does *not* move.
`:app-android`'s `ui/CadenceApp.kt` uses `androidx.navigation.compose`: route constants in
`Routes`, one `NavHost`, bottom bar + FAB shown only on the four top-level destinations.
`:app-desktop`'s `ui/CadenceDesktopApp.kt` does not depend on that Android-only artifact and
instead holds a plain `List<Route>` back stack in `remember { mutableStateOf(...) }`, rendering
only its last entry — a `NavigationRail` sidebar stands in for the bottom bar, a rail item for
the FAB, `Ctrl`/`Cmd`+`N` for its keyboard equivalent. Both shells call the same screen
composables with the same callbacks; only the chrome around them and how a route is stored
differ. Quick-add is a sheet driven by composable state on both, not a route. The sidebar's
detail-pane refinement ADR 0001 §8 describes, and swapping the quick-add sheet for a command
palette, are left for later — phase 5's bar is a *working* shell, not a *finished* one.

### Layers

```
:core         domain/     pure Kotlin — model, RecurrenceEngine, QuickAddParser, BackupCodec. NO
                          Android imports; this is what the JVM unit tests exercise. Keep it that way.
              data/       CadenceRepository, the TaskStore/ProjectStore/BackupStore/SyncStore ports it
                          needs, and the SQLDelight-backed implementations of those ports (data/db/)
              data/sync/  CadenceSyncEngine (supabase-kt: sign in, pull, merge, push), the wire DTOs
                          and SupabaseConfig — a plain class, not a port (ADR 0002, decision 7)
:ui           ui/         theme, shared components, ui/format/, one package per screen, CadenceViewModel
              ui/platform/ the ports the ViewModel needs from the machine — ReminderScheduler,
                          BackupGateway, BackupFilePicker
              composeResources/ strings.xml and values-de/, reached as Res.string.x
:app-android  ui/         CadenceApp (NavHost, bottom bar, FAB), CadenceViewModelHost, the SAF picker
              data/backup/BackupIo (SAF read/write)
              data/settings/SharedPrefsSettingsStore
              reminders/  AlarmManager scheduling, notification receiver, boot re-schedule
:app-desktop  Main.kt     application {}/Window, wires CadenceViewModel, Ctrl/Cmd+N
              AppContainer.kt hand-rolled DI, same shape as :app-android's
              ui/         CadenceDesktopApp (hand-rolled back stack, NavigationRail sidebar)
              data/       DesktopBackupIo (java.nio), DesktopSettingsStore
                          (JSON file), DesktopBackupFilePicker (JFileChooser), DesktopReminderScheduler
                          (coroutine poll + system tray, no AlarmManager here)
              platform/   PlatformDirs — the per-OS data directory (ADR 0001 §8)
```

**`:ui` states its platform needs as ports too.** `:core` already treats storage that way; the
same idea covers everything else the app touches that Android and the desktop do differently.
`ui/platform/Ports.kt` declares `ReminderScheduler`, `BackupGateway` and `BackupFilePicker`, plus
`BackupTarget` — an opaque string that is a SAF content uri on Android and a plain absolute path
on the desktop, which `:ui` only ever hands back. `:app-android` implements all three
(`AlarmReminderScheduler`, `BackupIo`, `rememberSafBackupFilePicker`) and `:app-desktop`
implements the same three (`DesktopReminderScheduler`, `DesktopBackupIo`,
`DesktopBackupFilePicker`), and no screen learns which shell it got. **Sync is deliberately not a
port**: HTTPS and JSON are identical on both platforms, so `CadenceSyncEngine` is a concrete class
in `:core` that each `AppContainer` constructs on its application scope (ADR 0002, decision 7). `SettingsStore` is a port
for the same reason, declared next to the settings types in `ui/settings/SettingsStore.kt` —
`SharedPrefsSettingsStore` on Android, `DesktopSettingsStore` (a JSON file under `PlatformDirs`)
on the desktop.

**Storage is a port, not a layer, and it lives in `:core` entirely (ADR 0001, phase 2).**
`CadenceRepository` reaches storage through three interfaces in `data/Stores.kt` that speak `Task`
and `Project` rather than rows and carry no database annotation. `data/db/SqlDelightStores.kt`
implements them over the SQLDelight schema in `data/db/*.sq`, and every row↔domain conversion
lives there — epoch day/second-of-day/epoch-millis at the boundary, nowhere else. This is
possible because SQLDelight itself is multiplatform, unlike Room: `:app-android` supplies the
`DatabaseDriverFactory` actual (`AndroidSqliteDriver`, needing a `Context`) and `:app-desktop`
the `jvmMain` one (`JdbcSqliteDriver`, needing `PlatformDirs.dataDir()`) — both open the same
schema; see "Persistence" below.

`TaskStore.completeIfOpen` and `reopenIfDone` return whether *this* call changed the row, and an
implementation must decide that inside the store — in SQL, in a lock, in whatever it has — never
against the `Task` it was handed. That is the whole idempotency guarantee; see the completion
notes below.

`:core` is a Kotlin Multiplatform module with an **android** and a **jvm** target, and all
hand-written code lives in a hand-declared `jvmShared` source set that both targets depend on —
not in `commonMain`. Both targets are the JVM, so `jvmShared` may use the JDK, which is why
`RecurrenceEngine` still speaks `java.time` rather than `kotlinx-datetime`. `commonMain` carries
no hand-written code on purpose; it starts earning its keep the day a browser target exists (ADR
0001, phase 7), and moving code there before that would buy a portability nothing needs at the
price of rewriting every date in the app. The one exception is *generated*: SQLDelight compiles
`data/db/*.sq` into query code under `commonMain` by default, which is fine — that generated code
touches no JDK API, only `app.cash.sqldelight`'s own multiplatform runtime. `DatabaseDriverFactory`
(`data/db/DatabaseDriverFactory.kt`) is the actual JDK/Android boundary, and it is `jvmShared`'s
first `expect`/`actual` pair: `expect class DatabaseDriverFactory` declares no constructor, so the
`androidMain` actual can take a `Context` and the `jvmMain` one a data directory `File` without
either matching the other's shape — still Beta as of Kotlin 2.0, hence
`-Xexpect-actual-classes` in `core/build.gradle.kts`.

Consequence worth knowing: **smart casts do not cross a module boundary.** `if (task.dueDate !=
null) task.dueDate.isAfter(…)` compiled while everything was one module and does not now. Bind a
local (`val due = task.dueDate`) or use `?.` — `task.dueDate?.isAfter(today) == true` — rather
than reaching for `!!`.

### Persistence gotchas

- Every id — task, project — is a **UUIDv7 string**, minted by `CadenceRepository` (not by
  storage) via `domain/id/UuidV7.kt` the moment a new row is created; `Task.id`/`Project.id`
  default to `""`, and `upsertTask`/`upsertProject` mint a real id exactly when that default is
  still blank. A recurring task's successor id is *derived*, not minted —
  `UuidV7.successorId(spawnedFromId, occurrenceDate)` — so two devices completing the same
  occurrence offline produce the same id once the phase-6 merge engine exists (ADR 0001,
  decision 4). `TaskStore.insert`/`ProjectStore.insert` therefore take a row that already carries
  its final id and return nothing.
- `taskRow`/`projectRow` are the schema's table names, not `task`/`project` — SQLDelight names the
  generated row class after the table, and `Task`/`Project` were already taken by the domain
  model. The tables live in `data/db/Task.sq`, `data/db/Project.sq`, `data/db/Attachment.sq` and
  `data/db/SyncState.sq`. **The schema is at version 3 and has a migration chain**: shipped
  installs of older versions exist, so a schema change means both editing the `.sq` file *and*
  adding an `N.sqm` beside it (`1.sqm` migrates 1→2 and adds `syncStateRow`; `2.sqm` migrates
  2→3, rebuilding both tables to drop foreign keys), the way `MIGRATION_3_4`
  used to work under Room. `AndroidSqliteDriver` runs migrations from its callback; the desktop's
  `JdbcSqliteDriver` has no such lifecycle, so `DatabaseDriverFactory` tracks the version in
  SQLite's own `PRAGMA user_version` — where **0 means "version 1, from before we counted"**,
  because nothing set it until now and the file already has the version-1 tables.
- **No write deletes the row it is about to insert, and `taskRow`/`projectRow` carry no foreign
  key.** Both are the same lesson. SQLite implements `INSERT OR REPLACE` as *delete the
  conflicting row, then insert*, both shells open the database with `PRAGMA foreign_keys = ON`,
  and the two tables used to cascade on `parentId` and set null on `projectId` — so saving an edit
  to a task deleted its checklist and its attachments, and merging one project row deleted its
  subprojects and emptied it of tasks. Writes are upserts now (`updateRow` + `insertIfAbsent`,
  because SQLite before 3.24 has no `ON CONFLICT … DO UPDATE` and minSdk 26 ships 3.19), and the
  merge is the same pair with last-writer-wins moved into the UPDATE's `WHERE` (`updateIfOlder`),
  which is also what removed a `SELECT` per merged row. The constraints went with them: a
  local-first app cannot enforce referential integrity when rows arrive in whatever order the
  pull pages them — a task landing before its project failed the whole merge with
  `SQLITE_CONSTRAINT_FOREIGNKEY` — which is why the Postgres mirror has never had one either.
  `attachmentRow → taskRow` keeps its cascade: attachments are local, never merged, and nothing
  references them back. Foreign keys are therefore switched on *after* migrating, not before —
  `DROP TABLE taskRow` inside `2.sqm` would otherwise take every attachment with it.
- Every row carries `updatedAt` (epoch millis) and `deletedAt` (epoch millis, nullable). **Deleting
  is a tombstone, not a `DELETE`** (`docs/adr/0002-supabase-sync.md`): the row stays, `deletedAt` is
  stamped, and every read in `Task.sq`/`Project.sq` filters `deletedAt IS NULL`. A delete has to
  travel — another device that merely fails to find a row cannot tell "deleted" from "never heard
  of it", and puts it back. Two consequences when adding a query: filter tombstones unless you are
  sync, and use `selectByIdIncludingDeleted` when you need to compare against one. `updatedAt` is
  stamped by `CadenceRepository.now()`, truncated to milliseconds so a row cannot ping-pong against
  Postgres's microsecond timestamps.
- `taskRow` stores dates as **epoch day** (`Long`) and times as **second of day** (`Long`);
  conversion to `LocalDate`/`LocalTime` happens in the private mappers in
  `data/db/SqlDelightStores.kt`. Nothing outside that file should touch the raw numbers.
- Recurrence rules are serialised into a **single TEXT column** as `v1;key=value;…` by
  `RecurrenceCodec` (now in `:core`, since storage is). Adding a rule field means extending the
  codec (and its `VERSION` handling), not adding a column. Malformed input decodes to `null`,
  never throws.
- `TaskQueries.completeIfOpen`/`reopenIfDone` report nothing directly — SQLDelight has no
  Room-style "return the row count" on an `UPDATE`. The store runs the update and
  `TaskQueries.changes()` (SQLite's `SELECT changes()`) inside one `database.transactionWithResult
  { }`, so the two run on the same connection and `changes()` reads back *this* statement's count.
- **An `IN :list` query is bounded, and the bound is 999.** SQLDelight expands `IN :taskIds` into
  one bind parameter per element, and `SQLITE_MAX_VARIABLE_NUMBER` is 999 on the SQLite that
  Android API 26-29 ship — inside the minSdk 26 range. The danger zone hands over *every* task
  id and a big project hands over its whole list, so `SqlDelightAttachmentStore` chunks at
  `SQL_VARIABLE_LIMIT` (900) rather than trusting the driver. The desktop's xerial driver allows
  32766 and a recent phone allows the same, so nothing in the test suite reproduces the crash —
  a new query taking a list has to chunk on the way in, and dedupe across chunks on the way out,
  since `DISTINCT` only ever sees one chunk.

### Behaviour worth knowing before editing

- **Completing a recurring task** (`CadenceRepository.setCompleted`) keeps the finished row in
  place — so it stays visible in Today — and *inserts a new row* for the next occurrence.
  Recurrence is modelled as a chain of rows, not one row with a moving date, linked by
  `spawnedFromId` — "this row replaces that finished occurrence". Everything below follows from
  the chain being rows rather than a moving date, so a new list or a new completion path has to
  answer the same three questions:
  - **Completing must be idempotent.** Every caller passes a `Task` the UI drew a row from, and a
    checkbox tapped twice hands back the same *open* snapshot both times.
    `TaskStore.completeIfOpen` closes the row in SQL and reports whether this call is the one that
    closed it, so only that call schedules the successor and the rest of the work reads the row
    back instead of trusting the snapshot. Don't replace it with a plain `update`.
  - **Reopening undoes both halves**: the row opens again and the occurrence that completion
    inserted is deleted (`TaskStore.openSuccessorsOf`), or the task would stand in the list twice.
    One that has itself been ticked off is left alone — the chain has moved on. `setCompleted`
    returns the ids it deleted so the ViewModel can cancel their alarms.
  - **Undated lists show a chain as one task.** `CadenceUiState.rootTasks` drops a completed
    occurrence that has been replaced (`withoutSupersededOccurrences`), because the Inbox and a
    project are not scoped to a day and a daily task would otherwise leave a struck-through copy
    in them every day. Today and Upcoming filter by date and Search is meant to reach history, so
    all three read `state.tasks` directly.
- **Subtasks are tasks with a `parentId`**, nested exactly one level deep — `addSubtask` files a
  step added under a subtask next to it rather than starting a third level. A parent and its
  steps share a project (`moveToProject` moves both), deleting a task deletes its steps
  (`deleteWithSubtasks`), and finishing a parent finishes whatever is still open beneath it. A
  recurring parent hands its checklist to the next occurrence unticked, with the subtask due
  dates shifted by the same span as the parent's. Which lists show them is a deliberate split:
  the container views (Inbox, projects) use `CadenceUiState.rootTasks()` because the parent
  already speaks for its steps there, while the date-driven views (Today, Upcoming, Search) show
  a dated subtask in its own right, labelled with the parent's title.
- **Reminders reconcile on every task emission**: the ViewModel collects `repository.tasks` and
  calls `ReminderScheduler.sync(tasks)`, which schedules *or cancels* a reminder for every task.
  Android's alarms are inexact (`setWindow`) deliberately, so the app needs no exact-alarm
  permission; the desktop has no AlarmManager at all, so `DesktopReminderScheduler` instead polls
  the synced task list every 30 seconds and fires a system-tray balloon for whatever just came
  due — which only works while the app is running, same accepted trade-off ADR 0001 §8 names for
  a killed Android process.
- **A fresh install starts empty.** There is no seeding: the first screen a new user sees is the
  empty state, not sample content. Anything that needs a populated app (screenshots, a demo) is
  built by importing a backup file, not by putting fixtures back into the app.
- **Deleting a project never silently hides tasks.** `ProjectStore.deleteWithChildren` runs in one
  transaction and either moves the affected tasks to the Inbox (`projectId = NULL`, the default)
  or deletes them; without that, a task filed under a deleted project would keep a `projectId`
  no project answers to and disappear from every list. The repository returns the ids of the
  tasks it deleted so the ViewModel can cancel their alarms — `ReminderScheduler.sync` only ever
  sees the tasks that still exist, so it cannot cancel one that is already gone.
- **The danger zone is the only wipe, and it is still a tombstone.** Settings → Danger zone →
  *Delete all data* runs `CadenceRepository.deleteEverything()`, which stamps `deletedAt` on every
  task and every project (`taskRow.tombstoneAll`, `projectRow.tombstoneAllRows`) rather than
  dropping rows — a `DELETE` would leave the other device with rows it has never seen deleted, and
  the next pull would hand the whole list back. Three gates, deliberately: the button, a dialog
  naming both counts, and the ordinary `UNDO_WINDOW` the write is deferred by, so the snackbar's
  **Undo** still catches it. Tasks are wiped before projects and the two are separate statements —
  a process killed between them leaves empty projects, never orphaned tasks — and both halves are
  idempotent, so running it again finishes the job.
- **Projects nest exactly one level**, which the editor enforces rather than the model:
  `CadenceUiState.nestingCandidates` returns nothing for a project that already has subprojects,
  and the "Nest under" section is then left out of the dialog.
- **Backup is a published contract, the DB is not.** `domain/backup/BackupCodec.kt` writes
  `{"format":"cadence.backup","version":1,…}` with ISO-8601 dates and recurrence as a nested
  object — deliberately *not* the packed `RecurrenceCodec` column — because a future web app
  reads these files. Unknown keys are ignored on read; a higher `version` is refused. Changing
  a field means bumping `VERSION` and keeping the old shape readable — but *adding* an optional
  field (`parentId`, `spawnedFromId`) deliberately leaves `VERSION` alone, since a bump would make
  older installs refuse the whole file over one key they can ignore. Links are repaired rather
  than trusted: a task pointing at a missing project lands in the Inbox, a recurrence link to an
  occurrence the file lacks is dropped, and a subtask whose parent the file
  lacks — or one in a chain or cycle — is set free by `normalisedParents`. It uses
  kotlinx.serialization (pure Kotlin, so the codec stays JVM-testable — `org.json` is stubbed
  in unit tests). Importing **merges** via `BackupStore.mergeAll` in one transaction — it used to
  replace both tables, which is why importing was a data-loss event. Ids come straight from the
  file and task→project links need no remapping; tasks referencing a project the file lacks fall
  back to the Inbox. No write empties the database as a side effect any more — the one operation
  that empties it is the Settings **danger zone**, which the user asks for outright. One rule
  decides every record: greater `updatedAt` wins, ties keep what is stored, and a tombstone
  competes on its timestamp like any other version rather than being special-cased — a v1 file,
  whose rows decode to `Instant.EPOCH`, therefore loses every conflict. **Importing has exactly
  one exception to that, and sync has none: a record that lands on a row this device has
  tombstoned is restored** (`BackupStore.mergeAll`'s `revivedAt`, stamped with the import's clock
  so the revival outlives the tombstone the server still holds). A file is a person asking for its
  contents, not a device offering a version, and a file written *before* the delete it is meant to
  undo is the ordinary case — `tools/todoist_import.py` derives ids from project names, so
  re-importing an export after deleting its staging project used to write nothing at all while
  still reporting the file's counts. A record that is itself a tombstone revives nothing.
- **Sync runs itself, and every step of a round is idempotent** (`data/sync/
  CadenceSyncEngine.kt`, ADR 0002). Signed out it does nothing at all and no request is made.
  Signed in, `syncOnce()` holds a `Mutex` and does: pull rows at or after the stored cursor →
  merge each page and advance the cursor **in the same transaction** → push everything written
  since the watermark, tombstones included → collect tombstones past 90 days, at most daily and
  only after a round that pushed. Five things are load-bearing:
  - **The cursor is the server's clock, the merge is the device's.** `server_updated_at` is
    written only by the server's trigger, so a device whose clock is wrong can lose a conflict
    but can never make itself invisible to the other device. The pull deliberately re-reads a
    five-second overlap, because Postgres's `now()` is transaction-start time and a transaction
    that began earlier may commit later, landing behind a cursor already advanced past it.
  - **There is no `dirty` column, and it is the server that makes that safe.** The push sends
    everything above the watermark, so a row that arrived *from* the server gets pushed straight
    back; the `BEFORE INSERT OR UPDATE` trigger in `supabase/migrations/` sees a timestamp that
    is not strictly greater and returns `NULL`, which skips *that row* without failing the batch.
    Ties keep the incumbent, on both sides.
  - **The new watermark is the newest `updatedAt` actually sent**, never "now": a row written
    while the push was in flight stands above it and waits for the next round rather than being
    skipped by a clock that ran ahead of the data.
  - **The session lives in `syncStateRow`, not in the settings file**, via a `SessionManager`
    handed to supabase-kt — it has to stay consistent with the cursors beside it. Signing out
    clears session, cursors and watermark and deletes **nothing**: the local database is the
    source of truth.
  - **The wire is the published shape, not the storage shape.** `data/sync/RemoteRecords.kt`
    speaks ISO dates and a `jsonb` recurrence object, and its DTOs are separate types from
    `BackupCodec`'s on purpose, so a Postgres column rename cannot change the shape of an
    exported backup file. Timestamps truncate to milliseconds in both directions, or a row
    pushed and pulled back returns strictly newer than its local copy and ping-pongs forever.
- **Nobody presses anything to sync** (ADR 0002, decisions 11, 13 and 14). Rounds start on app
  start and every return to the foreground, two seconds after a write, fire-and-forget on stop
  and window close, and — on the desktop only — every 15 minutes. There is no `WorkManager` and
  no Android poll: the phone is stale only while nobody is looking at it. Three things to know
  before adding a trigger or a list:
  - **The debounce is armed by the mutation, not by the task flow.** `CadenceViewModel.armSync()`
    is called from each task and project mutation; a row merged *in* from a pull lands in
    `repository.tasks` exactly like a local edit does, and a debounce watching that flow would
    have two devices pushing each other awake forever. Settings never arm it — they are
    per-device. A new mutation method therefore has to call `armSync()` itself.
  - **The lifecycle triggers hang off the shell, not the ViewModel**, and go through
    `CadenceSyncEngine.syncInBackground()`, which runs on the *application* scope:
    `:app-android`'s `CadenceApplication` observes `ProcessLifecycleOwner` (the Activity's
    lifecycle would sync on every rotation), `:app-desktop`'s `main()` syncs at startup, on
    `onCloseRequest` and on the poll it passes as `syncPollInterval`.
  - **The header shows where sync stands, and shows nothing at all signed out.**
    `ui/components/SyncControls.kt` holds the indicator, the pull-to-refresh wrapper and the
    failure snackbar; a shell hands the four top-level screens one `SyncControls` and the two
    flags in it are the whole platform difference (Android pulls, the desktop gets a button and
    `Ctrl`/`Cmd`+`R`). Every failed round raises a snackbar, `OFFLINE` included, and a new
    failure replaces the one on screen — which is why the engine publishes `failures` as a
    `SharedFlow` beside `status`: identical consecutive failures would collapse in a `StateFlow`.
  - **Realtime is an accelerant beside the round, and it never advances the cursor** (ADR 0002,
    decision 12). `CadenceSyncEngine.startRealtime()` opens one channel over both tables,
    filtered server-side on `user_id`; payloads decode with the same `RemoteTask`/`RemoteProject`
    and merge through the same `mergeAndAdvance` — with **null cursors**, because the socket is
    at-most-once and a cursor advanced past an event that never arrived skips that row forever.
    Every subscribe and every reconnect therefore runs a full `syncOnce()`, which is also what
    makes a missed event harmless. A deletion is a tombstone `UPDATE`, so `INSERT` and `UPDATE`
    cover everything and `DELETE` is not handled. The socket's lifetime is the shells' one real
    difference: `:app-android` opens it in `onStart` and closes it in `onStop` (a background
    websocket is the wakelock `WorkManager` was rejected to avoid), `:app-desktop` holds it for
    the process. `supabase/migrations/20260812120000_realtime.sql` is the server half — the
    publication plus `replica identity full`, without which an RLS-checked `UPDATE` payload is
    dropped.
- **Reminders are a per-device setting** (`CadenceSettings.remindersEnabled`), on by default on
  Android and off on the desktop — the default lives in each shell's `SettingsStore`, since that
  is the only thing that differs. Once a task exists on both devices both would otherwise fire
  for it at the same minute. Switching it off hands `ReminderScheduler.sync` the same tasks with
  their reminder times stripped, so it *cancels* what it had scheduled; an empty list would leave
  those alarms standing.
- Settings persist to `SharedPreferences` via `SharedPrefsSettingsStore` (not DataStore), the
  Android implementation of `:ui`'s `SettingsStore` port, exposed as a `StateFlow`. They stay
  per-device and out of sync — theme, density, language and reminders describe a screen or a
  machine, not a task list.

### UI conventions

- Two CompositionLocals carry what the M3 scheme cannot: `LocalCadenceColors` (priority colours,
  overdue accents) and `LocalCadenceDensity` (64dp comfortable vs 52dp compact rows, including
  whether the meta line renders). Read density from the local — do not hardcode row heights.
- **Priority is never colour alone.** `PrioritySpine` is always paired with its `P1`…`P4` label
  and announces itself as e.g. "P2 · High". Touch targets stay ≥44–48dp even where the design
  draws a 24dp circle. Type scale carries 1.3× line-height headroom for 200% font scaling.
- All icons go through `ui/components/AppIcons.kt` — don't import `Icons.Rounded.*` in screens.
- Labels in fixed-width slots (the bottom bar) use `FittedLabel`, which measures the slot and
  shrinks the type rather than wrapping — "Demnächst" is twice the width of "Today".

### Localisation

English and German. Since ADR 0001 phase 3 the strings live in **`:ui`'s
`src/commonMain/composeResources/values{,-de}/strings.xml`** and are reached as `Res.string.x` /
`Res.plurals.x` (`org.jetbrains.compose.resources`, not `androidx.compose.ui.res`) — the XML
shape, `<plurals>` included, is unchanged. `:app-android` keeps four strings of its own in `res/values/`:
the launcher label and the three the notification channel needs, none of which a composable ever
sees. `res/xml/locales_config.xml` still drives the Android 13+ per-app language picker.

The generated accessors are one top-level property per string, so files import them with
`de.andi1984.cadence.ui.resources.*` rather than 245 import lines.

**No user-visible string belongs in Kotlin.** Consequences worth knowing before adding a screen:

- The `domain/` layer produces no prose. `RecurrenceEngine.summarize()` returns a structured
  `RecurrenceSummary`/`MonthlyPhrase` (unit-testable on the JVM), and `ui/format/` turns it into
  words. `Priority` keeps only `shortLabel` ("P2"); its name and explanation live in
  `ui/format/PriorityLabels.kt`.
- Everything in `ui/format/` is `@Composable`, because the wording *and* the date patterns
  (`date_pattern_*`) come from resources. Use `currentLocale()` from `DateLabels.kt` rather than
  `Locale.getDefault()` — the app language can differ from the system one. It reads
  `LocalAppLocale`, which `CadenceTheme` provides from a `locale` parameter: the *shell* is what
  knows where the language comes from — Android's per-app picker. `:app-desktop` passes
  `Locale.getDefault()` for now; the explicit desktop language setting ADR 0001 decision 9
  describes (`CadenceSettings` has no language field yet) is left for a follow-up rather than
  bundled into "first runnable build".
- Android used to hand two of these labels over ready-made — `Formatter.formatShortFileSize` and
  `DateUtils.getRelativeTimeSpanString`. Neither has a desktop counterpart, so both are now
  `ui/format/DiagnosticLabels.kt`, resources and all.
- Never branch on a formatted string (an early bug compared a day header to `"Tomorrow"`);
  compare the underlying date or enum.
- Counts go through `<plurals>`, even where English and German happen to agree.
- The quick-add parser (`domain/parse/`) keeps its keywords in `QuickAddLexicon`, not in the
  grammar: `QuickAddParser.parse` takes one and `QuickAddSheet` picks it with
  `QuickAddLexicon.forLocale(currentLocale())`. Spelled-out counts are vocabulary too
  (`numbers`: `three`, `third`, `drei`, `dritten` all read as 3), so anywhere the grammar takes
  a digit it takes a word. Lexicons compose and English is always folded in,
  so `every 2 weeks` and `alle 2 Wochen` both parse in a German install. Weekday and month names
  are never listed — they come from `java.time` for the locale, so an unlisted language still
  reads `vendredi`. Adding a language means adding a lexicon, not touching the parser.
- Patterns are compiled by **ICU on device but by `java.util.regex` in the unit tests**, and the
  two disagree. ICU rejects the `(?u)`/`(?U)` inline flags (a crash the JVM tests cannot see), and
  `IGNORE_CASE` alone folds only ASCII on the JVM. `QuickAddPatterns` therefore spells word
  boundaries as `\p{L}` lookarounds and writes non-ASCII letters as two-case classes. Don't put
  `\b` or an inline flag back in.

## CI / releases

**Nothing runs on a hosted runner unless a human clicks it.** All three workflows are
`workflow_dispatch` only — no `push`, no `pull_request`, no `schedule`. `android.yml` and
`desktop.yml` used to run on every push to **any** branch and on every pull request, which is
where this repository's Actions minutes went; a single push started four runners, two of them
billed at macOS's 10x and Windows's 2x multipliers. **Verification is local now**: run the one command at the top of Commands before pushing,
because no runner will do it for you and a red branch will look green. The whole suite is
roughly five seconds of test time; if it takes minutes, something hangs — see the note below. Don't restore an
automatic trigger to any workflow in this repo without being asked for it outright.

What each one still does, when dispatched:

- `android.yml` — tests, then `build.sh apk`, uploading both APKs as artifacts.

Two backstops sit under all three, because the failure that prompted them cost hours rather
than minutes: **every `Test` task has a five-minute `timeout`** (the `subprojects` block in the
root `build.gradle.kts`) and **every job has a `timeout-minutes`**. GitHub's default job limit
is six hours, so a test that hangs instead of failing runs out the afternoon and reports
nothing. Five minutes is two orders of magnitude above what the suite needs, so it can only
ever catch a hang.

Caching is `gradle/actions/setup-gradle@v4` and nothing else. It caches `~/.gradle` — the
dependency cache, the wrapper and the **build cache**, which `gradle.properties` now switches
on — so the hand-rolled `actions/cache` step that used to sit beside it restored the same
directory a second time, with a `transforms-` glob that matched nothing, and has been removed
along with the `find ~/.gradle/caches -delete` step that pruned what the action manages.
- `desktop.yml` — tests, then `build.sh desktop`, over a matrix built from an `os` **input**
  that defaults to `ubuntu-latest` alone rather than fanning out to three runners; pass `all`
  for all three. jpackage runs on the target OS, so there is no cross-compiling a `.dmg` from
  Linux, and each leg installs the packaging tool its OS lacks (`fakeroot`/`rpm` on Linux, the
  WiX Toolset on Windows; macOS's `hdiutil` needs nothing extra). A `.dmg` or an `.msi` is the
  only artefact a Linux laptop genuinely cannot produce — that is the whole remaining case for
  the runner.
- `release.yml` — the only workflow that publishes anything, and **the only one that refuses to
  run off `main`**: it creates the tag from the commit it was dispatched on, so a run started on
  a branch would publish a release pointing at unmerged work. Its `targets` **input** decides
  what gets built, and defaults to `android+deb` — the APKs and the Linux `.deb`, one Linux
  runner each, which is what a Cadence release actually ships today. `android` and `deb` narrow
  that to one half; `all` adds the rpm, the tarball, the `.dmg` and the `.msi`, and with them the
  macOS and Windows runners at 10x and 2x. The download list in the release body is generated
  from the files that actually landed in `dist/`, so a run that packaged no `.msi` publishes no
  link to one. `dry-run` builds and tests everything and publishes nothing.

  Four jobs: `preflight` derives the version **once** (both build jobs read it from
  `needs.preflight.outputs`, so the two can never name one build two versions) and is where the
  branch guard and the "no commits since the last tag" guard live; `build-android` and
  `build-desktop` run in parallel; `publish` collects every `release-assets-*` artifact with one
  `pattern` download, because which artifacts exist depends on the input. The desktop job names
  `deb` outright rather than `desktop` — `build.sh` demotes a format to a warning only when an
  alias implied it, and a release that quietly ships without the `.deb` is exactly the failure
  the job exists to catch.

Since `build.sh` is what all three call, a release can equally be cut entirely on a laptop: `bash
.github/scripts/build.sh apk deb` stages `dist/`, then `gh release create … dist/*`.

The version is **derived, never edited**. `.github/scripts/next-version.sh` reads the
Conventional Commit subjects since the last `v*` tag: a `!` or a `BREAKING CHANGE:` footer bumps
major, any `feat:` bumps minor, anything else bumps patch, so a release always carries a version
that describes what went into it. With no tag yet the first release is `1.0.0`. The script
writes `version`/`version_code`/`notes` as step outputs; `app-android/build.gradle.kts` reads
`CADENCE_VERSION_NAME`/`CADENCE_VERSION_CODE` from the environment and falls back to `0.0.0-dev`
locally. `versionCode` is `major * 10000 + minor * 100 + patch`. Run the script locally to see
what releasing now would publish:

```bash
bash .github/scripts/next-version.sh    # prints the outputs when GITHUB_OUTPUT is unset
```

The release action creates the tag from the commit it ran on, so the next run measures from
there. `releases/latest/download/cadence-debug.apk` still serves the newest published build,
because each release is published with `make_latest` — it now moves when someone releases, not
when someone merges. Debug and release use different application IDs (`.debug` suffix) and
install side by side.

**The debug key is committed (`app-android/debug.keystore`) and must stay that way.** Android installs a
build over an existing app only when both carry the same signing certificate, and AGP invents a
fresh `~/.android/debug.keystore` wherever none exists — on a CI runner, that is every single
run. Releases up to v1.1.2 therefore each had their own key and could not update one another;
the phone just said "App not installed". Pinning the key is the fix, so don't move it back
behind `.gitignore` or let the debug `signingConfig` fall back to AGP's default.
`.github/scripts/check-signing.sh` runs in CI and fails the build if the debug APK's certificate
stops matching the committed keystore.

Release signing is optional on top of that: the four `CADENCE_*` env vars/secrets enable it,
otherwise the release build is signed with the debug key too (see the comment block in
`app-android/build.gradle.kts`) — which means an APK anyone can forge, acceptable only because the app
ships as a GitHub link rather than through a store.

## Agent skills

### Issue tracker

GitHub Issues, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Defaults (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`),
unmapped. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `docs/adr/` at the repo root. See `docs/agents/domain.md`.
