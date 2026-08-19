package de.andi1984.cadence.ui

import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.settings.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The one ordering rule the whole app is built on — importance first, due date breaks ties —
 * plus the two explicit opt-outs the sort chips offer.
 *
 * Every case builds a list in deliberately wrong order and asserts the ids that come back, so a
 * comparator clause that silently stops contributing shows up as a moved id rather than as
 * nothing at all.
 */
class TaskSortingTest {

    private val monday = LocalDate.of(2026, 8, 17)

    private fun task(
        id: String,
        priority: Priority = Priority.DEFAULT,
        due: LocalDate? = null,
        dueTime: LocalTime? = null,
        sortOrder: Int = 0,
        done: Boolean = false,
    ) = Task(
        id = id,
        title = id,
        priority = priority,
        dueDate = due,
        dueTime = dueTime,
        sortOrder = sortOrder,
        completedAt = if (done) Instant.EPOCH else null,
    )

    private fun List<Task>.ids() = map { it.id }

    @Test
    fun `importance comes before the due date`() {
        val tasks = listOf(
            task("p4-today", Priority.P4, due = monday),
            task("p1-next-week", Priority.P1, due = monday.plusWeeks(1)),
            task("p2-today", Priority.P2, due = monday),
        )

        assertEquals(
            listOf("p1-next-week", "p2-today", "p4-today"),
            tasks.sortedFor(SortMode.IMPORTANCE).ids(),
        )
    }

    @Test
    fun `the due date breaks a tie between equal priorities`() {
        val tasks = listOf(
            task("later", Priority.P2, due = monday.plusDays(3)),
            task("sooner", Priority.P2, due = monday),
            task("undated", Priority.P2),
        )

        // An undated task sorts behind every dated one rather than in front of them: the null
        // stands in as the far future, not as "no date, therefore first".
        assertEquals(
            listOf("sooner", "later", "undated"),
            tasks.sortedFor(SortMode.IMPORTANCE).ids(),
        )
    }

    @Test
    fun `the due time breaks a tie between two tasks on the same day`() {
        val tasks = listOf(
            task("anytime", Priority.P2, due = monday),
            task("evening", Priority.P2, due = monday, dueTime = LocalTime.of(18, 0)),
            task("morning", Priority.P2, due = monday, dueTime = LocalTime.of(9, 0)),
        )

        // No time at all reads as end of day, so it lands behind both timed tasks.
        assertEquals(
            listOf("morning", "evening", "anytime"),
            tasks.sortedFor(SortMode.IMPORTANCE).ids(),
        )
    }

    @Test
    fun `sortOrder is the last word when everything else matches`() {
        val tasks = listOf(
            task("third", Priority.P2, due = monday, sortOrder = 3),
            task("first", Priority.P2, due = monday, sortOrder = 1),
            task("second", Priority.P2, due = monday, sortOrder = 2),
        )

        assertEquals(
            listOf("first", "second", "third"),
            tasks.sortedFor(SortMode.IMPORTANCE).ids(),
        )
    }

    @Test
    fun `a finished task sinks below every open one, in every mode`() {
        val tasks = listOf(
            task("done-p1", Priority.P1, due = monday, done = true),
            task("open-p4", Priority.P4, due = monday.plusYears(1)),
        )

        SortMode.entries.forEach { mode ->
            assertEquals(
                "$mode should keep the finished task last",
                listOf("open-p4", "done-p1"),
                tasks.sortedFor(mode).ids(),
            )
        }
    }

    @Test
    fun `DATE puts the date first and lets priority break the tie`() {
        val tasks = listOf(
            task("p1-later", Priority.P1, due = monday.plusDays(1)),
            task("p4-today", Priority.P4, due = monday),
            task("p1-today", Priority.P1, due = monday),
        )

        assertEquals(
            listOf("p1-today", "p4-today", "p1-later"),
            tasks.sortedFor(SortMode.DATE).ids(),
        )
    }

    @Test
    fun `MANUAL ignores both priority and date, and falls back to the id`() {
        val tasks = listOf(
            task("b", Priority.P1, due = monday, sortOrder = 1),
            task("a", Priority.P4, due = monday.plusYears(1), sortOrder = 1),
            task("c", Priority.P2, due = monday, sortOrder = 0),
        )

        assertEquals(listOf("c", "a", "b"), tasks.sortedFor(SortMode.MANUAL).ids())
    }

    @Test
    fun `sorting an empty list is not an error`() {
        SortMode.entries.forEach { mode ->
            assertEquals(emptyList<String>(), emptyList<Task>().sortedFor(mode).ids())
        }
    }
}
