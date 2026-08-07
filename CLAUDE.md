# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Cadence — a local-first native Android todo app (Kotlin, Jetpack Compose, Material 3, Room).
No cloud, no account, no analytics. Package `de.andi1984.cadence` throughout.

Two modules: `:core` (Kotlin Multiplatform, the domain layer) and `:app` (the Android app).
A desktop app for Ubuntu/macOS/Windows is being built out of `:core` in the steps laid out in
[`docs/adr/0001-desktop-app-and-multi-device-sync.md`](docs/adr/0001-desktop-app-and-multi-device-sync.md).

The product rule the whole app is built on: **importance first, due date breaks ties**
(`ui/TaskSorting.kt`). Recurrence supports both calendar rules and "n days after completion".

## Commands

```bash
./gradlew testDebugUnitTest :core:jvmTest   # what CI runs — see below for why both
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # falls back to the debug key when no CADENCE_KEYSTORE is set

# a single test class / method (method names are backticked sentences)
./gradlew :core:jvmTest --tests "de.andi1984.cadence.RecurrenceEngineTest"
./gradlew :core:jvmTest --tests "*RecurrenceEngineTest.monthly on a fixed day*"
```

`:core`'s tests are one source set compiled twice: `:core:jvmTest` is the desktop compilation and
`:core:testDebugUnitTest` the Android one. `testDebugUnitTest` alone therefore misses nothing in
`:core` today, but it also never exercises the JVM target the desktop app will be built on, so CI
names both. `:app`'s only remaining unit test is `RecurrenceCodecTest`, which covers the packed
storage column and runs under `testDebugUnitTest`.

JDK 17, compileSdk/targetSdk 35, minSdk 26. No lint or format task is wired up.

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

**Wiring** is hand-rolled in `AppContainer`, built once in `CadenceApplication.onCreate` and
reached through `ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY`. No DI framework —
new singletons go in `AppContainer`.

**Navigation** lives entirely in `ui/CadenceApp.kt`: route constants in `Routes`, one `NavHost`,
bottom bar + FAB shown only on the four top-level destinations. Quick-add is a sheet driven by
composable state, not a route.

### Layers

```
:core  domain/     pure Kotlin — model, RecurrenceEngine, QuickAddParser, BackupCodec. NO
                   Android imports; this is what the JVM unit tests exercise. Keep it that way.
       data/       CadenceRepository, and the TaskStore/ProjectStore/BackupStore ports it needs
:app   data/db/    Room entities + DAOs, and the RoomStores that implement those ports
       data/backup/BackupIo (SAF read/write), AutoBackupSync
       reminders/  AlarmManager scheduling, notification receiver, boot re-schedule
       ui/         theme, shared components, one package per screen
```

**Storage is a port, not a layer.** `CadenceRepository` lives in `:core` because the completion
and recurrence rules do, and it reaches storage through three interfaces in `data/Stores.kt` that
speak `Task` and `Project` rather than rows and carry no database annotation. `:app` supplies
`RoomTaskStore`/`RoomProjectStore`/`RoomBackupStore` (`room-runtime` is an Android artifact and
stays on that side); the desktop app will supply its own. Every `toDomain`/`toEntity` in the app
is now in `data/db/RoomStores.kt` and `data/db/Entities.kt` — nowhere else.

`TaskStore.completeIfOpen` and `reopenIfDone` return whether *this* call changed the row, and an
implementation must decide that inside the store — in SQL, in a lock, in whatever it has — never
against the `Task` it was handed. That is the whole idempotency guarantee; see the completion
notes below.

`:core` is a Kotlin Multiplatform module with an **android** and a **jvm** target, and all of its
code lives in a hand-declared `jvmShared` source set that both targets depend on — not in
`commonMain`. Both targets are the JVM, so `jvmShared` may use the JDK, which is why
`RecurrenceEngine` still speaks `java.time` rather than `kotlinx-datetime`. `commonMain` stays
empty on purpose; it starts earning its keep the day a browser target exists (ADR 0001, phase 7),
and moving code there before that would buy a portability nothing needs at the price of
rewriting every date in the app.

Consequence worth knowing: **smart casts do not cross a module boundary.** `if (task.dueDate !=
null) task.dueDate.isAfter(…)` compiled while everything was one module and does not now. Bind a
local (`val due = task.dueDate`) or use `?.` — `task.dueDate?.isAfter(today) == true` — rather
than reaching for `!!`.

### Persistence gotchas

- Database version is **4** with `exportSchema = false` and **no destructive fallback** — the
  migrations live next to the `@Database` class in `CadenceDatabase.kt`. Any entity change
  therefore needs its own `Migration` and a version bump; without one the app crashes on open
  rather than silently emptying itself.
- `TaskEntity` stores dates as **epoch day** (`Long`) and times as **second of day** (`Int`);
  conversion to `LocalDate`/`LocalTime` happens in the `toDomain`/`toEntity` extensions in
  `data/db/Entities.kt`. Nothing outside that file should touch the raw numbers.
- Recurrence rules are serialised into a **single TEXT column** as `v1;key=value;…` by
  `RecurrenceCodec`. Adding a rule field means extending the codec (and its `VERSION` handling),
  not adding a column. Malformed input decodes to `null`, never throws.

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
  calls `ReminderScheduler.sync(tasks)`, which schedules *or cancels* an alarm for every task.
  Alarms are inexact (`setWindow`) deliberately, so the app needs no exact-alarm permission.
- **A fresh install starts empty.** There is no seeding: the first screen a new user sees is the
  empty state, not sample content. Anything that needs a populated app (screenshots, a demo) is
  built by importing a backup file, not by putting fixtures back into the app.
- **Deleting a project never silently hides tasks.** `ProjectStore.deleteWithChildren` runs in one
  transaction and either moves the affected tasks to the Inbox (`projectId = NULL`, the default)
  or deletes them; without that, a task filed under a deleted project would keep a `projectId`
  no project answers to and disappear from every list. The repository returns the ids of the
  tasks it deleted so the ViewModel can cancel their alarms — `ReminderScheduler.sync` only ever
  sees the tasks that still exist, so it cannot cancel one that is already gone.
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
  in unit tests). Importing **replaces** both tables via `BackupStore.replaceAll` in one
  transaction, so ids come straight from the file and task→project links need no remapping;
  tasks referencing a project the file lacks fall back to the Inbox.
- **Automatic backup sync is opt-in, and asked exactly once.** The offer appears after the first
  *successful* manual export or import (`SettingsScreen` holds the picked uri back until the
  outcome is `Exported`/`Imported`), and `AutoBackupSettings.offered` makes sure a "not now" is
  never asked again — `AutoBackupSection` in Settings is the only way in and out from then on.
  Manual export and import stay untouched whichever way it is answered. `AutoBackupSync` then
  writes the file on every change (debounced 2s) and on `ON_STOP`, and reads it on `ON_START`.
  Four things are load-bearing:
  - **Choosing and changing the file are buttons, not the switch.** A switch that silently opens
    a system file picker reads as broken, so `AutoBackupSection` only shows the switch once a
    file is named — pause/resume is genuinely all it does then — and puts picking or re-picking
    the file behind its own "Choose a file to sync" / "Change file" actions, both wired to the
    same export-and-enable flow as the switch used to trigger. "Change file" is always visible
    once a file exists, including next to the failure message, because a file that stopped
    working (moved, deleted, permission revoked) used to be a dead end: resuming always replayed
    the same broken uri, and nothing in the UI could point sync at a different file.
  - **It never reads back a file it wrote itself.** `AutoBackupPolicy.shouldImport` compares the
    file's `exportedAt` against `AutoBackupSettings.lastSyncedAt` — the timestamp of the last file
    this device wrote *or* imported — because an import replaces every row and re-reading our own
    export would undo everything added since. A device that has never synced with the file
    (`lastSyncedAt == null`) refuses to import it; a foreign file only ever arrives through an
    explicit manual import, which records the timestamp and lets every later open follow along.
  - **The picker's uri grant dies with the process**, so the export contract is subclassed
    (`PersistableCreateDocument`) to ask for a persistable one and `AutoBackupSync.enable` takes
    it. If that fails the switch stays off and says why rather than promising a sync that stops
    at the next restart.
  - **It runs on an application-scoped coroutine** in `AppContainer`, not `viewModelScope`: the
    write that starts as the user leaves has to outlive the screen it started from. A `Mutex`
    serialises reads against writes.
- Settings persist to `SharedPreferences` via `SettingsStore` (not DataStore), exposed as a
  `StateFlow`.

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

English and German (`values/` and `values-de/`), listed in `res/xml/locales_config.xml` for the
Android 13+ per-app language picker. **No user-visible string belongs in Kotlin.** Consequences
worth knowing before adding a screen:

- The `domain/` layer produces no prose. `RecurrenceEngine.summarize()` returns a structured
  `RecurrenceSummary`/`MonthlyPhrase` (unit-testable on the JVM), and `ui/format/` turns it into
  words. `Priority` keeps only `shortLabel` ("P2"); its name and explanation live in
  `ui/format/PriorityLabels.kt`.
- Everything in `ui/format/` is `@Composable`, because the wording *and* the date patterns
  (`date_pattern_*`) come from resources. Use `currentLocale()` from `DateLabels.kt` rather than
  `Locale.getDefault()` — the app language can differ from the system one.
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

`.github/workflows/android.yml` runs tests then builds both APKs on every push to **any** branch
and on pull requests. A push to `main` — i.e. a merged PR — additionally publishes a release.

The version is **derived, never edited**. `.github/scripts/next-version.sh` reads the
Conventional Commit subjects since the last `v*` tag: a `!` or a `BREAKING CHANGE:` footer bumps
major, any `feat:` bumps minor, anything else bumps patch, so every merge ships a build. With no
tag yet the first release is `1.0.0`. The script writes `version`/`version_code`/`notes` as step
outputs; `app/build.gradle.kts` reads `CADENCE_VERSION_NAME`/`CADENCE_VERSION_CODE` from the
environment and falls back to `0.0.0-dev` locally. `versionCode` is
`major * 10000 + minor * 100 + patch`. Run the script locally to see what a merge would publish:

```bash
bash .github/scripts/next-version.sh    # prints the outputs when GITHUB_OUTPUT is unset
```

The release action creates the tag from the merge commit, so the next run measures from there.
`releases/latest/download/cadence-debug.apk` still serves the newest build, now because each
release is published with `make_latest`. Branch and PR runs only upload APK artifacts — the
version they print is a preview of what merging would publish. Debug and release use different
application IDs (`.debug` suffix) and install side by side.

**The debug key is committed (`app/debug.keystore`) and must stay that way.** Android installs a
build over an existing app only when both carry the same signing certificate, and AGP invents a
fresh `~/.android/debug.keystore` wherever none exists — on a CI runner, that is every single
run. Releases up to v1.1.2 therefore each had their own key and could not update one another;
the phone just said "App not installed". Pinning the key is the fix, so don't move it back
behind `.gitignore` or let the debug `signingConfig` fall back to AGP's default.
`.github/scripts/check-signing.sh` runs in CI and fails the build if the debug APK's certificate
stops matching the committed keystore.

Release signing is optional on top of that: the four `CADENCE_*` env vars/secrets enable it,
otherwise the release build is signed with the debug key too (see the comment block in
`app/build.gradle.kts`) — which means an APK anyone can forge, acceptable only because the app
ships as a GitHub link rather than through a store.

## Agent skills

### Issue tracker

GitHub Issues, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Defaults (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`),
unmapped. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `docs/adr/` at the repo root. See `docs/agents/domain.md`.
