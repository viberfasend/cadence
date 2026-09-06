package de.andi1984.cadence.ui

import de.andi1984.cadence.data.sync.SyncState
import de.andi1984.cadence.data.sync.SyncStore
import de.andi1984.cadence.domain.backup.BackupOutcome
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Hand-written fakes for the *platform* ports the ViewModel reaches — reminders, the attachment
 * opener, backup I/O, settings, the sync session. No mocking library anywhere in this repo, and
 * these ports are small enough that a fake states the contract more clearly than a stubbing DSL
 * would.
 *
 * Only the platform ports are faked. Storage is not: the ViewModel tests run a real
 * `CadenceRepository` over the real SQLDelight stores on an in-memory database ([TestStores]),
 * because the rules under test — which rows a delete takes with it, which ids come back so their
 * alarms can be cancelled — are the repository's and the store's together, and an in-memory fake
 * of the stores was a second copy of the store contract that nothing pinned.
 */

/** Records what the platform was asked to schedule or cancel, which is the whole assertion in
 *  the "a delete has to cancel its alarms" tests. */
class RecordingReminderScheduler : ReminderScheduler {

    val cancelled = mutableListOf<String>()
    var lastSynced: List<Task> = emptyList()
        private set
    var lastLeadMinutes: List<Int> = emptyList()
        private set
    var lastEnabled: Boolean? = null
        private set

    override fun sync(tasks: List<Task>, leadMinutes: List<Int>, enabled: Boolean) {
        lastSynced = tasks
        lastLeadMinutes = leadMinutes
        lastEnabled = enabled
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
