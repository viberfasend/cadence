package de.andi1984.cadence

import android.app.Application
import android.content.Context
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.backup.BackupIo
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.reminders.ReminderScheduler
import de.andi1984.cadence.ui.settings.SettingsStore

/** Hand-rolled dependency graph — the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {

    private val database = CadenceDatabase.get(context)

    val repository = CadenceRepository(
        taskDao = database.taskDao(),
        projectDao = database.projectDao(),
        backupDao = database.backupDao(),
    )

    val backupIo = BackupIo(context, repository)

    val settingsStore = SettingsStore(context)

    val reminderScheduler = ReminderScheduler(context)
}

class CadenceApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderScheduler.createChannel(this)
    }
}
