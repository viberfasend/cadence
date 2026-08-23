# The next epics, and the order they go in

**Date:** 2026-08-23. A planning note, not a decision record — the decisions each of these
needs are written down in `docs/adr/` when the work starts, and the detail lives in the issues.
This file says *what is next and why in that order*, which neither an ADR nor an issue holds.

## Where the app stands

Cadence is feature-complete as a single-user list app. Today / Upcoming / Inbox / Triage,
projects with sections, tags ([ADR 0004](adr/0004-tags.md)), subtasks, recurrence as a chain of
rows, quick add in English and German, search, backup, Supabase sync with realtime
([ADR 0002](adr/0002-supabase-sync.md)), Android widgets, attachments, and a desktop shell that
behaves like a desktop application ([ADR 0003](adr/0003-desktop-interaction-model.md)).

What is left is not more list features. It is three different things:

- **Reach** — the machines Cadence does not run on.
- **Planning** — the app can say what is due; it cannot show a week.
- **Trust** — the server can read every task title, and the most stateful class in the repo has
  no tests.

## The epics

| | Epic | Issue | Size | New ADR |
|---|---|---|---|---|
| A | Multi-select and bulk actions | [#40](../../issues/40) | S–M | no |
| B | Adaptive shell — one navigator, two panes on tablets | [#48](../../issues/48) | M | ADR 0005 |
| C | Planning surfaces — month grid, week agenda, plan-my-day | [#148](../../issues/148) | M–L | yes |
| D | One query grammar, and saved views | [#149](../../issues/149) | L | ADR 0006 |
| E | The third platform is the browser | [#151](../../issues/151) | XL | yes |
| F | End-to-end encrypted sync | [#152](../../issues/152) | L | ADR 0007 |
| G | Interop — import, share target, bundle export, `.ics` | [#150](../../issues/150) | M, in slices | no |
| H | Tests and the defect backlog | [#153](../../issues/153) | continuous | no |

## The order, and why it is that order

1. **Housekeeping and a slice of H.** The sync engine has no tests at all, and #114, #115 and
   #117 are defects a person hits. #114 is the same undo state machine that A extends, so it is
   cheaper to fix before A than during it.
2. **A, then B.** Both cash in machinery that shipped with ADR 0003 — the selection state, the
   drag kernel, the two-pane rule — and both are prerequisites for what follows: a month grid is
   worth much less if you cannot select a week's tasks and move them, and three later surfaces
   each want a detail pane on Android.
3. **C.** The next thing a user actually sees. A day cell is a drop target, so the expensive part
   of it already exists.
4. **D.** After C, so the calendar can scope to a saved view rather than growing filters of its
   own; after A, because a view's results want bulk actions.
5. **One flagship: E or F.** Not both. E1 phase 0 — `:core` off `java.time` ([#160](../../issues/160))
   — is worth doing either way, because iOS needs exactly the same rewrite.
6. **G**, one slice per release, whenever a release has room.

## The flagship fork, stated once

**E — the browser.** Reaches the machine that cannot install software, needs no hardware, no
developer account and no review. The structural risk is real and named in the issue: `:ui` is one
`jvmShared` source set feeding two JVM targets, and a third non-JVM target breaks that
assumption.

**F — encryption.** Follows directly from decisions already made — the local database is the
source of truth, search runs in memory, and the wire shape is already separate from both storage
and the backup file. No competing app of this size ships it. It is also the least reversible
change on the board, which is why it gets an ADR before a line of code.

**iOS is parked, not rejected.** It needs a Mac, $99 a year and App Store review, and it shares
phase 0 with the web. Nothing in E forecloses it; the day someone wants it, phase 0 is already
done.

## Deliberately not proposed

- **Shared or collaborative projects.** ADR 0002 chose one user, forced RLS and last-writer-wins.
  Collaboration is a different merge model and a different security model, and retrofitting it
  would touch every decision in that ADR.
- **AI features.** Nothing here is improved by a model, and the app collects no data to feed one.
- **Time tracking and durations.** A task has no length in this model. Drawing one would be
  inventing data.
