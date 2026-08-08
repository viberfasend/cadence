package de.andi1984.cadence.domain.id

import java.security.SecureRandom
import java.time.LocalDate
import java.util.UUID

/**
 * Every id in the app — task, project — is one of these, stored as `TEXT`.
 *
 * `Long` autoincrement cannot survive a merge: two devices editing offline both mint id 7, and
 * the union of their files silently collapses two different tasks into one
 * (`docs/adr/0001-desktop-app-and-multi-device-sync.md`, decision 4). Version 7 rather than 4
 * because it is time-ordered — insertion stays local in the SQLite index, and "newest first"
 * needs no extra column.
 */
object UuidV7 {

    private val random = SecureRandom()

    /** A fresh id, ordered by the current time. */
    fun random(): String = randomAt(System.currentTimeMillis())

    /**
     * A fresh id ordered by [unixMillis] — the seam a test freezes to make output deterministic,
     * the way [de.andi1984.cadence.domain.recurrence.RecurrenceEngine] takes `today` rather than
     * calling [LocalDate.now] itself.
     */
    fun randomAt(unixMillis: Long): String {
        val randA = random.nextInt(0x1000) // 12 random bits
        val randB = random.nextLong() and 0x3FFFFFFFFFFFFFFFL // 62 random bits

        // 48 bits unix_ts_ms | 4 bits version (0111) | 12 bits rand_a
        val mostSigBits = (unixMillis shl 16) or (0x7L shl 12) or randA.toLong()
        // 2 bits variant (10) | 62 bits rand_b
        val leastSigBits = (0b10L shl 62) or randB

        return UUID(mostSigBits, leastSigBits).toString()
    }

    /**
     * The id of the occurrence completing [spawnedFromId] inserts on [occurrenceDate] —
     * deterministic, not random, so two devices completing the same occurrence offline mint the
     * same successor id and a merge collapses them instead of duplicating the task (ADR decision
     * 4). The same function derives the id a recurring parent's subtasks carry over to.
     */
    fun successorId(spawnedFromId: String, occurrenceDate: LocalDate?, discriminant: String = ""): String =
        UUID.nameUUIDFromBytes("$spawnedFromId|$occurrenceDate|$discriminant".toByteArray()).toString()
}
