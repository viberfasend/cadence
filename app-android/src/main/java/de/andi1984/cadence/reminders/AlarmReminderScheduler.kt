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
import de.andi1984.cadence.domain.reminder.ReminderPlanner
import de.andi1984.cadence.ui.platform.ReminderScheduler

/**
 * Android's answer to [ReminderScheduler]: AlarmManager, which the desktop has no counterpart
 * for — phase 5 runs a coroutine timer against the same port instead.
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
     * Brings the scheduled alarms in line with [tasks] and [leadMinutes]. Every task gets one
     * alarm per candidate lead — every positive entry in [leadMinutes] plus `0`, the moment
     * [Task.reminderTime] itself names — each either (re)scheduled or cancelled, so removing a
     * reminder or dropping a lead value from Settings takes effect on the next sync.
     */
    override fun sync(tasks: List<Task>, leadMinutes: List<Int>) {
        val manager = alarmManager ?: return
        val now = System.currentTimeMillis()
        // 0 is always a candidate — it is `reminderTime`'s own moment, scheduled the same way
        // since before lead times existed — so a task that never touches one behaves exactly as
        // it did before this method took a lead-minutes list at all.
        val candidateLeads = (leadMinutes.filter { it > 0 }.distinct() + 0)
        tasks.forEach { task -> reconcile(manager, task, leadMinutes, candidateLeads, now) }
    }

    private fun reconcile(
        manager: AlarmManager,
        task: Task,
        leadMinutes: List<Int>,
        candidateLeads: List<Int>,
        now: Long,
    ) {
        val planned = ReminderPlanner.plan(listOf(task), leadMinutes).associateBy { it.leadMinutes }
        val previousActive = ReminderRequestCodes.activeLeadsFor(context, task.id)
        val stillActive = mutableSetOf<Int>()
        candidateLeads.forEach { lead ->
            val triggerAt = planned[lead]?.triggerAt?.toEpochMilli()
            when {
                triggerAt == null || triggerAt + GRACE_MILLIS <= now -> cancelLead(task.id, lead)
                triggerAt > now -> {
                    val intent = pendingIntent(task.id, task.title, lead, create = true) ?: return@forEach
                    arm(manager, triggerAt, intent)
                    stillActive += lead
                }
                // Inside the grace period: the trigger has passed but the alarm may not have
                // been delivered yet — the inexact fallback is allowed to run late, and a doze
                // exit delivers pending alarms a beat after the app is already back and syncing.
                // Re-arming a past trigger would fire it again at once, and cancelling it would
                // be the race this grace exists to close, so an armed one is left exactly as it
                // is. One that was never armed (the app first saw the task after its trigger)
                // stays unarmed: AlarmManager cannot fire retroactively, and nor should we.
                lead in previousActive -> stillActive += lead
            }
        }
        // A lead that was armed before but is not even a candidate any more — Settings dropped
        // it — is never visited by the loop above, so it needs cancelling on its own.
        (previousActive - candidateLeads.toSet()).forEach { staleLead -> cancelLead(task.id, staleLead) }
        ReminderRequestCodes.setActiveLeadsFor(context, task.id, stillActive)
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
        // 0 (the legacy reminderTime alarm) predates the active-leads record and so is always
        // attempted, whether or not a sync ever ran to record it as active.
        val leads = ReminderRequestCodes.activeLeadsFor(context, taskId) + 0
        leads.forEach { lead -> cancelLead(taskId, lead) }
        ReminderRequestCodes.setActiveLeadsFor(context, taskId, emptySet())
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
         * How long after its trigger an armed alarm is left alone rather than cancelled. Exact
         * alarms arrive within seconds; the inexact fallback can be delivered up to this much
         * later, and cancelling inside that window took the notification away unfired.
         */
        private const val GRACE_MILLIS = 10 * 60 * 1000L

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
