package de.andi1984.cadence.data.db

import de.andi1984.cadence.data.BackupStore
import de.andi1984.cadence.data.ProjectStore
import de.andi1984.cadence.data.TaskStore
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Room behind the ports `:core` declares.
 *
 * The DAOs speak rows and the repository speaks tasks, and this is the one place the two meet:
 * every `toDomain`/`toEntity` in the app now lives in this file. Room stays on the Android side
 * of the module boundary because `room-runtime` is an Android artifact; what crosses is
 * [TaskStore] and friends, which the desktop app implements for itself.
 */
class RoomTaskStore(private val dao: TaskDao) : TaskStore {

    override fun observeAll(): Flow<List<Task>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: Long): Flow<Task?> = dao.observeById(id).map { it?.toDomain() }

    override suspend fun getAll(): List<Task> = dao.getAll().map { it.toDomain() }

    override suspend fun byId(id: Long): Task? = dao.byId(id)?.toDomain()

    override suspend fun subtasksOf(parentId: Long): List<Task> =
        dao.subtasksOf(parentId).map { it.toDomain() }

    override suspend fun insert(task: Task): Long = dao.insert(task.toEntity())

    override suspend fun update(task: Task) = dao.update(task.toEntity())

    override suspend fun deleteWithSubtasks(id: Long) = dao.deleteWithSubtasks(id)

    override suspend fun completeIfOpen(id: Long, completedAt: Instant): Int =
        dao.completeIfOpen(id, completedAt.toEpochMilli())

    override suspend fun reopenIfDone(id: Long): Int = dao.reopenIfDone(id)

    override suspend fun openSuccessorsOf(id: Long): List<Long> = dao.openSuccessorsOf(id)
}

class RoomProjectStore(private val dao: ProjectDao) : ProjectStore {

    override fun observeAll(): Flow<List<Project>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<Project> = dao.getAll().map { it.toDomain() }

    override suspend fun insert(project: Project): Long = dao.insert(project.toEntity())

    override suspend fun update(project: Project) = dao.update(project.toEntity())

    override suspend fun taskIdsIn(id: Long): List<Long> = dao.taskIdsIn(id)

    override suspend fun deleteWithChildren(id: Long, deleteTasks: Boolean) =
        dao.deleteWithChildren(id, deleteTasks)
}

class RoomBackupStore(private val dao: BackupDao) : BackupStore {

    override suspend fun replaceAll(projects: List<Project>, tasks: List<Task>) = dao.replaceAll(
        projects = projects.map { it.toEntity() },
        tasks = tasks.map { it.toEntity() },
    )
}
