# Roadmap

Cadence is a local-first Android todo app (Kotlin, Compose, Room). No cloud, no account today —
these are candidate directions, not commitments. Order is rough priority, not a release plan.

## Now

- [ ] Data backup/export (JSON or SQLite dump), foundation for a future web app to read the same
      data format

## Next

- [ ] Nth-weekday and spelled-out numbers in quick-add (`jeden 2. Montag`, `every 2nd monday`,
      `alle drei Tage`) — the lexicon reads digits and plain ordinals, not these
- [ ] Widgets (home screen: Today list, quick-add)
- [ ] Search across tasks and notes
- [ ] Bulk actions in Triage/Projects (multi-select complete/move/delete)
- [ ] Subtasks / checklists within a task
- [ ] Tags in addition to Projects
- [ ] Attachments (photo/file per task)

## Later / exploratory

- [ ] Sync (e.g. via user-provided WebDAV/Nextcloud, or the future web app backend)
- [ ] Web app companion (reads the backup format above)
- [ ] Home screen calendar view (month grid)
- [ ] Siri/Assistant-style quick capture (share sheet, voice)
- [ ] Additional locales beyond German
- [ ] Tablet/foldable layout

## Done

- [x] Core app: Today/Upcoming/Inbox/Triage/Projects, quick-add parser, recurrence engine
- [x] Accessibility pass (contrast, touch targets, font scaling)
- [x] CI: rolling GitHub Release build on every push
- [x] German translation of the full UI (`values-de/`, per-app language picker on Android 13+)
- [x] German quick-add parsing (`heute`, `morgen`, `jeden 1.`, `alle 2 Wochen am Donnerstag`,
      `3 Tage nach Erledigung`) — keywords live in `QuickAddLexicon`, English stays understood
