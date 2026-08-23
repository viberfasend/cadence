package de.andi1984.cadence.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import de.andi1984.cadence.MainActivity
import de.andi1984.cadence.reminders.AlarmReminderScheduler

/**
 * Every way a widget can open the app, in one place — and, more to the point, the one place that
 * gets the `data` URI right.
 *
 * **Two intents that differ only in their extras are the same intent.** [Intent.filterEquals] —
 * which is what `PendingIntent` matches on — compares action, data, type, package, component and
 * categories, and deliberately ignores extras. Glance builds every `actionStartActivity` into a
 * `PendingIntent` with `FLAG_UPDATE_CURRENT`, so a list of rows whose intents carry nothing but a
 * different `taskId` extra all collapse onto *one* PendingIntent, and the last row composed
 * overwrites the extras of every row above it. The list looks fine and every row opens the same
 * task: interactive code that reads as read-only.
 *
 * A distinct URI per destination is what keeps them apart. [AlarmReminderScheduler] already had
 * to learn this for its per-task alarms (`cadence://task/$id`), and these reuse that scheme so the
 * two agree on what a task's URI looks like.
 *
 * The extras still carry the payload — the URI only has to be *distinct*, and `MainActivity`
 * keeps reading the extras it always read.
 */
object WidgetIntents {

    /** Set when the app should come up with the quick-add sheet already open. */
    const val EXTRA_QUICK_ADD = "de.andi1984.cadence.widget.QUICK_ADD"

    /** The task an intent is about. The reminder notification's key, reused so the two agree. */
    const val EXTRA_TASK_ID = AlarmReminderScheduler.EXTRA_TASK_ID

    // Ticking a task off is not an intent at all any more: the circle is an `actionRunCallback`
    // to [ToggleTaskCallback], a broadcast that needs no activity and no URI of its own.

    /** Opens the app where it last was. */
    fun openApp(context: Context): Intent = base(context, "cadence://widget/app")

    /** Opens a task's detail screen — the same route a reminder notification opens. */
    fun openTask(context: Context, taskId: String): Intent =
        base(context, "cadence://task/$taskId")
            .putExtra(EXTRA_TASK_ID, taskId)

    /** Opens the app with the quick-add sheet up, ready for a title. */
    fun openQuickAdd(context: Context): Intent =
        base(context, "cadence://widget/quick-add")
            .putExtra(EXTRA_QUICK_ADD, true)

    /**
     * `CLEAR_TOP` without `SINGLE_TOP`, and `MainActivity` deliberately left on the default
     * `standard` launch mode: the activity is then torn down and recreated, so `onCreate` runs
     * and reads this intent. Handing a warm activity a new intent instead would need
     * `onNewIntent` *and* a way to redirect a `NavHost` whose start destination is already
     * fixed — which is the crash the reminder deep link was fixed for once already.
     */
    private fun base(context: Context, uri: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(uri)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
}
