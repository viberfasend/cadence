package de.andi1984.cadence.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver = AndroidSqliteDriver(
        schema = CadenceDatabase.Schema,
        context = context,
        name = CADENCE_DATABASE_FILE_NAME,
        callback = object : AndroidSqliteDriver.Callback(CadenceDatabase.Schema) {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // Declared FKs (parentId cascades, projectId sets null on delete) are a backstop,
                // never relied on — every delete path removes the rows it must explicitly.
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    )
}
