package de.andi1984.cadence.ui

import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.RepositoryResult
import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.data.sync.SignInResult
import de.andi1984.cadence.data.sync.SyncFailure
import de.andi1984.cadence.data.sync.SyncStatus
import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.SubtaskProgress
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.domain.model.withoutSupersededOccurrences
import de.andi1984.cadence.domain.parse.ParsedQuickAdd
import de.andi1984.cadence.ui.platform.BackupGateway
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.platform.ReminderScheduler
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SignInError
import de.andi1984.cadence.ui.settings.SyncUiState
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * A destructive action the user just took, held back from the database for [CadenceViewModel.UNDO_WINDOW]
 * so an undo costs no write at all — it only cancels the deferred job that would have committed it.
 */
sealed class UndoAction {
    /** Every id the commit will tombstone, so the pending set can hide exactly those rows. */
    abstract val ids: Set<String>

    /** Deleting a task tombstones it and its subtasks together. */
    data class DeleteTask(val task: Task, val subtasks: List<Task>) : UndoAction() {
        /** Every id the commit will tombstone — the row and its steps. */
        override val ids: Set<String> = setOf(task.id) + subtasks.map { it.id }
    }

    /** Deleting a project tombstones it and (optionally) its subprojects and tasks. */
    data class DeleteProject(
        val project: Project,
        val tasks: List<Task>,
        val deleteTasks: Boolean,
    ) : UndoAction() {
        /** Every id the commit will tombstone. Tasks are only included when [deleteTasks] is
         *  set, the same way [CadenceRepository.deleteProject] only tombstones them then. */
        override val ids: Set<String> =
            if (deleteTasks) setOf(project.id) + tasks.map { it.id } else setOf(project.id)
    }

    /**
     * The Settings danger zone: every task and every project at once.
     *
     * The ids are what the user saw on screen when they confirmed, held so the rows can be hidden
     * for the undo window and un-hidden again by an undo. The commit itself wipes whatever is
     * stored at the moment it runs, not this list — a row merged in from a pull during those few
     * seconds goes too, which is what "delete everything" has to mean for the wipe to be the same
     * on every device.
     */
    data class DeleteEverything(
        val taskIds: List<String>,
        val projectIds: List<String>,
    ) : UndoAction() {
        override val ids: Set<String> = (taskIds + projectIds).toSet()
    }
}

/**
 * A message the shell raises as a snackbar.
 *
 * The text is a resource rather than ready-made prose, so no user-visible string lives in Kotlin
 * (the project rule). When [undoAction] is set the snackbar shows a **Undo** action button; the
 * action is otherwise informational.
 */
sealed class SnackbarMessage {
    /** The delete this snackbar can undo, or null when the message is informational. */
    abstract val undoAction: UndoAction?

    /** A plain string resource, with optional format args. */
    data class Text(
        val text: StringResource,
        val args: List<Any> = emptyList(),
        override val undoAction: UndoAction? = null,
    ) : SnackbarMessage()

    /** A plural resource resolved against [count], with optional further format args. */
    data class Counted(
        val plural: org.jetbrains.compose.resources.PluralStringResource,
        val count: Int,
        val args: List<Any> = emptyList(),
        override val undoAction: UndoAction? = null,
    ) : SnackbarMessage()
}

/** A row in a task list — a root task, or a subtask shown inline beneath an expanded one. */
data class TaskListRow(val task: Task, val isSubtaskRow: Boolean)

data class CadenceUiState(
    val tasks: List<Task> = emptyList(),
    val projects: List<Project> = emptyList(),
    val sections: List<Section> = emptyList(),
    val settings: CadenceSettings = CadenceSettings(),
    /** Result of the last export or import, shown once under the Settings buttons. */
    val backupOutcome: BackupOutcome? = null,
    /** Sign-in state and where the last sync round got to. */
    val sync: SyncUiState = SyncUiState(),
    /** Current snackbar message to display, if any. */
    val snackbarMessage: SnackbarMessage? = null,
    /**
     * Ids of tasks and projects whose destructive write is held back while an undo is on offer.
     *
     * The rows are filtered out of every list below the moment one lands here — so the user
     * sees the item disappear immediately — even though no database transaction has happened
     * yet. Undo clears the set, and the rows reappear straight from the flow that was feeding
     * them all along.
     */
    val pendingDeleteIds: Set<String> = emptySet(),
) {
    fun project(id: String?): Project? = id?.let { projectId ->
        projects.firstOrNull { it.id == projectId }
    }

    /** "Home / Finance" for a task's project, or null for Inbox items. */
    fun projectLabel(task: Task): String? = projectPath(project(task.projectId), projects)

    fun openTasks(): List<Task> = tasks.filter { !it.isDone }

    fun overdue(today: LocalDate): List<Task> = tasks.filter { it.isOverdue(today) }

    /**
     * Tasks that stand on their own.
     *
     * The lists that stand for a container — the Inbox, a project — show these, because the
     * parent already speaks for its steps there. The date-driven views (Today, Upcoming, Search)
     * deliberately do not filter: a subtask with its own due date is work for that day, and it
     * carries its parent's title as context.
     *
     * The same reasoning drops a recurring occurrence that has already been replaced: its
     * successor speaks for it here. Only these undated lists need that — Today and Upcoming are
     * scoped to a day, and Search is meant to reach history.
     */
    fun rootTasks(): List<Task> = tasks
        .withoutSupersededOccurrences()
        .filter { !it.isSubtask }

    fun inboxTasks(): List<Task> = rootTasks().filter { it.isInbox }

    /** The steps under a task, in the order they were added. */
    fun subtasks(parentId: String): List<Task> = tasks
        .filter { it.parentId == parentId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    /** Null when a task has no checklist at all, so rows can leave the chip out entirely. */
    fun subtaskProgress(parentId: String): SubtaskProgress? {
        val steps = subtasks(parentId)
        return if (steps.isEmpty()) {
            null
        } else {
            SubtaskProgress(done = steps.count { it.isDone }, total = steps.size)
        }
    }

    fun parentOf(task: Task): Task? =
        task.parentId?.let { id -> tasks.firstOrNull { it.id == id } }

    /**
     * [roots] with the subtasks of any parent in [expandedIds] spliced in directly below it —
     * the "show subtasks" toggle on a row in a container list (Inbox, a project). Container
     * lists otherwise never show a subtask at all ([rootTasks] drops them), so this is the one
     * place they become visible outside the task's own detail screen.
     */
    fun expandedRows(roots: List<Task>, expandedIds: Set<String>): List<TaskListRow> =
        roots.flatMap { task ->
            val children = if (task.id in expandedIds) subtasks(task.id) else emptyList()
            listOf(TaskListRow(task, isSubtaskRow = false)) +
                children.map { TaskListRow(it, isSubtaskRow = true) }
        }

    /** Subprojects of a project, in the order they were added. */
    fun subprojects(parentId: String): List<Project> = projects
        .filter { it.parentId == parentId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    /** Tasks in a project, including everything filed under its subprojects. */
    fun tasksIn(projectId: String): List<Task> {
        val childIds = subprojects(projectId).map { it.id }.toSet()
        return rootTasks().filter { it.projectId == projectId || it.projectId in childIds }
    }

    fun section(id: String?): Section? = id?.let { sectionId ->
        sections.firstOrNull { it.id == sectionId }
    }

    /** The bands of a project's list, in the order they are drawn. */
    fun sectionsIn(projectId: String): List<Section> = sections
        .filter { it.projectId == projectId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    /**
     * One band of a project's list: [sectionId]'s tasks, or the ungrouped band for null.
     *
     * The ungrouped band deliberately catches more than `sectionId == null`. A project's screen
     * also shows the tasks of its subprojects, and one of those may be filed under a section of
     * *its own* project — a heading this list never draws. Matching only nulls would leave that
     * task in no band at all and drop it off the screen, which is the same failure the "deleting a
     * project never silently hides tasks" rule exists to prevent.
     */
    fun tasksInSection(projectId: String, sectionId: String?): List<Task> {
        val tasks = tasksIn(projectId)
        if (sectionId != null) return tasks.filter { it.sectionId == sectionId }
        val drawn = sectionsIn(projectId).mapTo(mutableSetOf()) { it.id }
        return tasks.filter { it.sectionId == null || it.sectionId !in drawn }
    }

    /**
     * Projects that can be parents for a new project.
     * A project cannot be nested under itself or any of its descendants.
     */
    fun nestingCandidates(exclude: Project?): List<Project> {
        if (exclude == null) return projects.filter { it.parentId == null }

        // Find all descendants of the excluded project
        val descendants = mutableSetOf<String>()
        var current = listOf(exclude)
        while (current.isNotEmpty()) {
            val childIds = current.map { it.id }
            descendants.addAll(childIds)
            current = projects.filter { it.parentId in childIds }
        }

        // Return root projects that are not the excluded project or its descendants
        return projects.filter {
            it.parentId == null && it.id != exclude.id && it.id !in descendants
        }
    }

}

/**
 * The one ViewModel for the whole app — a plain class, not an `androidx.lifecycle.ViewModel`.
 *
 * There is no ViewModel on the desktop, and the Android shell only wants one thing from the
 * lifecycle library anyway: something that survives a rotation and is told when to stop. That is
 * [scope] plus [close], which the shell wires to whatever its platform calls those (`:app` hangs
 * both off an `androidx.lifecycle.ViewModel` of its own; `:app-desktop` will hang them off the
 * window). Keeping the class free of the dependency is what lets the screens above it stay free
 * of it too.
 */
class CadenceViewModel(
    private val repository: CadenceRepository,
    private val settingsStore: SettingsStore,
    private val reminderScheduler: ReminderScheduler,
    private val backupGateway: BackupGateway,
    private val syncEngine: CadenceSyncEngine,
    private val scope: CoroutineScope,
    /**
     * How often to sync with nothing prompting it, or null for never — the safety net for a
     * socket that believes it is connected and is not (ADR 0002, decision 11).
     *
     * `:app-desktop` passes 15 minutes and `:app-android` passes nothing: a phone is stale only
     * while nobody is looking at it, and it syncs on foreground before the user reads a row, so
     * a background round there would buy a fresher database nobody is reading and pay for it in
     * a doze-mode fight.
     */
    syncPollInterval: Duration? = null,
) {

    private val backupOutcome = MutableStateFlow<BackupOutcome?>(null)
    private val snackbarMessage = MutableStateFlow<SnackbarMessage?>(null)

    /**
     * Ids of tasks/projects whose delete is held back while an undo is on the table.
     *
     * The deferred [commitPendingDelete] job sits in [pendingDeleteJob]; cancelling it on an
     * undo is the whole transaction — nothing is written, nothing is tombstoned, and the rows
     * the flow was already emitting simply stop being filtered out.
     */
    private val pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())
    private var pendingDeleteJob: Job? = null
    /** The action [pendingDeleteJob] will commit when its window elapses, captured so a second
     *  delete can commit it out of band rather than leave its rows filtered forever. */
    private var pendingDeleteAction: UndoAction? = null

    /** What a sign-in attempt is doing, folded together with the engine's own status — the
     *  screen wants one value, and `combine` takes five flows at most. */
    private val signInState = MutableStateFlow(SyncUiState())

    private val syncState = combine(
        syncEngine.status,
        signInState,
    ) { status, attempt -> attempt.copy(status = status) }

    /** Tripled so the state combine stays inside `combine`'s five-flow overload — the
     *  attachments feature will want a sixth, the same way pairing did before it. */
    private data class Transient(
        val backup: BackupOutcome?,
        val pendingDelete: Set<String>,
        val snackbar: SnackbarMessage?,
    )

    private val transient = combine(
        backupOutcome,
        pendingDeleteIds,
        snackbarMessage,
    ) { backup, pending, snackMessage ->
        Transient(backup, pending, snackMessage)
    }

    /**
     * The three record flows, folded into one for the same reason [Transient] exists: `combine`
     * takes five flows at most, and sections would have been the sixth. They belong together
     * anyway — a section without its project, or a task without its section, is half a screen.
     */
    private data class Records(
        val tasks: List<Task>,
        val projects: List<Project>,
        val sections: List<Section>,
    )

    private val records = combine(
        repository.tasks,
        repository.projects,
        repository.sections,
    ) { tasks, projects, sections -> Records(tasks, projects, sections) }

    val state: StateFlow<CadenceUiState> = combine(
        records,
        settingsStore.state,
        syncState,
        transient,
    ) { r, settings, sync, t ->
        CadenceUiState(
            // Pending-deletes are hidden here, at the source, so every list and every direct
            // read of `state.tasks` drops them at once — the delete is not in the database yet,
            // and an undo simply clears the set and lets the flow re-emit the rows.
            tasks = if (t.pendingDelete.isEmpty()) r.tasks else r.tasks.filter { it.id !in t.pendingDelete },
            projects = if (t.pendingDelete.isEmpty()) r.projects else r.projects.filter { it.id !in t.pendingDelete },
            // Sections follow their project: a heading whose project is being deleted must not
            // outlive it on screen for the length of the undo window.
            sections = if (t.pendingDelete.isEmpty()) {
                r.sections
            } else {
                r.sections.filter { it.projectId !in t.pendingDelete }
            },
            pendingDeleteIds = t.pendingDelete,
            settings = settings,
            backupOutcome = t.backup,
            sync = sync,
            snackbarMessage = t.snackbar,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CadenceUiState(),
    )

    /**
     * A write happened here — arm the debounce (ADR 0002, decision 11).
     *
     * Deliberately a signal every mutation *sends*, rather than something derived from
     * `repository.tasks`: a row merged in from a pull lands in that flow exactly like a local
     * edit does, and a debounce watching it would have the two devices pushing each other awake
     * forever.
     */
    private val writes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** One event per failed round, for the shell to raise a snackbar from. */
    val syncFailures: Flow<SyncFailure> = syncEngine.failures

    init {
        startWriteDebounce()
        if (syncPollInterval != null) startPoll(syncPollInterval)

        // Reminders reconcile on every emission, and on whether this device fires them at all:
        // with the task list shared between two devices, both would otherwise go off for the same
        // task at the same minute (ADR 0002, decision 9). Switching them off hands the scheduler
        // the same tasks with their reminder times stripped, so it *cancels* what it had
        // scheduled — passing an empty list would leave those alarms standing.
        scope.launch {
            combine(repository.tasks, settingsStore.state) { tasks, settings ->
                if (settings.remindersEnabled) tasks else tasks.map { it.copy(reminderTime = null) }
            }.collect { tasks -> reminderScheduler.sync(tasks) }
        }
    }

    // ── Tasks ────────────────────────────────────────────────────────────────────────

    fun toggleTask(task: Task) = scope.launch {
        // Reopening a recurring task takes the occurrence its completion inserted back out, and
        // an alarm outlives the row it belongs to unless it is cancelled here — sync only ever
        // sees the tasks that still exist.
        repository.setCompleted(task, !task.isDone).forEach { reminderScheduler.cancel(it) }
        armSync()
    }

    fun saveTask(task: Task) = scope.launch {
        repository.upsertTask(task)
        armSync()
    }

    /**
     * Deletes a task — *deferred*.
     *
     * The row and its subtasks vanish from every list at once (they land in
     * [CadenceUiState.pendingDeleteIds]), but the actual tombstone write is held back for
     * [UNDO_WINDOW]: an undo in that window only cancels the pending job, so it costs no database
     * transaction at all. Only when the window elapses does [commitPendingDelete] run the real
     * delete and arm sync.
     */
    fun deleteTask(task: Task) = scope.launch {
        val subtasks = state.value.subtasks(task.id)
        offerUndo(
            UndoAction.DeleteTask(task, subtasks),
            count = 1 + subtasks.size,
        )
    }

    fun addSubtask(parent: Task, title: String) = scope.launch {
        when (val result = repository.addSubtask(parent, title)) {
            is RepositoryResult.Success -> {
                // Success - the subtask was added
                // No action needed as the Flow will update automatically
                armSync()
            }
            is RepositoryResult.Error -> {
                showSnackbar(Res.string.snackbar_error, listOf(result.message))
            }
            RepositoryResult.ValidationError -> {
                showSnackbar(Res.string.snackbar_subtask_invalid)
            }
        }
    }

    fun setPriority(task: Task, priority: Priority) = saveTask(task.copy(priority = priority))

    fun setDueDate(task: Task, dueDate: LocalDate?) = scope.launch {
        repository.setDueDate(task, dueDate)
        armSync()
    }

    fun setDueTime(task: Task, dueTime: LocalTime?) = saveTask(task.copy(dueTime = dueTime))

    fun setReminder(task: Task, reminder: LocalTime?) = saveTask(task.copy(reminderTime = reminder))

    fun setRecurrence(task: Task, rule: RecurrenceRule?) = saveTask(task.copy(recurrence = rule))

    fun setProject(task: Task, projectId: String?) = scope.launch {
        repository.moveToProject(task, projectId)
        armSync()
    }

    /** Files a task under one of its project's sections, or under none. The steps follow it. */
    fun setSection(task: Task, sectionId: String?) = scope.launch {
        repository.moveToSection(task, sectionId)
        armSync()
    }

    fun snooze(task: Task, days: Long = 1L) = scope.launch {
        repository.shiftDueDate(task, days)
        armSync()
    }

    fun rescheduleOverdue() = scope.launch {
        repository.rescheduleOverdueToToday()
        armSync()
    }

    /** Creates the task the quick-add sheet parsed out of the typed line. */
    fun addParsedTask(parsed: ParsedQuickAdd, fallbackProjectId: String? = null) =
        scope.launch {
            if (parsed.title.isBlank()) return@launch
            repository.upsertTask(
                Task(
                    title = parsed.title,
                    priority = parsed.priority ?: Priority.DEFAULT,
                    projectId = parsed.projectId ?: fallbackProjectId,
                    dueDate = parsed.dueDate,
                    dueTime = parsed.dueTime,
                    recurrence = parsed.recurrence,
                ),
            )
            armSync()
        }

    // ── Projects ─────────────────────────────────────────────────────────────────────

    fun addProject(name: String, colorHex: String, parentId: String?) = scope.launch {
        if (name.isBlank()) {
            showSnackbar(Res.string.snackbar_project_name_empty)
            return@launch
        }

        val order = state.value.projects.count { it.parentId == parentId }
        val project = Project(name = name.trim(), colorHex = colorHex, parentId = parentId, sortOrder = order)

        when (val result = repository.upsertProject(project)) {
            is RepositoryResult.Success -> {
                // Success - project was created
                armSync()
            }
            is RepositoryResult.Error -> {
                showSnackbar(Res.string.snackbar_error, listOf(result.message))
            }
            RepositoryResult.ValidationError -> {
                showSnackbar(Res.string.snackbar_project_name_empty)
            }
        }
    }

    fun editProject(project: Project, name: String, colorHex: String, parentId: String?) = scope.launch {
        if (name.isBlank()) {
            showSnackbar(Res.string.snackbar_project_name_empty)
            return@launch
        }

        val moved = parentId != project.parentId
        val order = if (moved) {
            state.value.projects.count { it.parentId == parentId && it.id != project.id }
        } else {
            project.sortOrder
        }

        val updatedProject = project.copy(
            name = name.trim(),
            colorHex = colorHex,
            parentId = parentId,
            sortOrder = order
        )

        when (val result = repository.upsertProject(updatedProject)) {
            is RepositoryResult.Success -> {
                // Success - project was updated
                armSync()
            }
            is RepositoryResult.Error -> {
                showSnackbar(Res.string.snackbar_error, listOf(result.message))
            }
            RepositoryResult.ValidationError -> {
                showSnackbar(Res.string.snackbar_project_name_empty)
            }
        }
    }

    /**
     * Deletes a project — *deferred*, exactly like [deleteTask].
     *
     * Tasks that will follow the project into the tombstone (when [deleteTasks] is set) are hidden
     * too; tasks that would fall back to the Inbox are left in place, because they are not being
     * deleted. The deferred commit runs the real [CadenceRepository.deleteProject] only if the
     * undo window elapses.
     */
    fun deleteProject(project: Project, deleteTasks: Boolean = false) = scope.launch {
        val tasksInProject = if (deleteTasks) state.value.tasksIn(project.id) else emptyList()
        offerUndo(
            UndoAction.DeleteProject(project, tasksInProject, deleteTasks),
            count = if (deleteTasks) 1 + tasksInProject.size else 1,
        )
    }

    // ── Sections ─────────────────────────────────────────────────────────────────────

    /** Adds a band to the end of a project's list. */
    fun addSection(projectId: String, name: String) = scope.launch {
        if (name.isBlank()) {
            showSnackbar(Res.string.snackbar_section_name_empty)
            return@launch
        }
        val order = state.value.sectionsIn(projectId).size
        val section = Section(projectId = projectId, name = name.trim(), sortOrder = order)
        when (val result = repository.upsertSection(section)) {
            is RepositoryResult.Success -> armSync()
            is RepositoryResult.Error -> showSnackbar(Res.string.snackbar_error, listOf(result.message))
            RepositoryResult.ValidationError -> showSnackbar(Res.string.snackbar_section_name_empty)
        }
    }

    fun renameSection(section: Section, name: String) = scope.launch {
        if (name.isBlank()) {
            showSnackbar(Res.string.snackbar_section_name_empty)
            return@launch
        }
        when (val result = repository.upsertSection(section.copy(name = name.trim()))) {
            is RepositoryResult.Success -> armSync()
            is RepositoryResult.Error -> showSnackbar(Res.string.snackbar_error, listOf(result.message))
            RepositoryResult.ValidationError -> showSnackbar(Res.string.snackbar_section_name_empty)
        }
    }

    /**
     * Removes a band. Its tasks stay in the project and lose only the heading.
     *
     * Written straight through rather than deferred behind the undo window the three deletes
     * above use: nothing disappears here. The rows the section grouped are all still on the same
     * screen a moment later, one band higher — there is no work to rescue, so there is nothing
     * for an undo to give back.
     */
    fun deleteSection(section: Section) = scope.launch {
        repository.deleteSection(section.id)
        armSync()
    }

    // ── Settings ─────────────────────────────────────────────────────────────────────

    /**
     * Deletes every task and every project — the Settings danger zone.
     *
     * Deferred like the other two deletes, so the same undo covers it: the lists empty at once
     * and the write waits out [UNDO_WINDOW]. The confirmation dialog is what makes this
     * deliberate; the undo window is what makes it survivable.
     *
     * A wipe is a delete like any other, so it travels: the tombstones push on the next round and
     * the other devices empty too. That is the whole reason it cannot be a `DELETE`.
     */
    fun wipeEverything() = scope.launch {
        val snapshot = state.value
        if (snapshot.tasks.isEmpty() && snapshot.projects.isEmpty()) return@launch
        offerUndo(
            UndoAction.DeleteEverything(
                taskIds = snapshot.tasks.map { it.id },
                projectIds = snapshot.projects.map { it.id },
            ),
            count = snapshot.tasks.size + snapshot.projects.size,
        )
    }

    fun setSortMode(mode: SortMode) = settingsStore.setSortMode(mode)

    fun setTheme(theme: ThemeChoice) = settingsStore.setTheme(theme)

    fun setDensity(density: Density) = settingsStore.setDensity(density)

    fun setShowCompleted(show: Boolean) = settingsStore.setShowCompleted(show)

    fun setRemindersEnabled(enabled: Boolean) = settingsStore.setRemindersEnabled(enabled)

    // ── Backup ───────────────────────────────────────────────────────────────────────

    fun exportBackup(target: BackupTarget) = scope.launch {
        backupOutcome.value = backupGateway.export(target)
    }

    /**
     * Folds the files into what is already here; reminders resync themselves, and the rows the
     * merge wrote travel to the other devices like any other edit.
     *
     * Several files are imported one after another rather than together: each merge is its own
     * transaction, and one unreadable file among twenty must not cost the other nineteen. The
     * counts are added up so Settings still reports a single sentence, and the files that could
     * not be read are counted rather than swallowed. A run where *nothing* could be read reports
     * the first failure itself — "0 tasks imported" would say nothing about why.
     */
    fun importBackup(targets: List<BackupTarget>) = scope.launch {
        if (targets.isEmpty()) return@launch
        val outcomes = targets.map { backupGateway.import(it) }
        val imported = outcomes.filterIsInstance<BackupOutcome.Imported>()
        backupOutcome.value = if (imported.isEmpty()) {
            outcomes.first()
        } else {
            BackupOutcome.Imported(
                projects = imported.sumOf { it.projects },
                tasks = imported.sumOf { it.tasks },
                exportedAt = imported.firstNotNullOfOrNull { it.exportedAt },
                files = imported.size,
                unreadableFiles = outcomes.size - imported.size,
            )
        }
        armSync()
    }

    fun clearBackupOutcome() {
        backupOutcome.value = null
    }

    // ── Sync ─────────────────────────────────────────────────────────────────────────

    /** Signs in and immediately syncs: the point of signing in is the data, and making the user
     *  press a second button to get it would be asking them to finish the job by hand. */
    fun signIn(email: String, password: String) = scope.launch {
        signInState.value = SyncUiState(signingIn = true)
        when (val result = syncEngine.signIn(email, password)) {
            SignInResult.Ok -> {
                signInState.value = SyncUiState()
                syncEngine.syncOnce()
            }
            SignInResult.WrongCredentials ->
                signInState.value = SyncUiState(error = SignInError.WRONG_CREDENTIALS)
            SignInResult.Offline ->
                signInState.value = SyncUiState(error = SignInError.OFFLINE)
            is SignInResult.Failed ->
                signInState.value = SyncUiState(error = SignInError.SERVER)
        }
    }

    /** The manual gesture — Settings' button, the pull on Android, `Ctrl`/`Cmd`+`R` on the
     *  desktop. Signed out it reaches the engine and does nothing, which is the whole contract. */
    fun syncNow() = scope.launch { syncEngine.syncOnce() }

    /**
     * Waits for the writing to stop and then syncs once, rather than syncing per keystroke.
     *
     * [debounce] restarts its timer on every signal, so a burst of edits — three checkboxes, a
     * triage pass — costs one round two seconds after the last of them.
     */
    @OptIn(FlowPreview::class)
    private fun startWriteDebounce() = scope.launch {
        writes.debounce(WRITE_DEBOUNCE.toMillis()).collect { syncEngine.syncOnce() }
    }

    private fun startPoll(interval: Duration) = scope.launch {
        while (true) {
            delay(interval.toMillis())
            syncEngine.syncOnce()
        }
    }

    private fun armSync() {
        writes.tryEmit(Unit)
    }

    /** Signs out. Nothing local is deleted — the database on this device is the source of
     *  truth, and this only forgets the account and where the last round got to. */
    fun signOut() = scope.launch {
        syncEngine.signOut()
        signInState.value = SyncUiState()
    }

    // ── Undo ─────────────────────────────────────────────────────────────────────────

    /**
     * Offers an undo for [action]: hides its rows at once, shows the snackbar with a **Undo**
     * button, and arms [commitPendingDelete] to run the real write after [UNDO_WINDOW].
     *
     * A second delete while one is already pending commits the first one immediately rather than
     * racing two deferred jobs against the same data — the user moved on, so the previous delete
     * is settled.
     */
    private fun offerUndo(action: UndoAction, count: Int) {
        // Commit anything still pending before starting a new one: cancel its timer and run the
        // write out of band, so its rows are truly gone rather than left filtered forever.
        val prior = pendingDeleteAction
        pendingDeleteJob?.cancel()
        if (prior != null) {
            scope.launch { commitPendingDelete(prior) }
        }

        pendingDeleteAction = action
        pendingDeleteIds.value = pendingDeleteIds.value + action.ids
        snackbarMessage.value = SnackbarMessage.Counted(
            plural = Res.plurals.undo_items_deleted,
            count = count,
            args = listOf(count),
            undoAction = action,
        )
        pendingDeleteJob = scope.launch {
            delay(UNDO_WINDOW.toMillis())
            commitPendingDelete(action)
        }
    }

    /**
     * Runs the real destructive write the undo window was holding back, then arms sync and
     * clears the pending set so the rows stay hidden (the tombstone the write just made keeps
     * them out of the flow on its own; clearing here is what drops the filter once they are).
     */
    private suspend fun commitPendingDelete(action: UndoAction) {
        if (pendingDeleteAction === action) pendingDeleteAction = null
        when (action) {
            is UndoAction.DeleteTask -> {
                action.subtasks.forEach { reminderScheduler.cancel(it.id) }
                reminderScheduler.cancel(action.task.id)
                repository.deleteTask(action.task.id)
            }
            is UndoAction.DeleteProject -> {
                action.tasks.forEach { reminderScheduler.cancel(it.id) }
                repository.deleteProject(action.project.id, action.deleteTasks)
            }
            is UndoAction.DeleteEverything -> {
                // The repository reports what it actually tombstoned, which is a superset of the
                // ids captured when the dialog was confirmed: anything a pull merged in during
                // the undo window goes too, and its alarm has to be cancelled with the rest.
                repository.deleteEverything().forEach { reminderScheduler.cancel(it) }
            }
        }
        armSync()
        pendingDeleteIds.value = pendingDeleteIds.value - action.ids
        if (snackbarMessage.value?.undoAction === action) {
            snackbarMessage.value = null
        }
    }

    /**
     * Undo. Cancels the deferred delete job and clears the pending ids — the rows were never
     * written, so they reappear from the flow that was feeding them, and there is no database
     * transaction at all. This is the whole point of offsetting the write by the undo window.
     */
    fun undo() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteAction = null
        pendingDeleteIds.value = emptySet()
        snackbarMessage.value = null
    }

    /** Dismiss the current snackbar. Committing a pending delete is left to its own timer —
     *  dismissing the banner does not rush the write, it only stops showing it. */
    fun dismissSnackbar() {
        snackbarMessage.value = null
    }

    /** Show an informational snackbar with no undo action. */
    fun showSnackbar(text: StringResource, args: List<Any> = emptyList()) {
        snackbarMessage.value = SnackbarMessage.Text(text = text, args = args)
    }

    /** Stops everything this ViewModel started. The shell calls it when the screen it belongs
     *  to is gone for good — not on a rotation. */
    fun close() {
        scope.cancel()
    }

    private companion object {
        /** Long enough that a burst of edits is one round, short enough that the other device
         *  has the change while the user is still looking at this one (ADR 0002, decision 11). */
        val WRITE_DEBOUNCE: Duration = Duration.ofSeconds(2)

        /**
         * How long an undo is offered before the destructive write actually happens.
         *
         * The whole idea: a delete does not reach the database until this window elapses, so an
         * undo within it costs no transaction at all — it only cancels the deferred job. Long
         * enough to read the snackbar and tap, short enough that the delete is not left hanging
         * if the user walks away.
         */
        val UNDO_WINDOW: Duration = Duration.ofSeconds(5)
    }
}
