package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.Project
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
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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
     * Signs out and forgets the session, both cursors and the watermark.
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
        } catch (e: Throwable) {
            val reason = e.toFailure()
            _status.value = SyncStatus.Failed(reason, email, store.state().lastSyncedAt)
            _failures.tryEmit(reason)
            SyncOutcome.Failed(reason)
        }
    }

    // ── Pull ───────────────────────────────────────────────────────────────────────

    /**
     * Reads both tables forward from their cursors and merges each page as it lands.
     *
     * The cursor is re-read five seconds early every round. `now()` in Postgres is
     * transaction-start time, so a transaction that began before ours and committed after it can
     * land *behind* a cursor we already advanced past. Re-reading a little is free — the merge
     * discards anything it already has by timestamp — and missing a row is not.
     */
    private suspend fun pull(): Int {
        val state = store.state()
        var taskCursor = state.taskCursor
        var projectCursor = state.projectCursor
        var merged = 0

        while (true) {
            val projects = fetchProjects(projectCursor)
            val tasks = fetchTasks(taskCursor)
            if (projects.isEmpty() && tasks.isEmpty()) break

            val nextTaskCursor = tasks.mapNotNull { it.serverUpdatedAt }.maxByOrNull { it.asInstant() }
            val nextProjectCursor =
                projects.mapNotNull { it.serverUpdatedAt }.maxByOrNull { it.asInstant() }

            store.mergeAndAdvance(
                projects = projects.map { it.toDomain() },
                tasks = tasks.map { it.toDomain() },
                taskCursor = nextTaskCursor,
                projectCursor = nextProjectCursor,
            )
            merged += projects.size + tasks.size

            val done = tasks.size < PAGE_SIZE && projects.size < PAGE_SIZE
            // A full page whose newest row carries the cursor we already had would ask for the
            // same page forever: more than a thousand rows sharing one microsecond. Stopping is
            // the safe end of that — the next round starts from the same place and the rows are
            // still there. Paging past this properly is phase 4's problem.
            val stuck = nextTaskCursor == taskCursor && nextProjectCursor == projectCursor
            taskCursor = nextTaskCursor ?: taskCursor
            projectCursor = nextProjectCursor ?: projectCursor
            if (done || stuck) break
        }
        return merged
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

    // ── Push ───────────────────────────────────────────────────────────────────────

    /**
     * Sends everything written since the watermark, projects first.
     *
     * The new watermark is the newest `updatedAt` actually sent, not "now": a row written while
     * this push was in flight has a stamp above that and is therefore still waiting for the next
     * round rather than being skipped by a clock that ran ahead of the data.
     */
    private suspend fun push(): Int {
        val watermark = store.state().pushWatermark
        val projects = store.projectsChangedSince(watermark)
        val tasks = store.tasksChangedSince(watermark)
        if (projects.isEmpty() && tasks.isEmpty()) return 0

        projects.chunked(BATCH_SIZE).forEach { batch ->
            client.from(TABLE_PROJECTS).upsert(batch.map { it.toRemote() }) {
                onConflict = CONFLICT_KEY
            }
        }
        tasks.chunked(BATCH_SIZE).forEach { batch ->
            client.from(TABLE_TASKS).upsert(batch.map { it.toRemote() }) {
                onConflict = CONFLICT_KEY
            }
        }

        val newest = (projects.map(Project::updatedAt) + tasks.map(Task::updatedAt)).max()
        store.setPushWatermark(newest)
        return projects.size + tasks.size
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
        const val COLUMN_SERVER_UPDATED_AT = "server_updated_at"

        /** The primary key both tables carry, and therefore what an upsert conflicts on. */
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
