package de.andi1984.cadence.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.andi1984.cadence.MainActivity
import de.andi1984.cadence.R

/** Posts the reminder notification when a task's alarm fires. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AlarmReminderScheduler.EXTRA_TASK_ID)
        val title = intent.getStringExtra(AlarmReminderScheduler.EXTRA_TITLE).orEmpty()
        val leadMinutes = intent.getIntExtra(AlarmReminderScheduler.EXTRA_LEAD_MINUTES, 0)
        if (taskId.isNullOrBlank() || title.isBlank()) return

        val allowed = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmReminderScheduler.EXTRA_TASK_ID, taskId)
        }
        val requestCode = ReminderRequestCodes.codeFor(context, taskId, leadMinutes)
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Lead 0 is the legacy reminderTime moment — "due today", as it always said. A positive
        // lead names how long is left, since the task itself is not due yet.
        val contentText = if (leadMinutes > 0) {
            context.resources.getQuantityString(
                R.plurals.reminder_lead_content_text,
                leadMinutes,
                leadMinutes,
            )
        } else {
            context.getString(R.string.reminder_content_text)
        }

        val notification = NotificationCompat.Builder(context, AlarmReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(context).notify(requestCode, notification)
    }
}
