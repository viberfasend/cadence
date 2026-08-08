package de.andi1984.cadence.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import de.andi1984.cadence.R
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.FittedLabel
import de.andi1984.cadence.ui.detail.TaskDetailScreen
import de.andi1984.cadence.ui.inbox.InboxScreen
import de.andi1984.cadence.ui.inbox.TriageScreen
import de.andi1984.cadence.ui.projects.ProjectDetailScreen
import de.andi1984.cadence.ui.projects.ProjectsScreen
import de.andi1984.cadence.ui.quickadd.QuickAddSheet
import de.andi1984.cadence.ui.search.SearchScreen
import de.andi1984.cadence.ui.settings.SettingsScreen
import de.andi1984.cadence.ui.today.TodayScreen
import de.andi1984.cadence.ui.upcoming.UpcomingScreen
import java.time.LocalDate

object Routes {
    const val TODAY = "today"
    const val UPCOMING = "upcoming"
    const val INBOX = "inbox"
    const val PROJECTS = "projects"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val TRIAGE = "triage"
    const val TASK = "task/{taskId}"
    const val PROJECT = "project/{projectId}"

    fun task(id: Long) = "task/$id"

    fun project(id: Long) = "project/$id"
}

private data class BottomDestination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadenceApp(viewModel: CadenceViewModel, state: CadenceUiState, intentTaskId: Long = -1L) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val today = remember { LocalDate.now() }

    // Handle deep linking from reminder notifications
    LaunchedEffect(intentTaskId) {
        if (intentTaskId > 0) {
            navController.navigate(Routes.task(intentTaskId)) {
                // Clear back stack to start fresh from the task detail
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    var quickAddOpen by remember { mutableStateOf(false) }
    var quickAddProjectId by remember { mutableStateOf<Long?>(null) }

    val destinations = listOf(
        BottomDestination(Routes.TODAY, R.string.nav_today, AppIcons.Today),
        BottomDestination(Routes.UPCOMING, R.string.nav_upcoming, AppIcons.CalendarMonth),
        BottomDestination(Routes.INBOX, R.string.nav_inbox, AppIcons.Inbox),
        BottomDestination(Routes.PROJECTS, R.string.nav_projects, AppIcons.Folder),
    )
    val showChrome = currentRoute in destinations.map { it.route }
    val inboxCount = state.inboxTasks().count { !it.isDone }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showChrome) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    destinations.forEach { destination ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (destination.route == Routes.INBOX && inboxCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$inboxCount") } }) {
                                        Icon(destination.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(destination.icon, contentDescription = null)
                                }
                            },
                            label = { FittedLabel(stringResource(destination.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (showChrome) {
                ExtendedFloatingActionButton(
                    onClick = {
                        quickAddProjectId = null
                        quickAddOpen = true
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(AppIcons.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.nav_add_task)) },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(navController = navController, startDestination = Routes.TODAY) {
                composable(Routes.TODAY) {
                    TodayScreen(
                        state = state,
                        today = today,
                        onTaskClick = { navController.navigate(Routes.task(it.id)) },
                        onToggle = viewModel::toggleTask,
                        onRescheduleAll = viewModel::rescheduleOverdue,
                        onSortChange = viewModel::setSortMode,
                        onSearch = { navController.navigate(Routes.SEARCH) },
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.UPCOMING) {
                    UpcomingScreen(
                        state = state,
                        today = today,
                        onTaskClick = { navController.navigate(Routes.task(it.id)) },
                        onToggle = viewModel::toggleTask,
                    )
                }
                composable(Routes.INBOX) {
                    InboxScreen(
                        state = state,
                        today = today,
                        onTaskClick = { navController.navigate(Routes.task(it.id)) },
                        onToggle = viewModel::toggleTask,
                        onTriage = { navController.navigate(Routes.TRIAGE) },
                    )
                }
                composable(Routes.PROJECTS) {
                    ProjectsScreen(
                        state = state,
                        today = today,
                        onProjectClick = { navController.navigate(Routes.project(it.id)) },
                        onInbox = { navController.navigate(Routes.INBOX) },
                        onToday = { navController.navigate(Routes.TODAY) },
                        onCreateProject = viewModel::addProject,
                        onEditProject = viewModel::editProject,
                        onDeleteProject = viewModel::deleteProject,
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        state = state,
                        today = today,
                        onBack = { navController.popBackStack() },
                        onTaskClick = { navController.navigate(Routes.task(it.id)) },
                        onToggle = viewModel::toggleTask,
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onThemeChange = viewModel::setTheme,
                        onDensityChange = viewModel::setDensity,
                        onShowCompletedChange = viewModel::setShowCompleted,
                        onExport = viewModel::exportBackup,
                        onImport = viewModel::importBackup,
                        onClearBackupOutcome = viewModel::clearBackupOutcome,
                        onEnableAutoBackup = viewModel::enableAutoBackup,
                        onDisableAutoBackup = viewModel::disableAutoBackup,
                        onDeclineAutoBackup = viewModel::declineAutoBackup,
                    )
                }
                composable(Routes.TRIAGE) {
                    TriageScreen(
                        state = state,
                        today = today,
                        onClose = { navController.popBackStack() },
                        onSetPriority = viewModel::setPriority,
                        onSetDueDate = viewModel::setDueDate,
                        onSetProject = viewModel::setProject,
                    )
                }
                composable(Routes.TASK) { entry ->
                    val taskId = entry.arguments?.getString("taskId")?.toLongOrNull()
                    val task = state.tasks.firstOrNull { it.id == taskId }
                    TaskDetailScreen(
                        task = task,
                        state = state,
                        today = today,
                        onBack = { navController.popBackStack() },
                        onSave = viewModel::saveTask,
                        onToggle = viewModel::toggleTask,
                        onDelete = {
                            viewModel.deleteTask(it)
                            // Removing a subtask keeps you on the task you were looking at.
                            if (it.id == task?.id) navController.popBackStack()
                        },
                        onSnooze = { viewModel.snooze(it) },
                        onOpenTask = { navController.navigate(Routes.task(it.id)) },
                        onAddSubtask = viewModel::addSubtask,
                        onMoveToProject = viewModel::setProject,
                    )
                }
                composable(Routes.PROJECT) { entry ->
                    val projectId = entry.arguments?.getString("projectId")?.toLongOrNull()
                    ProjectDetailScreen(
                        projectId = projectId,
                        state = state,
                        today = today,
                        onBack = { navController.popBackStack() },
                        onTaskClick = { navController.navigate(Routes.task(it.id)) },
                        onProjectClick = { navController.navigate(Routes.project(it.id)) },
                        onToggle = viewModel::toggleTask,
                        onAddTask = {
                            quickAddProjectId = projectId
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
