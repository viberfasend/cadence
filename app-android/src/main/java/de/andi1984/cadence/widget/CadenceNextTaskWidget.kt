package de.andi1984.cadence.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.R
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.sortedFor
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * A single-glance widget: whichever open task importance-first sorting puts at the top of
 * [CadenceTodayWidget]'s list, and nothing else. Meant for a home screen with no room for a
 * scrolling list, so it answers one question — "what's next?" — and stays tickable while doing it.
 *
 * It reads [TaskListScope.TODAY] rather than filtering for itself, so the task it names is always
 * the one the Today widget and the Today screen have at the top.
 */
class CadenceNextTaskWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // `as?` for the same reason [TaskListWidget] uses it: a crashed widget is a worse failure
        // than an empty one, and the empty state here still opens quick-add.
        val container = (context.applicationContext as? CadenceApplication)?.container
        val today = LocalDate.now()
        // showCompleted = false, unlike the list widgets and regardless of the setting: "what's
        // next" is a question a finished task cannot answer, so this one always skips them.
        val next = container?.let {
            TaskListScope.TODAY
                .select(it.repository.tasks.first(), today, showCompleted = false)
                .sortedFor(SortMode.IMPORTANCE)
                .firstOrNull()
        }

        provideContent {
            GlanceTheme(colors = CadenceWidgetColors) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .appWidgetBackground()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (next == null) {
                        EmptyContent(context)
                    } else {
                        TaskWidgetRow(context, next, today, modifier = GlanceModifier.fillMaxSize())
                    }
                }
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
