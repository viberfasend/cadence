package de.andi1984.cadence.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.toTree
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.ProjectSwatch
import de.andi1984.cadence.ui.components.SectionHeader
import de.andi1984.cadence.ui.components.parseColor
import java.time.LocalDate

private val PROJECT_COLORS = listOf(
    "#006A60", "#3E6373", "#A1560A", "#7D5260", "#6F7976", "#BA1A1A",
)

@Composable
fun ProjectsScreen(
    state: CadenceUiState,
    today: LocalDate,
    onProjectClick: (Project) -> Unit,
    onInbox: () -> Unit,
    onToday: () -> Unit,
    onCreateProject: (String, String, Long?) -> Unit,
    onSettings: () -> Unit,
) {
    val tree = state.projects.toTree()
    var createOpen by remember { mutableStateOf(false) }
    val collapsed = remember { androidx.compose.runtime.mutableStateMapOf<Long, Boolean>() }

    val inboxCount = state.tasks.count { it.isInbox && !it.isDone }
    val todayCount = state.tasks.count { !it.isDone && it.dueDate != null && !it.dueDate.isAfter(today) }
    val recurringCount = state.tasks.count { !it.isDone && it.recurrence != null }

    Column(modifier = Modifier.fillMaxSize()) {
        de.andi1984.cadence.ui.components.ScreenHeader(title = "Projects") {
            IconButton(onClick = { createOpen = true }) {
                Icon(AppIcons.CreateNewFolder, contentDescription = "New project")
            }
            IconButton(onClick = onSettings) {
                Icon(AppIcons.Settings, contentDescription = "Settings")
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
                    label = "Inbox",
                    count = inboxCount,
                    highlighted = true,
                    onClick = onInbox,
                )
            }
            item {
                QuickRow(
                    icon = AppIcons.Today,
                    label = "Today",
                    count = todayCount,
                    highlighted = false,
                    onClick = onToday,
                )
            }
            item {
                QuickRow(
                    icon = AppIcons.EventRepeat,
                    label = "Recurring",
                    count = recurringCount,
                    highlighted = false,
                    onClick = {},
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                SectionHeader("Projects", modifier = Modifier.padding(start = 10.dp))
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
                    )
                }
                if (!isCollapsed) {
                    items(node.children.size, key = { "c-${node.children[it].id}" }) { index ->
                        val child = node.children[index]
                        val childTasks = state.tasks.filter { it.projectId == child.id }
                        SubprojectRow(
                            project = child,
                            count = childTasks.count { !it.isDone },
                            overdue = childTasks.count { it.isOverdue(today) },
                            onClick = { onProjectClick(child) },
                        )
                    }
                }
            }
        }
    }

    if (createOpen) {
        NewProjectDialog(
            roots = state.projects.filter { it.parentId == null },
            onDismiss = { createOpen = false },
            onCreate = { name, color, parentId ->
                onCreateProject(name, color, parentId)
                createOpen = false
            },
        )
    }
}

private fun dueThisWeek(state: CadenceUiState, projectId: Long, today: LocalDate): Int =
    state.tasksIn(projectId).count {
        !it.isDone && it.dueDate != null && !it.dueDate.isAfter(today.plusDays(7))
    }

private fun rootSubtitle(children: Int, dueThisWeek: Int): String? {
    val parts = buildList {
        if (children == 1) add("1 subproject") else if (children > 1) add("$children subprojects")
        if (dueThisWeek > 0) add("$dueThisWeek due this week")
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
            .background(if (highlighted) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
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
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(end = 12.dp),
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
                    contentDescription = if (collapsed) "Expand" else "Collapse",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
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
                text = "$overdue overdue",
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
}

@Composable
private fun SubprojectRow(
    project: Project,
    count: Int,
    overdue: Int,
    onClick: () -> Unit,
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
                        text = if (overdue == 1) "1 overdue" else "$overdue overdue",
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
    }
}

@Composable
private fun NewProjectDialog(
    roots: List<Project>,
    onDismiss: () -> Unit,
    onCreate: (String, String, Long?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(PROJECT_COLORS.first()) }
    var parentId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PROJECT_COLORS.forEach { option ->
                        Spacer(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(parseColor(option))
                                .then(
                                    if (option == color) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { color = option },
                        )
                    }
                }
                Text(
                    text = "Nest under",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    de.andi1984.cadence.ui.components.CadenceChip(
                        label = "Top level",
                        selected = parentId == null,
                        onClick = { parentId = null },
                    )
                }
                roots.forEach { root ->
                    de.andi1984.cadence.ui.components.CadenceChip(
                        label = root.name,
                        selected = parentId == root.id,
                        onClick = { parentId = root.id },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, color, parentId) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
