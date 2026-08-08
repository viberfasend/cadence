package de.andi1984.cadence.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.toTree
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ProjectSwatch
import de.andi1984.cadence.ui.components.ScreenHeader
import de.andi1984.cadence.ui.components.SectionHeader
import java.time.LocalDate


@Composable
fun ProjectsScreen(
    state: CadenceUiState,
    today: LocalDate,
    onProjectClick: (Project) -> Unit,
    onInbox: () -> Unit,
    onToday: () -> Unit,
    onCreateProject: (String, String, String?) -> Unit,
    onEditProject: (Project, String, String, String?) -> Unit,
    onDeleteProject: (Project, Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val tree = state.projects.toTree()
    var dialog by remember { mutableStateOf<ProjectDialogState?>(null) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    val inboxCount = state.inboxTasks().count { !it.isDone }
    val todayCount = state.tasks.count { task ->
        !task.isDone && task.dueDate?.isAfter(today) == false
    }
    val recurringCount = state.rootTasks().count { !it.isDone && it.recurrence != null }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.projects_title)) {
            IconButton(onClick = { dialog = ProjectDialogState.Create(parentId = null) }) {
                Icon(
                    AppIcons.CreateNewFolder,
                    contentDescription = stringResource(R.string.projects_new),
                )
            }
            IconButton(onClick = onSettings) {
                Icon(AppIcons.Settings, contentDescription = stringResource(R.string.action_settings))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                QuickRow(
                    icon = AppIcons.Inbox,
                    label = stringResource(R.string.inbox_title),
                    count = inboxCount,
                    highlighted = true,
                    onClick = onInbox,
                )
            }
            item {
                QuickRow(
                    icon = AppIcons.Today,
                    label = stringResource(R.string.today_title),
                    count = todayCount,
                    highlighted = false,
                    onClick = onToday,
                )
            }
            item {
                QuickRow(
                    icon = AppIcons.EventRepeat,
                    label = stringResource(R.string.projects_quick_recurring),
                    count = recurringCount,
                    highlighted = false,
                    onClick = {},
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                SectionHeader(
                    stringResource(R.string.projects_section),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            if (tree.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            title = stringResource(R.string.projects_empty_title),
                            supporting = stringResource(R.string.projects_empty_supporting),
                        )
                        TextButton(
                            onClick = { dialog = ProjectDialogState.Create(parentId = null) },
                        ) {
                            Icon(AppIcons.CreateNewFolder, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.projects_new))
                        }
                    }
                }
            }

            tree.forEach { node ->
                val isCollapsed = collapsed[node.project.id] == true
                item(key = "p-${node.project.id}") {
                    ProjectRow(
                        project = node.project,
                        subtitle = rootSubtitle(node.children.size, dueThisWeek(state, node.project.id, today)),
                        count = state.tasksIn(node.project.id).count { !it.isDone },
                        overdue = state.tasksIn(node.project.id).count { it.isOverdue(today) },
                        expandable = node.children.isNotEmpty(),
                        collapsed = isCollapsed,
                        onToggleExpand = { collapsed[node.project.id] = !isCollapsed },
                        onClick = { onProjectClick(node.project) },
                        menu = {
                            ProjectMenu(
                                project = node.project,
                                canAddSubproject = true,
                                onEdit = { dialog = ProjectDialogState.Edit(node.project) },
                                onAddSubproject = {
                                    collapsed[node.project.id] = false
                                    dialog = ProjectDialogState.Create(parentId = node.project.id)
                                },
                                onDelete = { dialog = ProjectDialogState.Delete(node.project) },
                            )
                        },
                    )
                }
                if (!isCollapsed) {
                    items(node.children.size, key = { "c-${node.children[it].id}" }) { index ->
                        val child = node.children[index]
                        val childTasks = state.rootTasks().filter { it.projectId == child.id }
                        SubprojectRow(
                            project = child,
                            count = childTasks.count { !it.isDone },
                            overdue = childTasks.count { it.isOverdue(today) },
                            onClick = { onProjectClick(child) },
                            menu = {
                                ProjectMenu(
                                    project = child,
                                    canAddSubproject = false,
                                    onEdit = { dialog = ProjectDialogState.Edit(child) },
                                    onAddSubproject = {},
                                    onDelete = { dialog = ProjectDialogState.Delete(child) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    ProjectDialogs(
        dialog = dialog,
        state = state,
        onDismiss = { dialog = null },
        onCreateProject = onCreateProject,
        onEditProject = onEditProject,
        onDeleteProject = onDeleteProject,
    )
}

private fun dueThisWeek(state: CadenceUiState, projectId: String, today: LocalDate): Int =
    state.tasksIn(projectId).count { task ->
        !task.isDone && task.dueDate?.isAfter(today.plusDays(7)) == false
    }

@Composable
private fun rootSubtitle(children: Int, dueThisWeek: Int): String? {
    val parts = buildList {
        if (children > 0) {
            add(pluralStringResource(R.plurals.projects_subproject_count, children, children))
        }
        if (dueThisWeek > 0) {
            add(stringResource(R.string.projects_due_this_week, dueThisWeek))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun QuickRow(
    icon: ImageVector,
    label: String,
    count: Int,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (highlighted) scheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlighted) scheme.onSecondaryContainer else scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlighted) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    subtitle: String?,
    count: Int,
    overdue: Int,
    expandable: Boolean,
    collapsed: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(enabled = expandable, onClick = onToggleExpand),
            contentAlignment = Alignment.Center,
        ) {
            if (expandable) {
                Icon(
                    imageVector = if (collapsed) AppIcons.ChevronRight else AppIcons.ExpandMore,
                    contentDescription = if (collapsed) {
                        stringResource(R.string.action_expand)
                    } else {
                        stringResource(R.string.action_collapse)
                    },
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(60.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProjectSwatch(colorHex = project.colorHex, size = 12)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (overdue > 0) {
                Text(
                    text = pluralStringResource(R.plurals.projects_overdue_count, overdue, overdue),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            }
            Text(
                text = "$count",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        menu()
    }
}

@Composable
private fun SubprojectRow(
    project: Project,
    count: Int,
    overdue: Int,
    onClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .width(2.dp)
                .height(56.dp)
                .background(scheme.outlineVariant),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .padding(start = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProjectSwatch(colorHex = project.colorHex, size = 10)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                if (overdue > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.projects_overdue_count,
                            overdue,
                            overdue,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                    )
                }
            }
            Text(
                text = "$count",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        menu()
    }
}

