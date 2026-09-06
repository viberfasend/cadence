package de.andi1984.cadence.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** The stored session is expired or revoked and a refresh could not save it — the user has to
 *  sign in again. The session stays stored so Settings can say who it belonged to. */
internal class SessionExpiredException : Exception("session expired")

/**
 * The engine's view of the stored [NeonSession], and the refresh state machine
 * (ADR 0002 decision 6's worry, hand-rolled now that no library carries it: silent token refresh
 * is where a client is most likely to be subtly wrong, so all of it lives in this one class).
 *
 * [withAccessToken] hands the block a JWT it believes in: re-minted preemptively when it is
 * within [EXPIRY_MARGIN] of its `exp` (or unreadable), re-minted once more and retried when the
 * server answers 401 anyway. A mint the auth server refuses with a 4xx — the session is expired,
 * revoked or deleted — throws [SessionExpiredException]; network trouble propagates as
 * IOException and reads as offline, because an expired wifi login must not log the user out.
 *
 * The mint runs under its own mutex: the pull fans out four requests at once, and four expired
 * JWTs must produce one `GET /token`, not four. Whoever waits on the lock re-reads the store
 * first and takes the JWT a faster caller already minted.
 */
internal class SessionTokens(
    private val store: SyncStore,
    private val authClient: NeonAuthClient,
    /** "Now", for the expiry check — a parameter so a test can put a JWT exactly at the margin. */
    private val clock: () -> Instant = Instant::now,
) {

    private val refreshMutex = Mutex()

    suspend fun current(): NeonSession? = NeonSession.decodeOrNull(store.state().session)

    suspend fun <T> withAccessToken(block: suspend (String) -> T): T {
        var session = current() ?: throw SessionExpiredException()
        val expiresAt = jwtExpiresAtOrNull(session.accessToken)
        if (expiresAt == null || expiresAt.isBefore(clock().plus(EXPIRY_MARGIN))) {
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

    private suspend fun refreshFrom(stale: NeonSession): NeonSession = refreshMutex.withLock {
        val stored = current() ?: throw SessionExpiredException()
        // A caller that waited on the lock finds the JWT someone faster already minted.
        if (stored.accessToken != stale.accessToken) return stored
        val answer = try {
            authClient.mintJwt(stored.sessionCookie)
        } catch (e: NeonAuthException) {
            if (e.status in 400..499) throw SessionExpiredException() else throw e
        }
        val renewed = stored.copy(accessToken = answer.token)
        store.setSession(renewed.encode())
        renewed
    }

    private companion object {
        const val UNAUTHORIZED = 401
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(30)
    }
}
