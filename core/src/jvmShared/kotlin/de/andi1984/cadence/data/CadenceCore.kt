package de.andi1984.cadence.data

import app.cash.sqldelight.db.SqlDriver
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.SqlDelightAttachmentStore
import de.andi1984.cadence.data.db.SqlDelightBackupStore
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightSectionStore
import de.andi1984.cadence.data.db.SqlDelightStoreTransaction
import de.andi1984.cadence.data.db.SqlDelightSyncStore
import de.andi1984.cadence.data.db.SqlDelightTagStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.data.sync.CadenceSyncEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * The dependency graph the two shells used to build twice: the database, the blob store, the
 * eight-argument [CadenceRepository] over them, and the [CadenceSyncEngine] beside it — every
 * store a new feature adds used to mean a line in both `AppContainer`s, and it now means a line
 * here.
 *
 * `:app-android`'s [DatabaseDriverFactory][de.andi1984.cadence.data.db.DatabaseDriverFactory]
 * takes a `Context` and `:app-desktop`'s takes a `File`, which is genuinely platform-specific
 * (ADR 0001) — so this class stays one step short of owning that, and each shell still calls its
 * `DatabaseDriverFactory().createDriver()` and hands the result here. Everything after that line
 * is identical on both platforms.
 *
 * [dataDir] is where the blob store keeps its files — `context.filesDir` on Android,
 * `PlatformDirs.dataDir()` on the desktop — passed in for the same reason the driver is: the
 * directory itself is a platform fact, but what gets built inside it is not.
 */
class CadenceCore(
    private val driver: SqlDriver,
    dataDir: File,
    /**
     * Outlives every screen: a write started as the user leaves the app must not hang off a
     * scope that is already being torn down. Both shells used to build this themselves and hand
     * it to the repository, the sync engine and the blob sweep separately; it is one field now.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val database = CadenceDatabase(driver)

    /** Both directories must sit on the same filesystem — the copy finishes with a `renameTo`
     *  that is only atomic within one volume — so `tmp` is a sibling of `root`, never a separate
     *  cache directory a platform is free to put elsewhere. */
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
        storeTransaction = SqlDelightStoreTransaction(database),
        ioDispatcher = ioDispatcher,
    )

    /** On [applicationScope], not a ViewModel's or a window's: a round that starts as the user
     *  leaves the screen has to be allowed to finish, and the session it refreshes outlives every
     *  screen. Same class over the same database on both shells — sync is deliberately not a
     *  port (ADR 0002, decision 7). */
    val syncEngine = CadenceSyncEngine(
        store = SqlDelightSyncStore(database),
        scope = applicationScope,
    )

    init {
        // Heals a leak left by a process killed mid-copy — cheap even at a few hundred files,
        // and start-up is the only time nothing else is racing the blob directory yet.
        applicationScope.launch { repository.sweepOrphanBlobs() }
    }

    /**
     * Tears down what this class owns: the application scope (which takes the sync engine's
     * in-flight round and the blob sweep with it) and the database driver underneath it.
     *
     * Deliberately not the whole shutdown a shell needs. A shell's own state — settings, a
     * window's position — has its own writes to flush *first*, and this only closes what
     * [CadenceCore] itself opened; see `:app-desktop`'s single exit path for the full ordering.
     */
    fun close() {
        applicationScope.cancel()
        driver.close()
    }
}
