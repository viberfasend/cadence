package de.andi1984.cadence.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/** [dataDir] is the desktop app's per-OS data directory (ADR 0001, decision 8); this factory
 *  only appends the database file name and does not create the directory itself. */
actual class DatabaseDriverFactory(private val dataDir: File) {
    actual fun createDriver(): SqlDriver {
        val databaseFile = File(dataDir, CADENCE_DATABASE_FILE_NAME)
        // Unlike AndroidSqliteDriver, JdbcSqliteDriver has no onCreate lifecycle of its own —
        // running Schema.create against a file that already has the tables would fail outright.
        // There is no migration chain to run either: phase 2 re-declares the schema from
        // scratch at version 1 (ADR 0001, decision 7), so "the file did not exist yet" is the
        // whole condition until a future schema version needs Schema.migrate.
        val isFreshDatabase = !databaseFile.exists()
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$databaseFile")
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        if (isFreshDatabase) {
            CadenceDatabase.Schema.create(driver)
        }
        return driver
    }
}
