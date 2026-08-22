package de.andi1984.cadence.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.format.title
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate

/**
 * The right-click menus for the three things a list is made of.
 *
 * These are the actions a row *has*, laid out once and reused by every screen and both shells:
 * changing a priority or a due date should not mean opening a detail screen, and moving a task
 * should not mean finding a dialog. Everything here already exists as a ViewModel method — this is
 * a second way to reach them, not a second implementation.
 *
 * Submenus are pages of the same menu rather than cascades: Material 3 has no cascading
 * `DropdownMenu`, and a page that names where it is and how to get back is both less code and
 * easier to hit with a mouse than a strip that closes when the pointer leaves it.
 */
private enum class TaskMenuPage { Root, Priority, DueDate, Project, Section }

@Composable
fun ColumnScope.TaskContextMenuItems(
    task: Task,
    state: CadenceUiState,
    today: LocalDate,
    dismiss: () -> Unit,
    onOpen: (Task) -> Unit,
    onToggle: (Task) -> Unit,
    onSetPriority: (Task, Priority) -> Unit,
    onSetDueDate: (Task, LocalDate?) -> Unit,
    onMoveToProject: (Task, String?) -> Unit,
    onMoveToSection: (Task, String?) -> Unit,
    onDuplicate: (Task) -> Unit,
    onDelete: (Task) -> Unit,
) {
    var page by remember { mutableStateOf(TaskMenuPage.Root) }
    val clipboard = LocalClipboardManager.current

    when (page) {
        TaskMenuPage.Root -> {
            CadenceMenuItem(
                text = stringResource(Res.string.menu_open),
                icon = AppIcons.Edit,
                onClick = { dismiss(); onOpen(task) },
            )
            CadenceMenuItem(
                text = stringResource(
                    if (task.isDone) Res.string.task_mark_not_done else Res.string.task_mark_done,
                ),
                icon = AppIcons.Check,
                onClick = { dismiss(); onToggle(task) },
            )
            HorizontalDivider()
            CadenceSubmenuItem(
                text = stringResource(Res.string.task_importance),
                icon = AppIcons.Flag,
                onClick = { page = TaskMenuPage.Priority },
            )
            CadenceSubmenuItem(
                text = stringResource(Res.string.menu_due_date),
                icon = AppIcons.EditCalendar,
                onClick = { page = TaskMenuPage.DueDate },
            )
            CadenceSubmenuItem(
                text = stringResource(Res.string.task_move_to_project),
                icon = AppIcons.Folder,
                onClick = { page = TaskMenuPage.Project },
            )
            // Only where there is somewhere to move it to: a project with no bands has no
            // grouping to offer, and an Inbox task has no project to take bands from.
            // (A local, because a smart cast does not cross the `:core` module boundary.)
            val bandedProject = task.projectId
            if (bandedProject != null && state.sectionsIn(bandedProject).isNotEmpty()) {
                CadenceSubmenuItem(
                    text = stringResource(Res.string.task_move_to_section),
                    icon = AppIcons.Section,
                    onClick = { page = TaskMenuPage.Section },
                )
            }
            HorizontalDivider()
            CadenceMenuItem(
                text = stringResource(Res.string.menu_duplicate),
                icon = AppIcons.Add,
                onClick = { dismiss(); onDuplicate(task) },
            )
            CadenceMenuItem(
                text = stringResource(Res.string.menu_copy_title),
                onClick = {
                    dismiss()
                    clipboard.setText(AnnotatedString(task.title))
                },
            )
            CadenceMenuItem(
                text = stringResource(Res.string.action_delete),
                icon = AppIcons.Delete,
                onClick = { dismiss(); onDelete(task) },
            )
        }

        TaskMenuPage.Priority -> {
            CadenceMenuBack(stringResource(Res.string.task_importance)) { page = TaskMenuPage.Root }
            Priority.entries.forEach { priority ->
                CadenceMenuItem(
                    text = priority.title(),
                    trailing = priority.shortLabel,
                    enabled = priority != task.priority,
                    onClick = { dismiss(); onSetPriority(task, priority) },
                )
            }
        }

        TaskMenuPage.DueDate -> {
            CadenceMenuBack(stringResource(Res.string.menu_due_date)) { page = TaskMenuPage.Root }
            CadenceMenuItem(
                text = stringResource(Res.string.date_today),
                icon = AppIcons.Today,
                onClick = { dismiss(); onSetDueDate(task, today) },
            )
            CadenceMenuItem(
                text = stringResource(Res.string.date_tomorrow),
                icon = AppIcons.Event,
                onClick = { dismiss(); onSetDueDate(task, today.plusDays(1)) },
            )
            CadenceMenuItem(
                text = stringResource(Res.string.menu_next_week),
                icon = AppIcons.CalendarMonth,
                onClick = { dismiss(); onSetDueDate(task, today.plusWeeks(1)) },
            )
            CadenceMenuItem(
                text = stringResource(Res.string.task_no_due_date),
                icon = AppIcons.EventBusy,
                enabled = task.dueDate != null,
                onClick = { dismiss(); onSetDueDate(task, null) },
            )
        }

        TaskMenuPage.Project -> {
            CadenceMenuBack(stringResource(Res.string.task_move_to_project)) { page = TaskMenuPage.Root }
            CadenceMenuItem(
                text = stringResource(Res.string.inbox_title),
                icon = AppIcons.Inbox,
                enabled = task.projectId != null,
                onClick = { dismiss(); onMoveToProject(task, null) },
            )
            state.projects.forEach { project ->
                CadenceMenuItem(
                    text = state.projectLabelFor(project),
                    icon = AppIcons.Folder,
                    enabled = project.id != task.projectId,
                    onClick = { dismiss(); onMoveToProject(task, project.id) },
                )
            }
        }

        TaskMenuPage.Section -> {
            val projectId = task.projectId
            CadenceMenuBack(stringResource(Res.string.task_move_to_section)) { page = TaskMenuPage.Root }
            CadenceMenuItem(
                text = stringResource(Res.string.sections_ungrouped),
                enabled = task.sectionId != null,
                onClick = { dismiss(); onMoveToSection(task, null) },
            )
            if (projectId != null) {
                state.sectionsIn(projectId).forEach { section ->
                    CadenceMenuItem(
                        text = section.name,
                        icon = AppIcons.Section,
                        enabled = section.id != task.sectionId,
                        onClick = { dismiss(); onMoveToSection(task, section.id) },
                    )
                }
            }
        }
    }
}

private enum class ProjectMenuPage { Root, NestUnder }

@Composable
fun ColumnScope.ProjectContextMenuItems(
    project: Project,
    state: CadenceUiState,
    dismiss: () -> Unit,
    onOpen: (Project) -> Unit,
    onAddTask: (Project) -> Unit,
    onAddSubproject: (Project) -> Unit,
    onEdit: (Project) -> Unit,
    onNestUnder: (Project, String?) -> Unit,
    onDelete: (Project) -> Unit,
) {
    var page by remember { mutableStateOf(ProjectMenuPage.Root) }

    when (page) {
        ProjectMenuPage.Root -> {
            CadenceMenuItem(
                text = stringResource(Res.string.menu_open),
                icon = AppIcons.Folder,
                onClick = { dismiss(); onOpen(project) },
            )
            CadenceMenuItem(
                text = stringResource(Res.string.menu_add_task_here),
                icon = AppIcons.Add,
                onClick = { dismiss(); onAddTask(project) },
            )
            // Nesting is one level: a project that is already nested cannot take children.
            if (!project.isSubproject) {
                CadenceMenuItem(
                    text = stringResource(Res.string.projects_new_subproject),
                    icon = AppIcons.CreateNewFolder,
                    onClick = { dismiss(); onAddSubproject(project) },
                )
            }
            HorizontalDivider()
            CadenceMenuItem(
                text = stringResource(Res.string.projects_edit),
                icon = AppIcons.Edit,
                onClick = { dismiss(); onEdit(project) },
            )
            // And the other half of the same rule: a project with children of its own has
            // nowhere to be nested.
            if (state.subprojects(project.id).isEmpty()) {
                CadenceSubmenuItem(
                    text = stringResource(Res.string.menu_nest_under),
                    icon = AppIcons.Section,
                    onClick = { page = ProjectMenuPage.NestUnder },
                )
            }
            CadenceMenuItem(
                text = stringResource(Res.string.projects_delete),
                icon = AppIcons.Delete,
                onClick = { dismiss(); onDelete(project) },
            )
        }

        ProjectMenuPage.NestUnder -> {
            CadenceMenuBack(stringResource(Res.string.menu_nest_under)) { page = ProjectMenuPage.Root }
            CadenceMenuItem(
                text = stringResource(Res.string.menu_top_level),
                enabled = project.isSubproject,
                onClick = { dismiss(); onNestUnder(project, null) },
            )
            state.nestingCandidates(exclude = project).forEach { candidate ->
                CadenceMenuItem(
                    text = candidate.name,
                    icon = AppIcons.Folder,
                    enabled = candidate.id != project.parentId,
                    onClick = { dismiss(); onNestUnder(project, candidate.id) },
                )
            }
        }
    }
}

@Composable
fun ColumnScope.SectionContextMenuItems(
    section: Section,
    dismiss: () -> Unit,
    onAddTask: (Section) -> Unit,
    onRename: (Section) -> Unit,
    onDelete: (Section) -> Unit,
) {
    CadenceMenuItem(
        text = stringResource(Res.string.menu_add_task_here),
        icon = AppIcons.Add,
        onClick = { dismiss(); onAddTask(section) },
    )
    CadenceMenuItem(
        text = stringResource(Res.string.sections_rename),
        icon = AppIcons.Edit,
        onClick = { dismiss(); onRename(section) },
    )
    CadenceMenuItem(
        text = stringResource(Res.string.sections_delete),
        icon = AppIcons.Delete,
        onClick = { dismiss(); onDelete(section) },
    )
}

/** "Home / Finance" for a project row in a menu, so two same-named bands are told apart. */
private fun CadenceUiState.projectLabelFor(project: Project): String {
    val parent = project.parentId?.let { project(it) } ?: return project.name
    return "${parent.name} / ${project.name}"
}
