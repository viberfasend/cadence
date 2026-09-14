package de.andi1984.cadence.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.sync.SyncWorker

/**
 * The one place that knows every widget Primico ships. [de.andi1984.cadence.AppContainer] calls
 * this on every `repository.tasks` emission — the same "reconcile on every emission" shape
 * [de.andi1984.cadence.reminders.AlarmReminderScheduler] follows — so a new widget only has to be
 * added here once rather than at every call site that mutates a task. [ToggleTaskCallback] and
 * [WidgetMidnightRefresh] call it for the writes and the day change that happen with no screen
 * open.
 *
 * `updateAll` is a no-op when a widget has no instance on any home screen, so this never has to
 * check what is actually pinned before calling it.
 *
 * **What `updateAll` does depends on whether the widget's session is still open.** Glance keeps a
 * composition alive for about 45 seconds after it first draws; inside that window `updateAll` is
 * an event that recomposes it, and the flow each widget collects inside `provideContent` is what
 * carries the new rows in. Past it, `updateAll` starts a new session, which runs `provideGlance`
 * again and draws its first frame from a fresh snapshot ([widgetSnapshot]). Both paths end in the
 * right rows; neither needs this caller to know which it took.
 *
 * [CadenceQuickAddWidget] is deliberately absent: it renders a button and reads no task, so a
 * task change has nothing to tell it.
 */
object WidgetUpdater {
    suspend fun refreshAll(context: Context) {
        CadenceTodayWidget().updateAll(context)
        CadenceInboxWidget().updateAll(context)
        CadenceNextTaskWidget().updateAll(context)
        // Re-armed here as well as from `provideGlance`, because an update that lands on an open
        // session recomposes it without running `provideGlance` — and only while a widget exists,
        // so an alarm is never left chaining for a home screen with nothing on it. The periodic
        // pull is reconciled on the same condition plus a stored session (ADR 0002, amendment 1):
        // the widget is what makes background freshness worth a scheduled job, and a session is
        // what gives the job anything to do.
        if (hasTaskWidgets(context)) {
            WidgetMidnightRefresh.schedule(context)
            val engine = (context.applicationContext as? CadenceApplication)?.container?.syncEngine
            if (engine?.isSignedIn() == true) {
                SyncWorker.ensurePeriodic(context)
            } else {
                SyncWorker.cancelPeriodic(context)
            }
        } else {
            SyncWorker.cancelPeriodic(context)
        }
    }

    private suspend fun hasTaskWidgets(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return manager.getGlanceIds(CadenceTodayWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(CadenceInboxWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(CadenceNextTaskWidget::class.java).isNotEmpty()
    }
}
