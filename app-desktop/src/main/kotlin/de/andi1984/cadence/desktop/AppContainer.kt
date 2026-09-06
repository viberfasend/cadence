package de.andi1984.cadence.desktop

import de.andi1984.cadence.data.CadenceCore
import de.andi1984.cadence.data.db.DatabaseDriverFactory
import de.andi1984.cadence.desktop.data.DesktopAttachmentOpener
import de.andi1984.cadence.desktop.data.DesktopBackupFilePicker
import de.andi1984.cadence.desktop.data.DesktopBackupIo
import de.andi1984.cadence.desktop.data.DesktopReminderScheduler
import de.andi1984.cadence.desktop.data.DesktopSettingsStore
import de.andi1984.cadence.desktop.data.createReminderTrayIcon
import de.andi1984.cadence.desktop.platform.PlatformDirs
import de.andi1984.cadence.ui.ViewModelAdapters
import java.awt.TrayIcon

/** Hand-rolled dependency graph, same idea as `:app-android`'s `AppContainer`: the app is small
 *  enough not to need a DI framework. Lives for the whole process — Compose Desktop has no
 *  Application class to build it in, so [main] constructs one and keeps it alive.
 *
 *  The database, the blob store, the repository and the sync engine are built once, in
 *  [CadenceCore], for both shells; what is left here is the desktop's own adapters — a JSON
 *  settings file, `java.awt.Desktop`, `JFileChooser`, the tray icon poll — plus [close], the one
 *  exit path both `onCloseRequest` and the tray's Quit call. */
class AppContainer {

    private val dataDir = PlatformDirs.dataDir()

    val core = CadenceCore(
        driver = DatabaseDriverFactory(dataDir).createDriver(),
        dataDir = dataDir,
    )

    val repository = core.repository

    /** [CadenceCore.applicationScope], named here too — the desktop's writes (settings, the
     *  reminder scheduler) used to reach a field declared directly on this class. */
    val applicationScope = core.applicationScope

    /** On [applicationScope] because its writes are, and declared after it for the same reason —
     *  a field initialiser can only read what is already built. */
    val settingsStore = DesktopSettingsStore(dataDir, applicationScope)

    val backupIo = DesktopBackupIo(repository, settingsStore)

    val backupFilePicker = DesktopBackupFilePicker()

    /** `java.awt.Desktop` where Android has an intent — the picker half lives in the composition,
     *  so only this one is a container singleton. */
    val attachmentOpener = DesktopAttachmentOpener()

    /** Same shape as `:app-android`'s, because sync is not a platform difference (ADR 0002,
     *  decision 7): the same class over the same database, on the process-wide scope. */
    val syncEngine = core.syncEngine

    /**
     * The tray icon, or null where there is no tray (a headless run, and several Linux desktops
     * that claim one and have none).
     *
     * Held here rather than created inside the scheduler because it is no longer only the
     * scheduler's: `main()` hangs the tray menu — show, new task, sync, quit — off the same icon,
     * and two icons would mean two Cadences in the tray.
     */
    val trayIcon: TrayIcon? = createReminderTrayIcon()

    val reminderScheduler = DesktopReminderScheduler(
        scope = applicationScope,
        trayIcon = trayIcon,
    )

    /** Handed to [de.andi1984.cadence.ui.cadenceViewModel] by `main()` so the seven-argument
     *  `CadenceViewModel` constructor is written once, in `:ui`. */
    val viewModelAdapters = ViewModelAdapters(
        settingsStore = settingsStore,
        reminderScheduler = reminderScheduler,
        backupGateway = backupIo,
        attachmentOpener = attachmentOpener,
    )

    /**
     * The one exit path (CLAUDE.md, "SettingsStore's setters are not suspend"): both
     * `onCloseRequest` and the tray menu's Quit call this, in the order that rule requires —
     * a last, fire-and-forget sync while there is still a process to run it, then the settings
     * flush `exitApplication()` must not race, then [CadenceCore.close] to cancel the
     * application scope and close the database driver.
     *
     * Sync stays fire-and-forget on [applicationScope] rather than being awaited: the window
     * must not hesitate on the way out, and a push that misses this moment ships on the next
     * start (ADR 0002, decision 11) — which is also why it runs *before* the scope it needs is
     * cancelled, not after.
     */
    fun shutdown() {
        syncEngine.syncInBackground()
        settingsStore.flush()
        core.close()
    }
}
