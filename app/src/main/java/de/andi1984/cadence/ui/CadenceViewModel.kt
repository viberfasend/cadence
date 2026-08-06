package de.andi1984.cadence.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.backup.BackupIo
import de.andi1984.cadence.data.backup.BackupOutcome
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.SubtaskProgress
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.domain.parse.ParsedQuickAdd
import de.andi1984.cadence.reminders.ReminderScheduler
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class CadenceUiState(
    val tasks: List<Task> = emptyList(),
    val projects: List<Project> = emptyList(),
    val settings: CadenceSettings = CadenceSettings(),
    /** Result of the last export or import, shown once under the Settings buttons. */
    val backupOutcome: BackupOutcome? = null,
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
     */
    fun rootTasks(): List<Task> = tasks.filter { !it.isSubtask }

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

    /** Tasks in a project, including everything filed under its subprojects. */
    fun tasksIn(projectId: Long): List<Task> {
        val childIds = projects.filter { it.parentId == projectId }.map { it.id }.toSet()
        return rootTasks().filter { it.projectId == projectId || it.projectId in childIds }
    }
}

class CadenceViewModel(
    private val repository: CadenceRepository,
    private val settingsStore: SettingsStore,
    private val reminderScheduler: ReminderScheduler,
    private val backupIo: BackupIo,
) : ViewModel() {

    private val backupOutcome = MutableStateFlow<BackupOutcome?>(null)

    val state: StateFlow<CadenceUiState> = combine(
        repository.tasks,
        repository.projects,
        settingsStore.state,
        backupOutcome,
    ) { tasks, projects, settings, backup ->
        CadenceUiState(
            tasks = tasks,
            projects = projects,
            settings = settings,
            backupOutcome = backup,
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
        repository.setCompleted(task, !task.isDone)
    }

    fun saveTask(task: Task) = viewModelScope.launch {
        repository.upsertTask(task)
    }

    fun deleteTask(task: Task) = viewModelScope.launch {
        // The subtasks go with it, so their alarms have to go too — sync only sees rows that
        // still exist.
        state.value.subtasks(task.id).forEach { reminderScheduler.cancel(it.id) }
        reminderScheduler.cancel(task.id)
        repository.deleteTask(task.id)
    }

    fun addSubtask(parent: Task, title: String) = viewModelScope.launch {
        repository.addSubtask(parent, title)
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
        if (name.isBlank()) return@launch
        val order = state.value.projects.count { it.parentId == parentId }
        repository.upsertProject(
            Project(name = name.trim(), colorHex = colorHex, parentId = parentId, sortOrder = order),
        )
    }

    fun deleteProject(project: Project) = viewModelScope.launch {
        repository.deleteProject(project.id)
    }

    // ── Settings ───────────────────────────────────────────────────────────────────

    fun setSortMode(mode: SortMode) = settingsStore.setSortMode(mode)

    fun setTheme(theme: ThemeChoice) = settingsStore.setTheme(theme)

    fun setDensity(density: Density) = settingsStore.setDensity(density)

    fun setShowCompleted(show: Boolean) = settingsStore.setShowCompleted(show)

    // ── Backup ─────────────────────────────────────────────────────────────────────

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        backupOutcome.value = backupIo.export(uri)
    }

    /** Replaces every task and project with the file's contents; reminders resync themselves. */
    fun importBackup(uri: Uri) = viewModelScope.launch {
        backupOutcome.value = backupIo.import(uri)
    }

    fun clearBackupOutcome() {
        backupOutcome.value = null
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
                )
            }
        }
    }
}
