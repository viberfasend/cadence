package de.andi1984.cadence.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.andi1984.cadence.MainActivity
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.reminders.AlarmReminderScheduler
import java.time.LocalDate

/**
 * One task row, shared by [CadenceTaskListWidget] and [CadenceNextTaskWidget] so the two widgets
 * read as one family rather than two unrelated designs.
 *
 * Priority is never colour alone here either — see `CLAUDE.md`'s UI conventions — so the row
 * always pairs the dot with [de.andi1984.cadence.domain.model.Priority.shortLabel] as text, the
 * same rule `PrioritySpine` follows on every other surface.
 */
@Composable
fun TaskWidgetRow(context: Context, task: Task, today: LocalDate, modifier: GlanceModifier = GlanceModifier) {
    val openTask = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(AlarmReminderScheduler.EXTRA_TASK_ID, task.id)
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(
            checked = task.isDone,
            onCheckedChange = actionRunCallback<ToggleTaskAction>(
                actionParametersOf(TaskIdKey to task.id),
            ),
        )
        Column(
            modifier = GlanceModifier
                .padding(start = 8.dp)
                .clickable(actionStartActivity(openTask)),
        ) {
            Text(
                text = task.title,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Medium,
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
    val due = if (task.isOverdue(today)) {
        context.getString(R.string.widget_overdue)
    } else {
        context.getString(R.string.widget_due_today)
    }
    return "${task.priority.shortLabel} · $due"
}

@Composable
private fun dueColor(task: Task, today: LocalDate): ColorProvider =
    if (task.isOverdue(today)) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant
