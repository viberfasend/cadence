package de.andi1984.cadence.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ProjectSwatch
import de.andi1984.cadence.ui.components.SectionHeader
import de.andi1984.cadence.ui.components.TaskRow
import de.andi1984.cadence.ui.format.pluralTasks
import de.andi1984.cadence.ui.sortedFor
import java.time.LocalDate

@Composable
fun ProjectDetailScreen(
    projectId: Long?,
    state: CadenceUiState,
    today: LocalDate,
    onBack: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onToggle: (Task) -> Unit,
    onAddTask: () -> Unit,
) {
    val project = state.project(projectId)
    if (project == null) {
        EmptyState(
            title = stringResource(R.string.project_not_found_title),
            supporting = stringResource(R.string.deleted_supporting),
        )
        return
    }

    val tasks = state.tasksIn(project.id)
        .filter { state.settings.showCompleted || !it.isDone }
        .sortedFor(state.settings.sortMode)
    val open = tasks.count { !it.isDone }
    val overdue = tasks.filter { it.isOverdue(today) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                    contentDescription = stringResource(R.string.project_add_task),
                )
            }
        }

        if (tasks.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.project_empty_title),
                supporting = stringResource(R.string.project_empty_supporting, project.name),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (overdue.isNotEmpty()) {
                item {
                    SectionHeader(
                        stringResource(R.string.project_section_overdue),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                items(overdue, key = { "o-${it.id}" }) { task ->
                    TaskRow(
                        task = task,
                        projectLabel = state.projectLabel(task),
                        today = today,
                        onToggle = { onToggle(task) },
                        onClick = { onTaskClick(task) },
                        overdueStyle = true,
                    )
                }
            }
            val rest = tasks.filterNot { it.isOverdue(today) }
            if (rest.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.project_section_all)) }
                items(rest, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        projectLabel = state.projectLabel(task),
                        today = today,
                        onToggle = { onToggle(task) },
                        onClick = { onTaskClick(task) },
                    )
                }
            }
        }
    }
}
