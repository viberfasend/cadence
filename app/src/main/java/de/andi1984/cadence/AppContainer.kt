package de.andi1984.cadence

import android.app.Application
import android.content.Context
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.backup.AutoBackupSync
import de.andi1984.cadence.data.backup.BackupIo
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.RoomBackupStore
import de.andi1984.cadence.data.db.RoomProjectStore
import de.andi1984.cadence.data.db.RoomTaskStore
import de.andi1984.cadence.reminders.ReminderScheduler
import de.andi1984.cadence.ui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled dependency graph — the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {

    private val database = CadenceDatabase.get(context)

    val repository = CadenceRepository(
        taskStore = RoomTaskStore(database.taskDao()),
        projectStore = RoomProjectStore(database.projectDao()),
        backupStore = RoomBackupStore(database.backupDao()),
    )

    val settingsStore = SettingsStore(context)

    val backupIo = BackupIo(context, repository, settingsStore)

    val reminderScheduler = ReminderScheduler(context)

    /**
     * Outlives every screen: the backup written as the user leaves the app starts while the
     * Activity is already being torn down, so it cannot hang off a ViewModel's scope.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val autoBackupSync = AutoBackupSync(
        context = context,
        backupIo = backupIo,
        settingsStore = settingsStore,
        repository = repository,
        scope = applicationScope,
    )
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
