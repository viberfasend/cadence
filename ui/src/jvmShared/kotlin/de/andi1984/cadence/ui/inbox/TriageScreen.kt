package de.andi1984.cadence.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceDatePickerDialog
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.PrioritySpine
import de.andi1984.cadence.ui.components.ProjectPickerDialog
import de.andi1984.cadence.ui.components.priorityColor
import de.andi1984.cadence.ui.format.formatDate
import de.andi1984.cadence.ui.format.relativeDate
import java.time.LocalDate

/**
 * Sets importance on the Inbox backlog in one pass — one card at a time, four flags,
 * no scrolling back and forth.
 */
@Composable
fun TriageScreen(
    state: CadenceUiState,
    today: LocalDate,
    onClose: () -> Unit,
    onSetPriority: (Task, Priority) -> Unit,
    onSetDueDate: (Task, LocalDate?) -> Unit,
    onSetProject: (Task, String?) -> Unit,
) {
    // The queue is captured once so editing a task does not reshuffle the run.
    val queue = remember { state.inboxTasks().filter { !it.isDone }.map { it.id } }
    var index by remember { mutableIntStateOf(0) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var projectPickerOpen by remember { mutableStateOf(false) }

    val current = queue.getOrNull(index)?.let { id -> state.tasks.firstOrNull { it.id == id } }
    val ranked = queue.take(index).mapNotNull { id -> state.tasks.firstOrNull { it.id == id } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(AppIcons.Close, contentDescription = stringResource(Res.string.triage_close))
            }
            Text(
                text = stringResource(Res.string.triage_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text(stringResource(Res.string.triage_skip_all)) }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = if (queue.isEmpty()) {
                    stringResource(Res.string.triage_nothing)
                } else {
                    stringResource(
                        Res.string.triage_progress,
                        (index + 1).coerceAtMost(queue.size),
                        queue.size,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { if (queue.isEmpty()) 0f else index.toFloat() / queue.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }

        if (current == null) {
            EmptyState(
                title = if (queue.isEmpty()) {
                    stringResource(Res.string.inbox_empty_title)
                } else {
                    stringResource(Res.string.triage_finished_title)
                },
                supporting = stringResource(Res.string.triage_finished_supporting),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                TriageCard(
                    task = current,
                    today = today,
                    onPriority = { priority ->
                        onSetPriority(current, priority)
                        index += 1
                    },
                    onSetDate = { datePickerOpen = true },
                    onMove = { projectPickerOpen = true },
                )
            }
            if (ranked.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.triage_ranked_so_far),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
                    )
                }
                items(ranked.size, key = { ranked[it].id }) { position ->
                    val task = ranked[position]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PrioritySpine(priority = task.priority, barHeight = 11)
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = task.dueDate?.let { relativeDate(it, today) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (datePickerOpen && current != null) {
        CadenceDatePickerDialog(
            initial = current.dueDate,
            onDismiss = { datePickerOpen = false },
            onPick = { picked -> onSetDueDate(current, picked) },
        )
    }
    if (projectPickerOpen && current != null) {
        ProjectPickerDialog(
            projects = state.projects,
            selectedId = current.projectId,
            onDismiss = { projectPickerOpen = false },
            onPick = { projectId -> onSetProject(current, projectId) },
        )
    }
}

@Composable
private fun TriageCard(
    task: Task,
    today: LocalDate,
    onPriority: (Priority) -> Unit,
    onSetDate: () -> Unit,
    onMove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer)
            .padding(20.dp),
    ) {
        Text(
            text = stringResource(Res.string.triage_from_inbox),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.primary,
        )
        Text(
            text = task.title,
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = AppIcons.Event,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = task.dueDate?.let { formatDate(it) }
                    ?: stringResource(Res.string.triage_no_due_date),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Priority.entries.forEach { priority ->
                val selected = priority == task.priority
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceContainerHigh)
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, scheme.primary, RoundedCornerShape(14.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onPriority(priority) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PrioritySpine(
                        priority = priority,
                        barHeight = 13,
                        overrideColor = priorityColor(priority),
                    )
                    Text(
                        text = priority.shortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TriageAction(
                label = stringResource(Res.string.triage_set_date),
                icon = AppIcons.Event,
                onClick = onSetDate,
                modifier = Modifier.weight(1f),
            )
            TriageAction(
                label = stringResource(Res.string.triage_move),
                icon = AppIcons.Folder,
                onClick = onMove,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TriageAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
        )
    }
}
