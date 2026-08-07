package de.andi1984.cadence.data

import de.andi1984.cadence.domain.backup.BackupSnapshot
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Maximum number of subtasks allowed per parent task. */
const val MAX_SUBTASKS_PER_PARENT = 100

/** Maximum depth of project nesting allowed. */
const val MAX_PROJECT_NESTING_DEPTH = 5

/** Result of a repository operation that can fail. */
sealed class RepositoryResult<out T> {
    data class Success<T>(val data: T) : RepositoryResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : RepositoryResult<Nothing>()
    object ValidationError : RepositoryResult<Nothing>()
}

class CadenceRepository(
    private val taskStore: TaskStore,
    private val projectStore: ProjectStore,
    private val backupStore: BackupStore,
) {

    val tasks: Flow<List<Task>> = taskStore.observeAll()

    val projects: Flow<List<Project>> = projectStore.observeAll()

    fun task(id: Long): Flow<Task?> = taskStore.observeById(id)

    suspend fun upsertTask(task: Task): Long {
        val stamped = if (task.createdAt == Instant.EPOCH) {
            task.copy(createdAt = Instant.now())
        } else {
            task
        }
        return if (stamped.id == 0L) {
            taskStore.insert(stamped)
        } else {
            taskStore.update(stamped)
            stamped.id
        }
    }

    suspend fun deleteTask(id: Long) = taskStore.deleteWithSubtasks(id)

    suspend fun subtasksOf(parentId: Long): List<Task> = taskStore.subtasksOf(parentId)

    /**
     * Adds a step under [parent].
     *
     * Nesting stays one level deep: adding a subtask while looking at a subtask files the new
     * one next to it, under the same task, rather than starting a third level.
     */
    suspend fun addSubtask(parent: Task, title: String): RepositoryResult<Long> {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            return RepositoryResult.ValidationError
        }

        // Nesting stops after one level: adding a subtask while looking at a subtask files the
        // new one under the same grandparent, next to the one being viewed.
        val parentId = parent.parentId ?: parent.id

        // Check if we would exceed the maximum subtask limit
        val currentSubtaskCount = taskStore.subtasksOf(parentId).size
        if (currentSubtaskCount >= MAX_SUBTASKS_PER_PARENT) {
            return RepositoryResult.Error("Maximum subtask limit ($MAX_SUBTASKS_PER_PARENT) reached for this parent")
        }

        return try {
            val newTaskId = taskStore.insert(
                Task(
                    title = trimmed,
                    projectId = parent.projectId,
                    parentId = parentId,
                    createdAt = Instant.now(),
                    sortOrder = currentSubtaskCount,
                ),
            )
            RepositoryResult.Success(newTaskId)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to add subtask", e)
        }
    }

    /** A task and its steps live in the same project, so moving one moves the whole checklist. */
    suspend fun moveToProject(task: Task, projectId: Long?) {
        taskStore.update(task.copy(projectId = projectId))
        taskStore.subtasksOf(task.id).forEach { taskStore.update(it.copy(projectId = projectId)) }
    }

    /**
     * Completing a recurring task keeps the finished instance in place — so it stays visible
     * in Today with its "Done 09:12" line — and inserts the next instance.
     *
     * Finishing a task also finishes whatever is still open beneath it, and a recurring task
     * hands its checklist to the next occurrence unticked, with the subtask dates moved by the
     * same span as the parent's.
     *
     * Completing is idempotent, which matters precisely because it has that side effect: [task]
     * is a snapshot the UI drew a row from, and the same open snapshot is handed back by every
     * tap that lands before the row re-composes. Closing the row is therefore left to
     * [TaskStore.completeIfOpen], and only the call that actually closed it goes on to schedule the
     * successor — otherwise two taps on one checkbox left two identical occurrences behind.
     *
     * Reopening undoes both halves: the row opens again *and* the occurrence its completion
     * inserted is removed, so the task does not end up standing in the list twice.
     *
     * @return the ids of the tasks this call deleted, so their reminders can be cancelled —
     *   [de.andi1984.cadence.reminders.ReminderScheduler.sync] only ever sees the tasks that
     *   still exist, so it cannot cancel an alarm for one that is already gone.
     */
    suspend fun setCompleted(
        task: Task,
        completed: Boolean,
        today: LocalDate = LocalDate.now(),
    ): List<Long> {
        if (!completed) {
            if (taskStore.reopenIfDone(task.id) == 0) return emptyList()
            val successors = taskStore.openSuccessorsOf(task.id)
            successors.forEach { taskStore.deleteWithSubtasks(it) }
            return successors
        }
        val now = Instant.now()
        if (taskStore.completeIfOpen(task.id, now) == 0) return emptyList()

        // Read the row back rather than trust the snapshot: the rule, the due date and the
        // project may have been edited since the row was drawn, and the next occurrence
        // inherits all of them.
        val current = taskStore.byId(task.id) ?: return emptyList()

        val subtasks = subtasksOf(current.id)
        subtasks.filter { !it.isDone }.forEach { taskStore.update(it.copy(completedAt = now)) }

        val rule = current.recurrence ?: return emptyList()
        val nextDue = RecurrenceEngine.dueDateAfterCompletion(rule, current.dueDate, today)
        val nextId = taskStore.insert(
            current.copy(
                id = 0L,
                completedAt = null,
                dueDate = nextDue,
                createdAt = now,
                spawnedFromId = current.id,
            ),
        )
        val shift = current.dueDate?.let { ChronoUnit.DAYS.between(it, nextDue) } ?: 0L
        subtasks.forEach { subtask ->
            taskStore.insert(
                subtask.copy(
                    id = 0L,
                    parentId = nextId,
                    completedAt = null,
                    dueDate = subtask.dueDate?.plusDays(shift),
                    createdAt = now,
                    // The step belongs to the new occurrence, which already carries the link
                    // back; only the parent rows form the chain.
                    spawnedFromId = null,
                ),
            )
        }
        return emptyList()
    }

    /** Moves a task's due date by [days], used by snooze and the overdue triage action. */
    suspend fun shiftDueDate(task: Task, days: Long, from: LocalDate = LocalDate.now()) {
        val base = task.dueDate?.takeIf { it.isAfter(from) } ?: from
        taskStore.update(task.copy(dueDate = base.plusDays(days)))
    }

    suspend fun setDueDate(task: Task, dueDate: LocalDate?) {
        taskStore.update(task.copy(dueDate = dueDate))
    }

    /** "Reschedule all" on the overdue block: everything overdue lands on today. */
    suspend fun rescheduleOverdueToToday(today: LocalDate = LocalDate.now()) {
        taskStore.getAll()
            .filter { it.isOverdue(today) }
            .forEach { taskStore.update(it.copy(dueDate = today)) }
    }

    /**
     * Validates and upserts a project, checking for circular references and maximum nesting depth.
     */
    suspend fun upsertProject(project: Project): RepositoryResult<Long> {
        // Validate project name
        if (project.name.isBlank()) {
            return RepositoryResult.ValidationError
        }
        
        // Validate nesting depth
        val parentId = project.parentId
        if (parentId != null) {
            val nestingDepth = getProjectNestingDepth(parentId)
            if (nestingDepth >= MAX_PROJECT_NESTING_DEPTH) {
                return RepositoryResult.Error("Maximum project nesting depth ($MAX_PROJECT_NESTING_DEPTH) reached")
            }
            
            // Prevent circular references
            if (wouldCreateCircularReference(project.id, project.parentId)) {
                return RepositoryResult.Error("Cannot create circular reference: project cannot be its own ancestor")
            }
        }
        
        return try {
            val id = if (project.id == 0L) {
                projectStore.insert(project)
            } else {
                projectStore.update(project)
                project.id
            }
            RepositoryResult.Success(id)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save project", e)
        }
    }

    /**
     * Calculates the nesting depth of a project by counting how many levels deep it is.
     */
    private suspend fun getProjectNestingDepth(projectId: Long): Int {
        var depth = 0
        var currentId: Long? = projectId
        
        while (currentId != null && currentId != 0L) {
            val project = projectStore.getAll().firstOrNull { it.id == currentId } ?: break
            currentId = project.parentId
            depth++
        }
        
        return depth
    }

    /**
     * Checks if setting parentId for a project would create a circular reference.
     */
    private suspend fun wouldCreateCircularReference(projectId: Long, newParentId: Long?): Boolean {
        if (newParentId == null || newParentId == 0L) return false
        
        var currentId: Long? = newParentId
        while (currentId != null && currentId != 0L) {
            if (currentId == projectId) {
                return true // Circular reference detected
            }
            val project = projectStore.getAll().firstOrNull { it.id == currentId } ?: break
            currentId = project.parentId
        }
        
        return false
    }

    /**
     * Removes a project and its subprojects. Their tasks either go with it or fall back to the
     * Inbox — a project is a folder, not a container that owns the work.
     *
     * Returns the ids of the tasks that were deleted, so reminders for them can be cancelled —
     * the scheduler's sync only ever sees the tasks that still exist.
     */
    suspend fun deleteProject(id: Long, deleteTasks: Boolean = false): List<Long> {
        val affected = if (deleteTasks) projectStore.taskIdsIn(id) else emptyList()
        projectStore.deleteWithChildren(id, deleteTasks)
        return affected
    }

    // ── Backup ─────────────────────────────────────────────────────────────────────

    /** One-shot read of everything, for an export. */
    suspend fun snapshot(): BackupSnapshot = BackupSnapshot(
        projects = projectStore.getAll(),
        tasks = taskStore.getAll(),
    )

    /**
     * Restores a backup by *replacing* both tables — importing is not a merge, so ids stay the
     * ones in the file and task→project links survive without remapping.
     */
    suspend fun restore(snapshot: BackupSnapshot) = backupStore.replaceAll(
        projects = snapshot.projects,
        tasks = snapshot.tasks,
    )
}

