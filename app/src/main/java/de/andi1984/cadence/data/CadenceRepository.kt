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

    suspend fun deleteTask(id: Long) = taskDao.deleteWithSubtasks(id)

    suspend fun subtasksOf(parentId: Long): List<Task> =
        taskDao.subtasksOf(parentId).map { it.toDomain() }

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
        
        // Prevent circular references - a task cannot be its own parent
        val parentId = parent.parentId ?: parent.id
        if (parentId == parent.id) {
            return RepositoryResult.Error("Cannot create circular reference: task cannot be its own parent")
        }
        
        // Check if we would exceed the maximum subtask limit
        val currentSubtaskCount = taskDao.subtasksOf(parentId).size
        if (currentSubtaskCount >= MAX_SUBTASKS_PER_PARENT) {
            return RepositoryResult.Error("Maximum subtask limit ($MAX_SUBTASKS_PER_PARENT) reached for this parent")
        }
        
        return try {
            val newTaskId = taskDao.insert(
                Task(
                    title = trimmed,
                    projectId = parent.projectId,
                    parentId = parentId,
                    createdAt = Instant.now(),
                    sortOrder = currentSubtaskCount,
                ).toEntity(),
            )
            RepositoryResult.Success(newTaskId)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to add subtask", e)
        }
    }

    /** A task and its steps live in the same project, so moving one moves the whole checklist. */
    suspend fun moveToProject(task: Task, projectId: Long?) {
        taskDao.update(task.copy(projectId = projectId).toEntity())
        taskDao.subtasksOf(task.id).forEach { taskDao.update(it.copy(projectId = projectId)) }
    }

    /**
     * Completing a recurring task keeps the finished instance in place — so it stays visible
     * in Today with its "Done 09:12" line — and inserts the next instance.
     *
     * Finishing a task also finishes whatever is still open beneath it, and a recurring task
     * hands its checklist to the next occurrence unticked, with the subtask dates moved by the
     * same span as the parent's.
     */
    suspend fun setCompleted(task: Task, completed: Boolean, today: LocalDate = LocalDate.now()) {
        if (!completed) {
            taskDao.update(task.copy(completedAt = null).toEntity())
            return
        }
        val now = Instant.now()
        taskDao.update(task.copy(completedAt = now).toEntity())

        val subtasks = subtasksOf(task.id)
        subtasks.filter { !it.isDone }
            .forEach { taskDao.update(it.copy(completedAt = now).toEntity()) }

        val rule = task.recurrence ?: return
        val nextDue = RecurrenceEngine.dueDateAfterCompletion(rule, task.dueDate, today)
        val nextId = taskDao.insert(
            task.copy(
                id = 0L,
                completedAt = null,
                dueDate = nextDue,
                createdAt = now,
            ).toEntity(),
        )
        val shift = task.dueDate?.let { ChronoUnit.DAYS.between(it, nextDue) } ?: 0L
        subtasks.forEach { subtask ->
            taskDao.insert(
                subtask.copy(
                    id = 0L,
                    parentId = nextId,
                    completedAt = null,
                    dueDate = subtask.dueDate?.plusDays(shift),
                    createdAt = now,
                ).toEntity(),
            )
        }
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

    /**
     * Validates and upserts a project, checking for circular references and maximum nesting depth.
     */
    suspend fun upsertProject(project: Project): RepositoryResult<Long> {
        // Validate project name
        if (project.name.isBlank()) {
            return RepositoryResult.ValidationError
        }
        
        // Validate nesting depth
        if (project.parentId != null) {
            val nestingDepth = getProjectNestingDepth(project.parentId)
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
                projectDao.insert(project.toEntity())
            } else {
                projectDao.update(project.toEntity())
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
            val project = projectDao.getAll().firstOrNull { it.id == currentId } ?: break
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
            val project = projectDao.getAll().firstOrNull { it.id == currentId } ?: break
            currentId = project.parentId
        }
        
        return false
    }

    suspend fun deleteProject(id: Long) = projectDao.deleteWithChildren(id)

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

    /** Fills a fresh install with the sample data the design was drawn against. */
    suspend fun seedIfEmpty() {
        if (projectDao.count() > 0 || taskDao.count() > 0) return
        val projectIds = mutableMapOf<String, Long>()
        SeedData.projects().forEach { (key, project) ->
            val parentId = project.parentKey?.let { projectIds[it] }
            val id = projectDao.insert(
                Project(
                    name = project.name,
                    colorHex = project.colorHex,
                    parentId = parentId,
                    sortOrder = project.sortOrder,
                ).toEntity(),
            )
            projectIds[key] = id
        }
        // Inserted one by one rather than in bulk: a sample checklist needs the id its parent
        // was actually given.
        val checklists = SeedData.subtasks()
        SeedData.tasks(LocalDate.now(), projectIds).forEach { task ->
            val parentId = taskDao.insert(task.toEntity())
            checklists[task.title]?.forEachIndexed { index, title ->
                taskDao.insert(
                    Task(
                        title = title,
                        projectId = task.projectId,
                        parentId = parentId,
                        createdAt = task.createdAt,
                        sortOrder = index,
                    ).toEntity(),
                )
            }
        }
    }
}
