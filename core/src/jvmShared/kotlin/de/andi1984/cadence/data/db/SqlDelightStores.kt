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

    override suspend fun deleteWithSubtasks(id: String): Unit = withContext(ioDispatcher) {
        queries.deleteWithSubtasks(id)
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

    override suspend fun deleteWithChildren(id: String, deleteTasks: Boolean) =
        withContext(ioDispatcher) {
            database.transaction {
                if (deleteTasks) {
                    queries.deleteTasksIn(id)
                } else {
                    queries.moveTasksToInbox(Instant.now().toEpochMilli(), id)
                }
                queries.deleteWithChildrenRows(id)
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
     * All or nothing, in one transaction: a failed restore must not leave the app half-empty.
     * `BackupDao.replaceAll` becomes a merge in phase 6 — until then a restore is still the
     * destructive full-replace it always was, ids and links carried over from the file as-is.
     * The backup format does not carry attachments yet (`docs/attachments-and-share.md`, phase
     * 4), so a restore clears every attachment row too — nothing in the file could repopulate
     * them, and leaving stale rows pointing at tasks the restore just replaced would be worse.
     * [de.andi1984.cadence.data.CadenceRepository.restore] sweeps the now-orphaned blobs
     * afterwards.
     */
    @Deprecated("Use mergeAll instead for phase 6 sync")
    override suspend fun replaceAll(projects: List<Project>, tasks: List<Task>) =
        withContext(ioDispatcher) {
            database.transaction {
                database.attachmentQueries.deleteAll()
                database.taskQueries.deleteAll()
                database.projectQueries.deleteAll()
                projects.forEach { database.projectQueries.insertRow(it) }
                tasks.forEach { database.taskQueries.insertRow(it) }
            }
        }

    /**
     * Merge the given projects and tasks into the current database using last-writer-wins
     * conflict resolution. This is the phase 6 implementation that replaces the destructive
     * replaceAll with a proper merge.
     * 
     * The merge works as follows:
     * 1. For each project/task, if it exists locally, compare updatedAt timestamps
     * 2. If the incoming record has a newer updatedAt, replace the local record
     * 3. If the incoming record has deletedAt set (tombstone), delete the local record
     * 4. If timestamps are equal, keep the local record (tie-break by preferring existing data)
     * 5. If the record doesn't exist locally, insert it
     * 
     * All operations happen in a single transaction to ensure atomicity.
     */
    override suspend fun mergeAll(projects: List<Project>, tasks: List<Task>) =
        withContext(ioDispatcher) {
            database.transaction {
                // Merge projects
                for (project in projects) {
                    val existing = database.projectQueries.selectById(project.id).executeAsOneOrNull()
                    
                    if (existing != null) {
                        // Record exists, check if we should replace it
                        val existingUpdatedAt = Instant.ofEpochMilli(existing.updatedAt)
                        val incomingUpdatedAt = project.updatedAt
                        
                        if (project.deletedAt != null) {
                            // Incoming is a tombstone - delete the local record
                            database.projectQueries.deleteById(project.id)
                        } else if (incomingUpdatedAt > existingUpdatedAt) {
                            // Incoming is newer - replace the local record
                            database.projectQueries.insertRow(project)
                        }
                        // If timestamps are equal or incoming is older, keep existing
                    } else {
                        // Record doesn't exist locally
                        if (project.deletedAt == null) {
                            // Insert new record (ignore tombstones for non-existent records)
                            database.projectQueries.insertRow(project)
                        }
                    }
                }

                // Merge tasks
                for (task in tasks) {
                    val existing = database.taskQueries.selectById(task.id).executeAsOneOrNull()
                    
                    if (existing != null) {
                        // Record exists, check if we should replace it
                        val existingUpdatedAt = Instant.ofEpochMilli(existing.updatedAt)
                        val incomingUpdatedAt = task.updatedAt
                        
                        if (task.deletedAt != null) {
                            // Incoming is a tombstone - delete the local record
                            database.taskQueries.deleteById(task.id)
                        } else if (incomingUpdatedAt > existingUpdatedAt) {
                            // Incoming is newer - replace the local record
                            database.taskQueries.insertRow(task)
                        }
                        // If timestamps are equal or incoming is older, keep existing
                    } else {
                        // Record doesn't exist locally
                        if (task.deletedAt == null) {
                            // Insert new record (ignore tombstones for non-existent records)
                            database.taskQueries.insertRow(task)
                        }
                    }
                }
            }
        }
}

private fun TaskQueries.insertRow(task: Task) {
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

private fun ProjectQueries.insertRow(project: Project) {
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

private fun toTask(
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

private fun toProject(
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
