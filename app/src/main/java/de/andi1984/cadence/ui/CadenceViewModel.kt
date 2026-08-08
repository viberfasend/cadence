package de.andi1984.cadence.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.RepositoryResult
import de.andi1984.cadence.data.backup.AutoBackupSync
import de.andi1984.cadence.data.backup.BackupFailure
import de.andi1984.cadence.data.backup.BackupIo
import de.andi1984.cadence.data.backup.BackupOutcome
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.SubtaskProgress
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.domain.model.withoutSupersededOccurrences
import de.andi1984.cadence.domain.parse.ParsedQuickAdd
import de.andi1984.cadence.reminders.ReminderScheduler
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    /** Why automatic sync last failed, or null while it is working. */
    val autoBackupFailure: BackupFailure? = null,
    /** Current snackbar message to display, if any. */
    val snackbarMessage: SnackbarMessage? = null,
) {
    fun project(id: Long?): Project? = id?.let { projectId -> projects.firstOrNull { it.id == projectId } }

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
    fun subtasks(parentId: Long): List<Task> = tasks
        .filter { it.parentId == parentId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    /** Null when a task has no checklist at all, so rows can leave the chip out entirely. */
    fun subtaskProgress(parentId: Long): SubtaskProgress? {
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
    fun expandedRows(roots: List<Task>, expandedIds: Set<Long>): List<TaskListRow> =
        roots.flatMap { task ->
            val children = if (task.id in expandedIds) subtasks(task.id) else emptyList()
            listOf(TaskListRow(task, isSubtaskRow = false)) +
                children.map { TaskListRow(it, isSubtaskRow = true) }
        }

    /** Subprojects of a project, in the order they were added. */
    fun subprojects(parentId: Long): List<Project> = projects
        .filter { it.parentId == parentId }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    /** Tasks in a project, including everything filed under its subprojects. */
    fun tasksIn(projectId: Long): List<Task> {
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
        val descendants = mutableSetOf<Long>()
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

class CadenceViewModel(
    private val repository: CadenceRepository,
    private val settingsStore: SettingsStore,
    private val reminderScheduler: ReminderScheduler,
    private val backupIo: BackupIo,
    private val autoBackupSync: AutoBackupSync,
) : ViewModel() {

    private val backupOutcome = MutableStateFlow<BackupOutcome?>(null)
    private val snackbarMessage = MutableStateFlow<SnackbarMessage?>(null)

    /** Settings and the health of automatic sync always travel together into the Data section,
     *  and `combine` takes five flows at most. */
    private val settingsWithSync = combine(
        settingsStore.state,
        autoBackupSync.failure,
    ) { settings, failure -> settings to failure }

    /** Paired for the same reason as [settingsWithSync] — one more flow (attachments, phase 1)
     *  would push the state combine past `combine`'s five-flow overload. */
    private val backupAndSnackbar = combine(
        backupOutcome,
        snackbarMessage,
    ) { backup, snackMessage -> backup to snackMessage }

    val state: StateFlow<CadenceUiState> = combine(
        repository.tasks,
        repository.projects,
        settingsWithSync,
        backupAndSnackbar,
    ) { tasks, projects, (settings, autoFailure), (backup, snackMessage) ->
        CadenceUiState(
            tasks = tasks,
            projects = projects,
            settings = settings,
            backupOutcome = backup,
            autoBackupFailure = autoFailure,
            snackbarMessage = snackMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CadenceUiState(),
    )

    init {
        viewModelScope.launch {
            repository.tasks.collect { tasks -> reminderScheduler.sync(tasks) }
        }
    }

    // ── Tasks ──────────────────────────────────────────────────────────────────────

    fun toggleTask(task: Task) = viewModelScope.launch {
        // Reopening a recurring task takes the occurrence its completion inserted back out, and
        // an alarm outlives the row it belongs to unless it is cancelled here — sync only ever
        // sees the tasks that still exist.
        repository.setCompleted(task, !task.isDone).forEach { reminderScheduler.cancel(it) }
    }

    fun saveTask(task: Task) = viewModelScope.launch {
        repository.upsertTask(task)
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        val subtasks = state.value.subtasks(task.id)
        
        // Cancel reminders for the task and its subtasks
        subtasks.forEach { reminderScheduler.cancel(it.id) }
        reminderScheduler.cancel(task.id)
        
        // Store for potential undo
        val deletedTask = task
        val deletedSubtasks = subtasks
        
        // Perform deletion
        repository.deleteTask(task.id)
        
        // Show snackbar with undo option
        showSnackbar(
            text = "Task and ${subtasks.size} subtask(s) deleted",
            undoAction = UndoAction.DeleteTask(deletedTask, deletedSubtasks)
        )
    }

    fun addSubtask(parent: Task, title: String) = viewModelScope.launch {
        when (val result = repository.addSubtask(parent, title)) {
            is RepositoryResult.Success -> {
                // Success - the subtask was added
                // No action needed as the Flow will update automatically
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

    fun setDueDate(task: Task, dueDate: LocalDate?) = viewModelScope.launch {
        repository.setDueDate(task, dueDate)
    }

    fun setDueTime(task: Task, dueTime: LocalTime?) = saveTask(task.copy(dueTime = dueTime))

    fun setReminder(task: Task, reminder: LocalTime?) = saveTask(task.copy(reminderTime = reminder))

    fun setRecurrence(task: Task, rule: RecurrenceRule?) = saveTask(task.copy(recurrence = rule))

    fun setProject(task: Task, projectId: Long?) = viewModelScope.launch {
        repository.moveToProject(task, projectId)
    }

    fun snooze(task: Task, days: Long = 1L) = viewModelScope.launch {
        repository.shiftDueDate(task, days)
    }

    fun rescheduleOverdue() = viewModelScope.launch {
        repository.rescheduleOverdueToToday()
    }

    /** Creates the task the quick-add sheet parsed out of the typed line. */
    fun addParsedTask(parsed: ParsedQuickAdd, fallbackProjectId: Long? = null) =
        viewModelScope.launch {
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
        }

    // ── Projects ───────────────────────────────────────────────────────────────────

    fun addProject(name: String, colorHex: String, parentId: Long?) = viewModelScope.launch {
        if (name.isBlank()) {
            showSnackbar("Project name cannot be empty")
            return@launch
        }
        
        val order = state.value.projects.count { it.parentId == parentId }
        val project = Project(name = name.trim(), colorHex = colorHex, parentId = parentId, sortOrder = order)
        
        when (val result = repository.upsertProject(project)) {
            is RepositoryResult.Success -> {
                // Success - project was created
            }
            is RepositoryResult.Error -> {
                showSnackbar(result.message)
            }
            RepositoryResult.ValidationError -> {
                showSnackbar("Please enter a valid project name")
            }
        }
    }

    fun editProject(project: Project, name: String, colorHex: String, parentId: Long?) = viewModelScope.launch {
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
            }
            is RepositoryResult.Error -> {
                showSnackbar(result.message)
            }
            RepositoryResult.ValidationError -> {
                showSnackbar("Please enter a valid project name")
            }
        }
    }

    fun deleteProject(project: Project, deleteTasks: Boolean = false) = viewModelScope.launch {
        val tasksInProject = state.value.tasksIn(project.id)
        
        // Cancel reminders for all tasks in the project
        tasksInProject.forEach { reminderScheduler.cancel(it.id) }
        
        // Store for potential undo
        val deletedProject = project
        val deletedTasks = tasksInProject
        
        // Perform deletion
        repository.deleteProject(project.id, deleteTasks)
        
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

    // ── Backup ─────────────────────────────────────────────────────────────────────

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        val outcome = backupIo.export(uri)
        backupOutcome.value = outcome
        autoBackupSync.noteManualBackup(uri, outcome)
    }

    /** Replaces every task and project with the file's contents; reminders resync themselves. */
    fun importBackup(uri: Uri) = viewModelScope.launch {
        val outcome = backupIo.import(uri)
        backupOutcome.value = outcome
        autoBackupSync.noteManualBackup(uri, outcome)
    }

    fun clearBackupOutcome() {
        backupOutcome.value = null
    }

    // ── Automatic backup sync ──────────────────────────────────────────────────────

    /** Turns sync on for the file the user just exported to, or picked from Settings. */
    fun enableAutoBackup(uri: Uri) = autoBackupSync.enable(uri)

    fun disableAutoBackup() = autoBackupSync.disable()

    /** "Not now" — the offer is answered, and manual export and import carry on as before. */
    fun declineAutoBackup() = autoBackupSync.declineOffer()

    fun onAppForegrounded() = autoBackupSync.onAppForegrounded()

    fun onAppBackgrounded() = autoBackupSync.onAppBackgrounded()

    /** Show a snackbar message with optional undo action. */
    fun showSnackbar(text: String, undoAction: UndoAction? = null, duration: Long = 5000L) {
        snackbarMessage.value = SnackbarMessage(text, undoAction, duration)
        
        // Auto-dismiss after duration if no undo action
        if (undoAction == null) {
            viewModelScope.launch {
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
    fun handleUndo() = viewModelScope.launch {
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
        
        // Auto-dismiss the snackbar after undo
        delay(1000) // Give time for the restore to complete
        dismissSnackbar()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as CadenceApplication
                CadenceViewModel(
                    repository = application.container.repository,
                    settingsStore = application.container.settingsStore,
                    reminderScheduler = application.container.reminderScheduler,
                    backupIo = application.container.backupIo,
                    autoBackupSync = application.container.autoBackupSync,
                )
            }
        }
    }
}
