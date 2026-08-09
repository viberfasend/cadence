package de.andi1984.cadence.data

import de.andi1984.cadence.domain.backup.BackupSnapshot
import de.andi1984.cadence.domain.id.UuidV7
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

    fun task(id: String): Flow<Task?> = taskStore.observeById(id)

    /** Mints a UUIDv7 for a new task, or stamps an edit — either way `updatedAt` moves to now,
     *  which is what the phase-6 merge engine will resolve conflicts by. */
    suspend fun upsertTask(task: Task): String {
        val now = now()
        val stamped = task.copy(
            createdAt = if (task.createdAt == Instant.EPOCH) now else task.createdAt,
            updatedAt = now,
        )
        return if (stamped.id.isBlank()) {
            val minted = stamped.copy(id = UuidV7.random())
            taskStore.insert(minted)
            minted.id
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
    suspend fun deleteTask(id: String) {
        val ids = listOf(id) + taskStore.subtasksOf(id).map { it.id }
        val hashes = attachmentStore.hashesForTasks(ids)
        attachmentStore.deleteForTasks(ids)
        taskStore.tombstoneWithSubtasks(id, now())
        reclaim(hashes)
    }

    suspend fun subtasksOf(parentId: String): List<Task> = taskStore.subtasksOf(parentId)

    /**
     * Adds a step under [parent].
     *
     * Nesting stays one level deep: adding a subtask while looking at a subtask files the new
     * one next to it, under the same task, rather than starting a third level.
     */
    suspend fun addSubtask(parent: Task, title: String): RepositoryResult<String> {
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
            val now = now()
            val newTask = Task(
                id = UuidV7.random(),
                title = trimmed,
                projectId = parent.projectId,
                parentId = parentId,
                createdAt = now,
                updatedAt = now,
                sortOrder = currentSubtaskCount,
            )
            taskStore.insert(newTask)
            RepositoryResult.Success(newTask.id)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to add subtask", e)
        }
    }

    /** A task and its steps live in the same project, so moving one moves the whole checklist. */
    suspend fun moveToProject(task: Task, projectId: String?) {
        val now = now()
        taskStore.update(task.copy(projectId = projectId, updatedAt = now))
        taskStore.subtasksOf(task.id)
            .forEach { taskStore.update(it.copy(projectId = projectId, updatedAt = now)) }
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
     * The successor's id is *derived*, not minted — [UuidV7.successorId] from the occurrence
     * being completed and the next due date, so two devices completing the same occurrence
     * offline produce the same id and a future merge collapses them instead of duplicating the
     * task (ADR 0001, decision 4). The subtasks handed over derive theirs the same way, keyed
     * additionally by their own id so siblings do not collide.
     *
     * @return the ids of the tasks this call deleted, so their reminders can be cancelled —
     *   [de.andi1984.cadence.reminders.ReminderScheduler.sync] only ever sees the tasks that
     *   still exist, so it cannot cancel an alarm for one that is already gone.
     */
    suspend fun setCompleted(
        task: Task,
        completed: Boolean,
        today: LocalDate = LocalDate.now(),
    ): List<String> {
        if (!completed) {
            if (taskStore.reopenIfDone(task.id, now()) == 0) return emptyList()
            val successors = taskStore.openSuccessorsOf(task.id)
            val at = now()
            successors.forEach { taskStore.tombstoneWithSubtasks(it, at) }
            return successors
        }
        val now = now()
        if (taskStore.completeIfOpen(task.id, now) == 0) return emptyList()

        // Read the row back rather than trust the snapshot: the rule, the due date and the
        // project may have been edited since the row was drawn, and the next occurrence
        // inherits all of them.
        val current = taskStore.byId(task.id) ?: return emptyList()

        val subtasks = subtasksOf(current.id)
        subtasks.filter { !it.isDone }
            .forEach { taskStore.update(it.copy(completedAt = now, updatedAt = now)) }

        val rule = current.recurrence ?: return emptyList()
        val nextDue = RecurrenceEngine.dueDateAfterCompletion(rule, current.dueDate, today)
        val nextId = UuidV7.successorId(current.id, nextDue)
        taskStore.insert(
            current.copy(
                id = nextId,
                completedAt = null,
                dueDate = nextDue,
                createdAt = now,
                updatedAt = now,
                spawnedFromId = current.id,
            ),
        )
        val shift = current.dueDate?.let { ChronoUnit.DAYS.between(it, nextDue) } ?: 0L
        subtasks.forEach { subtask ->
            taskStore.insert(
                subtask.copy(
                    id = UuidV7.successorId(current.id, nextDue, discriminant = subtask.id),
                    parentId = nextId,
                    completedAt = null,
                    dueDate = subtask.dueDate?.plusDays(shift),
                    createdAt = now,
                    updatedAt = now,
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
            attachmentStore.insert(attachment.copy(id = UuidV7.random(), taskId = nextId))
        }
        return emptyList()
    }

    /** Moves a task's due date by [days], used by snooze and the overdue triage action. */
    suspend fun shiftDueDate(task: Task, days: Long, from: LocalDate = LocalDate.now()) {
        val base = task.dueDate?.takeIf { it.isAfter(from) } ?: from
        taskStore.update(task.copy(dueDate = base.plusDays(days), updatedAt = now()))
    }

    suspend fun setDueDate(task: Task, dueDate: LocalDate?) {
        taskStore.update(task.copy(dueDate = dueDate, updatedAt = now()))
    }

    /** "Reschedule all" on the overdue block: everything overdue lands on today. */
    suspend fun rescheduleOverdueToToday(today: LocalDate = LocalDate.now()) {
        val now = now()
        taskStore.getAll()
            .filter { it.isOverdue(today) }
            .forEach { taskStore.update(it.copy(dueDate = today, updatedAt = now)) }
    }

    /**
     * Validates and upserts a project, checking for circular references and maximum nesting depth.
     */
    suspend fun upsertProject(project: Project): RepositoryResult<String> {
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
            val stamped = project.copy(updatedAt = now())
            val id = if (stamped.id.isBlank()) {
                val minted = stamped.copy(id = UuidV7.random())
                projectStore.insert(minted)
                minted.id
            } else {
                projectStore.update(stamped)
                stamped.id
            }
            RepositoryResult.Success(id)
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save project", e)
        }
    }

    /**
     * Calculates the nesting depth of a project by counting how many levels deep it is.
     */
    private suspend fun getProjectNestingDepth(projectId: String): Int {
        var depth = 0
        var currentId: String? = projectId

        while (currentId != null) {
            val project = projectStore.getAll().firstOrNull { it.id == currentId } ?: break
            currentId = project.parentId
            depth++
        }

        return depth
    }

    /**
     * Checks if setting parentId for a project would create a circular reference.
     */
    private suspend fun wouldCreateCircularReference(projectId: String, newParentId: String?): Boolean {
        if (newParentId == null) return false

        var currentId: String? = newParentId
        while (currentId != null) {
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
    suspend fun deleteProject(id: String, deleteTasks: Boolean = false): List<String> {
        val affected = if (deleteTasks) projectStore.taskIdsIn(id) else emptyList()
        val hashes = if (deleteTasks) attachmentStore.hashesForTasks(affected) else emptyList()
        if (deleteTasks) attachmentStore.deleteForTasks(affected)
        projectStore.tombstoneWithChildren(id, deleteTasks, now())
        reclaim(hashes)
        return affected
    }

    // ── Attachments ────────────────────────────────────────────────────────────────

    sealed class AddAttachmentResult {
        data class Success(val id: String) : AddAttachmentResult()
        data object TooLarge : AddAttachmentResult()
        data object LimitReached : AddAttachmentResult()
        data class Failed(val cause: Throwable) : AddAttachmentResult()
        data object ValidationError : AddAttachmentResult()
    }

    /** Copies [source] into the blob store and files it on [taskId]. */
    suspend fun addFileAttachment(
        taskId: String,
        source: InputStream,
        name: String,
        mimeType: String,
    ): AddAttachmentResult {
        val existing = attachmentStore.forTask(taskId)
        if (existing.size >= MAX_ATTACHMENTS_PER_TASK) return AddAttachmentResult.LimitReached
        return when (val result = blobStore.store(source, MAX_ATTACHMENT_BYTES)) {
            is StoreResult.Ok -> {
                val id = UuidV7.random()
                attachmentStore.insert(
                    Attachment(
                        id = id,
                        taskId = taskId,
                        kind = AttachmentKind.FILE,
                        name = name,
                        mimeType = mimeType,
                        sha256 = result.sha256,
                        sizeBytes = result.sizeBytes,
                        createdAt = now(),
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
    suspend fun addLinkAttachment(taskId: String, url: String, name: String): AddAttachmentResult {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return AddAttachmentResult.ValidationError
        val existing = attachmentStore.forTask(taskId)
        if (existing.size >= MAX_ATTACHMENTS_PER_TASK) return AddAttachmentResult.LimitReached
        val id = UuidV7.random()
        attachmentStore.insert(
            Attachment(
                id = id,
                taskId = taskId,
                kind = AttachmentKind.LINK,
                name = name.trim().ifBlank { trimmedUrl },
                mimeType = "text/uri-list",
                url = trimmedUrl,
                createdAt = now(),
                sortOrder = existing.size,
            ),
        )
        return AddAttachmentResult.Success(id)
    }

    /** Removes one attachment and reclaims its blob if nothing else names it. */
    suspend fun deleteAttachment(id: String) {
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
     * Folds a backup into what is already here. Ids stay the ones in the file and task→project
     * links survive without remapping; settings in the snapshot are ignored and handled by the
     * shell's `BackupIo`.
     *
     * This used to replace both tables outright, which made importing a data-loss event: a device
     * that imported a file older than itself lost everything added since that file was written,
     * and two devices sharing one file each undid the other. Importing is a merge now (ADR 0001
     * decision 5, implemented by ADR 0002), so it is idempotent, safe to run twice, and importing
     * someone else's file adds to yours rather than becoming it.
     *
     * A v1 file carries no `updatedAt` and decodes to `Instant.EPOCH`, so it loses every conflict
     * against a row you already have — which is the right answer for a file written before the
     * app tracked when anything changed.
     *
     * Blobs are swept afterwards because the merge can leave attachment rows naming tasks that
     * lost — see `mergeAll`, which does not touch attachments itself.
     */
    suspend fun restore(snapshot: BackupSnapshot) {
        backupStore.mergeAll(
            projects = snapshot.projects,
            tasks = snapshot.tasks,
        )
        sweepOrphanBlobs()
    }

    /**
     * Every timestamp this repository stamps, truncated to the millisecond.
     *
     * Local storage is epoch millis, Postgres `timestamptz` is microseconds, and `Instant.now()`
     * on JDK 17 has microsecond precision. Left alone, a row pushed to the server and pulled
     * back returns with a strictly greater `updatedAt` than the local copy — so the merge
     * overwrites it, which bumps `updatedAt`, which pushes it again, forever. Truncating at the
     * source is what makes the database, the wire and the merge agree exactly
     * (docs/adr/0002-supabase-sync.md, decision 8).
     */
    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
}
