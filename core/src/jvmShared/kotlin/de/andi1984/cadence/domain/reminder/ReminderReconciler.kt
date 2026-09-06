package de.andi1984.cadence.domain.reminder

import de.andi1984.cadence.domain.model.Task
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Identifies one alarm: a task and how long before its due time this one is, `0` for the moment
 * [Task.reminderTime] itself names. A task can carry several alarms at once, one per configured
 * lead, so a bare task id is not enough to name one.
 */
data class ReminderKey(val taskId: String, val leadMinutes: Int)

/** One thing a platform scheduler has to do to bring what it has armed in line with the plan. */
sealed interface ReminderCommand {
    val key: ReminderKey

    /** Schedule [key] to fire at [at] — replacing whatever was armed under it, if anything.
     *  [task] is the row as it stands, for whatever the notification needs of it (its title). */
    data class Arm(override val key: ReminderKey, val at: Instant, val task: Task) : ReminderCommand

    /** Drop [key]: it is armed and nothing plans it any more. */
    data class Cancel(override val key: ReminderKey) : ReminderCommand

    /** Leave [key] exactly as it is: nothing plans it any more, but its trigger passed so recently
     *  that the alarm may still be on its way — see [ReminderReconciler]. */
    data class Keep(override val key: ReminderKey) : ReminderCommand
}

/**
 * The diff between what a platform scheduler has armed and what [ReminderPlanner] says it should
 * have — pure, so both shells apply one answer to "what changes" instead of each re-deriving it in
 * its own vocabulary. [ReminderPlanner] answers "when"; this answers "so what do I do", and the
 * adapters are left with only the platform calls.
 *
 * The rules, in the order [reconcile] applies them:
 *  - **Reminders switched off** ([enabled] `false`) is an *empty plan*, nothing more: every armed
 *    key goes through the same "no longer planned" rule below, so switching them off cancels
 *    everything the device had armed without the caller stripping anything off the tasks. An
 *    empty task list would leave the alarms standing; an empty plan is what takes them down.
 *  - A planned key that is not armed, or is armed at a **different** instant, is [Arm]ed. The
 *    planner does not filter by "now", so an [Arm] may name an instant already in the past —
 *    what that means is the platform's business, and the two disagree: the desktop's poll fires
 *    it on its next tick (the reminder elapsed while the app was closed, and the balloon is still
 *    wanted), while AlarmManager cannot fire retroactively, so Android's adapter drops such an
 *    [Arm] and settles what it *had* armed under that key with [retire]. Neither is decided here.
 *  - A planned key armed at the **same** instant gets no command at all — the same input twice
 *    changes nothing, which is what makes calling this on every task emission cheap and safe.
 *    That silence is also what keeps the desktop from firing a reminder twice: a fired alarm
 *    stays armed at its instant, the plan keeps naming that instant, and nothing re-arms it.
 *  - An armed key that is **no longer planned** is [Cancel]led — unless its trigger passed less
 *    than [grace] ago, in which case it is [Keep]t (Android's `GRACE_MILLIS` rule). Inside that
 *    window there is no telling a delivered alarm from one still in flight: an exact alarm lands
 *    within seconds, but the inexact fallback may run this late, and cancelling it there took the
 *    notification away unfired — the bug that cost the lead-minutes feature its first release.
 *    The cost of keeping one is the reverse, a late notification for a reminder that was just
 *    cleared or ticked off; that is the smaller harm. A [grace] of zero disables the window, which
 *    is the desktop's setting: its poll has no delivery latency to wait out.
 *
 * [Keep] is reported rather than swallowed so an adapter that persists its armed set can tell
 * "still armed, on purpose" from "the reconciler forgot this key".
 */
object ReminderReconciler {

    fun reconcile(
        armed: Map<ReminderKey, Instant>,
        tasks: List<Task>,
        leadMinutes: List<Int>,
        enabled: Boolean,
        now: Instant,
        grace: Duration,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ReminderCommand> {
        val planned: Map<ReminderKey, Instant> = if (!enabled) {
            emptyMap()
        } else {
            ReminderPlanner.plan(tasks, leadMinutes, zone)
                .associate { ReminderKey(it.taskId, it.leadMinutes) to it.triggerAt }
        }
        val taskById = tasks.associateBy { it.id }

        val commands = ArrayList<ReminderCommand>()
        planned.forEach { (key, at) ->
            if (armed[key] != at) {
                val task = taskById.getValue(key.taskId)
                commands += ReminderCommand.Arm(key, at, task)
            }
        }
        armed.forEach { (key, at) ->
            if (key !in planned) commands += retire(key, at, now, grace)
        }
        return commands
    }

    /**
     * What to do with an armed alarm nothing plans any more: [ReminderCommand.Cancel], unless its
     * trigger passed less than [grace] ago, in which case [ReminderCommand.Keep]. One still in
     * the future is always cancelled — it has not fired and must not.
     *
     * Public because an adapter that cannot fire retroactively needs the same answer for an
     * [ReminderCommand.Arm] it has to drop: the plan names an instant it cannot honour, so
     * whatever it had armed under that key is, from its point of view, no longer planned.
     */
    fun retire(key: ReminderKey, armedAt: Instant, now: Instant, grace: Duration): ReminderCommand {
        val recentlyPassed = !armedAt.isAfter(now) && armedAt.plus(grace).isAfter(now)
        return if (recentlyPassed) ReminderCommand.Keep(key) else ReminderCommand.Cancel(key)
    }
}
