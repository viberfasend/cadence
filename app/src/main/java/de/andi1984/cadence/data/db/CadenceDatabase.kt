package de.andi1984.cadence.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskEntity::class, ProjectEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CadenceDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var instance: CadenceDatabase? = null

        fun get(context: Context): CadenceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CadenceDatabase::class.java,
                "cadence.db",
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
