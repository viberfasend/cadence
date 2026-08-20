package de.andi1984.cadence

import de.andi1984.cadence.domain.id.UuidV7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** Every id in the app, and the one deterministic exception recurrence needs (ADR 0001, decision 4). */
class UuidV7Test {

    @Test
    fun `a fresh id carries the version 7 and variant 2 bits the format requires`() {
        val id = UUID.fromString(UuidV7.randomAt(1_723_000_000_000L))

        assertEquals(7, id.version())
        assertEquals(2, id.variant())
    }

    @Test
    fun `ids ordered by an earlier timestamp sort before ones ordered by a later one`() {
        val earlier = UuidV7.randomAt(1_000_000_000_000L)
        val later = UuidV7.randomAt(1_000_000_010_000L)

        assertTrue(earlier < later)
    }

    @Test
    fun `two ids minted at the same millisecond are still distinct`() {
        val a = UuidV7.randomAt(1_000_000_000_000L)
        val b = UuidV7.randomAt(1_000_000_000_000L)

        assertNotEquals(a, b)
    }

    @Test
    fun `successorId is deterministic for the same occurrence and discriminant`() {
        val date = LocalDate.of(2026, 8, 20)

        val first = UuidV7.successorId("parent-1", date, discriminant = "step-1")
        val second = UuidV7.successorId("parent-1", date, discriminant = "step-1")

        assertEquals(first, second)
    }

    @Test
    fun `successorId differs when the occurrence date differs`() {
        val a = UuidV7.successorId("parent-1", LocalDate.of(2026, 8, 20))
        val b = UuidV7.successorId("parent-1", LocalDate.of(2026, 8, 21))

        assertNotEquals(a, b)
    }

    @Test
    fun `successorId differs when the discriminant differs, so siblings do not collide`() {
        val a = UuidV7.successorId("parent-1", null, discriminant = "step-1")
        val b = UuidV7.successorId("parent-1", null, discriminant = "step-2")

        assertNotEquals(a, b)
    }

    @Test
    fun `successorId with a null occurrence date is still deterministic`() {
        val a = UuidV7.successorId("parent-1", null)
        val b = UuidV7.successorId("parent-1", null)

        assertEquals(a, b)
    }
}
