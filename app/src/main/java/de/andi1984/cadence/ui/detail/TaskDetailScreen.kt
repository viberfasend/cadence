package de.andi1984.cadence.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.SubtaskProgress
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import de.andi1984.cadence.ui.format.describeRecurrence
import de.andi1984.cadence.ui.format.explanation
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceDatePickerDialog
import de.andi1984.cadence.ui.components.CadenceTimePickerDialog
import de.andi1984.cadence.ui.components.CompletionCircle
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ProjectPickerDialog
import de.andi1984.cadence.ui.components.ProjectSwatch
import de.andi1984.cadence.ui.components.SegmentedRow
import de.andi1984.cadence.ui.components.TaskRow
import de.andi1984.cadence.ui.components.priorityColor
import de.andi1984.cadence.ui.format.formatDate
import de.andi1984.cadence.ui.format.formatTime
import de.andi1984.cadence.ui.format.overdueByDays
import de.andi1984.cadence.ui.format.relativeDate
import de.andi1984.cadence.ui.recurrence.RecurrenceSheet
import de.andi1984.cadence.ui.theme.LocalCadenceColors
import java.time.LocalDate

@Composable
fun TaskDetailScreen(
    task: Task?,
    state: CadenceUiState,
    today: LocalDate,
    onBack: () -> Unit,
    onSave: (Task) -> Unit,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onSnooze: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
    onAddSubtask: (Task, String) -> Unit,
    onMoveToProject: (Task, Long?) -> Unit,
) {
    if (task == null) {
        EmptyState(
            title = stringResource(R.string.task_not_found_title),
            supporting = stringResource(R.string.deleted_supporting),
        )
        return
    }

    val scheme = MaterialTheme.colorScheme
    val cadenceColors = LocalCadenceColors.current

    var title by remember(task.id) { mutableStateOf(task.title) }
    var notes by remember(task.id) { mutableStateOf(task.notes.orEmpty()) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var reminderPickerOpen by remember { mutableStateOf(false) }
    var projectPickerOpen by remember { mutableStateOf(false) }
    var recurrenceOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val overdue = task.isOverdue(today)
    val projectLabel = projectPath(state.project(task.projectId), state.projects)
        ?: stringResource(R.string.inbox_title)
    val parent = state.parentOf(task)
    val subtasks = state.subtasks(task.id)

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { onDelete(task) }) {
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.task_delete))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_move_to_project)) },
                        onClick = {
                            menuOpen = false
                            projectPickerOpen = true
                        },
                        leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_set_time)) },
                        onClick = {
                            menuOpen = false
                            timePickerOpen = true
                        },
                        leadingIcon = { Icon(AppIcons.Schedule, contentDescription = null) },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (parent != null) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .defaultMinSize(minHeight = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceContainerHigh)
                        .clickable { onOpenTask(parent) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.ParentTask,
                        contentDescription = stringResource(R.string.subtasks_open_parent),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.subtasks_part_of, parent.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CompletionCircle(
                    done = task.isDone,
                    accent = if (overdue) cadenceColors.overdueAccent else priorityColor(task.priority),
                    size = 28.dp,
                    onToggle = { onToggle(task) },
                    stateLabel = if (task.isDone) {
                        stringResource(R.string.task_state_done)
                    } else {
                        stringResource(R.string.task_state_not_done)
                    },
                    title = task.title,
                )
                BasicTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        onSave(task.copy(title = it))
                    },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp, start = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerHigh)
                    .clickable { projectPickerOpen = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProjectSwatch(
                    colorHex = state.project(task.projectId)?.colorHex ?: "#6F7976",
                    size = 10,
                )
                Text(
                    text = projectLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                Icon(
                    imageVector = AppIcons.UnfoldMore,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            Text(
                text = stringResource(R.string.task_importance),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.primary,
                modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
            )
            SegmentedRow(
                options = Priority.entries.map { it.shortLabel },
                selectedIndex = task.priority.ordinal,
                onSelect = { index -> onSave(task.copy(priority = Priority.entries[index])) },
            )
            Text(
                text = task.priority.explanation(),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.surfaceContainer),
            ) {
                DetailRow(
                    icon = AppIcons.Event,
                    title = task.dueDate
                        ?.let { stringResource(R.string.task_due, relativeDate(it, today)) }
                        ?: stringResource(R.string.task_no_due_date),
                    supporting = when {
                        task.dueDate == null -> stringResource(R.string.task_tap_to_schedule)
                        overdue -> overdueByDays(task.dueDate, today)
                        else -> formatDate(task.dueDate)
                    },
                    supportingColor = if (overdue) scheme.error else scheme.onSurfaceVariant,
                    trailingIcon = AppIcons.EditCalendar,
                    onClick = { datePickerOpen = true },
                )
                DetailDivider()
                DetailRow(
                    icon = AppIcons.Notifications,
                    title = task.reminderTime
                        ?.let { stringResource(R.string.task_remind_at, formatTime(it)) }
                        ?: stringResource(R.string.task_no_reminder),
                    supporting = if (task.reminderTime != null) {
                        stringResource(R.string.task_on_the_due_day)
                    } else {
                        stringResource(R.string.task_tap_to_add_reminder)
                    },
                    onClick = { reminderPickerOpen = true },
                )
                DetailDivider()
                DetailRow(
                    icon = AppIcons.EventRepeat,
                    title = task.recurrence
                        ?.let { describeRecurrence(it) }
                        ?: stringResource(R.string.task_does_not_repeat),
                    supporting = task.recurrence?.let { rule ->
                        val next = RecurrenceEngine.nextAfter(rule, task.dueDate ?: today)
                        stringResource(R.string.task_next_occurrence, relativeDate(next, today))
                    } ?: stringResource(R.string.task_recurrence_supporting),
                    highlighted = task.recurrence != null,
                    trailingIcon = AppIcons.Tune,
                    onClick = { recurrenceOpen = true },
                )
            }

            // Only a top-level task gets a checklist: nesting stops after one level, so a
            // subtask shows its parent instead of a card of its own.
            if (!task.isSubtask) {
                SubtaskCard(
                    subtasks = subtasks,
                    today = today,
                    onToggle = onToggle,
                    onOpen = onOpenTask,
                    onDelete = onDelete,
                    onAdd = { text -> onAddSubtask(task, text) },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.surfaceContainer)
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.task_notes),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                BasicTextField(
                    value = notes,
                    onValueChange = {
                        notes = it
                        onSave(task.copy(notes = it.ifBlank { null }))
                    },
                    textStyle = LocalTextStyle.current.copy(
                        color = scheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                    ),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    decorationBox = { inner ->
                        if (notes.isEmpty()) {
                            Text(
                                text = stringResource(R.string.task_notes_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.background)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, scheme.outline, RoundedCornerShape(18.dp))
                    .clickable { onSnooze(task) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Snooze,
                    contentDescription = stringResource(R.string.task_snooze),
                    tint = scheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (task.isDone) scheme.surfaceContainerHigh else scheme.primary)
                    .clickable { onToggle(task) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = if (task.isDone) scheme.onSurface else scheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = if (task.isDone) {
                        stringResource(R.string.task_mark_not_done)
                    } else {
                        stringResource(R.string.task_mark_done)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (task.isDone) scheme.onSurface else scheme.onPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    if (datePickerOpen) {
        CadenceDatePickerDialog(
            initial = task.dueDate,
            onDismiss = { datePickerOpen = false },
            onPick = { onSave(task.copy(dueDate = it)) },
        )
    }
    if (timePickerOpen) {
        CadenceTimePickerDialog(
            initial = task.dueTime,
            title = stringResource(R.string.task_due_at_picker),
            onDismiss = { timePickerOpen = false },
            onPick = { onSave(task.copy(dueTime = it)) },
        )
    }
    if (reminderPickerOpen) {
        CadenceTimePickerDialog(
            initial = task.reminderTime,
            title = stringResource(R.string.task_remind_at_picker),
            onDismiss = { reminderPickerOpen = false },
            onPick = { onSave(task.copy(reminderTime = it)) },
        )
    }
    if (projectPickerOpen) {
        ProjectPickerDialog(
            projects = state.projects,
            selectedId = task.projectId,
            onDismiss = { projectPickerOpen = false },
            // Not onSave: the subtasks follow their task into the new project.
            onPick = { onMoveToProject(task, it) },
        )
    }
    if (recurrenceOpen) {
        RecurrenceSheet(
            initial = task.recurrence,
            taskTitle = task.title,
            anchorDate = task.dueDate ?: today,
            onDismiss = { recurrenceOpen = false },
            onSave = { rule ->
                onSave(task.copy(recurrence = rule))
                recurrenceOpen = false
            },
        )
    }
}

/**
 * The checklist under a task: what is left, one row per step, and a field that keeps the focus
 * so several steps can be typed in a row.
 */
@Composable
private fun SubtaskCard(
    subtasks: List<Task>,
    today: LocalDate,
    onToggle: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onAdd: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val progress = SubtaskProgress(done = subtasks.count { it.isDone }, total = subtasks.size)
    val spokenProgress =
        stringResource(R.string.subtasks_progress_label, progress.done, progress.total)
    var draft by remember { mutableStateOf("") }

    fun submit() {
        if (draft.isNotBlank()) {
            onAdd(draft)
            draft = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer)
            .padding(vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = AppIcons.Checklist,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.subtasks_title),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (subtasks.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.subtasks_progress,
                        progress.done,
                        progress.total,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (progress.isComplete) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = spokenProgress
                    },
                )
            }
        }

        if (subtasks.isEmpty()) {
            Text(
                text = stringResource(R.string.subtasks_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
        } else {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
            Text(
                text = stringResource(R.string.subtasks_completion_note),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            subtasks.forEach { subtask ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A subtask is a task like any other, so it gets the same row the rest of
                    // the app uses — same checkbox, same priority spine, same due chip.
                    TaskRow(
                        task = subtask,
                        projectLabel = null,
                        today = today,
                        onToggle = { onToggle(subtask) },
                        onClick = { onOpen(subtask) },
                        showProject = false,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onDelete(subtask) }) {
                        Icon(
                            imageVector = AppIcons.Close,
                            contentDescription = stringResource(R.string.subtasks_remove),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp)
                .defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.Add,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = LocalTextStyle.current.copy(
                    color = scheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                ),
                cursorBrush = SolidColor(scheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(
                            text = stringResource(R.string.subtasks_add_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
            if (draft.isNotBlank()) {
                // A checkmark here would read as "mark done" right next to rows that use exactly
                // that icon for exactly that — a text button says "submit" without borrowing a
                // meaning that already belongs to something else on this screen.
                TextButton(onClick = { submit() }) {
                    Text(stringResource(R.string.subtasks_add))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = null,
    highlighted: Boolean = false,
    supportingColor: androidx.compose.ui.graphics.Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val background = if (highlighted) scheme.secondaryContainer else scheme.surfaceContainer
    val foreground = if (highlighted) scheme.onSecondaryContainer else scheme.onSurface
    val secondary = supportingColor
        ?: if (highlighted) scheme.onSecondaryContainer else scheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = foreground,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
            }
        }
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = if (highlighted) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun DetailDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
