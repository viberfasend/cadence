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

/** Add indexes and foreign key constraints for performance and data integrity. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create temporary tables
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS projects_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                colorHex TEXT NOT NULL,
                parentId INTEGER,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(parentId) REFERENCES projects_new(id) ON DELETE CASCADE
            )
        """)
        
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tasks_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                notes TEXT,
                priority INTEGER NOT NULL DEFAULT 1,
                projectId INTEGER,
                parentId INTEGER,
                dueDate INTEGER,
                dueTime INTEGER,
                reminderTime INTEGER,
                completedAt INTEGER,
                createdAt INTEGER NOT NULL DEFAULT 0,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                recurrence TEXT,
                FOREIGN KEY(projectId) REFERENCES projects_new(id) ON DELETE SET NULL,
                FOREIGN KEY(parentId) REFERENCES tasks_new(id) ON DELETE CASCADE
            )
        """)
        
        // Create indexes
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_parent ON projects_new(parentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_sort ON projects_new(sortOrder)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_project ON tasks_new(projectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks_new(parentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks_new(dueDate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_completed ON tasks_new(completedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_sort ON tasks_new(sortOrder)")
        
        // Copy data from old tables
        db.execSQL("INSERT INTO projects_new (id, name, colorHex, parentId, sortOrder) SELECT id, name, colorHex, parentId, sortOrder FROM projects")
        db.execSQL("INSERT INTO tasks_new (id, title, notes, priority, projectId, parentId, dueDate, dueTime, reminderTime, completedAt, createdAt, sortOrder, recurrence) SELECT id, title, notes, priority, projectId, parentId, dueDate, dueTime, reminderTime, completedAt, createdAt, sortOrder, recurrence FROM tasks")
        
        // Drop old tables
        db.execSQL("DROP TABLE projects")
        db.execSQL("DROP TABLE tasks")
        
        // Rename new tables
        db.execSQL("ALTER TABLE projects_new RENAME TO projects")
        db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")
    }
}

/**
 * Recurrence chain: `tasks.spawnedFromId` names the occurrence whose completion inserted a row.
 *
 * Added with `ALTER TABLE`, and without a `REFERENCES` clause on purpose — a self-referencing
 * foreign key here would have to be matched exactly by the schema Room validates on open, and the
 * link is allowed to go stale anyway.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN spawnedFromId INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_spawned_from ON tasks(spawnedFromId)")
    }
}

@Database(
    entities = [TaskEntity::class, ProjectEntity::class],
    version = 4,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
