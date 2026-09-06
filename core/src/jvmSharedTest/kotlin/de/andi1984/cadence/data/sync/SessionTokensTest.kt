package de.andi1984.cadence.data.sync

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * The refresh state machine on its own — the piece ADR 0005 decision 3 names as the one most
 * likely to be subtly wrong, pinned without a sync round around it. The HTTP fake answers
 * `/token` and nothing else; the "Data API" is whatever the block handed to [withAccessToken]
 * decides to do with the token it was given.
 */
class SessionTokensTest {

    /** A fixed "now", so a JWT can sit exactly on either side of the 30-second margin. */
    private val now: Instant = Instant.parse("2026-09-06T12:00:00Z")

    // ── Preemptive mint ────────────────────────────────────────────────────────────

    @Test
    fun `a JWT with time left is used as it is, with no request`() = runTest {
        val store = storeWith(jwt(now.epochSecond + 31))
        val http = RecordingHttp { unexpected(it) }

        val seen = tokens(store, http).withAccessToken { it }

        assertEquals(store.accessToken(), seen)
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `a JWT inside the expiry margin is re-minted before the block runs`() = runTest {
        val stale = jwt(now.epochSecond + 29)
        val fresh = jwt(FAR_FUTURE)
        val store = storeWith(stale)
        val http = RecordingHttp { request ->
            assertEquals("/token", request.url.encodedPath)
            // The mint rides the session cookie, never the JWT it is replacing.
            assertEquals("cookie-1", request.headers[HttpHeaders.Cookie])
            respondJson("""{"token":"$fresh"}""")
        }

        val seen = tokens(store, http).withAccessToken { it }

        assertEquals(fresh, seen)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `an unreadable JWT counts as expired`() = runTest {
        val fresh = jwt(FAR_FUTURE)
        val store = storeWith("not.a.jwt")
        val http = RecordingHttp { respondJson("""{"token":"$fresh"}""") }

        assertEquals(fresh, tokens(store, http).withAccessToken { it })
        assertEquals(1, http.requests.size)
    }

    // ── Persistence ────────────────────────────────────────────────────────────────

    @Test
    fun `a minted JWT is persisted beside the unchanged cookie and email`() = runTest {
        val fresh = jwt(FAR_FUTURE)
        val store = storeWith(jwt(0))
        val http = RecordingHttp { respondJson("""{"token":"$fresh"}""") }

        tokens(store, http).withAccessToken { }

        val stored = NeonSession.decodeOrNull(store.stateValue.session)
        assertNotNull(stored)
        assertEquals(fresh, stored?.accessToken)
        assertEquals("cookie-1", stored?.sessionCookie)
        assertEquals("me@example.org", stored?.email)
    }

    // ── The mutex ──────────────────────────────────────────────────────────────────

    /**
     * The pull fans out four requests at once. The first to find the JWT expired takes the lock
     * and mints; the other three must wait on it and then take what it minted, not mint again.
     * The `/token` answer is held back until all four have arrived, so the three genuinely queue
     * behind an in-flight mint rather than reading a store the first caller already updated.
     */
    @Test
    fun `four concurrent callers with an expired JWT produce exactly one mint`() = runTest {
        val fresh = jwt(FAR_FUTURE)
        val store = storeWith(jwt(0))
        val mintReached = CompletableDeferred<Unit>()
        val releaseMint = CompletableDeferred<Unit>()
        val http = RecordingHttp { request ->
            assertEquals("/token", request.url.encodedPath)
            mintReached.complete(Unit)
            releaseMint.await()
            respondJson("""{"token":"$fresh"}""")
        }
        val tokens = tokens(store, http)

        val callers = List(4) { async { tokens.withAccessToken { it } } }
        mintReached.await()
        runCurrent()

        // One request in flight, everybody parked behind it.
        assertEquals(1, http.requests.size)
        assertTrue(callers.none { it.isCompleted })

        releaseMint.complete(Unit)
        val seen = callers.awaitAll()

        assertEquals(List(4) { fresh }, seen)
        assertEquals(1, http.requests.size)
    }

    // ── 401 inside the block ───────────────────────────────────────────────────────

    @Test
    fun `a 401 inside the block mints once and retries once`() = runTest {
        val first = jwt(FAR_FUTURE)
        val second = jwt(FAR_FUTURE + 1)
        val store = storeWith(first)
        val http = RecordingHttp { respondJson("""{"token":"$second"}""") }
        val attempts = mutableListOf<String>()

        val result = tokens(store, http).withAccessToken { token ->
            attempts += token
            if (token == first) throw SyncHttpException(401, "JWT expired")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf(first, second), attempts)
        assertEquals(1, http.requests.size)
        assertEquals(second, store.accessToken())
    }

    @Test
    fun `a 401 that survives the retry reads as session expired`() = runTest {
        val store = storeWith(jwt(FAR_FUTURE))
        val http = RecordingHttp { respondJson("""{"token":"${jwt(FAR_FUTURE + 1)}"}""") }

        try {
            tokens(store, http).withAccessToken { throw SyncHttpException(401, "still no") }
            fail("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            // Two attempts, one mint between them — never a third.
            assertEquals(1, http.requests.size)
        }
    }

    @Test
    fun `any other Data API error propagates without a mint`() = runTest {
        val store = storeWith(jwt(FAR_FUTURE))
        val http = RecordingHttp { unexpected(it) }

        try {
            tokens(store, http).withAccessToken { throw SyncHttpException(500, "boom") }
            fail("expected SyncHttpException")
        } catch (e: SyncHttpException) {
            assertEquals(500, e.status)
        }
        assertEquals(0, http.requests.size)
    }

    // ── A mint that fails ──────────────────────────────────────────────────────────

    /** What the engine turns into SESSION_EXPIRED: a 4xx from `/token` means the session itself
     *  is dead. The session stays stored so Settings can still say whose it was. */
    @Test
    fun `a refused mint reads as session expired and keeps the session stored`() = runTest {
        val store = storeWith(jwt(0))
        val http = RecordingHttp {
            respondJson("""{"code":"UNAUTHORIZED"}""", HttpStatusCode.Unauthorized)
        }

        try {
            tokens(store, http).withAccessToken { }
            fail("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            assertNotNull(NeonSession.decodeOrNull(store.stateValue.session))
        }
    }

    /** What the engine turns into OFFLINE, not SESSION_EXPIRED: an expired wifi login must not
     *  log the user out. */
    @Test
    fun `network trouble during a mint propagates as an IO error`() = runTest {
        val store = storeWith(jwt(0))
        val http = RecordingHttp { throw IOException("captive portal") }

        try {
            tokens(store, http).withAccessToken { }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals(jwt(0), store.accessToken())
        }
    }

    /** And a 5xx is the server's problem, so it surfaces as the auth error it is. */
    @Test
    fun `an auth server error during a mint propagates as is`() = runTest {
        val store = storeWith(jwt(0))
        val http = RecordingHttp { respondJson("", HttpStatusCode.BadGateway) }

        try {
            tokens(store, http).withAccessToken { }
            fail("expected NeonAuthException")
        } catch (e: NeonAuthException) {
            assertEquals(502, e.status)
        }
    }

    @Test
    fun `signed out, there is no token to hand over`() = runTest {
        val http = RecordingHttp { unexpected(it) }

        try {
            tokens(InMemorySyncStore(), http).withAccessToken { }
            fail("expected SessionExpiredException")
        } catch (e: SessionExpiredException) {
            assertEquals(0, http.requests.size)
        }
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────

    private fun tokens(store: InMemorySyncStore, http: RecordingHttp) = SessionTokens(
        store = store,
        authClient = NeonAuthClient(HttpClient(http.engine), "https://auth.example"),
        clock = { now },
    )

    private fun storeWith(accessToken: String) = InMemorySyncStore().apply {
        stateValue = SyncState(
            session = NeonSession(accessToken, "cookie-1", "me@example.org").encode(),
        )
    }

    private fun InMemorySyncStore.accessToken(): String =
        NeonSession.decodeOrNull(stateValue.session)!!.accessToken
}
