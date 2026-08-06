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

    /** The subprojects filed under a project — nesting is one level, so these have none. */
    fun subprojects(projectId: Long): List<Project> =
        projects.filter { it.parentId == projectId }.sortedBy { it.sortOrder }

    /** Tasks in a project, including everything filed under its subprojects. */
    fun tasksIn(projectId: Long): List<Task> {
        val childIds = subprojects(projectId).map { it.id }.toSet()
        return tasks.filter { it.projectId == projectId || it.projectId in childIds }
    }

    /**
     * The projects a project may be nested under: every top-level one except itself, and only
     * while it has no subprojects of its own.
     */
    fun nestingCandidates(project: Project?): List<Project> {
        if (project != null && subprojects(project.id).isNotEmpty()) return emptyList()
        return projects.filter { it.parentId == null && it.id != project?.id }
            .sortedBy { it.sortOrder }
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
        reminderScheduler.cancel(task.id)
        repository.deleteTask(task.id)
    }

    fun setPriority(task: Task, priority: Priority) = saveTask(task.copy(priority = priority))

    fun setDueDate(task: Task, dueDate: LocalDate?) = viewModelScope.launch {
        repository.setDueDate(task, dueDate)
    }

    fun setDueTime(task: Task, dueTime: LocalTime?) = saveTask(task.copy(dueTime = dueTime))

    fun setReminder(task: Task, reminder: LocalTime?) = saveTask(task.copy(reminderTime = reminder))

    fun setRecurrence(task: Task, rule: RecurrenceRule?) = saveTask(task.copy(recurrence = rule))

    fun setProject(task: Task, projectId: Long?) = saveTask(task.copy(projectId = projectId))

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

    /**
     * Renames, recolours or re-files an existing project. Moving it to a new parent puts it last
     * among that parent's children, the same place a freshly created one would land.
     */
    fun editProject(project: Project, name: String, colorHex: String, parentId: Long?) =
        viewModelScope.launch {
            if (name.isBlank()) return@launch
            val moved = parentId != project.parentId
            val order = if (moved) {
                state.value.projects.count { it.parentId == parentId && it.id != project.id }
            } else {
                project.sortOrder
            }
            repository.upsertProject(
                project.copy(
                    name = name.trim(),
                    colorHex = colorHex,
                    parentId = parentId,
                    sortOrder = order,
                ),
            )
        }

    /**
     * Deletes a project and its subprojects. [deleteTasks] decides whether the work goes with it
     * or falls back to the Inbox.
     */
    fun deleteProject(project: Project, deleteTasks: Boolean = false) = viewModelScope.launch {
        repository.deleteProject(project.id, deleteTasks)
            .forEach { taskId -> reminderScheduler.cancel(taskId) }
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
