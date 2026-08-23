# Roadmap

Cadence is a local-first Android and desktop todo app (Kotlin, Compose Multiplatform,
SQLDelight). Sync through a Supabase project you sign in to is optional and off until you do —
these are candidate directions, not commitments. Order is rough priority, not a release plan.

Decisions large enough to outlive a single change are written down in [`docs/adr/`](docs/adr/),
and the order the next epics go in — with the reasoning — is
[`docs/plans/next-epics.md`](docs/plans/next-epics.md). Each item below is tracked as a GitHub
issue, grouped the same way by [milestone](../../milestones) and indexed by the pinned
[📍 Roadmap overview](../../issues/49). This file is the human-readable summary; the issues carry
the detail and the up-to-date state.

Cadence is feature-complete as a single-user list app. What is left is **reach** (machines it does
not run on), **planning** (it can say what is due, not show a week) and **trust** (the server can
read every title, and the sync engine has no tests).

## Now

- [ ] **Multi-select and bulk actions, on every list** — [#40](../../issues/40). Finishes ADR
      0003's interaction model: the selection state, the row menus and the drag kernel all exist
      and all act on one row.
- [ ] **Adaptive shell: one navigator, two panes on tablets and foldables** — [#48](../../issues/48).
      `DesktopNavigator` and the 1000dp rule move into `:ui`, so Android stops running a phone
      layout on a 1200dp screen. ADR 0005.
- [ ] **Tests and the defect backlog** — [#153](../../issues/153). `CadenceSyncEngine` has no
      tests ([#107](../../issues/107)); [#114](../../issues/114), [#115](../../issues/115) and
      [#117](../../issues/117) are defects a person hits.
- [ ] Desktop polish: hover states and tooltips — [#133](../../issues/133)

## Next

- [ ] **Planning surfaces — a month grid, a week agenda, and plan-my-day** — [#148](../../issues/148).
      A day cell is a drop target; the drag kernel already exists.
      - [ ] Month grid — [#45](../../issues/45)
      - [ ] Week agenda — [#154](../../issues/154)
      - [ ] Plan my day — [#155](../../issues/155)
- [ ] **One query grammar, and saved views built on it** — [#149](../../issues/149). Four places
      filter tasks and each filters differently; reading a list should speak the same language as
      writing one. ADR 0006.
      - [ ] `TaskQuery` in `:core` — [#156](../../issues/156)
      - [ ] Search runs it — [#157](../../issues/157)
      - [ ] Saved views — [#158](../../issues/158)
- [ ] **Interop — import, share target, bundle export, `.ics`** — [#150](../../issues/150). Makes
      Cadence adoptable, not only usable. Ships in slices.
      - [ ] Import from another app, in the app — [#163](../../issues/163)
      - [ ] Share target (Android share sheet, `PROCESS_TEXT`) — [#35](../../issues/35)
      - [ ] Bundle export (a backup plus its blobs) — [#36](../../issues/36)
      - [ ] `.ics` export — [#164](../../issues/164)
- [ ] Desktop backup file picker can't reach cloud storage — [#65](../../issues/65). Escape-hatch
      only now that sync no longer travels through a file.

## Later / exploratory

One flagship, not both — see [`docs/plans/next-epics.md`](docs/plans/next-epics.md).

- [ ] **The third platform is the browser** — [#151](../../issues/151). Phase 0 is worth doing
      either way: iOS would need exactly the same rewrite.
      - [ ] 0 — `:core` off `java.time` onto kotlinx-datetime — [#160](../../issues/160)
      - [ ] 1 — `commonMain` earns its keep, and the wasm target — [#44](../../issues/44)
      - [ ] 2 — persistence in a browser (OPFS, and the migration chain) — [#161](../../issues/161)
      - [ ] 3 — `:app-web`, read and complete — [#43](../../issues/43)
      - [ ] 4 — parity, minus what a browser cannot do — [#162](../../issues/162)
- [ ] **End-to-end encrypted sync** — [#152](../../issues/152). The server should not be able to
      read the list. Search is local already, so it costs the app no capability. ADR 0007.
- [ ] Widgets scope to a saved view — [#159](../../issues/159)
- [ ] Attachments phase 5 — shortcuts, deep link, outgoing share — [#37](../../issues/37)
- [ ] Additional locales beyond German — [#47](../../issues/47)

**Not proposed:** shared or collaborative projects (a different merge model and a different
security model from the one ADR 0002 chose), AI features, and time tracking (a task has no
duration in this model).

## Done

- [x] **The desktop power shell** — [#123](../../issues/123), designed in
      [ADR 0003](docs/adr/0003-desktop-interaction-model.md): a drag-and-drop kernel, right-click
      menus, drag to reorder and to file, the sidebar with the project tree, the command palette,
      a shortcut table with a cheat sheet, and the two-pane layout
- [x] **Tags in addition to projects** — [#41](../../issues/41), designed in
      [ADR 0004](docs/adr/0004-tags.md): `@handle` in quick add, chips, a cross-project list per
      tag, and membership as a packed column on the task
- [x] **Attachments** phases 0–2 — files and links on a task, content-addressed blobs, thumbnails
      ([#32](../../issues/32), [#33](../../issues/33), [#34](../../issues/34))
- [x] **Sync that runs itself** — [#76](../../issues/76),
      [ADR 0002](docs/adr/0002-supabase-sync.md) phases 3 and 3b: automatic triggers, sync in the
      header, a failure snackbar, and realtime as an accelerant
- [x] **Desktop app (Ubuntu/macOS/Windows)** — [#28](../../issues/28),
      [ADR 0001](docs/adr/0001-desktop-app-and-multi-device-sync.md) phases 1–5: `:core` as a KMP
      module, UUIDv7 ids with `updatedAt`/`deletedAt`, `:ui` as Compose Multiplatform,
      `:app-android` reduced to a shell, and `:app-desktop` with jpackage installers
- [x] Home-screen widgets (Glance): the Today list and quick-add — [#39](../../issues/39)
- [x] Voice quick capture — [#46](../../issues/46)
- [x] Carry settings and the app language in the backup file — [#38](../../issues/38)
- [x] Core app: Today/Upcoming/Inbox/Triage/Projects, quick-add parser, recurrence engine
- [x] Subtasks — a task with a `parentId`, nested one level deep
- [x] Search across tasks and notes
- [x] Data backup/export — `domain/backup/BackupCodec.kt`, a versioned JSON document with ISO
      dates and structured recurrence; importing merges rather than replaces
- [x] German translation of the full UI, and German quick-add parsing (`heute`, `jeden 1.`,
      `alle 2 Wochen am Donnerstag`, `3 Tage nach Erledigung`) — keywords in `QuickAddLexicon`
- [x] Nth-weekday and spelled-out numbers in quick add (`every 2nd monday`, `alle drei Tage`)
- [x] Accessibility pass (contrast, touch targets, font scaling)
- [x] CI: a GitHub release build, since made `workflow_dispatch`-only — `build.sh` cuts the same
      release on a laptop, so no run is automatic any more
