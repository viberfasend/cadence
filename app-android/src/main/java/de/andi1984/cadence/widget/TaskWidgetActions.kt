package de.andi1984.cadence.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import de.andi1984.cadence.CadenceApplication
import kotlinx.coroutines.flow.first

/** The task id a widget action was invoked for — Glance actions carry no other context. */
val TaskIdKey = ActionParameters.Key<String>("taskId")

/**
 * Toggles a task's completion straight from a widget row, with no app process brought to the
 * foreground.
 *
 * Mirrors [de.andi1984.cadence.ui.CadenceViewModel.toggleTask] exactly, bar one thing: that
 * method arms the ViewModel's two-second sync debounce, and there is no ViewModel scope here to
 * hang a delayed job off, so this fires the round immediately instead. Both are "a mutation arms
 * a round" (ADR 0002) — only the delay differs.
 */
class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[TaskIdKey] ?: return
        // `as?`, like BootReceiver's: a ClassCastException raised here surfaces as the launcher
        // reporting that the widget crashed, which is a poor way to learn the manifest is wrong.
        val container = (context.applicationContext as? CadenceApplication)?.container ?: return
        // Read the row back rather than trust anything the widget drew it from — the same
        // reason CadenceRepository.setCompleted itself re-reads before spawning a successor.
        val task = container.repository.tasks.first().firstOrNull { it.id == taskId } ?: return
        container.repository.setCompleted(task, !task.isDone)
            .forEach { container.reminderScheduler.cancel(it) }
        container.syncEngine.syncInBackground()
        WidgetUpdater.refreshAll(context)
    }
}
