# Plan: the desktop power shell

Phase 5 of [ADR 0001](../adr/0001-desktop-app-and-multi-device-sync.md) shipped a *working*
desktop shell: the Android screens, a `NavigationRail`, a hand-rolled back stack, `Ctrl`/`Cmd`+`N`.
It is a phone app in a 1100×780 window. This plan turns it into an application that behaves the
way desktop applications behave — pointer-first, keyboard-complete, direct manipulation — without
forking `:ui` into two copies of every screen.

Everything below is either shared code in `:ui` that Android is free to ignore, or shell code in
`:app-desktop`. No screen gets a desktop twin.

## The three interactions this is built around

1. **Drag and drop.** A task is a thing you can pick up: drop it on a project in the sidebar to
   file it, on a section band to group it, between two rows to order it, on Today to date it. A
   project is a thing you can pick up: drop it on another project to nest it, between two to
   order them.
2. **Right-click.** Every row answers a secondary click with the actions that row has. No more
   opening a detail screen to change a priority.
3. **The keyboard.** Every action reachable without the pointer, a command palette
   (`Ctrl`/`Cmd`+`K`) over every task, project and command, and a cheat sheet that lists them.

Plus the two structural changes that make those worth having: a **real sidebar** (the project
tree, not four rail icons) and a **two-pane layout** (list left, detail right — ADR 0001 §8
named it and phase 5 skipped it).

## Why the work is shaped this way

**Manual order has to exist before anything can be dragged into it.** `Task`, `Project` and
`Section` all carry `sortOrder`, `SortMode.MANUAL` already sorts by it, the column is in the
SQLDelight schema *and* in the Postgres mirror — and nothing has ever written it. Every new row
gets `0`. Reordering is therefore a `:core` change first (issue 1) and a gesture second.

**The drag kernel is ours, not the platform's.** `Modifier.dragAndDropSource/Target` exists in
Compose Multiplatform, but its payload is a `ClipEntry` wrapping an AWT `Transferable` on the
desktop and a `ClipData` on Android — constructing one needs `expect`/`actual`, and `:ui` has no
`expect`/`actual` seam (`jvmShared` is one source set feeding both targets). It also gives us
nothing we want: the drags here are all in-process, they carry a domain object rather than text,
and they need an insertion caret between rows that the platform API has no concept of. A
~250-line kernel of our own — a drag state holder, a bounds registry, a pure hit test — is both
smaller and unit-testable on the JVM, which the platform one is not.

**Right-click is shared code too.** `ContextMenuArea` is desktop-only, but
`PointerEvent.buttons.isSecondaryPressed` is common API, and Material 3's `DropdownMenu` anchors
anywhere. So `Modifier.secondaryClickable {}` lives in `:ui`, fires on desktop, and is inert on a
touchscreen that has no secondary button — Android keeps its long-press sheets and loses nothing.

**Speed is a correctness problem here, not a benchmark.** `ProjectsScreen` calls
`state.tasksIn(id)` — a full scan of every task — four times per project row, inside a
`LazyColumn`, on every recomposition. With 20 projects and 2000 tasks that is 160k comparisons
per frame. The fix is derived indexes computed once per `CadenceUiState`, which is issue 9 and is
worth more than any amount of Compose micro-tuning.

## Issues

| # | Title | Module | Depends on |
|---|-------|--------|-----------|
| 1 | Manual ordering: `sortOrder` becomes a value the app writes | `:core` | — |
| 2 | A drag-and-drop kernel for `:ui` | `:ui` | — |
| 3 | Right-click context menus on tasks, projects and sections | `:ui` | — |
| 4 | The desktop sidebar: the project tree, resizable, and a drop target | `:app-desktop` | 1, 2, 3 |
| 5 | Drag to reorder, drag to file: tasks in every list | `:ui` | 1, 2 |
| 6 | Command palette (`Ctrl`/`Cmd`+`K`) | `:ui`, `:app-desktop` | — |
| 7 | Keyboard-complete: a shortcut map, row selection, and a cheat sheet | `:app-desktop` | — |
| 8 | Two-pane layout: list and detail side by side | `:app-desktop` | — |
| 9 | Derived indexes on `CadenceUiState` | `:ui` | — |
| 10 | Desktop polish: window state, hover, tooltips, animated reorder, midnight | `:app-desktop` | 4–8 |
| 11 | ADR 0003 and the CLAUDE.md the shell now needs | docs | 1–10 |

### 1 — Manual ordering (`:core`)

- `Task.sq` / `Project.sq` / `Section.sq` gain `updateSortOrder:` and a `maxSortOrder…:` query
  per bucket; no schema change, so **no `.sqm`** — the columns already exist.
- `TaskStore.reorder(List<Pair<String, Int>>, at: Instant)` writes the whole list in one
  `transaction`, stamping `updatedAt` so the round pushes it. Same on `ProjectStore` and
  `SectionStore`.
- `CadenceRepository.reorderTasks(bucket, orderedIds)` / `reorderProjects` / `reorderSections`
  renumber from 0 in steps of 1 — a dense renumber, not a gap/midpoint scheme. Sync merges rows,
  not lists, so two devices reordering the same list concurrently interleave under
  last-writer-wins whatever the numbering; dense integers at least stay readable and never run
  out of room between two neighbours.
- New rows get `max + 1` in their bucket instead of `0`: `upsertTask`, `upsertProject`,
  `upsertSection`, `addParsedTask`.
- Tests: renumbering is dense and stable, a move to the same index writes nothing, a row from
  another bucket is ignored, `updatedAt` is stamped on every row the reorder touched.

### 2 — Drag-and-drop kernel (`:ui/dnd/`)

- `DragPayload` — sealed: `TaskDrag(task)`, `ProjectDrag(project)`, `SectionDrag(section)`.
- `DropTarget` — sealed: `IntoProject(id?)`, `IntoSection(projectId, sectionId?)`, `Before(rowId)`,
  `After(rowId)`, `OntoDate(date)`, `NestUnder(projectId)`, `Reorder(bucket, index)`.
- `DragAndDropState` — the picked-up payload, the pointer position, the registry of target
  bounds (`onGloballyPositioned` in, `Rect` out), the currently hovered target, and the drag
  ghost's content. Provided through `LocalDragAndDrop`.
- `Modifier.cadenceDragSource(payload, ghost)` — a long-press-free pointer drag with a small
  slop threshold, so a click still clicks.
- `Modifier.cadenceDropTarget(target, accepts)` — registers bounds, highlights on hover.
- `DragOverlay` — one composable at the app root drawing the ghost under the cursor and the
  insertion caret.
- Auto-scroll: while a drag hovers within 48dp of a `LazyColumn` edge, scroll.
- Pure and tested in `jvmSharedTest`: `hitTest(registry, position)` picks the innermost target,
  `resolveDrop(payload, target)` returns a `DropIntent` (`Move`, `Nest`, `Reorder`, `Reschedule`,
  `Rejected`) and rejects the impossible ones — a project onto its own subtree, a task onto a
  section of a project it is not in (that one resolves to move + group, not reject), a
  second-level nest.

### 3 — Context menus (`:ui/components/ContextMenu.kt`)

- `Modifier.secondaryClickable { offset -> }` — `awaitPointerEventScope`, secondary button,
  press only. Inert where there is no secondary button.
- `CadenceContextMenu(expanded, offset, onDismiss) { }` — `DropdownMenu` at the cursor.
- `TaskContextMenu`: complete/reopen · priority P1–P4 · due today / tomorrow / next week / no
  date · move to project ▸ · move to section ▸ · add subtask · duplicate · copy title · delete.
- `ProjectContextMenu`: open · new task here · new subproject · rename & colour · nest under ▸ ·
  delete.
- `SectionContextMenu`: rename · add task · delete.
- New ViewModel actions: `duplicateTask`, `setDueDatePreset`, and `armSync()` on each (a mutation
  that forgets it leaves the other device stale — CLAUDE.md's rule).
- Every label is a string resource, EN + DE. No prose in Kotlin.

### 4 — The sidebar (`:app-desktop/ui/Sidebar.kt`)

Replaces `NavigationRail` outright.

- Sections: **Views** (Today, Upcoming, Inbox, Search) · **Projects** (the real tree, one level,
  collapsible, counts, colour swatches) · a footer (sync state, Settings).
- Resizable by dragging its trailing edge, 200–420dp, width persisted in `DesktopSettings`;
  collapsible to a 64dp icon rail with `Ctrl`/`Cmd`+`B`.
- Drop targets on every row: a task dropped on a project moves it (and clears its section), a
  task dropped on Inbox clears its project, a task dropped on Today dates it, a project dropped
  on a project nests it, a project dropped between two reorders them.
- Right-click anywhere in it opens the matching context menu.
- Expanded/collapsed project state persists too — it is a per-device fact, like every other
  setting.

### 5 — Dragging tasks in the lists (`:ui`)

- `ProjectDetailScreen`: reorder inside a section band, drag between bands, drag onto a
  subproject link. Sorting is `MANUAL` there by default once a list has been ordered by hand.
- `InboxScreen`, `TodayScreen`: reorder; a drop between two rows in a date-sorted list explains
  itself by switching that list to manual rather than silently doing nothing.
- `UpcomingScreen`: a task dropped on another day's header gets that due date.
- Insertion caret, ghost row, `Modifier.animateItem()` on settle.

### 6 — Command palette (`:ui/palette/`)

- `CommandPaletteModel` — pure: a scored fuzzy match (subsequence + word-boundary bonus +
  recency) over commands, tasks and projects, capped and grouped. Tested in `jvmSharedTest`.
- Entries: every navigation target, every project, open tasks by title, and verbs — new task,
  new project, sync now, toggle theme, toggle density, export, sign in/out, show shortcuts.
- `Ctrl`/`Cmd`+`K` opens it, `↑`/`↓`/`Enter`/`Esc` drive it, typing `>` restricts to commands and
  `#` to projects.

### 7 — Keyboard (`:app-desktop`)

- One `Shortcuts.kt` table — key + modifiers + action + the string that names it — so the cheat
  sheet is generated from the same list the window dispatches, and the two cannot drift.
- Selection: `↑`/`↓`/`j`/`k` move a highlighted row, `Enter` opens it, `Space`/`x` completes,
  `1`–`4` set priority, `t`/`m`/`w` date it today/tomorrow/next week, `#` moves it,
  `Del`/`Backspace` deletes with the usual undo window.
- Global: `Ctrl+K` palette, `Ctrl+F` search, `Ctrl+N` new task, `Ctrl+,` settings, `Ctrl+B`
  sidebar, `Ctrl+R` sync, `Ctrl+Z` undo, `Alt+←/→` back/forward, `Esc` closes, `?` cheat sheet.
- A real forward stack — the current back stack only goes one way.

### 8 — Two panes (`:app-desktop`)

- Window ≥ 1000dp: list pane and a detail pane side by side; selecting a row fills the detail
  pane instead of pushing a screen. Below that, the current push behaviour.
- The detail pane is `TaskDetailScreen`/`ProjectDetailScreen` unchanged — it already takes a task
  and callbacks.
- Pane split draggable and persisted.

### 9 — Derived indexes (`:ui/CadenceUiState`)

- `by lazy` maps built once per state emission: `tasksByProject`, `tasksBySection`,
  `subtasksByParent`, `openCountByProject`, `overdueCountByProject`, `projectsByParent`,
  `taskById`. Every existing helper reads them; every call site keeps its signature.
- `rootTasks()` is called per row today and rebuilds a `Set` each time — memoise it.
- Tests: the derivations already have a test class; extend it so the memoised path is what the
  existing assertions exercise.

### 10 — Polish (`:app-desktop`)

- Window size, position and maximised state persisted in `DesktopSettings` (rejoining the screen
  it was on, clamped to a screen that still exists).
- `today` is `remember`ed at start and never changes — a machine left open overnight draws
  yesterday. Recompute at midnight from a coroutine.
- Hover states on every row, `TooltipBox` on every icon-only control, focus rings that are
  visible in both themes.
- The system tray gets a menu (show/hide, new task, sync, quit) rather than only balloons.
- The desktop language setting ADR 0001 decision 9 deferred, since Settings is being touched
  anyway.

### 11 — Docs

- `docs/adr/0003-desktop-interaction-model.md`: why an in-house drag kernel, why context menus
  are shared code, why manual order is dense integers under last-writer-wins, what the sidebar
  replaced.
- CLAUDE.md: the drag kernel and the shortcut table in the architecture map, the "a new mutation
  calls `armSync()`" rule restated for the new ones, the reorder API in the persistence notes.

## Out of scope

Multi-select (a second selection model on top of the one issue 7 introduces — worth its own
epic), touch-gesture parity on Android, a plugin surface, and any change to the sync protocol:
reordering rides on `sort_order`, which the wire already carries.
