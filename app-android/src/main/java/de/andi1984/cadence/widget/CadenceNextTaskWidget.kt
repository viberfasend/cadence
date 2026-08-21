package de.andi1984.cadence.widget

import android.content.Context
import android.content.Intent
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
import de.andi1984.cadence.MainActivity
import de.andi1984.cadence.R
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.sortedFor
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * A single-glance widget: whichever open task importance-first sorting puts at the top of
 * [CadenceTaskListWidget]'s list, at 1x1 (or bigger — it just centres). Meant for a home screen
 * that has no room for a scrolling list, so it answers one question only: "what's next?"
 */
class CadenceNextTaskWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as CadenceApplication).container
        val today = LocalDate.now()
        val tasks = container.repository.tasks.first()
        val next = (tasks.filter { it.isOverdue(today) } + tasks.filter { it.isDueOn(today) && !it.isDone })
            .distinctBy { it.id }
            .sortedFor(SortMode.IMPORTANCE)
            .firstOrNull()

        provideContent {
            GlanceTheme(colors = CadenceWidgetColors) {
                if (next == null) {
                    NextTaskEmptyContent(context)
                } else {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .appWidgetBackground()
                            .background(GlanceTheme.colors.widgetBackground)
                            .cornerRadius(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TaskWidgetRow(context, next, today, modifier = GlanceModifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun NextTaskEmptyContent(context: Context) {
    val openApp = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(openApp))
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
