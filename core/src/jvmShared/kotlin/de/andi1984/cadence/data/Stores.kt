package de.andi1984.cadence.data

import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * What [CadenceRepository] needs from storage, in the app's own vocabulary.
 *
 * These are ports, not a data layer: they speak [Task] and [Project] rather than table rows, and
 * they carry no annotation from any database library. That is what lets the repository — where
 * the completion and recurrence rules live — sit in `:core` next to the engine it drives, with
 * SQLDelight supplying the implementation on every platform
 * (see docs/adr/0001-desktop-app-and-multi-device-sync.md).
 *
 * Every id is a UUIDv7 string minted by the caller before a row exists, never assigned by
 * storage (ADR decision 4) — so [insert] takes a [Task]/[Project] that already carries its final
 * id and reports nothing back.
 *
 * The awkward-looking members are the load-bearing ones. [TaskStore.completeIfOpen] and
 * [TaskStore.reopenIfDone] report whether *this* call is the one that changed the row, because
 * the caller always holds a snapshot the UI drew and a checkbox tapped twice hands back the same
 * open task both times. An implementation must make that decision inside the store — in SQL, in a
 * lock, in whatever it has — and never against the [Task] it was passed.
 */
interface TaskStore {

    fun observeAll(): Flow<List<Task>>

    fun observeById(id: String): Flow<Task?>

    suspend fun getAll(): List<Task>

    suspend fun byId(id: String): Task?

    /** The steps of [parentId], in the order they are shown. */
    suspend fun subtasksOf(parentId: String): List<Task>

    /** Inserts a task that already carries its final id, minted by the caller. */
    suspend fun insert(task: Task)

    suspend fun update(task: Task)

    /** Removes a task and its steps — a step without its task has no meaning. */
    suspend fun deleteWithSubtasks(id: String)

    /**
     * Closes a task only if it is still open, and reports whether this call is the one that did it.
     *
     * @return 1 when the row was open and is now done, 0 when it was already done or is gone.
     */
    suspend fun completeIfOpen(id: String, completedAt: Instant): Int

    /** The mirror of [completeIfOpen]: reopens a done task, and reports 0 if it was open. */
    suspend fun reopenIfDone(id: String, updatedAt: Instant): Int

    /**
     * The occurrences [id]'s completion inserted that nobody has ticked off yet.
     *
     * A successor that has itself been completed is left out: the chain has moved past it, and
     * reopening one link is not a reason to unravel the rest of it.
     */
    suspend fun openSuccessorsOf(id: String): List<String>
}

interface ProjectStore {

    fun observeAll(): Flow<List<Project>>

    suspend fun getAll(): List<Project>

    /** Inserts a project that already carries its final id, minted by the caller. */
    suspend fun insert(project: Project)

    suspend fun update(project: Project)

    /** Everything filed under the project, including its subprojects — nesting is one level. */
    suspend fun taskIdsIn(id: String): List<String>

    /**
     * Removes the project and its subprojects, and either moves their tasks to the Inbox or
     * deletes them.
     *
     * Both halves happen together or not at all: a task left naming a project no one answers to
     * disappears from every list, which is worse than either outcome the flag chooses between.
     */
    suspend fun deleteWithChildren(id: String, deleteTasks: Boolean)
}

interface BackupStore {

    /** All or nothing: a failed restore must not leave the app half-empty. */
    suspend fun replaceAll(projects: List<Project>, tasks: List<Task>)
}

/**
 * Metadata rows only — the bytes live in [BlobStore], addressed by [Attachment.sha256] and
 * shared across every row that names them. The attachments table *is* the refcount: there is no
 * stored counter to drift, only [stillReferenced] and [referencedHashes] reading it back.
 */
interface AttachmentStore {

    /** Every attachment across every task — grouped by task the way [Task]s are grouped into
     *  subtask lists, by whatever reads this flow. */
    fun observeAll(): Flow<List<Attachment>>

    suspend fun byId(id: String): Attachment?

    /** The attachments of one task, in the order they were added. */
    suspend fun forTask(taskId: String): List<Attachment>

    /** Inserts an attachment that already carries its final id, minted by the caller. */
    suspend fun insert(attachment: Attachment)

    suspend fun delete(id: String)

    /**
     * Attachment rows for [taskIds], deleted explicitly ahead of the task rows themselves —
     * never left to the FK cascade, for the same reason `TaskDao.deleteWithSubtasks` is not
     * either: the repository's fakes model no foreign-key semantics at all, and a cascade an
     * implementation forgets to enable would leak every blob it should have reclaimed.
     */
    suspend fun deleteForTasks(taskIds: List<String>)

    /** The distinct hashes named by [taskIds]' FILE attachments, gathered before those rows are
     *  deleted so the caller can reclaim whichever of them no surviving row still names. */
    suspend fun hashesForTasks(taskIds: List<String>): List<String>

    /** Of [hashes], the ones some row still names — the rest are garbage on disk. */
    suspend fun stillReferenced(hashes: List<String>): List<String>

    /** Every hash any row currently names, for the full sweep after a backup restore. */
    suspend fun referencedHashes(): List<String>
}
