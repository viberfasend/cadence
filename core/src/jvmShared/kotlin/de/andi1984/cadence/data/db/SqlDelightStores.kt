package de.andi1984.cadence.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import de.andi1984.cadence.data.BackupStore
import de.andi1984.cadence.data.ProjectStore
import de.andi1984.cadence.data.TaskStore
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

    override suspend fun deleteWithSubtasks(id: String) = withContext(ioDispatcher) {
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

    override suspend fun update(project: Project) = withContext(ioDispatcher) {
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

class SqlDelightBackupStore(
    private val database: CadenceDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupStore {

    /**
     * All or nothing, in one transaction: a failed restore must not leave the app half-empty.
     * `BackupDao.replaceAll` becomes a merge in phase 6 — until then a restore is still the
     * destructive full-replace it always was, ids and links carried over from the file as-is.
     */
    override suspend fun replaceAll(projects: List<Project>, tasks: List<Task>) =
        withContext(ioDispatcher) {
            database.transaction {
                database.taskQueries.deleteAll()
                database.projectQueries.deleteAll()
                projects.forEach { database.projectQueries.insertRow(it) }
                tasks.forEach { database.taskQueries.insertRow(it) }
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
