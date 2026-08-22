package de.andi1984.cadence.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.desktop.data.DesktopBackupFilePicker
import de.andi1984.cadence.desktop.data.DesktopWorkspaceStore
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.CadenceViewModel
import androidx.compose.runtime.CompositionLocalProvider
import de.andi1984.cadence.ui.components.LocalRowSelection
import de.andi1984.cadence.ui.components.ProvideRowInteractions
import de.andi1984.cadence.ui.components.RowSelectionState
import de.andi1984.cadence.ui.components.RowInteractions
import de.andi1984.cadence.ui.components.SyncControls
import de.andi1984.cadence.ui.components.SyncFailureSnackbar
import de.andi1984.cadence.ui.components.UndoSnackbar
import de.andi1984.cadence.ui.detail.TaskDetailScreen
import de.andi1984.cadence.ui.dnd.DragAndDropHost
import de.andi1984.cadence.ui.inbox.InboxScreen
import de.andi1984.cadence.ui.inbox.TriageScreen
import de.andi1984.cadence.ui.platform.AppInfo
import de.andi1984.cadence.ui.projects.ProjectDetailScreen
import de.andi1984.cadence.ui.projects.ProjectDialogState
import de.andi1984.cadence.ui.projects.ProjectDialogs
import de.andi1984.cadence.ui.projects.ProjectsScreen
import de.andi1984.cadence.ui.quickadd.QuickAddSheet
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.ui.search.SearchScreen
import de.andi1984.cadence.ui.tags.TagDetailScreen
import de.andi1984.cadence.ui.tags.TagsScreen
import de.andi1984.cadence.ui.settings.SettingsScreen
import de.andi1984.cadence.ui.today.TodayScreen
import de.andi1984.cadence.ui.upcoming.UpcomingScreen
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate

/**
 * The desktop shell: a sidebar, a list pane, and — when the window is wide enough — a detail pane
 * beside it.
 *
 * What is *not* here is any screen: every one of them is the composable `:app-android` renders,
 * called with the same callbacks. The shell is the part that differs — where you are (a
 * [DesktopNavigator] with a real forward stack instead of `NavHost`), what the chrome around it
 * looks like ([CadenceSidebar] instead of a bottom bar), and the two interactions a pointer
 * expects: [DragAndDropHost] wraps everything so a row can be picked up, and [RowInteractions]
 * gives every row its right-click menu without a single screen signature changing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadenceDesktopApp(
    viewModel: CadenceViewModel,
    state: CadenceUiState,
    appInfo: AppInfo,
    today: LocalDate,
    workspaceStore: DesktopWorkspaceStore,
    navigator: DesktopNavigator,
    quickAddRequested: Boolean,
    onQuickAddHandled: () -> Unit,
    shortcutsRequested: Boolean,
    onShortcutsHandled: () -> Unit,
    paletteRequested: Boolean,
    onPaletteHandled: () -> Unit,
    selection: RowSelectionState,
) {
    val backupFilePicker = remember { DesktopBackupFilePicker() }
    val snackbarHostState = remember { SnackbarHostState() }
    val workspace by workspaceStore.state.collectAsState()

    var quickAddOpen by remember { mutableStateOf(false) }
    var quickAddProjectId by remember { mutableStateOf<String?>(null) }
    var projectDialog by remember { mutableStateOf<ProjectDialogState?>(null) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }

    fun openQuickAdd(projectId: String?) {
        quickAddProjectId = projectId
        quickAddOpen = true
    }

    if (quickAddRequested) {
        openQuickAdd(null)
        onQuickAddHandled()
    }
    if (shortcutsRequested) {
        shortcutsOpen = true
        onShortcutsHandled()
    }
    if (paletteRequested) {
        paletteOpen = true
        onPaletteHandled()
    }

    // The desktop has no pull gesture and no discoverable `Ctrl`+`R`, so the header carries a
    // button; `Ctrl`/`Cmd`+`R` in `main()` runs the same round (ADR 0002, decision 13).
    val syncControls = remember(viewModel) {
        SyncControls(
            onRefresh = { viewModel.syncNow() },
            onOpenSettings = { navigator.go(Route.Settings) },
            showRefreshControl = true,
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

    val rowInteractions = RowInteractions(
        enabled = true,
        state = state,
        today = today,
        onOpenTask = { navigator.go(Route.TaskDetail(it.id)) },
        onToggleTask = viewModel::toggleTask,
        onSetPriority = viewModel::setPriority,
        onSetDueDate = viewModel::setDueDate,
        onMoveTaskToProject = viewModel::setProject,
        onMoveTaskToSection = viewModel::setSection,
        onDuplicateTask = { task -> viewModel.duplicateTask(task, "${task.title} (copy)") },
        onDeleteTask = viewModel::deleteTask,
    )

    DragAndDropHost(state = state, onIntent = viewModel::applyDropIntent) {
        CompositionLocalProvider(LocalRowSelection provides selection) {
            ProvideRowInteractions(rowInteractions) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { innerPadding ->
                Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    CadenceSidebar(
                        state = state,
                        today = today,
                        workspace = workspace,
                        current = navigator.current,
                        onSwitchTo = navigator::switchTo,
                        onOpenProject = { navigator.go(Route.ProjectDetail(it.id)) },
                        onOpenTag = { navigator.go(Route.TagDetail(it.id)) },
                        onToggleProjectFold = workspaceStore::toggleProjectCollapsed,
                        onWidthChange = workspaceStore::setSidebarWidth,
                        onToggleCollapsed = {
                            workspaceStore.setSidebarCollapsed(!workspace.sidebarCollapsed)
                        },
                        onNewProject = { parentId ->
                            projectDialog = ProjectDialogState.Create(parentId)
                        },
                        onEditProject = { projectDialog = ProjectDialogState.Edit(it) },
                        onDeleteProject = { projectDialog = ProjectDialogState.Delete(it) },
                        onNestProject = { project, parentId ->
                            viewModel.editProject(project, project.name, project.colorHex, parentId)
                        },
                        onAddTask = ::openQuickAdd,
                    )
                    VerticalDivider(modifier = Modifier.fillMaxHeight())

                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        // Wide enough for two panes: the list keeps a workable width and the
                        // detail still has room for the notes field, which is what decides it.
                        val twoPane = maxWidth >= TWO_PANE_THRESHOLD
                        // Bound here, not read below: inside the `Row` the receiver is
                        // `RowScope` and `maxWidth` is no longer in scope.
                        val contentWidth = maxWidth
                        LaunchedEffect(twoPane) { navigator.onWidthChanged(twoPane) }

                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = if (navigator.detail != null) {
                                    Modifier.width(contentWidth * LIST_PANE_FRACTION).fillMaxHeight()
                                } else {
                                    Modifier.fillMaxSize()
                                },
                            ) {
                                RouteContent(
                                    route = navigator.current,
                                    viewModel = viewModel,
                                    state = state,
                                    today = today,
                                    appInfo = appInfo,
                                    navigator = navigator,
                                    syncControls = syncControls,
                                    backupFilePicker = backupFilePicker,
                                    onQuickAdd = ::openQuickAdd,
                                    onProjectDialog = { projectDialog = it },
                                )
                            }

                            navigator.detail?.let { detail ->
                                VerticalDivider(modifier = Modifier.fillMaxHeight())
                                Box(modifier = Modifier.fillMaxSize()) {
                                    RouteContent(
                                        route = detail,
                                        viewModel = viewModel,
                                        state = state,
                                        today = today,
                                        appInfo = appInfo,
                                        navigator = navigator,
                                        syncControls = syncControls,
                                        backupFilePicker = backupFilePicker,
                                        onQuickAdd = ::openQuickAdd,
                                        onProjectDialog = { projectDialog = it },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    // The project dialogs are the shell's now rather than the Projects screen's: the sidebar's
    // context menus reach the same three, and two copies of "are you sure" would drift apart.
    ProjectDialogs(
        dialog = projectDialog,
        state = state,
        onDismiss = { projectDialog = null },
        onCreateProject = viewModel::addProject,
        onEditProject = viewModel::editProject,
        onDeleteProject = viewModel::deleteProject,
    )

    if (quickAddOpen) {
        QuickAddSheet(
            projects = state.projects,
            tags = state.tags,
            today = today,
            defaultProjectId = quickAddProjectId,
            onDismiss = { quickAddOpen = false },
            onSubmit = { parsed ->
                viewModel.addParsedTask(parsed, quickAddProjectId)
                quickAddOpen = false
            },
        )
    }

    if (shortcutsOpen) {
        ShortcutSheet(onDismiss = { shortcutsOpen = false })
    }

    if (paletteOpen) {
        CommandPaletteDialog(
            state = state,
            viewModel = viewModel,
            navigator = navigator,
            onDismiss = { paletteOpen = false },
            onNewTask = { openQuickAdd(null) },
            onNewProject = { projectDialog = ProjectDialogState.Create(null) },
            onShowShortcuts = { shortcutsOpen = true },
            onToggleSidebar = { workspaceStore.setSidebarCollapsed(!workspace.sidebarCollapsed) },
        )
    }
}

/** Below this the window shows one pane at a time, as a phone does. */
private val TWO_PANE_THRESHOLD = 1000.dp

/** How much of the content area the list keeps once a detail pane opens beside it. */
private const val LIST_PANE_FRACTION = 0.42f

/**
 * One route, drawn wherever it was asked for.
 *
 * The same function serves both panes, which is the whole point of the split: a detail route is
 * the same composable whether it fills the window or sits beside the list, so nothing about a
 * screen knows how wide its frame is.
 */
@Composable
private fun RouteContent(
    route: Route,
    viewModel: CadenceViewModel,
    state: CadenceUiState,
    today: LocalDate,
    appInfo: AppInfo,
    navigator: DesktopNavigator,
    syncControls: SyncControls,
    backupFilePicker: DesktopBackupFilePicker,
    onQuickAdd: (String?) -> Unit,
    onProjectDialog: (ProjectDialogState) -> Unit,
) {
    when (route) {
        Route.Today -> TodayScreen(
            state = state,
            today = today,
            onTaskClick = { navigator.go(Route.TaskDetail(it.id)) },
            onToggle = viewModel::toggleTask,
            onRescheduleAll = viewModel::rescheduleOverdue,
            onSortChange = viewModel::setSortMode,
            onSearch = { navigator.go(Route.Search) },
            onSettings = { navigator.go(Route.Settings) },
            syncControls = syncControls,
        )

        Route.Upcoming -> UpcomingScreen(
            state = state,
            today = today,
            onTaskClick = { navigator.go(Route.TaskDetail(it.id)) },
            onToggle = viewModel::toggleTask,
            syncControls = syncControls,
        )

        Route.Inbox -> InboxScreen(
            state = state,
            today = today,
            onTaskClick = { navigator.go(Route.TaskDetail(it.id)) },
            onToggle = viewModel::toggleTask,
            onTriage = { navigator.go(Route.Triage) },
            syncControls = syncControls,
        )

        Route.Projects -> ProjectsScreen(
            state = state,
            today = today,
            onProjectClick = { navigator.go(Route.ProjectDetail(it.id)) },
            onInbox = { navigator.switchTo(Route.Inbox) },
            onToday = { navigator.switchTo(Route.Today) },
            onCreateProject = viewModel::addProject,
            onEditProject = viewModel::editProject,
            onDeleteProject = viewModel::deleteProject,
            onTags = { navigator.go(Route.Tags) },
            onSettings = { navigator.go(Route.Settings) },
            syncControls = syncControls,
        )

        Route.Tags -> TagsScreen(
            state = state,
            onBack = navigator::back,
            onTagClick = { navigator.go(Route.TagDetail(it.id)) },
            onCreateTag = viewModel::addTag,
            onEditTag = viewModel::editTag,
            onDeleteTag = viewModel::deleteTag,
            onReorder = viewModel::reorderTags,
            // The window is wrapped in a DragAndDropHost, so rows here can be dragged; the
            // Android shell has no host and reorders from the row menu.
            reorderable = true,
        )

        is Route.TagDetail -> TagDetailScreen(
            tagId = route.tagId,
            state = state,
            today = today,
            onBack = navigator::back,
            onTaskClick = { navigator.go(Route.TaskDetail(it.id)) },
            onToggle = viewModel::toggleTask,
        )

        Route.Search -> SearchScreen(
            state = state,
            today = today,
            onBack = navigator::back,
            onTaskClick = { navigator.go(Route.TaskDetail(it.id)) },
            onToggle = viewModel::toggleTask,
        )

        Route.Settings -> SettingsScreen(
            state = state,
            appInfo = appInfo,
            filePicker = backupFilePicker,
            onBack = navigator::back,
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
            onWipe = { viewModel.wipeEverything() },
        )

        Route.Triage -> TriageScreen(
            state = state,
            today = today,
            onClose = navigator::back,
            onSetPriority = viewModel::setPriority,
            onSetDueDate = viewModel::setDueDate,
            onSetProject = viewModel::setProject,
        )

        is Route.TaskDetail -> {
            val task: Task? = state.tasks.firstOrNull { it.id == route.taskId }
            TaskDetailScreen(
                task = task,
                state = state,
                today = today,
                onBack = navigator::back,
                onSave = viewModel::saveTask,
                onToggle = viewModel::toggleTask,
                onDelete = {
                    viewModel.deleteTask(it)
                    if (it.id == task?.id) navigator.back()
                },
                onSnooze = { viewModel.snooze(it) },
                onOpenTask = { navigator.go(Route.TaskDetail(it.id)) },
                onAddSubtask = viewModel::addSubtask,
                onMoveToProject = viewModel::setProject,
                onMoveToSection = viewModel::setSection,
                onToggleTag = viewModel::toggleTag,
                onCreateTag = viewModel::addTag,
            )
        }

        is Route.ProjectDetail -> ProjectDetailScreen(
            projectId = route.projectId,
            state = state,
            today = today,
            onBack = navigator::back,
            onTaskClick = { navigator.go(Route.TaskDetail(it.id)) },
            onProjectClick = { navigator.go(Route.ProjectDetail(it.id)) },
            onToggle = viewModel::toggleTask,
            onAddTask = { onQuickAdd(route.projectId) },
            onCreateProject = viewModel::addProject,
            onEditProject = viewModel::editProject,
            onDeleteProject = viewModel::deleteProject,
            onCreateSection = viewModel::addSection,
            onRenameSection = viewModel::renameSection,
            onDeleteSection = viewModel::deleteSection,
        )
    }
}

/** The label a route answers to in the command palette and the window title. */
@Composable
fun routeLabel(route: Route, state: CadenceUiState): String = when (route) {
    Route.Today -> stringResource(Res.string.nav_today)
    Route.Upcoming -> stringResource(Res.string.nav_upcoming)
    Route.Inbox -> stringResource(Res.string.nav_inbox)
    Route.Projects -> stringResource(Res.string.nav_projects)
    Route.Tags -> stringResource(Res.string.tags_title)
    Route.Search -> stringResource(Res.string.search_title)
    Route.Settings -> stringResource(Res.string.settings_title)
    Route.Triage -> stringResource(Res.string.inbox_title)
    is Route.TaskDetail -> state.tasks.firstOrNull { it.id == route.taskId }?.title.orEmpty()
    is Route.ProjectDetail -> state.project(route.projectId)?.name.orEmpty()
    is Route.TagDetail -> state.tag(route.tagId)?.let { "@" + it.handle }.orEmpty()
}
