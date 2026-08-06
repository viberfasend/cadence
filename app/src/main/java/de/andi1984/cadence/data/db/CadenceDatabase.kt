package de.andi1984.cadence.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Subtasks: `tasks.parentId` points at the task a row is a step of. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN parentId INTEGER")
    }
}

@Database(
    entities = [TaskEntity::class, ProjectEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class CadenceDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    abstract fun projectDao(): ProjectDao

    abstract fun backupDao(): BackupDao

    companion object {
        @Volatile
        private var instance: CadenceDatabase? = null

        fun get(context: Context): CadenceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CadenceDatabase::class.java,
                "cadence.db",
            )
                // Real migrations, no destructive fallback: an upgrade must not empty the app.
                // Every future entity change needs its own Migration here.
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
