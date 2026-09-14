<div align="center">

# Cadence

**A local-first todo app for Android and the desktop. Importance first, due date breaks ties.**

Your tasks live in a SQLite file on your device. No account required, no analytics, no cloud
unless you point it at your own.

[![CI](https://github.com/viberfasend/cadence/actions/workflows/ci.yml/badge.svg)](https://github.com/viberfasend/cadence/actions/workflows/ci.yml)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF.svg)](https://kotlinlang.org/docs/multiplatform.html)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4.svg)](https://www.jetbrains.com/compose-multiplatform/)

</div>

> **Status:** feature-complete as a single-user list app, in daily use on Android and Linux.
> macOS and Windows builds are produced by the same code and the same release workflow but see
> far less real-world use. What comes next is on the [roadmap](ROADMAP.md).

---

## Why Cadence?

Most todo apps either sort by date and bury what matters under what is merely due, or keep your
list on someone else's server. Cadence does neither. Every list is ordered by **importance first,
due date second**, so the thing you should be doing sits on top even when ten small things are due
today. And every task is stored locally: the app works fully offline and signed out, and syncing
between your own devices is an optional sign-in against a database you control.

## Features

- 🗂️ **Local-first** — one SQLite database per device, and it is the source of truth. Nothing
  leaves the device until you sign in.
- ⭐ **Importance first** — four priority levels, always spelled out (`P1`…`P4`), never colour
  alone. Due date only breaks ties.
- ⌨️ **Quick add** — one line, parsed as you type, in English and German:
  `Call the dentist tomorrow at 17:00 !p1 #Health @phone`.
- 🔁 **Recurrence that understands both kinds of rule** — calendar rules (`every 2 weeks on thu`,
  `every last weekday`) and *n days after completion*. A task completed late catches up to the
  next occurrence that is not behind you.
- 📥 **Today, Upcoming, Inbox, Triage, Projects, Tags** — projects nest one level, tags are
  cross-cutting labels, and Triage walks the backlog one card at a time.
- ☑️ **Subtasks, notes, attachments** — files and links on a task, content-addressed and
  deduplicated on disk.
- 🔔 **Reminders** — exact, Doze-proof alarms on Android; a tray notification on the desktop.
  Several lead times per task.
- 🖥️ **A real desktop app, not a phone app in a window** — drag and drop, right-click menus, a
  project-tree sidebar, two panes on wide windows and a command palette (`Ctrl`/`Cmd`+`K`).
- 📱 **Home-screen widgets** on Android — the Today list and a next-task card.
- 🔄 **Optional sync** — sign in and your devices merge through a Postgres database you host
  yourself, last-writer-wins, tombstones and all. See [Sync](#sync-optional).
- 💾 **Backup and import** — a versioned JSON file you can read, plus a converter for Todoist
  exports.
- 🌍 **English and German**, including the quick-add grammar, with a per-app language picker on
  Android 13+.
- ♿ **Accessible by design** — 44dp+ touch targets, 200% font scaling, two row densities, light
  and dark.

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin, one codebase for every target |
| UI | Compose Multiplatform, Material 3 |
| Storage | SQLite via [SQLDelight](https://sqldelight.github.io/sqldelight/) — the same schema and migration chain on every platform |
| Android shell | Jetpack Navigation, Glance widgets, AlarmManager |
| Desktop shell | Compose for Desktop, jpackage installers (`.deb`, `.rpm`, `.dmg`, `.msi`) |
| Sync (optional) | Ktor client talking PostgREST to a [Neon](https://neon.tech) Data API, with Neon Auth |

## Get it

### Android

Open this link on the phone and install it:

**https://github.com/viberfasend/cadence/releases/latest/download/cadence-release.apk**

That URL always serves the newest published build. Android will ask you to allow installs from
this source, which is expected for an app that does not come from a store. Tap the same link
again later to update in place: the release APK is signed with a stable release key, so a new
build installs over the old one and keeps your tasks.

> If you still have an install from **before 2.0** (signed with the debug key), Android will
> refuse the update with *"App not installed"*: the signing key changed. Export a backup first
> (Settings → Backup), uninstall once, install again and import.

A debug-signed build sits beside it as `cadence-debug.apk`. The two have different application
IDs and install side by side.

### Desktop

Grab the package for your OS from the [latest release](https://github.com/viberfasend/cadence/releases/latest):
`.deb`, `.rpm` or a tarball on Linux, `.dmg` on macOS, `.msi` on Windows. Every release ships the
Linux packages; the macOS and Windows installers are built on request, so if the newest release
lacks one, the previous one may have it, or [build it yourself](#building-from-source).

## Quick add

```
Pay rent every 1st !p2 #Home @bills
Water the plants 3 days after done
Call the dentist tomorrow at 17:00 !p1
Take out recycling every 2 weeks on thu
Steuer 24.12. @papierkram
```

Recognised: `!p1`–`!p4`, `#Project`, `@tag` (as many as you like; a tag that does not exist is
created), `today`/`tomorrow`/`next friday`/`in 3 days`/`24.12.`/`24 Dec`/`2026-12-24`, `at 17:00`/
`9am`, and recurrence phrases (`daily`, `every 2 weeks on thu`, `every 1st`, `every last weekday`,
`3 days after done`). German works throughout (`morgen`, `jeden 1.`, `alle drei Tage`, `18 Uhr`).
Everything is optional, unrecognised words stay in the title, and every parsed chip can be
corrected by tapping it. A bare time defaults the date to today.

## Where your data lives

| Platform | Location |
|---|---|
| Android | the app's private storage (`Settings → Backup` exports it) |
| Linux | `$XDG_DATA_HOME/cadence/` (default `~/.local/share/cadence/`) |
| macOS | `~/Library/Application Support/Cadence/` |
| Windows | `%APPDATA%\Cadence\` |

The directory holds the SQLite database, attachments (content-addressed by SHA-256), and on the
desktop two small JSON files for settings and window state. To start fresh, use **Settings →
Danger zone → Delete all data** (it asks, and offers Undo), or quit and delete the directory.

## Sync (optional)

Cadence syncs hub-and-spoke through a Postgres database: each device pushes what it wrote and
pulls what the others wrote, last-writer-wins per row, deletes travel as tombstones, and the
server enforces row-level security so an account only ever sees its own rows. There is no
Cadence-operated server. The published builds are wired to the maintainer's own instance, which
accepts no sign-ups, so to sync your devices you point a build at **your own** Neon project. The
walkthrough is in [docs/self-hosting.md](docs/self-hosting.md), and the design in
[ADR 0002](docs/adr/0002-supabase-sync.md) and [ADR 0005](docs/adr/0005-neon-sync.md).

A build with no sync endpoint configured simply hides sign-in. Everything else works the same.

## Coming from Todoist

Export your Todoist data (Settings → Backup, one CSV per project), convert the folder with
[`tools/todoist_import.py`](tools/README.md) and import the result under **Settings → Backup**:

```bash
python3 tools/todoist_import.py ~/Downloads/"Todoist backup 2026-08-12 2248 UTC"
python3 tools/todoist_import.py ~/Downloads/"Todoist backup …" --todoist-token 0123…   # exact dates for recurring tasks
```

Everything lands in one staging project named `Import <date>` for you to sort. Projects,
sections, subtasks, comments, priorities, due dates and repeat phrases in German and English all
come across. Importing merges, so re-running it updates rather than duplicates.

## Building from source

**Prerequisites:** JDK 17. For the Android app additionally the Android SDK with `compileSdk`
35 (`ANDROID_HOME` set, or a `local.properties` with `sdk.dir`). The desktop app needs nothing
beyond the JDK; native installers additionally need the OS's own packaging tool (`fakeroot`
for `.deb`, `rpmbuild` for `.rpm`, WiX on Windows).

```bash
git clone https://github.com/viberfasend/cadence.git
cd cadence

./gradlew :app-desktop:run                    # launch the desktop app from source
./gradlew assembleDebug                       # app-android/build/outputs/apk/debug/app-android-debug.apk
./gradlew :app-desktop:packageDistributionForCurrentOS   # installer for this OS
./gradlew :app-desktop:packageUberJarForCurrentOS        # a runnable jar, no packaging tools needed
```

To build with sync enabled, set `CADENCE_NEON_DATA_API_URL` and `CADENCE_NEON_AUTH_URL` in the
environment first — see [docs/self-hosting.md](docs/self-hosting.md).

The whole release is one script, the same one CI runs:

```bash
bash .github/scripts/build.sh              # apk + everything this OS can package, into dist/
bash .github/scripts/build.sh --dry-run    # what it would run, and at which version
```

The version is derived from the Conventional Commit subjects since the last `v*` tag, never
edited by hand.

## Development

```bash
# the whole test suite, in one Gradle invocation — what CI runs on every pull request
./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest \
          :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm

# one test class or method (method names are backticked sentences)
./gradlew :core:jvmTest --tests "de.andi1984.cadence.RecurrenceEngineTest"

# the server half — applies every migration to a throwaway postgres container; needs docker
bash neon/tests/run.sh
```

The suite takes a few seconds. JUnit 4 plus `kotlin.test`, no mocking library: every test that
touches storage runs the real SQLDelight stores over an in-memory SQLite database.

### Project structure

```
core/          Kotlin Multiplatform — the domain model, recurrence engine, quick-add parser,
               backup codec, the SQLDelight stores and the sync engine. No Android imports.
ui/            Compose Multiplatform — theme, components, every screen, the one ViewModel,
               and the string resources (English and German)
app-android/   the Android shell: navigation, alarms, widgets, SAF, SharedPreferences
app-desktop/   the JVM shell: window, sidebar, shortcuts, tray, JSON settings, jpackage
neon/          the server half — SQL migrations, migrate.sh, db.sh and a docker-based test
tools/         the Todoist converter
docs/adr/      architecture decision records — read these before changing anything structural
```

[`CLAUDE.md`](CLAUDE.md) is the long-form guide to the codebase, written for AI coding agents
and just as useful to humans: the invariants, the gotchas and the reasons behind them.

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) to get started, and
note that this project follows a [Code of Conduct](CODE_OF_CONDUCT.md). For anything larger than
a bug fix, open an issue first so the approach can be discussed before you invest time.

## Security

Found a vulnerability? Please report it privately — see [SECURITY.md](SECURITY.md). Do not open
a public issue for security problems.

## License

Cadence is free software, licensed under the **GNU General Public License v3.0 or later**
(`GPL-3.0-or-later`). You may use, study, share and modify it; if you distribute a modified
version, you must release your source under the same license. See [LICENSE](LICENSE) for the
full text.

```
Copyright (C) 2026 Andreas Sander

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

## Acknowledgements

Built with [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html),
[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/),
[SQLDelight](https://sqldelight.github.io/sqldelight/), [Ktor](https://ktor.io) and
[Material 3](https://m3.material.io). Sync runs on [Neon](https://neon.tech)'s Data API and Auth.
