package de.andi1984.cadence

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import de.andi1984.cadence.reminders.ReminderScheduler
import de.andi1984.cadence.ui.CadenceApp
import de.andi1984.cadence.ui.CadenceViewModel
import de.andi1984.cadence.ui.Routes
import de.andi1984.cadence.ui.theme.CadenceTheme
import androidx.activity.compose.rememberLauncherForActivityResult

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: CadenceViewModel = viewModel(factory = CadenceViewModel.Factory)
            val state by viewModel.state.collectAsState()

            RequestNotificationPermission()

            // Handle intent extras for deep linking (e.g., from reminder notifications)
            val intentTaskId = remember { intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) }
            val startDestination = remember(intentTaskId) {
                if (intentTaskId != null) Routes.task(intentTaskId) else Routes.TODAY
            }

            // Automatic backup sync, when the user has switched it on, reads the file as the
            // app comes up and writes it as the app leaves. Both are no-ops otherwise.
            LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onAppForegrounded() }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onAppBackgrounded() }

            CadenceTheme(
                theme = state.settings.theme,
                density = state.settings.density,
            ) {
                CadenceApp(viewModel = viewModel, state = state, intentTaskId = intentTaskId, startDestination = startDestination)
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
