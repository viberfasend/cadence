package de.andi1984.cadence.domain.reminder

import de.andi1984.cadence.domain.model.Task
import java.time.Instant
import java.time.ZoneId

/**
 * One concrete moment a `ReminderScheduler`-shaped platform port should fire a notification for
 * [taskId] — [leadMinutes] is how long before [Task.dueTime] this is, or `0` for the moment
 * [Task.reminderTime] itself names.
 */
data class ReminderInstant(val taskId: String, val leadMinutes: Int, val triggerAt: Instant)

/**
 * Turns tasks into the concrete instants a reminder scheduler should arm — pure, so both shells'
 * platform schedulers (Android's AlarmManager, the desktop's poll) share one answer to "when" and
 * it is unit-testable on the JVM instead of copied twice.
 *
 * Two independent sources, both optional, land in the result for a task:
 *  - [Task.dueTime] fires once per entry in [leadMinutes], that many minutes before it — quick
 *    add's "18 Uhr" already fills [Task.dueTime] in, so this list is what turns a time typed into
 *    a task into an actual notification.
 *  - [Task.reminderTime] fires once, at its own exact moment, reported with lead `0` — a
 *    manually chosen reminder that predates lead times, independent of [Task.dueTime] and
 *    unaffected by [leadMinutes].
 *
 * A task with no due date or one already done contributes nothing. **Deliberately not filtered
 * by "now"**, unlike [de.andi1984.cadence.domain.recurrence.RecurrenceEngine]'s date math: an
 * instant already in the past is still reported, because the two platform schedulers disagree on
 * what that should mean. AlarmManager cannot fire retroactively, so Android's scheduler discards
 * a past instant and cancels whatever alarm used to be there; the desktop has no such limit and
 * deliberately fires on the very next poll after a reminder that elapsed while the app was
 * closed, exactly as it did before this planner existed. Filtering here would have silently taken
 * that behaviour away from the desktop to give Android something it already does for itself.
 */
object ReminderPlanner {

    fun plan(
        tasks: List<Task>,
        leadMinutes: List<Int>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ReminderInstant> = tasks.flatMap { task -> instantsFor(task, leadMinutes, zone) }

    private fun instantsFor(task: Task, leadMinutes: List<Int>, zone: ZoneId): List<ReminderInstant> {
        if (task.isDone) return emptyList()
        val due = task.dueDate ?: return emptyList()
        // Keyed by lead minutes rather than collected into a list: the two sources below never
        // share a key (only the reminderTime source ever uses 0), so this also happens to dedupe
        // a lead value repeated in leadMinutes without an extra `.distinct()` at the call site.
        val byLead = LinkedHashMap<Int, Instant>()

        task.dueTime?.let { time ->
            val anchor = due.atTime(time).atZone(zone).toInstant()
            leadMinutes.filter { it > 0 }.distinct().forEach { lead ->
                byLead[lead] = anchor.minusSeconds(lead.toLong() * 60)
            }
        }
        task.reminderTime?.let { time ->
            byLead.putIfAbsent(0, due.atTime(time).atZone(zone).toInstant())
        }

        return byLead.map { (lead, trigger) -> ReminderInstant(task.id, lead, trigger) }
    }
}
