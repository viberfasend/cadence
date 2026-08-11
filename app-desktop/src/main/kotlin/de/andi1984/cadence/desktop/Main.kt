package de.andi1984.cadence.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.andi1984.cadence.data.db.CADENCE_DATABASE_FILE_NAME
import de.andi1984.cadence.desktop.platform.PlatformDirs
import de.andi1984.cadence.desktop.ui.CadenceDesktopApp
import de.andi1984.cadence.ui.CadenceViewModel
import de.andi1984.cadence.ui.platform.AppInfo
import de.andi1984.cadence.ui.theme.CadenceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

/** Ctrl+N on Linux/Windows, Cmd+N on macOS — the FAB's keyboard equivalent (ADR 0001 §8). AWT
 *  reports both as [isMetaPressed] false and a platform-specific modifier otherwise, so this
 *  reads the OS rather than trusting one modifier key. */
private val isMac = System.getProperty("os.name").lowercase().contains("mac")

fun main() = application {
    val container = remember { AppContainer() }
    val viewModelScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val viewModel = remember {
        CadenceViewModel(
            repository = container.repository,
            settingsStore = container.settingsStore,
            reminderScheduler = container.reminderScheduler,
            backupGateway = container.backupIo,
            syncEngine = container.syncEngine,
            scope = viewModelScope,
        )
    }
    val state by viewModel.state.collectAsState()

    var quickAddRequested by remember { mutableStateOf(false) }

    val windowState = rememberWindowState(width = 1100.dp, height = 780.dp)

    Window(
        onCloseRequest = {
            viewModel.close()
            exitApplication()
        },
        state = windowState,
        title = "Cadence",
        onKeyEvent = { event ->
            val modifierHeld = if (isMac) event.isMetaPressed else event.isCtrlPressed
            if (event.type == KeyEventType.KeyDown && event.key == Key.N && modifierHeld) {
                quickAddRequested = true
                true
            } else {
                false
            }
        },
    ) {
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
                quickAddRequested = quickAddRequested,
                onQuickAddHandled = { quickAddRequested = false },
            )
        }
    }
}
