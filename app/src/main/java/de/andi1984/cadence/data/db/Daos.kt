package de.andi1984.cadence.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<TaskEntity>)

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY sortOrder")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY sortOrder")
    suspend fun getAll(): List<ProjectEntity>

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(projects: List<ProjectEntity>)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id OR parentId = :id")
    suspend fun deleteWithChildren(id: Long)
}

/**
 * Restoring a backup swaps both tables at once. An abstract class, not an interface, because
 * Room only implements `@Transaction` around a method it can see the body of.
 */
@Dao
abstract class BackupDao {

    @Query("DELETE FROM tasks")
    protected abstract suspend fun deleteAllTasks()

    @Query("DELETE FROM projects")
    protected abstract suspend fun deleteAllProjects()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertProjects(projects: List<ProjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTasks(tasks: List<TaskEntity>)

    /** All or nothing: a failed restore must not leave the app half-empty. */
    @Transaction
    open suspend fun replaceAll(projects: List<ProjectEntity>, tasks: List<TaskEntity>) {
        deleteAllTasks()
        deleteAllProjects()
        insertProjects(projects)
        insertTasks(tasks)
    }
}
