# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Cadence — a local-first native Android todo app (Kotlin, Jetpack Compose, Material 3, Room).
No cloud, no account, no analytics. Single module `:app`, package `de.andi1984.cadence`.

The product rule the whole app is built on: **importance first, due date breaks ties**
(`ui/TaskSorting.kt`). Recurrence supports both calendar rules and "n days after completion".

## Commands

```bash
./gradlew testDebugUnitTest        # JVM unit tests (recurrence engine + quick-add parser)
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # falls back to the debug key when no CADENCE_KEYSTORE is set

# a single test class / method (method names are backticked sentences)
./gradlew testDebugUnitTest --tests "de.andi1984.cadence.RecurrenceEngineTest"
./gradlew testDebugUnitTest --tests "*RecurrenceEngineTest.monthly on a fixed day*"
```

JDK 17, compileSdk/targetSdk 35, minSdk 26. No lint or format task is wired up.
Instrumented tests have deps declared but no `androidTest` sources exist.

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
domain/     pure Kotlin — model, RecurrenceEngine, QuickAddParser. NO Android imports;
            this is what the JVM unit tests exercise. Keep it that way.
data/       Room entities + DAOs, CadenceRepository, SeedData
reminders/  AlarmManager scheduling, notification receiver, boot re-schedule
ui/         theme, shared components, one package per screen
```

### Persistence gotchas

- Database version is **1** with `fallbackToDestructiveMigration()` and `exportSchema = false`.
  Any entity change therefore **wipes user data** on upgrade. Adding a real migration means
  bumping the version and dropping the destructive fallback.
- `TaskEntity` stores dates as **epoch day** (`Long`) and times as **second of day** (`Int`);
  conversion to `LocalDate`/`LocalTime` happens in the `toDomain`/`toEntity` extensions in
  `data/db/Entities.kt`. Nothing outside that file should touch the raw numbers.
- Recurrence rules are serialised into a **single TEXT column** as `v1;key=value;…` by
  `RecurrenceCodec`. Adding a rule field means extending the codec (and its `VERSION` handling),
  not adding a column. Malformed input decodes to `null`, never throws.

### Behaviour worth knowing before editing

- **Completing a recurring task** (`CadenceRepository.setCompleted`) keeps the finished row in
  place — so it stays visible in Today — and *inserts a new row* for the next occurrence.
  Recurrence is modelled as a chain of rows, not one row with a moving date.
- **Reminders reconcile on every task emission**: the ViewModel collects `repository.tasks` and
  calls `ReminderScheduler.sync(tasks)`, which schedules *or cancels* an alarm for every task.
  Alarms are inexact (`setWindow`) deliberately, so the app needs no exact-alarm permission.
- **Seeding**: `seedIfEmpty()` fills a fresh install with sample data from `SeedData`.
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
- The quick-add parser (`domain/parse/`) still only recognises English keywords (`tomorrow`,
  `every 2 weeks`). The German `quick_add_hint` says so; teaching it German is a roadmap item.

## CI / releases

`.github/workflows/android.yml` runs tests then builds both APKs on every push to **any** branch.
Non-tag pushes force-move the `latest` tag and republish the rolling release, so
`releases/latest/download/cadence-debug.apk` always serves the newest build. Pushing a `v*` tag
publishes a separate permanent release. Debug and release use different application IDs
(`.debug` suffix) and install side by side. `versionCode`/`versionName` are still hardcoded at
`1` / `1.0`.

Release signing is optional: the four `CADENCE_*` env vars/secrets enable it, otherwise the
release build is signed with the debug key (see the comment block in `app/build.gradle.kts`).
