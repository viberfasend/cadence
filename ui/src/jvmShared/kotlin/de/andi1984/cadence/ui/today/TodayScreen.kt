package de.andi1984.cadence.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import de.andi1984.cadence.ui.BandHeading
import de.andi1984.cadence.ui.TaskView
import de.andi1984.cadence.ui.taskList
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ScreenHeader
import de.andi1984.cadence.ui.components.SyncActions
import de.andi1984.cadence.ui.components.SyncControls
import de.andi1984.cadence.ui.components.SyncRefreshBox
import de.andi1984.cadence.ui.components.SectionHeader
import de.andi1984.cadence.ui.components.TaskRow
import de.andi1984.cadence.ui.dnd.DropCaret
import de.andi1984.cadence.ui.dnd.DropTarget
import de.andi1984.cadence.ui.dnd.OrderedList
import de.andi1984.cadence.ui.format.formatDate
import de.andi1984.cadence.ui.format.pluralTasks
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.theme.LocalCadenceColors
import java.time.LocalDate

@Composable
fun TodayScreen(
    state: CadenceUiState,
    today: LocalDate,
    onTaskClick: (Task) -> Unit,
    onToggle: (Task) -> Unit,
    onRescheduleAll: () -> Unit,
    onSortChange: (SortMode) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    syncControls: SyncControls = SyncControls(),
) {
    val sortMode = state.settings.sortMode
    val list = state.taskList(TaskView.Today, today)
    val overdue = list.band(BandHeading.Overdue)?.tasks.orEmpty()
    val dueToday = list.band(BandHeading.None)?.tasks.orEmpty()
    val openCount = list.openCount

    var sortMenuOpen by remember { mutableStateOf(false) }
    var overdueExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(Res.string.today_title),
            subtitle = "${formatDate(today)} · ${pluralTasks(openCount)}",
        ) {
            SyncActions(status = state.sync.status, controls = syncControls)
            IconButton(onClick = onSearch) {
                Icon(AppIcons.Search, contentDescription = stringResource(Res.string.action_search))
            }
            Column {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(
                        AppIcons.Sort,
                        contentDescription = stringResource(Res.string.action_sort_and_settings),
                    )
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false },
                ) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(stringResource(mode.label)) },
                            onClick = {
                                onSortChange(mode)
                                sortMenuOpen = false
                            },
                            leadingIcon = {
                                if (mode == sortMode) {
                                    Icon(AppIcons.Check, contentDescription = null)
                                }
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_settings)) },
                        onClick = {
                            sortMenuOpen = false
                            onSettings()
                        },
                        leadingIcon = { Icon(AppIcons.Settings, contentDescription = null) },
                    )
                }
            }
        }

        SyncRefreshBox(status = state.sync.status, controls = syncControls) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 12.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (overdue.isNotEmpty()) {
                    item(key = "overdue") {
                        OverdueBlock(
                            tasks = overdue,
                            state = state,
                            today = today,
                            expanded = overdueExpanded,
                            onExpand = { overdueExpanded = true },
                            onRescheduleAll = onRescheduleAll,
                            onTaskClick = onTaskClick,
                            onToggle = onToggle,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                if (dueToday.isEmpty() && overdue.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(Res.string.today_empty_title),
                            supporting = stringResource(Res.string.today_empty_supporting),
                        )
                    }
                } else {
                    item(key = "due-today-header") {
                        SectionHeader(stringResource(Res.string.today_section_due))
                    }
                    // Today spans every project, so a drop between two rows changes the order and
                    // nothing else — `LooseTasks`. A task filed under Home must not leave Home
                    // because it was dragged up one line here.
                    val dueTodayIds = dueToday.map { it.id }
                    dueToday.forEachIndexed { index, task ->
                        item(key = "caret-$index") {
                            DropCaret(
                                key = "today:caret:$index",
                                target = DropTarget.Between(OrderedList.LooseTasks, index, dueTodayIds),
                            )
                        }
                        item(key = task.id) {
                            TaskRow(
                                task = task,
                                projectLabel = state.projectLabel(task),
                                today = today,
                                onToggle = { onToggle(task) },
                                onClick = { onTaskClick(task) },
                                parentTitle = state.parentOf(task)?.title,
                                subtaskProgress = state.subtaskProgress(task.id),
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    item(key = "caret-end") {
                        DropCaret(
                            key = "today:caret:end",
                            target = DropTarget.Between(
                                OrderedList.LooseTasks,
                                dueTodayIds.size,
                                dueTodayIds,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** The red block pinned above Today. */
@Composable
private fun OverdueBlock(
    tasks: List<Task>,
    state: CadenceUiState,
    today: LocalDate,
    expanded: Boolean,
    onExpand: () -> Unit,
    onRescheduleAll: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onToggle: (Task) -> Unit,
) {
    val cadenceColors = LocalCadenceColors.current
    val visible = if (expanded) tasks else tasks.take(2)
    val hidden = tasks.size - visible.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cadenceColors.overdueBlock)
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = AppIcons.Error,
                contentDescription = null,
                tint = cadenceColors.overdueAccent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(Res.string.today_overdue_header, tasks.size),
                style = MaterialTheme.typography.titleSmall,
                color = cadenceColors.onOverdue,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, cadenceColors.overdueAccent, RoundedCornerShape(16.dp))
                    .clickable(onClick = onRescheduleAll)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.today_reschedule_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = cadenceColors.onOverdue,
                )
            }
        }

        visible.forEach { task ->
            TaskRow(
                task = task,
                projectLabel = state.projectLabel(task),
                today = today,
                onToggle = { onToggle(task) },
                onClick = { onTaskClick(task) },
                overdueStyle = true,
                parentTitle = state.parentOf(task)?.title,
                subtaskProgress = state.subtaskProgress(task.id),
            )
        }

        if (hidden > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onExpand),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pluralStringResource(Res.plurals.today_show_more, hidden, hidden),
                    style = MaterialTheme.typography.labelLarge,
                    color = cadenceColors.onOverdue,
                )
            }
        }
    }
}
