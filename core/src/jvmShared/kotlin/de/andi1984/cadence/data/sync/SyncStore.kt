package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Task
import java.time.Instant

/**
 * What sync remembers between rounds, and the two views of local data no other caller wants.
 *
 * [session] is supabase-kt's serialised `UserSession`. It lives here rather than in the settings
 * file because it has to stay consistent with the cursors beside it: a session restored from a
 * backup next to cursors that were not would have the device claim to hold rows it does not.
 *
 * The cursors are the *server's* clock, as ISO-8601 text, and the watermark is this device's, as
 * epoch millis. Mixing the two would be a bug; keeping them different types is how this says so.
 */
data class SyncState(
    val session: String? = null,
    val taskCursor: String? = null,
    val projectCursor: String? = null,
    val pushWatermark: Instant = Instant.EPOCH,
    val lastSyncedAt: Instant? = null,
    val lastSweepAt: Instant? = null,
)

interface SyncStore {

    suspend fun state(): SyncState

    suspend fun setSession(session: String?)

    /** Everything written since [since], tombstones included — what the next push sends. */
    suspend fun tasksChangedSince(since: Instant): List<Task>

    suspend fun projectsChangedSince(since: Instant): List<Project>

    /**
     * Folds a pulled page in and advances the cursors, in **one** transaction.
     *
     * Together or not at all, because the two orders both lose data on a crash between them: a
     * cursor advanced before the merge skips rows on the next round, and a merge without the
     * cursor is only saved by the merge being idempotent. A null cursor leaves that table's
     * cursor where it was — a page can be empty for one table and full for the other.
     *
     * The merge itself is the same one importing a backup uses: greater `updatedAt` wins, ties
     * keep what is stored, a tombstone is a version like any other.
     */
    suspend fun mergeAndAdvance(
        projects: List<Project>,
        tasks: List<Task>,
        taskCursor: String?,
        projectCursor: String?,
    )

    suspend fun setPushWatermark(at: Instant)

    suspend fun setLastSyncedAt(at: Instant)

    /**
     * Drops tombstones older than [before] and records that the sweep ran at [at].
     *
     * Only ever called after a round that pushed successfully: collecting a tombstone this device
     * has not yet handed over turns a deletion into a row the other device puts back.
     */
    suspend fun collectTombstones(before: Instant, at: Instant)

    /** Signing out: the session and everything derived from the server. No task is touched. */
    suspend fun clear()
}
