package de.andi1984.cadence.reminders

import android.content.Context

/**
 * The `PendingIntent`/notification request code for a (task id, lead minutes) pair, collision-free
 * by construction: the pair is assigned the next counter value the first time it is seen and that
 * assignment is persisted, rather than derived from a 32-bit [String.hashCode] two different pairs
 * can share. [AlarmReminderScheduler] assigns a code when it schedules an alarm and
 * [ReminderReceiver] looks up the same persisted value when the alarm fires, so both sides always
 * agree.
 *
 * `leadMinutes` `0` — the moment [de.andi1984.cadence.domain.model.Task.reminderTime] itself
 * names — keeps the bare task id as its key, exactly as it was before lead times existed; only a
 * positive lead ever gets the newer `"$taskId:$leadMinutes"` key, so upgrading changes nothing
 * about a code, or an alarm already armed under one, that predates this file knowing about leads.
 */
object ReminderRequestCodes {

    private const val PREFS_NAME = "cadence_reminder_request_codes"
    private const val KEY_NEXT_CODE = "next_code"
    private const val ACTIVE_LEADS_PREFIX = "active_leads:"

    private fun keyFor(taskId: String, leadMinutes: Int): String =
        if (leadMinutes <= 0) taskId else "$taskId:$leadMinutes"

    @Synchronized
    fun codeFor(context: Context, taskId: String, leadMinutes: Int): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = keyFor(taskId, leadMinutes)
        val existing = prefs.getInt(key, NO_CODE)
        if (existing != NO_CODE) return existing
        val assigned = prefs.getInt(KEY_NEXT_CODE, 1)
        prefs.edit()
            .putInt(key, assigned)
            .putInt(KEY_NEXT_CODE, assigned + 1)
            .apply()
        return assigned
    }

    /** The code already assigned to (taskId, leadMinutes), or `null` if it was never scheduled —
     *  never assigns one, so cancelling a lead that was never armed does not grow the store. */
    @Synchronized
    fun existingCodeFor(context: Context, taskId: String, leadMinutes: Int): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getInt(keyFor(taskId, leadMinutes), NO_CODE)
        return if (existing == NO_CODE) null else existing
    }

    /** The lead minutes [AlarmReminderScheduler] currently has an alarm armed for on this task,
     *  so the next `sync` can tell "still wanted" apart from "Settings dropped this one" without
     *  re-deriving history from scratch. */
    @Synchronized
    fun activeLeadsFor(context: Context, taskId: String): Set<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(ACTIVE_LEADS_PREFIX + taskId, null)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()
    }

    @Synchronized
    fun setActiveLeadsFor(context: Context, taskId: String, leads: Set<Int>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (leads.isEmpty()) {
            prefs.edit().remove(ACTIVE_LEADS_PREFIX + taskId).apply()
        } else {
            prefs.edit().putString(ACTIVE_LEADS_PREFIX + taskId, leads.joinToString(",")).apply()
        }
    }

    private const val NO_CODE = 0
}
