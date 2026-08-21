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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.MainActivity
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.sortedFor
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Today's due-and-overdue tasks, importance first — the same rule
 * [de.andi1984.cadence.ui.today.TodayScreen] draws its own two sections from (`CLAUDE.md`: "the
 * product rule the whole app is built on"). A widget is a shrunk-down view of the app, not a
 * second opinion about what matters, so it reuses the app's own
 * [de.andi1984.cadence.ui.sortedFor] rather than sorting a third way.
 *
 * [SizeMode.Exact] rather than [SizeMode.Responsive]: a scrolling list just shows more or fewer
 * rows as it grows, so it needs one continuously-adapting layout, not a fixed set of breakpoint
 * layouts to switch between.
 */
class CadenceTaskListWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as CadenceApplication).container
        val today = LocalDate.now()
        val tasks = container.repository.tasks.first()
        val due = (tasks.filter { it.isOverdue(today) } + tasks.filter { it.isDueOn(today) && !it.isDone })
            .distinctBy { it.id }
            .sortedFor(SortMode.IMPORTANCE)

        provideContent {
            GlanceTheme(colors = CadenceWidgetColors) {
                TaskListWidgetContent(context, due, today)
            }
        }
    }
}

@Composable
private fun TaskListWidgetContent(context: Context, tasks: List<Task>, today: LocalDate) {
    val openApp = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp),
    ) {
        Text(
            text = context.getString(R.string.widget_task_list_title),
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity(openApp))
                .padding(12.dp),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
        )
        if (tasks.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = context.getString(R.string.widget_task_list_empty),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(tasks, itemId = { it.id.hashCode().toLong() }) { task ->
                    TaskWidgetRow(context, task, today)
                }
            }
        }
    }
}
