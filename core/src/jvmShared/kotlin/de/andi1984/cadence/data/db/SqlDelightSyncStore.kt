package de.andi1984.cadence.data.db

import de.andi1984.cadence.data.sync.SyncState
import de.andi1984.cadence.data.sync.SyncStore
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** [SyncStore] over the single `syncStateRow` and the two tombstone-aware views in `Task.sq` and
 *  `Project.sq` — the only queries in the schema that deliberately do not filter tombstones. */
class SqlDelightSyncStore(
    private val database: CadenceDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncStore {

    private val queries = database.syncStateQueries

    override suspend fun state(): SyncState = withContext(ioDispatcher) {
        val row = queries.select().executeAsOne()
        SyncState(
            session = row.session,
            taskCursor = row.taskCursor,
            projectCursor = row.projectCursor,
            pushWatermark = Instant.ofEpochMilli(row.pushWatermark),
            lastSyncedAt = row.lastSyncedAt?.let { Instant.ofEpochMilli(it) },
            lastSweepAt = row.lastSweepAt?.let { Instant.ofEpochMilli(it) },
        )
    }

    override suspend fun setSession(session: String?): Unit = withContext(ioDispatcher) {
        queries.setSession(session)
    }

    override suspend fun tasksChangedSince(since: Instant): List<Task> =
        withContext(ioDispatcher) {
            if (since == Instant.EPOCH) {
                database.taskQueries.selectAllIncludingDeleted(mapper = ::toTask).executeAsList()
            } else {
                database.taskQueries
                    .selectChangedSince(since.toEpochMilli(), mapper = ::toTask)
                    .executeAsList()
            }
        }

    override suspend fun projectsChangedSince(since: Instant): List<Project> =
        withContext(ioDispatcher) {
            if (since == Instant.EPOCH) {
                database.projectQueries
                    .selectAllIncludingDeleted(mapper = ::toProject)
                    .executeAsList()
            } else {
                database.projectQueries
                    .selectChangedSince(since.toEpochMilli(), mapper = ::toProject)
                    .executeAsList()
            }
        }

    override suspend fun mergeAndAdvance(
        projects: List<Project>,
        tasks: List<Task>,
        taskCursor: String?,
        projectCursor: String?,
    ): Unit = withContext(ioDispatcher) {
        database.transaction {
            database.mergeRecords(projects, tasks)
            if (taskCursor != null) queries.setTaskCursor(taskCursor)
            if (projectCursor != null) queries.setProjectCursor(projectCursor)
        }
    }

    override suspend fun setPushWatermark(at: Instant): Unit = withContext(ioDispatcher) {
        queries.setPushWatermark(at.toEpochMilli())
    }

    override suspend fun setLastSyncedAt(at: Instant): Unit = withContext(ioDispatcher) {
        queries.setLastSyncedAt(at.toEpochMilli())
    }

    override suspend fun collectTombstones(before: Instant, at: Instant): Unit =
        withContext(ioDispatcher) {
            database.transaction {
                database.taskQueries.collectTombstones(before.toEpochMilli())
                database.projectQueries.collectTombstones(before.toEpochMilli())
                queries.setLastSweepAt(at.toEpochMilli())
            }
        }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        queries.clearSync()
    }
}
