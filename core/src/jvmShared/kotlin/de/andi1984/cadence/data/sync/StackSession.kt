package de.andi1984.cadence.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Base64

/**
 * The signed-in state this device keeps, serialised into `syncStateRow.session`.
 *
 * Our own shape, not Stack Auth's: the access token is a short-lived JWT the [CadenceSyncEngine]
 * refreshes as needed, the refresh token is what buys the next one, and the email is only ever
 * shown in Settings. A stored value that does not decode — including the supabase-kt
 * `UserSession` every install carried before ADR 0005 — counts as signed out, which after the
 * migration is exactly the forced re-sign-in the cutover wants: fresh session, cleared cursors,
 * full push.
 */
@Serializable
internal data class StackSession(
    val accessToken: String,
    val refreshToken: String,
    val email: String? = null,
) {
    fun encode(): String = SessionJson.encodeToString(serializer(), this)

    companion object {
        private val SessionJson = Json { ignoreUnknownKeys = true }

        fun decodeOrNull(stored: String?): StackSession? = stored?.let {
            runCatching { SessionJson.decodeFromString(serializer(), it) }.getOrNull()
                // The old library's session also decodes only by accident, never usefully; a
                // session without both tokens is no session.
                ?.takeIf { s -> s.accessToken.isNotBlank() && s.refreshToken.isNotBlank() }
        }
    }
}

/**
 * The `exp` claim of a JWT, or null when the token does not yield one.
 *
 * A payload read without verifying the signature, deliberately: the client is not the party the
 * signature protects (the server verifies every request anyway), and the only decision hanging on
 * this value is "refresh now or try first". Null therefore means "assume expired and refresh" —
 * the safe reading of a token we cannot even parse.
 */
internal fun jwtExpiresAtOrNull(token: String): Instant? {
    val payload = token.split('.').getOrNull(1) ?: return null
    val decoded = runCatching {
        Base64.getUrlDecoder().decode(payload).toString(Charsets.UTF_8)
    }.getOrNull() ?: return null
    val exp = runCatching {
        Json.parseToJsonElement(decoded).jsonObject["exp"]?.jsonPrimitive?.longOrNull
    }.getOrNull() ?: return null
    return Instant.ofEpochSecond(exp)
}
