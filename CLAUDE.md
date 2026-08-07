# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Cadence — a local-first native Android todo app (Kotlin, Jetpack Compose, Material 3, Room).
No cloud, no account, no analytics. Single module `:app`, package `de.andi1984.cadence`.

The product rule the whole app is built on: **importance first, due date breaks ties**
(`ui/TaskSorting.kt`). Recurrence supports both calendar rules and "n days after completion".

## Commands

```bash
./gradlew testDebugUnitTest        # JVM unit tests (recurrence, quick-add, backup, repository)
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # falls back to the debug key when no CADENCE_KEYSTORE is set

# a single test class / method (method names are backticked sentences)
./gradlew testDebugUnitTest --tests "de.andi1984.cadence.RecurrenceEngineTest"
./gradlew testDebugUnitTest --tests "*RecurrenceEngineTest.monthly on a fixed day*"
```

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
domain/     pure Kotlin — model, RecurrenceEngine, QuickAddParser, BackupCodec. NO Android
            imports; this is what the JVM unit tests exercise. Keep it that way.
data/       Room entities + DAOs, CadenceRepository, BackupIo (SAF read/write)
reminders/  AlarmManager scheduling, notification receiver, boot re-schedule
ui/         theme, shared components, one package per screen
```

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
    `TaskDao.completeIfOpen` closes the row in SQL and reports whether this call is the one that
    closed it, so only that call schedules the successor and the rest of the work reads the row
    back instead of trusting the snapshot. Don't replace it with a plain `update`.
  - **Reopening undoes both halves**: the row opens again and the occurrence that completion
    inserted is deleted (`TaskDao.openSuccessorsOf`), or the task would stand in the list twice.
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
- **Deleting a project never silently hides tasks.** `ProjectDao.deleteWithChildren` runs in one
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
  in unit tests). Importing **replaces** both tables via `BackupDao.replaceAll` in one
  transaction, so ids come straight from the file and task→project links need no remapping;
  tasks referencing a project the file lacks fall back to the Inbox.
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

Release signing is optional: the four `CADENCE_*` env vars/secrets enable it, otherwise the
release build is signed with the debug key (see the comment block in `app/build.gradle.kts`).
