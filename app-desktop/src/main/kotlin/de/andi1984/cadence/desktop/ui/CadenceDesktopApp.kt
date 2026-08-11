package de.andi1984.cadence.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.desktop.data.DesktopBackupFilePicker
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.CadenceViewModel
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.detail.TaskDetailScreen
import de.andi1984.cadence.ui.inbox.InboxScreen
import de.andi1984.cadence.ui.inbox.TriageScreen
import de.andi1984.cadence.ui.platform.AppInfo
import de.andi1984.cadence.ui.projects.ProjectDetailScreen
import de.andi1984.cadence.ui.projects.ProjectsScreen
import de.andi1984.cadence.ui.quickadd.QuickAddSheet
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.ui.search.SearchScreen
import de.andi1984.cadence.ui.settings.SettingsScreen
import de.andi1984.cadence.ui.today.TodayScreen
import de.andi1984.cadence.ui.upcoming.UpcomingScreen
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate

/**
 * Desktop's answer to `:app-android`'s `CadenceApp`/`Routes` (ADR 0001 §8): a `NavigationRail`
 * sidebar stands in for the bottom bar, a plain button in it for the FAB, and a hand-rolled
 * back stack stands in for `NavHost` — `androidx.navigation.compose` is an Android artifact this
 * module has no dependency on. Every screen composable below is the exact one Android renders;
 * only the shell around them differs.
 */
private sealed interface Route {
    data object Today : Route
    data object Upcoming : Route
    data object Inbox : Route
    data object Projects : Route
    data object Search : Route
    data object Settings : Route
    data object Triage : Route
    data class TaskDetail(val taskId: String) : Route
    data class ProjectDetail(val projectId: String) : Route
}

private data class RailDestination(val route: Route, val label: StringResource, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadenceDesktopApp(
    viewModel: CadenceViewModel,
    state: CadenceUiState,
    appInfo: AppInfo,
    quickAddRequested: Boolean,
    onQuickAddHandled: () -> Unit,
) {
    val backupFilePicker = remember { DesktopBackupFilePicker() }
    var backStack by remember { mutableStateOf<List<Route>>(listOf(Route.Today)) }
    val current = backStack.last()
    val today = remember { LocalDate.now() }

    var quickAddOpen by remember { mutableStateOf(false) }
    var quickAddProjectId by remember { mutableStateOf<String?>(null) }

    if (quickAddRequested) {
        quickAddProjectId = null
        quickAddOpen = true
        onQuickAddHandled()
    }

    fun navigateTo(route: Route) {
        backStack = listOf(route)
    }

    fun push(route: Route) {
        backStack = backStack + route
    }

    fun back() {
        if (backStack.size > 1) backStack = backStack.dropLast(1)
    }

    val topLevel = listOf(
        RailDestination(Route.Today, Res.string.nav_today, AppIcons.Today),
        RailDestination(Route.Upcoming, Res.string.nav_upcoming, AppIcons.CalendarMonth),
        RailDestination(Route.Inbox, Res.string.nav_inbox, AppIcons.Inbox),
        RailDestination(Route.Projects, Res.string.nav_projects, AppIcons.Folder),
    )
    val inboxCount = state.inboxTasks().count { !it.isDone }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Spacer(modifier = Modifier.height(8.dp))
                topLevel.forEach { destination ->
                    NavigationRailItem(
                        selected = current == destination.route,
                        onClick = { navigateTo(destination.route) },
                        icon = {
                            if (destination.route == Route.Inbox && inboxCount > 0) {
                                BadgedBox(badge = { Badge { Text("$inboxCount") } }) {
                                    Icon(destination.icon, contentDescription = null)
                                }
                            } else {
                                Icon(destination.icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(destination.label)) },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                NavigationRailItem(
                    selected = current == Route.Search,
                    onClick = { push(Route.Search) },
                    icon = { Icon(AppIcons.Search, contentDescription = null) },
                    label = { Text(stringResource(Res.string.search_title)) },
                )
                NavigationRailItem(
                    selected = current == Route.Settings,
                    onClick = { push(Route.Settings) },
                    icon = { Icon(AppIcons.Settings, contentDescription = null) },
                    label = { Text(stringResource(Res.string.settings_title)) },
                )
                Spacer(modifier = Modifier.height(16.dp))
                NavigationRailItem(
                    selected = false,
                    onClick = {
                        quickAddProjectId = null
                        quickAddOpen = true
                    },
                    icon = { Icon(AppIcons.Add, contentDescription = null) },
                    label = { Text(stringResource(Res.string.nav_add_task)) },
                )
            }
            VerticalDivider(modifier = Modifier.fillMaxHeight())

            Box(modifier = Modifier.fillMaxSize()) {
                when (val route = current) {
                    Route.Today -> TodayScreen(
                        state = state,
                        today = today,
                        onTaskClick = { push(Route.TaskDetail(it.id)) },
                        onToggle = viewModel::toggleTask,
                        onRescheduleAll = viewModel::rescheduleOverdue,
                        onSortChange = viewModel::setSortMode,
                        onSearch = { push(Route.Search) },
                        onSettings = { push(Route.Settings) },
                    )

                    Route.Upcoming -> UpcomingScreen(
                        state = state,
                        today = today,
                        onTaskClick = { push(Route.TaskDetail(it.id)) },
                        onToggle = viewModel::toggleTask,
                    )

                    Route.Inbox -> InboxScreen(
                        state = state,
                        today = today,
                        onTaskClick = { push(Route.TaskDetail(it.id)) },
                        onToggle = viewModel::toggleTask,
                        onTriage = { push(Route.Triage) },
                    )

                    Route.Projects -> ProjectsScreen(
                        state = state,
                        today = today,
                        onProjectClick = { push(Route.ProjectDetail(it.id)) },
                        onInbox = { navigateTo(Route.Inbox) },
                        onToday = { navigateTo(Route.Today) },
                        onCreateProject = viewModel::addProject,
                        onEditProject = viewModel::editProject,
                        onDeleteProject = viewModel::deleteProject,
                        onSettings = { push(Route.Settings) },
                    )

                    Route.Search -> SearchScreen(
                        state = state,
                        today = today,
                        onBack = { back() },
                        onTaskClick = { push(Route.TaskDetail(it.id)) },
                        onToggle = viewModel::toggleTask,
                    )

                    Route.Settings -> SettingsScreen(
                        state = state,
                        appInfo = appInfo,
                        filePicker = backupFilePicker,
                        onBack = { back() },
                        onThemeChange = viewModel::setTheme,
                        onDensityChange = viewModel::setDensity,
                        onShowCompletedChange = viewModel::setShowCompleted,
                        onExport = viewModel::exportBackup,
                        onImport = viewModel::importBackup,
                        onClearBackupOutcome = viewModel::clearBackupOutcome,
                        onRemindersChange = viewModel::setRemindersEnabled,
                        onSignIn = viewModel::signIn,
                        onSyncNow = { viewModel.syncNow() },
                        onSignOut = { viewModel.signOut() },
                    )

                    Route.Triage -> TriageScreen(
                        state = state,
                        today = today,
                        onClose = { back() },
                        onSetPriority = viewModel::setPriority,
                        onSetDueDate = viewModel::setDueDate,
                        onSetProject = viewModel::setProject,
                    )

                    is Route.TaskDetail -> {
                        val task = state.tasks.firstOrNull { it.id == route.taskId }
                        TaskDetailScreen(
                            task = task,
                            state = state,
                            today = today,
                            onBack = { back() },
                            onSave = viewModel::saveTask,
                            onToggle = viewModel::toggleTask,
                            onDelete = {
                                viewModel.deleteTask(it)
                                if (it.id == task?.id) back()
                            },
                            onSnooze = { viewModel.snooze(it) },
                            onOpenTask = { push(Route.TaskDetail(it.id)) },
                            onAddSubtask = viewModel::addSubtask,
                            onMoveToProject = viewModel::setProject,
                        )
                    }

                    is Route.ProjectDetail -> ProjectDetailScreen(
                        projectId = route.projectId,
                        state = state,
                        today = today,
                        onBack = { back() },
                        onTaskClick = { push(Route.TaskDetail(it.id)) },
                        onProjectClick = { push(Route.ProjectDetail(it.id)) },
                        onToggle = viewModel::toggleTask,
                        onAddTask = {
                            quickAddProjectId = route.projectId
                            quickAddOpen = true
                        },
                        onCreateProject = viewModel::addProject,
                        onEditProject = viewModel::editProject,
                        onDeleteProject = viewModel::deleteProject,
                    )
                }
            }
        }
    }

    if (quickAddOpen) {
        QuickAddSheet(
            projects = state.projects,
            today = today,
            defaultProjectId = quickAddProjectId,
            onDismiss = { quickAddOpen = false },
            onSubmit = { parsed ->
                viewModel.addParsedTask(parsed, quickAddProjectId)
                quickAddOpen = false
            },
        )
    }
}
