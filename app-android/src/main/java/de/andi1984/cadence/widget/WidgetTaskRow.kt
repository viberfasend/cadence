package de.andi1984.cadence.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import java.time.LocalDate

/**
 * One task row, shared by every list widget so they read as one family rather than as unrelated
 * designs.
 *
 * **The completion circle is a clickable [Box], not Glance's `CheckBox`, and that is the point.**
 * Glance translates a `LazyColumn` into a `ListView`, so everything inside a row is a RemoteViews
 * *collection item*, and a collection item cannot own a `PendingIntent` of its own — the platform
 * only offers a template on the list plus a fill-in intent per item. Glance implements `clickable`
 * that way and it works inside a list; a compound button instead wants
 * `RemoteViews.setOnCheckedChangeResponse`, which is not part of the collection contract. Using
 * the one primitive the collection actually supports is what makes these rows tickable rather
 * than decorative.
 *
 * Priority is never colour alone here either (`CLAUDE.md`, UI conventions): the meta line always
 * spells out [de.andi1984.cadence.domain.model.Priority.shortLabel], and the circle's accent
 * carries no meaning the text does not.
 */
@Composable
fun TaskWidgetRow(
    context: Context,
    task: Task,
    today: LocalDate,
    modifier: GlanceModifier = GlanceModifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            // 44dp of touch target around a 24dp circle — the same figure
            // `ui/components/TaskRow.kt`'s CompletionCircle keeps, and the same reason.
            modifier = GlanceModifier
                .size(44.dp)
                .clickable(
                    actionRunCallback<ToggleTaskAction>(
                        actionParametersOf(TaskIdKey to task.id),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(
                    if (task.isDone) R.drawable.ic_widget_circle_check else R.drawable.ic_widget_circle_ring,
                ),
                contentDescription = context.getString(
                    if (task.isDone) R.string.widget_mark_open else R.string.widget_mark_done,
                    task.title,
                ),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(24.dp),
            )
        }
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(start = 4.dp, end = 4.dp)
                .clickable(actionStartActivity(WidgetIntents.openTask(context, task.id))),
        ) {
            Text(
                text = task.title,
                maxLines = 1,
                style = TextStyle(
                    // Struck through and dimmed once done, so the tap that completed it reads as
                    // "finished" rather than as the row having been removed.
                    color = if (task.isDone) {
                        GlanceTheme.colors.onSurfaceVariant
                    } else {
                        GlanceTheme.colors.onSurface
                    },
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                ),
            )
            Text(
                text = taskMetaLine(context, task, today),
                maxLines = 1,
                style = TextStyle(color = dueColor(task, today)),
            )
        }
    }
}

private fun taskMetaLine(context: Context, task: Task, today: LocalDate): String {
    val due = when {
        task.isDone -> context.getString(R.string.widget_done)
        task.isOverdue(today) -> context.getString(R.string.widget_overdue)
        task.isDueOn(today) -> context.getString(R.string.widget_due_today)
        task.dueDate == null -> context.getString(R.string.widget_no_due_date)
        else -> task.dueDate.toString()
    }
    return "${task.priority.shortLabel} · $due"
}

@Composable
private fun dueColor(task: Task, today: LocalDate): ColorProvider =
    if (task.isOverdue(today)) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant
