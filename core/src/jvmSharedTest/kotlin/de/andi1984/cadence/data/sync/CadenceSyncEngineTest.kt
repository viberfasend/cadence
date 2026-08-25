package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Base64

/**
 * The engine against a hand-fed HTTP engine — the wire shapes the Data API and Stack Auth
 * actually see, without either being reachable. The store is an in-memory fake in the shape of
 * `:ui`'s `Fakes.kt`; the HTTP fake is Ktor's own [MockEngine], which is the same idea for
 * requests: every response is written by the test, nothing is stubbed by name.
 */
class CadenceSyncEngineTest {

    // ── Signed out ─────────────────────────────────────────────────────────────────

    @Test
    fun `signed out, a round makes no request and says so`() = runTest {
        val http = RecordingHttp { unexpected(it) }
        val engine = engine(InMemorySyncStore(), http)

        assertEquals(SyncOutcome.SignedOut, engine.syncOnce())
        assertEquals(0, http.requests.size)
    }

    // ── Sign-in ────────────────────────────────────────────────────────────────────

    @Test
    fun `sign-in stores the session and reports idle`() = runTest {
        val store = InMemorySyncStore()
        val minted = jwt(FAR_FUTURE)
        val http = RecordingHttp { request ->
            when (request.url.encodedPath) {
                "/sign-in/email" -> {
                    val body = (request.body as TextContent).text
                    assertTrue(body.contains("\"email\":\"me@example.org\""))
                    // The session travels only in Set-Cookie (signed form); the body's raw
                    // token is deliberately not usable — the live endpoint proved it.
                    respond(
                        content = """{"redirect":false,"token":"raw-unusable",
                           "user":{"id":"u1","email":"me@example.org"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            HttpHeaders.SetCookie to listOf(
                                "__Secure-neon-auth.session_token=session-1.sig%3D; Path=/; HttpOnly",
                            ),
                        ),
                    )
                }
                "/token" -> {
                    // The Data API JWT is minted right away, with the cookie replayed verbatim.
                    assertEquals(
                        "__Secure-neon-auth.session_token=session-1.sig%3D",
                        request.headers[HttpHeaders.Cookie],
                    )
                    respondJson("""{"token":"$minted"}""")
                }
                else -> unexpected(request)
            }
        }
        val engine = engine(store, http)

        val result = engine.signIn("  me@example.org  ", "pw")

        assertEquals(SignInResult.Ok, result)
        val session = NeonSession.decodeOrNull(store.stateValue.session)
        assertEquals(
            "__Secure-neon-auth.session_token=session-1.sig%3D",
            session?.sessionCookie,
        )
        assertEquals(minted, session?.accessToken)
        assertEquals("me@example.org", session?.email)
        assertEquals(SyncStatus.Idle("me@example.org", null), engine.status.value)
    }

    @Test
    fun `sign-in maps a credential error to wrong credentials`() = runTest {
        val http = RecordingHttp {
            respondJson("""{"code":"INVALID_EMAIL_OR_PASSWORD"}""", HttpStatusCode.Unauthorized)
        }
        assertEquals(SignInResult.WrongCredentials, engine(InMemorySyncStore(), http).signIn("a", "b"))
    }

    @Test
    fun `sign-in maps network trouble to offline`() = runTest {
        val http = RecordingHttp { throw IOException("no route") }
        assertEquals(SignInResult.Offline, engine(InMemorySyncStore(), http).signIn("a", "b"))
    }

    // ── The round ──────────────────────────────────────────────────────────────────

    @Test
    fun `a round pulls with the overlapped cursor, pushes with upsert semantics, and sweeps`() =
        runTest {
            val store = InMemorySyncStore()
            store.stateValue = signedInState().copy(
                taskCursor = "2026-08-25T10:00:00Z",
                // A push with something to send, and a sweep that is due (lastSweepAt null).
                pushWatermark = Instant.parse("2026-08-20T00:00:00Z"),
            )
            store.tasks = listOf(
                Task(id = "t1", title = "one", updatedAt = Instant.parse("2026-08-24T09:00:00Z")),
                Task(id = "t2", title = "two", updatedAt = Instant.parse("2026-08-24T10:00:00Z")),
            )
            val http = RecordingHttp { request ->
                when {
                    request.method == HttpMethod.Get -> {
                        assertEquals("Bearer ${store.accessToken()}", request.headers[HttpHeaders.Authorization])
                        if (request.url.encodedPath == "/tasks") {
                            // The stored cursor, minus the five-second overlap.
                            assertEquals(
                                "gte.2026-08-25T09:59:55Z",
                                request.url.parameters["server_updated_at"],
                            )
                        }
                        assertEquals("server_updated_at.asc", request.url.parameters["order"])
                        assertEquals("1000", request.url.parameters["limit"])
                        respondJson("[]")
                    }
                    request.method == HttpMethod.Post -> {
                        assertEquals("/tasks", request.url.encodedPath)
                        assertEquals("user_id,id", request.url.parameters["on_conflict"])
                        assertEquals(
                            "resolution=merge-duplicates,return=minimal",
                            request.headers["Prefer"],
                        )
                        val body = (request.body as TextContent).text
                        assertTrue(body.startsWith("[") && body.contains("\"id\":\"t2\""))
                        respondJson("")
                    }
                    request.method == HttpMethod.Delete -> {
                        assertEquals("not.is.null", request.url.parameters["deleted_at"])
                        assertTrue(
                            request.url.parameters["server_updated_at"].orEmpty().startsWith("lt."),
                        )
                        respondJson("")
                    }
                    else -> unexpected(request)
                }
            }
            val engine = engine(store, http)

            val outcome = engine.syncOnce()

            assertEquals(SyncOutcome.Ok(pulled = 0, pushed = 2), outcome)
            // The watermark is the newest updatedAt actually sent, never "now".
            assertEquals(Instant.parse("2026-08-24T10:00:00Z"), store.stateValue.pushWatermark)
            // Four pulls, one push batch, four sweep deletes.
            assertEquals(4, http.requests.count { it.method == HttpMethod.Get })
            assertEquals(1, http.requests.count { it.method == HttpMethod.Post })
            assertEquals(4, http.requests.count { it.method == HttpMethod.Delete })
            assertEquals(
                listOf("/tasks", "/projects", "/sections", "/tags").sorted(),
                http.requests.filter { it.method == HttpMethod.Delete }
                    .map { it.url.encodedPath }.sorted(),
            )
            // The local sweep ran only after the server one succeeded.
            assertTrue(store.tombstonesCollected)
        }

    @Test
    fun `a pulled page lands in the store and advances the cursor`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState()
        val http = RecordingHttp { request ->
            when (request.url.encodedPath) {
                "/tasks" -> respondJson(
                    """[{"id":"r1","title":"from server","created_at":"2026-08-25T08:00:00Z",
                       "updated_at":"2026-08-25T08:00:00Z","priority":2,"sort_order":0,
                       "server_updated_at":"2026-08-25T08:00:01+00:00"}]""".trimIndent(),
                )
                else -> respondJson("[]")
            }
        }
        val engine = engine(store, http)

        val outcome = engine.syncOnce()

        assertEquals(SyncOutcome.Ok(pulled = 1, pushed = 0), outcome)
        assertEquals(listOf("r1"), store.mergedTasks.map { it.id })
        assertEquals("2026-08-25T08:00:01+00:00", store.stateValue.taskCursor)
        assertNull(store.stateValue.projectCursor)
    }

    // ── Token lifecycle ────────────────────────────────────────────────────────────

    @Test
    fun `a 401 refreshes once, retries, and persists the renewed session`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState()
        val firstToken = store.accessToken()
        val secondToken = jwt(FAR_FUTURE + 1)
        var deniedOnce = false
        val http = RecordingHttp { request ->
            when {
                request.url.encodedPath == "/token" -> {
                    // The mint rides the session cookie, not the dead JWT.
                    assertEquals("cookie-1", request.headers[HttpHeaders.Cookie])
                    respondJson("""{"token":"$secondToken"}""")
                }
                request.headers[HttpHeaders.Authorization] == "Bearer $firstToken" -> {
                    deniedOnce = true
                    respondJson("", HttpStatusCode.Unauthorized)
                }
                else -> respondJson("[]")
            }
        }
        val engine = engine(store, http)

        val outcome = engine.syncOnce()

        assertTrue(deniedOnce)
        assertTrue(outcome is SyncOutcome.Ok)
        val session = NeonSession.decodeOrNull(store.stateValue.session)
        assertEquals(secondToken, session?.accessToken)
        // The session cookie itself never rotates on a mint.
        assertEquals("cookie-1", session?.sessionCookie)
    }

    @Test
    fun `a mint the server refuses reads as session expired`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState(accessToken = jwt(0)) // long expired: mint up front
        val http = RecordingHttp { request ->
            when (request.url.encodedPath) {
                "/token" ->
                    respondJson("""{"code":"UNAUTHORIZED"}""", HttpStatusCode.Unauthorized)
                else -> unexpected(request)
            }
        }
        val engine = engine(store, http)

        assertEquals(SyncOutcome.Failed(SyncFailure.SESSION_EXPIRED), engine.syncOnce())
        // The session stays stored — Settings still knows who it belonged to.
        assertNotEquals(null, store.stateValue.session)
    }

    @Test
    fun `an expired token refreshes before the first request`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState(accessToken = jwt(0))
        val renewed = jwt(FAR_FUTURE)
        val http = RecordingHttp { request ->
            when (request.url.encodedPath) {
                "/token" ->
                    respondJson("""{"token":"$renewed"}""")
                else -> {
                    assertEquals("Bearer $renewed", request.headers[HttpHeaders.Authorization])
                    respondJson("[]")
                }
            }
        }

        assertTrue(engine(store, http).syncOnce() is SyncOutcome.Ok)
    }

    @Test
    fun `network trouble mid-round reads as offline`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState()
        val http = RecordingHttp { throw IOException("cable pulled") }

        assertEquals(SyncOutcome.Failed(SyncFailure.OFFLINE), engine(store, http).syncOnce())
    }

    // ── Sign-out ───────────────────────────────────────────────────────────────────

    @Test
    fun `sign-out clears the stored state even when the server call fails`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState().copy(taskCursor = "2026-08-25T10:00:00Z")
        val http = RecordingHttp { respondJson("", HttpStatusCode.InternalServerError) }
        val engine = engine(store, http)

        engine.signOut()

        assertNull(store.stateValue.session)
        assertNull(store.stateValue.taskCursor)
        assertEquals(SyncStatus.SignedOut, engine.status.value)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────

    // The engine takes its scope from runTest's backgroundScope, per the house rule: a scope of
    // our own would leak whatever the engine ever launches into the scheduler drain.
    private fun kotlinx.coroutines.test.TestScope.engine(
        store: InMemorySyncStore,
        http: RecordingHttp,
    ) = CadenceSyncEngine(
        store = store,
        scope = backgroundScope,
        httpClient = HttpClient(http.engine),
        dataApiUrl = "https://data.example",
        authUrl = "https://auth.example",
    )

    private fun signedInState(accessToken: String = jwt(FAR_FUTURE)) = SyncState(
        session = NeonSession(accessToken, "cookie-1", "me@example.org").encode(),
    )

    private fun InMemorySyncStore.accessToken(): String =
        NeonSession.decodeOrNull(stateValue.session)!!.accessToken

    private companion object {
        /** Far enough that the 30-second refresh margin never triggers in a test. */
        const val FAR_FUTURE = 4_102_444_800L // 2100-01-01
    }
}

/** A syntactically valid JWT whose payload carries only `exp` — enough for the client, which
 *  reads the claim without verifying anything. */
private fun jwt(expEpochSecond: Long): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
    val payload = encoder.encodeToString("""{"exp":$expEpochSecond}""".toByteArray())
    return "$header.$payload.sig"
}

private fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun unexpected(request: HttpRequestData): Nothing =
    throw AssertionError("unexpected request: ${request.method.value} ${request.url}")

/** Records every request and answers with whatever the test's handler says. */
private class RecordingHttp(
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests: MutableList<HttpRequestData> =
        java.util.Collections.synchronizedList(mutableListOf())
    val engine = MockEngine { request ->
        requests += request
        handler(request)
    }
}

/** The store in `Fakes.kt`'s shape: plain state, every write visible to the test. */
private class InMemorySyncStore : SyncStore {

    var stateValue = SyncState()
    var tasks: List<Task> = emptyList()
    var projects: List<Project> = emptyList()
    var sections: List<Section> = emptyList()
    var tags: List<Tag> = emptyList()
    val mergedTasks = mutableListOf<Task>()
    var tombstonesCollected = false

    override suspend fun state(): SyncState = stateValue

    override suspend fun setSession(session: String?) {
        stateValue = stateValue.copy(session = session)
    }

    override suspend fun tasksChangedSince(since: Instant): List<Task> =
        tasks.filter { it.updatedAt.isAfter(since) }

    override suspend fun projectsChangedSince(since: Instant): List<Project> =
        projects.filter { it.updatedAt.isAfter(since) }

    override suspend fun sectionsChangedSince(since: Instant): List<Section> =
        sections.filter { it.updatedAt.isAfter(since) }

    override suspend fun tagsChangedSince(since: Instant): List<Tag> =
        tags.filter { it.updatedAt.isAfter(since) }

    override suspend fun mergeAndAdvance(
        projects: List<Project>,
        sections: List<Section>,
        tags: List<Tag>,
        tasks: List<Task>,
        taskCursor: String?,
        projectCursor: String?,
        sectionCursor: String?,
        tagCursor: String?,
    ) {
        mergedTasks += tasks
        stateValue = stateValue.copy(
            taskCursor = taskCursor ?: stateValue.taskCursor,
            projectCursor = projectCursor ?: stateValue.projectCursor,
            sectionCursor = sectionCursor ?: stateValue.sectionCursor,
            tagCursor = tagCursor ?: stateValue.tagCursor,
        )
    }

    override suspend fun setPushWatermark(at: Instant) {
        stateValue = stateValue.copy(pushWatermark = at)
    }

    override suspend fun setLastSyncedAt(at: Instant) {
        stateValue = stateValue.copy(lastSyncedAt = at)
    }

    override suspend fun collectTombstones(before: Instant, at: Instant) {
        tombstonesCollected = true
        stateValue = stateValue.copy(lastSweepAt = at)
    }

    override suspend fun clear() {
        stateValue = SyncState()
    }
}
