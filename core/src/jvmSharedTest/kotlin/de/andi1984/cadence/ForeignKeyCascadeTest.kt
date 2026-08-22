package de.andi1984.cadence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.SqlDelightAttachmentStore
import de.andi1984.cadence.data.db.SqlDelightBackupStore
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant

/**
 * Writing a row that already exists must never *delete* it first.
 *
 * Both shells open the database with `PRAGMA foreign_keys = ON`, and the schema declares
 * `parentId … ON DELETE CASCADE` on both tables plus `projectId … ON DELETE SET NULL` on
 * `taskRow`. Under those two facts an `INSERT OR REPLACE` — which SQLite implements as *delete
 * the conflicting row, then insert* — is a data-loss event dressed up as a write: editing a task
 * took its checklist and its attachments with it, and merging a project row took its subprojects
 * with it and emptied it of tasks.
 *
 * These tests therefore enable the pragma the way the app does. Every other test in this suite
 * leaves it off, which is exactly why the bug lived here undetected for so long.
 */
class ForeignKeyCascadeTest {

    private fun newDatabase(): CadenceDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        CadenceDatabase.Schema.create(driver)
        return CadenceDatabase(driver)
    }

    private val now: Instant = Instant.parse("2026-08-08T09:00:00Z")
    private val later: Instant = now.plusSeconds(60)

    @Test
    fun `editing a task keeps its subtasks`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Pack", createdAt = now, updatedAt = now))
        taskStore.insert(Task(id = "t2", title = "Passport", parentId = "t1", createdAt = now, updatedAt = now))

        taskStore.update(taskStore.byId("t1")!!.copy(title = "Pack for Rome", updatedAt = later))

        assertEquals(listOf("t2"), taskStore.subtasksOf("t1").map { it.id })
    }

    @Test
    fun `editing a task keeps its attachments`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val attachmentStore = SqlDelightAttachmentStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Pack", createdAt = now, updatedAt = now))
        attachmentStore.insert(
            Attachment(
                id = "a1",
                taskId = "t1",
                kind = AttachmentKind.LINK,
                name = "Checklist",
                mimeType = "text/uri-list",
                url = "https://example.com",
                createdAt = now,
            ),
        )

        taskStore.update(taskStore.byId("t1")!!.copy(title = "Pack for Rome", updatedAt = later))

        assertEquals(listOf("a1"), attachmentStore.forTask("t1").map { it.id })
    }

    @Test
    fun `merging a newer task keeps its subtasks`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "t1", title = "Pack", createdAt = now, updatedAt = now))
        taskStore.insert(Task(id = "t2", title = "Passport", parentId = "t1", createdAt = now, updatedAt = now))

        backupStore.mergeAll(
            projects = emptyList(),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(Task(id = "t1", title = "Pack for Rome", createdAt = now, updatedAt = later)),
            revivedAt = now,
        )

        assertEquals("Pack for Rome", taskStore.byId("t1")?.title)
        assertNotNull(taskStore.byId("t2"))
    }

    @Test
    fun `merging a newer project keeps its subprojects and its tasks`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        projectStore.insert(Project(id = "p1", name = "Home", updatedAt = now))
        projectStore.insert(Project(id = "p2", name = "Finance", parentId = "p1", updatedAt = now))
        taskStore.insert(Task(id = "t1", title = "Water the plants", projectId = "p1", createdAt = now, updatedAt = now))

        backupStore.mergeAll(
            projects = listOf(Project(id = "p1", name = "House", updatedAt = later)),
            sections = emptyList(),
            tags = emptyList(),
            tasks =emptyList(),
            revivedAt = now,
        )

        assertEquals(listOf("p1", "p2"), projectStore.getAll().map { it.id }.sorted())
        assertEquals("p1", taskStore.byId("t1")?.projectId)
    }

    /**
     * Saving a project that is not in storage has to write it, not quietly do nothing.
     *
     * `ProjectStore.update` was a bare `UPDATE … WHERE id = ?`, and the row can genuinely be
     * absent: the 90-day sweep collects a tombstoned project outright, so restoring one from the
     * delete snackbar's Undo after that wrote nothing at all and it simply never came back.
     */
    @Test
    fun `saving a project that is no longer stored writes it back`() = runTest {
        val database = newDatabase()
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)

        projectStore.update(Project(id = "p1", name = "Swept away", updatedAt = now))

        assertEquals(listOf("p1"), projectStore.getAll().map { it.id })
    }

    /**
     * A pull pages the two tables separately and realtime delivers one row at a time, so a task
     * can perfectly well arrive before the project it names. That used to raise
     * SQLITE_CONSTRAINT_FOREIGNKEY and fail the entire round — every row in the page with it —
     * until the project happened to come back in the same page as the task.
     */
    @Test
    fun `merging a task whose project has not arrived yet stores it`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)

        backupStore.mergeAll(
            projects = emptyList(),
            sections = emptyList(),
            tags = emptyList(),
            tasks =listOf(Task(id = "t1", title = "Book flights", projectId = "p1", createdAt = now, updatedAt = now)),
            revivedAt = now,
        )
        assertEquals("p1", taskStore.byId("t1")?.projectId)

        // …and the project arriving later joins up with it rather than leaving it in the Inbox.
        backupStore.mergeAll(
            projects = listOf(Project(id = "p1", name = "Rome", updatedAt = now)),
            sections = emptyList(),
            tags = emptyList(),
            tasks =emptyList(),
            revivedAt = now,
        )
        assertEquals(listOf("t1"), projectStore.taskIdsIn("p1"))
    }

    /**
     * The bug as it was reported: a project is deleted, one with the same name is created in its
     * place, and importing the very file the projects came from makes the new one — and everything
     * under it — disappear.
     *
     * Nothing here turns on the *name*. A re-import hands the merge a project row it already has,
     * newer than the stored one, and the replace-then-insert cascaded down every subproject in the
     * same transaction, including the ones the same merge had just written.
     */
    @Test
    fun `re-importing a backup keeps the subprojects it just wrote`() = runTest {
        val database = newDatabase()
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        val parent = Project(id = "p1", name = "Todoist import", updatedAt = now)
        // The child first, the way a file lists whatever order it was written in.
        val child = Project(id = "p2", name = "Errands", parentId = "p1", updatedAt = now)

        backupStore.mergeAll(
            projects = listOf(child, parent),
            sections = emptyList(),
            tags = emptyList(),
            tasks = emptyList(),
            revivedAt = now,
        )
        backupStore.mergeAll(
            projects = listOf(child.copy(updatedAt = later), parent.copy(updatedAt = later)),
            sections = emptyList(),
            tags = emptyList(),
            tasks =emptyList(),
            revivedAt = now,
        )

        assertEquals(listOf("p1", "p2"), projectStore.getAll().map { it.id }.sorted())
    }
}
