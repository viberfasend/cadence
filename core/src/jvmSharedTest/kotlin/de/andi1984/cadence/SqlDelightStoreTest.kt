package de.andi1984.cadence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.SqlDelightBackupStore
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The `.sq` queries against a real (in-memory) SQLite database — the JVM tests carry this, per
 * `docs/agents/`'s testing conventions, since it needs no Android runtime.
 */
class SqlDelightStoreTest {

    private fun newDatabase(): CadenceDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CadenceDatabase.Schema.create(driver)
        return CadenceDatabase(driver)
    }

    private val now = Instant.parse("2026-08-08T09:00:00Z")

    @Test
    fun `a task survives an insert and read back`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val task = Task(id = "t1", title = "Water the plants", createdAt = now, updatedAt = now)

        taskStore.insert(task)

        assertEquals(task, taskStore.byId("t1"))
        assertEquals(listOf(task), taskStore.getAll())
    }

    @Test
    fun `completeIfOpen reports 1 once and 0 on a second call`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Call the vet", createdAt = now, updatedAt = now))

        val first = taskStore.completeIfOpen("t1", now)
        val second = taskStore.completeIfOpen("t1", now)

        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals(now, taskStore.byId("t1")?.completedAt)
    }

    @Test
    fun `reopenIfDone mirrors completeIfOpen`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Call the vet", createdAt = now, updatedAt = now))
        taskStore.completeIfOpen("t1", now)

        val first = taskStore.reopenIfDone("t1", now)
        val second = taskStore.reopenIfDone("t1", now)

        assertEquals(1, first)
        assertEquals(0, second)
        assertNull(taskStore.byId("t1")?.completedAt)
    }

    @Test
    fun `deleting a project moves its tasks to the Inbox unless deleteTasks is set`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        projectStore.insert(Project(id = "p1", name = "Home", updatedAt = now))
        taskStore.insert(Task(id = "t1", title = "Water the plants", projectId = "p1", createdAt = now, updatedAt = now))

        projectStore.tombstoneWithChildren("p1", deleteTasks = false, at = now)

        assertNull(taskStore.byId("t1")?.projectId)
        assertTrue(projectStore.getAll().isEmpty())
    }

    @Test
    fun `deleting a project with deleteTasks removes its tasks too`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        projectStore.insert(Project(id = "p1", name = "Home", updatedAt = now))
        taskStore.insert(Task(id = "t1", title = "Water the plants", projectId = "p1", createdAt = now, updatedAt = now))

        projectStore.tombstoneWithChildren("p1", deleteTasks = true, at = now)

        assertNull(taskStore.byId("t1"))
    }

    @Test
    fun `restoring a backup merges into what is already stored`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "stale", title = "Old task", createdAt = now, updatedAt = now))

        backupStore.mergeAll(
            projects = listOf(Project(id = "p1", name = "Home", updatedAt = now)),
            tasks = listOf(Task(id = "t1", title = "Fresh task", projectId = "p1", createdAt = now, updatedAt = now)),
            revivedAt = now,
        )

        // The row the file knows nothing about survives. This assertion was the exact opposite
        // before ADR 0002: importing replaced both tables, so anything added since the file was
        // written was silently discarded — the reason two devices sharing one file each undid
        // the other.
        assertEquals("Old task", taskStore.byId("stale")?.title)
        assertEquals("Fresh task", taskStore.byId("t1")?.title)
        assertEquals(listOf("p1"), projectStore.getAll().map { it.id })
    }

    @Test
    fun `merging keeps whichever version was written last`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        val later = now.plusSeconds(60)
        taskStore.insert(Task(id = "t1", title = "Local wins", createdAt = now, updatedAt = later))

        // Older than what is stored, so it loses; a tie would also keep the stored row.
        backupStore.mergeAll(
            projects = emptyList(),
            tasks = listOf(Task(id = "t1", title = "Stale", createdAt = now, updatedAt = now)),
            revivedAt = now,
        )
        assertEquals("Local wins", taskStore.byId("t1")?.title)

        backupStore.mergeAll(
            projects = emptyList(),
            tasks = listOf(Task(id = "t1", title = "Newer", createdAt = now, updatedAt = later.plusSeconds(1))),
            revivedAt = now,
        )
        assertEquals("Newer", taskStore.byId("t1")?.title)
    }

    @Test
    fun `a tombstone competes on its timestamp like any other version`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        val later = now.plusSeconds(60)
        taskStore.insert(Task(id = "t1", title = "Edited here", createdAt = now, updatedAt = later))

        // A delete older than the local edit must lose. The first cut of mergeAll special-cased
        // deletedAt into an unconditional delete, so a stale delete beat a newer edit.
        backupStore.mergeAll(
            projects = emptyList(),
            tasks = listOf(Task(id = "t1", title = "Deleted there", createdAt = now, updatedAt = now, deletedAt = now)),
            revivedAt = now,
        )
        assertEquals("Edited here", taskStore.byId("t1")?.title)

        // A newer one wins, and the row goes invisible without being removed.
        val deletedAt = later.plusSeconds(1)
        backupStore.mergeAll(
            projects = emptyList(),
            tasks = listOf(
                Task(id = "t1", title = "Deleted there", createdAt = now, updatedAt = deletedAt, deletedAt = deletedAt),
            ),
            revivedAt = now,
        )
        assertNull(taskStore.byId("t1"))
    }

    @Test
    fun `a tombstone for a task we have never seen is stored, not dropped`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)

        backupStore.mergeAll(
            projects = emptyList(),
            tasks = listOf(Task(id = "t1", title = "Gone", createdAt = now, updatedAt = now, deletedAt = now)),
            revivedAt = now,
        )
        assertNull(taskStore.byId("t1"))
    }

    /**
     * A file is an instruction, not a version — the one place the merge rule bends.
     *
     * Everywhere else, an incoming record older than a local tombstone loses, which is what stops
     * a sync round from resurrecting what another device deleted. Importing is a person pointing
     * at a file and asking for its contents, and answering that by writing nothing at all — while
     * still reporting the file's counts — is how a Todoist re-import came back empty: the tool
     * derives its ids from project names, so the second import carried exactly the ids the delete
     * had just tombstoned, each stamped when the *file* was written.
     *
     * The revived row is stamped [revivedAt] rather than keeping the file's timestamp, or the
     * server's tombstone would win the next round and delete it straight back.
     */
    @Test
    fun `importing a file restores what this device had deleted`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        val deletedAt = now.plusSeconds(600)
        projectStore.insert(Project(id = "p1", name = "Import", updatedAt = deletedAt, deletedAt = deletedAt))
        taskStore.insert(
            Task(id = "t1", title = "Gone", projectId = "p1", createdAt = now, updatedAt = deletedAt, deletedAt = deletedAt),
        )
        val importedAt = deletedAt.plusSeconds(60)

        // The file is older than the tombstones — it was written before the delete.
        backupStore.mergeAll(
            projects = listOf(Project(id = "p1", name = "Import", updatedAt = now)),
            tasks = listOf(Task(id = "t1", title = "Back", projectId = "p1", createdAt = now, updatedAt = now)),
            revivedAt = importedAt,
        )

        assertEquals("Back", taskStore.byId("t1")?.title)
        assertEquals(importedAt, taskStore.byId("t1")?.updatedAt)
        assertEquals(listOf("p1"), projectStore.getAll().map { it.id })
        assertEquals(importedAt, projectStore.getAll().single().updatedAt)
    }

    /** The other half of that rule: a file's own tombstone is still only a version, so it does not
     *  revive anything and it still loses to a newer local edit. */
    @Test
    fun `an imported tombstone revives nothing`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        val deletedAt = now.plusSeconds(600)
        taskStore.insert(Task(id = "t1", title = "Gone", createdAt = now, updatedAt = deletedAt, deletedAt = deletedAt))

        backupStore.mergeAll(
            projects = emptyList(),
            tasks = listOf(Task(id = "t1", title = "Also gone", createdAt = now, updatedAt = now, deletedAt = now)),
            revivedAt = deletedAt.plusSeconds(60),
        )

        assertNull(taskStore.byId("t1"))
    }

    @Test
    fun `a tombstoned task is invisible to every read`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "parent", title = "Parent", createdAt = now, updatedAt = now))
        taskStore.insert(Task(id = "step", title = "Step", parentId = "parent", createdAt = now, updatedAt = now))
        taskStore.insert(
            Task(id = "next", title = "Next", spawnedFromId = "parent", createdAt = now, updatedAt = now),
        )

        taskStore.tombstoneWithSubtasks("parent", now)

        assertNull(taskStore.byId("parent"))
        assertTrue(taskStore.getAll().none { it.id == "parent" || it.id == "step" })
        // The step went with its task — a step without its task has no meaning.
        assertTrue(taskStore.subtasksOf("parent").isEmpty())
        // Completing one is not merely a no-op, it must *report* that it did nothing, or the
        // caller schedules a successor for a task nobody can see.
        assertEquals(0, taskStore.completeIfOpen("parent", now))
        // The successor is a separate row and is untouched.
        assertEquals(listOf("next"), taskStore.openSuccessorsOf("parent"))
    }
}
