package de.andi1984.cadence.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.andi1984.cadence.R
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.TaskView
import de.andi1984.cadence.ui.taskList
import java.time.LocalDate

/**
 * A single-glance widget: whichever open task sits at the top of [CadenceTodayWidget]'s list, and
 * nothing else. Meant for a home screen with no room for a scrolling list, so it answers one
 * question — "what's next?" — and stays tickable while doing it.
 *
 * It draws from [TaskView.Today] rather than filtering for itself, so the task it names is always
 * the one the Today widget and the Today screen have at the top.
 *
 * [SizeMode.Single]: one row fills whatever size it is given, so there is nothing to recompose
 * for on a resize — and a widget that never asks to be recomposed for its size never needs the
 * process for it ([TaskListWidget] explains the same choice for the lists).
 *
 * The session mechanics live once in [CadenceStateWidget]; this class only says what to draw.
 */
class CadenceNextTaskWidget : CadenceStateWidget() {

    override val sizeMode = SizeMode.Single

    @Composable
    override fun Content(context: Context, state: CadenceUiState?, today: LocalDate) {
        // The head of the same list [CadenceTodayWidget] draws, minus anything already ticked
        // off: "what's next" is a question a finished task cannot answer, and Today keeps a
        // completed task on screen for the rest of the day when `showCompleted` is on.
        val next = state
            ?.taskList(TaskView.Today, today)
            ?.tasks
            ?.firstOrNull { !it.isDone }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // No container to read from — saying "all clear" would be a guess.
                state == null -> Spacer(GlanceModifier.fillMaxSize())
                next == null -> EmptyContent(context)
                else ->
                    TaskWidgetRow(
                        context,
                        next,
                        today,
                        // A plain surface, not a collection item, so the circle can take
                        // the broadcast route — no window, no activity start.
                        toggleAction = actionRunCallback<ToggleTaskCallback>(
                            actionParametersOf(ToggleTaskCallback.TASK_ID to next.id),
                        ),
                        modifier = GlanceModifier.fillMaxSize(),
                        card = false,
                    )
            }
        }
    }
}

@Composable
private fun EmptyContent(context: Context) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            // Nothing to show is exactly when someone wants to add something, so the empty
            // state opens quick-add rather than merely opening the app.
            .clickable(actionStartActivity(WidgetIntents.openQuickAdd(context)))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = context.getString(R.string.widget_next_task_empty),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
