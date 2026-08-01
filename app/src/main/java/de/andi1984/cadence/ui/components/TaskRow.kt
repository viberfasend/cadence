package de.andi1984.cadence.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import de.andi1984.cadence.ui.format.compactDate
import de.andi1984.cadence.ui.format.formatTime
import de.andi1984.cadence.ui.format.relativeDate
import de.andi1984.cadence.ui.theme.LocalCadenceColors
import de.andi1984.cadence.ui.theme.LocalCadenceDensity
import java.time.LocalDate

/**
 * A task row in either density.
 *
 * Comfortable (1a): 64dp minimum, title plus a wrapping meta line.
 * Compact (1b): 52dp, single-line title with the spine and a short date on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    task: Task,
    projectLabel: String?,
    today: LocalDate,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overdueStyle: Boolean = false,
    showProject: Boolean = true,
) {
    val cadenceColors = LocalCadenceColors.current
    val density = LocalCadenceDensity.current
    val scheme = MaterialTheme.colorScheme
    val overdue = overdueStyle || task.isOverdue(today)

    val accent = when {
        overdue -> cadenceColors.overdueAccent
        task.priority.filledBars >= 2 -> priorityColor(task.priority)
        else -> scheme.outline
    }
    val container = when {
        overdue -> cadenceColors.overdueRow
        else -> scheme.surfaceContainer
    }
    val titleColor = if (overdue) cadenceColors.onOverdue else scheme.onSurface
    val metaColor = scheme.onSurfaceVariant

    val recurrenceText = task.recurrence?.let { RecurrenceEngine.describe(it) }
    val stateLabel = if (task.isDone) "Done" else "Not done"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = density.rowMinHeight)
            .padding(
                start = 4.dp,
                end = 14.dp,
                top = density.rowVerticalPadding,
                bottom = density.rowVerticalPadding,
            )
            .alpha(if (task.isDone) 0.55f else 1f),
        verticalAlignment = if (density.showMetaLine) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CompletionCircle(
            done = task.isDone,
            accent = accent,
            size = density.checkboxSize,
            onToggle = onToggle,
            stateLabel = stateLabel,
            title = task.title,
        )

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = if (density.showMetaLine) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                )

                if (density.showMetaLine) {
                    if (task.isDone) {
                        val doneAt = task.completedAt
                            ?.atZone(java.time.ZoneId.systemDefault())
                            ?.toLocalTime()
                        Text(
                            text = buildString {
                                append("Done")
                                doneAt?.let { append(" ${formatTime(it)}") }
                                recurrenceText?.let { append(" · repeats ${it.lowercase()}") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = metaColor,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            DueChip(task = task, today = today, overdue = overdue)
                            PriorityBadge(
                                priority = task.priority,
                                labelColor = metaColor,
                                spineColor = if (overdue) cadenceColors.overdueAccent else null,
                            )
                            if (showProject && projectLabel != null) {
                                Text(
                                    text = projectLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = metaColor,
                                )
                            }
                        }
                    }
                }
            }

            if (!density.showMetaLine) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (recurrenceText != null && !task.isDone) {
                        Icon(
                            imageVector = AppIcons.EventRepeat,
                            contentDescription = recurrenceText,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    PriorityBadge(
                        priority = task.priority,
                        labelColor = metaColor,
                        spineColor = if (overdue) cadenceColors.overdueAccent else null,
                    )
                    task.dueDate?.let { due ->
                        Text(
                            text = compactDate(due, task.dueTime, today),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) cadenceColors.overdueAccent else metaColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DueChip(task: Task, today: LocalDate, overdue: Boolean) {
    val cadenceColors = LocalCadenceColors.current
    val scheme = MaterialTheme.colorScheme
    val tint = if (overdue) cadenceColors.overdueAccent else scheme.onSurfaceVariant

    val recurrence = task.recurrence
    val due = task.dueDate

    val (icon, label) = when {
        overdue && due != null -> AppIcons.EventBusy to relativeDate(due, today)
        due == today && task.dueTime != null -> AppIcons.Schedule to formatTime(task.dueTime)
        recurrence != null -> AppIcons.EventRepeat to RecurrenceEngine.describe(recurrence)
        due != null -> AppIcons.Event to relativeDate(due, today)
        else -> return
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

@Composable
fun CompletionCircle(
    done: Boolean,
    accent: Color,
    size: androidx.compose.ui.unit.Dp,
    onToggle: () -> Unit,
    stateLabel: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                role = Role.Checkbox,
                onClick = onToggle,
            )
            .semantics {
                contentDescription = title
                stateDescription = stateLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .then(
                    if (done) {
                        Modifier.background(accent)
                    } else {
                        Modifier.border(2.dp, accent, CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = scheme.surface,
                    modifier = Modifier.size(size * 0.66f),
                )
            }
        }
    }
}
