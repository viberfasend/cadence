package de.andi1984.cadence.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import de.andi1984.cadence.ui.BandHeading
import de.andi1984.cadence.ui.TaskView
import de.andi1984.cadence.ui.taskList
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.TaskListRow
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ProjectSwatch
import de.andi1984.cadence.ui.components.SectionHeader
import de.andi1984.cadence.ui.components.TaskRow
import de.andi1984.cadence.ui.format.pluralTasks
import java.time.LocalDate

@Composable
fun ProjectDetailScreen(
    projectId: String?,
    state: CadenceUiState,
    today: LocalDate,
    onBack: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onProjectClick: (Project) -> Unit,
    onToggle: (Task) -> Unit,
    onAddTask: () -> Unit,
    onCreateProject: (String, String, String?) -> Unit,
    onEditProject: (Project, String, String, String?) -> Unit,
    onDeleteProject: (Project, Boolean) -> Unit,
    onCreateSection: (String, String) -> Unit,
    onRenameSection: (Section, String) -> Unit,
    onDeleteSection: (Section) -> Unit,
) {
    val project = state.project(projectId)
    var dialog by remember { mutableStateOf<ProjectDialogState?>(null) }
    var sectionDialog by remember { mutableStateOf<SectionDialogState?>(null) }
    if (project == null) {
        EmptyState(
            title = stringResource(Res.string.project_not_found_title),
            supporting = stringResource(Res.string.deleted_supporting),
        )
        return
    }

    val subprojects = state.subprojects(project.id)
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }
    val list = state.taskList(TaskView.Project(project.id), today, expandedIds)
    val open = list.openCount
    fun toggleExpanded(id: String) {
        expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back))
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProjectSwatch(colorHex = project.colorHex, size = 12)
                Column {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = buildString {
                            projectPath(project, state.projects)
                                ?.takeIf { it != project.name }
                                ?.let { append("$it · ") }
                            append(pluralTasks(open))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onAddTask) {
                Icon(
                    AppIcons.Add,
                    contentDescription = stringResource(Res.string.project_add_task),
                )
            }
            ProjectMenu(
                project = project,
                canAddSubproject = !project.isSubproject,
                onEdit = { dialog = ProjectDialogState.Edit(project) },
                onAddSubproject = { dialog = ProjectDialogState.Create(parentId = project.id) },
                onDelete = { dialog = ProjectDialogState.Delete(project) },
                onAddSection = { sectionDialog = SectionDialogState.Create(project.id) },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (subprojects.isNotEmpty()) {
                item { SectionHeader(stringResource(Res.string.project_section_subprojects)) }
                items(subprojects, key = { "s-${it.id}" }) { child ->
                    SubprojectLink(
                        project = child,
                        count = state.tasks.count { it.projectId == child.id && !it.isDone },
                        onClick = { onProjectClick(child) },
                    )
                }
            }

            if (list.isEmpty) {
                item {
                    EmptyState(
                        title = stringResource(Res.string.project_empty_title),
                        supporting = stringResource(
                            Res.string.project_empty_supporting,
                            project.name,
                        ),
                    )
                }
            }

            // Which bands a project's list has is decided in `taskList`, not here: without
            // sections it splits by urgency (overdue on top, the rest below), with them by where
            // the user filed the work. This draws whatever bands come back, in order.
            list.bands.forEach { band ->
                when (val heading = band.heading) {
                    BandHeading.Overdue -> item(key = "h-overdue") {
                        SectionHeader(
                            stringResource(Res.string.project_section_overdue),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    BandHeading.Everything -> item(key = "h-all") {
                        SectionHeader(stringResource(Res.string.project_section_all))
                    }

                    BandHeading.Ungrouped -> item(key = "h-ungrouped") {
                        SectionHeader(stringResource(Res.string.sections_ungrouped))
                    }

                    is BandHeading.Named -> item(key = "h-${heading.section.id}") {
                        SectionBandHeader(
                            section = heading.section,
                            onRename = { sectionDialog = SectionDialogState.Rename(heading.section) },
                            onDelete = { sectionDialog = SectionDialogState.Delete(heading.section) },
                        )
                    }

                    else -> Unit
                }
                taskBand(
                    rows = band.rows,
                    keyPrefix = band.key,
                    state = state,
                    today = today,
                    overdueStyle = band.heading == BandHeading.Overdue,
                    expandedIds = expandedIds,
                    onToggleExpanded = ::toggleExpanded,
                    onToggle = onToggle,
                    onTaskClick = onTaskClick,
                )
            }
        }
    }

    ProjectDialogs(
        dialog = dialog,
        state = state,
        onDismiss = { dialog = null },
        onCreateProject = onCreateProject,
        onEditProject = onEditProject,
        onDeleteProject = { deleted, deleteTasks ->
            onDeleteProject(deleted, deleteTasks)
            onBack()
        },
    )

    SectionDialogs(
        dialog = sectionDialog,
        state = state,
        onDismiss = { sectionDialog = null },
        onCreateSection = onCreateSection,
        onRenameSection = onRenameSection,
        onDeleteSection = onDeleteSection,
    )
}

/**
 * One band of task rows.
 *
 * Pulled out because the screen now draws as many bands as the project has sections plus one, and
 * every one of them needs the same subtask-expansion bookkeeping. [keyPrefix] keeps the list keys
 * unique: a task shows up in exactly one band, but a *subtask* row is drawn beneath its parent, so
 * two prefixed keys are what stop the ungrouped band and a section from claiming the same one.
 */
private fun LazyListScope.taskBand(
    rows: List<TaskListRow>,
    keyPrefix: String,
    state: CadenceUiState,
    today: LocalDate,
    expandedIds: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onToggle: (Task) -> Unit,
    onTaskClick: (Task) -> Unit,
    overdueStyle: Boolean = false,
) {
    items(
        rows,
        key = { "$keyPrefix-${if (it.isSubtaskRow) "sub" else "row"}-${it.task.id}" },
    ) { row ->
        val task = row.task
        val progress = if (row.isSubtaskRow) null else state.subtaskProgress(task.id)
        TaskRow(
            task = task,
            projectLabel = state.projectLabel(task),
            today = today,
            onToggle = { onToggle(task) },
            onClick = { onTaskClick(task) },
            overdueStyle = overdueStyle,
            subtaskProgress = progress,
            expanded = task.id in expandedIds,
            onExpandToggle = if (progress != null) {
                { onToggleExpanded(task.id) }
            } else {
                null
            },
            modifier = if (row.isSubtaskRow) Modifier.padding(start = 28.dp) else Modifier,
        )
    }
}

/** A section's heading, with the rename/delete menu the band's own actions live in. */
@Composable
private fun SectionBandHeader(
    section: Section,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(text = section.name, modifier = Modifier.weight(1f))
        SectionMenu(section = section, onRename = onRename, onDelete = onDelete)
    }
}

/** A subproject as it appears on its parent's screen — a way in, not a task row. */
@Composable
private fun SubprojectLink(project: Project, count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProjectSwatch(colorHex = project.colorHex, size = 10)
        Text(
            text = project.name,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Icon(
            imageVector = AppIcons.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
    }
}
