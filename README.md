# Cadence

A native Android and desktop todo app built from the "Cadence" design: **importance first, due
date breaks ties**, with recurrence that understands both calendar rules and "n days after
completion".

Kotlin · Compose Multiplatform · Material 3 · SQLDelight. Local-first and no analytics: the
database on each device is the source of truth, and the app is fully usable signed out and
offline. Syncing your own devices is one optional sign-in in Settings
([ADR 0002](docs/adr/0002-supabase-sync.md)); nothing leaves the device until you do.

## Getting the APK on your phone

Open this link on the phone and tap it:

**https://github.com/andi1984/todo/releases/latest/download/cadence-debug.apk**

That URL is permanent and always serves the newest *published* build. Releases are cut
deliberately now rather than on every push — see [Releases](#releases) — so the link moves when
someone releases, not when someone commits. Android will ask you to allow installs from that
source, which is expected for an app that did not come from the Play Store. There is nothing to
unzip.

The release build is at the same address as `cadence-release.apk`. The two have different
application IDs (`…cadence.debug` and `…cadence`), so they can live side by side.

Prefer the raw build output? A run of the [Android
workflow](../../actions/workflows/android.yml) also uploads both APKs as workflow artifacts, at
the bottom of the run's **Summary** page. Those download as a `.zip` and are only visible in a
browser — the GitHub mobile app does not show artifacts.

### Updating over an older build

Tap the same link again and install — the new build replaces the old one and keeps your tasks.

If the installer answers **"App not installed"**, the build on the phone is older than v1.1.2:
up to that release each build was signed with a throwaway key generated on the build machine,
and Android will not install an app over one signed by a different key. Uninstall Cadence once
and install again. **Export a backup first** (Settings → Backup) — uninstalling deletes the
database — and import it afterwards. Every build since shares one key, so this is a one-time
step.

### Signing the release build (optional)

Without any configuration the release APK is signed with the debug key committed at
`app-android/debug.keystore`, which is public: anyone can build an APK that installs over it. That is
fine for a build distributed as a link on GitHub, and it is what keeps updates working. To sign
the release APK with a key only you hold, add these repository secrets:

| Secret | Contents |
| --- | --- |
| `CADENCE_KEYSTORE_BASE64` | `base64 -w0 my-key.jks` |
| `CADENCE_KEYSTORE_PASSWORD` | keystore password |
| `CADENCE_KEY_ALIAS` | key alias |
| `CADENCE_KEY_PASSWORD` | key password |

## What it does

**Today** — overdue work is pinned to the top in a red block with a "Reschedule all" action,
then everything due today. Completed tasks stay in place for the rest of the day.

**Upcoming** — a week strip plus an agenda grouped by day.

**Inbox & Triage** — anything captured without a project waits in the Inbox. Triage walks the
backlog one card at a time so you can set importance in a single pass.

**Projects** — one level of subprojects, with open and overdue counts.

**Task detail** — importance as a four-way segmented control, due date, reminder, recurrence
and notes.

**Quick add** — one line, parsed as you type:

```
Pay rent every 1st !p2 #Home
Water the plants 3 days after done
Call the dentist tomorrow at 17:00 !p1
Take out recycling every 2 weeks on thu
Steuer 24.12.
```

Recognised: `!p1`–`!p4`, `#Project`, `today`/`tomorrow`/`next friday`/`in 3 days`/`24.12.`/
`24 Dec`/`2026-12-24`, `at 17:00`/`9am`, and recurrence phrases (`daily`, `every 2 weeks on thu`,
`every 1st`, `every last weekday`, `3 days after done`). Everything is optional; unrecognised
words stay in the title, and every parsed chip can be corrected by tapping it.

## Language

English and German. The app follows the system language, and on Android 13 and newer it also
appears in **Settings → Apps → Cadence → Language** so you can pick one just for Cadence.

Quick-add still parses English keywords only (`tomorrow`, `every 2 weeks on thu`) — everything
around them can be German, and unrecognised words stay in the title as usual.

## Accessibility

- Priority is never colour alone: the segmented spine is always paired with its `P1`…`P4` label,
  and the spine is announced as e.g. "P2 · High".
- Touch targets are at least 44–48dp even where the design draws a 24dp circle.
- The type scale carries 1.3× line-height headroom so 200% system font still fits.
- Two row densities: comfortable (64dp, full meta line) and compact (52dp), in Settings.
- Light and dark themes, or follow the system.

## Recurrence

Two kinds of rule:

- **On a schedule** — every N days/weeks/months/years, with weekday selection for weekly rules
  and `day N` / `last day` / `last weekday` / `nth weekday` for monthly ones.
- **After I finish** — the next due date is counted from the day you complete the task.

Completing a recurring task keeps the finished instance where it is and inserts the next one.
"Keep missed instances" decides whether a skipped occurrence stays overdue or the series catches
up to today.

## Coming from Todoist

Export your Todoist data (Settings → Backup → one CSV per project), then convert the whole
folder into a backup file the app imports under Settings → Backup:

```bash
python3 tools/todoist_import.py ~/Downloads/"Todoist backup 2026-08-12 2248 UTC"
```

**It all arrives in one project named `Import <date>`, and nowhere else** — not in your Inbox,
not beside the projects you already have. An import is a pile to sort: a task joins your system
when you move it out of that pile, and the pile can be deleted once it is empty. Sections come
across as sibling projects named `Garten · August ☀️`, since the staging project takes the one
level of nesting Cadence allows.

Projects, sections, subtasks, comments, priorities, due dates, deadlines and repeat phrases in
German and English all come across — `every! 3 months` included, as an "after I finish" rule.
Importing merges, and the converter derives its ids from the export, so re-running it later
updates the tasks it already imported instead of duplicating them.
[`tools/README.md`](tools/README.md) has the full mapping table and the flags.

## Building locally

```bash
./gradlew testDebugUnitTest   # unit tests for the recurrence engine and the quick-add parser
./gradlew assembleDebug       # app-android/build/outputs/apk/debug/app-android-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 35). Minimum supported device: Android 8.0.

## Releases

**Nothing builds automatically.** All three GitHub Actions workflows are `workflow_dispatch`
only: no push, no pull request, no schedule triggers a run, because hosted minutes were being
spent on builds a laptop does for free. Run one from the Actions tab when you want it.

The whole release is a single script, and it is the same script the workflows call:

```bash
bash .github/scripts/build.sh            # apk + everything this OS can package, into dist/
bash .github/scripts/build.sh --dry-run  # what it would run, and at which version
gh release create "v$(…)" dist/* --generate-notes   # publish by hand
```

`build.sh` derives the version from the Conventional Commit subjects since the last `v*` tag
(`.github/scripts/next-version.sh`), stages the APKs under the fixed names the download links
point at, and checks the debug signing certificate. A Linux laptop can produce the APKs, a
`.deb`, an `.rpm` and a tarball; `.dmg` and `.msi` are the one thing that genuinely needs a
runner, since jpackage cannot cross-compile them — run the **Desktop** workflow with its `os`
input set to `macos-latest` or `windows-latest` for those, one at a time.

## Layout

```
app-android/src/main/java/de/andi1984/cadence/
├── data/            Room entities, DAOs, repository, seed content
├── domain/          model, recurrence engine, quick-add parser
├── reminders/       alarm scheduling and notifications
└── ui/              theme, shared components, one package per screen
```
