package de.andi1984.cadence.ui

import de.andi1984.cadence.data.AttachmentStore
import de.andi1984.cadence.data.BackupStore
import de.andi1984.cadence.data.BlobStore
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.ProjectStore
import de.andi1984.cadence.data.SectionStore
import de.andi1984.cadence.data.TagStore
import de.andi1984.cadence.data.TaskStore
import de.andi1984.cadence.data.sync.SyncState
import de.andi1984.cadence.data.sync.SyncStore
import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.platform.AttachmentOpener
import de.andi1984.cadence.ui.platform.BackupGateway
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.platform.ReminderScheduler
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.Dispatchers
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

    /**
     * Held open, a tombstone write parks here instead of finishing.
     *
     * The only hook of its kind in these fakes, and it earns that: `CadenceViewModel`'s undo
     * window has a state — *this* delete is being written while *that* one can still be taken
     * back — that a test cannot otherwise stand inside, because every fake here answers without
     * suspending and `runCurrent()` therefore drains a settled delete to completion in the same
     * breath as the one that settled it. Null by default, so no other test sees it.
     */
    var beforeTombstone: (suspend () -> Unit)? = null

    override suspend fun tombstoneWithSubtasks(id: String, at: Instant) {
        beforeTombstone?.invoke()
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

    /** `Task.sq`'s `updateSortOrder`: a row already at that position is not written, so a drag
     *  puts only the rows that moved on the wire. */
    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant) {
        val positions = orders.toMap()
        rows.value = rows.value.map { task ->
            val position = positions[task.id]
            if (task.deletedAt == null && position != null && task.sortOrder != position) {
                task.copy(sortOrder = position, updatedAt = at)
            } else {
                task
            }
        }
    }

    override suspend fun maxSortOrder(projectId: String?): Int? = live()
        .filter { it.parentId == null && it.projectId == projectId }
        .maxOfOrNull { it.sortOrder }
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

    /** See [FakeTaskStore.reorder]. */
    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant) {
        val positions = orders.toMap()
        rows.value = rows.value.map { project ->
            val position = positions[project.id]
            if (project.deletedAt == null && position != null && project.sortOrder != position) {
                project.copy(sortOrder = position, updatedAt = at)
            } else {
                project
            }
        }
    }

    override suspend fun maxSortOrder(parentId: String?): Int? = live()
        .filter { it.parentId == parentId }
        .maxOfOrNull { it.sortOrder }

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

    /** See [FakeTaskStore.reorder]. */
    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant) {
        val positions = orders.toMap()
        rows.value = rows.value.map { section ->
            val position = positions[section.id]
            if (section.deletedAt == null && position != null && section.sortOrder != position) {
                section.copy(sortOrder = position, updatedAt = at)
            } else {
                section
            }
        }
    }

    override suspend fun maxSortOrder(projectId: String): Int? = rows.value
        .filter { it.deletedAt == null && it.projectId == projectId }
        .maxOfOrNull { it.sortOrder }
}

/**
 * An in-memory [AttachmentStore], the same shape as the other fakes here.
 *
 * It was a no-op until the detail screen grew an attachments card; the ViewModel's add and remove
 * paths now have to read back what they wrote, and the repository behind them reclaims blobs by
 * asking this table which hashes are still named.
 */
class FakeAttachmentStore : AttachmentStore {

    private val table = MutableStateFlow<Map<String, Attachment>>(emptyMap())

    fun rows(): List<Attachment> =
        table.value.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override fun observeAll(): Flow<List<Attachment>> = table.map { it.values.toList() }

    override suspend fun byId(id: String): Attachment? = table.value[id]

    override suspend fun forTask(taskId: String): List<Attachment> = table.value.values
        .filter { it.taskId == taskId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun insert(attachment: Attachment) {
        table.value = table.value + (attachment.id to attachment)
    }

    override suspend fun update(attachment: Attachment) = insert(attachment)

    override suspend fun delete(id: String) {
        table.value = table.value - id
    }

    override suspend fun deleteForTasks(taskIds: List<String>) {
        table.value = table.value.filterValues { it.taskId !in taskIds }
    }

    override suspend fun hashesForTasks(taskIds: List<String>): List<String> = table.value.values
        .filter { it.taskId in taskIds }
        .mapNotNull { it.sha256 }
        .distinct()

    override suspend fun stillReferenced(hashes: List<String>): List<String> {
        val named = table.value.values.mapNotNull { it.sha256 }.toSet()
        return hashes.filter { it in named }
    }

    override suspend fun referencedHashes(): List<String> =
        table.value.values.mapNotNull { it.sha256 }.distinct()
}

class FakeBackupStore : BackupStore {
    override suspend fun mergeAll(
        projects: List<Project>,
        sections: List<Section>,
        tags: List<Tag>,
        tasks: List<Task>,
        revivedAt: Instant,
    ) = Unit
}

/**
 * An in-memory [TagStore]. Shorter than [FakeSectionStore] by the thing that makes a tag a tag:
 * tombstoning one writes a single row and rewrites no task, because membership lives on the task.
 */
class FakeTagStore : TagStore {

    private val table = MutableStateFlow<Map<String, Tag>>(emptyMap())

    /** Puts a tag in directly, id and all — the shortcut a test setup wants. */
    fun put(tag: Tag): Tag {
        val stored = if (tag.id.isBlank()) tag.copy(id = "tag-${table.value.size + 1}") else tag
        table.value = table.value + (stored.id to stored)
        return stored
    }

    fun rows(): List<Tag> = table.value.values.filter { it.deletedAt == null }.sortedBy { it.sortOrder }

    override fun observeAll(): Flow<List<Tag>> =
        table.map { m -> m.values.filter { it.deletedAt == null }.sortedBy { it.sortOrder } }

    override suspend fun getAll(): List<Tag> = rows()

    override suspend fun insert(tag: Tag) {
        table.value = table.value + (tag.id to tag)
    }

    override suspend fun update(tag: Tag) {
        table.value = table.value + (tag.id to tag)
    }

    override suspend fun tombstone(id: String, at: Instant) {
        val tag = table.value[id] ?: return
        if (tag.deletedAt != null) return
        table.value = table.value + (id to tag.copy(deletedAt = at, updatedAt = at))
    }

    override suspend fun tombstoneAll(at: Instant) {
        table.value = table.value.mapValues { (_, tag) ->
            if (tag.deletedAt == null) tag.copy(deletedAt = at, updatedAt = at) else tag
        }
    }

    override suspend fun reorder(orders: List<Pair<String, Int>>, at: Instant) {
        var next = table.value
        orders.forEach { (id, position) ->
            val tag = next[id]?.takeIf { it.deletedAt == null } ?: return@forEach
            if (tag.sortOrder == position) return@forEach
            next = next + (id to tag.copy(sortOrder = position, updatedAt = at))
        }
        table.value = next
    }

    override suspend fun maxSortOrder(): Int? = rows().maxOfOrNull { it.sortOrder }
}

/** Records what the platform was asked to schedule or cancel, which is the whole assertion in
 *  the "a delete has to cancel its alarms" tests. */
class RecordingReminderScheduler : ReminderScheduler {

    val cancelled = mutableListOf<String>()
    var lastSynced: List<Task> = emptyList()
        private set
    var lastLeadMinutes: List<Int> = emptyList()
        private set

    override fun sync(tasks: List<Task>, leadMinutes: List<Int>) {
        lastSynced = tasks
        lastLeadMinutes = leadMinutes
    }

    override fun cancel(taskId: String) {
        cancelled += taskId
    }
}

/**
 * Records what was handed to a viewer, and can refuse to open — the state a phone with no PDF
 * reader is in, which the ViewModel answers with a snackbar rather than silence.
 */
class RecordingAttachmentOpener : AttachmentOpener {

    val openedFiles = mutableListOf<java.io.File>()
    val openedLinks = mutableListOf<String>()

    /** Set false to model a machine that has nothing for the type. */
    var canOpen: Boolean = true

    override fun openFile(file: java.io.File, name: String, mimeType: String): Boolean {
        openedFiles += file
        return canOpen
    }

    override fun openLink(url: String): Boolean {
        openedLinks += url
        return canOpen
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

    override fun setReminderLeadMinutes(minutes: List<Int>) {
        _state.value = _state.value.copy(reminderLeadMinutes = minutes)
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

    override suspend fun tagsChangedSince(since: Instant): List<Tag> = emptyList()

    override suspend fun mergeAndAdvance(
        projects: List<Project>,
        sections: List<Section>,
        tags: List<Tag>,
        tasks: List<Task>,
        taskCursor: String?,
        projectCursor: String?,
        sectionCursor: String?,
        tagCursor: String?,
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
    tagStore: FakeTagStore = FakeTagStore(),
    attachmentStore: FakeAttachmentStore = FakeAttachmentStore(),
): CadenceRepository = CadenceRepository(
    taskStore,
    projectStore,
    sectionStore,
    tagStore,
    FakeBackupStore(),
    attachmentStore,
    BlobStore(
        root = Files.createTempDirectory("cadence-ui-blobs").toFile(),
        tmp = Files.createTempDirectory("cadence-ui-blobs-tmp").toFile(),
    ),
    // The blob store's own dispatcher, unconfined here for the same reason the SQLDelight stores
    // take one: a real `Dispatchers.IO` inside `attachmentIndex`'s `flowOn` would put the state
    // flow on a thread `runTest`'s virtual clock does not control, and every assertion after a
    // write would race it.
    Dispatchers.Unconfined,
)
