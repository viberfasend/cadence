package de.andi1984.cadence.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.ReminderCommand
import de.andi1984.cadence.domain.reminder.ReminderKey
import de.andi1984.cadence.domain.reminder.ReminderReconciler
import de.andi1984.cadence.ui.platform.ReminderScheduler
import java.time.Duration
import java.time.Instant

/**
 * Android's answer to [ReminderScheduler]: AlarmManager, which the desktop has no counterpart
 * for — phase 5 runs a coroutine timer against the same port instead.
 *
 * A thin adapter: the diff between what is armed and what the tasks call for — which alarm to
 * set, which to cancel, which to leave alone — is [ReminderReconciler]'s, pure and tested in
 * `:core`. This class loads the armed set ([ReminderRequestCodes.armed]), applies the commands
 * with AlarmManager, and persists what is armed afterwards. The one decision it keeps for itself
 * is the platform's own: AlarmManager cannot fire retroactively, so an [ReminderCommand.Arm]
 * naming an instant already in the past is dropped — the alarm that may already be armed under
 * that key is settled with [ReminderReconciler.retire], the same grace rule the reconciler
 * applies to a key nothing plans any more, because from this platform's point of view that is
 * what it has become. One never armed stays unarmed: nor should we fire retroactively.
 *
 * Alarms are exact and Doze-proof ([AlarmManager.setExactAndAllowWhileIdle]) wherever the
 * platform lets them be, and degrade to [AlarmManager.setAndAllowWhileIdle] — inexact, but still
 * delivered in Doze — where it does not. The first version of this class used [AlarmManager.setWindow]
 * with a ten-minute window instead, on the grounds that a todo reminder does not need second
 * accuracy; a lead of "5 minutes before" made that false. An inexact alarm is also *deferred*
 * outright while the phone dozes, which is exactly the state a phone is in when a reminder is
 * meant to interrupt it, so on a locked phone the old alarm arrived at the next maintenance window
 * or on unlock — and by then the trigger was in the past and the next `sync` cancelled it unfired.
 *
 * The permission is `USE_EXACT_ALARM` (Android 13+, granted at install with no prompt) plus
 * `SCHEDULE_EXACT_ALARM` on 12 and 12L, where it is granted by default; `canScheduleExactAlarms`
 * is still checked, because either can be revoked from the app's special-access settings, and a
 * revoked one throws rather than degrades.
 */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * Brings the scheduled alarms in line with [tasks], [leadMinutes] and [enabled]. Every task
     * gets one alarm per candidate lead — every positive entry in [leadMinutes] plus `0`, the
     * moment [Task.reminderTime] itself names — each either (re)scheduled or cancelled, so
     * removing a reminder, dropping a lead value from Settings or switching reminders off takes
     * effect on the next sync.
     */
    override fun sync(tasks: List<Task>, leadMinutes: List<Int>, enabled: Boolean) {
        val manager = alarmManager ?: return
        val now = Instant.now()
        val previous = ReminderRequestCodes.armed(context)
        val next = previous.toMutableMap()
        ReminderReconciler.reconcile(previous, tasks, leadMinutes, enabled, now, GRACE)
            .forEach { command -> apply(manager, command, previous, next, now) }
        ReminderRequestCodes.setArmed(context, previous, next)
    }

    private fun apply(
        manager: AlarmManager,
        command: ReminderCommand,
        previous: Map<ReminderKey, Instant>,
        next: MutableMap<ReminderKey, Instant>,
        now: Instant,
    ) {
        when (command) {
            is ReminderCommand.Arm -> {
                val key = command.key
                if (!command.at.isAfter(now)) {
                    // AlarmManager cannot fire retroactively. See the class comment.
                    val armedAt = previous[key] ?: return
                    apply(manager, ReminderReconciler.retire(key, armedAt, now, GRACE), previous, next, now)
                    return
                }
                val intent = pendingIntent(key.taskId, command.task.title, key.leadMinutes, create = true) ?: return
                arm(manager, command.at.toEpochMilli(), intent)
                next[key] = command.at
            }
            is ReminderCommand.Cancel -> {
                cancelLead(command.key.taskId, command.key.leadMinutes)
                next.remove(command.key)
            }
            is ReminderCommand.Keep -> Unit
        }
    }

    private fun arm(manager: AlarmManager, triggerAt: Long, intent: PendingIntent) {
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (exactAllowed) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
                return
            } catch (_: SecurityException) {
                // Revoked between the check and the call; fall through to the inexact alarm.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
    }

    override fun cancel(taskId: String) {
        val previous = ReminderRequestCodes.armed(context)
        // 0 (the legacy reminderTime alarm) predates the armed record and so is always
        // attempted, whether or not a sync ever ran to record it as armed.
        val leads = previous.keys.filter { it.taskId == taskId }.map { it.leadMinutes }.toSet() + 0
        leads.forEach { lead -> cancelLead(taskId, lead) }
        ReminderRequestCodes.setArmed(context, previous, previous.filterKeys { it.taskId != taskId })
    }

    private fun cancelLead(taskId: String, leadMinutes: Int) {
        // No persisted code means this (task, lead) was never scheduled — nothing to cancel, and
        // looking one up must not assign one (see ReminderRequestCodes.existingCodeFor).
        val requestCode = ReminderRequestCodes.existingCodeFor(context, taskId, leadMinutes) ?: return
        val intent = pendingIntent(taskId, title = "", leadMinutes, requestCode = requestCode, create = false)
            ?: return
        alarmManager?.cancel(intent)
    }

    private fun pendingIntent(
        taskId: String,
        title: String,
        leadMinutes: Int,
        requestCode: Int = ReminderRequestCodes.codeFor(context, taskId, leadMinutes),
        create: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            data = android.net.Uri.parse("cadence://task/$taskId/$leadMinutes")
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_LEAD_MINUTES, leadMinutes)
        }
        val extra = if (create) 0 else PendingIntent.FLAG_NO_CREATE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or extra
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    companion object {
        const val CHANNEL_ID = "cadence-reminders"
        const val ACTION_REMIND = "de.andi1984.cadence.REMIND"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LEAD_MINUTES = "leadMinutes"
        /**
         * How long after its trigger an armed alarm nothing plans any more is left alone rather
         * than cancelled. Exact alarms arrive within seconds; the inexact fallback can be
         * delivered up to this much later, and cancelling inside that window took the
         * notification away unfired. Handed to [ReminderReconciler], which is where the rule
         * itself lives.
         */
        private val GRACE: Duration = Duration.ofMinutes(10)

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
