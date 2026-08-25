package de.andi1984.cadence.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Base64

class StackSessionTest {

    @Test
    fun `a session round-trips`() {
        val session = StackSession("access", "refresh", "me@example.org")
        assertEquals(session, StackSession.decodeOrNull(session.encode()))
    }

    @Test
    fun `absent and unreadable sessions read as signed out`() {
        assertNull(StackSession.decodeOrNull(null))
        assertNull(StackSession.decodeOrNull("not json"))
        assertNull(StackSession.decodeOrNull("""{"accessToken":"","refreshToken":""}"""))
    }

    /** The exact upgrade case ADR 0005 documents: a stored supabase-kt `UserSession` must decode
     *  to "signed out", never crash — its keys are different, so the fields stay blank. */
    @Test
    fun `a stored supabase session reads as signed out`() {
        val supabaseShape = """{"access_token":"a","refresh_token":"r","token_type":"bearer",
            "user":{"id":"u","email":"me@example.org"}}"""
        assertNull(StackSession.decodeOrNull(supabaseShape))
    }

    @Test
    fun `the exp claim is read from a JWT payload`() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val token = encoder.encodeToString("""{"alg":"none"}""".toByteArray()) +
            "." + encoder.encodeToString("""{"exp":1700000000}""".toByteArray()) + ".sig"
        assertEquals(Instant.ofEpochSecond(1_700_000_000), jwtExpiresAtOrNull(token))
    }

    @Test
    fun `a token that does not parse reads as expired`() {
        assertNull(jwtExpiresAtOrNull("not-a-jwt"))
        assertNull(jwtExpiresAtOrNull("a.b.c"))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val noExp = "h." + encoder.encodeToString("""{"sub":"x"}""".toByteArray()) + ".s"
        assertNull(jwtExpiresAtOrNull(noExp))
    }
}
