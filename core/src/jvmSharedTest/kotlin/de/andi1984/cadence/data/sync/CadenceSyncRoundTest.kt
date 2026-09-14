package de.andi1984.cadence.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.data.db.SqlDelightSyncStore
import de.andi1984.cadence.data.db.SqlDelightTaskStore
import de.andi1984.cadence.domain.model.Task
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Sync rounds through HTTP and real SQLite: retries must preserve both rows and progress. */
@OptIn(ExperimentalCoroutinesApi::class)
class CadenceSyncRoundTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = CadenceDatabase(driver).also {
        CadenceDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    }
    private val store = SqlDelightSyncStore(database, Dispatchers.Unconfined)
    private val tasks = SqlDelightTaskStore(database, Dispatchers.Unconfined)
    private val clients = mutableListOf<HttpClient>()
    private val stamp = Instant.parse("2026-08-25T10:00:00Z")

    @After
    fun close() {
        clients.forEach { it.close() }
        driver.close()
    }

    @Test
    fun `an undecodable saved session makes no requests and preserves local tasks`() = runTest {
        store.setSession("not json")
        tasks.insert(Task(id = "local", title = "keep me", updatedAt = stamp))
        val http = RecordingHttp { unexpected(it) }
        val engine = engine(http)

        assertEquals(SyncOutcome.SignedOut, engine.syncOnce())
        assertFalse(engine.isSignedIn())
        assertTrue(http.requests.isEmpty())
        assertEquals("keep me", tasks.byId("local")?.title)
        assertEquals(Instant.EPOCH, store.state().pushWatermark)
    }

    @Test
    fun `cancelling a pull emits no failure or progress and a later round can retry`() = runTest {
        signIn()
        val before = store.state()
        val requested = CompletableDeferred<Unit>()
        var suspendRequests = true
        val http = RecordingHttp { request ->
            if (suspendRequests) {
                requested.complete(Unit)
                awaitCancellation()
            }
            if (request.method == HttpMethod.Get) respondJson("[]") else unexpected(request)
        }
        val engine = engine(http)
        val failures = mutableListOf<SyncFailure>()
        backgroundScope.launch { engine.failures.collect { failures += it } }
        runCurrent()
        var returnedNormally = false
        val round = backgroundScope.launch {
            engine.syncOnce()
            returnedNormally = true
        }
        requested.await()
        round.cancelAndJoin()
        runCurrent()

        assertFalse(returnedNormally)
        assertTrue(failures.isEmpty())
        assertFalse(engine.status.value is SyncStatus.Failed)
        assertEquals(before, store.state())

        suspendRequests = false
        assertEquals(SyncOutcome.Ok(0, 0), engine.syncOnce())
        assertTrue(engine.status.value is SyncStatus.Idle)
    }

    @Test
    fun `a failed second push batch keeps the watermark and retries every row including tombstones`() = runTest {
        signIn()
        val watermark = stamp.minusSeconds(1)
        store.setPushWatermark(watermark)
        repeat(501) { i ->
            tasks.insert(Task(
                id = "t$i", title = "task $i", updatedAt = stamp,
                deletedAt = if (i == 500) stamp else null,
            ))
        }
        var posts = 0
        var failSecondBatch = true
        val http = RecordingHttp { request ->
            when (request.method) {
                HttpMethod.Get -> respondJson("[]")
                HttpMethod.Post -> {
                    posts++
                    respondJson("", if (failSecondBatch && posts == 2) {
                        HttpStatusCode.InternalServerError
                    } else HttpStatusCode.OK)
                }
                else -> unexpected(request)
            }
        }
        val engine = engine(http)

        assertEquals(SyncOutcome.Failed(SyncFailure.SERVER), engine.syncOnce())
        assertEquals(2, posts)
        assertEquals(watermark, store.state().pushWatermark)
        assertNull(store.state().lastSyncedAt)
        assertEquals(501, store.tasksChangedSince(watermark).size)

        failSecondBatch = false
        val retryStart = http.requests.size
        assertEquals(SyncOutcome.Ok(0, 501), engine.syncOnce())
        val retried = http.requests.drop(retryStart).filter { it.method == HttpMethod.Post }
            .flatMap { Json.parseToJsonElement((it.body as TextContent).text).jsonArray }
        assertEquals((0..500).map { "t$it" }.toSet(), retried.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        }.toSet())
        assertEquals(stamp.toString(), retried.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == "t500"
        }.jsonObject.getValue("deleted_at").jsonPrimitive.content)
        assertEquals(stamp, store.state().pushWatermark)
        assertTrue(store.tasksChangedSince(stamp).isEmpty())
    }

    @Test
    fun `an older remote row advances its cursor without overwriting the local edit sent back`() = runTest {
        signIn()
        tasks.insert(Task(id = "t1", title = "new local title", updatedAt = stamp))
        val remoteStamp = stamp.minusSeconds(60)
        val serverCursor = "2026-08-25T11:00:00Z"
        val http = RecordingHttp { request ->
            when (request.method) {
                HttpMethod.Get -> if (request.url.encodedPath == "/tasks") {
                    respondJson("""[{"id":"t1","title":"old remote title",
                        "created_at":"$remoteStamp","updated_at":"$remoteStamp",
                        "priority":2,"sort_order":0,"server_updated_at":"$serverCursor"}]""")
                } else respondJson("[]")
                HttpMethod.Post -> {
                    val row = Json.parseToJsonElement((request.body as TextContent).text)
                        .jsonArray.single().jsonObject
                    assertEquals("new local title", row.getValue("title").jsonPrimitive.content)
                    respondJson("")
                }
                else -> unexpected(request)
            }
        }

        assertEquals(SyncOutcome.Ok(1, 1), engine(http).syncOnce())
        assertEquals("new local title", tasks.byId("t1")?.title)
        assertEquals(serverCursor, store.state().taskCursor)
        assertEquals(stamp, store.state().pushWatermark)
    }

    private suspend fun signIn() {
        store.setSession(NeonSession(jwt(FAR_FUTURE), "cookie-1", "me@example.org").encode())
        // These tests concern the round, not the separately covered daily sweep.
        store.collectTombstones(Instant.EPOCH, Instant.now())
    }

    private fun TestScope.engine(http: RecordingHttp): CadenceSyncEngine {
        val client = HttpClient(http.engine).also { clients += it }
        return CadenceSyncEngine(
            store = store, scope = backgroundScope, httpClient = client,
            config = NeonConfig("https://data.example", "https://auth.example"),
        )
    }
}
