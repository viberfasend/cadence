package de.andi1984.cadence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.SqlDelightAttachmentStore
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightStoreTransaction
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/**
 * [SqlDelightStoreTransaction] — the seam [de.andi1984.cadence.data.CadenceRepository] uses to
 * make several stores commit together (`setCompleted`, `deleteTask`, `deleteProject`).
 *
 * The `Dispatchers.IO` case is deliberate, and the point of that one test: every other test in
 * this suite runs stores over [Dispatchers.Unconfined], which never dispatches at all, so it
 * would not catch a genuine cross-thread hop. Reproducing a real dispatcher confirms `run` still
 * commits and rolls back correctly when its one [kotlinx.coroutines.withContext] call really does
 * move to a different worker thread first.
 */
class SqlDelightStoreTransactionTest {

    private val now = Instant.parse("2026-08-08T09:00:00Z")

    private fun newInMemoryDatabase(): CadenceDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CadenceDatabase.Schema.create(driver)
        return CadenceDatabase(driver)
    }

    private fun newFileBackedDatabase(): CadenceDatabase {
        val path = Files.createTempFile("cadence-store-transaction-test", ".db")
        path.toFile().deleteOnExit()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        CadenceDatabase.Schema.create(driver)
        return CadenceDatabase(driver)
    }

    @Test
    fun `a run block that throws rolls back every write it made, across two tables`() = runTest {
        val database = newInMemoryDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val attachmentStore = SqlDelightAttachmentStore(database, Dispatchers.Unconfined)
        val transaction = SqlDelightStoreTransaction(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Water the plants", createdAt = now, updatedAt = now))

        val thrown = runCatching {
            transaction.run {
                updateTask(Task(id = "t1", title = "Renamed", createdAt = now, updatedAt = now))
                insertAttachment(
                    Attachment(
                        id = "a1",
                        taskId = "t1",
                        kind = AttachmentKind.LINK,
                        name = "spec",
                        mimeType = "text/uri-list",
                        url = "https://example.com",
                        createdAt = now,
                    ),
                )
                error("boom")
            }
        }.exceptionOrNull()

        assertEquals("boom", thrown?.message)
        // Neither write landed: the task keeps its original title and the attachment never exists.
        assertEquals("Water the plants", taskStore.byId("t1")?.title)
        assertTrue(attachmentStore.forTask("t1").isEmpty())
    }

    @Test
    fun `a successful run block commits every write together`() = runTest {
        val database = newInMemoryDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val transaction = SqlDelightStoreTransaction(database, Dispatchers.Unconfined)
        projectStore.insert(Project(id = "p1", name = "Home", updatedAt = now))
        taskStore.insert(
            Task(id = "t1", title = "Water the plants", projectId = "p1", createdAt = now, updatedAt = now),
        )

        transaction.run {
            // A later statement in the same run{} block sees an earlier one's write: renaming the
            // task and then deleting its project (which would otherwise move it to the Inbox) both
            // land, and the project delete wins the race for `projectId`, exactly as it would if
            // these were two separate calls outside any transaction at all.
            updateTask(Task(id = "t1", title = "Renamed", projectId = "p1", createdAt = now, updatedAt = now))
            tombstoneProjectWithChildren("p1", deleteTasks = false, at = now)
        }

        assertEquals("Renamed", taskStore.byId("t1")?.title)
        assertNull(taskStore.byId("t1")?.projectId)
        assertTrue(projectStore.getAll().isEmpty())
    }

    @Test
    fun `completing a recurring task inserts its successor in the same transaction as the close`() = runTest {
        val database = newInMemoryDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val transaction = SqlDelightStoreTransaction(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Take out the bins", createdAt = now, updatedAt = now))

        val closedThenSuccessorInserted = transaction.run {
            val closed = completeTaskIfOpen("t1", now)
            if (closed == 1) insertTask(Task(id = "t2", title = "Take out the bins", createdAt = now, updatedAt = now))
            closed
        }

        assertEquals(1, closedThenSuccessorInserted)
        assertEquals(now, taskStore.byId("t1")?.completedAt)
        assertEquals("Take out the bins", taskStore.byId("t2")?.title)
    }

    @Test
    fun `over a real dispatcher and a file-backed database, a run block commits atomically`() = runTest {
        val database = newFileBackedDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.IO)
        val attachmentStore = SqlDelightAttachmentStore(database, Dispatchers.IO)
        val transaction = SqlDelightStoreTransaction(database, Dispatchers.IO)
        taskStore.insert(Task(id = "t1", title = "Water the plants", createdAt = now, updatedAt = now))

        transaction.run {
            updateTask(Task(id = "t1", title = "Watered", completedAt = now, createdAt = now, updatedAt = now))
            insertAttachment(
                Attachment(
                    id = "a1",
                    taskId = "t1",
                    kind = AttachmentKind.LINK,
                    name = "spec",
                    mimeType = "text/uri-list",
                    url = "https://example.com",
                    createdAt = now,
                ),
            )
        }

        assertEquals("Watered", taskStore.byId("t1")?.title)
        assertEquals(1, attachmentStore.forTask("t1").size)
    }

    @Test
    fun `over a real dispatcher and a file-backed database, a run block that throws rolls back`() = runTest {
        val database = newFileBackedDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.IO)
        val attachmentStore = SqlDelightAttachmentStore(database, Dispatchers.IO)
        val transaction = SqlDelightStoreTransaction(database, Dispatchers.IO)
        taskStore.insert(Task(id = "t1", title = "Water the plants", createdAt = now, updatedAt = now))

        val thrown = runCatching {
            transaction.run {
                updateTask(Task(id = "t1", title = "Renamed", createdAt = now, updatedAt = now))
                insertAttachment(
                    Attachment(
                        id = "a1",
                        taskId = "t1",
                        kind = AttachmentKind.LINK,
                        name = "spec",
                        mimeType = "text/uri-list",
                        url = "https://example.com",
                        createdAt = now,
                    ),
                )
                error("boom")
            }
        }.exceptionOrNull()

        assertEquals("boom", thrown?.message)
        assertEquals("Water the plants", taskStore.byId("t1")?.title)
        assertNull(attachmentStore.forTask("t1").firstOrNull())
    }
}
