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
import de.andi1984.cadence.data.db.CADENCE_DATABASE_FILE_NAME
import de.andi1984.cadence.reminders.AlarmReminderScheduler
import de.andi1984.cadence.ui.CadenceApp
import de.andi1984.cadence.ui.CadenceViewModelHost
import de.andi1984.cadence.ui.Routes
import de.andi1984.cadence.ui.platform.AppInfo
import de.andi1984.cadence.ui.theme.CadenceTheme
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
