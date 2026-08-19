package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Task
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.HasRecord
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * Why a round did not finish, at the granularity the user can act on.
 *
 * [PROJECT_ASLEEP] is worth telling apart from [OFFLINE]: a Supabase free-tier project pauses
 * after about a week of no requests and answers Supabase's own **HTTP 540** — it still resolves
 * DNS and completes TLS, so the app can say "the server is asleep, resume it in the dashboard"
 * rather than blaming the network for something no amount of waiting will fix.
 */
enum class SyncFailure { OFFLINE, PROJECT_ASLEEP, SESSION_EXPIRED, SERVER }

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
 * One sync round, and the session it needs.
 *
 * A plain class in `:core`, not a port (ADR 0002, decision 7): `ui/platform/Ports.kt` exists for
 * what the *machine* does differently — alarms, a file picker, a SAF uri — and HTTPS is not that.
 * Both shells construct one in their `AppContainer` on the application scope and hand it to the
 * ViewModel, the same way they already hand over the concrete `CadenceRepository`.
 *
 * The round, under [mutex] so two of them cannot interleave, every step idempotent so any failure
 * means "stop, try again later":
 *
 * 1. Pull rows at or after the stored cursor, in pages, and merge each page as it arrives.
 * 2. Push local rows written since the watermark, tombstones included, as an upsert.
 * 3. Collect tombstones past the horizon, at most once a day, and only after a round that pushed.
 *
 * The push sends everything above the watermark rather than tracking which rows are dirty,
 * because the server can tell: its trigger drops a write whose `updated_at` is not strictly
 * greater than the stored row's, so a row that arrived *from* the server and gets pushed straight
 * back is a no-op there.
 */
class CadenceSyncEngine(
    private val store: SyncStore,
    private val scope: CoroutineScope,
    url: String = SupabaseConfig.url,
    anonKey: String = SupabaseConfig.anonKey,
) {

    private val client: SupabaseClient = createSupabaseClient(url, anonKey) {
        // Both halves of the wire contract — unknown keys ignored on the way in, defaults still
        // written on the way out — live with the records themselves; see [SyncJson].
        defaultSerializer = KotlinXSerializer(SyncJson)
        install(Auth) {
            // The session belongs next to the cursor it has to stay consistent with, not in
            // `java.util.prefs`, which is where this library's JVM default would put it.
            sessionManager = DatabaseSessionManager(store)
            alwaysAutoRefresh = true
            autoLoadFromStorage = true
        }
        install(Postgrest)
        // Realtime is an accelerant beside the round, never in place of it (ADR 0002, decision
        // 12): it carries a change over in about a second, and everything it can drop is picked
        // up by the next pull.
        install(Realtime)
    }

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

    /** The socket's whole lifetime, held so the shell can end it — see [startRealtime]. */
    private var realtimeJob: Job? = null

    init {
        // The signed-in/out half of the status comes from the library — it loads the stored
        // session at start and drops it when a refresh finally fails — while the "when did this
        // last work" half is ours. Whichever moves last wins, which is why a running round
        // republishes its own state rather than being overwritten by a session event.
        scope.launch {
            client.auth.sessionStatus.collect { sessionStatus ->
                _status.value = when (sessionStatus) {
                    is SessionStatus.Authenticated ->
                        SyncStatus.Idle(sessionStatus.email(), store.state().lastSyncedAt)
                    is SessionStatus.RefreshFailure ->
                        SyncStatus.Failed(SyncFailure.OFFLINE, null, store.state().lastSyncedAt)
                    is SessionStatus.NotAuthenticated -> SyncStatus.SignedOut
                    is SessionStatus.Initializing -> _status.value
                }
            }
        }
    }

    /**
     * Signs in with the one account this project has. There is no sign-up here on purpose:
     * sign-ups are disabled in the dashboard and the account is created there (ADR 0002,
     * decision 2), so a registration form would only ever produce an error.
     */
    suspend fun signIn(email: String, password: String): SignInResult = try {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        SignInResult.Ok
    } catch (e: AuthRestException) {
        // 400 with `invalid_credentials` is the ordinary "wrong password"; anything else from
        // the auth API is worth showing verbatim rather than flattening into the same message.
        if (e.statusCode == 400) SignInResult.WrongCredentials
        else SignInResult.Failed(e.errorDescription?.ifBlank { null } ?: e.error)
    } catch (e: HttpRequestException) {
        SignInResult.Offline
    } catch (e: IOException) {
        SignInResult.Offline
    } catch (e: RestException) {
        SignInResult.Failed(e.description?.ifBlank { null } ?: e.error)
    }

    /**
     * Signs out and forgets the session, every cursor and the watermark.
     *
     * It deliberately deletes nothing: the local database is the source of truth and signing out
     * is not a delete. Clearing the cursors is what makes signing back in start from the
     * beginning — which is right, because this device has no idea what the server did meanwhile.
     */
    suspend fun signOut() {
        runCatching { client.auth.signOut() }
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

    // ── Realtime ───────────────────────────────────────────────────────────────────

    /**
     * Opens the change socket, and keeps re-opening it (ADR 0002, decision 12).
     *
     * The shell decides how long that lasts, because the answer differs: `:app-android` starts
     * this in the foreground and [stopRealtime]s on the way out — a background websocket is the
     * wakelock `WorkManager` was rejected to avoid — while `:app-desktop` starts it once and
     * leaves it open for the process, minimised included. A desktop that drops the socket on
     * alt-tab drops it exactly when the phone is in use, which is the one case this exists for.
     *
     * Calling it twice is a no-op, and signed out it costs nothing: the loop waits for a session
     * rather than connecting to be told it has none.
     */
    @Synchronized
    fun startRealtime() {
        if (realtimeJob?.isActive == true) return
        realtimeJob = scope.launch { realtimeLoop() }
    }

    /** Closes the socket and stops re-opening it. The pull is what covers the gap afterwards. */
    @Synchronized
    fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        // Guarded because this is also the signed-out path: an app that goes to the background
        // without ever having connected must not be the one call that throws in `onStop`.
        if (client.realtime.status.value != Realtime.Status.DISCONNECTED) {
            client.realtime.disconnect()
        }
    }

    /**
     * Subscribe, listen, and on any failure wait and subscribe again — 1s, doubling to 30s.
     *
     * The backoff is ours rather than the library's because what follows a reconnect is ours too:
     * every successful subscribe runs a full [syncOnce], since the socket's downtime is exactly
     * the gap the cursor already covers. That round is also what makes the unavoidable race here
     * harmless — a change committed between the join and the first delivered event is simply
     * pulled.
     */
    private suspend fun realtimeLoop() {
        var backoff = REALTIME_MIN_BACKOFF
        while (true) {
            val session = client.auth.sessionStatus
                .first { it is SessionStatus.Authenticated } as SessionStatus.Authenticated
            val userId = session.session.user?.id
            if (userId == null) {
                // No user on an authenticated session is not a thing to retry in a tight loop.
                delay(REALTIME_MAX_BACKOFF.toMillis())
                continue
            }
            try {
                listen(userId)
                // A clean return means the account went away, not that anything broke.
                backoff = REALTIME_MIN_BACKOFF
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                delay(backoff.toMillis())
                backoff = minOf(backoff.multipliedBy(2), REALTIME_MAX_BACKOFF)
            }
        }
    }

    /**
     * One channel, all three tables, filtered server-side on `user_id`.
     *
     * The filter is not decoration: without it the socket carries every account's rows and RLS
     * drops them at delivery, which is waste plus one more thing to get wrong.
     *
     * Returns when the session ends; throws when the socket does. Every flow is collected before
     * [RealtimeChannel.subscribe] because a `postgres_changes` binding is registered as its flow
     * is collected and only travels with the join that follows it.
     */
    private suspend fun listen(userId: String) = coroutineScope {
        val channel = client.realtime.channel(REALTIME_CHANNEL)
        val tasks = channel.postgresChangeFlow<PostgresAction>(schema = SCHEMA) {
            table = TABLE_TASKS
            filter(COLUMN_USER_ID, FilterOperator.EQ, userId)
        }
        val projects = channel.postgresChangeFlow<PostgresAction>(schema = SCHEMA) {
            table = TABLE_PROJECTS
            filter(COLUMN_USER_ID, FilterOperator.EQ, userId)
        }
        val sections = channel.postgresChangeFlow<PostgresAction>(schema = SCHEMA) {
            table = TABLE_SECTIONS
            filter(COLUMN_USER_ID, FilterOperator.EQ, userId)
        }

        val listeners = listOf(
            launch { tasks.collect { action -> action.record()?.let { mergeRemoteTask(it) } } },
            launch { projects.collect { action -> action.record()?.let { mergeRemoteProject(it) } } },
            launch { sections.collect { action -> action.record()?.let { mergeRemoteSection(it) } } },
            // Every reconnect the library manages under us gets its own full round, for the same
            // reason the first subscribe does: the socket's downtime is exactly the gap the
            // cursor covers. `drop(1)` skips the *first* connected — that one is the socket this
            // subscribe just opened, and the round for it is the explicit one below.
            launch {
                client.realtime.status
                    .filter { it == Realtime.Status.CONNECTED }
                    .drop(1)
                    .collect { syncOnce() }
            },
        )

        try {
            channel.subscribe(blockUntilSubscribed = true)
            syncOnce()
            // Nothing else to do here: the listeners are running, and the library rejoins on its
            // own. This returns when the account goes away, and is cancelled when the shell says
            // to stop.
            client.auth.sessionStatus.first { it is SessionStatus.NotAuthenticated }
        } finally {
            listeners.forEach { it.cancel() }
            withContext(NonCancellable) { runCatching { client.realtime.removeChannel(channel) } }
        }
    }

    /**
     * Folds one payload row in **without touching the cursor** (ADR 0002, decision 12).
     *
     * Realtime is at-most-once: a dropped socket loses events silently, and a cursor advanced
     * past an event that never arrived has skipped that row forever. Merging is idempotent and
     * therefore safe; advancing from a payload is not, so the cursors travel as null and the next
     * pull re-fetches the same rows harmlessly.
     *
     * A row that does not decode is dropped rather than thrown: one unreadable payload must not
     * take the socket down with it, and the pull will bring the same row back.
     */
    private suspend fun mergeRemoteTask(record: JsonObject) {
        val task = runCatching { SyncJson.decodeFromJsonElement(RemoteTask.serializer(), record) }
            .getOrNull() ?: return
        mutex.withLock {
            store.mergeAndAdvance(
                projects = emptyList(),
                sections = emptyList(),
                tasks = listOf(task.toDomain()),
                taskCursor = null,
                projectCursor = null,
                sectionCursor = null,
            )
        }
    }

    private suspend fun mergeRemoteProject(record: JsonObject) {
        val project =
            runCatching { SyncJson.decodeFromJsonElement(RemoteProject.serializer(), record) }
                .getOrNull() ?: return
        mutex.withLock {
            store.mergeAndAdvance(
                projects = listOf(project.toDomain()),
                sections = emptyList(),
                tasks = emptyList(),
                taskCursor = null,
                projectCursor = null,
                sectionCursor = null,
            )
        }
    }

    private suspend fun mergeRemoteSection(record: JsonObject) {
        val section =
            runCatching { SyncJson.decodeFromJsonElement(RemoteSection.serializer(), record) }
                .getOrNull() ?: return
        mutex.withLock {
            store.mergeAndAdvance(
                projects = emptyList(),
                sections = listOf(section.toDomain()),
                tasks = emptyList(),
                taskCursor = null,
                projectCursor = null,
                sectionCursor = null,
            )
        }
    }

    /** The new version a payload carries, or null for the events that carry none. A delete is a
     *  tombstone `UPDATE` here (ADR 0002, decision 3), so `DELETE` needs no handling. */
    private fun PostgresAction.record(): JsonObject? = (this as? HasRecord)?.record

    suspend fun syncOnce(): SyncOutcome = mutex.withLock {
        val session = client.auth.sessionStatus.value as? SessionStatus.Authenticated
            ?: return SyncOutcome.SignedOut
        val email = session.email()
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
     * Reads all three tables forward from their cursors and merges each page as it lands.
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
        var merged = 0
        // Tracked per table rather than for the pull as a whole. The three are paged
        // independently, and asking a table that already answered with a short page costs a
        // request that can only return rows this round has merged already — which is what the old
        // shared `done` flag did on every iteration while another table was still catching up.
        var moreTasks = true
        var moreProjects = true
        var moreSections = true

        while (moreTasks || moreProjects || moreSections) {
            // No table's page depends on another's, so they travel together: one round trip's
            // latency per iteration instead of three.
            val projectPage = if (moreProjects) async { fetchProjects(projectCursor) } else null
            val sectionPage = if (moreSections) async { fetchSections(sectionCursor) } else null
            val taskPage = if (moreTasks) async { fetchTasks(taskCursor) } else null
            val projects = projectPage?.await().orEmpty()
            val sections = sectionPage?.await().orEmpty()
            val tasks = taskPage?.await().orEmpty()
            if (projects.isEmpty() && sections.isEmpty() && tasks.isEmpty()) break

            val nextTaskCursor = tasks.mapNotNull { it.serverUpdatedAt }.maxByOrNull { it.asInstant() }
            val nextProjectCursor =
                projects.mapNotNull { it.serverUpdatedAt }.maxByOrNull { it.asInstant() }
            val nextSectionCursor =
                sections.mapNotNull { it.serverUpdatedAt }.maxByOrNull { it.asInstant() }

            store.mergeAndAdvance(
                projects = projects.map { it.toDomain() },
                sections = sections.map { it.toDomain() },
                tasks = tasks.map { it.toDomain() },
                taskCursor = nextTaskCursor,
                projectCursor = nextProjectCursor,
                sectionCursor = nextSectionCursor,
            )
            merged += projects.size + sections.size + tasks.size

            // A full page whose newest row carries the cursor we already had would ask for the
            // same page forever: more than a thousand rows sharing one microsecond. Stopping is
            // the safe end of that — the next round starts from the same place and the rows are
            // still there. Paging past this properly is phase 4's problem.
            moreTasks = tasks.size >= PAGE_SIZE && nextTaskCursor != taskCursor
            moreProjects = projects.size >= PAGE_SIZE && nextProjectCursor != projectCursor
            moreSections = sections.size >= PAGE_SIZE && nextSectionCursor != sectionCursor
            taskCursor = nextTaskCursor ?: taskCursor
            projectCursor = nextProjectCursor ?: projectCursor
            sectionCursor = nextSectionCursor ?: sectionCursor
        }
        merged
    }

    private suspend fun fetchTasks(cursor: String?): List<RemoteTask> =
        client.from(TABLE_TASKS).select {
            filter { cursor?.let { gte(COLUMN_SERVER_UPDATED_AT, it.minusOverlap()) } }
            order(COLUMN_SERVER_UPDATED_AT, Order.ASCENDING)
            limit(PAGE_SIZE.toLong())
        }.decodeList()

    private suspend fun fetchProjects(cursor: String?): List<RemoteProject> =
        client.from(TABLE_PROJECTS).select {
            filter { cursor?.let { gte(COLUMN_SERVER_UPDATED_AT, it.minusOverlap()) } }
            order(COLUMN_SERVER_UPDATED_AT, Order.ASCENDING)
            limit(PAGE_SIZE.toLong())
        }.decodeList()

    private suspend fun fetchSections(cursor: String?): List<RemoteSection> =
        client.from(TABLE_SECTIONS).select {
            filter { cursor?.let { gte(COLUMN_SERVER_UPDATED_AT, it.minusOverlap()) } }
            order(COLUMN_SERVER_UPDATED_AT, Order.ASCENDING)
            limit(PAGE_SIZE.toLong())
        }.decodeList()

    // ── Push ───────────────────────────────────────────────────────────────────────

    /**
     * Sends everything written since the watermark: projects, then sections, then tasks.
     *
     * The order is the reference order — a section names its project and a task names its section
     * — so a round that fails part-way leaves the server with rows whose links already resolve.
     * Postgres enforces none of it (the mirror carries no foreign key either, for the reason the
     * local schema carries none), but a web client reading between two batches sees a coherent
     * list rather than a heading pointing at a project it has not been sent yet.
     *
     * The new watermark is the newest `updatedAt` actually sent, not "now": a row written while
     * this push was in flight has a stamp above that and is therefore still waiting for the next
     * round rather than being skipped by a clock that ran ahead of the data.
     */
    private suspend fun push(): Int {
        val watermark = store.state().pushWatermark
        val projects = store.projectsChangedSince(watermark)
        val sections = store.sectionsChangedSince(watermark)
        val tasks = store.tasksChangedSince(watermark)
        if (projects.isEmpty() && sections.isEmpty() && tasks.isEmpty()) return 0

        projects.chunked(BATCH_SIZE).forEach { batch ->
            client.from(TABLE_PROJECTS).upsert(batch.map { it.toRemote() }) {
                onConflict = CONFLICT_KEY
            }
        }
        sections.chunked(BATCH_SIZE).forEach { batch ->
            client.from(TABLE_SECTIONS).upsert(batch.map { it.toRemote() }) {
                onConflict = CONFLICT_KEY
            }
        }
        tasks.chunked(BATCH_SIZE).forEach { batch ->
            client.from(TABLE_TASKS).upsert(batch.map { it.toRemote() }) {
                onConflict = CONFLICT_KEY
            }
        }

        val newest = (
            projects.map(Project::updatedAt) +
                sections.map(Section::updatedAt) +
                tasks.map(Task::updatedAt)
            ).max()
        store.setPushWatermark(newest)
        return projects.size + sections.size + tasks.size
    }

    /** Tombstones are only collectable once they have been handed over, so this runs after a
     *  successful round and at most once a day. */
    private suspend fun sweepIfDue(now: Instant) {
        val lastSweep = store.state().lastSweepAt
        if (lastSweep != null && Duration.between(lastSweep, now) < SWEEP_INTERVAL) return
        store.collectTombstones(before = now.minus(TOMBSTONE_HORIZON), at = now)
    }

    // ── Failure classification ─────────────────────────────────────────────────────

    private fun Throwable.toFailure(): SyncFailure = when {
        this is UnauthorizedRestException -> SyncFailure.SESSION_EXPIRED
        this is RestException && statusCode == PROJECT_PAUSED_STATUS -> SyncFailure.PROJECT_ASLEEP
        this is RestException -> SyncFailure.SERVER
        this is HttpRequestException || this is IOException -> SyncFailure.OFFLINE
        else -> SyncFailure.SERVER
    }

    private fun SessionStatus.Authenticated.email(): String? = session.user?.email

    private fun String.asInstant(): Instant =
        runCatching { OffsetDateTime.parse(this).toInstant() }
            .getOrElse { runCatching { Instant.parse(this) }.getOrDefault(Instant.EPOCH) }

    /** The cursor, moved back by the overlap the pull deliberately re-reads. */
    private fun String.minusOverlap(): String = asInstant().minus(PULL_OVERLAP).toString()

    private companion object {
        const val TABLE_TASKS = "tasks"
        const val TABLE_PROJECTS = "projects"
        const val TABLE_SECTIONS = "sections"
        const val COLUMN_SERVER_UPDATED_AT = "server_updated_at"
        const val COLUMN_USER_ID = "user_id"
        const val SCHEMA = "public"

        /** One channel carries all three tables — one per table would be three sockets' worth of
         *  bookkeeping for the same account's rows. */
        const val REALTIME_CHANNEL = "cadence"

        val REALTIME_MIN_BACKOFF: Duration = Duration.ofSeconds(1)
        val REALTIME_MAX_BACKOFF: Duration = Duration.ofSeconds(30)

        /** The primary key every table carries, and therefore what an upsert conflicts on. */
        const val CONFLICT_KEY = "user_id,id"

        const val PAGE_SIZE = 1000
        const val BATCH_SIZE = 500

        /** Supabase's own status code for a project that has been paused for inactivity. */
        const val PROJECT_PAUSED_STATUS = 540

        val PULL_OVERLAP: Duration = Duration.ofSeconds(5)
        val SWEEP_INTERVAL: Duration = Duration.ofDays(1)

        /** The horizon ADR 0001 chose and ADR 0002 kept. A device offline longer than this
         *  re-inserts what the others deleted; that is an accepted loss, not a solved problem. */
        val TOMBSTONE_HORIZON: Duration = Duration.ofDays(90)
    }
}

/**
 * supabase-kt's session storage, pointed at `syncStateRow`.
 *
 * One small class rather than the whole auth flow written by hand: silent token refresh is where
 * a hand-rolled client is most likely to be subtly wrong, and its failure presents as "sync just
 * stopped" (ADR 0002, decision 6).
 */
private class DatabaseSessionManager(private val store: SyncStore) : SessionManager {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) {
        store.setSession(json.encodeToString(session))
    }

    /**
     * The library's contract is "return one or throw [NoSessionFoundException]" — the nullable
     * answer is `loadSessionOrNull`, which catches exactly that.
     *
     * A session that no longer decodes — written by an older version of the library, or a damaged
     * row — counts as absent rather than crashing the app at startup.
     */
    override suspend fun loadSession(): UserSession {
        val stored = store.state().session ?: throw NoSessionFoundException()
        return runCatching { json.decodeFromString<UserSession>(stored) }.getOrNull()
            ?: throw NoSessionFoundException()
    }

    override suspend fun deleteSession() {
        store.setSession(null)
    }
}
