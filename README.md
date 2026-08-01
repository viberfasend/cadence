# Cadence

A native Android todo app built from the "Cadence" design: **importance first, due date breaks
ties**, with recurrence that understands both calendar rules and "n days after completion".

Kotlin · Jetpack Compose · Material 3 · Room. No cloud, no account, no analytics — everything
lives in a local database.

## Getting the APK on your phone

Every push builds an installable APK in GitHub Actions.

1. Open the [Actions tab](../../actions/workflows/android.yml) and pick the latest green run.
2. Download the **cadence-debug-apk** artifact (or **cadence-release-apk**).
3. Unzip it, copy the `.apk` to your phone and open it. Android will ask you to allow installs
   from that source — that is expected for an app that did not come from the Play Store.

Tagging a commit `v1.0.0` and pushing the tag also attaches both APKs to a GitHub Release.

The debug and release builds have different application IDs (`…cadence.debug` and `…cadence`),
so they can live side by side.

### Signing the release build (optional)

Without any configuration the release APK is signed with the standard debug key. To sign with
your own key, add these repository secrets:

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

## Building locally

```bash
./gradlew testDebugUnitTest   # unit tests for the recurrence engine and the quick-add parser
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 35). Minimum supported device: Android 8.0.

## Layout

```
app/src/main/java/de/andi1984/cadence/
├── data/            Room entities, DAOs, repository, seed content
├── domain/          model, recurrence engine, quick-add parser
├── reminders/       alarm scheduling and notifications
└── ui/              theme, shared components, one package per screen
```
