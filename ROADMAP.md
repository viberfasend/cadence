# Roadmap

Cadence is a local-first Android todo app (Kotlin, Compose, Room). No cloud, no account today —
these are candidate directions, not commitments. Order is rough priority, not a release plan.

## Now

- [ ] Merge on import, next to the current replace-everything restore (match on task id, keep
      whichever row is newer)
- [ ] A reminder opens Today, not the task it reminded you about — `ReminderReceiver` already puts
      `EXTRA_TASK_ID` into the content intent and nothing ever reads it
- [ ] `android:allowBackup="true"` with no rules file, so Android auto-backup ships whatever ends up
      in the app's storage. Fine today; a silent data-loss trap the moment anything large lands
      there, because exceeding the 25 MB per-app ceiling stops the backup entirely

## Next

- [ ] Carry settings and the app language in the backup file — today it holds tasks and projects
      only
- [ ] Widgets (home screen: Today list, quick-add)
- [ ] Bulk actions in Triage/Projects (multi-select complete/move/delete)
- [ ] Subtasks / checklists within a task
- [ ] Tags in addition to Projects
- [ ] Attachments — files and links per task, designed in
      [`docs/attachments-and-share.md`](docs/attachments-and-share.md). Bytes are copied into
      app-private storage and content-addressed by SHA-256 (free dedupe, and the shape a web
      companion would want), because a share sheet's URI grant is one-shot and cannot be persisted.
      Ships in three steps: storage and migration, the detail screen and a file viewer, then
      attachment metadata in the backup plus a bundle export that carries the blobs
- [ ] Receive from the Android share sheet — text, links, images and files, plus a text selection via
      `PROCESS_TEXT`. A transparent share activity offers a new task prefilled through the existing
      quick-add parser, with existing tasks ranked below it (an already-attached link is the
      strongest signal). Same document as above

## Later / exploratory

- [ ] Sync (e.g. via user-provided WebDAV/Nextcloud, or the future web app backend)
- [ ] Web app companion (reads the backup format above)
- [ ] Home screen calendar view (month grid)
- [ ] Voice quick capture (Assistant-style) — the share-sheet half of this moved to **Next** above
- [ ] Direct Share targets, so a project appears in the share sheet's top row. Deferred with the
      share work: the shortcut lifecycle spans every project create, rename and delete, survives a
      backup restore, and is ranked by the OS in a way tests cannot reach
- [ ] Additional locales beyond German
- [ ] Tablet/foldable layout

## Done

- [x] Core app: Today/Upcoming/Inbox/Triage/Projects, quick-add parser, recurrence engine
- [x] Accessibility pass (contrast, touch targets, font scaling)
- [x] CI: rolling GitHub Release build on every push
- [x] German translation of the full UI (`values-de/`, per-app language picker on Android 13+)
- [x] Nth-weekday and spelled-out numbers in quick-add (`every 2nd monday`, `jeden letzten
      Freitag`, `alle drei Tage`) — number words live in `QuickAddLexicon.numbers`, and an
      `androidTest` now compiles the grammar under the device's ICU engine
- [x] Search across tasks and notes
- [x] Data backup/export — `domain/backup/BackupCodec.kt` writes a versioned JSON document
      (ISO dates, structured recurrence) through the Storage Access Framework; import replaces
      both tables in one transaction. This is the format the future web app reads.
- [x] German quick-add parsing (`heute`, `morgen`, `jeden 1.`, `alle 2 Wochen am Donnerstag`,
      `3 Tage nach Erledigung`) — keywords live in `QuickAddLexicon`, English stays understood
