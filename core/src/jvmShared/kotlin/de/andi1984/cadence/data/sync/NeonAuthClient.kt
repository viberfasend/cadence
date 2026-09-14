package de.andi1984.cadence.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The three Neon Auth (Better Auth) REST calls the app is made of — hand-rolled, because no
 * Kotlin SDK exists (ADR 0005). Password sign-in yields the long-lived **session cookie**;
 * `GET /token` trades it for the short-lived **JWT** the Data API wants as Bearer; sign-out
 * revokes the session. No API key or project id travels with any of it: the auth URL names the
 * project, and RLS on the Neon side is what protects rows.
 *
 * **The session is a cookie, not a bearer token** — verified against the live endpoint: the
 * sign-in *body* carries a raw session token, but `/token` only accepts the *signed* form
 * (`token.signature`), which Better Auth hands out solely in the `Set-Cookie` header (the
 * bearer plugin is not enabled on Neon's deployment). So sign-in captures that cookie pair
 * verbatim and every session-authenticated call replays it in a `Cookie` header. The
 * [HttpClient] here has no cookie storage installed on purpose — the cookie lives in
 * `syncStateRow.session` with the cursors, not in some jar tied to the client's lifetime.
 *
 * Errors: a non-2xx answer throws [NeonAuthException] with the status and the response body
 * (Better Auth's errors are JSON with a `code`); network trouble propagates as
 * [java.io.IOException] from the engine, which callers map to "offline".
 */
internal class NeonAuthClient(
    private val http: HttpClient,
    private val authUrl: String,
) {

    /** A signed-in session: the cookie pair (`name=value`, ready to replay) and who it is. */
    data class Session(val sessionCookie: String, val email: String?)

    /** `GET /token`'s answer: one short-lived JWT for the Data API. */
    @Serializable
    data class Jwt(@SerialName("token") val token: String)

    @Serializable
    private data class SignInBody(val user: User? = null) {
        @Serializable
        data class User(val email: String? = null)
    }

    suspend fun signIn(email: String, password: String): Session {
        val response = http.post("$authUrl/sign-in/email") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("email", email)
                put("password", password)
            }.toString())
        }
        val body = response.decodeOrThrow(SignInBody.serializer())
        val cookie = response.headers.getAll(HttpHeaders.SetCookie)
            ?.firstOrNull { it.substringBefore('=').endsWith(SESSION_COOKIE_SUFFIX) }
            ?.substringBefore(';')
            ?.trim()
            ?: throw NeonAuthException(response.status.value, "sign-in answered without a session cookie")
        return Session(cookie, body.user?.email)
    }

    /** Trades the session cookie for a fresh Data API JWT. A 401/403 here means the session
     *  itself is dead — expired or revoked — not that anything about the JWT went wrong. */
    suspend fun mintJwt(sessionCookie: String): Jwt {
        val response = http.get("$authUrl/token") {
            header(HttpHeaders.Cookie, sessionCookie)
        }
        return response.decodeOrThrow(Jwt.serializer())
    }

    /** Revokes the session server-side. Callers treat this as best-effort: the local sign-out
     *  must not hinge on the network being up. */
    suspend fun signOut(sessionCookie: String) {
        val response = http.post("$authUrl/sign-out") {
            header(HttpHeaders.Cookie, sessionCookie)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        if (response.status.value !in 200..299) {
            throw NeonAuthException(response.status.value, response.bodyAsText().take(BODY_LIMIT))
        }
    }

    private suspend fun <T> HttpResponse.decodeOrThrow(serializer: KSerializer<T>): T {
        val body = bodyAsText()
        if (status.value !in 200..299) throw NeonAuthException(status.value, body.take(BODY_LIMIT))
        return runCatching { AuthJson.decodeFromString(serializer, body) }
            .getOrElse { throw NeonAuthException(status.value, "unreadable auth response") }
    }

    private companion object {
        /** `__Secure-neon-auth.session_token` over HTTPS; matched by suffix so a deployment
         *  without the `__Secure-` prefix still signs in. */
        const val SESSION_COOKIE_SUFFIX = "session_token"

        const val BODY_LIMIT = 500
        val AuthJson = Json { ignoreUnknownKeys = true }
    }
}

/** A Neon Auth answer that is not a success — the status decides how callers read it: 400/401
 *  on sign-in is "wrong credentials", 4xx on `GET /token` is "session expired". */
internal class NeonAuthException(val status: Int, val body: String) :
    Exception("Neon Auth returned $status: $body")
