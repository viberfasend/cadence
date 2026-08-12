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
import de.andi1984.cadence.domain.model.SubtaskProgress
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.domain.model.withoutSupersededOccurrences
import de.andi1984.cadence.domain.parse.ParsedQuickAdd
import de.andi1984.cadence.ui.platform.BackupGateway
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.platform.ReminderScheduler
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SignInError
import de.andi1984.cadence.ui.settings.SyncUiState
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** Represents an action that can be undone. */
sealed class UndoAction {
    data class DeleteTask(val task: Task, val subtasks: List<Task>) : UndoAction()
    data class DeleteProject(val project: Project, val tasks: List<Task>) : UndoAction()
}

/** Snackbar message with optional undo action. */
data class SnackbarMessage(
    val text: String,
    val undoAction: UndoAction? = null,
    val duration: Long = 5000L, // Default 5 seconds
)

/** A row in a task list — a root task, or a subtask shown inline beneath an expanded one. */
data class TaskListRow(val task: Task, val isSubtaskRow: Boolean)

data class CadenceUiState(
    val tasks: List<Task> = emptyList(),
    val projects: List<Project> = emptyList(),
    val settings: CadenceSettings = CadenceSettings(),
    /** Result of the last export or import, shown once under the Settings buttons. */
    val backupOutcome: BackupOutcome? = null,
    /** Sign-in state and where the last sync round got to. */
    val sync: SyncUiState = SyncUiState(),
    /** Current snackbar message to display, if any. */
    val snackbarMessage: SnackbarMessage? = null,
) {
    fun project(id: String?): Project? = id?.let { projectId -> projects.firstOrNull { it.id == projectId } }

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

    /** What a sign-in attempt is doing, folded together with the engine's own status — the
     *  screen wants one value, and `combine` takes five flows at most. */
    private val signInState = MutableStateFlow(SyncUiState())

    private val syncState = combine(
        syncEngine.status,
        signInState,
    ) { status, attempt -> attempt.copy(status = status) }

    /** Paired so the state combine stays inside `combine`'s five-flow overload. */
    private val backupAndSnackbar = combine(
        backupOutcome,
        snackbarMessage,
    ) { backup, snackMessage -> backup to snackMessage }

    val state: StateFlow<CadenceUiState> = combine(
        repository.tasks,
        repository.projects,
        settingsStore.state,
        syncState,
        backupAndSnackbar,
    ) { tasks, projects, settings, sync, (backup, snackMessage) ->
        CadenceUiState(
            tasks = tasks,
            projects = projects,
            settings = settings,
            backupOutcome = backup,
            sync = sync,
            snackbarMessage = snackMessage,
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

    // ── Tasks ──────────────────────────────────────────────────────────────────────

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

    fun deleteTask(task: Task) = scope.launch {
        val subtasks = state.value.subtasks(task.id)
        
        // Cancel reminders for the task and its subtasks
        subtasks.forEach { reminderScheduler.cancel(it.id) }
        reminderScheduler.cancel(task.id)
        
        // Store for potential undo
        val deletedTask = task
        val deletedSubtasks = subtasks
        
        // Perform deletion
        repository.deleteTask(task.id)
        armSync()
        
        // Show snackbar with undo option
        showSnackbar(
            text = "Task and ${subtasks.size} subtask(s) deleted",
            undoAction = UndoAction.DeleteTask(deletedTask, deletedSubtasks)
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
                showSnackbar(result.message)
            }
            RepositoryResult.ValidationError -> {
                showSnackbar("Please enter a valid subtask title")
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

    // ── Projects ───────────────────────────────────────────────────────────────────

    fun addProject(name: String, colorHex: String, parentId: String?) = scope.launch {
        if (name.isBlank()) {
            showSnackbar("Project name cannot be empty")
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
                showSnackbar(result.message)
            }
            RepositoryResult.ValidationError -> {
                showSnackbar("Please enter a valid project name")
            }
        }
    }

    fun editProject(project: Project, name: String, colorHex: String, parentId: String?) = scope.launch {
        if (name.isBlank()) {
            showSnackbar("Project name cannot be empty")
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
                showSnackbar(result.message)
            }
            RepositoryResult.ValidationError -> {
                showSnackbar("Please enter a valid project name")
            }
        }
    }

    fun deleteProject(project: Project, deleteTasks: Boolean = false) = scope.launch {
        val tasksInProject = state.value.tasksIn(project.id)
        
        // Cancel reminders for all tasks in the project
        tasksInProject.forEach { reminderScheduler.cancel(it.id) }
        
        // Store for potential undo
        val deletedProject = project
        val deletedTasks = tasksInProject
        
        // Perform deletion
        repository.deleteProject(project.id, deleteTasks)
        armSync()
        
        // Show snackbar with undo option
        showSnackbar(
            text = "Project and ${tasksInProject.size} task(s) deleted",
            undoAction = UndoAction.DeleteProject(deletedProject, deletedTasks)
        )
    }

    // ── Settings ───────────────────────────────────────────────────────────────────

    fun setSortMode(mode: SortMode) = settingsStore.setSortMode(mode)

    fun setTheme(theme: ThemeChoice) = settingsStore.setTheme(theme)

    fun setDensity(density: Density) = settingsStore.setDensity(density)

    fun setShowCompleted(show: Boolean) = settingsStore.setShowCompleted(show)

    fun setRemindersEnabled(enabled: Boolean) = settingsStore.setRemindersEnabled(enabled)

    // ── Backup ─────────────────────────────────────────────────────────────────────

    fun exportBackup(target: BackupTarget) = scope.launch {
        backupOutcome.value = backupGateway.export(target)
    }

    /** Folds the file into what is already here; reminders resync themselves, and the rows the
     *  merge wrote travel to the other devices like any other edit. */
    fun importBackup(target: BackupTarget) = scope.launch {
        backupOutcome.value = backupGateway.import(target)
        armSync()
    }

    fun clearBackupOutcome() {
        backupOutcome.value = null
    }

    // ── Sync ───────────────────────────────────────────────────────────────────────

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

    /** Show a snackbar message with optional undo action. */
    fun showSnackbar(text: String, undoAction: UndoAction? = null, duration: Long = 5000L) {
        snackbarMessage.value = SnackbarMessage(text, undoAction, duration)
        
        // Auto-dismiss after duration if no undo action
        if (undoAction == null) {
            scope.launch {
                delay(duration)
                dismissSnackbar()
            }
        }
    }

    /** Dismiss the current snackbar message. */
    fun dismissSnackbar() {
        snackbarMessage.value = null
    }

    /** Handle undo action from snackbar. */
    fun handleUndo() = scope.launch {
        val currentMessage = snackbarMessage.value ?: return@launch
        val undoAction = currentMessage.undoAction ?: return@launch
        
        when (undoAction) {
            is UndoAction.DeleteTask -> {
                // Restore the task and its subtasks
                repository.upsertTask(undoAction.task)
                undoAction.subtasks.forEach { subtask ->
                    repository.upsertTask(subtask)
                }
                
                // Restore reminders
                reminderScheduler.sync(listOf(undoAction.task) + undoAction.subtasks)
            }
            is UndoAction.DeleteProject -> {
                // Restore the project
                repository.upsertProject(undoAction.project)
                
                // Restore all tasks that were in the project
                undoAction.tasks.forEach { task ->
                    repository.upsertTask(task)
                }
                
                // Restore reminders
                reminderScheduler.sync(undoAction.tasks)
            }
        }
        armSync()
        
        // Auto-dismiss the snackbar after undo
        delay(1000) // Give time for the restore to complete
        dismissSnackbar()
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
    }
}
