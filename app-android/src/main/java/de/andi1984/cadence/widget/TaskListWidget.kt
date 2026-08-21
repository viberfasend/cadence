package de.andi1984.cadence.widget

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.withoutSupersededOccurrences
import de.andi1984.cadence.ui.sortedFor
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Which slice of the task list a [TaskListWidget] shows.
 *
 * Adding a widget is adding an entry here plus a three-line subclass and a provider-info XML —
 * the list chrome, the rows, the empty state and the quick-add button are written once. That is
 * the whole point of keeping the widget layer modular: the *scope* is the variable, the
 * presentation is not.
 *
 * Each scope selects with the app's own derivations rather than a widget-local reimplementation,
 * so a widget can never disagree with the screen it mirrors.
 *
 * [showCompleted] is the user's own `CadenceSettings` flag, threaded through for the same reason
 * every screen honours it — and with a second payoff here. Left out, a ticked task would simply
 * vanish from the widget, which reads like the row was deleted rather than completed; with it on
 * (the default) the circle fills in and the title strikes through, so a tap visibly *did*
 * something. A widget nobody can see respond to a tap is a widget nobody believes is interactive.
 */
enum class TaskListScope(@StringRes val titleRes: Int, @StringRes val emptyRes: Int) {

    /** What [de.andi1984.cadence.ui.today.TodayScreen] shows: overdue first, then due today. */
    TODAY(R.string.widget_today_title, R.string.widget_today_empty) {
        override fun select(tasks: List<Task>, today: LocalDate, showCompleted: Boolean): List<Task> =
            (
                tasks.filter { it.isOverdue(today) } +
                    tasks.filter { it.isDueOn(today) && (showCompleted || !it.isDone) }
                )
                .distinctBy { it.id }
    },

    /**
     * What [de.andi1984.cadence.ui.inbox.InboxScreen] shows: unfiled, and only tasks that stand
     * on their own — a parent already speaks for its steps in a container list, and a recurring
     * occurrence that has been replaced is history (`CadenceUiState.rootTasks`).
     */
    INBOX(R.string.widget_inbox_title, R.string.widget_inbox_empty) {
        override fun select(tasks: List<Task>, today: LocalDate, showCompleted: Boolean): List<Task> =
            tasks
                .withoutSupersededOccurrences()
                .filter { it.isInbox && !it.isSubtask && (showCompleted || !it.isDone) }
    };

    abstract fun select(tasks: List<Task>, today: LocalDate, showCompleted: Boolean): List<Task>
}

/**
 * A scrolling list of tasks, tickable in place, with a quick-add button in its header.
 *
 * Ordering comes from `:ui`'s own [de.andi1984.cadence.ui.sortedFor] under the user's own
 * `sortMode` — importance first by default, due date breaking ties — rather than a third sort
 * order of the widget's own. A widget is a shrunk-down view of the app, not a second opinion
 * about what matters, so the sort chips the user set inside the app reach the home screen too.
 *
 * [SizeMode.Exact] rather than [SizeMode.Responsive]: a scrolling list simply shows more or fewer
 * rows as it grows, so it wants one continuously-adapting layout rather than a fixed set of
 * breakpoint layouts to switch between.
 */
abstract class TaskListWidget(private val scope: TaskListScope) : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // `as?` rather than a hard cast: a widget that throws is reported by the launcher as a
        // crashed widget, so an impossible-in-practice failure degrades to the empty state —
        // which is still a working quick-add button — instead of a broken tile.
        val container = (context.applicationContext as? CadenceApplication)?.container
        val today = LocalDate.now()
        val settings = container?.settingsStore?.state?.value
        val tasks = if (container == null || settings == null) {
            emptyList()
        } else {
            scope.select(container.repository.tasks.first(), today, settings.showCompleted)
                .sortedFor(settings.sortMode)
        }

        provideContent {
            GlanceTheme(colors = CadenceWidgetColors) {
                TaskListContent(context, scope, tasks, today)
            }
        }
    }
}

class CadenceTodayWidget : TaskListWidget(TaskListScope.TODAY)

class CadenceInboxWidget : TaskListWidget(TaskListScope.INBOX)

@Composable
private fun TaskListContent(
    context: Context,
    scope: TaskListScope,
    tasks: List<Task>,
    today: LocalDate,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp),
    ) {
        Header(context, scope)
        if (tasks.isEmpty()) {
            // The empty state is the invitation, so it opens quick-add rather than the app: a
            // widget showing "nothing due today" is exactly when someone wants to add something.
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .clickable(actionStartActivity(WidgetIntents.openQuickAdd(context)))
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = context.getString(scope.emptyRes),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    ),
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

@Composable
private fun Header(context: Context, scope: TaskListScope) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = context.getString(scope.titleRes),
            maxLines = 1,
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(WidgetIntents.openApp(context))),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
        )
        Box(
            modifier = GlanceModifier
                .size(44.dp)
                .clickable(actionStartActivity(WidgetIntents.openQuickAdd(context))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = context.getString(R.string.widget_add_task),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(22.dp),
            )
        }
    }
}
