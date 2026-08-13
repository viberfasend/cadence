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
        CadenceDatabase(first).taskQueries.insertIfAbsent(
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
        CadenceDatabase(setup).taskQueries.insertIfAbsent(
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

    /**
     * The 2 → 3 migration, which is the one that rewrites tables rather than adding one.
     *
     * Two things have to survive it and one has to stop being true: every row stays, every
     * attachment stays — `DROP TABLE taskRow` with foreign keys still enabled would have taken
     * them all — and a task may afterwards name a project this device has not pulled yet, which
     * used to fail the whole merge with SQLITE_CONSTRAINT_FOREIGNKEY.
     */
    @Test
    fun `the 2 to 3 migration keeps every row and drops the task-project constraints`() {
        val setup = DatabaseDriverFactory(folder.root).createDriver()
        setup.execute(null, "DROP TABLE taskRow", 0)
        setup.execute(null, "DROP TABLE projectRow", 0)
        setup.execute(null, "DROP TABLE attachmentRow", 0)
        VERSION_2_TABLES.forEach { setup.execute(null, it, 0) }
        setup.execute(
            null,
            "INSERT INTO projectRow(id, name, colorHex, parentId, sortOrder, updatedAt, deletedAt) " +
                "VALUES ('p1', 'Home', '#FF0000', NULL, 0, 1, NULL), " +
                "('p2', 'Finance', '#00FF00', 'p1', 1, 1, NULL)",
            0,
        )
        setup.execute(
            null,
            "INSERT INTO taskRow(id, title, priority, projectId, createdAt, sortOrder, updatedAt) " +
                "VALUES ('t1', 'Water the plants', 2, 'p1', 0, 0, 1)",
            0,
        )
        setup.execute(
            null,
            "INSERT INTO attachmentRow(id, taskId, kind, name, mimeType, sizeBytes, createdAt, sortOrder) " +
                "VALUES ('a1', 't1', 'LINK', 'Guide', 'text/uri-list', 0, 0, 0)",
            0,
        )
        setup.execute(null, "PRAGMA user_version = 2", 0)
        setup.close()

        val migrated = DatabaseDriverFactory(folder.root).createDriver()

        val database = CadenceDatabase(migrated)
        assertEquals(CadenceDatabase.Schema.version, migrated.userVersion())
        assertEquals(listOf("p1", "p2"), database.projectQueries.selectAll(::toProject).executeAsList().map { it.id })
        assertEquals("p1", database.taskQueries.selectAll(::toTask).executeAsOne().projectId)
        assertEquals(1, database.attachmentQueries.selectAll { _, _, _, _, _, _, _, _, _, _ -> Unit }.executeAsList().size)
        // The row a pull can now deliver before the project it names.
        database.taskQueries.insertIfAbsent(
            id = "t2", title = "Not pulled yet", notes = null, priority = 2, projectId = "ghost",
            parentId = null, spawnedFromId = null, dueDate = null, dueTime = null,
            reminderTime = null, completedAt = null, createdAt = 0, sortOrder = 0,
            recurrence = null, updatedAt = 1, deletedAt = null,
        )
        assertEquals(2, database.taskQueries.selectAll(::toTask).executeAsList().size)
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

    private companion object {
        /** The three tables as version 2 declared them, foreign keys and all — what a shipped
         *  install has on disk before `2.sqm` runs. */
        val VERSION_2_TABLES = listOf(
            """
            CREATE TABLE projectRow (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                colorHex TEXT NOT NULL,
                parentId TEXT REFERENCES projectRow(id) ON DELETE CASCADE,
                sortOrder INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE taskRow (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                notes TEXT,
                priority INTEGER NOT NULL,
                projectId TEXT REFERENCES projectRow(id) ON DELETE SET NULL,
                parentId TEXT REFERENCES taskRow(id) ON DELETE CASCADE,
                spawnedFromId TEXT,
                dueDate INTEGER,
                dueTime INTEGER,
                reminderTime INTEGER,
                completedAt INTEGER,
                createdAt INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                recurrence TEXT,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE attachmentRow (
                id TEXT NOT NULL PRIMARY KEY,
                taskId TEXT NOT NULL REFERENCES taskRow(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                sha256 TEXT,
                sizeBytes INTEGER NOT NULL,
                url TEXT,
                createdAt INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX idx_task_project ON taskRow(projectId)",
            "CREATE INDEX idx_task_parent ON taskRow(parentId)",
            "CREATE INDEX idx_task_spawned_from ON taskRow(spawnedFromId)",
            "CREATE INDEX idx_task_due ON taskRow(dueDate)",
            "CREATE INDEX idx_task_completed ON taskRow(completedAt)",
            "CREATE INDEX idx_task_sort ON taskRow(sortOrder)",
            "CREATE INDEX idx_project_parent ON projectRow(parentId)",
            "CREATE INDEX idx_project_sort ON projectRow(sortOrder)",
            "CREATE INDEX idx_attachment_task ON attachmentRow(taskId)",
            "CREATE INDEX idx_attachment_sha ON attachmentRow(sha256)",
        )
    }
}
