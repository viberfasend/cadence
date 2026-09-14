# Contributing to Cadence

Thanks for your interest in improving Cadence! This document explains how to set up your
environment, the conventions we follow, and how to get a change merged.

By participating, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to contribute

- 🐛 **Report bugs** — open a [bug report](../../issues/new/choose) with clear reproduction
  steps and the version you are running (Settings → About on Android, the window title on the
  desktop).
- 💡 **Suggest features** — open a [feature request](../../issues/new/choose) describing the
  problem you are trying to solve, not only the solution. The [roadmap](ROADMAP.md) lists what
  is already planned and what is deliberately not.
- 📝 **Improve docs** — typo fixes and clarifications are very welcome, in `README.md`,
  `CLAUDE.md` and `docs/`.
- 🌍 **Translate** — a new language is a `values-xx/strings.xml` plus a `QuickAddLexicon`;
  see the *Localisation* section of [`CLAUDE.md`](CLAUDE.md#localisation).
- 🔧 **Submit code** — fix a bug or build a feature (see below).

If you are planning a larger change, please open an issue first so we can discuss the approach
before you invest time. Decisions that outlive one change are written down as ADRs in
[`docs/adr/`](docs/adr/); a change that contradicts one should say so and why.

## Development setup

You need **JDK 17**. The desktop app needs nothing else; the Android app additionally needs the
**Android SDK** with `compileSdk` 35 (set `ANDROID_HOME`, or write `sdk.dir=…` into a
`local.properties` at the repo root). The server-side tests need **docker**.

```bash
git clone https://github.com/viberfasend/cadence.git
cd cadence
./gradlew :app-desktop:run      # the desktop app, from source
./gradlew assembleDebug         # the Android debug APK
```

## Project layout

```
core/          Kotlin Multiplatform — domain model, recurrence engine, quick-add parser, backup
               codec, SQLDelight stores, sync engine. Pure Kotlin; no Android imports.
ui/            Compose Multiplatform — theme, components, screens, the one ViewModel, strings
app-android/   the Android shell (navigation, alarms, widgets, SAF, SharedPreferences)
app-desktop/   the JVM shell (window, sidebar, shortcuts, tray, JSON settings, jpackage)
neon/          the server half: SQL migrations, migrate.sh, db.sh, a docker-based test harness
docs/adr/      architecture decision records
```

[`CLAUDE.md`](CLAUDE.md) is the long-form guide: the architecture, every invariant that has cost
real time to learn, and why things are the shape they are. It is written for AI coding agents,
and it is the best thing to read before touching anything non-trivial. If a change invalidates
something written there, update it in the same pull request.

## Running the checks

CI runs exactly this on every pull request, on Linux. Please make sure it passes locally first:

```bash
./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest \
          :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm
```

It takes a few seconds once Gradle is warm. If it takes minutes, something hangs — see the note
on `backgroundScope` in `CLAUDE.md`. If you touched anything under `neon/`:

```bash
bash neon/tests/run.sh          # needs docker
```

If you touched `QuickAddPatterns` or a lexicon, also run the one instrumented test on a device
or emulator, because the device's regex engine is not the JVM's:

```bash
./gradlew connectedDebugAndroidTest
```

## Coding conventions

- **Kotlin** — match the surrounding code. No lint or format task is wired up; keep to the
  style of the file you are in.
- **No user-visible string in Kotlin.** Every label goes through `Res.string.*` with an English
  and a German entry, and counts go through `<plurals>`.
- **Icons** go through `ui/components/AppIcons.kt`; **colours** through the M3 scheme or
  `LocalCadenceColors`; **row heights** through `LocalCadenceDensity`.
- **Tests** — new behaviour comes with tests. JUnit 4, `kotlin.test` and
  `kotlinx-coroutines-test`, hand-written fakes for the platform ports, and **the real SQLDelight
  stores over in-memory SQLite** for anything that touches storage. No mocking library.
- **Schema changes** need both the `.sq` edit and an `N.sqm` migration beside it, with any new
  column added last. **Sync wire and backup file shapes are published contracts**; see
  `CLAUDE.md` before changing either.
- **Don't add a dependency casually.** The app is small on purpose.

## Commit messages

We follow [Conventional Commits](https://www.conventionalcommits.org/). The type prefix drives
the version number and the release notes (`.github/scripts/next-version.sh`): `feat` bumps
minor, a `!` or a `BREAKING CHANGE:` footer bumps major, everything else bumps patch.

```
feat(quick-add): parse "next month"
fix(sync): truncate pulled timestamps to milliseconds
docs(readme): document the desktop data directory
```

Common types: `feat`, `fix`, `perf`, `docs`, `refactor`, `test`, `chore`, `ci`. Scopes are free
text; the module or feature name works well.

## Pull request process

1. **Fork** the repo and create a branch from `main` (e.g. `feat/tag-colors` or
   `fix/import-crash`).
2. Make your change, with tests, keeping the checks above green.
3. Update documentation (`README.md`, `CLAUDE.md`, the relevant ADR) if behaviour or setup
   changes.
4. Open a pull request against `main` and fill in the template. Link any related issue.
5. Ensure CI passes. A maintainer will review and may request changes.

## License of contributions

Cadence is licensed under **GPL-3.0-or-later**. By submitting a contribution, you agree that
your work will be licensed under the same terms. Don't submit code you don't have the right to
license this way.

Thank you for contributing! 💜
