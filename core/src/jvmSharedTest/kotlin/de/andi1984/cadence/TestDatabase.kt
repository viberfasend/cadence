package de.andi1984.cadence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.andi1984.cadence.data.BlobStore
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.TaskStore
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.SqlDelightAttachmentStore
import de.andi1984.cadence.data.db.SqlDelightBackupStore
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightSectionStore
import de.andi1984.cadence.data.db.SqlDelightStoreTransaction
import de.andi1984.cadence.data.db.SqlDelightSyncStore
import de.andi1984.cadence.data.db.SqlDelightTagStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.time.Clock
import java.time.Instant

/**
 * An in-memory database opened the way both shells open theirs: the schema created, foreign keys
 * switched on afterwards (`DatabaseDriverFactory` does the same, and for the same reason — the
 * `attachmentRow → taskRow` cascade is part of what the app runs under).
 */
fun inMemoryDatabase(): CadenceDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    CadenceDatabase.Schema.create(driver)
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    return CadenceDatabase(driver)
}

/**
 * The real SQLDelight stores over one in-memory database, and a real [CadenceRepository] on top.
 *
 * There is no in-memory fake of any store port in this suite, deliberately. The contract those
 * ports carry — tombstones filtered on every read, `completeIfOpen`/`reopenIfDone` deciding
 * idempotence inside the store, the `sortOrder != position` guard that keeps a drag off the wire,
 * a section delete freeing its tasks — is written once, in the `.sq` files under `data/db/`, and
 * a fake would be a second copy of it that no test pins. The ports stay as interfaces for their
 * vocabulary (`Task` and `Project`, not rows), not as a seam anything else plugs into.
 *
 * Everything takes [Dispatchers.Unconfined] so the work stays on the test's own scheduler: a real
 * `Dispatchers.IO` inside `attachmentIndex`'s `flowOn`, or under a store's `withContext`, would
 * put the state flow on a thread `runTest`'s virtual clock does not control.
 */
class TestStores(val database: CadenceDatabase = inMemoryDatabase()) {

    val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
    val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
    val sectionStore = SqlDelightSectionStore(database, Dispatchers.Unconfined)
    val tagStore = SqlDelightTagStore(database, Dispatchers.Unconfined)
    val attachmentStore = SqlDelightAttachmentStore(database, Dispatchers.Unconfined)
    val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
    val syncStore = SqlDelightSyncStore(database, Dispatchers.Unconfined)
    val storeTransaction = SqlDelightStoreTransaction(database, Dispatchers.Unconfined)
    val blobStore = BlobStore(
        root = Files.createTempDirectory("cadence-blobs").toFile(),
        tmp = Files.createTempDirectory("cadence-blobs-tmp").toFile(),
    )

    /** A repository over the stores above. [taskStore] can be swapped for a wrapper around the
     *  real one — a test that needs to stand inside a write gates it that way. [clock] defaults
     *  to the real one; a test pins it to assert against a fixed "now"/"today" instead of reading
     *  the machine's own. */
    fun repository(
        taskStore: TaskStore = this.taskStore,
        clock: Clock = Clock.systemDefaultZone(),
    ): CadenceRepository = CadenceRepository(
        taskStore,
        projectStore,
        sectionStore,
        tagStore,
        backupStore,
        attachmentStore,
        blobStore,
        storeTransaction,
        Dispatchers.Unconfined,
        clock,
    )

    // Raw rows, tombstones included — read through sync's view, the only queries in the schema
    // that see them. This is how a test asserts that a delete *stamped* the row rather than
    // removing it.

    suspend fun taskRow(id: String): Task =
        syncStore.tasksChangedSince(Instant.EPOCH).firstOrNull { it.id == id }
            ?: error("no task with id $id")

    suspend fun projectRow(id: String): Project =
        syncStore.projectsChangedSince(Instant.EPOCH).firstOrNull { it.id == id }
            ?: error("no project with id $id")

    suspend fun sectionRow(id: String): Section =
        syncStore.sectionsChangedSince(Instant.EPOCH).firstOrNull { it.id == id }
            ?: error("no section with id $id")

    suspend fun tagRow(id: String): Tag =
        syncStore.tagsChangedSince(Instant.EPOCH).firstOrNull { it.id == id }
            ?: error("no tag with id $id")
}
