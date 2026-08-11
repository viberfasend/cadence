package de.andi1984.cadence.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.andi1984.cadence.data.AttachmentStore
import de.andi1984.cadence.data.BackupStore
import de.andi1984.cadence.data.ProjectStore
import de.andi1984.cadence.data.TaskStore
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * SQLDelight behind the ports `:core` declares — the direct successor of what `RoomStores.kt`
 * did for `:app`, now living beside the schema because SQLDelight reaches every platform from
 * one set of `.sq` files (ADR 0001, decision 2). The mappers build a domain [Task]/[Project]
 * straight from the query row, the same field-by-field conversion `toDomain`/`toEntity` used to
 * do — epoch day/second-of-day/epoch-millis at the boundary and nowhere else, packed recurrence
 * through [RecurrenceCodec].
 */
class SqlDelightTaskStore(
    private val database: CadenceDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TaskStore {

    private val queries = database.taskQueries

    override fun observeAll(): Flow<List<Task>> =
        queries.selectAll(mapper = ::toTask).asFlow().mapToList(ioDispatcher)

    override fun observeById(id: String): Flow<Task?> =
        queries.selectById(id, mapper = ::toTask).asFlow().mapToOneOrNull(ioDispatcher)

    override suspend fun getAll(): List<Task> = withContext(ioDispatcher) {
        queries.selectAll(mapper = ::toTask).executeAsList()
    }

    override suspend fun byId(id: String): Task? = withContext(ioDispatcher) {
        queries.selectById(id, mapper = ::toTask).executeAsOneOrNull()
    }

    override suspend fun subtasksOf(parentId: String): List<Task> = withContext(ioDispatcher) {
        queries.selectSubtasksOf(parentId, mapper = ::toTask).executeAsList()
    }

    override suspend fun insert(task: Task) = withContext(ioDispatcher) {
        queries.insertRow(task)
    }

    override suspend fun update(task: Task) = insert(task)

    override suspend fun tombstoneWithSubtasks(id: String, at: Instant): Unit =
        withContext(ioDispatcher) {
            queries.tombstoneWithSubtasks(at = at.toEpochMilli(), id = id)
        }

    override suspend fun completeIfOpen(id: String, completedAt: Instant): Int =
        withContext(ioDispatcher) {
            database.transactionWithResult {
                queries.completeIfOpen(completedAt.toEpochMilli(), id)
                queries.changes().executeAsOne().toInt()
            }
        }

    override suspend fun reopenIfDone(id: String, updatedAt: Instant): Int =
        withContext(ioDispatcher) {
            database.transactionWithResult {
                queries.reopenIfDone(updatedAt.toEpochMilli(), id)
                queries.changes().executeAsOne().toInt()
            }
        }

    override suspend fun openSuccessorsOf(id: String): List<String> = withContext(ioDispatcher) {
        queries.selectOpenSuccessorsOf(id).executeAsList()
    }
}

class SqlDelightProjectStore(
    private val database: CadenceDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProjectStore {

    private val queries = database.projectQueries

    override fun observeAll(): Flow<List<Project>> =
        queries.selectAll(mapper = ::toProject).asFlow().mapToList(ioDispatcher)

    override suspend fun getAll(): List<Project> = withContext(ioDispatcher) {
        queries.selectAll(mapper = ::toProject).executeAsList()
    }

    override suspend fun insert(project: Project) = withContext(ioDispatcher) {
        queries.insertRow(project)
    }

    override suspend fun update(project: Project): Unit = withContext(ioDispatcher) {
        queries.update(
            name = project.name,
            colorHex = project.colorHex,
            parentId = project.parentId,
            sortOrder = project.sortOrder.toLong(),
            updatedAt = project.updatedAt.toEpochMilli(),
            deletedAt = project.deletedAt?.toEpochMilli(),
            id = project.id,
        )
    }

    override suspend fun taskIdsIn(id: String): List<String> = withContext(ioDispatcher) {
        queries.selectTaskIdsIn(id).executeAsList()
    }

    override suspend fun tombstoneWithChildren(id: String, deleteTasks: Boolean, at: Instant): Unit =
        withContext(ioDispatcher) {
            val stamp = at.toEpochMilli()
            database.transaction {
                if (deleteTasks) {
                    queries.tombstoneTasksIn(at = stamp, id = id)
                } else {
                    queries.moveTasksToInbox(updatedAt = stamp, id = id)
                }
                // Last, not first: both statements above select by `parentId = :id` against rows
                // this one is about to tombstone, and a tombstoned subproject is excluded there.
                queries.tombstoneWithChildrenRows(at = stamp, id = id)
            }
        }
}

class SqlDelightAttachmentStore(
    private val database: CadenceDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentStore {

    private val queries = database.attachmentQueries

    override fun observeAll(): Flow<List<Attachment>> =
        queries.selectAll(mapper = ::toAttachment).asFlow().mapToList(ioDispatcher)

    override suspend fun byId(id: String): Attachment? = withContext(ioDispatcher) {
        queries.selectById(id, mapper = ::toAttachment).executeAsOneOrNull()
    }

    override suspend fun forTask(taskId: String): List<Attachment> = withContext(ioDispatcher) {
        queries.selectForTask(taskId, mapper = ::toAttachment).executeAsList()
    }

    override suspend fun insert(attachment: Attachment) = withContext(ioDispatcher) {
        queries.insertRow(attachment)
    }

    override suspend fun delete(id: String): Unit = withContext(ioDispatcher) {
        queries.delete(id)
    }

    override suspend fun deleteForTasks(taskIds: List<String>): Unit = withContext(ioDispatcher) {
        queries.deleteForTasks(taskIds)
    }

    override suspend fun hashesForTasks(taskIds: List<String>): List<String> =
        withContext(ioDispatcher) {
            queries.selectHashesForTasks(taskIds).executeAsList()
        }

    override suspend fun stillReferenced(hashes: List<String>): List<String> =
        withContext(ioDispatcher) {
            // The generated single-column mapper overload requires a non-null T, so this reads
            // the wrapper row type instead and unwraps — sha256 is nullable only because
            // `IN :hashes` does not narrow it the way `IS NOT NULL` does on the other queries.
            queries.selectStillReferenced(hashes).executeAsList().mapNotNull { it.sha256 }
        }

    override suspend fun referencedHashes(): List<String> = withContext(ioDispatcher) {
        queries.selectReferencedHashes().executeAsList()
    }
}

class SqlDelightBackupStore(
    private val database: CadenceDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupStore {

    /**
     * One rule, applied to every record the same way: the greater `updatedAt` wins, ties keep
     * what is stored, and a tombstone is just a version of the record rather than a special case.
     *
     * The uniformity is the point. The first cut of this special-cased `deletedAt != null` into
     * an unconditional delete, which let a stale delete beat a newer edit — the one outcome
     * last-writer-wins exists to prevent. It also dropped incoming tombstones for ids it had
     * never seen, which quietly resurrects a task the moment some other path delivers the row.
     *
     * Reads go through `selectByIdIncludingDeleted` for the same reason: the local row a merge
     * has to compare against is often precisely a tombstone, and the ordinary `selectById`
     * filters those out.
     *
     * All or nothing, in one transaction: a failed merge must not leave the app half-written.
     * Attachment rows are untouched — the backup format does not carry attachments
     * (`docs/attachments-and-share.md`), so there is nothing here that could repopulate them,
     * and a merge that discarded them would lose files no incoming record ever mentioned.
     */
    override suspend fun mergeAll(projects: List<Project>, tasks: List<Task>): Unit =
        withContext(ioDispatcher) {
            database.transaction { database.mergeRecords(projects, tasks) }
        }

}

/**
 * The merge rule itself, without a transaction of its own so a caller can put more in the same
 * one — which is exactly what sync does, advancing its cursor beside the rows the cursor
 * describes ([de.andi1984.cadence.data.sync.SyncStore.mergeAndAdvance]).
 *
 * Both callers apply the same rule because there is only one: a backup file and a pulled page are
 * the same thing arriving by different roads.
 */
internal fun CadenceDatabase.mergeRecords(projects: List<Project>, tasks: List<Task>) {
    for (project in projects) {
        val existing = projectQueries.selectByIdIncludingDeleted(project.id).executeAsOneOrNull()
        if (existing == null || project.updatedAt > Instant.ofEpochMilli(existing.updatedAt)) {
            projectQueries.insertRow(project)
        }
    }
    for (task in tasks) {
        val existing = taskQueries.selectByIdIncludingDeleted(task.id).executeAsOneOrNull()
        if (existing == null || task.updatedAt > Instant.ofEpochMilli(existing.updatedAt)) {
            taskQueries.insertRow(task)
        }
    }
}

internal fun TaskQueries.insertRow(task: Task) {
    insertOrReplace(
        id = task.id,
        title = task.title,
        notes = task.notes,
        priority = task.priority.level.toLong(),
        projectId = task.projectId,
        parentId = task.parentId,
        spawnedFromId = task.spawnedFromId,
        dueDate = task.dueDate?.toEpochDay(),
        dueTime = task.dueTime?.toSecondOfDay()?.toLong(),
        reminderTime = task.reminderTime?.toSecondOfDay()?.toLong(),
        completedAt = task.completedAt?.toEpochMilli(),
        createdAt = task.createdAt.toEpochMilli(),
        sortOrder = task.sortOrder.toLong(),
        recurrence = RecurrenceCodec.encode(task.recurrence),
        updatedAt = task.updatedAt.toEpochMilli(),
        deletedAt = task.deletedAt?.toEpochMilli(),
    )
}

internal fun ProjectQueries.insertRow(project: Project) {
    insertOrReplace(
        id = project.id,
        name = project.name,
        colorHex = project.colorHex,
        parentId = project.parentId,
        sortOrder = project.sortOrder.toLong(),
        updatedAt = project.updatedAt.toEpochMilli(),
        deletedAt = project.deletedAt?.toEpochMilli(),
    )
}

private fun AttachmentQueries.insertRow(attachment: Attachment) {
    insertOrReplace(
        id = attachment.id,
        taskId = attachment.taskId,
        kind = attachment.kind.name,
        name = attachment.name,
        mimeType = attachment.mimeType,
        sha256 = attachment.sha256,
        sizeBytes = attachment.sizeBytes,
        url = attachment.url,
        createdAt = attachment.createdAt.toEpochMilli(),
        sortOrder = attachment.sortOrder.toLong(),
    )
}

internal fun toTask(
    id: String,
    title: String,
    notes: String?,
    priority: Long,
    projectId: String?,
    parentId: String?,
    spawnedFromId: String?,
    dueDate: Long?,
    dueTime: Long?,
    reminderTime: Long?,
    completedAt: Long?,
    createdAt: Long,
    sortOrder: Long,
    recurrence: String?,
    updatedAt: Long,
    deletedAt: Long?,
) = Task(
    id = id,
    title = title,
    notes = notes,
    priority = Priority.fromLevel(priority.toInt()),
    projectId = projectId,
    parentId = parentId,
    spawnedFromId = spawnedFromId,
    dueDate = dueDate?.let { LocalDate.ofEpochDay(it) },
    dueTime = dueTime?.let { LocalTime.ofSecondOfDay(it) },
    reminderTime = reminderTime?.let { LocalTime.ofSecondOfDay(it) },
    completedAt = completedAt?.let { Instant.ofEpochMilli(it) },
    createdAt = Instant.ofEpochMilli(createdAt),
    sortOrder = sortOrder.toInt(),
    recurrence = RecurrenceCodec.decode(recurrence),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    deletedAt = deletedAt?.let { Instant.ofEpochMilli(it) },
)

internal fun toProject(
    id: String,
    name: String,
    colorHex: String,
    parentId: String?,
    sortOrder: Long,
    updatedAt: Long,
    deletedAt: Long?,
) = Project(
    id = id,
    name = name,
    colorHex = colorHex,
    parentId = parentId,
    sortOrder = sortOrder.toInt(),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    deletedAt = deletedAt?.let { Instant.ofEpochMilli(it) },
)

private fun toAttachment(
    id: String,
    taskId: String,
    kind: String,
    name: String,
    mimeType: String,
    sha256: String?,
    sizeBytes: Long,
    url: String?,
    createdAt: Long,
    sortOrder: Long,
) = Attachment(
    id = id,
    taskId = taskId,
    // Falls back from the bytes rather than throwing, the rule RecurrenceCodec already follows:
    // an unrecognised kind should still render as something rather than crash the whole list.
    kind = AttachmentKind.entries.firstOrNull { it.name == kind }
        ?: if (sha256 != null) AttachmentKind.FILE else AttachmentKind.LINK,
    name = name,
    mimeType = mimeType,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    url = url,
    createdAt = Instant.ofEpochMilli(createdAt),
    sortOrder = sortOrder.toInt(),
)
