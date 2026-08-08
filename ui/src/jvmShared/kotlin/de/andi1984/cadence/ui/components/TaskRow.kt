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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.model.SubtaskProgress
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.format.compactDate
import de.andi1984.cadence.ui.format.describeRecurrence
import de.andi1984.cadence.ui.format.describeRecurrenceInline
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
    /** Title of the task this one is a step of — set in the date-driven lists, null elsewhere. */
    parentTitle: String? = null,
    /** "2/5" for a task with a checklist, null for one without. */
    subtaskProgress: SubtaskProgress? = null,
    /** Whether this row's subtasks are currently shown inline below it. */
    expanded: Boolean = false,
    /** Set only where a row can reveal its subtasks inline — renders the expand toggle. */
    onExpandToggle: (() -> Unit)? = null,
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

    val recurrenceText = task.recurrence?.let { describeRecurrence(it) }
    val recurrenceInline = task.recurrence?.let { describeRecurrenceInline(it) }
    val stateLabel = if (task.isDone) {
        stringResource(Res.string.task_state_done)
    } else {
        stringResource(Res.string.task_state_not_done)
    }

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
                        val doneLabel = if (doneAt != null) {
                            stringResource(Res.string.task_done_at, formatTime(doneAt))
                        } else {
                            stringResource(Res.string.task_state_done)
                        }
                        Text(
                            text = if (recurrenceInline != null) {
                                stringResource(
                                    Res.string.task_done_repeats,
                                    doneLabel,
                                    recurrenceInline,
                                )
                            } else {
                                doneLabel
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
                            if (subtaskProgress != null) {
                                SubtaskChip(progress = subtaskProgress, tint = metaColor)
                            }
                            if (parentTitle != null) {
                                MetaWithIcon(
                                    icon = AppIcons.ParentTask,
                                    label = parentTitle,
                                    tint = metaColor,
                                )
                            }
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
                    if (subtaskProgress != null) {
                        SubtaskChip(progress = subtaskProgress, tint = metaColor)
                    }
                    // "Belongs to a parent" — same icon and meaning as the pill on the task's own
                    // detail screen. Deliberately not AppIcons.Checklist: that icon already means
                    // "this task has subtasks" on the chip above, and reusing it here for the
                    // opposite relationship (this task IS a subtask) read as the same badge twice.
                    if (parentTitle != null && task.isSubtask) {
                        Icon(
                            imageVector = AppIcons.ParentTask,
                            contentDescription = stringResource(Res.string.subtasks_part_of, parentTitle),
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

        if (onExpandToggle != null) {
            ExpandToggle(expanded = expanded, onToggle = onExpandToggle)
        }
    }
}

/** Reveals or hides a task's subtasks inline below it — a 44dp target of its own, apart from the checkbox. */
@Composable
private fun ExpandToggle(expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "subtask-expand-rotation",
    )
    val label = stringResource(
        if (expanded) Res.string.subtasks_collapse else Res.string.subtasks_expand,
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.ExpandMore,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer(rotationZ = rotation),
        )
    }
}

/**
 * "2/5" — never the bare bar alone, so the count reads the same to a screen reader as it does
 * on screen.
 */
@Composable
private fun SubtaskChip(progress: SubtaskProgress, tint: Color) {
    val label = stringResource(Res.string.subtasks_progress, progress.done, progress.total)
    val spoken = stringResource(Res.string.subtasks_progress_label, progress.done, progress.total)
    Row(
        modifier = Modifier.semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Checklist,
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
private fun MetaWithIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DueChip(task: Task, today: LocalDate, overdue: Boolean) {
    val cadenceColors = LocalCadenceColors.current
    val scheme = MaterialTheme.colorScheme
    val tint = if (overdue) cadenceColors.overdueAccent else scheme.onSurfaceVariant

    val due = task.dueDate
    val dueTime = task.dueTime
    val recurrenceText = task.recurrence?.let { describeRecurrence(it) }

    // The third value is what the icon says out loud: it is decorative wherever the label
    // already carries the whole story, and speaks up where it is the only thing left saying
    // "this repeats".
    val (icon, label, spokenIcon) = when {
        overdue && due != null -> Triple(AppIcons.EventBusy, relativeDate(due, today), null)
        due == today && dueTime != null ->
            Triple(AppIcons.Schedule, formatTime(dueTime), null)
        // A dated occurrence says *when* it is due, even though it repeats: the repeat icon
        // carries the "it comes back" half. Without the date two occurrences of the same task
        // read identically, which is how a duplicate used to hide in plain sight.
        recurrenceText != null && due != null ->
            Triple(AppIcons.EventRepeat, relativeDate(due, today), recurrenceText)
        recurrenceText != null -> Triple(AppIcons.EventRepeat, recurrenceText, null)
        due != null -> Triple(AppIcons.Event, relativeDate(due, today), null)
        else -> return
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = spokenIcon,
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
