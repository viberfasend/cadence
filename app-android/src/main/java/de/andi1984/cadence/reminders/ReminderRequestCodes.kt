package de.andi1984.cadence.reminders

import android.content.Context

/**
 * The `PendingIntent`/notification request code for a task id, collision-free by construction: a
 * task id is assigned the next counter value the first time it is seen and that assignment is
 * persisted, rather than derived from a 32-bit [String.hashCode] two different ids can share.
 * [AlarmReminderScheduler] assigns a code when it schedules a task and [ReminderReceiver] looks
 * up the same persisted value when the alarm fires, so both sides always agree.
 */
object ReminderRequestCodes {

    private const val PREFS_NAME = "cadence_reminder_request_codes"
    private const val KEY_NEXT_CODE = "next_code"

    @Synchronized
    fun codeFor(context: Context, taskId: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getInt(taskId, NO_CODE)
        if (existing != NO_CODE) return existing
        val assigned = prefs.getInt(KEY_NEXT_CODE, 1)
        prefs.edit()
            .putInt(taskId, assigned)
            .putInt(KEY_NEXT_CODE, assigned + 1)
            .apply()
        return assigned
    }

    /** The code already assigned to [taskId], or `null` if it was never scheduled — never
     *  assigns one, so cancelling a task with no reminder does not grow the store. */
    @Synchronized
    fun existingCodeFor(context: Context, taskId: String): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getInt(taskId, NO_CODE)
        return if (existing == NO_CODE) null else existing
    }

    private const val NO_CODE = 0
}
