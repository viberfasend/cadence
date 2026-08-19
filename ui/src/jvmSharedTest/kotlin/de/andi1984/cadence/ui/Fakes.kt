package de.andi1984.cadence.ui

import de.andi1984.cadence.data.AttachmentStore
import de.andi1984.cadence.data.BackupStore
import de.andi1984.cadence.data.BlobStore
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.ProjectStore
import de.andi1984.cadence.data.SectionStore
import de.andi1984.cadence.data.TaskStore
import de.andi1984.cadence.data.sync.SyncState
import de.andi1984.cadence.data.sync.SyncStore
import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.platform.BackupGateway
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.platform.ReminderScheduler
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.nio.file.Files
import java.time.Instant

/**
 * Hand-written fakes for everything the ViewModel reaches, in the same style
 * `:core`'s `CadenceRepositoryTest` uses for the stores — no mocking library anywhere in this
 * repo, and these ports are small enough that a fake states the contract more clearly than a
 * stubbing DSL would.
 *
 * The stores back a **real** [CadenceRepository] rather than the repository itself being faked:
 * the rules under test here — which rows a delete takes with it, which ids come back so their
 * alarms can be cancelled — are the repository's, and a faked one would assert only that the
 * ViewModel called what the test told it to expect.
 */

/** Tombstone-aware in-memory task table. Reads filter tombstones the way every `.sq` read does. */
class FakeTaskStore : TaskStore {

    private val rows = MutableStateFlow<List<Task>>(emptyList())

    /** Every row, tombstoned ones included — what a test asserts against. */
    fun allRows(): List<Task> = rows.value

    fun seed(tasks: List<Task>) {
        rows.value = tasks
    }

    private fun live() = rows.value.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<Task>> = rows.map { all -> all.filter { it.deletedAt == null } }

    override fun observeById(id: String): Flow<Task?> =
        rows.map { all -> all.firstOrNull { it.id == id && it.deletedAt == null } }

    override suspend fun getAll(): List<Task> = live()

    override suspend fun byId(id: String): Task? = live().firstOrNull { it.id == id }

    override suspend fun subtasksOf(parentId: String): List<Task> =
        live().filter { it.parentId == parentId }

    override suspend fun insert(task: Task) {
        rows.value = rows.value + task
    }

    override suspend fun update(task: Task) {
        rows.value = rows.value.map { if (it.id == task.id) task else it }
    }

    override suspend fun tombstoneWithSubtasks(id: String, at: Instant) {
        val doomed = rows.value.filter { it.id == id || it.parentId == id }.map { it.id }.toSet()
        rows.value = rows.value.map {
            if (it.id in doomed && it.deletedAt == null) it.copy(deletedAt = at, updatedAt = at) else it
        }
    }

    override suspend fun tombstoneAll(at: Instant) {
        rows.value = rows.value.map {
            if (it.deletedAt == null) it.copy(deletedAt = at, updatedAt = at) else it
        }
    }

    override suspend fun completeIfOpen(id: String, completedAt: Instant): Int {
        val row = live().firstOrNull { it.id == id } ?: return 0
        if (row.isDone) return 0
        update(row.copy(completedAt = completedAt, updatedAt = completedAt))
        return 1
    }

    override suspend fun reopenIfDone(id: String, updatedAt: Instant): Int {
        val row = live().firstOrNull { it.id == id } ?: return 0
        if (!row.isDone) return 0
        update(row.copy(completedAt = null, updatedAt = updatedAt))
        return 1
    }

    override suspend fun openSuccessorsOf(id: String): List<String> =
        live().filter { it.spawnedFromId == id && !it.isDone }.map { it.id }
}

/** Projects, with the "and everything filed under it" semantics `deleteWithChildren` needs. */
class FakeProjectStore(private val taskStore: FakeTaskStore) : ProjectStore {

    private val rows = MutableStateFlow<List<Project>>(emptyList())

    fun seed(projects: List<Project>) {
        rows.value = projects
    }

    private fun live() = rows.value.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<Project>> =
        rows.map { all -> all.filter { it.deletedAt == null } }

    override suspend fun getAll(): List<Project> = live()

    override suspend fun insert(project: Project) {
        rows.value = rows.value + project
    }

    override suspend fun update(project: Project) {
        rows.value = rows.value.map { if (it.id == project.id) project else it }
    }

    /** The whole family: the project, its subprojects, and every task filed under either —
     *  **subtasks included**, which is the point of the real store returning this list. */
    override suspend fun taskIdsIn(id: String): List<String> {
        val familyIds = familyOf(id)
        return taskStore.allRows()
            .filter { it.deletedAt == null && it.projectId in familyIds }
            .map { it.id }
    }

    override suspend fun tombstoneWithChildren(id: String, deleteTasks: Boolean, at: Instant) {
        val familyIds = familyOf(id)
        val affected = taskIdsIn(id).toSet()
        taskStore.seed(
            taskStore.allRows().map { task ->
                when {
                    task.id !in affected -> task
                    deleteTasks && task.deletedAt == null -> task.copy(deletedAt = at, updatedAt = at)
                    !deleteTasks -> task.copy(projectId = null, sectionId = null, updatedAt = at)
                    else -> task
                }
            },
        )
        rows.value = rows.value.map {
            if (it.id in familyIds && it.deletedAt == null) it.copy(deletedAt = at, updatedAt = at) else it
        }
    }

    override suspend fun tombstoneAll(at: Instant) {
        rows.value = rows.value.map {
            if (it.deletedAt == null) it.copy(deletedAt = at, updatedAt = at) else it
        }
    }

    private fun familyOf(id: String): Set<String> =
        setOf(id) + live().filter { it.parentId == id }.map { it.id }
}

class FakeSectionStore : SectionStore {

    private val rows = MutableStateFlow<List<Section>>(emptyList())

    fun seed(sections: List<Section>) {
        rows.value = sections
    }

    override fun observeAll(): Flow<List<Section>> =
        rows.map { all -> all.filter { it.deletedAt == null } }

    override suspend fun getAll(): List<Section> = rows.value.filter { it.deletedAt == null }

    override suspend fun insert(section: Section) {
        rows.value = rows.value + section
    }

    override suspend fun update(section: Section) {
        rows.value = rows.value.map { if (it.id == section.id) section else it }
    }

    override suspend fun tombstone(id: String, at: Instant) {
        rows.value = rows.value.map {
            if (it.id == id && it.deletedAt == null) it.copy(deletedAt = at, updatedAt = at) else it
        }
    }

    override suspend fun tombstoneAll(at: Instant) {
        rows.value = rows.value.map {
            if (it.deletedAt == null) it.copy(deletedAt = at, updatedAt = at) else it
        }
    }
}

/** Attachments are not what any of these tests are about; this only has to be a working no-op. */
class FakeAttachmentStore : AttachmentStore {
    override fun observeAll(): Flow<List<Attachment>> = MutableStateFlow(emptyList<Attachment>())
    override suspend fun byId(id: String): Attachment? = null
    override suspend fun forTask(taskId: String): List<Attachment> = emptyList()
    override suspend fun insert(attachment: Attachment) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun deleteForTasks(taskIds: List<String>) = Unit
    override suspend fun hashesForTasks(taskIds: List<String>): List<String> = emptyList()
    override suspend fun stillReferenced(hashes: List<String>): List<String> = emptyList()
    override suspend fun referencedHashes(): List<String> = emptyList()
}

class FakeBackupStore : BackupStore {
    override suspend fun mergeAll(
        projects: List<Project>,
        sections: List<Section>,
        tasks: List<Task>,
        revivedAt: Instant,
    ) = Unit
}

/** Records what the platform was asked to schedule or cancel, which is the whole assertion in
 *  the "a delete has to cancel its alarms" tests. */
class RecordingReminderScheduler : ReminderScheduler {

    val cancelled = mutableListOf<String>()
    var lastSynced: List<Task> = emptyList()
        private set

    override fun sync(tasks: List<Task>) {
        lastSynced = tasks
    }

    override fun cancel(taskId: String) {
        cancelled += taskId
    }
}

class FakeBackupGateway : BackupGateway {

    var exported: BackupOutcome = BackupOutcome.Exported(projects = 0, tasks = 0, exportedAt = Instant.EPOCH)
    var imports: List<BackupOutcome> = emptyList()

    val importedTargets = mutableListOf<BackupTarget>()

    override suspend fun export(target: BackupTarget): BackupOutcome = exported

    override suspend fun import(target: BackupTarget): BackupOutcome {
        importedTargets += target
        return imports.getOrElse(importedTargets.size - 1) { imports.lastOrNull() ?: exported }
    }
}

class FakeSettingsStore(initial: CadenceSettings = CadenceSettings()) : SettingsStore {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<CadenceSettings> = _state.asStateFlow()

    override fun setTheme(theme: ThemeChoice) {
        _state.value = _state.value.copy(theme = theme)
    }

    override fun setDensity(density: Density) {
        _state.value = _state.value.copy(density = density)
    }

    override fun setSortMode(sortMode: SortMode) {
        _state.value = _state.value.copy(sortMode = sortMode)
    }

    override fun setShowCompleted(show: Boolean) {
        _state.value = _state.value.copy(showCompleted = show)
    }

    override fun setRemindersEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(remindersEnabled = enabled)
    }
}

/**
 * A [SyncStore] that remembers nothing but a session, which is all the engine needs to stay
 * signed out for the length of a ViewModel test: signed out, every round is a no-op and no
 * request is ever made.
 */
class FakeSyncStore : SyncStore {

    private var current = SyncState()

    override suspend fun state(): SyncState = current

    override suspend fun setSession(session: String?) {
        current = current.copy(session = session)
    }

    override suspend fun tasksChangedSince(since: Instant): List<Task> = emptyList()

    override suspend fun projectsChangedSince(since: Instant): List<Project> = emptyList()

    override suspend fun sectionsChangedSince(since: Instant): List<Section> = emptyList()

    override suspend fun mergeAndAdvance(
        projects: List<Project>,
        sections: List<Section>,
        tasks: List<Task>,
        taskCursor: String?,
        projectCursor: String?,
        sectionCursor: String?,
    ) = Unit

    override suspend fun setPushWatermark(at: Instant) {
        current = current.copy(pushWatermark = at)
    }

    override suspend fun setLastSyncedAt(at: Instant) {
        current = current.copy(lastSyncedAt = at)
    }

    override suspend fun collectTombstones(before: Instant, at: Instant) = Unit

    override suspend fun clear() {
        current = SyncState()
    }
}

/** A real repository over the fakes above — the same arrangement `CadenceRepositoryTest` uses. */
fun repositoryOver(
    taskStore: FakeTaskStore,
    projectStore: FakeProjectStore,
    sectionStore: FakeSectionStore,
): CadenceRepository = CadenceRepository(
    taskStore,
    projectStore,
    sectionStore,
    FakeBackupStore(),
    FakeAttachmentStore(),
    BlobStore(
        root = Files.createTempDirectory("cadence-ui-blobs").toFile(),
        tmp = Files.createTempDirectory("cadence-ui-blobs-tmp").toFile(),
    ),
)
