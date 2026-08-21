package de.andi1984.cadence.widget

import android.app.Activity
import android.os.Bundle
import de.andi1984.cadence.AppContainer
import de.andi1984.cadence.CadenceApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ticks a task off from a widget row, and shows nothing at all while doing it.
 *
 * **Why an Activity for what is plainly a background write.** Glance translates a `LazyColumn`
 * into a `ListView`, so a row is a RemoteViews collection item, and a collection carries exactly
 * one `PendingIntent` *template* with a fill-in intent per item. Glance's template starts an
 * activity, and every other kind of action — `actionRunCallback` included — has to reach its
 * destination by way of a trampoline activity that unpacks the fill-in intent and dispatches.
 * That indirection is what did not survive contact with a real device here: rows wired with
 * `actionStartActivity` opened the right task every time, while `actionRunCallback` on the
 * completion circle never arrived, which is precisely the shape of "the widget renders but
 * nothing responds".
 *
 * So this takes the one route the collection is known to deliver. It is the same invisible-
 * trampoline pattern Glance uses internally (`InvisibleActionTrampolineActivity`), with our work
 * in place of its dispatch, and it costs one activity launch that never draws a frame.
 *
 * `Theme.NoDisplay` obliges an Activity to finish before `onResume`, which is why [finish] is
 * called here in `onCreate` and why the write goes to [AppContainer.applicationScope] rather than
 * to anything this Activity owns.
 */
class WidgetToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent?.getStringExtra(WidgetIntents.EXTRA_TASK_ID)
        val container = (application as? CadenceApplication)?.container
        if (taskId != null && container != null) {
            container.applicationScope.launch { container.toggleTask(taskId) }
        }
        finish()
    }
}

/**
 * Completing or reopening a task, exactly as [de.andi1984.cadence.ui.CadenceViewModel.toggleTask]
 * does it, bar the debounce: that method arms the ViewModel's two-second sync timer and there is
 * no ViewModel here to hold one, so the round fires straight away. Both are "a mutation arms a
 * round" (ADR 0002) — only the delay differs.
 */
private suspend fun AppContainer.toggleTask(taskId: String) {
    // Read the row back rather than trust what the widget was drawn from — the widget may have
    // been rendered before an edit, and it is the same reason `CadenceRepository.setCompleted`
    // itself re-reads before spawning a successor.
    val task = repository.tasks.first().firstOrNull { it.id == taskId } ?: return
    // Reopening a recurring task takes the occurrence its completion inserted back out, and an
    // alarm outlives the row it belongs to unless it is cancelled here.
    repository.setCompleted(task, !task.isDone).forEach { reminderScheduler.cancel(it) }
    syncEngine.syncInBackground()
}
