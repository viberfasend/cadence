package de.andi1984.cadence.data

import de.andi1984.cadence.data.db.BackupDao
import de.andi1984.cadence.data.db.ProjectDao
import de.andi1984.cadence.data.db.TaskDao
import de.andi1984.cadence.data.db.toDomain
import de.andi1984.cadence.data.db.toEntity
import de.andi1984.cadence.domain.backup.BackupSnapshot
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

class CadenceRepository(
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val backupDao: BackupDao,
) {

    val tasks: Flow<List<Task>> = taskDao.observeAll().map { list -> list.map { it.toDomain() } }

    val projects: Flow<List<Project>> =
        projectDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun task(id: Long): Flow<Task?> = taskDao.observeById(id).map { it?.toDomain() }

    suspend fun upsertTask(task: Task): Long {
        val stamped = if (task.createdAt == Instant.EPOCH) {
            task.copy(createdAt = Instant.now())
        } else {
            task
        }
        return if (stamped.id == 0L) {
            taskDao.insert(stamped.toEntity())
        } else {
            taskDao.update(stamped.toEntity())
            stamped.id
        }
    }

    suspend fun deleteTask(id: Long) = taskDao.deleteById(id)

    /**
     * Completing a recurring task keeps the finished instance in place — so it stays visible
     * in Today with its "Done 09:12" line — and inserts the next instance.
     */
    suspend fun setCompleted(task: Task, completed: Boolean, today: LocalDate = LocalDate.now()) {
        if (!completed) {
            taskDao.update(task.copy(completedAt = null).toEntity())
            return
        }
        val now = Instant.now()
        taskDao.update(task.copy(completedAt = now).toEntity())
        val rule = task.recurrence ?: return
        val nextDue = RecurrenceEngine.dueDateAfterCompletion(rule, task.dueDate, today)
        taskDao.insert(
            task.copy(
                id = 0L,
                completedAt = null,
                dueDate = nextDue,
                createdAt = now,
            ).toEntity(),
        )
    }

    /** Moves a task's due date by [days], used by snooze and the overdue triage action. */
    suspend fun shiftDueDate(task: Task, days: Long, from: LocalDate = LocalDate.now()) {
        val base = task.dueDate?.takeIf { it.isAfter(from) } ?: from
        taskDao.update(task.copy(dueDate = base.plusDays(days)).toEntity())
    }

    suspend fun setDueDate(task: Task, dueDate: LocalDate?) {
        taskDao.update(task.copy(dueDate = dueDate).toEntity())
    }

    /** "Reschedule all" on the overdue block: everything overdue lands on today. */
    suspend fun rescheduleOverdueToToday(today: LocalDate = LocalDate.now()) {
        taskDao.getAll()
            .map { it.toDomain() }
            .filter { it.isOverdue(today) }
            .forEach { taskDao.update(it.copy(dueDate = today).toEntity()) }
    }

    suspend fun upsertProject(project: Project): Long =
        if (project.id == 0L) {
            projectDao.insert(project.toEntity())
        } else {
            projectDao.update(project.toEntity())
            project.id
        }

    /**
     * Removes a project and its subprojects. Their tasks either go with it or fall back to the
     * Inbox — a project is a folder, not a container that owns the work.
     *
     * Returns the ids of the tasks that were deleted, so reminders for them can be cancelled —
     * the scheduler's sync only ever sees the tasks that still exist.
     */
    suspend fun deleteProject(id: Long, deleteTasks: Boolean = false): List<Long> {
        val affected = if (deleteTasks) projectDao.taskIdsIn(id) else emptyList()
        projectDao.deleteWithChildren(id, deleteTasks)
        return affected
    }

    // ── Backup ─────────────────────────────────────────────────────────────────────

    /** One-shot read of everything, for an export. */
    suspend fun snapshot(): BackupSnapshot = BackupSnapshot(
        projects = projectDao.getAll().map { it.toDomain() },
        tasks = taskDao.getAll().map { it.toDomain() },
    )

    /**
     * Restores a backup by *replacing* both tables — importing is not a merge, so ids stay the
     * ones in the file and task→project links survive without remapping.
     */
    suspend fun restore(snapshot: BackupSnapshot) = backupDao.replaceAll(
        projects = snapshot.projects.map { it.toEntity() },
        tasks = snapshot.tasks.map { it.toEntity() },
    )
}
