package de.andi1984.cadence.desktop

import de.andi1984.cadence.data.BlobStore
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.DatabaseDriverFactory
import de.andi1984.cadence.data.db.SqlDelightAttachmentStore
import de.andi1984.cadence.data.db.SqlDelightBackupStore
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightSectionStore
import de.andi1984.cadence.data.db.SqlDelightSyncStore
import de.andi1984.cadence.data.db.SqlDelightTagStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.desktop.data.DesktopAttachmentOpener
import de.andi1984.cadence.desktop.data.DesktopBackupFilePicker
import de.andi1984.cadence.desktop.data.DesktopBackupIo
import de.andi1984.cadence.desktop.data.DesktopReminderScheduler
import de.andi1984.cadence.desktop.data.DesktopSettingsStore
import de.andi1984.cadence.desktop.data.createReminderTrayIcon
import de.andi1984.cadence.desktop.platform.PlatformDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Hand-rolled dependency graph, same idea as `:app-android`'s `AppContainer`: the app is small
 *  enough not to need a DI framework. Lives for the whole process — Compose Desktop has no
 *  Application class to build it in, so [main] constructs one and keeps it alive. */
class AppContainer {

    private val dataDir = PlatformDirs.dataDir()

    private val database = CadenceDatabase(DatabaseDriverFactory(dataDir).createDriver())

    private val blobStore = BlobStore(
        root = File(dataDir, "attachments"),
        tmp = File(dataDir, "attachments-tmp"),
    )

    val repository = CadenceRepository(
        taskStore = SqlDelightTaskStore(database),
        projectStore = SqlDelightProjectStore(database),
        sectionStore = SqlDelightSectionStore(database),
        tagStore = SqlDelightTagStore(database),
        backupStore = SqlDelightBackupStore(database),
        attachmentStore = SqlDelightAttachmentStore(database),
        blobStore = blobStore,
    )

    /** Outlives every window: the same reasoning as the Android container's application-scoped
     *  backup sync — the write that starts as the user quits must not hang off a scope that is
     *  already being torn down. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    val syncEngine = CadenceSyncEngine(
        store = SqlDelightSyncStore(database),
        scope = applicationScope,
    )

    /**
     * The tray icon, or null where there is no tray (a headless run, and several Linux desktops
     * that claim one and have none).
     *
     * Held here rather than created inside the scheduler because it is no longer only the
     * scheduler's: `main()` hangs the tray menu — show, new task, sync, quit — off the same icon,
     * and two icons would mean two Cadences in the tray.
     */
    val trayIcon = createReminderTrayIcon()

    val reminderScheduler = DesktopReminderScheduler(
        scope = applicationScope,
        trayIcon = trayIcon,
    )

    init {
        applicationScope.launch { repository.sweepOrphanBlobs() }
    }
}
