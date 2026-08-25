package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** Where sync stands, as the Settings screen needs to show it. */
sealed interface SyncStatus {

    /** No account on this device. Everything else in the app works exactly as before. */
    data object SignedOut : SyncStatus

    data class Idle(val email: String?, val lastSyncedAt: Instant?) : SyncStatus

    data class Syncing(val email: String?) : SyncStatus

    data class Failed(
        val reason: SyncFailure,
        val email: String?,
        val lastSyncedAt: Instant?,
    ) : SyncStatus
}

/**
 * Why a round did not finish, at the granularity the user can act on. Neon's scale-to-zero cold
 * start is deliberately *not* a case here: it presents as a slow first request, which the HTTP
 * timeouts absorb, not as an error a person could act on.
 */
enum class SyncFailure { OFFLINE, SESSION_EXPIRED, SERVER }

sealed interface SignInResult {
    data object Ok : SignInResult
    data object WrongCredentials : SignInResult
    data object Offline : SignInResult
    data class Failed(val message: String) : SignInResult
}

sealed interface SyncOutcome {
    data class Ok(val pulled: Int, val pushed: Int) : SyncOutcome
    data object SignedOut : SyncOutcome
    data class Failed(val reason: SyncFailure) : SyncOutcome
}

/**
 * One sync round, and the session it needs — against Neon since ADR 0005: Stack Auth REST for the
 * session, the Data API (PostgREST) for the rows. The protocol is still ADR 0002's; only the
 * transport moved.
 *
 * A plain class in `:core`, not a port (ADR 0002, decision 7): `ui/platform/Ports.kt` exists for
 * what the *machine* does differently — alarms, a file picker, a SAF uri — and HTTPS is not that.
 * Both shells construct one in their `AppContainer` on the application scope and hand it to the
 * ViewModel, the same way they already hand over the concrete `CadenceRepository`. Construction
 * makes no request and touches nothing: a test can build one against a fake HTTP engine, and the
 * ViewModel tests build one that never speaks at all.
 *
 * The round, under [mutex] so two of them cannot interleave, every step idempotent so any failure
 * means "stop, try again later":
 *
 * 1. Pull rows at or after the stored cursor, in pages, and merge each page as it arrives.
 * 2. Push local rows written since the watermark, tombstones included, as an upsert.
 * 3. Collect tombstones past the horizon — on the server first, then locally — at most once a
 *    day, and only after a round that pushed. Client-driven since ADR 0005: pg_cron on a compute
 *    that scales to zero only fires while something else keeps it awake.
 *
 * The push sends everything above the watermark rather than tracking which rows are dirty,
 * because the server can tell: its trigger drops a write whose `updated_at` is not strictly
 * greater than the stored row's, so a row that arrived *from* the server and gets pushed straight
 * back is a no-op there.
 */
class CadenceSyncEngine(
    private val store: SyncStore,
    private val scope: CoroutineScope,
    httpClient: HttpClient? = null,
    dataApiUrl: String = NeonConfig.dataApiUrl,
    stackApiUrl: String = NeonConfig.stackApiUrl,
    stackProjectId: String = NeonConfig.stackProjectId,
    stackPublishableClientKey: String = NeonConfig.stackPublishableClientKey,
) {

    private val http: HttpClient = httpClient ?: defaultHttpClient()

    private val stackAuth = StackAuthClient(http, stackApiUrl, stackProjectId, stackPublishableClientKey)

    private val postgrest = PostgrestHttp(http, dataApiUrl)

    private val tokens = SessionTokens(store, stackAuth)

    private val mutex = Mutex()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.SignedOut)

    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _failures = MutableSharedFlow<SyncFailure>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * One event per failed round, whoever started it (ADR 0002, decision 14).
     *
     * [status] cannot stand in for this: it is a `StateFlow`, so a second round failing exactly
     * the way the first one did is an equal value and never re-emitted — and a phone in a tunnel
     * fails identically every time. The screen needs to hear about each round, so each round says
     * so here.
     */
    val failures: SharedFlow<SyncFailure> = _failures.asSharedFlow()

    /** The foreground poll's lifetime, held so the shell can end it — see [startForegroundPoll]. */
    private var pollJob: Job? = null

    init {
        // Seed the signed-in/out half of the status from the store — there is no auth library
        // loading sessions under us any more, so the stored session *is* the answer. Everything
        // after this beat is moved by signIn/signOut/syncOnce themselves.
        scope.launch {
            val session = tokens.current()
            if (_status.value is SyncStatus.SignedOut && session != null) {
                _status.value = SyncStatus.Idle(session.email, store.state().lastSyncedAt)
            }
        }
    }

    /**
     * Signs in with the one account this project has. There is no sign-up here on purpose: the
     * account is created in the Neon console (ADR 0002 decision 2, unchanged by ADR 0005), so a
     * registration form would only ever produce an error.
     */
    suspend fun signIn(email: String, password: String): SignInResult = try {
        val trimmed = email.trim()
        val answer = stackAuth.signIn(trimmed, password)
        val refreshToken = answer.refreshToken
        if (refreshToken.isNullOrBlank()) {
            SignInResult.Failed("sign-in answered without a refresh token")
        } else {
            val session = StackSession(answer.accessToken, refreshToken, trimmed)
            store.setSession(session.encode())
            _status.value = SyncStatus.Idle(trimmed, store.state().lastSyncedAt)
            SignInResult.Ok
        }
    } catch (e: StackAuthException) {
        // 400/401 is the ordinary "wrong password" (Stack answers EMAIL_PASSWORD_MISMATCH with
        // 400); anything else from the auth API is worth showing rather than flattening.
        if (e.status == 400 || e.status == 401) SignInResult.WrongCredentials
        else SignInResult.Failed(e.body.ifBlank { "Stack Auth error ${e.status}" })
    } catch (e: IOException) {
        SignInResult.Offline
    }

    /**
     * Signs out and forgets the session, every cursor and the watermark.
     *
     * It deliberately deletes nothing: the local database is the source of truth and signing out
     * is not a delete. Clearing the cursors is what makes signing back in start from the
     * beginning — which is right, because this device has no idea what the server did meanwhile.
     * The server-side revocation is best-effort for the same reason the old one was: signing out
     * must work on a plane.
     */
    suspend fun signOut() {
        tokens.current()?.let { session ->
            runCatching { stackAuth.signOut(session.refreshToken) }
        }
        store.clear()
        _status.value = SyncStatus.SignedOut
    }

    /**
     * A round nobody waits for — the app-start, foreground, stop and window-close triggers
     * (ADR 0002, decision 11).
     *
     * It runs on this engine's own scope, which is the *application* scope in both shells, rather
     * than on the caller's: the caller here is a lifecycle callback or a window that is closing,
     * and a round hung off either would be cancelled by the very event that started it. A process
     * that dies before the round finishes loses nothing — the local database is the source of
     * truth and the push ships on the next start.
     */
    fun syncInBackground() {
        scope.launch { syncOnce() }
    }

    /**
     * [syncInBackground], but only when the last completed round is older than [maxAge] — for
     * callers that fire often and mean "make sure this is not stale" rather than "something
     * changed". The Android widgets are the caller: every widget redraw starts a Glance session,
     * most redraws are caused by this app's own writes and refreshes, and an unguarded round per
     * session would turn each of them into a request. Signed out the round still costs nothing —
     * it returns before making one.
     */
    fun syncInBackgroundIfStale(maxAge: Duration) {
        scope.launch {
            val last = store.state().lastSyncedAt
            if (last == null || Duration.between(last, Instant.now()) >= maxAge) syncOnce()
        }
    }

    /**
     * Whether a session is **stored** — the database's answer, which is ready the moment the
     * process is. [status] is seeded from the same store asynchronously, so a cold process asking
     * it right away could still read `SignedOut` for a beat; background work gates on this
     * instead.
     */
    suspend fun isSignedIn(): Boolean = tokens.current() != null

    // ── Foreground poll ────────────────────────────────────────────────────────────

    /**
     * Polls a round every [interval] until [stopForegroundPoll] — what stands where the realtime
     * socket used to (ADR 0005): Neon has no change feed, so "the other device sees it in about a
     * minute" is a timer, not a websocket.
     *
     * The shell decides how long it runs, exactly as it decided the socket's lifetime:
     * `:app-android` starts it in the foreground and stops it on the way out — a background poll
     * is the battery drain WorkManager was rejected to avoid — while `:app-desktop` starts it
     * once and leaves it for the process, minimised included. A desktop that stops polling on
     * alt-tab goes stale exactly when the phone is in use, which is the one case this exists for.
     *
     * Calling it twice is a no-op, and signed out it costs nothing: a poll tick's round returns
     * before making a request.
     */
    @Synchronized
    fun startForegroundPoll(interval: Duration = FOREGROUND_POLL_INTERVAL) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(interval.toMillis())
                syncOnce()
            }
        }
    }

    /** Stops the poll. The lifecycle-edge rounds are what cover the gap afterwards. */
    @Synchronized
    fun stopForegroundPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun syncOnce(): SyncOutcome = mutex.withLock {
        val session = tokens.current() ?: return SyncOutcome.SignedOut
        val email = session.email
        _status.value = SyncStatus.Syncing(email)
        try {
            val pulled = pull()
            val pushed = push()
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            store.setLastSyncedAt(now)
            sweepIfDue(now)
            _status.value = SyncStatus.Idle(email, now)
            SyncOutcome.Ok(pulled = pulled, pushed = pushed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = e.toFailure()
            _status.value = SyncStatus.Failed(reason, email, store.state().lastSyncedAt)
            _failures.tryEmit(reason)
            SyncOutcome.Failed(reason)
        }
    }

    // ── Pull ───────────────────────────────────────────────────────────────────────

    /**
     * Reads all four tables forward from their cursors and merges each page as it lands.
     *
     * The cursor is re-read five seconds early every round. `now()` in Postgres is
     * transaction-start time, so a transaction that began before ours and committed after it can
     * land *behind* a cursor we already advanced past. Re-reading a little is free — the merge
     * discards anything it already has by timestamp — and missing a row is not.
     */
    private suspend fun pull(): Int = coroutineScope {
        val state = store.state()
        var taskCursor = state.taskCursor
        var projectCursor = state.projectCursor
        var sectionCursor = state.sectionCursor
        var tagCursor = state.tagCursor
        var merged = 0
        // Tracked per table rather than for the pull as a whole. The four are paged
        // independently, and asking a table that already answered with a short page costs a
        // request that can only return rows this round has merged already.
        var moreTasks = true
        var moreProjects = true
        var moreSections = true
        var moreTags = true

        while (moreTasks || moreProjects || moreSections || moreTags) {
            // No table's page depends on another's, so they travel together: one round trip's
            // latency per iteration instead of four.
            val projectPage = if (moreProjects) async { fetchProjects(projectCursor) } else null
            val sectionPage = if (moreSections) async { fetchSections(sectionCursor) } else null
            val tagPage = if (moreTags) async { fetchTags(tagCursor) } else null
            val taskPage = if (moreTasks) async { fetchTasks(taskCursor) } else null
            val projects = projectPage?.await().orEmpty()
            val sections = sectionPage?.await().orEmpty()
            val tags = tagPage?.await().orEmpty()
            val tasks = taskPage?.await().orEmpty()
            if (projects.isEmpty() && sections.isEmpty() && tags.isEmpty() && tasks.isEmpty()) break

            val nextTaskCursor = tasks.mapNotNull { it.serverUpdatedAt }.maxByOrNull { it.asInstant() }
            val nextProjectCursor =
                projects.mapNotNull { it.serverUpdatedAt }.maxByOrNull { it.asInstant() }
            val nextSectionCursor =
                sections.mapNotNull { it.serverUpdatedAt }.maxByOrNull { it.asInstant() }
            val nextTagCursor = tags.mapNotNull { it.serverUpdatedAt }.maxByOrNull { it.asInstant() }

            store.mergeAndAdvance(
                projects = projects.map { it.toDomain() },
                sections = sections.map { it.toDomain() },
                tags = tags.map { it.toDomain() },
                tasks = tasks.map { it.toDomain() },
                taskCursor = nextTaskCursor,
                projectCursor = nextProjectCursor,
                sectionCursor = nextSectionCursor,
                tagCursor = nextTagCursor,
            )
            merged += projects.size + sections.size + tags.size + tasks.size

            // A full page whose newest row carries the cursor we already had would ask for the
            // same page forever: more than a thousand rows sharing one microsecond. Stopping is
            // the safe end of that — the next round starts from the same place and the rows are
            // still there. Paging past this properly is phase 4's problem.
            moreTasks = tasks.size >= PAGE_SIZE && nextTaskCursor != taskCursor
            moreProjects = projects.size >= PAGE_SIZE && nextProjectCursor != projectCursor
            moreSections = sections.size >= PAGE_SIZE && nextSectionCursor != sectionCursor
            moreTags = tags.size >= PAGE_SIZE && nextTagCursor != tagCursor
            taskCursor = nextTaskCursor ?: taskCursor
            projectCursor = nextProjectCursor ?: projectCursor
            sectionCursor = nextSectionCursor ?: sectionCursor
            tagCursor = nextTagCursor ?: tagCursor
        }
        merged
    }

    private suspend fun fetchTasks(cursor: String?): List<RemoteTask> =
        tokens.withAccessToken { token ->
            SyncJson.decodeFromString(
                ListSerializer(RemoteTask.serializer()),
                postgrest.selectSince(TABLE_TASKS, cursor?.minusOverlap(), PAGE_SIZE, token),
            )
        }

    private suspend fun fetchProjects(cursor: String?): List<RemoteProject> =
        tokens.withAccessToken { token ->
            SyncJson.decodeFromString(
                ListSerializer(RemoteProject.serializer()),
                postgrest.selectSince(TABLE_PROJECTS, cursor?.minusOverlap(), PAGE_SIZE, token),
            )
        }

    private suspend fun fetchSections(cursor: String?): List<RemoteSection> =
        tokens.withAccessToken { token ->
            SyncJson.decodeFromString(
                ListSerializer(RemoteSection.serializer()),
                postgrest.selectSince(TABLE_SECTIONS, cursor?.minusOverlap(), PAGE_SIZE, token),
            )
        }

    private suspend fun fetchTags(cursor: String?): List<RemoteTag> =
        tokens.withAccessToken { token ->
            SyncJson.decodeFromString(
                ListSerializer(RemoteTag.serializer()),
                postgrest.selectSince(TABLE_TAGS, cursor?.minusOverlap(), PAGE_SIZE, token),
            )
        }

    // ── Push ───────────────────────────────────────────────────────────────────────

    /**
     * Sends everything written since the watermark: projects, then sections, then tags, then
     * tasks.
     *
     * The order is the reference order — a section names its project, and a task names both its
     * section and its tags — so a round that fails part-way leaves the server with rows whose
     * links already resolve. Postgres enforces none of it (the mirror carries no foreign key
     * either, for the reason the local schema carries none), but a web client reading between two
     * batches sees a coherent list rather than a heading pointing at a project it has not been
     * sent yet.
     *
     * The new watermark is the newest `updatedAt` actually sent, not "now": a row written while
     * this push was in flight has a stamp above that and is therefore still waiting for the next
     * round rather than being skipped by a clock that ran ahead of the data.
     */
    private suspend fun push(): Int {
        val watermark = store.state().pushWatermark
        val projects = store.projectsChangedSince(watermark)
        val sections = store.sectionsChangedSince(watermark)
        val tags = store.tagsChangedSince(watermark)
        val tasks = store.tasksChangedSince(watermark)
        if (projects.isEmpty() && sections.isEmpty() && tags.isEmpty() && tasks.isEmpty()) return 0

        projects.chunked(BATCH_SIZE).forEach { batch ->
            upsert(TABLE_PROJECTS, SyncJson.encodeToString(
                ListSerializer(RemoteProject.serializer()), batch.map { it.toRemote() }))
        }
        sections.chunked(BATCH_SIZE).forEach { batch ->
            upsert(TABLE_SECTIONS, SyncJson.encodeToString(
                ListSerializer(RemoteSection.serializer()), batch.map { it.toRemote() }))
        }
        tags.chunked(BATCH_SIZE).forEach { batch ->
            upsert(TABLE_TAGS, SyncJson.encodeToString(
                ListSerializer(RemoteTag.serializer()), batch.map { it.toRemote() }))
        }
        tasks.chunked(BATCH_SIZE).forEach { batch ->
            upsert(TABLE_TASKS, SyncJson.encodeToString(
                ListSerializer(RemoteTask.serializer()), batch.map { it.toRemote() }))
        }

        val newest = (
            projects.map(Project::updatedAt) +
                sections.map(Section::updatedAt) +
                tags.map(Tag::updatedAt) +
                tasks.map(Task::updatedAt)
            ).max()
        store.setPushWatermark(newest)
        return projects.size + sections.size + tags.size + tasks.size
    }

    private suspend fun upsert(table: String, jsonBody: String) {
        tokens.withAccessToken { token -> postgrest.upsert(table, jsonBody, token) }
    }

    /**
     * Tombstones are only collectable once they have been handed over, so this runs after a
     * successful round and at most once a day. Server first, local second, on purpose: a failed
     * server sweep throws, the round fails, `lastSweepAt` stays unstamped and the whole sweep is
     * retried next round — the local copy is never collected before the server's is.
     *
     * The cutoff filters `server_updated_at`, not `deleted_at`, the same choice the retired
     * pg_cron job made: `deleted_at` is a device's clock, and a device with a wrong clock must
     * not be able to collect a tombstone before the other device has pulled it.
     */
    private suspend fun sweepIfDue(now: Instant) {
        val lastSweep = store.state().lastSweepAt
        if (lastSweep != null && Duration.between(lastSweep, now) < SWEEP_INTERVAL) return
        val cutoff = now.minus(TOMBSTONE_HORIZON)
        val cutoffIso = cutoff.toString()
        listOf(TABLE_TASKS, TABLE_PROJECTS, TABLE_SECTIONS, TABLE_TAGS).forEach { table ->
            tokens.withAccessToken { token ->
                postgrest.deleteTombstonesBefore(table, cutoffIso, token)
            }
        }
        store.collectTombstones(before = cutoff, at = now)
    }

    // ── Failure classification ─────────────────────────────────────────────────────

    private fun Throwable.toFailure(): SyncFailure = when {
        this is SessionExpiredException -> SyncFailure.SESSION_EXPIRED
        this is SyncHttpException && status == 401 -> SyncFailure.SESSION_EXPIRED
        this is SyncHttpException -> SyncFailure.SERVER
        this is StackAuthException -> SyncFailure.SERVER
        this is IOException -> SyncFailure.OFFLINE
        else -> SyncFailure.SERVER
    }

    private fun String.asInstant(): Instant =
        runCatching { OffsetDateTime.parse(this).toInstant() }
            .getOrElse { runCatching { Instant.parse(this) }.getOrDefault(Instant.EPOCH) }

    /** The cursor, moved back by the overlap the pull deliberately re-reads. */
    private fun String.minusOverlap(): String = asInstant().minus(PULL_OVERLAP).toString()

    private companion object {
        const val TABLE_TASKS = "tasks"
        const val TABLE_PROJECTS = "projects"
        const val TABLE_SECTIONS = "sections"
        const val TABLE_TAGS = "tags"

        const val PAGE_SIZE = 1000
        const val BATCH_SIZE = 500

        val PULL_OVERLAP: Duration = Duration.ofSeconds(5)
        val SWEEP_INTERVAL: Duration = Duration.ofDays(1)

        /** What stands where realtime's ~1s delivery stood: the other device sees a change within
         *  about a minute while both apps are in the foreground. */
        val FOREGROUND_POLL_INTERVAL: Duration = Duration.ofSeconds(60)

        /** The horizon ADR 0001 chose and ADR 0002 kept. A device offline longer than this
         *  re-inserts what the others deleted; that is an accepted loss, not a solved problem. */
        val TOMBSTONE_HORIZON: Duration = Duration.ofDays(90)

        /**
         * Generous on purpose: a Neon compute that scaled to zero answers its first request only
         * after a cold start, and that has to read as latency, not as OFFLINE.
         */
        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }
    }
}

/** The stored session is expired or revoked and a refresh could not save it — the user has to
 *  sign in again. The session stays stored so Settings can say who it belonged to. */
internal class SessionExpiredException : Exception("session expired")

/**
 * The engine's view of the stored [StackSession], and the refresh state machine
 * (ADR 0002 decision 6's worry, hand-rolled now that no library carries it: silent token refresh
 * is where a client is most likely to be subtly wrong, so all of it lives in this one class).
 *
 * [withAccessToken] hands the block a token it believes in: refreshed preemptively when the JWT
 * is within [EXPIRY_MARGIN] of its `exp` (or unreadable), refreshed once more and retried when
 * the server answers 401 anyway. A refresh that fails with a 4xx — revoked, expired, deleted —
 * throws [SessionExpiredException]; network trouble propagates as IOException and reads as
 * offline, because an expired wifi login must not log the user out.
 *
 * The refresh runs under its own mutex: the pull fans out four requests at once, and four
 * near-expired tokens must produce one refresh, not four (Stack Auth may rotate the refresh
 * token, and the second spend of a rotated token is a revocation). Whoever waits on the lock
 * re-reads the store first and takes the session a faster caller already renewed.
 */
private class SessionTokens(
    private val store: SyncStore,
    private val stackAuth: StackAuthClient,
) {

    private val refreshMutex = Mutex()

    suspend fun current(): StackSession? = StackSession.decodeOrNull(store.state().session)

    suspend fun <T> withAccessToken(block: suspend (String) -> T): T {
        var session = current() ?: throw SessionExpiredException()
        val expiresAt = jwtExpiresAtOrNull(session.accessToken)
        if (expiresAt == null || expiresAt.isBefore(Instant.now().plus(EXPIRY_MARGIN))) {
            session = refreshFrom(session)
        }
        return try {
            block(session.accessToken)
        } catch (e: SyncHttpException) {
            if (e.status != UNAUTHORIZED) throw e
            val renewed = refreshFrom(session)
            try {
                block(renewed.accessToken)
            } catch (e2: SyncHttpException) {
                if (e2.status == UNAUTHORIZED) throw SessionExpiredException() else throw e2
            }
        }
    }

    private suspend fun refreshFrom(stale: StackSession): StackSession = refreshMutex.withLock {
        val stored = current() ?: throw SessionExpiredException()
        // A caller that waited on the lock finds the session someone faster already renewed.
        if (stored.accessToken != stale.accessToken) return stored
        val answer = try {
            stackAuth.refresh(stored.refreshToken)
        } catch (e: StackAuthException) {
            if (e.status in 400..499) throw SessionExpiredException() else throw e
        }
        val renewed = stored.copy(
            accessToken = answer.accessToken,
            refreshToken = answer.refreshToken?.takeIf { it.isNotBlank() } ?: stored.refreshToken,
        )
        store.setSession(renewed.encode())
        renewed
    }

    private companion object {
        const val UNAUTHORIZED = 401
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(30)
    }
}
