package de.andi1984.cadence.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * The one place that knows every widget Cadence ships. [de.andi1984.cadence.AppContainer]
 * calls this on every `repository.tasks` emission — the same "reconcile on every emission" shape
 * [de.andi1984.cadence.reminders.AlarmReminderScheduler] follows — so a new widget only has to be
 * added here once rather than at every call site that mutates a task.
 *
 * `updateAll` is a no-op when a widget has no instance on any home screen, so this never has to
 * check what is actually pinned before calling it.
 */
object WidgetUpdater {
    suspend fun refreshAll(context: Context) {
        CadenceTaskListWidget().updateAll(context)
        CadenceNextTaskWidget().updateAll(context)
    }
}
