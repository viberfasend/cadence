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

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY sortOrder, id")
    suspend fun subtasksOf(parentId: Long): List<TaskEntity>

    /**
     * Closes a task only if it is still open, and reports whether this call is the one that did it.
     *
     * The check has to happen in SQL rather than against the caller's [TaskEntity], because that
     * is a snapshot: a checkbox tapped twice before its row re-composes hands out the same open
     * task twice. Completing a recurring task inserts its next occurrence, so a completion that
     * runs twice leaves a duplicate behind — this is the guard that makes it run once.
     *
     * @return 1 when the row was open and is now done, 0 when it was already done or is gone.
     */
    @Query("UPDATE tasks SET completedAt = :completedAt WHERE id = :id AND completedAt IS NULL")
    suspend fun completeIfOpen(id: Long, completedAt: Long): Int

    /** The mirror of [completeIfOpen]: reopens a done task, and reports 0 if it was open. */
    @Query("UPDATE tasks SET completedAt = NULL WHERE id = :id AND completedAt IS NOT NULL")
    suspend fun reopenIfDone(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    /** Deleting a task takes its subtasks with it — a step without its task has no meaning. */
    @Query("DELETE FROM tasks WHERE id = :id OR parentId = :id")
    suspend fun deleteWithSubtasks(id: Long)
}

/**
 * An abstract class, not an interface, so deleting a project can move or remove its tasks in the
 * same transaction as the project rows themselves.
 */
@Dao
abstract class ProjectDao {

    @Query("SELECT * FROM projects ORDER BY sortOrder")
    abstract fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY sortOrder")
    abstract suspend fun getAll(): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(project: ProjectEntity): Long

    @Update
    abstract suspend fun update(project: ProjectEntity)

    /** Everything filed under the project, including its subprojects — nesting is one level. */
    @Query(
        "SELECT id FROM tasks " +
            "WHERE projectId = :id OR projectId IN (SELECT id FROM projects WHERE parentId = :id)",
    )
    abstract suspend fun taskIdsIn(id: Long): List<Long>

    @Query(
        "UPDATE tasks SET projectId = NULL " +
            "WHERE projectId = :id OR projectId IN (SELECT id FROM projects WHERE parentId = :id)",
    )
    protected abstract suspend fun moveTasksToInbox(id: Long)

    @Query(
        "DELETE FROM tasks " +
            "WHERE projectId = :id OR projectId IN (SELECT id FROM projects WHERE parentId = :id)",
    )
    protected abstract suspend fun deleteTasksIn(id: Long)

    @Query("DELETE FROM projects WHERE id = :id OR parentId = :id")
    protected abstract suspend fun deleteRows(id: Long)

    /** The tasks are dealt with first, while the subproject rows still name their parent. */
    @Transaction
    open suspend fun deleteWithChildren(id: Long, deleteTasks: Boolean) {
        if (deleteTasks) deleteTasksIn(id) else moveTasksToInbox(id)
        deleteRows(id)
    }
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
