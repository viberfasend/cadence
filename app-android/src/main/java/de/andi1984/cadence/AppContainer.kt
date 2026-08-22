package de.andi1984.cadence

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.andi1984.cadence.data.BlobStore
import de.andi1984.cadence.data.CadenceRepository
import de.andi1984.cadence.data.backup.BackupIo
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.DatabaseDriverFactory
import de.andi1984.cadence.data.db.SqlDelightAttachmentStore
import de.andi1984.cadence.data.db.SqlDelightBackupStore
import de.andi1984.cadence.data.db.SqlDelightProjectStore
import de.andi1984.cadence.data.db.SqlDelightSectionStore
import de.andi1984.cadence.data.db.SqlDelightSyncStore
import de.andi1984.cadence.data.db.SqlDelightTagStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.data.settings.SharedPrefsSettingsStore
import de.andi1984.cadence.reminders.AlarmReminderScheduler
import de.andi1984.cadence.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Hand-rolled dependency graph — the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {

    private val database = CadenceDatabase(DatabaseDriverFactory(context).createDriver())

    /** Both directories must sit on the same filesystem — the copy finishes with a `renameTo`
     *  that is only atomic within one volume — so `tmp` is a sibling under `filesDir`, never
     *  `cacheDir`, which Android is free to put elsewhere. */
    private val blobStore = BlobStore(
        root = File(context.filesDir, "attachments"),
        tmp = File(context.filesDir, "attachments-tmp"),
    )

    val repository = CadenceRepository(
        taskStore = SqlDelightTaskStore(database),
        projectStore = SqlDelightProjectStore(database),
        sectionStore = SqlDelightSectionStore(database),
        tagStore = SqlDelightTagStore(database),
        backupStore = SqlDelightBackupStore(database),
        attachmentStore = SqlDelightAttachmentStore(database),
        blobStore = blobStore,
    )

    val settingsStore = SharedPrefsSettingsStore(context)

    val backupIo = BackupIo(context, repository, settingsStore)

    val reminderScheduler = AlarmReminderScheduler(context)

    /**
     * Outlives every screen: the backup written as the user leaves the app starts while the
     * Activity is already being torn down, so it cannot hang off a ViewModel's scope.
     *
     * Public because a widget needs it for the same reason. `WidgetToggleActivity` finishes
     * inside its own `onCreate` — it never shows a frame — so work started there would be
     * cancelled before it reached the database if it hung off anything the Activity owns.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** On the application scope, not a ViewModel's: a round that starts as the user leaves the
     *  screen has to be allowed to finish, and the session it refreshes outlives every screen. */
    val syncEngine = CadenceSyncEngine(
        store = SqlDelightSyncStore(database),
        scope = applicationScope,
    )

    init {
        // Heals a leak left by a process killed mid-copy — cheap even at a few hundred files,
        // and cold start is the only time nothing else is racing the blob directory yet.
        applicationScope.launch { repository.sweepOrphanBlobs() }

        // "Reminders reconcile on every task emission" (CLAUDE.md) — a home-screen widget is the
        // same shape of problem: it has no view of `repository.tasks` of its own, so something
        // has to push it a redraw whenever a local edit, an import or a sync merge changes what
        // it should show. `updatePeriodMillis` in the widgets' provider info is only the fallback
        // for whenever the process is not alive to run this collector at all.
        applicationScope.launch {
            repository.tasks.collect { WidgetUpdater.refreshAll(context) }
        }
    }
}

class CadenceApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AlarmReminderScheduler.createChannel(this)
        syncWithTheApp()
    }

    /**
     * Sync on start, on every return to the foreground, and on the way out (ADR 0002, decision
     * 11). Signed out each of these reaches the engine and makes no request at all.
     *
     * The *process* lifecycle, not the Activity's: a rotation stops and starts the Activity, and
     * a device that syncs every time the phone is turned sideways is doing work nobody asked for.
     * `ProcessLifecycleOwner` waits out that gap and reports only the ones that mean "the app
     * came to the front" and "the app left it".
     *
     * Both rounds are fire-and-forget on the container's application scope. `onStop` carries no
     * completion guarantee on Android anyway, and a push that misses its window ships on the next
     * start — the local database is the source of truth until then. There is deliberately no
     * `WorkManager` and no background poll behind this: the phone is stale only while nobody is
     * looking at it.
     *
     * The change socket follows the same two events, and only these (ADR 0002, decision 12): a
     * websocket held open in the background is the wakelock `WorkManager` was rejected to avoid,
     * and nothing on this device is reading a row while the app is not in front of the user.
     */
    private fun syncWithTheApp() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    container.syncEngine.syncInBackground()
                    container.syncEngine.startRealtime()
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.syncEngine.syncInBackground()
                    container.syncEngine.stopRealtime()
                }
            },
        )
    }
}
