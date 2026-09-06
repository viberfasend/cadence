package de.andi1984.cadence

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.andi1984.cadence.data.CadenceCore
import de.andi1984.cadence.data.backup.BackupIo
import de.andi1984.cadence.data.attachments.AndroidAttachmentOpener
import de.andi1984.cadence.data.db.DatabaseDriverFactory
import de.andi1984.cadence.data.settings.SharedPrefsSettingsStore
import de.andi1984.cadence.reminders.AlarmReminderScheduler
import de.andi1984.cadence.ui.ViewModelAdapters
import de.andi1984.cadence.widget.WidgetUpdater
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency graph — the app is small enough not to need a DI framework. The database,
 * the blob store, the repository and the sync engine are built once, in [CadenceCore], for both
 * shells; what is left here is Android's own adapters — SharedPreferences, AlarmManager, SAF —
 * plus the event wiring `:core` cannot own (widgets, process lifecycle).
 */
class AppContainer(context: Context) {

    val core = CadenceCore(
        driver = DatabaseDriverFactory(context).createDriver(),
        dataDir = context.filesDir,
    )

    val repository = core.repository

    val settingsStore = SharedPrefsSettingsStore(context)

    val backupIo = BackupIo(context, repository, settingsStore)

    val reminderScheduler = AlarmReminderScheduler(context)

    /** The `FileProvider` half of attachments; the picker half is a composable, since a SAF
     *  launcher can only be created in a composition. */
    val attachmentOpener = AndroidAttachmentOpener(context)

    /** Handed to [de.andi1984.cadence.ui.cadenceViewModel] by [ui.CadenceViewModelHost] so the
     *  seven-argument `CadenceViewModel` constructor is written once, in `:ui`. */
    val viewModelAdapters = ViewModelAdapters(
        settingsStore = settingsStore,
        reminderScheduler = reminderScheduler,
        backupGateway = backupIo,
        attachmentOpener = attachmentOpener,
    )

    /** [CadenceCore.applicationScope], named here too: the widgets' `SyncWorker` and
     *  `ToggleTaskCallback` reach the engine and the repository through this container from a
     *  process a broadcast created, and both used to reach a field declared directly on this
     *  class. */
    val applicationScope = core.applicationScope

    /** On [applicationScope], not a ViewModel's: a round that starts as the user leaves the
     *  screen has to be allowed to finish, and the session it refreshes outlives every screen. */
    val syncEngine = core.syncEngine

    init {
        // "Reminders reconcile on every task emission" (CLAUDE.md) — a home-screen widget is the
        // same shape of problem: it has no view of `repository.tasks` of its own, so something
        // has to push it a redraw whenever a local edit, an import or a sync merge changes what
        // it should show. When the process is not alive to run this collector the widgets look
        // after themselves: a tap redraws through `ToggleTaskCallback`, midnight through
        // `WidgetMidnightRefresh`, and `updatePeriodMillis` is the half-hourly floor under both.
        //
        // This stays here rather than moving into `CadenceCore`: it needs a `Context` to reach
        // the widgets, which is exactly the kind of platform-specific event binding that does
        // not belong in `:core`.
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
     * periodic `WorkManager` job and no background poll behind this: the phone is stale only
     * while nobody is looking at it. (A write made from a *widget* has no next start to ride on,
     * so that one goes through `sync/SyncWorker`, a one-shot — the only WorkManager use here.)
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
                    container.syncEngine.startForegroundPoll()
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.syncEngine.syncInBackground()
                    container.syncEngine.stopForegroundPoll()
                }
            },
        )
    }
}
