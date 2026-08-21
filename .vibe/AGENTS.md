# Mistral Vibe Agents Configuration for Cadence

This file provides agent-specific instructions for Mistral Vibe when working with the Cadence Android project.

## Project Overview

**Cadence** is a local-first native Android todo app built with:
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material 3
- **Architecture**: Single ViewModel pattern
- **Persistence**: Room Database
- **Target**: Android 26+ (minSdk), Android 35 (targetSdk)

## Architecture Guidelines

### Single ViewModel Pattern
- **ONE ViewModel** (`CadenceViewModel`) for the entire app
- All screens receive the same `CadenceUiState` via `StateFlow`
- Derived views (overdue, inbox, tasks-in-project) are computed in `CadenceUiState`
- No per-screen ViewModel or ViewModel classes

### Layer Separation
```
domain/     → Pure Kotlin (models, RecurrenceEngine, QuickAddParser, BackupCodec)
data/       → Room entities + DAOs, CadenceRepository, BackupIo
reminders/  → AlarmManager scheduling, notification receiver
ui/         → Theme, shared components, one package per screen
```

### Key Behaviors

1. **Recurring Tasks**: Modeled as a chain of rows, not one row with a moving date
2. **Reminders**: Reconcile on every task emission via `ReminderScheduler.sync(tasks)`
3. **Fresh Install**: Starts empty (no seeding)
4. **Project Deletion**: Never silently hides tasks - moves to Inbox or deletes with alarm cleanup
5. **Project Nesting**: Exactly one level deep (enforced by UI, not model)
6. **Backup**: Published contract with forwards/backwards compatibility

## Coding Standards

### Kotlin Style
- Use `data class` for immutable models
- Use `val` for immutable properties, `var` only when necessary
- Use `suspend` functions for coroutines
- Use `Flow` for reactive data
- Use sealed classes/interfaces for state management

### Compose Conventions
- Use `Modifier` chains for styling
- Use `remember` for state that survives recomposition
- Use `LaunchedEffect` for side effects
- Use `CompositionLocal` for app-wide configuration
- Follow Material 3 design guidelines

### Database Patterns
- Store dates as epoch day (`Long`), times as second of day (`Int`)
- Use `toDomain()`/`toEntity()` extensions for conversions
- Use `RecurrenceCodec` for recurrence rule serialization
- Add proper indexes for query performance
- Use foreign key constraints for data integrity

## Common Tasks

### Adding a New Screen
1. Add route constant in `ui/Routes.kt`
2. Add composable in appropriate `ui/*` package
3. Add navigation in `ui/CadenceApp.kt`
4. Add derived state to `CadenceUiState` if needed

### Adding a New Feature
1. Add domain model in `domain/model/`
2. Add database entity and DAO in `data/db/`
3. Add repository methods in `CadenceRepository`
4. Add ViewModel methods in `CadenceViewModel`
5. Add UI in appropriate `ui/*` package

### Adding a New String Resource
1. Add to `app/src/main/res/values/strings.xml`
2. Add German translation to `app/src/main/res/values-de/strings.xml`
3. Use `stringResource(R.string.name)` in code
4. Never hardcode user-visible strings in Kotlin

## Testing

### Unit Tests
```bash
./gradlew testDebugUnitTest
```
- Test domain layer (RecurrenceEngine, QuickAddParser, BackupCodec)
- Pure Kotlin, no Android dependencies

### Instrumented Tests
```bash
./gradlew connectedDebugAndroidTest
```
- Requires device/emulator
- Test UI and integration

## Localization

- **Languages**: English (`values/`) and German (`values-de/`)
- **Locale Config**: `res/xml/locales_config.xml`
- **Pattern**: All user-visible strings in resource files
- **Plurals**: Use `<plurals>` for count-dependent strings
- **Date Patterns**: Use `currentLocale()` from `DateLabels.kt`

## Performance Considerations

- Use database indexes for frequently queried columns
- Use `Flow` with `distinctUntilChanged()` to prevent unnecessary recompositions
- Use `StateFlow` with `SharingStarted.WhileSubscribed()` for UI state
- Avoid loading entire datasets into memory for large collections
- Use Room's `@Transaction` for multi-table operations

## Error Handling

- Use `RepositoryResult` for operations that can fail
- Show user-friendly error messages via snackbar
- Provide undo functionality for destructive operations
- Validate input before database operations
- Handle edge cases gracefully

## Security

- Never commit `local.properties` or `signing.properties`
- Never commit keystore files
- Use environment variables for sensitive data
- Follow Android security best practices

## CI/CD

- No workflow runs automatically: all three are `workflow_dispatch` only, to keep Actions
  minutes at zero. Run tests locally (`./gradlew testDebugUnitTest :core:jvmTest`) before pushing
- `bash .github/scripts/build.sh` produces the whole release into `dist/` on a laptop; the
  workflows call the same script
- Version derived from Conventional Commits since last tag
- Releases are published by hand (`gh release create`) or by dispatching `release.yml`, which
  only runs from `main` and builds the Android APKs plus the Linux `.deb` unless its `targets`
  input asks for more
- Debug and release builds have different application IDs

## Mistral Vibe Specific Instructions

When working in this repository:

1. **Always** follow the existing architecture patterns
2. **Always** add proper string resources for new UI elements
3. **Always** include German translations
4. **Always** use the single ViewModel pattern
5. **Always** respect the layer separation
6. **Never** hardcode user-visible strings in Kotlin code
7. **Never** add Android imports to domain layer
8. **Never** use `fallbackToDestructiveMigration()` for production databases

## File Patterns to Ignore

See `.vibeignore` for complete list of files and directories to ignore.
