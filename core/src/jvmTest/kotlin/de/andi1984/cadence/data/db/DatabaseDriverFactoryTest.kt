package de.andi1984.cadence.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop's schema versioning, which is hand-rolled because `JdbcSqliteDriver` has no
 * onCreate/onUpgrade lifecycle of its own — the one place a migration can go wrong without any
 * other test noticing, since Android's driver runs migrations from its callback.
 *
 * JVM-only on purpose: this is `jvmMain`'s `actual`, and the Android one is a different class.
 */
class DatabaseDriverFactoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a fresh database is created at the current version`() {
        val driver = DatabaseDriverFactory(folder.root).createDriver()

        assertEquals(CadenceDatabase.Schema.version, driver.userVersion())
        // The sync row is inserted with the table, so every read may assume it is there.
        assertEquals(1, CadenceDatabase(driver).syncStateQueries.select().executeAsList().size)
        driver.close()
    }

    @Test
    fun `opening an existing database again neither re-creates nor re-migrates it`() {
        val first = DatabaseDriverFactory(folder.root).createDriver()
        CadenceDatabase(first).taskQueries.insertOrReplace(
            id = "t1", title = "Survives", notes = null, priority = 2, projectId = null,
            parentId = null, spawnedFromId = null, dueDate = null, dueTime = null,
            reminderTime = null, completedAt = null, createdAt = 0, sortOrder = 0,
            recurrence = null, updatedAt = 0, deletedAt = null,
        )
        first.close()

        val second = DatabaseDriverFactory(folder.root).createDriver()

        assertEquals("Survives", CadenceDatabase(second).taskQueries.selectAll(::toTask).executeAsOne().title)
        second.close()
    }

    /**
     * The case that actually ships: a desktop install written before any of this existed has the
     * version-1 tables and a `user_version` still at 0, because nothing ever set it. Zero has to
     * read as "version 1, from before we counted" — treating it as an empty file would run
     * `Schema.create` against tables that already exist and fail at launch.
     */
    @Test
    fun `a version-1 install from before user_version was tracked is migrated, not re-created`() {
        val setup = DatabaseDriverFactory(folder.root).createDriver()
        CadenceDatabase(setup).taskQueries.insertOrReplace(
            id = "t1", title = "Older than sync", notes = null, priority = 2, projectId = null,
            parentId = null, spawnedFromId = null, dueDate = null, dueTime = null,
            reminderTime = null, completedAt = null, createdAt = 0, sortOrder = 0,
            recurrence = null, updatedAt = 0, deletedAt = null,
        )
        // Wind the file back to what a phase-1 install looks like on disk.
        setup.execute(null, "DROP TABLE syncStateRow", 0)
        setup.execute(null, "PRAGMA user_version = 0", 0)
        setup.close()

        val migrated = DatabaseDriverFactory(folder.root).createDriver()

        val database = CadenceDatabase(migrated)
        assertEquals(CadenceDatabase.Schema.version, migrated.userVersion())
        assertEquals("Older than sync", database.taskQueries.selectAll(::toTask).executeAsOne().title)
        assertTrue(database.syncStateQueries.select().executeAsOneOrNull() != null)
        migrated.close()
    }

    private fun app.cash.sqldelight.db.SqlDriver.userVersion(): Long =
        executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L,
                )
            },
            parameters = 0,
        ).value
}
