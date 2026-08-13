package de.andi1984.cadence.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/** [dataDir] is the desktop app's per-OS data directory (ADR 0001, decision 8); this factory
 *  only appends the database file name and does not create the directory itself. */
actual class DatabaseDriverFactory(private val dataDir: File) {
    actual fun createDriver(): SqlDriver {
        val databaseFile = File(dataDir, CADENCE_DATABASE_FILE_NAME)
        val isFreshDatabase = !databaseFile.exists()
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$databaseFile")

        // Unlike AndroidSqliteDriver, JdbcSqliteDriver has no onCreate/onUpgrade lifecycle: it
        // opens the file and nothing else. The version has to be tracked by hand, and SQLite's
        // own `user_version` pragma is the field made for it.
        val target = CadenceDatabase.Schema.version
        if (isFreshDatabase) {
            CadenceDatabase.Schema.create(driver)
            driver.setUserVersion(target)
            return driver.withForeignKeys()
        }

        // Every desktop install written before this existed has the version-1 tables and a
        // `user_version` still at its default 0, because nothing ever set it. Zero therefore
        // means "version 1, from before we counted", not "empty file" — the file existing is
        // what rules the second reading out.
        val current = driver.userVersion().takeIf { it > 0 } ?: 1L
        if (current < target) {
            CadenceDatabase.Schema.migrate(driver, current, target)
            driver.setUserVersion(target)
        }
        return driver.withForeignKeys()
    }

    /**
     * Foreign keys, switched on only once the schema is where it should be.
     *
     * Deliberately after `migrate`, never before: `2.sqm` rebuilds `taskRow` to drop constraints
     * SQLite cannot ALTER away, and `DROP TABLE taskRow` with foreign keys enabled would cascade
     * into `attachmentRow` and take every attachment with it. Android gets this for free — its
     * callback enables them in `onOpen`, which runs after `onUpgrade` — so this is the desktop
     * catching up with what that driver already does.
     */
    private fun SqlDriver.withForeignKeys(): SqlDriver = apply {
        execute(null, "PRAGMA foreign_keys = ON", 0)
    }

    // Both helpers are members rather than top-level extensions: this file and the `expect`
    // declaration in jvmShared share a name, so a file-level function here would collide with
    // that one's facade class.

    private fun SqlDriver.userVersion(): Long =
        executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            },
            parameters = 0,
        ).value

    /** `PRAGMA user_version` takes no bind parameters — the value has to be spliced into the
     *  SQL. It is a Long this class computed, so there is nothing for an injection to reach. */
    private fun SqlDriver.setUserVersion(version: Long) {
        execute(null, "PRAGMA user_version = $version", 0)
    }
}
