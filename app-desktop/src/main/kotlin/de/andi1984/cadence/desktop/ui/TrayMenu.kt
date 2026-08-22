package de.andi1984.cadence.desktop.ui

import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.TrayIcon

/**
 * The tray icon's menu.
 *
 * The icon has been in the tray since phase 5 and could only ever pop a reminder balloon —
 * right-clicking it did nothing, which reads as a broken tray icon rather than a deliberate one.
 * Four items is what a tray menu is for: show the window, capture something, sync, quit.
 *
 * Plain AWT, because the tray is AWT: Compose Desktop's `Tray` composable exists but wants to own
 * the icon, and this one already belongs to `DesktopReminderScheduler`, which needs it whether or
 * not a window is open.
 *
 * The callbacks run on AWT's event thread and touch Compose state, which is safe — snapshot state
 * may be written from any thread — but they must not *block*, so each one is a flag flip or a
 * fire-and-forget.
 */
fun TrayIcon.installMenu(
    labels: TrayLabels,
    onShow: () -> Unit,
    onNewTask: () -> Unit,
    onSync: () -> Unit,
    onQuit: () -> Unit,
) {
    popupMenu = PopupMenu().apply {
        add(MenuItem(labels.show).apply { addActionListener { onShow() } })
        add(MenuItem(labels.newTask).apply { addActionListener { onNewTask() } })
        add(MenuItem(labels.sync).apply { addActionListener { onSync() } })
        addSeparator()
        add(MenuItem(labels.quit).apply { addActionListener { onQuit() } })
    }
    // Double-clicking the icon is "show me the window" on every platform that has a tray.
    addActionListener { onShow() }
}

/**
 * The menu's four words, resolved by the caller.
 *
 * AWT cannot read a Compose resource, and no user-visible string may live in Kotlin (the project
 * rule), so the shell resolves them inside a composable and hands them over as data.
 */
data class TrayLabels(
    val show: String,
    val newTask: String,
    val sync: String,
    val quit: String,
)
