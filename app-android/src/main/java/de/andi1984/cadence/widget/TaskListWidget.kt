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
import androidx.glance.layout.Spacer
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
import de.andi1984.cadence.ui.TaskView
import de.andi1984.cadence.ui.taskList
import java.time.LocalDate

/**
 * Which slice of the task list a [TaskListWidget] shows.
 *
 * Adding a widget is adding an entry here plus a three-line subclass and a provider-info XML —
 * the list chrome, the rows, the empty state and the quick-add button are written once. The
 * *scope* is the variable; the presentation is not.
 *
 * A scope is a [TaskView] and two strings, deliberately: `ui/TaskLists.kt` already decides which
 * tasks a list shows and in what order, so a widget names a view rather than deriving one.
 * Deriving is the drift that refactor was made to end — a widget filtering for itself would be a
 * sixth copy of rules that had already gone out of step between two screens.
 *
 * What comes with that for free is the user's own `showCompleted` and `sortMode`, handled per
 * view the way each screen handles it. `showCompleted` earns a second keep here: without it a
 * ticked task would simply vanish from the widget, which reads as though the row had been
 * deleted rather than completed. With it on — the default — the circle fills in and the title
 * strikes through, so a tap visibly *did* something. A widget nobody can see respond to a tap is
 * a widget nobody believes is interactive.
 */
enum class TaskListScope(
    @StringRes val titleRes: Int,
    @StringRes val emptyRes: Int,
    val view: TaskView,
) {

    /** What [de.andi1984.cadence.ui.today.TodayScreen] shows: overdue first, then due today. */
    TODAY(R.string.widget_today_title, R.string.widget_today_empty, TaskView.Today),

    /** What [de.andi1984.cadence.ui.inbox.InboxScreen] shows: unfiled, parents speaking for
     *  their steps. */
    INBOX(R.string.widget_inbox_title, R.string.widget_inbox_empty, TaskView.Inbox),
}

/**
 * A scrolling list of tasks, tickable in place, with a quick-add button in its header.
 *
 * Which tasks, in what order, is [de.andi1984.cadence.ui.taskList]'s answer rather than one of
 * this widget's own — so the sort chips and the completed-task switch the user set inside the
 * app reach the home screen too. A widget is a shrunk-down view of the app, not a second opinion
 * about what matters.
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

        // Everything the widget reads is read *inside* the composition, because this method runs
        // once per session and a recomposition is all a later update gets — see [widgetUiState].
        provideContent {
            val state = widgetUiState(container)
            val today = LocalDate.now()
            GlanceTheme(colors = CadenceWidgetColors) {
                TaskListContent(context, scope, state?.taskList(scope.view, today)?.tasks, today)
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
    /** `null` until the first emission arrives — "not read yet", not "nothing to do". */
    tasks: List<Task>?,
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
        if (tasks == null) {
            // The header alone, for the frame or two before the database answers: the empty
            // state below says something false, and a blank tile says nothing at all.
            Spacer(GlanceModifier.fillMaxSize())
        } else if (tasks.isEmpty()) {
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
