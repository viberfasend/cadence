package de.andi1984.cadence.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.data.sync.SyncFailure
import de.andi1984.cadence.data.sync.SyncOutcome
import java.util.concurrent.TimeUnit

/**
 * One sync round, run by WorkManager — for the syncing that has to happen while no screen is
 * open, which the widgets are the only source of. Two schedules share this worker:
 *
 * - **[enqueue]: one shot, after a write made from a widget** (`ToggleTaskCallback`,
 *   `WidgetToggleActivity`) — the push that cannot wait for the app to be opened.
 * - **[ensurePeriodic]: every 15 minutes, while a task widget exists and a session is stored**
 *   (ADR 0002, amendment 1) — the pull that keeps the home screen from showing yesterday's list
 *   for something another device finished an hour ago. Reconciled by `WidgetUpdater.refreshAll`
 *   and cancelled the moment either condition stops holding, so a phone without widgets, or
 *   signed out, schedules nothing.
 *
 * **Neither is the background sync ADR 0002 rejected, and the distinction is the point.** What
 * was rejected was polling *while nobody is looking*: a home screen with a Primico widget on it
 * is being looked at — the same reasoning that gives the desktop its 15-minute poll while the
 * window is open, now applied to the surface that is always open. The interval matches the
 * desktop's for that reason.
 *
 * Why the write needs it where an in-app write does not: inside the app the round runs on the
 * application scope two seconds after the edit, and if the process dies first the next start
 * pushes it. A widget tap has no next start. The process a broadcast created is a cached one the
 * moment the receiver returns; a coroutine launched on it has no guarantee beyond "probably", and
 * offline it has none at all — the push would wait until the app was next opened, which for a
 * task ticked off from the widget may be days, during which every other device keeps showing it
 * open. WorkManager is the platform's answer to "run this once, soon, when there is a network,
 * and survive the process and a reboot while waiting", and it is already on the classpath: Glance
 * runs every widget composition in a WorkManager job.
 *
 * The one-shot is unique work with [ExistingWorkPolicy.APPEND_OR_REPLACE]: a second tap while a
 * round is running queues a round behind it rather than being folded into one that may already be
 * past its push. The periodic is [ExistingPeriodicWorkPolicy.KEEP], so re-ensuring it on every
 * task emission never resets its clock.
 * Signed out the round is `SignedOut` and the worker is done — no request, no retry, exactly as
 * every other trigger behaves. A failure retries with backoff a few times: `OFFLINE` is mostly
 * headed off by the network constraint, and what remains — a paused project, a server error —
 * is not going to change in the next minute.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? CadenceApplication)?.container
            ?: return Result.failure()
        return when (val outcome = container.syncEngine.syncOnce()) {
            is SyncOutcome.Ok, SyncOutcome.SignedOut -> Result.success()
            is SyncOutcome.Failed -> when {
                runAttemptCount >= MAX_ATTEMPTS -> Result.failure()
                // The session is gone; no retry signs the user back in.
                outcome.reason == SyncFailure.SESSION_EXPIRED -> Result.failure()
                else -> Result.retry()
            }
        }
    }

    companion object {
        private const val UNIQUE_NAME = "cadence-sync-after-widget-write"
        private const val PERIODIC_NAME = "cadence-sync-while-widgets"
        private const val PERIODIC_MINUTES = 15L
        private const val MAX_ATTEMPTS = 3

        private fun networkConstraint() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** The push behind one widget write — call *after* that write has committed. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** The widget-freshness pull. KEEP: calling this every emission never resets the clock. */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkConstraint())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        }
    }
}
