package de.andi1984.cadence

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.ReminderCommand
import de.andi1984.cadence.domain.reminder.ReminderKey
import de.andi1984.cadence.domain.reminder.ReminderReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The diff between armed and planned — the one place both shells' schedulers get "what changes"
 * from. The rules here used to live twice, in AlarmManager's vocabulary on Android (with no test
 * at all) and in three `retainAll`s on the desktop; the desktop tests that were really about the
 * diff rather than the poll moved here with them.
 *
 * Times are UTC and `now` is fixed, so "in the future", "just passed" and "long past" are exact.
 */
class ReminderReconcilerTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 8, 12)
    private val now: Instant = today.atTime(LocalTime.of(12, 0)).toInstant(ZoneOffset.UTC)
    private val grace: Duration = Duration.ofMinutes(10)

    private fun at(time: LocalTime, date: LocalDate = today): Instant =
        date.atTime(time).toInstant(ZoneOffset.UTC)

    private fun task(
        id: String = "a",
        title: String = id,
        due: LocalDate? = today,
        dueTime: LocalTime? = null,
        reminderTime: LocalTime? = null,
        done: Boolean = false,
    ) = Task(
        id = id,
        title = title,
        dueDate = due,
        dueTime = dueTime,
        reminderTime = reminderTime,
        completedAt = if (done) Instant.EPOCH else null,
    )

    private fun reconcile(
        armed: Map<ReminderKey, Instant>,
        tasks: List<Task>,
        leadMinutes: List<Int> = emptyList(),
        enabled: Boolean = true,
        grace: Duration = this.grace,
    ) = ReminderReconciler.reconcile(armed, tasks, leadMinutes, enabled, now, grace, zone)

    // ── Arming ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a planned alarm nothing has armed is armed, with the task it belongs to`() {
        val task = task(title = "Water the plants", reminderTime = LocalTime.of(18, 0))

        val commands = reconcile(armed = emptyMap(), tasks = listOf(task))

        assertEquals(
            listOf(ReminderCommand.Arm(ReminderKey("a", 0), at(LocalTime.of(18, 0)), task)),
            commands,
        )
    }

    @Test
    fun `one alarm per configured lead, each armed on its own`() {
        val task = task(dueTime = LocalTime.of(18, 0))

        val commands = reconcile(armed = emptyMap(), tasks = listOf(task), leadMinutes = listOf(20, 10))

        assertEquals(
            setOf(
                ReminderCommand.Arm(ReminderKey("a", 20), at(LocalTime.of(17, 40)), task),
                ReminderCommand.Arm(ReminderKey("a", 10), at(LocalTime.of(17, 50)), task),
            ),
            commands.toSet(),
        )
    }

    @Test
    fun `a planned instant already in the past is still armed — what that means is the platform's`() {
        // The desktop fires it on the next poll (the reminder elapsed while the app was closed);
        // Android drops it, since AlarmManager cannot fire retroactively. Neither is decided here.
        val task = task(due = today.minusDays(1), reminderTime = LocalTime.NOON)

        val commands = reconcile(armed = emptyMap(), tasks = listOf(task))

        assertEquals(
            listOf(ReminderCommand.Arm(ReminderKey("a", 0), at(LocalTime.NOON, today.minusDays(1)), task)),
            commands,
        )
    }

    @Test
    fun `an alarm moved into the future is re-armed at its new instant`() {
        // Snoozed: the same alarm, now due tomorrow. An adapter that marked the old instant as
        // fired has to be told, or the reminder is suppressed forever by a decision made about a
        // time that no longer applies.
        val key = ReminderKey("a", 0)
        val task = task(due = today.plusDays(1), reminderTime = LocalTime.NOON)

        val commands = reconcile(
            armed = mapOf(key to at(LocalTime.NOON, today.minusDays(1))),
            tasks = listOf(task),
        )

        assertEquals(listOf(ReminderCommand.Arm(key, at(LocalTime.NOON, today.plusDays(1)), task)), commands)
    }

    @Test
    fun `the same input twice changes nothing`() {
        // Every task emission reconciles, so this is the ordinary case, not a corner one — and it
        // is what keeps an alarm that already fired from being armed, and fired, a second time.
        val task = task(due = today.minusDays(1), reminderTime = LocalTime.NOON)
        val first = reconcile(armed = emptyMap(), tasks = listOf(task))
        val armed = first.filterIsInstance<ReminderCommand.Arm>().associate { it.key to it.at }

        val second = reconcile(armed = armed, tasks = listOf(task))

        assertEquals(emptyList<ReminderCommand>(), second)
    }

    @Test
    fun `a title edit alone issues no command`() {
        val key = ReminderKey("a", 0)
        val renamed = task(title = "New name", reminderTime = LocalTime.of(18, 0))

        val commands = reconcile(armed = mapOf(key to at(LocalTime.of(18, 0))), tasks = listOf(renamed))

        assertEquals(emptyList<ReminderCommand>(), commands)
    }

    // ── Cancelling ───────────────────────────────────────────────────────────────────

    @Test
    fun `a lead dropped from the settings list is cancelled`() {
        val task = task(dueTime = LocalTime.of(18, 0))
        val armed = mapOf(
            ReminderKey("a", 20) to at(LocalTime.of(17, 40)),
            ReminderKey("a", 10) to at(LocalTime.of(17, 50)),
        )

        val commands = reconcile(armed = armed, tasks = listOf(task), leadMinutes = listOf(10))

        assertEquals(listOf(ReminderCommand.Cancel(ReminderKey("a", 20))), commands)
    }

    @Test
    fun `a task no longer listed has every lead cancelled`() {
        val armed = mapOf(
            ReminderKey("a", 20) to at(LocalTime.of(17, 40)),
            ReminderKey("a", 10) to at(LocalTime.of(17, 50)),
            ReminderKey("a", 0) to at(LocalTime.of(18, 0)),
        )

        val commands = reconcile(armed = armed, tasks = emptyList(), leadMinutes = listOf(20, 10))

        assertEquals(armed.keys.map { ReminderCommand.Cancel(it) }.toSet(), commands.toSet())
    }

    @Test
    fun `a finished task has its alarm cancelled`() {
        val key = ReminderKey("a", 0)
        val done = task(reminderTime = LocalTime.of(18, 0), done = true)

        val commands = reconcile(armed = mapOf(key to at(LocalTime.of(18, 0))), tasks = listOf(done))

        assertEquals(listOf(ReminderCommand.Cancel(key)), commands)
    }

    @Test
    fun `an alarm still in the future that nothing plans any more is cancelled, grace or not`() {
        // It has not fired, so there is no delivery in flight to protect — and left alone it
        // would fire for a reminder that was cleared.
        val key = ReminderKey("a", 0)

        val commands = reconcile(armed = mapOf(key to at(LocalTime.of(18, 0))), tasks = emptyList())

        assertEquals(listOf(ReminderCommand.Cancel(key)), commands)
    }

    // ── Reminders switched off ───────────────────────────────────────────────────────

    @Test
    fun `reminders off cancels everything armed and strips nothing off the tasks`() {
        val task = task(dueTime = LocalTime.of(18, 0), reminderTime = LocalTime.of(9, 0))
        val armed = mapOf(
            ReminderKey("a", 10) to at(LocalTime.of(17, 50)),
            ReminderKey("a", 0) to at(LocalTime.of(9, 0), today.minusDays(3)),
        )

        val commands = reconcile(armed = armed, tasks = listOf(task), leadMinutes = listOf(10), enabled = false)

        // An empty plan, not an empty task list: both alarms — one ahead, one long past — go.
        assertEquals(armed.keys.map { ReminderCommand.Cancel(it) }.toSet(), commands.toSet())
        assertEquals(LocalTime.of(18, 0), task.dueTime)
        assertEquals(LocalTime.of(9, 0), task.reminderTime)
    }

    @Test
    fun `reminders off with nothing armed does nothing`() {
        val task = task(dueTime = LocalTime.of(18, 0))

        val commands = reconcile(armed = emptyMap(), tasks = listOf(task), leadMinutes = listOf(10), enabled = false)

        assertEquals(emptyList<ReminderCommand>(), commands)
    }

    // ── The grace window (Android's GRACE_MILLIS rule) ───────────────────────────────

    @Test
    fun `an unplanned alarm whose trigger passed less than the grace ago is kept, not cancelled`() {
        // Inside the window a delivered alarm cannot be told from one the inexact fallback is
        // still carrying, and cancelling the latter took the notification away unfired.
        val key = ReminderKey("a", 0)

        val commands = reconcile(armed = mapOf(key to now.minus(Duration.ofMinutes(3))), tasks = emptyList())

        assertEquals(listOf(ReminderCommand.Keep(key)), commands)
    }

    @Test
    fun `an unplanned alarm whose trigger passed longer than the grace ago is cancelled`() {
        val key = ReminderKey("a", 0)

        val commands = reconcile(armed = mapOf(key to now.minus(Duration.ofMinutes(10))), tasks = emptyList())

        assertEquals(listOf(ReminderCommand.Cancel(key)), commands)
    }

    @Test
    fun `a zero grace cancels an unplanned alarm the moment its trigger has passed`() {
        // The desktop's setting: a poll has no delivery latency to wait out.
        val key = ReminderKey("a", 0)

        val commands = reconcile(
            armed = mapOf(key to now.minusSeconds(1)),
            tasks = emptyList(),
            grace = Duration.ZERO,
        )

        assertEquals(listOf(ReminderCommand.Cancel(key)), commands)
    }

    @Test
    fun `retire is the same rule an adapter applies to an Arm it cannot honour`() {
        // Android drops an Arm naming a past instant and settles what it had armed under that key
        // with `retire`: a trigger just passed is kept, one long past or still ahead is cancelled.
        val key = ReminderKey("a", 0)

        assertEquals(ReminderCommand.Keep(key), ReminderReconciler.retire(key, now.minusSeconds(30), now, grace))
        assertEquals(ReminderCommand.Cancel(key), ReminderReconciler.retire(key, now.minus(grace), now, grace))
        assertEquals(ReminderCommand.Cancel(key), ReminderReconciler.retire(key, now.plusSeconds(30), now, grace))
    }

    @Test
    fun `a planned alarm inside the grace window at a new instant is re-armed, not kept`() {
        // The grace protects an alarm nothing plans any more. One the plan moved — a due time
        // edited a moment ago — is a different instant, and the adapter is told so; whether it
        // can honour a past one is its call.
        val key = ReminderKey("a", 0)
        val moved = task(reminderTime = LocalTime.of(11, 57))

        val commands = reconcile(armed = mapOf(key to at(LocalTime.of(11, 55))), tasks = listOf(moved))

        assertTrue(commands.single() is ReminderCommand.Arm)
        assertEquals(at(LocalTime.of(11, 57)), (commands.single() as ReminderCommand.Arm).at)
    }
}
