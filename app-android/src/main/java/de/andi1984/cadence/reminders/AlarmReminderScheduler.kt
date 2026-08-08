package de.andi1984.cadence.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.platform.ReminderScheduler
import java.time.ZoneId

/**
 * Android's answer to [ReminderScheduler]: AlarmManager, which the desktop has no counterpart
 * for — phase 5 runs a coroutine timer against the same port instead.
 *
 * Alarms are inexact ([AlarmManager.setWindow]) so the app needs no exact-alarm permission —
 * a todo reminder does not need second accuracy.
 */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * Brings the scheduled alarms in line with [tasks]. Every task is either (re)scheduled or
     * cancelled, so removing a reminder takes effect on the next sync.
     */
    override fun sync(tasks: List<Task>) {
        val manager = alarmManager ?: return
        val now = System.currentTimeMillis()
        tasks.forEach { task ->
            val intent = pendingIntent(task.id, task.title, create = true) ?: return@forEach
            val due = task.dueDate
            val time = task.reminderTime
            val triggerAt = if (due != null && time != null && !task.isDone) {
                due.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else {
                null
            }
            if (triggerAt != null && triggerAt > now) {
                manager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_MILLIS, intent)
            } else {
                manager.cancel(intent)
            }
        }
    }

    override fun cancel(taskId: String) {
        val intent = pendingIntent(taskId, title = "", create = false) ?: return
        alarmManager?.cancel(intent)
    }

    private fun pendingIntent(taskId: String, title: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            data = android.net.Uri.parse("cadence://task/$taskId")
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TITLE, title)
        }
        val extra = if (create) 0 else PendingIntent.FLAG_NO_CREATE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or extra
        // requestCode must be an Int; String.hashCode() is a documented, JVM-stable algorithm
        // (unlike Object.hashCode()), so the same task id always maps to the same request code
        // across process restarts — a UUID has no int form of its own to reuse instead.
        return PendingIntent.getBroadcast(context, requestCodeFor(taskId), intent, flags)
    }

    companion object {
        const val CHANNEL_ID = "cadence-reminders"
        const val ACTION_REMIND = "de.andi1984.cadence.REMIND"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        private const val WINDOW_MILLIS = 10 * 60 * 1000L

        /** The `PendingIntent`/notification request code for a task id — see [pendingIntent]. */
        fun requestCodeFor(taskId: String): Int = taskId.hashCode()

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
