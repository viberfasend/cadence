# ADR 0003 — The desktop interaction model

**Status:** accepted · **Date:** 2026-08-21 · **Supersedes nothing.** Continues
[ADR 0001](0001-desktop-app-and-multi-device-sync.md) §8, which shipped a *working* desktop shell
and named what it deliberately left out.

## Context

Phase 5 gave the desktop a `NavigationRail`, a one-way back stack and `Ctrl`/`Cmd`+`N`, and called
it what it was: "a *working* shell, not a *finished* one". Everything else was the Android app in
a 1100×780 window. A phone app run on a desktop is not wrong so much as *unfinished*: the pointer
can only tap, the keyboard can only type, and a window three times the size of a phone screen
still shows one thing at a time.

This ADR records the decisions behind closing that gap — the epic tracked as
[#123](https://github.com/andi1984/todo/issues/123) — because most of them are the kind that look
arbitrary in six months.

## Decisions

### 1. Manual order is dense integers, renumbered from 0

`Task`, `Project` and `Section` have all carried a `sortOrder` since the first schema,
`SortMode.MANUAL` has always sorted by it, and the Postgres mirror has always synced it — and
nothing ever wrote one. Every row was inserted at 0, so "manual" order was id order.

`CadenceRepository.reorderTasks`/`reorderProjects`/`reorderSections` renumber a whole list densely.
Not a gap scheme (100, 200, 300…), not fractional midpoints:

- **Sync merges rows, not lists.** Two devices reordering the same list while offline interleave
  under last-writer-wins whatever the numbering is. The clever schemes buy no conflict resistance
  *here* — they would in a CRDT, and this is not one (ADR 0002 chose last-writer-wins outright).
- What they *do* buy is fewer rows written per drag, and the price is a renormalisation pass
  nobody can trigger deterministically plus numbers that stop being readable in the database.
- The `sortOrder != :sortOrder` guard in the UPDATE recovers most of that saving anyway: moving one
  row in a ten-row list writes the rows that actually moved, so the push carries those and nothing
  else.

### 2. The drag kernel is ours, not the platform's

Compose Multiplatform has `Modifier.dragAndDropSource`/`Target`. It is the wrong tool here:

- Its payload is a `ClipEntry` wrapping an AWT `Transferable` on the desktop and a `ClipData` on
  Android. Building one needs an `expect`/`actual` seam, and `:ui` has none — `jvmShared` is a
  single source set feeding both targets, on purpose (ADR 0001, decision 3).
- Every drag in this app is in-process and carries a domain object. Serialising a `Task` to a MIME
  type so the same process can parse it back is work in exchange for nothing.
- It has no concept of an insertion caret between two rows, which is most of what these drags have
  to *show*.
- None of it is reachable from a JVM unit test, and the interesting half of a drag is the rules.

So `:ui/dnd/` is two files. `DragModel.kt` is pure: payloads, targets, intents, `resolveDrop`,
`hitTest`. `DragAndDropKt` is the gesture, the ghost, the caret and the hover highlight, and
decides nothing. The split is what makes "a project cannot nest under its own child" a test rather
than a thing to remember.

**A drop that would change nothing is `Rejected`, not a no-op write.** The hover highlight is
driven by the same function that runs the drop, so a row that lights up is a promise that
something will happen.

**`hitTest` picks the innermost zone by area and skips one that rejects the payload**, so dragging
a project across a task row still reaches the pane underneath.

### 3. Context menus and row interactions are shared code that Android ignores

`ContextMenuArea` is desktop-only. Everything it is made of is not:
`PointerEvent.buttons.isSecondaryPressed` is common API, and `DropdownMenu` anchors anywhere. So
`Modifier.secondaryClickable` and the three row menus live in `:ui` beside every other component,
fire on the desktop, and are inert on a touchscreen that has no secondary button.

Reaching them from seven screens without changing seven signatures is a composition local:
`RowInteractions` (and `RowSelectionState` beside it). The desktop shell provides them, `TaskRow`
picks them up, and `:app-android` — which provides nothing — draws exactly the rows it drew
before. A local is the wrong default for most things; it is right here because the behaviour is
per-*row*, identical everywhere, and wanted by one shell.

Submenus are **pages of the same menu**, not cascades: Material 3 has no cascading `DropdownMenu`,
and a page that names where it is and how to get back is both less code and easier to hit with a
mouse than a strip that closes when the pointer leaves it.

### 4. Keyboard selection is read off the screen, not off a list

Nothing in `:ui` has "the list". Seven screens draw rows in bands, under headings, with checklists
spliced in, and two of them draw the same task twice in different roles. So `RowSelectionState`
does not index into anything: rows **register where they are** and the order is read off their
vertical positions. That is the order the user sees, and the only one that stays right across
bands, headers and expansion. A row that scrolls away or is deleted takes the selection with it —
otherwise the next `x` completes something nobody can see.

### 5. One shortcut table, two readers

`Shortcuts.kt` is data. The window dispatches from it and the `?` sheet is generated from it, so a
shortcut cannot be wired but undocumented, or documented but dead. `Ctrl` versus `Cmd` is decided
once, by reading the OS, rather than at fifteen call sites.

### 6. The sidebar replaced the rail, and the Projects screen stayed

A rail of four icons is a phone's bottom bar stood on its side. The sidebar is the views plus the
whole project tree, with counts, foldable, resizable, and a **drop target on every row** — which
is the part a rail could never be, and the reason the sidebar exists at all rather than being a
wider rail.

The Projects *screen* stays. It is where a project is created, edited and deleted, and those are
dialogs that want a page behind them rather than a 268dp column.

### 7. Two panes are one navigator at two widths

`DesktopNavigator` holds a back stack, a **forward** stack (which `NavHost` never gave us) and the
detail pane's occupant. `go()` puts a detail route in the pane when the window is wide and pushes
it as a destination when it is narrow; `back()` undoes whichever happened. Crossing the threshold
by resizing moves an open detail between the two rather than dropping it. That is what keeps `Esc`
and `Alt`+`←` meaning one thing while a window is being dragged wider.

The screens know none of this: `TaskDetailScreen` is the same composable in a pane and in a
window.

### 8. The desktop's own window state is a second file

`workspace.json` next to `settings.json`: sidebar width, which projects are folded, window bounds.
Not in `CadenceSettings`, which is the shared, ported settings object both shells implement — none
of this means anything on a phone, and adding it would give Android's `SharedPrefsSettingsStore`
fields it can never set. Every value is clamped on read: a window position from a monitor that is
no longer plugged in is a window nobody can find.

## Consequences

- `sortOrder` is now a value the app writes. A new list or a new "add" path has to give a row a
  position, or it lands at 0 among everything else that never got one.
- A new mutation still has to call `armSync()` (ADR 0002, decision 11); the reorder methods and
  `duplicateTask` do.
- Any new drop must be expressible as a `DropTarget` and resolved in `resolveDrop`. A screen that
  handles a drop itself is a rule that no test covers.
- Android is unaffected by every one of these, and that is a property worth protecting:
  `assembleDebug` compiling is not enough, since the shared screens now read two composition
  locals that Android leaves at their defaults.

## What is still open

Multi-select (a second selection model on top of §4), a per-window "focus mode", touch-gesture
parity on Android, and the desktop language setting ADR 0001 decision 9 deferred — `CadenceSettings`
still has no language field, and `Main.kt` still passes `Locale.getDefault()`.
