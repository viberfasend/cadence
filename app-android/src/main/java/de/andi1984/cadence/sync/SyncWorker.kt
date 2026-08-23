package de.andi1984.cadence.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.data.sync.SyncFailure
import de.andi1984.cadence.data.sync.SyncOutcome
import java.util.concurrent.TimeUnit

/**
 * One sync round, run by WorkManager — for the writes made while no screen is open.
 *
 * **This is not the background sync ADR 0002 rejected, and the distinction is the whole point.**
 * What was rejected was a *poll*: a periodic job, or a websocket held open, to find out whether
 * the server has something new while nobody is looking at the phone. This worker never runs on a
 * schedule and never runs unprompted. It is enqueued by exactly one thing — a write the user made
 * from a home-screen widget (`ToggleTaskCallback`) — and what it carries is that write.
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
 * Unique work with [ExistingWorkPolicy.APPEND_OR_REPLACE]: a second tap while a round is running
 * queues a round behind it rather than being folded into one that may already be past its push.
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
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
