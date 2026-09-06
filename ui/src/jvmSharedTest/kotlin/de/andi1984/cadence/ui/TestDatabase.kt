package de.andi1984.cadence.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.andi1984.cadence.data.BlobStore
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.TaskStore
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.SqlDelightAttachmentStore
import de.andi1984.cadence.data.db.SqlDelightBackupStore
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightSectionStore
import de.andi1984.cadence.data.db.SqlDelightTagStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.time.Clock

/**
 * An in-memory database opened the way both shells open theirs: the schema created, foreign keys
 * switched on afterwards — the same recipe as `:core`'s `TestDatabase.kt`, repeated here because
 * a test source set cannot reach another module's.
 */
fun inMemoryDatabase(): CadenceDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    CadenceDatabase.Schema.create(driver)
    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    return CadenceDatabase(driver)
}

/**
 * The real SQLDelight stores over one in-memory database, and a real [CadenceRepository] on top —
 * what every ViewModel test drives.
 *
 * Nothing between the ViewModel and SQLite is faked: the rules under test here — which rows a
 * delete takes with it, which ids come back so their alarms can be cancelled, what a section
 * delete leaves on the tasks it grouped — are the repository's *and the store's*, and an
 * in-memory fake of the stores used to carry its own copy of the second half. The platform ports
 * the ViewModel reaches (`Fakes.kt`) are the only things faked, because those really are seams
 * with one adapter per shell.
 *
 * Everything takes [Dispatchers.Unconfined] so the work stays on `runTest`'s scheduler: a real
 * `Dispatchers.IO` under a store's `withContext`, or inside `attachmentIndex`'s `flowOn`, would
 * put the state flow on a thread the virtual clock does not control, and every assertion after a
 * write would race it.
 */
class TestStores(val database: CadenceDatabase = inMemoryDatabase()) {

    val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
    val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
    val sectionStore = SqlDelightSectionStore(database, Dispatchers.Unconfined)
    val tagStore = SqlDelightTagStore(database, Dispatchers.Unconfined)
    val attachmentStore = SqlDelightAttachmentStore(database, Dispatchers.Unconfined)
    val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
    val blobStore = BlobStore(
        root = Files.createTempDirectory("cadence-ui-blobs").toFile(),
        tmp = Files.createTempDirectory("cadence-ui-blobs-tmp").toFile(),
    )

    /** A repository over the stores above. [taskStore] can be swapped for a wrapper around the
     *  real one — `CadenceViewModelUndoTest` gates a write that way to stand inside it. [clock]
     *  defaults to the real one; a test pins it to assert against a fixed "now"/"today" instead
     *  of reading the machine's own. */
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
        Dispatchers.Unconfined,
        clock,
    )

    private var nextId = 1

    // Seeding. Rows go in through the ports, ids and all — a test names its rows so it can name
    // them again in the ViewModel call and the assertion.

    suspend fun seedTasks(vararg tasks: Task) = tasks.forEach { task ->
        require(task.id.isNotBlank()) { "a seeded task needs an id: ${task.title}" }
        taskStore.insert(task)
    }

    suspend fun seedProjects(vararg projects: Project) = projects.forEach { project ->
        require(project.id.isNotBlank()) { "a seeded project needs an id: ${project.name}" }
        projectStore.insert(project)
    }

    suspend fun seedSections(vararg sections: Section) = sections.forEach { section ->
        require(section.id.isNotBlank()) { "a seeded section needs an id: ${section.name}" }
        sectionStore.insert(section)
    }

    /** Puts a tag in directly, minting an id when it carries none — the shortcut a setup wants. */
    suspend fun putTag(tag: Tag): Tag {
        val stored = if (tag.id.isBlank()) tag.copy(id = "tag-${nextId++}") else tag
        tagStore.insert(stored)
        return stored
    }

    /** Every attachment row, across tasks. */
    suspend fun attachments(): List<Attachment> = attachmentStore.observeAll().first()
}
