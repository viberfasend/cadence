package de.andi1984.cadence.widget

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
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
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
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
 * As many tasks as fit, tickable in place, with a quick-add button in its header and an
 * "N more" line that opens the app when the list is longer than the widget.
 *
 * Which tasks, in what order, is [de.andi1984.cadence.ui.taskList]'s answer rather than one of
 * this widget's own — so the sort chips and the completed-task switch the user set inside the
 * app reach the home screen too. A widget is a shrunk-down view of the app, not a second opinion
 * about what matters.
 *
 * **The rows are a plain [Column], not a `LazyColumn`, and that is the reliability decision of
 * this file.** A Glance `LazyColumn` becomes a `ListView` in the launcher, and a `ListView` in a
 * widget is a different, worse contract than a row of views: its items are RemoteViews
 * *collection items*, which own no `PendingIntent` of their own, so a tap inside one has to go
 * through a fill-in intent and a trampoline activity (`actionRunCallback` never arrived from a
 * row on a real device; the completion circle used to launch an invisible activity to get
 * around that); on Android 11 and below the items are served by a `RemoteViewsService` from an
 * in-memory store that dies with the process, so a list redrawn while the process was cold came
 * back as the launcher's "Loading…" rows; and every update resets the scroll. With plain rows,
 * the circle is a broadcast straight to [ToggleTaskCallback], the whole tile is one self-contained
 * `RemoteViews` the launcher keeps across process death, and nothing scrolls — the widget shows
 * what fits and says how much it does not, which is what the next-task widget already did for a
 * list of one.
 *
 * [SizeMode.Responsive] with one size per row count rather than [SizeMode.Exact]: the launcher
 * then holds a composition for every height the widget can be resized to and switches between
 * them on its own, so resizing the widget or rotating the phone needs no round trip into a
 * process that may not be running. `Exact` would recompose on every size change — through
 * WorkManager, with the process cold more often than not.
 */
abstract class TaskListWidget(private val scope: TaskListScope) : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(ROW_LADDER)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // `as?` rather than a hard cast: a widget that throws is reported by the launcher as a
        // crashed widget, so an impossible-in-practice failure degrades to the empty state —
        // which is still a working quick-add button — instead of a broken tile.
        val container = (context.applicationContext as? CadenceApplication)?.container

        // Frame one is drawn from a snapshot read here, and every later frame from the flow
        // collected inside the composition — see [widgetSnapshot] and [widgetUiState] for why
        // each half is needed and what each was once the whole fix for.
        val initial = container.widgetSnapshot()

        // Today's list changes at midnight whether or not anything was written; the alarm armed
        // here is what redraws it then while the process is not around to notice.
        WidgetMidnightRefresh.schedule(context)

        provideContent {
            val state = widgetUiState(container, initial)
            val today = LocalDate.now()
            GlanceTheme(colors = CadenceWidgetColors) {
                TaskListContent(context, scope, state?.taskList(scope.view, today)?.tasks, today)
            }
        }
    }

    companion object {
        /** The header row: title, quick-add button. */
        internal val HEADER_HEIGHT = 48.dp

        /** One task row: a 44dp touch target plus 2dp above and below. */
        internal val ROW_HEIGHT = 48.dp

        /** The most rows a size in [ROW_LADDER] accounts for; taller widgets leave space. */
        private const val MAX_ROWS = 8

        /**
         * One size per row count. The width is below every widget's `minResizeWidth`, so it
         * never decides which size the launcher picks — only the height does, and the height of
         * rung *n* is exactly what *n* rows under the header need.
         */
        internal val ROW_LADDER: Set<DpSize> = (1..MAX_ROWS)
            .map { rows -> DpSize(width = 110.dp, height = HEADER_HEIGHT + ROW_HEIGHT * rows) }
            .toSet()
    }
}

class CadenceTodayWidget : TaskListWidget(TaskListScope.TODAY)

class CadenceInboxWidget : TaskListWidget(TaskListScope.INBOX)

@Composable
private fun TaskListContent(
    context: Context,
    scope: TaskListScope,
    /** `null` only when there is no container to read from — drawn as the header alone. */
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
            FittedRows(context, tasks, today, LocalSize.current.height)
        }
    }
}

/**
 * The rows that fit under the header at [height], and an "N more" line in the last slot when
 * the list is longer than that — so the widget never ends on a row that looks like the last one
 * while more wait behind it.
 */
@Composable
private fun FittedRows(context: Context, tasks: List<Task>, today: LocalDate, height: Dp) {
    val slots = ((height - TaskListWidget.HEADER_HEIGHT) / TaskListWidget.ROW_HEIGHT).toInt()
        .coerceAtLeast(1)
    val overflow = tasks.size > slots
    // A single slot shows one task rather than only a count of what it cannot show.
    val shown = if (overflow && slots > 1) slots - 1 else slots
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        tasks.take(shown).forEach { task ->
            TaskWidgetRow(
                context,
                task,
                today,
                modifier = GlanceModifier.height(TaskListWidget.ROW_HEIGHT),
            )
        }
        if (overflow && slots > 1) {
            val remaining = tasks.size - shown
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(TaskListWidget.ROW_HEIGHT)
                    .clickable(actionStartActivity(WidgetIntents.openApp(context)))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = context.resources.getQuantityString(
                        R.plurals.widget_more_tasks,
                        remaining,
                        remaining,
                    ),
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Header(context: Context, scope: TaskListScope) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(TaskListWidget.HEADER_HEIGHT)
            .padding(start = 12.dp, top = 4.dp, end = 4.dp),
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
