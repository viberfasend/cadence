package de.andi1984.cadence.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [CadenceCore] is the graph both shells build the same way: a driver they opened themselves
 * (platform-specific — `DatabaseDriverFactory` takes a `Context` on Android, a `File` here) and a
 * data directory, handed over so the database, the blob store, the repository and the sync
 * engine come back wired together. This is the one test that exercises the graph as a whole
 * rather than any one piece of it — an in-memory driver opened the way both `DatabaseDriverFactory`s
 * do (schema created, foreign keys on), a write through [CadenceCore.repository], and the row
 * read back before [CadenceCore.close] tears the whole thing down.
 */
class CadenceCoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `builds a working repository over the driver it is handed, and closes cleanly`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CadenceDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)

        val core = CadenceCore(
            driver = driver,
            dataDir = folder.root,
            ioDispatcher = Dispatchers.Unconfined,
        )

        runBlocking {
            val id = core.repository.upsertTask(Task(title = "Water the plants"))
            val tasks = core.repository.tasks.first()

            assertEquals(1, tasks.size)
            assertEquals("Water the plants", tasks.single().title)
            assertEquals(id, tasks.single().id)
            assertTrue(id.isNotBlank())
        }

        // Tears down the application scope and the driver without throwing — a second close, or
        // a repository call after it, is not something either shell does and is not asserted.
        core.close()
    }
}
