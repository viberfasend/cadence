package de.andi1984.cadence.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * Every shortcut the window answers to, as data.
 *
 * One table, two readers: `main()` dispatches from it and [ShortcutSheet] lists it. A shortcut
 * that exists but is not documented, or documented but not wired, is not possible here — which is
 * the whole reason this is a list rather than a `when` over key codes.
 */
enum class ShortcutGroup(val title: StringResource) {
    Global(Res.string.shortcuts_group_global),
    Navigation(Res.string.shortcuts_group_navigation),
    Selection(Res.string.shortcuts_group_selection),
}

/**
 * `Ctrl` on Linux and Windows, `Cmd` on macOS — decided once, here, rather than at every call
 * site. AWT reports the platform's own modifier, so the choice is a read of the OS and not a
 * guess about which key was pressed.
 */
private val isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac")

/** Which modifiers a shortcut needs. [primary] is Ctrl/Cmd, resolved per platform. */
data class Modifiers(
    val primary: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    fun matches(event: KeyEvent): Boolean {
        val primaryHeld = if (isMac) event.isMetaPressed else event.isCtrlPressed
        return primaryHeld == primary && event.isShiftPressed == shift && event.isAltPressed == alt
    }

    /** "Ctrl+Shift" / "⌘⇧", for the cheat sheet. */
    fun label(): String = buildList {
        if (primary) add(if (isMac) "⌘" else "Ctrl")
        if (shift) add(if (isMac) "⇧" else "Shift")
        if (alt) add(if (isMac) "⌥" else "Alt")
    }.joinToString(if (isMac) "" else "+")
}

/** What a shortcut asks the shell to do. The shell owns the state; this names the verb. */
enum class ShortcutAction {
    QuickAdd,
    SelectNext,
    SelectPrevious,
    OpenSelected,
    ToggleSelected,
    DeleteSelected,
    SelectedPriority1,
    SelectedPriority2,
    SelectedPriority3,
    SelectedPriority4,
    SelectedDueToday,
    SelectedDueTomorrow,
    SelectedDueNextWeek,
    SelectedNoDueDate,
    CommandPalette,
    Search,
    Settings,
    ToggleSidebar,
    SyncNow,
    Undo,
    ShowShortcuts,
    Back,
    Forward,
    Close,
    GoToday,
    GoUpcoming,
    GoInbox,
    GoProjects,
}

data class Shortcut(
    val action: ShortcutAction,
    val key: Key,
    val modifiers: Modifiers = Modifiers(),
    val group: ShortcutGroup,
    val label: StringResource,
    /** How the key itself is written in the sheet — `Key` has no printable name of its own. */
    val keyLabel: String,
) {
    fun matches(event: KeyEvent): Boolean =
        event.type == KeyEventType.KeyDown && event.key == key && modifiers.matches(event)

    /** "Ctrl+K", or just "?" for a bare key. */
    fun combination(): String {
        val mods = modifiers.label()
        return if (mods.isEmpty()) keyLabel else if (isMac) "$mods$keyLabel" else "$mods+$keyLabel"
    }
}

private val primary = Modifiers(primary = true)

/**
 * The keys that act on the selected row.
 *
 * Bare letters, deliberately: this is a list, not a form, and `x` to tick something off is what a
 * keyboard-driven task list has meant since mutt. They reach the window only when nothing focused
 * has taken them first, so typing into the quick-add field never completes a task.
 */
private val SELECTION_SHORTCUTS: List<Shortcut> = listOf(
    Shortcut(ShortcutAction.SelectNext, Key.J, Modifiers(), ShortcutGroup.Selection, Res.string.action_more, "J"),
    Shortcut(
        ShortcutAction.SelectPrevious, Key.K, Modifiers(), ShortcutGroup.Selection,
        Res.string.action_back, "K",
    ),
    Shortcut(
        ShortcutAction.SelectNext, Key.DirectionDown, Modifiers(), ShortcutGroup.Selection,
        Res.string.action_more, "↓",
    ),
    Shortcut(
        ShortcutAction.SelectPrevious, Key.DirectionUp, Modifiers(), ShortcutGroup.Selection,
        Res.string.action_back, "↑",
    ),
    Shortcut(
        ShortcutAction.OpenSelected, Key.Enter, Modifiers(), ShortcutGroup.Selection,
        Res.string.menu_open, "Enter",
    ),
    Shortcut(
        ShortcutAction.ToggleSelected, Key.X, Modifiers(), ShortcutGroup.Selection,
        Res.string.task_mark_done, "X",
    ),
    Shortcut(
        ShortcutAction.DeleteSelected, Key.Delete, Modifiers(), ShortcutGroup.Selection,
        Res.string.task_delete, "Del",
    ),
    Shortcut(
        ShortcutAction.SelectedPriority1, Key.One, Modifiers(), ShortcutGroup.Selection,
        Res.string.priority_p1_title, "1",
    ),
    Shortcut(
        ShortcutAction.SelectedPriority2, Key.Two, Modifiers(), ShortcutGroup.Selection,
        Res.string.priority_p2_title, "2",
    ),
    Shortcut(
        ShortcutAction.SelectedPriority3, Key.Three, Modifiers(), ShortcutGroup.Selection,
        Res.string.priority_p3_title, "3",
    ),
    Shortcut(
        ShortcutAction.SelectedPriority4, Key.Four, Modifiers(), ShortcutGroup.Selection,
        Res.string.priority_p4_title, "4",
    ),
    Shortcut(
        ShortcutAction.SelectedDueToday, Key.T, Modifiers(), ShortcutGroup.Selection,
        Res.string.date_today, "T",
    ),
    Shortcut(
        ShortcutAction.SelectedDueTomorrow, Key.M, Modifiers(), ShortcutGroup.Selection,
        Res.string.date_tomorrow, "M",
    ),
    Shortcut(
        ShortcutAction.SelectedDueNextWeek, Key.W, Modifiers(), ShortcutGroup.Selection,
        Res.string.menu_next_week, "W",
    ),
    Shortcut(
        ShortcutAction.SelectedNoDueDate, Key.Zero, Modifiers(), ShortcutGroup.Selection,
        Res.string.task_no_due_date, "0",
    ),
)

val CADENCE_SHORTCUTS: List<Shortcut> = listOf(
    Shortcut(ShortcutAction.QuickAdd, Key.N, primary, ShortcutGroup.Global, Res.string.command_new_task, "N"),
    Shortcut(
        ShortcutAction.CommandPalette, Key.K, primary, ShortcutGroup.Global,
        Res.string.palette_title, "K",
    ),
    Shortcut(ShortcutAction.Search, Key.F, primary, ShortcutGroup.Global, Res.string.search_title, "F"),
    Shortcut(
        ShortcutAction.Settings, Key.Comma, primary, ShortcutGroup.Global,
        Res.string.settings_title, ",",
    ),
    Shortcut(
        ShortcutAction.ToggleSidebar, Key.B, primary, ShortcutGroup.Global,
        Res.string.command_toggle_sidebar, "B",
    ),
    Shortcut(
        ShortcutAction.SyncNow, Key.R, primary, ShortcutGroup.Global,
        Res.string.command_sync_now, "R",
    ),
    Shortcut(ShortcutAction.Undo, Key.Z, primary, ShortcutGroup.Global, Res.string.undo_action, "Z"),
    Shortcut(
        ShortcutAction.ShowShortcuts, Key.Slash, Modifiers(shift = true), ShortcutGroup.Global,
        Res.string.command_show_shortcuts, "?",
    ),
    Shortcut(
        ShortcutAction.Back, Key.DirectionLeft, Modifiers(alt = true), ShortcutGroup.Navigation,
        Res.string.action_back, "←",
    ),
    Shortcut(
        ShortcutAction.Forward, Key.DirectionRight, Modifiers(alt = true), ShortcutGroup.Navigation,
        Res.string.action_more, "→",
    ),
    Shortcut(
        ShortcutAction.Close, Key.Escape, Modifiers(), ShortcutGroup.Navigation,
        Res.string.action_cancel, "Esc",
    ),
    Shortcut(
        ShortcutAction.GoToday, Key.One, primary, ShortcutGroup.Navigation,
        Res.string.nav_today, "1",
    ),
    Shortcut(
        ShortcutAction.GoUpcoming, Key.Two, primary, ShortcutGroup.Navigation,
        Res.string.nav_upcoming, "2",
    ),
    Shortcut(
        ShortcutAction.GoInbox, Key.Three, primary, ShortcutGroup.Navigation,
        Res.string.nav_inbox, "3",
    ),
    Shortcut(
        ShortcutAction.GoProjects, Key.Four, primary, ShortcutGroup.Navigation,
        Res.string.nav_projects, "4",
    ),
) + SELECTION_SHORTCUTS

/** The action [event] asks for, or null if the table has nothing for it. */
fun shortcutFor(event: KeyEvent): ShortcutAction? =
    CADENCE_SHORTCUTS.firstOrNull { it.matches(event) }?.action
