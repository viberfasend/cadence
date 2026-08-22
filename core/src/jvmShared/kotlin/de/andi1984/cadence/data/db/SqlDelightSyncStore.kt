package de.andi1984.cadence.data.db

import de.andi1984.cadence.data.sync.SyncState
import de.andi1984.cadence.data.sync.SyncStore
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** [SyncStore] over the single `syncStateRow` and the tombstone-aware views in `Task.sq`,
 *  `Project.sq`, `Section.sq` and `Tag.sq` — the only queries in the schema that deliberately do
 *  not filter tombstones. */
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
            sectionCursor = row.sectionCursor,
            tagCursor = row.tagCursor,
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

    override suspend fun sectionsChangedSince(since: Instant): List<Section> =
        withContext(ioDispatcher) {
            if (since == Instant.EPOCH) {
                database.sectionQueries
                    .selectAllIncludingDeleted(mapper = ::toSection)
                    .executeAsList()
            } else {
                database.sectionQueries
                    .selectChangedSince(since.toEpochMilli(), mapper = ::toSection)
                    .executeAsList()
            }
        }

    override suspend fun tagsChangedSince(since: Instant): List<Tag> =
        withContext(ioDispatcher) {
            if (since == Instant.EPOCH) {
                database.tagQueries.selectAllIncludingDeleted(mapper = ::toTag).executeAsList()
            } else {
                database.tagQueries
                    .selectChangedSince(since.toEpochMilli(), mapper = ::toTag)
                    .executeAsList()
            }
        }

    override suspend fun mergeAndAdvance(
        projects: List<Project>,
        sections: List<Section>,
        tags: List<Tag>,
        tasks: List<Task>,
        taskCursor: String?,
        projectCursor: String?,
        sectionCursor: String?,
        tagCursor: String?,
    ): Unit = withContext(ioDispatcher) {
        database.transaction {
            database.mergeRecords(projects, sections, tags, tasks)
            if (taskCursor != null) queries.setTaskCursor(taskCursor)
            if (projectCursor != null) queries.setProjectCursor(projectCursor)
            if (sectionCursor != null) queries.setSectionCursor(sectionCursor)
            if (tagCursor != null) queries.setTagCursor(tagCursor)
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
                database.sectionQueries.collectTombstones(before.toEpochMilli())
                database.tagQueries.collectTombstones(before.toEpochMilli())
                queries.setLastSweepAt(at.toEpochMilli())
            }
        }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        queries.clearSync()
    }
}
