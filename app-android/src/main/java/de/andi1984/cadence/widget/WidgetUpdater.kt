package de.andi1984.cadence.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * The one place that knows every widget Cadence ships. [de.andi1984.cadence.AppContainer] calls
 * this on every `repository.tasks` emission — the same "reconcile on every emission" shape
 * [de.andi1984.cadence.reminders.AlarmReminderScheduler] follows — so a new widget only has to be
 * added here once rather than at every call site that mutates a task.
 *
 * `updateAll` is a no-op when a widget has no instance on any home screen, so this never has to
 * check what is actually pinned before calling it.
 *
 * **What it does not do is carry the new rows in.** `updateAll` recomposes a running session; it
 * does not run `provideGlance` again, so a widget that read its tasks there would redraw itself
 * unchanged. The content follows the flow each widget collects inside `provideContent`
 * ([widgetUiState]) — this call is what *starts* a session for a widget that has none, which is
 * the case after the process was killed and is why it still earns its keep.
 *
 * [CadenceQuickAddWidget] is deliberately absent: it renders a button and reads no task, so a
 * task change has nothing to tell it.
 */
object WidgetUpdater {
    suspend fun refreshAll(context: Context) {
        CadenceTodayWidget().updateAll(context)
        CadenceInboxWidget().updateAll(context)
        CadenceNextTaskWidget().updateAll(context)
    }
}
