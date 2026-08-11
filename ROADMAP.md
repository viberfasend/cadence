# Roadmap

Cadence is a local-first Android and desktop todo app (Kotlin, Compose Multiplatform,
SQLDelight). Sync through a Supabase project you sign in to is optional and off until you do —
these are candidate directions, not commitments. Order is rough priority, not a release plan.

Decisions large enough to outlive a single change are written down in [`docs/adr/`](docs/adr/).
Each item below is tracked as a GitHub issue — grouped the same way by
[milestone](../../milestones), and indexed in one place by the pinned
[📍 Roadmap overview](../../issues/49) issue. This file is the human-readable summary; the issues
carry the detail and the up-to-date checked/unchecked state.

## Now

- [ ] **Desktop app (Ubuntu/macOS/Windows) and multi-device sync** — [#28](../../issues/28),
      tracking [ADR 0001](docs/adr/0001-desktop-app-and-multi-device-sync.md). Moves the app to
      Kotlin Multiplatform over a shared `:core`, swaps Room for SQLDelight, replaces `Long` ids
      with UUIDv7, and turns sync into a folder of per-device state files merged on read rather
      than a snapshot that replaces everything. Phases 1–4 land invisibly, phase 5 is the first
      desktop build.
      - [x] 1 — `:core` KMP module ([#19](../../issues/19), [#20](../../issues/20))
      - [x] 2 — UUIDv7 keys, `updatedAt`/`deletedAt`, SQLDelight schema — [#23](../../issues/23)
      - [x] 3 — `:ui` Compose Multiplatform module, strings to `composeResources` —
            [#24](../../issues/24)
      - [x] 4 — `:app-android` reduced to a shell — [#25](../../issues/25)
      - [x] 5 — `:app-desktop` with jpackage installers and a CI matrix — [#26](../../issues/26)
      - [ ] 6 — backup format v2, merge engine, `SyncTransport`, per-device sync folder —
            [#27](../../issues/27)
- [ ] A reminder opens Today, not the task it reminded you about — [#29](../../issues/29)
- [ ] `android:allowBackup="true"` with no rules file is a silent data-loss trap —
      [#30](../../issues/30)

## Next

- [ ] Carry settings and the app language in the backup file — [#38](../../issues/38)
- [ ] Widgets (home screen: Today list, quick-add) — [#39](../../issues/39)
- [ ] Bulk actions in Triage/Projects (multi-select complete/move/delete) —
      [#40](../../issues/40)
- [ ] Tags in addition to Projects — [#41](../../issues/41)
- [ ] **Attachments and the Android share sheet** — [#31](../../issues/31), designed in
      [`docs/attachments-and-share.md`](docs/attachments-and-share.md). Six phases, each shipping
      on its own.
      - [ ] 0 — pre-refactor — [#32](../../issues/32)
      - [ ] 1 — storage, no UI — [#33](../../issues/33)
      - [ ] 2 — attachments in the app — [#34](../../issues/34)
      - [ ] 3 — share target (receive from the Android share sheet, `PROCESS_TEXT`) —
            [#35](../../issues/35)
      - [ ] 4 — bundle export — [#36](../../issues/36)
      - [ ] 5 (Later) — shortcuts, deep link, outgoing share — [#37](../../issues/37)

## Later / exploratory

- [ ] WebDAV/Nextcloud as a second `SyncTransport`, next to the synced folder of ADR 0001 —
      [#42](../../issues/42)
- [ ] Web app companion — [#43](../../issues/43)
- [ ] ADR 0001 phase 7 (optional) — wasm/js target for `:core` — [#44](../../issues/44)
- [ ] Home screen calendar view (month grid) — [#45](../../issues/45)
- [ ] Voice quick capture (Assistant-style) — [#46](../../issues/46)
- [ ] Additional locales beyond German — [#47](../../issues/47)
- [ ] Tablet/foldable layout — [#48](../../issues/48)

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
- [x] Subtasks — a task with a `parentId`, nested one level deep (`CLAUDE.md`, "Subtasks are
      tasks with a `parentId`")
