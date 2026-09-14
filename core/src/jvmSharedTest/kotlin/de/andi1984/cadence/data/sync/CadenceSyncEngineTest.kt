package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The engine against a hand-fed HTTP engine — the wire shapes the Data API and Neon Auth
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

    // ── Unconfigured ───────────────────────────────────────────────────────────────

    @Test
    fun `a build without endpoints is inert - no request, a stored session ignored`() = runTest {
        // A session left behind by a configured build of the same install must not be replayed
        // against nothing — and must not make the header claim there is an account.
        val store = InMemorySyncStore().apply { stateValue = signedInState() }
        val http = RecordingHttp { unexpected(it) }
        val engine = CadenceSyncEngine(
            store = store, scope = backgroundScope, httpClient = HttpClient(http.engine), config = null,
        )
        runCurrent()

        assertEquals(SyncStatus.Unconfigured, engine.status.value)
        assertEquals(false, engine.isSignedIn())
        assertEquals(SyncOutcome.SignedOut, engine.syncOnce())
        assertTrue(engine.signIn("me@example.org", "pw") is SignInResult.Failed)
        assertEquals(SyncStatus.Unconfigured, engine.status.value)
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

    // ── Paging ─────────────────────────────────────────────────────────────────────

    /** A full page means there may be more; a short one means that table is done. The cursor
     *  advances once per page, in the same call that merges it. */
    @Test
    fun `a full page is followed by another and a short one ends the table`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState().copy(lastSweepAt = Instant.now())
        val http = RecordingHttp { request ->
            when (request.url.encodedPath) {
                "/tasks" -> when (request.url.parameters["server_updated_at"]) {
                    // No cursor yet: a full page, ending at 08:16:39.
                    null -> respondJson(taskPage(count = 1000, from = "2026-08-25T08:00:00Z"))
                    // From that cursor, overlapped: one row, so this is the last page.
                    "gte.2026-08-25T08:16:34Z" ->
                        respondJson(taskPage(count = 1, from = "2026-08-25T09:00:00Z", prefix = "late"))
                    else -> unexpected(request)
                }
                else -> respondJson("[]")
            }
        }

        val outcome = engine(store, http).syncOnce()

        assertEquals(SyncOutcome.Ok(pulled = 1001, pushed = 0), outcome)
        assertEquals(2, http.requests.count { it.url.encodedPath == "/tasks" })
        // A table that answered short is not asked again on the next iteration.
        assertEquals(1, http.requests.count { it.url.encodedPath == "/projects" })
        assertEquals(
            listOf("2026-08-25T08:16:39Z", "2026-08-25T09:00:00Z"),
            store.advancedTaskCursors,
        )
        assertEquals("2026-08-25T09:00:00Z", store.stateValue.taskCursor)
        assertEquals(1001, store.mergedTasks.size)
    }

    /** More than a page of rows sharing one `server_updated_at` would be asked for forever:
     *  the same cursor, the same page. The round stops instead, and the next one starts from
     *  the same place. */
    @Test
    fun `a full page that does not move the cursor ends the pull instead of looping`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState().copy(lastSweepAt = Instant.now())
        val http = RecordingHttp { request ->
            when (request.url.encodedPath) {
                "/tasks" -> respondJson(taskPage(count = 1000, from = "2026-08-25T08:00:00Z", step = 0))
                else -> respondJson("[]")
            }
        }

        val outcome = engine(store, http).syncOnce()

        assertTrue(outcome is SyncOutcome.Ok)
        // Once with no cursor, once from the cursor the first page set, and then no more.
        assertEquals(2, http.requests.count { it.url.encodedPath == "/tasks" })
        assertEquals("2026-08-25T08:00:00Z", store.stateValue.taskCursor)
    }

    // ── Batching ───────────────────────────────────────────────────────────────────

    /** The push goes out in reference order — projects, sections, tags, tasks — and a table with
     *  more rows than one batch carries is sent in several, in the store's order. The watermark
     *  is then the newest `updatedAt` among everything sent, wherever in the list it stood. */
    @Test
    fun `a push above the batch size is chunked in reference order and the watermark is the newest row sent`() =
        runTest {
            val store = InMemorySyncStore()
            store.stateValue = signedInState().copy(lastSweepAt = Instant.now())
            val base = Instant.parse("2026-08-24T09:00:00Z")
            val newest = Instant.parse("2026-08-26T00:00:00Z")
            store.projects = listOf(Project(id = "p1", name = "p", updatedAt = base.plusSeconds(1)))
            store.sections =
                listOf(Section(id = "s1", projectId = "p1", name = "s", updatedAt = base.plusSeconds(2)))
            store.tags = listOf(Tag(id = "g1", name = "g", updatedAt = base.plusSeconds(3)))
            store.tasks = List(1200) { i ->
                Task(
                    id = "t$i",
                    title = "task $i",
                    // The newest row sits in the middle of the second batch, not at the end.
                    updatedAt = if (i == 600) newest else base.plusSeconds(10L + i),
                )
            }
            val http = RecordingHttp { request ->
                when (request.method) {
                    HttpMethod.Get -> respondJson("[]")
                    HttpMethod.Post -> respondJson("")
                    else -> unexpected(request)
                }
            }

            val outcome = engine(store, http).syncOnce()

            assertEquals(SyncOutcome.Ok(pulled = 0, pushed = 1203), outcome)
            val posts = http.requests.filter { it.method == HttpMethod.Post }
            assertEquals(
                listOf("/projects", "/sections", "/tags", "/tasks", "/tasks", "/tasks"),
                posts.map { it.url.encodedPath },
            )
            val taskBatches = posts.filter { it.url.encodedPath == "/tasks" }.map { it.idsInBody() }
            assertEquals(listOf(500, 500, 200), taskBatches.map { it.size })
            assertEquals(List(500) { "t$it" }, taskBatches[0])
            assertEquals(List(1200) { "t$it" }, taskBatches.flatten())
            assertEquals(newest, store.stateValue.pushWatermark)
        }

    // ── The sweep ──────────────────────────────────────────────────────────────────

    /** Server first, local second: a server sweep that fails leaves `lastSweepAt` unstamped and
     *  the local tombstones in place, so the whole sweep is retried next round rather than the
     *  local copy being collected before the server's. */
    @Test
    fun `a failed server sweep fails the round and skips the local sweep`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState()
        store.tasks = listOf(Task(id = "t1", title = "one", updatedAt = Instant.parse("2026-08-24T09:00:00Z")))
        val http = RecordingHttp { request ->
            when (request.method) {
                HttpMethod.Get -> respondJson("[]")
                HttpMethod.Post -> respondJson("")
                HttpMethod.Delete -> respondJson("", HttpStatusCode.InternalServerError)
                else -> unexpected(request)
            }
        }
        val engine = engine(store, http)

        val outcome = engine.syncOnce()

        assertEquals(SyncOutcome.Failed(SyncFailure.SERVER), outcome)
        assertTrue(store.sweeps.isEmpty())
        assertNull(store.stateValue.lastSweepAt)
        assertEquals(SyncFailure.SERVER, (engine.status.value as SyncStatus.Failed).reason)
        // The push before it did land, and stays landed: every step is its own.
        assertEquals(Instant.parse("2026-08-24T09:00:00Z"), store.stateValue.pushWatermark)
    }

    @Test
    fun `the sweep runs at most once a day`() = runTest {
        val store = InMemorySyncStore()
        val recentSweep = Instant.now().minus(Duration.ofHours(23))
        store.stateValue = signedInState().copy(lastSweepAt = recentSweep)
        store.tasks = listOf(Task(id = "t1", title = "one", updatedAt = Instant.parse("2026-08-24T09:00:00Z")))
        val http = RecordingHttp { request ->
            when (request.method) {
                HttpMethod.Get -> respondJson("[]")
                HttpMethod.Post -> respondJson("")
                HttpMethod.Delete -> respondJson("")
                else -> unexpected(request)
            }
        }
        val engine = engine(store, http)

        assertTrue(engine.syncOnce() is SyncOutcome.Ok)

        assertEquals(0, http.requests.count { it.method == HttpMethod.Delete })
        assertTrue(store.sweeps.isEmpty())
        assertEquals(recentSweep, store.stateValue.lastSweepAt)

        // A day and a bit later, with something new to push, it is due again.
        store.stateValue = store.stateValue.copy(lastSweepAt = Instant.now().minus(Duration.ofHours(25)))
        store.tasks = store.tasks + Task(id = "t2", title = "two", updatedAt = Instant.parse("2026-08-24T10:00:00Z"))

        assertTrue(engine.syncOnce() is SyncOutcome.Ok)

        assertEquals(4, http.requests.count { it.method == HttpMethod.Delete })
        assertEquals(1, store.sweeps.size)
        assertTrue(store.stateValue.lastSweepAt!!.isAfter(recentSweep))
    }

    // ── Failure reporting ──────────────────────────────────────────────────────────

    /** `status` is a StateFlow, so two identical failures would collapse into one there; the
     *  screen hears about each round through `failures` instead (ADR 0002, decision 14). */
    @Test
    fun `a failed round sets the status and emits on failures once per round`() = runTest {
        val store = InMemorySyncStore()
        store.stateValue = signedInState()
        val http = RecordingHttp { respondJson("", HttpStatusCode.InternalServerError) }
        val engine = engine(store, http)
        val received = mutableListOf<SyncFailure>()
        backgroundScope.launch { engine.failures.collect { received += it } }
        runCurrent()

        assertEquals(SyncOutcome.Failed(SyncFailure.SERVER), engine.syncOnce())
        runCurrent()

        assertEquals(SyncStatus.Failed(SyncFailure.SERVER, "me@example.org", null), engine.status.value)
        assertEquals(listOf(SyncFailure.SERVER), received)

        assertEquals(SyncOutcome.Failed(SyncFailure.SERVER), engine.syncOnce())
        runCurrent()

        assertEquals(listOf(SyncFailure.SERVER, SyncFailure.SERVER), received)
    }

    // ── Background triggers ────────────────────────────────────────────────────────

    @Test
    fun `syncInBackgroundIfStale runs a round only when the last one is older than the given age`() =
        runTest {
            val store = InMemorySyncStore()
            store.stateValue = signedInState().copy(lastSyncedAt = Instant.now(), lastSweepAt = Instant.now())
            val http = RecordingHttp { respondJson("[]") }
            val engine = engine(store, http)

            engine.syncInBackgroundIfStale(Duration.ofMinutes(5))
            advanceUntilIdle()

            assertEquals(0, http.requests.size)

            val startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            store.stateValue = store.stateValue.copy(lastSyncedAt = startedAt.minus(Duration.ofMinutes(6)))
            engine.syncInBackgroundIfStale(Duration.ofMinutes(5))
            engine.status.first { it is SyncStatus.Idle && it.lastSyncedAt?.isBefore(startedAt) == false }

            assertEquals(4, http.requests.count { it.method == HttpMethod.Get })
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
        config = NeonConfig("https://data.example", "https://auth.example"),
    )

    private fun signedInState(accessToken: String = jwt(FAR_FUTURE)) = SyncState(
        session = NeonSession(accessToken, "cookie-1", "me@example.org").encode(),
    )

    private fun InMemorySyncStore.accessToken(): String =
        NeonSession.decodeOrNull(stateValue.session)!!.accessToken

    /** One pull page of tasks, `count` rows whose `server_updated_at` starts at [from] and moves
     *  [step] seconds per row — 0 for a page that shares one timestamp. */
    private fun taskPage(count: Int, from: String, step: Long = 1, prefix: String = "r"): String {
        val start = Instant.parse(from)
        return (0 until count).joinToString(prefix = "[", postfix = "]") { i ->
            val stamp = start.plusSeconds(step * i)
            """{"id":"$prefix$i","title":"row $i","created_at":"$stamp","updated_at":"$stamp",
               "priority":2,"sort_order":0,"server_updated_at":"$stamp"}"""
        }
    }

    /** The `id` of every record in an upsert body, in the order they were sent. */
    private fun HttpRequestData.idsInBody(): List<String> =
        Json.parseToJsonElement((body as TextContent).text).jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
}
