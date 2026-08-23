package de.andi1984.cadence.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import de.andi1984.cadence.AppContainer
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.sync.SyncWorker
import kotlinx.coroutines.flow.first

/**
 * Ticks a task off from the **next-task** widget's circle, or reopens it — the target of its
 * `actionRunCallback`. Only that widget can use this route: its circle sits on a plain surface,
 * while a list row is a RemoteViews collection item, from which `actionRunCallback` never
 * arrived on a real device — the lists go through [WidgetToggleActivity] instead. Same write,
 * same aftercare, different door.
 *
 * Runs inside Glance's `ActionCallbackBroadcastReceiver`, i.e. in a broadcast's `goAsync` window
 * of about ten seconds, which a read, a write and two enqueues spend a few milliseconds of. The
 * process may well have been started by this very broadcast: everything here reaches the
 * database through [AppContainer], which `CadenceApplication.onCreate` builds before any receiver
 * runs, so a cold process and a warm one take the same path.
 *
 * It does exactly what [de.andi1984.cadence.ui.CadenceViewModel.toggleTask] does, bar the
 * debounce — that method arms the ViewModel's two-second sync timer, and there is no ViewModel
 * here to hold one — and then does the two things a write made *outside* the app has to do for
 * itself: push the widgets a redraw, and hand the push to Supabase to a [SyncWorker] rather than
 * to a coroutine on a process nothing is keeping alive.
 */
class ToggleTaskCallback : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TASK_ID] ?: return
        val container = (context.applicationContext as? CadenceApplication)?.container ?: return
        container.toggleTaskFromWidget(taskId)
        // The container's own collector does this on the emission the write just caused, too;
        // asking directly as well costs a no-op event on a running session and makes the redraw
        // not depend on that collector having started yet in a process this broadcast created.
        WidgetUpdater.refreshAll(context)
        SyncWorker.enqueue(context)
    }

    companion object {
        val TASK_ID = ActionParameters.Key<String>("taskId")
    }
}

/** The write both widget toggle routes share — [ToggleTaskCallback] and [WidgetToggleActivity]. */
internal suspend fun AppContainer.toggleTaskFromWidget(taskId: String) {
    // Read the row back rather than trust what the widget was drawn from — the widget may have
    // been rendered before an edit, and it is the same reason `CadenceRepository.setCompleted`
    // itself re-reads before spawning a successor.
    val task = repository.tasks.first().firstOrNull { it.id == taskId } ?: return
    // Reopening a recurring task takes the occurrence its completion inserted back out, and an
    // alarm outlives the row it belongs to unless it is cancelled here.
    repository.setCompleted(task, !task.isDone).forEach { reminderScheduler.cancel(it) }
}
