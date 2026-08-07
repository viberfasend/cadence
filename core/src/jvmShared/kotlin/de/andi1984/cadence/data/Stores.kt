package de.andi1984.cadence.data

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
 * the Android app supplying a Room-backed implementation and the desktop app supplying its own
 * (see docs/adr/0001-desktop-app-and-multi-device-sync.md).
 *
 * The awkward-looking members are the load-bearing ones. [TaskStore.completeIfOpen] and
 * [TaskStore.reopenIfDone] report whether *this* call is the one that changed the row, because
 * the caller always holds a snapshot the UI drew and a checkbox tapped twice hands back the same
 * open task both times. An implementation must make that decision inside the store — in SQL, in a
 * lock, in whatever it has — and never against the [Task] it was passed.
 */
interface TaskStore {

    fun observeAll(): Flow<List<Task>>

    fun observeById(id: Long): Flow<Task?>

    suspend fun getAll(): List<Task>

    suspend fun byId(id: Long): Task?

    /** The steps of [parentId], in the order they are shown. */
    suspend fun subtasksOf(parentId: Long): List<Task>

    /** Inserts a task with `id == 0L`, replaces one with an id. Returns the id it now has. */
    suspend fun insert(task: Task): Long

    suspend fun update(task: Task)

    /** Removes a task and its steps — a step without its task has no meaning. */
    suspend fun deleteWithSubtasks(id: Long)

    /**
     * Closes a task only if it is still open, and reports whether this call is the one that did it.
     *
     * @return 1 when the row was open and is now done, 0 when it was already done or is gone.
     */
    suspend fun completeIfOpen(id: Long, completedAt: Instant): Int

    /** The mirror of [completeIfOpen]: reopens a done task, and reports 0 if it was open. */
    suspend fun reopenIfDone(id: Long): Int

    /**
     * The occurrences [id]'s completion inserted that nobody has ticked off yet.
     *
     * A successor that has itself been completed is left out: the chain has moved past it, and
     * reopening one link is not a reason to unravel the rest of it.
     */
    suspend fun openSuccessorsOf(id: Long): List<Long>
}

interface ProjectStore {

    fun observeAll(): Flow<List<Project>>

    suspend fun getAll(): List<Project>

    suspend fun insert(project: Project): Long

    suspend fun update(project: Project)

    /** Everything filed under the project, including its subprojects — nesting is one level. */
    suspend fun taskIdsIn(id: Long): List<Long>

    /**
     * Removes the project and its subprojects, and either moves their tasks to the Inbox or
     * deletes them.
     *
     * Both halves happen together or not at all: a task left naming a project no one answers to
     * disappears from every list, which is worse than either outcome the flag chooses between.
     */
    suspend fun deleteWithChildren(id: Long, deleteTasks: Boolean)
}

interface BackupStore {

    /** All or nothing: a failed restore must not leave the app half-empty. */
    suspend fun replaceAll(projects: List<Project>, tasks: List<Task>)
}
