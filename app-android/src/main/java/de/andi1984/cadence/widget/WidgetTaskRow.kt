package de.andi1984.cadence.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
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
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * One task row, shared by every list widget so they read as one family rather than as unrelated
 * designs.
 *
 * The row is a rounded card on the widget surface — the shape the launcher's own M3 widgets
 * (Tasks, Keep) settled on, because on a home screen a bare list reads as wallpaper while cards
 * read as touchable. An overdue card is tinted with the error container, the widget's version of
 * the red block the Today screen pins above the day; the completion ring wears the priority
 * colour. Neither colour stands alone (`CLAUDE.md`, UI conventions): the meta line always spells
 * out [de.andi1984.cadence.domain.model.Priority.shortLabel], and "Overdue" is written next to
 * it. `cornerRadius` clips on Android 12+ and quietly draws square corners below — a degrade,
 * not a break.
 *
 * **The toggle is the caller's to wire**, because where the row sits decides which route a tap
 * can take at all. Inside a `LazyColumn` a row is a RemoteViews collection item, and only
 * `actionStartActivity` reliably escapes one — `WidgetIntents.toggleTask`, the invisible
 * [WidgetToggleActivity]. On a plain surface (the next-task widget) [ToggleTaskCallback]'s
 * broadcast is the better route: no window, no activity start. [TaskListWidget] and
 * [CadenceNextTaskWidget] each pass their own.
 */
@Composable
fun TaskWidgetRow(
    context: Context,
    task: Task,
    today: LocalDate,
    toggleAction: Action,
    modifier: GlanceModifier = GlanceModifier,
    card: Boolean = true,
) {
    val overdue = task.isOverdue(today)
    val surface = when {
        !card -> null
        overdue -> GlanceTheme.colors.errorContainer
        else -> GlanceTheme.colors.secondaryContainer
    }
    val titleColor = when {
        task.isDone -> GlanceTheme.colors.onSurfaceVariant
        overdue && card -> GlanceTheme.colors.onErrorContainer
        else -> GlanceTheme.colors.onSurface
    }

    // The 2dp frame is the gap between cards; the card itself is the inner Row.
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
        var row = GlanceModifier.fillMaxWidth().height(48.dp)
        if (surface != null) row = row.background(surface).cornerRadius(14.dp)
        Row(modifier = row, verticalAlignment = Alignment.CenterVertically) {
            Box(
                // 44dp of touch target around a 26dp circle — the same figure
                // `ui/components/TaskRow.kt`'s CompletionCircle keeps, and the same reason.
                modifier = GlanceModifier.size(44.dp).clickable(toggleAction),
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
                    colorFilter = ColorFilter.tint(
                        if (task.isDone) {
                            GlanceTheme.colors.onSurfaceVariant
                        } else {
                            widgetPriorityColor(task.priority)
                        },
                    ),
                    modifier = GlanceModifier.size(26.dp),
                )
            }
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .padding(end = 12.dp)
                    .clickable(actionStartActivity(WidgetIntents.openTask(context, task.id))),
            ) {
                Text(
                    text = task.title,
                    maxLines = 1,
                    style = TextStyle(
                        color = titleColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        // Struck through and dimmed once done, so the tap that completed it
                        // reads as "finished" rather than as the row having been removed.
                        textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.priority.shortLabel,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (task.isDone) {
                                GlanceTheme.colors.onSurfaceVariant
                            } else {
                                widgetPriorityColor(task.priority)
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        text = " · " + dueLabel(context, task, today),
                        maxLines = 1,
                        style = TextStyle(color = metaColor(task, today, card), fontSize = 11.sp),
                    )
                }
            }
        }
    }
}

private fun dueLabel(context: Context, task: Task, today: LocalDate): String {
    val dueTime = task.dueTime
    return when {
        task.isDone -> context.getString(R.string.widget_done)
        task.isOverdue(today) -> context.getString(R.string.widget_overdue)
        task.isDueOn(today) && dueTime != null ->
            // Locale-formatted by java.time, the same source the quick-add grammar trusts for
            // weekday names — a pattern of our own here would be a second clock format to keep
            // in step with the app's.
            dueTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        task.isDueOn(today) -> context.getString(R.string.widget_due_today)
        task.dueDate == null -> context.getString(R.string.widget_no_due_date)
        else -> task.dueDate.toString()
    }
}

@Composable
private fun metaColor(task: Task, today: LocalDate, card: Boolean): ColorProvider = when {
    task.isDone -> GlanceTheme.colors.onSurfaceVariant
    task.isOverdue(today) -> if (card) GlanceTheme.colors.onErrorContainer else GlanceTheme.colors.error
    else -> GlanceTheme.colors.onSurfaceVariant
}
