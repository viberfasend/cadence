package de.andi1984.cadence.data

import de.andi1984.cadence.domain.backup.BackupSnapshot
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Maximum number of subtasks allowed per parent task. */
const val MAX_SUBTASKS_PER_PARENT = 100

/** Maximum depth of project nesting allowed. */
const val MAX_PROJECT_NESTING_DEPTH = 5

/** No progress UI on the copy, and the number Android's own auto-backup ceiling already trains
 *  people to expect. */
const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

/** Maximum number of attachments allowed per task. */
const val MAX_ATTACHMENTS_PER_TASK = 20

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
    private val attachmentStore: AttachmentStore,
    private val blobStore: BlobStore,
) {

    val tasks: Flow<List<Task>> = taskStore.observeAll()

    val projects: Flow<List<Project>> = projectStore.observeAll()

    val attachments: Flow<List<Attachment>> = attachmentStore.observeAll()

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

    /**
     * Deletes a task and its subtasks, and reclaims any blob none of their attachments named
     * anymore.
     *
     * The attachment rows are deleted explicitly, ahead of the tasks — never left to the
     * database's FK cascade — for the same reason as everywhere else this repository does that:
     * the fakes this class is tested against model no foreign-key semantics at all, so an
     * implicit delete would let a leak slip past every test that exists to catch one.
     */
    suspend fun deleteTask(id: Long) {
        val ids = listOf(id) + taskStore.subtasksOf(id).map { it.id }
        val hashes = attachmentStore.hashesForTasks(ids)
        attachmentStore.deleteForTasks(ids)
        taskStore.deleteWithSubtasks(id)
        reclaim(hashes)
    }

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

        // A checklist is the method of doing the task, and the method repeats — attachments
        // follow the same rule and hand over unticked. The store is content-addressed, so
        // cloning a FILE row copies no bytes, only a row that points at the same blob.
        attachmentStore.forTask(current.id).forEach { attachment ->
            attachmentStore.insert(attachment.copy(id = 0L, taskId = nextId))
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
     * the scheduler's sync only ever sees the tasks that still exist. When the tasks are
     * deleted their attachments go with them, explicitly and ahead of the task rows, and
     * whichever blobs that leaves unnamed are reclaimed. Tasks moved to the Inbox keep their
     * attachments — nothing to reclaim there.
     */
    suspend fun deleteProject(id: Long, deleteTasks: Boolean = false): List<Long> {
        val affected = if (deleteTasks) projectStore.taskIdsIn(id) else emptyList()
        val hashes = if (deleteTasks) attachmentStore.hashesForTasks(affected) else emptyList()
        if (deleteTasks) attachmentStore.deleteForTasks(affected)
        projectStore.deleteWithChildren(id, deleteTasks)
        reclaim(hashes)
        return affected
    }

    // ── Attachments ────────────────────────────────────────────────────────────────

    sealed class AddAttachmentResult {
        data class Success(val id: Long) : AddAttachmentResult()
        data object TooLarge : AddAttachmentResult()
        data object LimitReached : AddAttachmentResult()
        data class Failed(val cause: Throwable) : AddAttachmentResult()
        data object ValidationError : AddAttachmentResult()
    }

    /** Copies [source] into the blob store and files it on [taskId]. */
    suspend fun addFileAttachment(
        taskId: Long,
        source: InputStream,
        name: String,
        mimeType: String,
    ): AddAttachmentResult {
        val existing = attachmentStore.forTask(taskId)
        if (existing.size >= MAX_ATTACHMENTS_PER_TASK) return AddAttachmentResult.LimitReached
        return when (val result = blobStore.store(source, MAX_ATTACHMENT_BYTES)) {
            is StoreResult.Ok -> {
                val id = attachmentStore.insert(
                    Attachment(
                        taskId = taskId,
                        kind = AttachmentKind.FILE,
                        name = name,
                        mimeType = mimeType,
                        sha256 = result.sha256,
                        sizeBytes = result.sizeBytes,
                        createdAt = Instant.now(),
                        sortOrder = existing.size,
                    ),
                )
                AddAttachmentResult.Success(id)
            }
            StoreResult.TooLarge -> AddAttachmentResult.TooLarge
            is StoreResult.Failed -> AddAttachmentResult.Failed(result.cause)
        }
    }

    /** Files a link on [taskId] — no blob, nothing to reclaim if it is later removed. */
    suspend fun addLinkAttachment(taskId: Long, url: String, name: String): AddAttachmentResult {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return AddAttachmentResult.ValidationError
        val existing = attachmentStore.forTask(taskId)
        if (existing.size >= MAX_ATTACHMENTS_PER_TASK) return AddAttachmentResult.LimitReached
        val id = attachmentStore.insert(
            Attachment(
                taskId = taskId,
                kind = AttachmentKind.LINK,
                name = name.trim().ifBlank { trimmedUrl },
                mimeType = "text/uri-list",
                url = trimmedUrl,
                createdAt = Instant.now(),
                sortOrder = existing.size,
            ),
        )
        return AddAttachmentResult.Success(id)
    }

    /** Removes one attachment and reclaims its blob if nothing else names it. */
    suspend fun deleteAttachment(id: Long) {
        val attachment = attachmentStore.byId(id) ?: return
        attachmentStore.delete(id)
        reclaim(listOfNotNull(attachment.sha256))
    }

    /** A blob is garbage the moment no row names it — the attachments table is the only
     *  refcount, read back here rather than trusted from a stored counter. */
    private suspend fun reclaim(hashes: List<String>) {
        if (hashes.isEmpty()) return
        val kept = attachmentStore.stillReferenced(hashes).toSet()
        blobStore.deleteAll(hashes.toSet() - kept)
    }

    /**
     * Removes anything on disk no attachment row names. Meant to run once at cold start, to
     * heal a leak left by a process killed mid-copy, and after [restore], where a full replace
     * makes reclaiming one hash at a time meaningless.
     */
    suspend fun sweepOrphanBlobs() {
        blobStore.sweepOrphans(attachmentStore.referencedHashes().toSet())
    }

    // ── Backup ─────────────────────────────────────────────────────────────────────

    /** One-shot read of everything, for an export. */
    suspend fun snapshot(): BackupSnapshot = BackupSnapshot(
        projects = projectStore.getAll(),
        tasks = taskStore.getAll(),
        settings = null, // Settings are added by the app-specific BackupIo
    )

    /**
     * Restores a backup by *replacing* both tables — importing is not a merge, so ids stay the
     * ones in the file and task→project links survive without remapping. Settings in the
     * snapshot are ignored here and handled by the app-specific `BackupIo`.
     *
     * The backup format does not carry attachments yet (`docs/attachments-and-share.md`, phase
     * 4), so a restore also clears every attachment row and sweeps every blob that leaves
     * unreferenced — the same full-replace promise the rest of the snapshot already makes,
     * rather than quietly leaving rows that name tasks the file just replaced out from under
     * them.
     */
    suspend fun restore(snapshot: BackupSnapshot) {
        backupStore.replaceAll(
            projects = snapshot.projects,
            tasks = snapshot.tasks,
        )
        sweepOrphanBlobs()
    }
}

