package de.andi1984.cadence

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import de.andi1984.cadence.assistant.AppActionsIntents
import de.andi1984.cadence.data.db.CADENCE_DATABASE_FILE_NAME
import de.andi1984.cadence.reminders.AlarmReminderScheduler
import de.andi1984.cadence.ui.CadenceApp
import de.andi1984.cadence.ui.CadenceViewModelHost
import de.andi1984.cadence.ui.Routes
import de.andi1984.cadence.ui.platform.AppInfo
import de.andi1984.cadence.ui.theme.CadenceTheme
import de.andi1984.cadence.widget.WidgetIntents
import androidx.activity.compose.rememberLauncherForActivityResult

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val host: CadenceViewModelHost = viewModel(factory = CadenceViewModelHost.Factory)
            val viewModel = host.viewModel
            val state by viewModel.state.collectAsState()

            RequestNotificationPermission()

            // Handle intent extras for deep linking (e.g., from reminder notifications)
            val intentTaskId = remember {
                intent.getStringExtra(AlarmReminderScheduler.EXTRA_TASK_ID)
            }
            val startDestination = remember(intentTaskId) {
                if (intentTaskId != null) Routes.task(intentTaskId) else Routes.TODAY
            }
            // The quick-add widget asks for the composer rather than for a screen, so it is a
            // flag rather than a route: the sheet is composable state on top of whatever
            // destination the app came up on, exactly as the in-app FAB leaves it.
            //
            // Read once, like the task id above, and that is enough because every widget intent
            // carries CLEAR_TOP against a `standard` launch mode — the Activity is recreated and
            // `onCreate` runs again. See WidgetIntents.base.
            val openQuickAdd = remember {
                intent.getBooleanExtra(WidgetIntents.EXTRA_QUICK_ADD, false)
            }
            // App Actions voice capture (#46) — Assistant fulfills the CREATE_ITEM_LIST
            // capability declared in res/xml/shortcuts.xml with this same explicit-component
            // VIEW intent shape, so it is read once exactly like the widget's flag above.
            val voiceQuickAddText = remember {
                intent.getStringExtra(AppActionsIntents.EXTRA_ITEM_TEXT)
            }

            // Automatic backup sync, when the user has switched it on, reads the file as the
            // app comes up and writes it as the app leaves. Both are no-ops otherwise.
            val context = LocalContext.current
            // Diagnostics for the About section. The database file is the shell's business —
            // `:core` names it, Android is what knows where it landed.
            val appInfo = remember(state.tasks.size, state.projects.size) {
                AppInfo(
                    version = BuildConfig.VERSION_NAME,
                    databaseSizeBytes = context.getDatabasePath(CADENCE_DATABASE_FILE_NAME).length(),
                )
            }

            CadenceTheme(
                theme = state.settings.theme,
                density = state.settings.density,
                // Android 13+ has a per-app language picker, so the app language can differ from
                // the system one; every formatter downstream reads it back from the theme.
                locale = LocalConfiguration.current.locales[0],
            ) {
                CadenceApp(
                    viewModel = viewModel,
                    state = state,
                    appInfo = appInfo,
                    startDestination = startDestination,
                    openQuickAdd = openQuickAdd,
                    voiceQuickAddText = voiceQuickAddText,
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { },
    )
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
