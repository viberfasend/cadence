package de.andi1984.cadence.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import java.time.LocalDate
import java.time.ZoneId

/**
 * Schedules the per-task "Remind at 09:00" alarms.
 *
 * Alarms are inexact ([AlarmManager.setWindow]) so the app needs no exact-alarm permission —
 * a todo reminder does not need second accuracy.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * Brings the scheduled alarms in line with [tasks]. Every task is either (re)scheduled or
     * cancelled, so removing a reminder takes effect on the next sync.
     */
    fun sync(tasks: List<Task>, today: LocalDate = LocalDate.now()) {
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

    fun cancel(taskId: Long) {
        val intent = pendingIntent(taskId, title = "", create = false) ?: return
        alarmManager?.cancel(intent)
    }

    private fun pendingIntent(taskId: Long, title: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            data = android.net.Uri.parse("cadence://task/$taskId")
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TITLE, title)
        }
        val extra = if (create) 0 else PendingIntent.FLAG_NO_CREATE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or extra
        return PendingIntent.getBroadcast(context, taskId.toInt(), intent, flags)
    }

    companion object {
        const val CHANNEL_ID = "cadence-reminders"
        const val ACTION_REMIND = "de.andi1984.cadence.REMIND"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        private const val WINDOW_MILLIS = 10 * 60 * 1000L

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
