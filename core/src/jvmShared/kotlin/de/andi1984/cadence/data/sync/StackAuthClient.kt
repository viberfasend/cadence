package de.andi1984.cadence.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Stack Auth REST calls Neon Auth is made of — hand-rolled, because no Kotlin SDK exists
 * (ADR 0005). Three requests: password sign-in, session refresh, sign-out. Every call carries the
 * client-access headers; the publishable key identifies the project and grants nothing (RLS on
 * the Neon side is what protects rows).
 *
 * Errors: a non-2xx answer throws [StackAuthException] with the status and the response body
 * (Stack Auth's errors are JSON with a `code`); network trouble propagates as [java.io.IOException]
 * from the engine, which callers map to "offline".
 */
internal class StackAuthClient(
    private val http: HttpClient,
    private val apiUrl: String = NeonConfig.stackApiUrl,
    private val projectId: String = NeonConfig.stackProjectId,
    private val publishableClientKey: String = NeonConfig.stackPublishableClientKey,
) {

    @Serializable
    data class Tokens(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
    )

    /** Signs in with email and password and returns the session's token pair. */
    suspend fun signIn(email: String, password: String): Tokens {
        val response = http.post("$apiUrl/api/v1/auth/password/sign-in") {
            stackHeaders()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("email", email)
                put("password", password)
            }.toString())
        }
        return response.decodeOrThrow()
    }

    /**
     * Trades the refresh token for a fresh access token. Stack Auth answers with the new access
     * token alone unless it chose to rotate the refresh token too, so the caller keeps its stored
     * refresh token when the answer carries none.
     */
    suspend fun refresh(refreshToken: String): Tokens {
        val response = http.post("$apiUrl/api/v1/auth/sessions/current/refresh") {
            stackHeaders()
            header(HEADER_REFRESH_TOKEN, refreshToken)
        }
        return response.decodeOrThrow()
    }

    /** Revokes the session server-side. Callers treat this as best-effort: the local sign-out
     *  must not hinge on the network being up. */
    suspend fun signOut(refreshToken: String) {
        val response = http.delete("$apiUrl/api/v1/auth/sessions/current") {
            stackHeaders()
            header(HEADER_REFRESH_TOKEN, refreshToken)
        }
        if (response.status.value !in 200..299) {
            throw StackAuthException(response.status.value, response.bodyAsText().take(BODY_LIMIT))
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.stackHeaders() {
        header("X-Stack-Access-Type", "client")
        header("X-Stack-Project-Id", projectId)
        header("X-Stack-Publishable-Client-Key", publishableClientKey)
    }

    private suspend fun HttpResponse.decodeOrThrow(): Tokens {
        val body = bodyAsText()
        if (status.value !in 200..299) throw StackAuthException(status.value, body.take(BODY_LIMIT))
        return runCatching { TokensJson.decodeFromString(Tokens.serializer(), body) }
            .getOrElse { throw StackAuthException(status.value, "unreadable token response") }
    }

    private companion object {
        const val HEADER_REFRESH_TOKEN = "X-Stack-Refresh-Token"
        const val BODY_LIMIT = 500
        val TokensJson = Json { ignoreUnknownKeys = true }
    }
}

/** A Stack Auth answer that is not a success — the status decides how callers read it: 400/401
 *  on sign-in is "wrong credentials", 4xx on refresh is "session expired". */
internal class StackAuthException(val status: Int, val body: String) :
    Exception("Stack Auth returned $status: $body")
