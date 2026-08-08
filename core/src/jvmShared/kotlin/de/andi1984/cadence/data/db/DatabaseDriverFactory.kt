package de.andi1984.cadence.data.db

import app.cash.sqldelight.db.SqlDriver

/** The on-disk database file name, shared by both platform driver factories. */
const val CADENCE_DATABASE_FILE_NAME = "cadence.db"

/**
 * The only thing that differs between Android and the desktop JVM about opening the database:
 * `AndroidSqliteDriver` needs a `Context`, `JdbcSqliteDriver` needs a file path. Everything else
 * — schema, queries, the store implementations — is the plain Kotlin `TaskQueries`/`ProjectQueries`
 * SQLDelight generates, unchanged across platforms.
 */
expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
