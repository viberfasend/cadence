package de.andi1984.cadence.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.desktop.data.DesktopWorkspace
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.toTree
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceContextMenu
import de.andi1984.cadence.ui.components.ProjectContextMenuItems
import de.andi1984.cadence.ui.components.ProjectSwatch
import de.andi1984.cadence.ui.dnd.DragPayload
import de.andi1984.cadence.ui.dnd.DropCaret
import de.andi1984.cadence.ui.dnd.DropTarget
import de.andi1984.cadence.ui.dnd.OrderedList
import de.andi1984.cadence.ui.dnd.cadenceDragSource
import de.andi1984.cadence.ui.dnd.cadenceDropTarget
import de.andi1984.cadence.ui.dnd.dragSourceAlpha
import de.andi1984.cadence.ui.dnd.dropHighlight
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.awt.Cursor
import java.time.LocalDate

/**
 * The desktop's navigation, and the thing that replaced `NavigationRail`.
 *
 * A rail of four icons is what a phone's bottom bar becomes when it is turned on its side; it is
 * not what a desktop application's left-hand side is for. This is: the views, then the whole
 * project tree with its counts, resizable, foldable, and — the part a rail could never be — a
 * **drop target on every row**. Dragging a task onto a project files it, onto Inbox unfiles it,
 * onto Today dates it; dragging a project onto another nests it, and into a gap orders it.
 *
 * Every rule about what may land where is `resolveDrop`'s (`:ui`'s `dnd/DragModel.kt`); this file
 * only says which target each row registers.
 */
@Composable
fun CadenceSidebar(
    state: CadenceUiState,
    today: LocalDate,
    workspace: DesktopWorkspace,
    current: Route,
    onSwitchTo: (Route) -> Unit,
    onOpenProject: (Project) -> Unit,
    onOpenTag: (Tag) -> Unit,
    onToggleProjectFold: (String) -> Unit,
    onWidthChange: (Float) -> Unit,
    onToggleCollapsed: () -> Unit,
    onNewProject: (String?) -> Unit,
    onEditProject: (Project) -> Unit,
    onDeleteProject: (Project) -> Unit,
    onNestProject: (Project, String?) -> Unit,
    onAddTask: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tree = state.projects.toTree()
    val inboxCount = state.inboxTasks().count { !it.isDone }
    val todayCount = state.tasks.count { !it.isDone && it.dueDate?.isAfter(today) == false }
    val upcomingCount = state.tasks.count { !it.isDone && it.dueDate?.isAfter(today) == true }

    Row(modifier = modifier.fillMaxHeight().background(scheme.surfaceContainerLow)) {
        if (workspace.sidebarCollapsed) {
            CollapsedRail(
                current = current,
                inboxCount = inboxCount,
                onSwitchTo = onSwitchTo,
                onExpand = onToggleCollapsed,
                onAddTask = { onAddTask(null) },
            )
        } else {
            Column(modifier = Modifier.width(workspace.sidebarWidth.dp).fillMaxHeight()) {
                SidebarHeader(onToggleCollapsed = onToggleCollapsed, onAddTask = { onAddTask(null) })

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    item { SidebarLabel(stringResource(Res.string.nav_views)) }
                    item {
                        SidebarRow(
                            icon = AppIcons.Today,
                            label = stringResource(Res.string.nav_today),
                            count = todayCount,
                            selected = current == Route.Today,
                            // A task dropped on Today is due today — the one gesture that dates
                            // something without opening it.
                            dropKey = "view:today",
                            dropTarget = DropTarget.OntoDate(today),
                            onClick = { onSwitchTo(Route.Today) },
                        )
                    }
                    item {
                        SidebarRow(
                            icon = AppIcons.CalendarMonth,
                            label = stringResource(Res.string.nav_upcoming),
                            count = upcomingCount,
                            selected = current == Route.Upcoming,
                            onClick = { onSwitchTo(Route.Upcoming) },
                        )
                    }
                    item {
                        SidebarRow(
                            icon = AppIcons.Inbox,
                            label = stringResource(Res.string.nav_inbox),
                            count = inboxCount,
                            selected = current == Route.Inbox,
                            // Two meanings, one row: a task loses its project, a subproject is
                            // lifted back to the root list. `resolveDrop` tells them apart.
                            dropKey = "view:inbox",
                            dropTarget = DropTarget.IntoProject(null),
                            onClick = { onSwitchTo(Route.Inbox) },
                        )
                    }
                    item {
                        SidebarRow(
                            icon = AppIcons.Search,
                            label = stringResource(Res.string.search_title),
                            count = null,
                            selected = current == Route.Search,
                            onClick = { onSwitchTo(Route.Search) },
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SidebarLabel(
                                text = stringResource(Res.string.projects_section),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onNewProject(null) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    AppIcons.Add,
                                    contentDescription = stringResource(Res.string.projects_new),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    val rootIds = tree.map { it.project.id }
                    itemsIndexed(tree) { index, node ->
                        val folded = node.project.id in workspace.collapsedProjects
                        Column {
                            DropCaret(
                                key = "projects:root:$index",
                                target = DropTarget.Between(OrderedList.Projects(null), index, rootIds),
                            )
                            ProjectSidebarRow(
                                project = node.project,
                                state = state,
                                today = today,
                                depth = 0,
                                selected = current == Route.ProjectDetail(node.project.id),
                                foldable = node.children.isNotEmpty(),
                                folded = folded,
                                onFold = { onToggleProjectFold(node.project.id) },
                                onClick = { onOpenProject(node.project) },
                                onNewProject = onNewProject,
                                onEditProject = onEditProject,
                                onDeleteProject = onDeleteProject,
                                onNestProject = onNestProject,
                                onAddTask = onAddTask,
                            )
                            if (!folded) {
                                val childIds = node.children.map { it.id }
                                node.children.forEachIndexed { childIndex, child ->
                                    DropCaret(
                                        key = "projects:${node.project.id}:$childIndex",
                                        target = DropTarget.Between(
                                            OrderedList.Projects(node.project.id),
                                            childIndex,
                                            childIds,
                                        ),
                                    )
                                    ProjectSidebarRow(
                                        project = child,
                                        state = state,
                                        today = today,
                                        depth = 1,
                                        selected = current == Route.ProjectDetail(child.id),
                                        foldable = false,
                                        folded = false,
                                        onFold = {},
                                        onClick = { onOpenProject(child) },
                                        onNewProject = onNewProject,
                                        onEditProject = onEditProject,
                                        onDeleteProject = onDeleteProject,
                                        onNestProject = onNestProject,
                                        onAddTask = onAddTask,
                                    )
                                }
                                DropCaret(
                                    key = "projects:${node.project.id}:end",
                                    target = DropTarget.Between(
                                        OrderedList.Projects(node.project.id),
                                        node.children.size,
                                        childIds,
                                    ),
                                )
                            }
                        }
                    }
                    item {
                        DropCaret(
                            key = "projects:root:end",
                            target = DropTarget.Between(OrderedList.Projects(null), tree.size, rootIds),
                        )
                    }

                    // Below the project tree, and flat: a tag is not a place in the hierarchy, so
                    // it gets no fold, no nesting and no drop target. The group disappears
                    // entirely when there are no tags — an empty heading in a sidebar is noise,
                    // and the Projects screen is where one is created.
                    if (state.tags.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            SidebarLabel(stringResource(Res.string.tags_title))
                        }
                        items(state.tags, key = { it.id }) { tag ->
                            SidebarRow(
                                icon = AppIcons.Tag,
                                label = "@" + tag.handle,
                                // The open count, unlike the Tags screen's total: a sidebar
                                // number is "how much is waiting", the same as every row above it.
                                count = state.openCountForTag(tag.id),
                                selected = current == Route.TagDetail(tag.id),
                                onClick = { onOpenTag(tag) },
                            )
                        }
                    }
                }

                SidebarRow(
                    icon = AppIcons.Settings,
                    label = stringResource(Res.string.settings_title),
                    count = null,
                    selected = current == Route.Settings,
                    onClick = { onSwitchTo(Route.Settings) },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            SidebarResizeHandle(width = workspace.sidebarWidth, onWidthChange = onWidthChange)
        }
    }
}

@Composable
private fun SidebarHeader(onToggleCollapsed: () -> Unit, onAddTask: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Cadence",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAddTask) {
            Icon(AppIcons.Add, contentDescription = stringResource(Res.string.nav_add_task))
        }
        IconButton(onClick = onToggleCollapsed) {
            Icon(AppIcons.Sort, contentDescription = stringResource(Res.string.sidebar_toggle))
        }
    }
}

/** The 64dp version: the four views and nothing else, for a window that needs its width. */
@Composable
private fun CollapsedRail(
    current: Route,
    inboxCount: Int,
    onSwitchTo: (Route) -> Unit,
    onExpand: () -> Unit,
    onAddTask: () -> Unit,
) {
    Column(
        modifier = Modifier.width(64.dp).fillMaxHeight().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onExpand) {
            Icon(AppIcons.Sort, contentDescription = stringResource(Res.string.sidebar_toggle))
        }
        RailIcon(AppIcons.Add, Res.string.nav_add_task, selected = false, onClick = onAddTask)
        Spacer(modifier = Modifier.height(8.dp))
        RailIcon(AppIcons.Today, Res.string.nav_today, current == Route.Today) { onSwitchTo(Route.Today) }
        RailIcon(AppIcons.CalendarMonth, Res.string.nav_upcoming, current == Route.Upcoming) {
            onSwitchTo(Route.Upcoming)
        }
        Box {
            RailIcon(AppIcons.Inbox, Res.string.nav_inbox, current == Route.Inbox) { onSwitchTo(Route.Inbox) }
            if (inboxCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        RailIcon(AppIcons.Folder, Res.string.nav_projects, current == Route.Projects) {
            onSwitchTo(Route.Projects)
        }
        Spacer(modifier = Modifier.weight(1f))
        RailIcon(AppIcons.Settings, Res.string.settings_title, current == Route.Settings) {
            onSwitchTo(Route.Settings)
        }
    }
}

@Composable
private fun RailIcon(
    icon: ImageVector,
    label: org.jetbrains.compose.resources.StringResource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(label),
            tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SidebarLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** One row of the sidebar. [dropTarget] is what a drag may land on, or nothing. */
@Composable
private fun SidebarRow(
    icon: ImageVector,
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dropKey: Any? = null,
    dropTarget: DropTarget? = null,
    leading: (@Composable () -> Unit)? = null,
    indent: Int = 0,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val droppable = if (dropKey != null && dropTarget != null) {
        Modifier.cadenceDropTarget(dropKey, dropTarget).dropHighlight(dropKey)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (indent * 16).dp)
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .then(droppable)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leading != null) {
            leading()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        if (count != null && count > 0) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectSidebarRow(
    project: Project,
    state: CadenceUiState,
    today: LocalDate,
    depth: Int,
    selected: Boolean,
    foldable: Boolean,
    folded: Boolean,
    onFold: () -> Unit,
    onClick: () -> Unit,
    onNewProject: (String?) -> Unit,
    onEditProject: (Project) -> Unit,
    onDeleteProject: (Project) -> Unit,
    onNestProject: (Project, String?) -> Unit,
    onAddTask: (String?) -> Unit,
) {
    val tasks = state.tasksIn(project.id)
    val open = tasks.count { !it.isDone }
    val overdue = tasks.count { it.isOverdue(today) }
    val dropKey = "project:${project.id}"

    CadenceContextMenu(
        menu = { dismiss ->
            ProjectContextMenuItems(
                project = project,
                state = state,
                dismiss = dismiss,
                onOpen = { onClick() },
                onAddTask = { onAddTask(project.id) },
                onAddSubproject = { onNewProject(project.id) },
                onEdit = onEditProject,
                onNestUnder = onNestProject,
                onDelete = onDeleteProject,
            )
        },
    ) {
        SidebarRow(
            icon = AppIcons.Folder,
            label = project.name,
            count = open,
            selected = selected,
            onClick = onClick,
            indent = depth,
            dropKey = dropKey,
            dropTarget = DropTarget.IntoProject(project.id),
            modifier = Modifier
                .cadenceDragSource(DragPayload.ProjectDrag(project)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                .dragSourceAlpha(project.id),
            leading = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable(enabled = foldable, onClick = onFold),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (foldable) {
                            Icon(
                                imageVector = if (folded) AppIcons.ChevronRight else AppIcons.ExpandMore,
                                contentDescription = stringResource(
                                    if (folded) Res.string.action_expand else Res.string.action_collapse,
                                ),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    ProjectSwatch(colorHex = project.colorHex, size = 10)
                }
            },
            trailing = {
                if (overdue > 0) {
                    Text(
                        text = "$overdue",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}

/**
 * The draggable edge.
 *
 * 6dp of hit area with a resize cursor over it — a 1dp divider is a target nobody can hit, and a
 * sidebar that cannot be resized is the complaint every fixed-width sidebar gets.
 */
@Composable
private fun SidebarResizeHandle(width: Float, onWidthChange: (Float) -> Unit) {
    var dragged by remember(width) { mutableStateOf(width) }
    val resizeCursor = remember { PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)) }

    Box(
        modifier = Modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerHoverIcon(resizeCursor)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragged = width },
                    onDrag = { change, amount ->
                        change.consume()
                        dragged += amount.x / density
                        onWidthChange(dragged)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}
