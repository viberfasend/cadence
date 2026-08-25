package de.andi1984.cadence.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * The three PostgREST requests the sync round is made of, against the Neon Data API (ADR 0005).
 *
 * A thin conventions layer, not a client library: URLs and filter strings are spelled here once,
 * the caller supplies the Bearer token per call (token lifetime is the session manager's problem,
 * not this class's), and bodies travel as pre-encoded JSON — [SyncJson]'s `encodeDefaults` is
 * load-bearing for upserts, see [RemoteRecords].
 */
internal class PostgrestHttp(
    private val http: HttpClient,
    private val baseUrl: String,
) {

    /**
     * `GET /{table}?server_updated_at=gte.{cursor}&order=server_updated_at.asc&limit={n}` —
     * one pull page, oldest first, as raw JSON for the caller to decode. A null cursor asks from
     * the beginning; the caller has already widened the cursor by the pull overlap.
     */
    suspend fun selectSince(table: String, cursorIso: String?, limit: Int, token: String): String {
        val response = http.get("$baseUrl/$table") {
            bearer(token)
            parameter("select", "*")
            cursorIso?.let { parameter(COLUMN_SERVER_UPDATED_AT, "gte.$it") }
            parameter("order", "$COLUMN_SERVER_UPDATED_AT.asc")
            parameter("limit", limit.toString())
        }
        return response.bodyOrThrow()
    }

    /**
     * `POST /{table}?on_conflict=user_id,id` with `Prefer: resolution=merge-duplicates` — the
     * upsert the push rides on. `return=minimal` because the answer is not read: the stale-write
     * trigger decides row by row, silently, and the client finds out via the next pull.
     */
    suspend fun upsert(table: String, jsonBody: String, token: String) {
        val response = http.post("$baseUrl/$table") {
            bearer(token)
            parameter("on_conflict", CONFLICT_KEY)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        response.bodyOrThrow()
    }

    /**
     * `DELETE /{table}?deleted_at=not.is.null&server_updated_at=lt.{cutoff}` — the server half of
     * the tombstone sweep, run by the client since ADR 0005 (Neon's compute scales to zero, so a
     * pg_cron job only fires while something else keeps it awake). RLS scopes it to this
     * account's rows; the tombstone-and-horizon filter is this statement.
     */
    suspend fun deleteTombstonesBefore(table: String, cutoffIso: String, token: String) {
        val response = http.delete("$baseUrl/$table") {
            bearer(token)
            parameter("deleted_at", "not.is.null")
            parameter(COLUMN_SERVER_UPDATED_AT, "lt.$cutoffIso")
            header("Prefer", "return=minimal")
        }
        response.bodyOrThrow()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun HttpResponse.bodyOrThrow(): String {
        val body = bodyAsText()
        if (status.value !in 200..299) throw SyncHttpException(status.value, body.take(BODY_LIMIT))
        return body
    }

    private companion object {
        const val COLUMN_SERVER_UPDATED_AT = "server_updated_at"

        /** The primary key every table carries, and therefore what an upsert conflicts on. */
        const val CONFLICT_KEY = "user_id,id"

        const val BODY_LIMIT = 500
    }
}

/** A Data API answer that is not a success. 401 is the one status callers branch on — it is what
 *  sends the session manager after a token refresh. */
internal class SyncHttpException(val status: Int, val body: String) :
    Exception("Data API returned $status: $body")
