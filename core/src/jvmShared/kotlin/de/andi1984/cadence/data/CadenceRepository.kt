package de.andi1984.cadence.data

import de.andi1984.cadence.domain.backup.BackupSnapshot
import de.andi1984.cadence.domain.id.UuidV7
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Clock
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

/**
 * The attachment rows, plus the hashes among them whose bytes are on disk.
 *
 * One value rather than two flows so the pair can never be drawn half-updated — a row added and
 * a presence set from before it existed would render the new attachment as missing for a frame.
 */
data class AttachmentIndex(
    val attachments: List<Attachment> = emptyList(),
    val presentBlobs: Set<String> = emptySet(),
)

/** Result of a repository operation that can fail. */
sealed class RepositoryResult<out T> {
    data class Success<T>(val data: T) : RepositoryResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : RepositoryResult<Nothing>()
    object ValidationError : RepositoryResult<Nothing>()
}

class CadenceRepository(
    private val taskStore: TaskStore,
    private val projectStore: ProjectStore,
    private val sectionStore: SectionStore,
    private val tagStore: TagStore,
    private val backupStore: BackupStore,
    private val attachmentStore: AttachmentStore,
    private val blobStore: BlobStore,
    /** The one seam for making several of the stores above commit together — see the note on
     *  [StoreTransaction] and on [setCompleted]/[deleteTask]/[deleteProject] below. */
    private val storeTransaction: StoreTransaction,
    /** Where the blob store's filesystem work runs — every other port already dispatches its
     *  own I/O, and [BlobStore] is a plain `java.io` class with no dispatcher of its own. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** What "now"/"today" means for every write this repository makes — [now] and [today]
     *  both derive from it rather than calling `Instant.now()`/`LocalDate.now()` themselves, so
     *  a test can pin the clock a chain of recurring completions runs against instead of reading
     *  the machine's own date to build its expectation. Defaults to the real clock, so every
     *  existing call site outside tests is unchanged. */
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    val tasks: Flow<List<Task>> = taskStore.observeAll()

    val projects: Flow<List<Project>> = projectStore.observeAll()

    val sections: Flow<List<Section>> = sectionStore.observeAll()

    val tags: Flow<List<Tag>> = tagStore.observeAll()

    val attachments: Flow<List<Attachment>> = attachmentStore.observeAll()

    private val _localWrites = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Ticks once after this device writes a task, project, section or tag — never after a pull.
     *
     * A tick, not an event log: nobody needs to know *what* changed, only that something did, so
     * a slow or absent collector never sees more than one pending tick (`DROP_OLDEST`, capacity
     * 1) and a fresh subscriber sees nothing that happened before it subscribed (no replay). The
     * ViewModel's sync debounce is the one collector today, and this is deliberately where the
     * signal now originates instead of at every call site: [SqlDelightSyncStore.mergeAndAdvance]
     * writes a pulled page straight to the database and never calls through this class, so a row
     * merged in from another device can never appear here — the debounce can collect this flow
     * without the two devices pushing each other awake forever (ADR 0002, decision 11).
     */
    val localWrites: SharedFlow<Unit> = _localWrites.asSharedFlow()

    /**
     * Runs [block] and ticks [localWrites] once it returns without throwing.
     *
     * Every method that used to be followed by `CadenceViewModel.armSync()` routes its write
     * through here instead. A method that reports success or failure as a [RepositoryResult]
     * wraps only the store call inside its success branch, so a validation error or a store
     * failure never ticks — matching exactly what used to arm sync only from the `Success` branch
     * of the caller's `when`. A method with no such branch (an unconditional `armSync()` today)
     * wraps its whole body instead, ticking even where an internal guard made the call a no-op —
     * `setCompleted`'s second tap on an already-done task, for one — because that is what always
     * arming after the call already did.
     *
     * Attachment mutations are the one exception and never call this at all — see the comment by
     * the attachment methods below for why.
     */
    private suspend fun <T> write(block: suspend () -> T): T {
        val result = block()
        _localWrites.tryEmit(Unit)
        return result
    }

    /**
     * Every attachment, and which of the blobs they name are actually on disk right now.
     *
     * The two travel together because presence is not a property of the row and must not become
     * a filesystem hit per row per frame: a screen asking "is this one here?" while drawing a
     * list would stat the same blob directory a hundred times a second. One `exists()` per
     * *distinct* hash per emission, on [ioDispatcher], is the whole cost — and a new emission is
     * exactly when it can have changed, since every add, delete and reclaim goes through a write
     * this flow is downstream of.
     *
     * A row whose blob is missing is not an error: the row is the truth of "the user attached
     * this", the blob is a cache that a restored backup, a new device or a process killed
     * mid-copy can legitimately leave empty.
     */
    val attachmentIndex: Flow<AttachmentIndex> = attachments
        .map { rows -> AttachmentIndex(rows, blobStore.present(rows.mapNotNull { it.sha256 })) }
        .flowOn(ioDispatcher)

    fun task(id: String): Flow<Task?> = taskStore.observeById(id)

    /**
     * The row as it is stored right now — for a caller that has just written it and needs the
     * result of that write, not the snapshot it started from.
     *
     * Every other read in the app is a flow, deliberately. This one exists because a gesture can
     * be two writes (file a task, then group it), and the second must act on what the first left
     * behind rather than on a `Task` that still names the old project.
     */
    suspend fun taskById(id: String): Task? = taskStore.byId(id)

    /** Mints a UUIDv7 for a new task, or stamps an edit — either way `updatedAt` moves to now,
     *  which is what the phase-6 merge engine will resolve conflicts by. */
    suspend fun upsertTask(task: Task): String {
        val now = now()
        val stamped = task.copy(
            createdAt = if (task.createdAt == Instant.EPOCH) now else task.createdAt,
            updatedAt = now,
        )
        return write {
            if (stamped.id.isBlank()) {
                val minted = stamped.copy(
                    id = UuidV7.random(),
                    // A new task goes to the *bottom* of its list, which is only true if someone
                    // gives it a position: every row used to be inserted with 0 and manual order
                    // was therefore id order. A caller that already picked a position keeps it —
                    // that is what `addSubtask` does, and what an import does with the file's own
                    // numbers.
                    sortOrder = if (stamped.sortOrder == 0) nextTaskOrder(stamped) else stamped.sortOrder,
                )
                taskStore.insert(minted)
                minted.id
            } else {
                taskStore.update(stamped)
                stamped.id
            }
        }
    }

    /** One past the last position in the list this task will be drawn in. */
    private suspend fun nextTaskOrder(task: Task): Int =
        if (task.parentId != null) {
            // A step is numbered inside its parent's checklist, not inside the project.
            taskStore.subtasksOf(task.parentId).size
        } else {
            (taskStore.maxSortOrder(task.projectId) ?: -1) + 1
        }

    /**
     * Deletes a task and its subtasks, and reclaims any blob none of their attachments named
     * anymore.
     *
     * The attachment rows are deleted explicitly, ahead of the tasks — never left to the
     * database's FK cascade — for the same reason as everywhere else this repository does that:
     * the fakes this class is tested against model no foreign-key semantics at all, so an
     * implicit delete would let a leak slip past every test that exists to catch one.
     *
     * The attachment delete and the tombstone land in one [storeTransaction] — a process killed
     * between them used to leave either a task gone with its attachments still on disk, or a
     * task still on the row's own way out with its attachments already gone. [reclaim] stays
     * outside it: it is a filesystem cleanup, already idempotent on its own
     * ([sweepOrphanBlobs] is the backstop for exactly a blob the reclaim step never reached), and
     * not something a SQL rollback could undo anyway.
     */
    suspend fun deleteTask(id: String): Unit = write {
        val hashes = storeTransaction.run {
            val ids = listOf(id) + subtasksOf(id).map { it.id }
            val hashes = hashesForTasks(ids)
            deleteAttachmentsForTasks(ids)
            tombstoneTaskWithSubtasks(id, now())
            hashes
        }
        reclaim(hashes)
    }

    /**
     * Tombstones every task and every project — the Settings danger zone, and the only wipe the
     * app has. Returns the ids of the tasks it tombstoned, so the caller can cancel their alarms;
     * a reminder outlives the row it belongs to unless someone cancels it.
     *
     * Attachment rows and their blobs go first and explicitly, the same way [deleteTask] does it,
     * rather than being left to a cascade the fakes do not model.
     *
     * Tasks are wiped first, then sections, then tags, then projects. The four are separate
     * statements —
     * nothing spans several stores in one transaction anywhere in this class — and a process
     * killed between them leaves empty projects behind rather than tasks filed under projects
     * that no longer answer, which is the failure the whole `deleteWithChildren` rule exists to
     * avoid. Running the wipe again finishes the job: every part is idempotent.
     */
    suspend fun deleteEverything(): List<String> = write {
        val taskIds = taskStore.getAll().map { it.id }
        val hashes = attachmentStore.hashesForTasks(taskIds)
        attachmentStore.deleteForTasks(taskIds)
        val at = now()
        taskStore.tombstoneAll(at)
        sectionStore.tombstoneAll(at)
        tagStore.tombstoneAll(at)
        projectStore.tombstoneAll(at)
        reclaim(hashes)
        taskIds
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
            write { taskStore.insert(newTask) }
            RepositoryResult.Success(newTask.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to add subtask", e)
        }
    }

    /**
     * A task and its steps live in the same project, so moving one moves the whole checklist.
     *
     * The section is dropped, not carried: a section belongs to one project, so the heading the
     * task had means nothing in the project it is arriving at.
     */
    suspend fun moveToProject(task: Task, projectId: String?): Unit = write {
        val now = now()
        taskStore.update(task.copy(projectId = projectId, sectionId = null, updatedAt = now))
        taskStore.subtasksOf(task.id).forEach {
            taskStore.update(it.copy(projectId = projectId, sectionId = null, updatedAt = now))
        }
    }

    /**
     * Files a task under one of its project's sections, or under none.
     *
     * The steps follow, the same way they follow their task into a project: a checklist drawn
     * under a different heading than the task it belongs to would read as two separate pieces of
     * work. A task with no project has nowhere to be grouped, so this does nothing for one.
     */
    suspend fun moveToSection(task: Task, sectionId: String?): Unit = write {
        if (task.projectId == null && sectionId != null) return@write
        val now = now()
        taskStore.update(task.copy(sectionId = sectionId, updatedAt = now))
        taskStore.subtasksOf(task.id)
            .forEach { taskStore.update(it.copy(sectionId = sectionId, updatedAt = now)) }
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
     *
     * Every write below — closing the row (or reopening it), shifting the subtasks, inserting
     * the successor and its own subtasks, cloning the attachments — lands in one
     * [storeTransaction]. Before this, each was its own write, and a process killed between
     * "closed" and "successor inserted" left a completed row with no successor, silently ending
     * the recurrence; a kill between "reopened" and "successor removed" left the task standing in
     * the list twice.
     */
    suspend fun setCompleted(
        task: Task,
        completed: Boolean,
        today: LocalDate = today(),
    ): List<String> = write {
        if (!completed) {
            return@write storeTransaction.run {
                if (reopenTaskIfDone(task.id, now()) == 0) return@run emptyList()
                val successors = openSuccessorsOf(task.id)
                val at = now()
                successors.forEach { tombstoneTaskWithSubtasks(it, at) }
                successors
            }
        }
        storeTransaction.run {
            val now = now()
            if (completeTaskIfOpen(task.id, now) == 0) return@run emptyList()

            // Read the row back rather than trust the snapshot: the rule, the due date and the
            // project may have been edited since the row was drawn, and the next occurrence
            // inherits all of them.
            val current = taskById(task.id) ?: return@run emptyList()

            val subtasks = subtasksOf(current.id)
            subtasks.filter { !it.isDone }
                .forEach { updateTask(it.copy(completedAt = now, updatedAt = now)) }

            val rule = current.recurrence ?: return@run emptyList()
            val nextDue = RecurrenceEngine.dueDateAfterCompletion(rule, current.dueDate, today)
            val nextId = UuidV7.successorId(current.id, nextDue)
            insertTask(
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
                insertTask(
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
            attachmentsForTask(current.id).forEach { attachment ->
                insertAttachment(attachment.copy(id = UuidV7.random(), taskId = nextId))
            }
            emptyList()
        }
    }

    /** Moves a task's due date by [days], used by snooze and the overdue triage action. */
    suspend fun shiftDueDate(task: Task, days: Long, from: LocalDate = today()): Unit = write {
        val base = task.dueDate?.takeIf { it.isAfter(from) } ?: from
        taskStore.update(task.copy(dueDate = base.plusDays(days), updatedAt = now()))
    }

    suspend fun setDueDate(task: Task, dueDate: LocalDate?): Unit = write {
        taskStore.update(task.copy(dueDate = dueDate, updatedAt = now()))
    }

    /** "Reschedule all" on the overdue block: everything overdue lands on today. */
    suspend fun rescheduleOverdueToToday(today: LocalDate = today()): Unit = write {
        val now = now()
        taskStore.getAll()
            .filter { it.isOverdue(today) }
            .forEach { taskStore.update(it.copy(dueDate = today, updatedAt = now)) }
    }

    // ── Manual order ───────────────────────────────────────────────────────────────
    //
    // Every row has carried a `sortOrder` since the first schema, `SortMode.MANUAL` has always
    // sorted by it, and until these three methods nothing ever wrote one. Dragging a row is what
    // writes it.
    //
    // **Dense integers, renumbered from 0.** Not a gap scheme, not midpoints. Sync merges rows,
    // not lists: two devices reordering the same list while offline interleave under
    // last-writer-wins whatever the numbering is, so the fancier schemes buy no conflict
    // resistance here — they buy fewer rows written per drag, at the price of a renormalisation
    // pass nobody can trigger deterministically. Dense integers never run out of room between two
    // neighbours, and the store skips the rows whose position did not actually change, so the
    // push still carries only what moved.

    /**
     * Writes [orderedIds] as the order of the list they came from, first at 0.
     *
     * The list *is* the bucket: whatever the caller hands over is renumbered together, which is
     * what lets one call cover a project's band, the Inbox, or a day in Today. Ids no row answers
     * to are skipped by the store rather than being an error — a list can be dragged while a pull
     * is deleting one of its rows.
     */
    suspend fun reorderTasks(orderedIds: List<String>): Unit = write {
        if (orderedIds.size < 2) return@write
        taskStore.reorder(orderedIds.mapIndexed { index, id -> id to index }, now())
    }

    /**
     * Writes the order of the projects directly under [parentId] — null for the root list.
     *
     * Unlike [reorderTasks] this one filters: a subproject dropped into the root list is a *move*
     * and goes through [upsertProject], and renumbering it here would leave it nested but ordered
     * among rows it is not beside.
     */
    suspend fun reorderProjects(parentId: String?, orderedIds: List<String>): Unit = write {
        val here = projectStore.getAll().filterTo(mutableSetOf()) { it.parentId == parentId }
            .mapTo(mutableSetOf()) { it.id }
        val kept = orderedIds.filter { it in here }
        if (kept.size < 2) return@write
        projectStore.reorder(kept.mapIndexed { index, id -> id to index }, now())
    }

    /** Writes the order of [projectId]'s bands. Ids from another project are ignored, as in
     *  [reorderProjects]. */
    suspend fun reorderSections(projectId: String, orderedIds: List<String>): Unit = write {
        val here = sectionStore.getAll().filterTo(mutableSetOf()) { it.projectId == projectId }
            .mapTo(mutableSetOf()) { it.id }
        val kept = orderedIds.filter { it in here }
        if (kept.size < 2) return@write
        sectionStore.reorder(kept.mapIndexed { index, id -> id to index }, now())
    }

    /**
     * Validates and upserts a project, checking for circular references and maximum nesting depth.
     */
    suspend fun upsertProject(project: Project): RepositoryResult<String> {
        // Validate project name
        if (project.name.isBlank()) {
            return RepositoryResult.ValidationError
        }

        // Validate nesting depth and rule out a cycle. Both walk the same chain of parents, so
        // they walk it over one snapshot of the table: each used to re-read every project on
        // every step, which is a full table scan per level of nesting per save.
        val parentId = project.parentId
        if (parentId != null) {
            val parents = projectStore.getAll().associateBy { it.id }
            if (nestingDepth(parentId, parents) >= MAX_PROJECT_NESTING_DEPTH) {
                return RepositoryResult.Error("Maximum project nesting depth ($MAX_PROJECT_NESTING_DEPTH) reached")
            }

            if (ancestorsOf(parentId, parents).contains(project.id)) {
                return RepositoryResult.Error("Cannot create circular reference: project cannot be its own ancestor")
            }
        }

        return try {
            val stamped = project.copy(updatedAt = now())
            val id = write {
                if (stamped.id.isBlank()) {
                    val minted = stamped.copy(
                        id = UuidV7.random(),
                        // Last in its list — see the same line in [upsertTask].
                        sortOrder = if (stamped.sortOrder == 0) {
                            (projectStore.maxSortOrder(stamped.parentId) ?: -1) + 1
                        } else {
                            stamped.sortOrder
                        },
                    )
                    projectStore.insert(minted)
                    minted.id
                } else {
                    projectStore.update(stamped)
                    stamped.id
                }
            }
            RepositoryResult.Success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save project", e)
        }
    }

    /** How many levels deep [projectId] sits, counting itself. */
    private fun nestingDepth(projectId: String, projects: Map<String, Project>): Int =
        ancestorsOf(projectId, projects).size

    /**
     * [projectId] and everything above it, which is what both rules above ask about: how long the
     * chain is, and whether the project being saved is already on it.
     *
     * The walk stops on a repeat as well as on a missing parent — a cycle already in storage must
     * not spin here, and one *is* reachable, since two devices can each nest the other's project
     * under theirs while offline.
     */
    private fun ancestorsOf(projectId: String, projects: Map<String, Project>): Set<String> {
        val chain = LinkedHashSet<String>()
        var currentId: String? = projectId
        while (currentId != null && chain.add(currentId)) {
            currentId = projects[currentId]?.parentId
        }
        return chain
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
     *
     * Reading which tasks are affected, deleting their attachments and tombstoning the project
     * tree all land in one [storeTransaction], the same reasoning as [deleteTask]: without it, a
     * process killed mid-delete could leave a task naming a project that answers to nobody, which
     * is exactly the outcome this whole method exists to prevent. [reclaim] stays outside it for
     * the same reason it does there.
     */
    suspend fun deleteProject(id: String, deleteTasks: Boolean = false): List<String> = write {
        val (affected, hashes) = storeTransaction.run {
            val affected = if (deleteTasks) taskIdsInProject(id) else emptyList()
            val hashes = if (deleteTasks) hashesForTasks(affected) else emptyList()
            if (deleteTasks) deleteAttachmentsForTasks(affected)
            tombstoneProjectWithChildren(id, deleteTasks, now())
            affected to hashes
        }
        reclaim(hashes)
        affected
    }

    // ── Sections ───────────────────────────────────────────────────────────────────

    /**
     * Validates and upserts a section.
     *
     * Far less to check than [upsertProject]: sections never nest, so there is no depth to
     * measure and no cycle to rule out — a name and the project it belongs to is the whole
     * record.
     */
    suspend fun upsertSection(section: Section): RepositoryResult<String> {
        if (section.name.isBlank() || section.projectId.isBlank()) {
            return RepositoryResult.ValidationError
        }
        return try {
            val stamped = section.copy(name = section.name.trim(), updatedAt = now())
            val id = write {
                if (stamped.id.isBlank()) {
                    val minted = stamped.copy(
                        id = UuidV7.random(),
                        // A new band goes below the existing ones — see [upsertTask].
                        sortOrder = if (stamped.sortOrder == 0) {
                            (sectionStore.maxSortOrder(stamped.projectId) ?: -1) + 1
                        } else {
                            stamped.sortOrder
                        },
                    )
                    sectionStore.insert(minted)
                    minted.id
                } else {
                    sectionStore.update(stamped)
                    stamped.id
                }
            }
            RepositoryResult.Success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save section", e)
        }
    }

    /**
     * Removes a section. Its tasks stay exactly where they are and lose only the heading.
     *
     * There is deliberately no "delete the tasks too" the way [deleteProject] offers one: a
     * section is a band in a list, and nobody means "and everything in it" by dragging a heading
     * away. Deleting the work is what deleting a task or a project is for.
     */
    suspend fun deleteSection(id: String): Unit = write {
        sectionStore.tombstone(id, now())
    }

    // ── Tags ───────────────────────────────────────────────────────────────────────

    /**
     * Validates and upserts a tag.
     *
     * The one rule beyond a non-blank name is that names are **unique, case-insensitively**. Tags
     * are typed as `@handle` in the quick-add line and matched by name, so two tags called `home`
     * and `Home` would make that line ambiguous — and a duplicate is almost always a second
     * device having created the same label rather than someone wanting two of them. The check is
     * a validation, not a merge: sync can still land two, and the read side simply shows both.
     */
    suspend fun upsertTag(tag: Tag): RepositoryResult<String> {
        val name = tag.name.trim()
        if (name.isBlank()) return RepositoryResult.ValidationError
        val clash = tagStore.getAll()
            .any { it.id != tag.id && it.name.equals(name, ignoreCase = true) }
        if (clash) return RepositoryResult.Error("A tag called \"$name\" already exists")

        return try {
            val stamped = tag.copy(name = name, updatedAt = now())
            val id = write {
                if (stamped.id.isBlank()) {
                    val minted = stamped.copy(
                        id = UuidV7.random(),
                        // Last in the list — see the same line in [upsertTask].
                        sortOrder = if (stamped.sortOrder == 0) {
                            (tagStore.maxSortOrder() ?: -1) + 1
                        } else {
                            stamped.sortOrder
                        },
                    )
                    tagStore.insert(minted)
                    minted.id
                } else {
                    tagStore.update(stamped)
                    stamped.id
                }
            }
            RepositoryResult.Success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RepositoryResult.Error("Failed to save tag", e)
        }
    }

    /**
     * Removes a tag. Every task keeps every other label it had, and nothing else is written.
     *
     * There is deliberately no "delete the tasks too" the way [deleteProject] offers one, and no
     * pass over the tasks that wore it either: a tag is a label, and stripping it from a thousand
     * rows would push a thousand rows for a change that is already invisible — the read side drops
     * an id no live tag answers to. It also means reviving the tag from a backup puts it back on
     * exactly the tasks that had it.
     */
    suspend fun deleteTag(id: String): Unit = write {
        tagStore.tombstone(id, now())
    }

    /** Writes the order of the tag list — see [reorderTasks]. */
    suspend fun reorderTags(orderedIds: List<String>): Unit = write {
        if (orderedIds.size < 2) return@write
        tagStore.reorder(orderedIds.mapIndexed { index, id -> id to index }, now())
    }

    /**
     * Replaces a task's labels wholesale.
     *
     * Wholesale rather than add/remove because that is what the row on the wire is: `tagIds` is one
     * column, so a "remove one tag" that read the row back and wrote it again would be the same
     * write with a race in front of it. The steps do **not** follow — unlike a project or a
     * section, a label on the parent says nothing about its checklist, and tagging a task with
     * `@errand` should not silently tag five subtasks.
     *
     * Ids are deduplicated and blanks dropped; ids naming a tag that does not exist are kept, not
     * pruned. A pull can deliver a task before the tag it names, and a repository that pruned on
     * write would erase the label a moment before its tag arrived.
     */
    suspend fun setTaskTags(task: Task, tagIds: List<String>): Unit = write {
        val cleaned = tagIds.filter { it.isNotBlank() }.distinct()
        if (cleaned == task.tagIds) return@write
        taskStore.update(task.copy(tagIds = cleaned, updatedAt = now()))
    }

    /** Adds [tagId] if the task does not have it, removes it if it does — what tapping a chip in
     *  the picker does. */
    suspend fun toggleTaskTag(task: Task, tagId: String) {
        val next =
            if (tagId in task.tagIds) task.tagIds - tagId else task.tagIds + tagId
        setTaskTags(task, next)
    }

    // ── Attachments ────────────────────────────────────────────────────────────────
    //
    // None of these route through `write` — and that is not an oversight: an attachment is a
    // local fact. Its row is not in the wire shape (`data/sync/RemoteRecords.kt`) and its bytes
    // are not in the backup file either until the bundle export of phase 4, so there is nothing
    // here for a round to push and no reason to tick `localWrites` at all.

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
        // Every other port dispatches its own I/O; [BlobStore] is a plain `java.io` class, and
        // this is a copy of up to 25 MB — on Android the ViewModel's scope is the main thread, so
        // an undispatched call here is a frozen UI for the length of the file.
        val stored = withContext(ioDispatcher) { blobStore.store(source, MAX_ATTACHMENT_BYTES) }
        return when (val result = stored) {
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

    /**
     * The bytes behind [sha256], or null when they are not on this device.
     *
     * The one place outside this class that gets a [File] — a viewer has to be handed something
     * the platform can open, and the shells' openers take it from here rather than assembling a
     * path out of the store's layout themselves.
     */
    fun blobFile(sha256: String): File? = blobStore.file(sha256)

    /**
     * Points a FILE row at bytes the user found again, and heals it.
     *
     * The row keeps its name, its task and its place in the list; the hash and the size come from
     * what was just copied in. That the new bytes may hash differently is deliberate and not an
     * error — someone re-exporting the same invoice from their bank gets a different file with
     * the same meaning, and refusing it would leave the row broken forever to protect a hash
     * nobody promised.
     *
     * The old hash is reclaimed like any other: if no surviving row names it, its blob goes — it
     * is normally already gone, which is why this path exists at all.
     */
    suspend fun relocateAttachment(id: String, source: InputStream): AddAttachmentResult {
        val existing = attachmentStore.byId(id) ?: return AddAttachmentResult.ValidationError
        if (existing.kind != AttachmentKind.FILE) return AddAttachmentResult.ValidationError
        val stored = withContext(ioDispatcher) { blobStore.store(source, MAX_ATTACHMENT_BYTES) }
        return when (val result = stored) {
            is StoreResult.Ok -> {
                attachmentStore.update(
                    existing.copy(sha256 = result.sha256, sizeBytes = result.sizeBytes),
                )
                val previous = existing.sha256
                if (previous != null && previous != result.sha256) reclaim(listOf(previous))
                AddAttachmentResult.Success(id)
            }
            StoreResult.TooLarge -> AddAttachmentResult.TooLarge
            is StoreResult.Failed -> AddAttachmentResult.Failed(result.cause)
        }
    }

    /** A blob is garbage the moment no row names it — the attachments table is the only
     *  refcount, read back here rather than trusted from a stored counter. */
    private suspend fun reclaim(hashes: List<String>) {
        if (hashes.isEmpty()) return
        val kept = attachmentStore.stillReferenced(hashes).toSet()
        withContext(ioDispatcher) { blobStore.deleteAll(hashes.toSet() - kept) }
    }

    /**
     * Removes anything on disk no attachment row names. Meant to run once at cold start, to
     * heal a leak left by a process killed mid-copy, and after [restore], where a full replace
     * makes reclaiming one hash at a time meaningless.
     */
    suspend fun sweepOrphanBlobs() {
        val referenced = attachmentStore.referencedHashes().toSet()
        withContext(ioDispatcher) { blobStore.sweepOrphans(referenced) }
    }

    // ── Backup ─────────────────────────────────────────────────────────────────────

    /** One-shot read of everything, for an export. */
    suspend fun snapshot(): BackupSnapshot = BackupSnapshot(
        projects = projectStore.getAll(),
        sections = sectionStore.getAll(),
        tags = tagStore.getAll(),
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
     * What it does *not* lose is a record you have since deleted: a file is an instruction, not a
     * version, so anything in it that lands on a tombstone is restored and stamped with this
     * moment rather than the file's (see [BackupStore.mergeAll]). Importing the same export twice
     * around a delete is the ordinary way to undo one, and it used to write nothing at all.
     *
     * Blobs are swept afterwards because the merge can leave attachment rows naming tasks that
     * lost — see `mergeAll`, which does not touch attachments itself.
     */
    suspend fun restore(snapshot: BackupSnapshot): Unit = write {
        backupStore.mergeAll(
            projects = snapshot.projects,
            sections = snapshot.sections,
            tags = snapshot.tags,
            tasks = snapshot.tasks,
            revivedAt = now(),
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
     *
     * Reads [clock] rather than calling `Instant.now()` directly, so a test can pin what "now"
     * means for a whole chain of writes.
     */
    private fun now(): Instant = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)

    /** What "today" means for a write that lands on it — [setCompleted]'s recurrence step,
     *  [shiftDueDate]'s snooze base, [rescheduleOverdueToToday]'s target — read from [clock]
     *  rather than `LocalDate.now()`, for the same reason [now] is. */
    private fun today(): LocalDate = LocalDate.now(clock)
}
