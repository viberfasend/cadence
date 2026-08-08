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

        projectStore.deleteWithChildren("p1", deleteTasks = false)

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

        projectStore.deleteWithChildren("p1", deleteTasks = true)

        assertNull(taskStore.byId("t1"))
    }

    @Test
    fun `restoring a backup replaces every task and project`() = runTest {
        val database = newDatabase()
        val taskStore = SqlDelightTaskStore(database, Dispatchers.Unconfined)
        val projectStore = SqlDelightProjectStore(database, Dispatchers.Unconfined)
        val backupStore = SqlDelightBackupStore(database, Dispatchers.Unconfined)
        taskStore.insert(Task(id = "stale", title = "Old task", createdAt = now, updatedAt = now))

        backupStore.replaceAll(
            projects = listOf(Project(id = "p1", name = "Home", updatedAt = now)),
            tasks = listOf(Task(id = "t1", title = "Fresh task", projectId = "p1", createdAt = now, updatedAt = now)),
        )

        assertNull(taskStore.byId("stale"))
        assertEquals("Fresh task", taskStore.byId("t1")?.title)
        assertEquals(listOf("p1"), projectStore.getAll().map { it.id })
    }
}
