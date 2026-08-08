package de.andi1984.cadence

import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.withoutSupersededOccurrences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * A recurring task is a chain of rows, one per occurrence. These are the rules for reading that
 * chain as a single task in the lists that are not scoped to a day.
 */
class RecurrenceChainTest {

    private val daily = RecurrenceRule(interval = 1, unit = RecurrenceUnit.DAY)

    /** Occurrence [id] of a daily task, replacing [replaces], done unless [done] says otherwise. */
    private fun occurrence(id: String, day: Int, replaces: String? = null, done: Boolean = true) = Task(
        id = id,
        title = "Rat poison",
        dueDate = LocalDate.of(2026, 8, day),
        completedAt = if (done) Instant.parse("2026-08-07T07:26:00Z") else null,
        recurrence = daily,
        spawnedFromId = replaces,
    )

    @Test
    fun `a week of a daily task reads as the one occurrence still open`() {
        val chain = listOf(
            occurrence("1", day = 1),
            occurrence("2", day = 2, replaces = "1"),
            occurrence("3", day = 3, replaces = "2"),
            occurrence("4", day = 4, replaces = "3", done = false),
        )

        assertEquals(listOf("4"), chain.withoutSupersededOccurrences().map { it.id })
    }

    @Test
    fun `the last occurrence stays even once it is finished`() {
        val chain = listOf(occurrence("1", day = 1), occurrence("2", day = 2, replaces = "1"))

        // Nothing replaced occurrence 2, so finishing the series leaves it visible rather than
        // making the task disappear without trace.
        assertEquals(listOf("2"), chain.withoutSupersededOccurrences().map { it.id })
    }

    @Test
    fun `an ordinary completed task is left alone`() {
        val done = Task(id = "9", title = "Call the vet", completedAt = Instant.EPOCH)
        val open = Task(id = "10", title = "Buy traps")

        val kept = listOf(done, open).withoutSupersededOccurrences()
        assertEquals(listOf("9", "10"), kept.map { it.id })
    }

    @Test
    fun `a replaced occurrence that is somehow still open is kept`() {
        // Defensive: an open row is work, whatever the links say — only history is dropped.
        val chain = listOf(
            occurrence("1", day = 1, done = false),
            occurrence("2", day = 2, replaces = "1", done = false),
        )

        assertEquals(listOf("1", "2"), chain.withoutSupersededOccurrences().map { it.id })
    }
}
