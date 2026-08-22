package de.andi1984.cadence.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.andi1984.cadence.data.AttachmentStore
import de.andi1984.cadence.data.BackupStore
import de.andi1984.cadence.data.ProjectStore
import de.andi1984.cadence.data.SectionStore
import de.andi1984.cadence.data.TagStore
import de.andi1984.cadence.data.TaskStore
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
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
        database.transaction { queries.upsertRow(task) }
    }

    override suspend fun update(task: Task) = insert(task)

    override suspend fun tombstoneWithSubtasks(id: String, at: Instant): Unit =
        withContext(ioDispatcher) {
            queries.tombstoneWithSubtasks(at = at.toEpochMilli(), id = id)
        }

    override suspend fun tombstoneAll(at: Instant): Unit =
        withContext(ioDispatcher) {
            queries.tombstoneAll(at = at.toEpochMilli())
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

    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant): Unit =
        withContext(ioDispatcher) {
            val stamp = at.toEpochMilli()
            database.transaction {
                orders.forEach { (id, position) ->
                    queries.updateSortOrder(sortOrder = position.toLong(), updatedAt = stamp, id = id)
                }
            }
        }

    override suspend fun maxSortOrder(projectId: String?): Int? = withContext(ioDispatcher) {
        // The query COALESCEs an empty bucket to -1 so SQLDelight can type the column NOT NULL;
        // the port speaks null, which is the shape the caller branches on.
        queries.maxSortOrderIn(projectId).executeAsOne().toInt().takeIf { it >= 0 }
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
        database.transaction { queries.upsertRow(project) }
    }

    /**
     * The same upsert as [insert], deliberately.
     *
     * It used to be a bare `UPDATE … WHERE id = ?`, which is a silent no-op against a row that is
     * not there — and "not there" is reachable: the 90-day tombstone sweep collects a deleted
     * project outright, so restoring one from a snackbar's Undo after that wrote nothing at all
     * and the project simply never came back.
     */
    override suspend fun update(project: Project) = insert(project)

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
                queries.tombstoneSectionsIn(at = stamp, id = id)
                // Last, not first: all three statements above select by `parentId = :id` against
                // rows this one is about to tombstone, and a tombstoned subproject is excluded
                // there.
                queries.tombstoneWithChildrenRows(at = stamp, id = id)
            }
        }

    override suspend fun tombstoneAll(at: Instant): Unit =
        withContext(ioDispatcher) {
            queries.tombstoneAllRows(at = at.toEpochMilli())
        }

    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant): Unit =
        withContext(ioDispatcher) {
            val stamp = at.toEpochMilli()
            database.transaction {
                orders.forEach { (id, position) ->
                    queries.updateSortOrder(sortOrder = position.toLong(), updatedAt = stamp, id = id)
                }
            }
        }

    override suspend fun maxSortOrder(parentId: String?): Int? = withContext(ioDispatcher) {
        queries.maxSortOrderUnder(parentId).executeAsOne().toInt().takeIf { it >= 0 }
    }
}

class SqlDelightSectionStore(
    private val database: CadenceDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SectionStore {

    private val queries = database.sectionQueries

    override fun observeAll(): Flow<List<Section>> =
        queries.selectAll(mapper = ::toSection).asFlow().mapToList(ioDispatcher)

    override suspend fun getAll(): List<Section> = withContext(ioDispatcher) {
        queries.selectAll(mapper = ::toSection).executeAsList()
    }

    override suspend fun insert(section: Section) = withContext(ioDispatcher) {
        database.transaction { queries.upsertRow(section) }
    }

    /** The same upsert as [insert] — see [SqlDelightProjectStore.update] for why an update alone
     *  is not enough. */
    override suspend fun update(section: Section) = insert(section)

    override suspend fun tombstone(id: String, at: Instant): Unit = withContext(ioDispatcher) {
        val stamp = at.toEpochMilli()
        database.transaction {
            // The tasks first: `clearTasksIn` selects by `sectionId = :id`, which the tombstone
            // does not change, but keeping the pair in one transaction is what makes "the heading
            // is gone and its tasks are ungrouped" a single fact rather than two.
            queries.clearTasksIn(updatedAt = stamp, id = id)
            queries.tombstoneRow(at = stamp, id = id)
        }
    }

    override suspend fun tombstoneAll(at: Instant): Unit = withContext(ioDispatcher) {
        queries.tombstoneAllRows(at = at.toEpochMilli())
    }

    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant): Unit =
        withContext(ioDispatcher) {
            val stamp = at.toEpochMilli()
            database.transaction {
                orders.forEach { (id, position) ->
                    queries.updateSortOrder(sortOrder = position.toLong(), updatedAt = stamp, id = id)
                }
            }
        }

    override suspend fun maxSortOrder(projectId: String): Int? = withContext(ioDispatcher) {
        queries.maxSortOrderIn(projectId).executeAsOne().toInt().takeIf { it >= 0 }
    }
}

class SqlDelightTagStore(
    private val database: CadenceDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TagStore {

    private val queries = database.tagQueries

    override fun observeAll(): Flow<List<Tag>> =
        queries.selectAll(mapper = ::toTag).asFlow().mapToList(ioDispatcher)

    override suspend fun getAll(): List<Tag> = withContext(ioDispatcher) {
        queries.selectAll(mapper = ::toTag).executeAsList()
    }

    override suspend fun insert(tag: Tag) = withContext(ioDispatcher) {
        database.transaction { queries.upsertRow(tag) }
    }

    /** The same upsert as [insert] — see [SqlDelightProjectStore.update] for why an update alone
     *  is not enough. */
    override suspend fun update(tag: Tag) = insert(tag)

    /**
     * One statement, and deliberately only one: no task is rewritten to drop the id.
     *
     * A tag on two thousand tasks would otherwise turn one delete into two thousand rows on the
     * next push — for a change nobody can see, since the read side already drops an id no live
     * tag answers to.
     */
    override suspend fun tombstone(id: String, at: Instant): Unit = withContext(ioDispatcher) {
        queries.tombstoneRow(at = at.toEpochMilli(), id = id)
    }

    override suspend fun tombstoneAll(at: Instant): Unit = withContext(ioDispatcher) {
        queries.tombstoneAllRows(at = at.toEpochMilli())
    }

    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant): Unit =
        withContext(ioDispatcher) {
            val stamp = at.toEpochMilli()
            database.transaction {
                orders.forEach { (id, position) ->
                    queries.updateSortOrder(sortOrder = position.toLong(), updatedAt = stamp, id = id)
                }
            }
        }

    override suspend fun maxSortOrder(): Int? = withContext(ioDispatcher) {
        queries.maxSortOrder().executeAsOne().toInt().takeIf { it >= 0 }
    }
}

/**
 * How many ids may go into one `IN :list` query.
 *
 * SQLDelight expands `IN :taskIds` into one bind parameter per element, and
 * `SQLITE_MAX_VARIABLE_NUMBER` is **999** on SQLite before 3.32 — which is what Android API
 * 26-29 ship, inside the minSdk 26 range this app supports. Deleting a project with a thousand
 * tasks in it, or wiping the database from the danger zone, hands exactly such a list over.
 * Neither the desktop's xerial driver (32766) nor a recent phone reproduces it, so the limit is
 * pinned here rather than read from the connection: the smallest driver the app runs on is what
 * every driver has to survive.
 */
private const val SQL_VARIABLE_LIMIT = 900

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

    // `insertOrReplace`, so the same statement covers both — and safely: nothing references an
    // attachment row, so SQLite's delete-then-insert takes nothing with it (the rule the task and
    // project tables had to be rewritten for does not bite here).
    override suspend fun update(attachment: Attachment) = insert(attachment)

    override suspend fun delete(id: String): Unit = withContext(ioDispatcher) {
        queries.delete(id)
    }

    override suspend fun deleteForTasks(taskIds: List<String>): Unit = withContext(ioDispatcher) {
        // One transaction around the chunks, so a delete of 1500 tasks is still all-or-nothing.
        database.transaction {
            taskIds.chunked(SQL_VARIABLE_LIMIT).forEach { queries.deleteForTasks(it) }
        }
    }

    override suspend fun hashesForTasks(taskIds: List<String>): List<String> =
        withContext(ioDispatcher) {
            // `DISTINCT` only dedupes within a chunk, so the fold does it across them.
            taskIds.chunked(SQL_VARIABLE_LIMIT)
                .flatMap { queries.selectHashesForTasks(it).executeAsList() }
                .distinct()
        }

    override suspend fun stillReferenced(hashes: List<String>): List<String> =
        withContext(ioDispatcher) {
            // The generated single-column mapper overload requires a non-null T, so this reads
            // the wrapper row type instead and unwraps — sha256 is nullable only because
            // `IN :hashes` does not narrow it the way `IS NOT NULL` does on the other queries.
            hashes.chunked(SQL_VARIABLE_LIMIT)
                .flatMap { chunk ->
                    queries.selectStillReferenced(chunk).executeAsList().mapNotNull { it.sha256 }
                }
                .distinct()
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
     * Last-writer-wins for every record, with one exception a file is entitled to: a record that
     * lands on a row this device has *tombstoned* comes back, stamped [revivedAt].
     *
     * The rule everywhere else is uniform on purpose — the greater `updatedAt` wins, ties keep
     * what is stored, and a tombstone is a version of the record rather than a special case. An
     * early cut turned `deletedAt != null` into an unconditional delete, which let a stale delete
     * beat a newer edit, and dropped incoming tombstones for ids it had never seen, which
     * resurrects a task the moment some other path delivers the row.
     *
     * Importing is the one caller that is not two devices agreeing on a version: it is a person
     * pointing at a file and asking for its contents. Answering that by writing nothing — while
     * still reporting the file's counts — is what made a re-imported Todoist export come back
     * empty, since the tool derives ids from project names and the file was written before the
     * delete it was meant to undo. Only a record that is not itself a tombstone revives anything,
     * and the revived row carries the import's clock rather than the file's, or the server's
     * tombstone wins the next round and deletes it straight back.
     *
     * All or nothing, in one transaction: a failed merge must not leave the app half-written.
     * Attachment rows are untouched — the backup format does not carry attachments
     * (`docs/attachments-and-share.md`), so there is nothing here that could repopulate them,
     * and a merge that discarded them would lose files no incoming record ever mentioned.
     */
    override suspend fun mergeAll(
        projects: List<Project>,
        sections: List<Section>,
        tags: List<Tag>,
        tasks: List<Task>,
        revivedAt: Instant,
    ): Unit = withContext(ioDispatcher) {
        database.transaction { database.mergeRecords(projects, sections, tags, tasks, revivedAt) }
    }

}

/**
 * The merge rule itself, without a transaction of its own so a caller can put more in the same
 * one — which is exactly what sync does, advancing its cursor beside the rows the cursor
 * describes ([de.andi1984.cadence.data.sync.SyncStore.mergeAndAdvance]).
 *
 * A backup file and a pulled page are the same thing arriving by different roads, and they get
 * the same last-writer-wins rule — except for [revivedAt], which only importing passes. Sync
 * passes null and must keep passing null: reviving a row a pull delivers would undo every delete
 * the moment the other device pushed its copy back.
 */
internal fun CadenceDatabase.mergeRecords(
    projects: List<Project>,
    sections: List<Section>,
    tags: List<Tag>,
    tasks: List<Task>,
    revivedAt: Instant? = null,
) {
    for (project in projects) projectQueries.mergeRow(project, revivedAt)
    for (section in sections) sectionQueries.mergeRow(section, revivedAt)
    for (tag in tags) tagQueries.mergeRow(tag, revivedAt)
    for (task in tasks) taskQueries.mergeRow(task, revivedAt)
}

/**
 * A local write: this device's own edit, which wins whatever is stored.
 *
 * The pair is an upsert, not a replace — see the note above `updateRow` in `Task.sq` for what
 * REPLACE did to a task's checklist. The caller runs both statements in one transaction.
 */
internal fun TaskQueries.upsertRow(task: Task) {
    updateRow(
        title = task.title,
        notes = task.notes,
        priority = task.priority.level.toLong(),
        projectId = task.projectId,
        sectionId = task.sectionId,
        tagIds = TagIdsCodec.encode(task.tagIds),
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
        id = task.id,
    )
    insertIfAbsent(task)
}

/**
 * A merged write: the same upsert, with last-writer-wins moved into the UPDATE's WHERE clause,
 * so a page of a thousand rows costs no reads at all.
 *
 * [revivedAt] is importing's exception and nothing else's — a live record in a file lifts the
 * local tombstone it lands on, stamped with the import's clock. It runs first so the
 * last-writer-wins update that follows finds a row it cannot beat, and it is a no-op on a row
 * that is not tombstoned.
 */
internal fun TaskQueries.mergeRow(task: Task, revivedAt: Instant? = null) {
    if (revivedAt != null && task.deletedAt == null) {
        reviveIfDeleted(
            title = task.title,
            notes = task.notes,
            priority = task.priority.level.toLong(),
            projectId = task.projectId,
            sectionId = task.sectionId,
        tagIds = TagIdsCodec.encode(task.tagIds),
            parentId = task.parentId,
            spawnedFromId = task.spawnedFromId,
            dueDate = task.dueDate?.toEpochDay(),
            dueTime = task.dueTime?.toSecondOfDay()?.toLong(),
            reminderTime = task.reminderTime?.toSecondOfDay()?.toLong(),
            completedAt = task.completedAt?.toEpochMilli(),
            createdAt = task.createdAt.toEpochMilli(),
            sortOrder = task.sortOrder.toLong(),
            recurrence = RecurrenceCodec.encode(task.recurrence),
            revivedAt = revivedAt.toEpochMilli(),
            id = task.id,
        )
    }
    updateIfOlder(
        title = task.title,
        notes = task.notes,
        priority = task.priority.level.toLong(),
        projectId = task.projectId,
        sectionId = task.sectionId,
        tagIds = TagIdsCodec.encode(task.tagIds),
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
        id = task.id,
    )
    insertIfAbsent(task)
}

private fun TaskQueries.insertIfAbsent(task: Task) {
    insertIfAbsent(
        id = task.id,
        title = task.title,
        notes = task.notes,
        priority = task.priority.level.toLong(),
        projectId = task.projectId,
        sectionId = task.sectionId,
        tagIds = TagIdsCodec.encode(task.tagIds),
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

/** The project half of the same pair — see [upsertRow]. */
internal fun ProjectQueries.upsertRow(project: Project) {
    updateRow(
        name = project.name,
        colorHex = project.colorHex,
        parentId = project.parentId,
        sortOrder = project.sortOrder.toLong(),
        updatedAt = project.updatedAt.toEpochMilli(),
        deletedAt = project.deletedAt?.toEpochMilli(),
        id = project.id,
    )
    insertIfAbsent(project)
}

/** The project half of the merge, [revivedAt] included — see [TaskQueries.mergeRow]. */
internal fun ProjectQueries.mergeRow(project: Project, revivedAt: Instant? = null) {
    if (revivedAt != null && project.deletedAt == null) {
        reviveIfDeleted(
            name = project.name,
            colorHex = project.colorHex,
            parentId = project.parentId,
            sortOrder = project.sortOrder.toLong(),
            revivedAt = revivedAt.toEpochMilli(),
            id = project.id,
        )
    }
    updateIfOlder(
        name = project.name,
        colorHex = project.colorHex,
        parentId = project.parentId,
        sortOrder = project.sortOrder.toLong(),
        updatedAt = project.updatedAt.toEpochMilli(),
        deletedAt = project.deletedAt?.toEpochMilli(),
        id = project.id,
    )
    insertIfAbsent(project)
}

private fun ProjectQueries.insertIfAbsent(project: Project) {
    insertIfAbsent(
        id = project.id,
        name = project.name,
        colorHex = project.colorHex,
        parentId = project.parentId,
        sortOrder = project.sortOrder.toLong(),
        updatedAt = project.updatedAt.toEpochMilli(),
        deletedAt = project.deletedAt?.toEpochMilli(),
    )
}

/** The section half of the same pair — see [upsertRow]. */
internal fun SectionQueries.upsertRow(section: Section) {
    updateRow(
        projectId = section.projectId,
        name = section.name,
        sortOrder = section.sortOrder.toLong(),
        updatedAt = section.updatedAt.toEpochMilli(),
        deletedAt = section.deletedAt?.toEpochMilli(),
        id = section.id,
    )
    insertIfAbsent(section)
}

/** The section half of the merge, [revivedAt] included — see [TaskQueries.mergeRow]. */
internal fun SectionQueries.mergeRow(section: Section, revivedAt: Instant? = null) {
    if (revivedAt != null && section.deletedAt == null) {
        reviveIfDeleted(
            projectId = section.projectId,
            name = section.name,
            sortOrder = section.sortOrder.toLong(),
            revivedAt = revivedAt.toEpochMilli(),
            id = section.id,
        )
    }
    updateIfOlder(
        projectId = section.projectId,
        name = section.name,
        sortOrder = section.sortOrder.toLong(),
        updatedAt = section.updatedAt.toEpochMilli(),
        deletedAt = section.deletedAt?.toEpochMilli(),
        id = section.id,
    )
    insertIfAbsent(section)
}

private fun SectionQueries.insertIfAbsent(section: Section) {
    insertIfAbsent(
        id = section.id,
        projectId = section.projectId,
        name = section.name,
        sortOrder = section.sortOrder.toLong(),
        updatedAt = section.updatedAt.toEpochMilli(),
        deletedAt = section.deletedAt?.toEpochMilli(),
    )
}

/** The tag half of the same pair — see [upsertRow]. */
internal fun TagQueries.upsertRow(tag: Tag) {
    updateRow(
        name = tag.name,
        colorHex = tag.colorHex,
        sortOrder = tag.sortOrder.toLong(),
        updatedAt = tag.updatedAt.toEpochMilli(),
        deletedAt = tag.deletedAt?.toEpochMilli(),
        id = tag.id,
    )
    insertIfAbsent(tag)
}

/** The tag half of the merge, [revivedAt] included — see [TaskQueries.mergeRow]. */
internal fun TagQueries.mergeRow(tag: Tag, revivedAt: Instant? = null) {
    if (revivedAt != null && tag.deletedAt == null) {
        reviveIfDeleted(
            name = tag.name,
            colorHex = tag.colorHex,
            sortOrder = tag.sortOrder.toLong(),
            revivedAt = revivedAt.toEpochMilli(),
            id = tag.id,
        )
    }
    updateIfOlder(
        name = tag.name,
        colorHex = tag.colorHex,
        sortOrder = tag.sortOrder.toLong(),
        updatedAt = tag.updatedAt.toEpochMilli(),
        deletedAt = tag.deletedAt?.toEpochMilli(),
        id = tag.id,
    )
    insertIfAbsent(tag)
}

private fun TagQueries.insertIfAbsent(tag: Tag) {
    insertIfAbsent(
        id = tag.id,
        name = tag.name,
        colorHex = tag.colorHex,
        sortOrder = tag.sortOrder.toLong(),
        updatedAt = tag.updatedAt.toEpochMilli(),
        deletedAt = tag.deletedAt?.toEpochMilli(),
    )
}

/** Attachments keep their `INSERT OR REPLACE`: no table references `attachmentRow`, so the
 *  delete-then-insert REPLACE performs cascades nowhere, and every id here is freshly minted. */
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
    // Last, because `SELECT *` hands the mapper the table's own column order and `3.sqm`/`4.sqm`
    // could only append these — see the note on `taskRow.sectionId`.
    sectionId: String?,
    tagIds: String?,
) = Task(
    id = id,
    title = title,
    notes = notes,
    priority = Priority.fromLevel(priority.toInt()),
    projectId = projectId,
    sectionId = sectionId,
    tagIds = TagIdsCodec.decode(tagIds),
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

internal fun toSection(
    id: String,
    projectId: String,
    name: String,
    sortOrder: Long,
    updatedAt: Long,
    deletedAt: Long?,
) = Section(
    id = id,
    projectId = projectId,
    name = name,
    sortOrder = sortOrder.toInt(),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    deletedAt = deletedAt?.let { Instant.ofEpochMilli(it) },
)

internal fun toTag(
    id: String,
    name: String,
    colorHex: String,
    sortOrder: Long,
    updatedAt: Long,
    deletedAt: Long?,
) = Tag(
    id = id,
    name = name,
    colorHex = colorHex,
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
