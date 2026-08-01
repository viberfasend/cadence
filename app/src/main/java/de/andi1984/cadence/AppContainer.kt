package de.andi1984.cadence

import android.app.Application
import android.content.Context
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.reminders.ReminderScheduler
import de.andi1984.cadence.ui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-rolled dependency graph — the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {

    private val database = CadenceDatabase.get(context)

    val repository = CadenceRepository(database.taskDao(), database.projectDao())

    val settingsStore = SettingsStore(context)

    val reminderScheduler = ReminderScheduler(context)
}

class CadenceApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderScheduler.createChannel(this)
        scope.launch { container.repository.seedIfEmpty() }
    }
}
