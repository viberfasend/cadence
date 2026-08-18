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
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.data.sync.CadenceSyncEngine
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
        backupStore = SqlDelightBackupStore(database),
        attachmentStore = SqlDelightAttachmentStore(database),
        blobStore = blobStore,
    )

    val settingsStore = DesktopSettingsStore(dataDir)

    val backupIo = DesktopBackupIo(repository, settingsStore)

    val backupFilePicker = DesktopBackupFilePicker()

    /** Outlives every window: the same reasoning as the Android container's application-scoped
     *  backup sync — the write that starts as the user quits must not hang off a scope that is
     *  already being torn down. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Same shape as `:app-android`'s, because sync is not a platform difference (ADR 0002,
     *  decision 7): the same class over the same database, on the process-wide scope. */
    val syncEngine = CadenceSyncEngine(
        store = SqlDelightSyncStore(database),
        scope = applicationScope,
    )

    val reminderScheduler = DesktopReminderScheduler(
        scope = applicationScope,
        trayIcon = createReminderTrayIcon(),
    )

    init {
        applicationScope.launch { repository.sweepOrphanBlobs() }
    }
}
