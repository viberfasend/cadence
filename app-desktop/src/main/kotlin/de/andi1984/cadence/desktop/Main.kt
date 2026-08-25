package de.andi1984.cadence.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.andi1984.cadence.data.db.CADENCE_DATABASE_FILE_NAME
import de.andi1984.cadence.data.sync.SyncStatus
import de.andi1984.cadence.desktop.data.DesktopWorkspaceStore
import de.andi1984.cadence.desktop.platform.PlatformDirs
import de.andi1984.cadence.desktop.ui.CadenceDesktopApp
import de.andi1984.cadence.desktop.ui.DesktopNavigator
import de.andi1984.cadence.desktop.ui.Route
import de.andi1984.cadence.desktop.ui.ShortcutAction
import de.andi1984.cadence.desktop.ui.TrayLabels
import de.andi1984.cadence.desktop.ui.installMenu
import de.andi1984.cadence.desktop.ui.shortcutFor
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.CadenceViewModel
import de.andi1984.cadence.ui.components.RowSelectionState
import de.andi1984.cadence.ui.platform.AppInfo
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.ui.theme.CadenceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

/**
 * The desktop's safety net, and the one poll in the app (ADR 0002, decision 11).
 *
 * A laptop lid or a socket that believes it is connected can leave this process out of step for
 * hours, and a window left open and focused on one screen for a whole day raises no other
 * trigger. Android gets no equivalent: a phone is stale only while nobody is looking at it, and
 * it syncs on foreground before the user reads a row.
 */
private val DESKTOP_POLL_INTERVAL: Duration = Duration.ofMinutes(15)

fun main() = application {
    val container = remember { AppContainer() }
    val workspaceStore = remember { DesktopWorkspaceStore() }
    val viewModelScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val viewModel = remember {
        CadenceViewModel(
            repository = container.repository,
            settingsStore = container.settingsStore,
            reminderScheduler = container.reminderScheduler,
            backupGateway = container.backupIo,
            attachmentOpener = container.attachmentOpener,
            syncEngine = container.syncEngine,
            scope = viewModelScope,
            syncPollInterval = DESKTOP_POLL_INTERVAL,
        )
    }
    val state by viewModel.state.collectAsState()
    val workspace by workspaceStore.state.collectAsState()
    val navigator = remember { DesktopNavigator() }
    // Which row the keyboard is on. It lives out here rather than inside the app composable
    // because the window is where key events arrive, and the two have to agree.
    val selection = remember { RowSelectionState() }

    // App start. Signed out this makes no request at all, so a fresh install still talks to
    // nobody until somebody signs in.
    //
    // The foreground poll starts here too and runs for the whole process, minimised included
    // (ADR 0005) — unlike Android, which polls only in the foreground. A desktop that stopped
    // polling on alt-tab would go stale exactly when the phone is being used, which is the one
    // case the fast poll exists for.
    LaunchedEffect(Unit) {
        container.syncEngine.syncInBackground()
        container.syncEngine.startForegroundPoll()
    }

    /**
     * Today, recomputed at midnight.
     *
     * This was `remember { LocalDate.now() }` and never changed again, so a machine left open
     * overnight kept drawing yesterday: Today listed yesterday's tasks, and nothing that became
     * overdue at 00:00 looked overdue. A phone is killed and restarted often enough to hide the
     * bug; a desktop window is not.
     */
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = java.time.LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            // A second past midnight, not exactly on it: `LocalDate.now()` a hair early would
            // return the day that is ending and the loop would spin until the clock caught up.
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L) + 1_000L)
            today = LocalDate.now()
        }
    }

    var quickAddRequested by remember { mutableStateOf(false) }
    var shortcutsRequested by remember { mutableStateOf(false) }
    var paletteRequested by remember { mutableStateOf(false) }

    // Restored from the workspace file, which clamps anything unusable — a window that opens
    // off-screen because a second monitor is gone is a window nobody can find.
    val windowState = rememberWindowState(
        size = DpSize(workspace.windowWidth.dp, workspace.windowHeight.dp),
        position = workspace.windowX?.let { x ->
            workspace.windowY?.let { y -> WindowPosition(x.dp, y.dp) }
        } ?: WindowPosition.PlatformDefault,
        placement = if (workspace.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
    )

    fun rememberWindowBounds() {
        val position = windowState.position
        workspaceStore.setWindowBounds(
            width = windowState.size.width.value,
            height = windowState.size.height.value,
            x = if (position is WindowPosition.Absolute) position.x.value else null,
            y = if (position is WindowPosition.Absolute) position.y.value else null,
            maximized = windowState.placement == WindowPlacement.Maximized,
        )
    }

    Window(
        onCloseRequest = {
            // Fire-and-forget on the container's application scope, which [CadenceViewModel.close]
            // does not touch: the window must not hesitate on the way out, and a push that misses
            // this window ships on the next start (ADR 0002, decision 11).
            rememberWindowBounds()
            container.syncEngine.syncInBackground()
            // Settings, unlike sync, are *not* fire-and-forget on the way out: their write runs
            // on a daemon thread and `exitApplication()` would take it with it, losing the
            // toggle someone flipped a second before quitting. A few hundred bytes, at the one
            // moment where there is nothing left to hold up (#104).
            container.settingsStore.flush()
            viewModel.close()
            exitApplication()
        },
        state = windowState,
        title = "Cadence",
        // One table decides every shortcut, and the cheat sheet is generated from the same one
        // (`ui/Shortcuts.kt`), so the two cannot drift apart.
        onKeyEvent = { event ->
            when (shortcutFor(event)) {
                ShortcutAction.QuickAdd -> { quickAddRequested = true; true }
                ShortcutAction.CommandPalette -> { paletteRequested = true; true }
                ShortcutAction.ShowShortcuts -> { shortcutsRequested = true; true }
                ShortcutAction.Search -> { navigator.go(Route.Search); true }
                ShortcutAction.Settings -> { navigator.go(Route.Settings); true }
                ShortcutAction.ToggleSidebar -> {
                    workspaceStore.setSidebarCollapsed(!workspace.sidebarCollapsed)
                    true
                }

                // Signed out there is nothing to refresh, and the shortcut does nothing rather
                // than pretending: the same rule the header's button follows.
                ShortcutAction.SyncNow -> {
                    if (state.sync.status !is SyncStatus.SignedOut) viewModel.syncNow()
                    true
                }

                ShortcutAction.Undo -> { viewModel.undo(); true }
                ShortcutAction.Back, ShortcutAction.Close -> { navigator.back(); true }
                ShortcutAction.Forward -> { navigator.forward(); true }
                ShortcutAction.GoToday -> { navigator.switchTo(Route.Today); true }
                ShortcutAction.GoUpcoming -> { navigator.switchTo(Route.Upcoming); true }
                ShortcutAction.GoInbox -> { navigator.switchTo(Route.Inbox); true }
                ShortcutAction.GoProjects -> { navigator.switchTo(Route.Projects); true }

                // The selected row's own keys. Each resolves the id to a live task first: the
                // selection is a row that was on screen, and a pull may have deleted it since.
                ShortcutAction.SelectNext -> { selection.moveBy(1); true }
                ShortcutAction.SelectPrevious -> { selection.moveBy(-1); true }
                ShortcutAction.OpenSelected -> selection.withTask(state) {
                    navigator.go(Route.TaskDetail(it.id))
                }

                ShortcutAction.ToggleSelected -> selection.withTask(state, viewModel::toggleTask)
                ShortcutAction.DeleteSelected -> selection.withTask(state, viewModel::deleteTask)
                ShortcutAction.SelectedPriority1 ->
                    selection.withTask(state) { viewModel.setPriority(it, Priority.P1) }

                ShortcutAction.SelectedPriority2 ->
                    selection.withTask(state) { viewModel.setPriority(it, Priority.P2) }

                ShortcutAction.SelectedPriority3 ->
                    selection.withTask(state) { viewModel.setPriority(it, Priority.P3) }

                ShortcutAction.SelectedPriority4 ->
                    selection.withTask(state) { viewModel.setPriority(it, Priority.P4) }

                ShortcutAction.SelectedDueToday ->
                    selection.withTask(state) { viewModel.setDueDate(it, today) }

                ShortcutAction.SelectedDueTomorrow ->
                    selection.withTask(state) { viewModel.setDueDate(it, today.plusDays(1)) }

                ShortcutAction.SelectedDueNextWeek ->
                    selection.withTask(state) { viewModel.setDueDate(it, today.plusWeeks(1)) }

                ShortcutAction.SelectedNoDueDate ->
                    selection.withTask(state) { viewModel.setDueDate(it, null) }

                null -> false
            }
        },
    ) {
        // What "returning to the foreground" means on a desktop: the window has focus again,
        // most likely because the user just put the phone down. `drop(1)` skips the emission
        // that only reports the window opening — the round for that is the one above.
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(windowInfo) {
            snapshotFlow { windowInfo.isWindowFocused }
                .drop(1)
                .filter { it }
                .collect { container.syncEngine.syncInBackground() }
        }

        // Resizing and moving are continuous, so the file is written a second after the last of
        // them rather than on every frame of a drag.
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
                .collect {
                    delay(1_000)
                    rememberWindowBounds()
                }
        }

        // The tray icon has existed since phase 5 and could only pop a balloon; right-clicking it
        // did nothing, which reads as broken rather than deliberate. AWT cannot read a Compose
        // resource, so the labels are resolved here and handed over as plain strings.
        val trayLabels = TrayLabels(
            show = stringResource(Res.string.tray_show),
            newTask = stringResource(Res.string.command_new_task),
            sync = stringResource(Res.string.command_sync_now),
            quit = stringResource(Res.string.tray_quit),
        )
        LaunchedEffect(trayLabels) {
            container.trayIcon?.installMenu(
                labels = trayLabels,
                onShow = {
                    windowState.isMinimized = false
                    window.toFront()
                },
                onNewTask = {
                    windowState.isMinimized = false
                    window.toFront()
                    quickAddRequested = true
                },
                onSync = { container.syncEngine.syncInBackground() },
                onQuit = {
                    container.syncEngine.syncInBackground()
                    viewModel.close()
                    exitApplication()
                },
            )
        }

        val databaseFile = remember { File(PlatformDirs.dataDir(), CADENCE_DATABASE_FILE_NAME) }
        val appInfo = remember(state.tasks.size, state.projects.size) {
            AppInfo(
                version = System.getenv("CADENCE_VERSION_NAME") ?: "0.0.0-dev",
                databaseSizeBytes = databaseFile.length(),
            )
        }

        CadenceTheme(
            theme = state.settings.theme,
            density = state.settings.density,
            // No per-app language setting on desktop yet (ADR 0001 decision 9 names this as a
            // phase-5 follow-up); the system locale is what every formatter reads until then.
            locale = Locale.getDefault(),
        ) {
            CadenceDesktopApp(
                viewModel = viewModel,
                state = state,
                appInfo = appInfo,
                today = today,
                workspaceStore = workspaceStore,
                navigator = navigator,
                quickAddRequested = quickAddRequested,
                onQuickAddHandled = { quickAddRequested = false },
                shortcutsRequested = shortcutsRequested,
                onShortcutsHandled = { shortcutsRequested = false },
                paletteRequested = paletteRequested,
                onPaletteHandled = { paletteRequested = false },
                selection = selection,
            )
        }
    }
}

/**
 * Runs [action] on the selected row, and reports whether there was one.
 *
 * Reporting matters: a key that acted is consumed, and a key that found nothing selected has to
 * fall through — otherwise `1` would be swallowed on a screen with no rows on it.
 */
private fun RowSelectionState.withTask(state: CadenceUiState, action: (Task) -> Unit): Boolean {
    val task = selectedId?.let { id -> state.tasks.firstOrNull { it.id == id } } ?: return false
    action(task)
    return true
}
