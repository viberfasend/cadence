package de.andi1984.cadence.ui

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.andi1984.cadence.ui.assistant.AssistantScreen
import de.andi1984.cadence.ui.assistant.rememberSpeechVoiceInput
import de.andi1984.cadence.ui.attachments.rememberSafAttachmentFilePicker
import de.andi1984.cadence.ui.backup.rememberSafBackupFilePicker
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.FittedLabel
import de.andi1984.cadence.ui.components.SyncControls
import de.andi1984.cadence.ui.components.SyncFailureSnackbar
import de.andi1984.cadence.ui.components.UndoSnackbar
import de.andi1984.cadence.ui.detail.TaskDetailScreen
import de.andi1984.cadence.ui.platform.AppInfo
import de.andi1984.cadence.ui.inbox.InboxScreen
import de.andi1984.cadence.ui.inbox.TriageScreen
import de.andi1984.cadence.ui.projects.ProjectDetailScreen
import de.andi1984.cadence.ui.projects.ProjectsScreen
import de.andi1984.cadence.ui.quickadd.QuickAddSheet
import de.andi1984.cadence.ui.search.SearchScreen
import de.andi1984.cadence.ui.tags.TagDetailScreen
import de.andi1984.cadence.ui.tags.TagsScreen
import de.andi1984.cadence.ui.settings.SettingsScreen
import de.andi1984.cadence.ui.today.TodayScreen
import de.andi1984.cadence.ui.upcoming.UpcomingScreen
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

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
    const val TAGS = "tags"
    const val TAG = "tag/{tagId}"
    const val ASSISTANT = "assistant"

    fun task(id: String) = "task/$id"

    fun project(id: String) = "project/$id"

    fun tag(id: String) = "tag/$id"
}

private data class BottomDestination(
    val route: String,
    val label: StringResource,
    val icon: ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadenceApp(
    viewModel: CadenceViewModel,
    state: CadenceUiState,
    appInfo: AppInfo,
    // A reminder notification launches straight into its task rather than navigating there once
    // the NavHost is up — see the crash that fix was for (#29 follow-up).
    startDestination: String = Routes.TODAY,
    // The quick-add widget opens the composer, not a screen. It rides on top of
    // [startDestination] rather than replacing it, so "add a task" leaves the user where they
    // would otherwise have landed once the sheet is dismissed.
    openQuickAdd: Boolean = false,
    // Voice capture (#46): the text Assistant heard, already routed through the same composer —
    // see MainActivity's reading of AppActionsIntents.EXTRA_ITEM_TEXT.
    voiceQuickAddText: String? = null,
) {
    val navController = rememberNavController()
    val backupFilePicker = rememberSafBackupFilePicker()
    val attachmentFilePicker = rememberSafAttachmentFilePicker()
    val voiceInput = rememberSpeechVoiceInput()
    val assistantState by viewModel.assistantState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Recomputed at midnight rather than captured once (#115): this was `remember { LocalDate.now() }`
    // and never changed again, so an app left open (or merely backgrounded with the process
    // alive — no Activity recreation) past midnight kept drawing yesterday.
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            // A second past midnight, not exactly on it: `LocalDate.now()` a hair early would
            // return the day that is ending and the loop would spin until the clock caught up.
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L) + 1_000L)
            today = LocalDate.now()
        }
    }

    var quickAddOpen by remember { mutableStateOf(openQuickAdd || voiceQuickAddText != null) }
    var quickAddProjectId by remember { mutableStateOf<String?>(null) }
    // Consumed once: a later, manually opened sheet must not resurrect Assistant's transcript.
    var pendingVoiceQuickAddText by remember { mutableStateOf(voiceQuickAddText) }

    val destinations = listOf(
        BottomDestination(Routes.TODAY, Res.string.nav_today, AppIcons.Today),
        BottomDestination(Routes.UPCOMING, Res.string.nav_upcoming, AppIcons.CalendarMonth),
        BottomDestination(Routes.INBOX, Res.string.nav_inbox, AppIcons.Inbox),
        BottomDestination(Routes.PROJECTS, Res.string.nav_projects, AppIcons.Folder),
    )
    val showChrome = currentRoute in destinations.map { it.route }
    val inboxCount = state.inboxTasks().count { !it.isDone }

    // Android's manual gesture is the pull, so no refresh icon joins sort and search up there
    // (ADR 0002, decision 13). Signed out, `SyncControls` renders nothing and arms no gesture.
    val syncControls = remember(viewModel, navController) {
        SyncControls(
            onRefresh = { viewModel.syncNow() },
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            pullToRefresh = true,
        )
    }

    // Every failed round says so, wherever the user happens to be (ADR 0002, decision 14).
    SyncFailureSnackbar(failures = viewModel.syncFailures, hostState = snackbarHostState)

    // A delete is held back for a few seconds while this banner is up; tapping Undo cancels
    // the deferred write, so it costs no database transaction at all.
    UndoSnackbar(
        message = state.snackbarMessage,
        hostState = snackbarHostState,
        onUndo = viewModel::undo,
        onDismiss = viewModel::dismissSnackbar,
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    text = { Text(stringResource(Res.string.nav_add_task)) },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(navController = navController, startDestination = startDestination) {
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
                        onAssistant = { navController.navigate(Routes.ASSISTANT) },
                        syncControls = syncControls,
                    )
                }
                composable(Routes.UPCOMING) {
                    UpcomingScreen(
                        state = state,
                        today = today,
                        onTaskClick = { navController.navigate(Routes.task(it.id)) },
                        onToggle = viewModel::toggleTask,
                        syncControls = syncControls,
                    )
                }
                composable(Routes.INBOX) {
                    InboxScreen(
                        state = state,
                        today = today,
                        onTaskClick = { navController.navigate(Routes.task(it.id)) },
                        onToggle = viewModel::toggleTask,
                        onTriage = { navController.navigate(Routes.TRIAGE) },
                        syncControls = syncControls,
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
                        onTags = { navController.navigate(Routes.TAGS) },
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        syncControls = syncControls,
                    )
                }
                composable(Routes.TAGS) {
                    TagsScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onTagClick = { navController.navigate(Routes.tag(it.id)) },
                        onCreateTag = viewModel::addTag,
                        onEditTag = viewModel::editTag,
                        onDeleteTag = viewModel::deleteTag,
                        onReorder = viewModel::reorderTags,
                    )
                }
                composable(Routes.TAG) { entry ->
                    TagDetailScreen(
                        tagId = entry.arguments?.getString("tagId"),
                        state = state,
                        today = today,
                        onBack = { navController.popBackStack() },
                        onTaskClick = { navController.navigate(Routes.task(it.id)) },
                        onToggle = viewModel::toggleTask,
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
                composable(Routes.ASSISTANT) {
                    AssistantScreen(
                        state = state,
                        assistant = assistantState,
                        voiceInput = voiceInput,
                        onBack = { navController.popBackStack() },
                        onAsk = viewModel::ask,
                        onClear = viewModel::clearConversation,
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        state = state,
                        appInfo = appInfo,
                        filePicker = backupFilePicker,
                        onBack = { navController.popBackStack() },
                        onThemeChange = viewModel::setTheme,
                        onDensityChange = viewModel::setDensity,
                        onShowCompletedChange = viewModel::setShowCompleted,
                        onExport = viewModel::exportBackup,
                        onImport = viewModel::importBackup,
                        onClearBackupOutcome = viewModel::clearBackupOutcome,
                        onRemindersChange = viewModel::setRemindersEnabled,
                        onReminderLeadMinutesChange = viewModel::setReminderLeadMinutes,
                        onSignIn = viewModel::signIn,
                        onSyncNow = { viewModel.syncNow() },
                        onSignOut = { viewModel.signOut() },
                        onWipe = { viewModel.wipeEverything() },
                        onClaudeApiKeyChange = viewModel::setClaudeApiKey,
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
                    val taskId = entry.arguments?.getString("taskId")
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
                        onMoveToSection = viewModel::setSection,
                        onToggleTag = viewModel::toggleTag,
                        onCreateTag = viewModel::addTag,
                        attachmentPicker = attachmentFilePicker,
                        onAddFileAttachment = viewModel::addFileAttachment,
                        onAddLinkAttachment = viewModel::addLinkAttachment,
                        onOpenAttachment = viewModel::openAttachment,
                        onRelocateAttachment = viewModel::relocateAttachment,
                        onRemoveAttachment = viewModel::deleteAttachment,
                        attachmentBlobFile = viewModel::blobFile,
                    )
                }
                composable(Routes.PROJECT) { entry ->
                    val projectId = entry.arguments?.getString("projectId")
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
                        onCreateSection = viewModel::addSection,
                        onRenameSection = viewModel::renameSection,
                        onDeleteSection = viewModel::deleteSection,
                    )
                }
            }
        }
    }

    if (quickAddOpen) {
        QuickAddSheet(
            projects = state.projects,
            tags = state.tags,
            today = today,
            defaultProjectId = quickAddProjectId,
            onDismiss = {
                quickAddOpen = false
                pendingVoiceQuickAddText = null
            },
            onSubmit = { parsed ->
                viewModel.addParsedTask(parsed, quickAddProjectId)
                quickAddOpen = false
                pendingVoiceQuickAddText = null
            },
            initialText = pendingVoiceQuickAddText ?: "",
        )
    }
}
