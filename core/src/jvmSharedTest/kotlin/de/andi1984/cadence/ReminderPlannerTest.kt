package de.andi1984.cadence

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.ReminderInstant
import de.andi1984.cadence.domain.reminder.ReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderPlannerTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 8, 12)

    private fun task(
        id: String = "a",
        due: LocalDate? = today,
        dueTime: LocalTime? = null,
        reminderTime: LocalTime? = null,
        done: Boolean = false,
    ) = Task(
        id = id,
        title = id,
        dueDate = due,
        dueTime = dueTime,
        reminderTime = reminderTime,
        completedAt = if (done) Instant.EPOCH else null,
    )

    @Test
    fun `one alarm per configured lead minutes, counting back from due time`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(dueTime = LocalTime.of(18, 0))),
            leadMinutes = listOf(20, 10, 5),
            zone = zone,
        )

        val expected = setOf(
            ReminderInstant("a", 20, LocalTime.of(17, 40).atDate(today).toInstant(ZoneOffset.UTC)),
            ReminderInstant("a", 10, LocalTime.of(17, 50).atDate(today).toInstant(ZoneOffset.UTC)),
            ReminderInstant("a", 5, LocalTime.of(17, 55).atDate(today).toInstant(ZoneOffset.UTC)),
        )
        assertEquals(expected, plan.toSet())
    }

    @Test
    fun `an instant already in the past is still reported, not filtered here`() {
        // Filtering by "now" is the platform scheduler's job, and the two disagree about what a
        // past instant means: Android drops it (AlarmManager cannot fire retroactively), the
        // desktop fires it on the next poll. The planner stays neutral between the two.
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(due = today.minusDays(1), dueTime = LocalTime.of(9, 0))),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertEquals(1, plan.size)
        assertEquals(LocalTime.of(8, 50).atDate(today.minusDays(1)).toInstant(ZoneOffset.UTC), plan.single().triggerAt)
    }

    @Test
    fun `no due time means no lead-based alarms, lead list or not`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(dueTime = null)),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `reminderTime fires once at its own moment, as lead zero, independent of the lead list`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(reminderTime = LocalTime.of(9, 0))),
            leadMinutes = emptyList(),
            zone = zone,
        )

        assertEquals(
            listOf(ReminderInstant("a", 0, LocalTime.of(9, 0).atDate(today).toInstant(ZoneOffset.UTC))),
            plan,
        )
    }

    @Test
    fun `dueTime and reminderTime both firing produce independent alarms`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(dueTime = LocalTime.of(18, 0), reminderTime = LocalTime.of(9, 0))),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertEquals(2, plan.size)
        assertEquals(setOf(0, 10), plan.map { it.leadMinutes }.toSet())
    }

    @Test
    fun `no due date means nothing to schedule at all`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(due = null, dueTime = LocalTime.of(18, 0), reminderTime = LocalTime.of(9, 0))),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a finished task never schedules`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(dueTime = LocalTime.of(18, 0), done = true)),
            leadMinutes = listOf(10),
            zone = zone,
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a repeated lead value is not scheduled twice`() {
        val plan = ReminderPlanner.plan(
            tasks = listOf(task(dueTime = LocalTime.of(18, 0))),
            leadMinutes = listOf(10, 10),
            zone = zone,
        )

        assertEquals(1, plan.size)
    }
}
