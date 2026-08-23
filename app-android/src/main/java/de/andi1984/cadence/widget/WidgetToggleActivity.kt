package de.andi1984.cadence.widget

import android.app.Activity
import android.os.Bundle
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.sync.SyncWorker
import kotlinx.coroutines.launch

/**
 * Ticks a task off from a **list** widget row, and shows nothing at all while doing it.
 *
 * **Why an Activity for what is plainly a background write.** The list widgets scroll, so their
 * rows are RemoteViews collection items — a collection carries exactly one `PendingIntent`
 * *template* with a fill-in intent per item, and Glance's template starts an activity. Every
 * other kind of action, `actionRunCallback` included, has to reach its destination by way of a
 * trampoline activity that unpacks the fill-in intent and dispatches — and that indirection is
 * what did not survive contact with a real device: rows wired with `actionStartActivity` opened
 * the right task every time while `actionRunCallback` on the very same rows never arrived. So
 * this takes the one route the collection is known to deliver, with our work in place of
 * Glance's dispatch. The next-task widget is not a collection, so its circle skips all of this
 * and broadcasts to [ToggleTaskCallback] — same write, no window at all.
 *
 * `Theme.NoDisplay` obliges an Activity to finish before `onResume`, which is why [finish] is
 * called here in `onCreate` and why the write goes to the container's `applicationScope` rather
 * than to anything this Activity owns. The aftercare matches [ToggleTaskCallback]'s, for the
 * same reason it exists there: this write happens with no screen open, so it redraws the widgets
 * itself and hands the Supabase push to a [SyncWorker] that outlives the cached process.
 */
class WidgetToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent?.getStringExtra(WidgetIntents.EXTRA_TASK_ID)
        val container = (application as? CadenceApplication)?.container
        if (taskId != null && container != null) {
            container.applicationScope.launch {
                container.toggleTaskFromWidget(taskId)
                WidgetUpdater.refreshAll(applicationContext)
            }
            SyncWorker.enqueue(applicationContext)
        }
        finish()
    }
}
